package org.aclpe.core.env.bootstrap

import kotlinx.coroutines.flow.firstOrNull
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.env.fsm.EnvEvent
import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.fsm.EnvStateMachine
import org.aclpe.core.env.fsm.EnvTransition
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.EnvProbeReport
import org.aclpe.core.env.probe.EnvScope
import org.aclpe.core.env.probe.JreProbeResult
import org.aclpe.core.env.store.EnvItemEntity
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.model.ComponentProbeResult

/** 组件下载规格（由调用方提供：Step 2 起从 JRE/资源清单解析而来）。 */
data class EnvDownloadSpec(val url: String, val sha256: String, val sizeBytes: Long? = null)

/**
 * 环境补齐编排（设计文档 §5）：探测 → 与 DB reconcile → 缺失/损坏项入队下载。
 * 幂等：重复执行安全，状态机保证已在途任务不被重复入队；
 * 恢复：状态全部落 DB，进程被杀后下次启动的 reconcile 即为续跑入口。
 * 协程作用域由调用方注入，本类不持有任何作用域。
 */
class EnvBootstrap(
    private val probe: EnvProbe,
    private val scheduler: DownloadScheduler,
    private val store: EnvStore,
    private val layout: EnvLayout,
) {

    /**
     * 启动 reconcile。返回探测报告；缺失/损坏项若 [downloadSpecs] 提供了规格则自动入队。
     */
    suspend fun reconcile(scope: EnvScope, downloadSpecs: Map<String, EnvDownloadSpec>): AppResult<EnvProbeReport> =
        try {
            val report: EnvProbeReport = probe.probe(scope, layout)
            val now: Long = System.currentTimeMillis()

            report.jres.forEach { (major, result) ->
                reconcileComponent(
                    component = EnvItemEntity.jre(major),
                    event = result.toEvent(),
                    now = now,
                    spec = downloadSpecs[EnvItemEntity.jre(major)],
                )
            }
            report.natives.forEach { (hash, result) ->
                reconcileComponent(
                    component = EnvItemEntity.natives(hash),
                    event = result.toEvent(),
                    now = now,
                    spec = downloadSpecs[EnvItemEntity.natives(hash)],
                )
            }
            report.assetsIndexes.keys.forEach { indexId ->
                val result: ComponentProbeResult = report.assetsIndexes.getValue(indexId)
                reconcileComponent(
                    component = EnvItemEntity.assets(indexId),
                    event = result.toEvent(),
                    now = now,
                    spec = downloadSpecs[EnvItemEntity.assets(indexId)],
                )
            }
            AppResult.Ok(report)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Env("env_reconcile_failed", cause = t))
        }

    /** 幂等清理 downloads/tmp（设计文档 §5.3）：在途任务 .part 保留，其余删除。 */
    suspend fun cleanupTmp() {
        val active: Set<String> = store.observeActiveComponentSet()
        val (toDelete, _) = org.aclpe.core.env.store.TmpCleaner.plan(layout.downloadsTmp, active)
        toDelete.forEach { it.deleteRecursively() }
    }

    private suspend fun reconcileComponent(component: String, event: EnvEvent, now: Long, spec: EnvDownloadSpec?) {
        val current: EnvItemEntity? = store.get(component)
        val state: EnvItemState = current?.let { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() } ?: EnvItemState.UNKNOWN
        val retryCount: Int = current?.retryCount ?: 0

        // 在途任务由调度器推进（进度/成功/失败事件回写），reconcile 不打扰 → 幂等且不打断续传
        if (state in ACTIVE_STATES) return

        val transition: EnvTransition = EnvStateMachine.next(state, event, retryCount)

        val updated: EnvItemEntity = EnvItemEntity(
            component = component,
            state = transition.newState.name,
            buildId = current?.buildId,
            sha256 = current?.sha256,
            sizeBytes = current?.sizeBytes,
            progressBytes = current?.progressBytes ?: 0L,
            retryCount = (retryCount + transition.retryDelta).coerceAtLeast(0),
            errorCode = when {
                transition.newState == EnvItemState.READY -> null
                spec == null && transition.newState in NEEDS_DOWNLOAD -> "no_download_spec"
                else -> current?.errorCode
            },
            updatedAt = now,
        )
        store.upsert(updated)

        if (transition.newState in NEEDS_DOWNLOAD && spec != null) {
            scheduler.enqueue(EnvDownloadTask(component, spec.url, spec.sha256, spec.sizeBytes))
            val queued: EnvTransition = EnvStateMachine.next(transition.newState, EnvEvent.Enqueued, updated.retryCount)
            store.upsert(updated.copy(state = queued.newState.name, retryCount = (updated.retryCount + queued.retryDelta).coerceAtLeast(0)))
        }
    }

    private val NEEDS_DOWNLOAD: Set<EnvItemState> = setOf(EnvItemState.MISSING, EnvItemState.CORRUPTED)

    private val ACTIVE_STATES: Set<EnvItemState> = setOf(
        EnvItemState.QUEUED, EnvItemState.DOWNLOADING, EnvItemState.PAUSED,
        EnvItemState.VERIFYING, EnvItemState.INSTALLING,
    )

    private fun JreProbeResult.toEvent(): EnvEvent = when (this) {
        is JreProbeResult.Ok -> EnvEvent.ProbeReady
        JreProbeResult.Missing -> EnvEvent.ProbeMissing
        is JreProbeResult.Partial -> EnvEvent.ProbePartial
        is JreProbeResult.ExecFailed,
        is JreProbeResult.WrongVersion,
        JreProbeResult.Not64Bit,
        JreProbeResult.NoServerCompiler,
        -> EnvEvent.ProbeCorrupted
    }

    private fun ComponentProbeResult.toEvent(): EnvEvent = when (this) {
        is ComponentProbeResult.Ok -> EnvEvent.ProbeReady
        is ComponentProbeResult.Missing -> EnvEvent.ProbeMissing
        is ComponentProbeResult.Partial -> EnvEvent.ProbePartial
        is ComponentProbeResult.Corrupted -> EnvEvent.ProbeCorrupted
    }
}

/** 存储扩展：读取「活动（在途）组件」集合，供 tmp 清理判断 .part 去留。 */
private suspend fun EnvStore.observeActiveComponentSet(): Set<String> {
    val active: Set<EnvItemState> = setOf(
        EnvItemState.QUEUED, EnvItemState.DOWNLOADING, EnvItemState.PAUSED,
        EnvItemState.VERIFYING, EnvItemState.INSTALLING, EnvItemState.FAILED,
    )
    return observe().firstOrNull().orEmpty()
        .filter { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() in active }
        .map { it.component }
        .toSet()
}
