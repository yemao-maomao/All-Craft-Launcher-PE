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
import timber.log.Timber
import org.aclpe.core.env.store.EnvStore
import org.aclpe.core.env.model.ComponentProbeResult

/** 缁勪欢涓嬭浇瑙勬牸锛堢敱璋冪敤鏂规彁渚涳細Step 2 璧蜂粠 JRE/璧勬簮娓呭崟瑙ｆ瀽鑰屾潵锛夈€?*/
data class EnvDownloadSpec(val url: String, val sha256: String, val sizeBytes: Long? = null)

/**
 * 鐜琛ラ綈缂栨帓锛堣璁℃枃妗?搂5锛夛細鎺㈡祴 鈫?涓?DB reconcile 鈫?缂哄け/鎹熷潖椤瑰叆闃熶笅杞姐€? * 骞傜瓑锛氶噸澶嶆墽琛屽畨鍏紝鐘舵€佹満淇濊瘉宸插湪閫斾换鍔′笉琚噸澶嶅叆闃燂紱
 * 鎭㈠锛氱姸鎬佸叏閮ㄨ惤 DB锛岃繘绋嬭鏉€鍚庝笅娆″惎鍔ㄧ殑 reconcile 鍗充负缁窇鍏ュ彛銆? * 鍗忕▼浣滅敤鍩熺敱璋冪敤鏂规敞鍏ワ紝鏈被涓嶆寔鏈変换浣曚綔鐢ㄥ煙銆? */
class EnvBootstrap(
    private val probe: EnvProbe,
    private val scheduler: DownloadScheduler,
    private val store: EnvStore,
    private val layout: EnvLayout,
) {

    /** 单调时钟（毫秒）：JVM 单测与 Android 通用（不引 android.os.SystemClock）。 */
    private fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    /**
     * 鍚姩 reconcile銆傝繑鍥炴帰娴嬫姤鍛婏紱缂哄け/鎹熷潖椤硅嫢 [downloadSpecs] 鎻愪緵浜嗚鏍煎垯鑷姩鍏ラ槦銆?     */
    suspend fun reconcile(scope: EnvScope, downloadSpecs: Map<String, EnvDownloadSpec>): AppResult<EnvProbeReport> =
        try {
            val startedAt: Long = elapsedRealtime()
            val report: EnvProbeReport = probe.probe(scope, layout)
            Timber.d("env reconcile: probe done in %dms, jres=%s natives=%s assets=%s",
                elapsedRealtime() - startedAt,
                report.jres.entries.joinToString { (major, r) -> "$major:${r::class.simpleName}" },
                report.natives.size, report.assetsIndexes.size)
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

    /** 骞傜瓑娓呯悊 downloads/tmp锛堣璁℃枃妗?搂5.3锛夛細鍦ㄩ€斾换鍔?.part 淇濈暀锛屽叾浣欏垹闄ゃ€?*/
    suspend fun cleanupTmp() {
        val active: Set<String> = store.observeActiveComponentSet()
        val (toDelete, _) = org.aclpe.core.env.store.TmpCleaner.plan(layout.downloadsTmp, active)
        toDelete.forEach { it.deleteRecursively() }
    }

    private suspend fun reconcileComponent(component: String, event: EnvEvent, now: Long, spec: EnvDownloadSpec?) {
        val current: EnvItemEntity? = store.get(component)
        val state: EnvItemState = current?.let { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() } ?: EnvItemState.UNKNOWN
        val retryCount: Int = current?.retryCount ?: 0

        // 在途任务由调度器推进，reconcile 不打扰——例外：探测 READY（磁盘事实绝对覆盖）必须翻 READY，
        // 否则资产/网络路径完成后 env_items 停在 QUEUED，UI 永远「处理中」（真机实证卡死点）。

        if (state in ACTIVE_STATES && event != EnvEvent.ProbeReady) return
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

/** 瀛樺偍鎵╁睍锛氳鍙栥€屾椿鍔紙鍦ㄩ€旓級缁勪欢銆嶉泦鍚堬紝渚?tmp 娓呯悊鍒ゆ柇 .part 鍘荤暀銆?*/
private suspend fun EnvStore.observeActiveComponentSet(): Set<String> {
    val active: Set<EnvItemState> = setOf(
        EnvItemState.QUEUED, EnvItemState.DOWNLOADING, EnvItemState.PAUSED,
        EnvItemState.VERIFYING, EnvItemState.INSTALLING,
    )
    return observe().firstOrNull().orEmpty()
        .filter { runCatching { EnvItemState.valueOf(it.state) }.getOrNull() in active }
        .map { it.component }
        .toSet()
}
