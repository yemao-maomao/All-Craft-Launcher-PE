package org.aclpe.aclpe.core.env

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.install.JreInstaller
import org.aclpe.core.env.bootstrap.EnvDownloadSpec
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.store.EnvItemEntity
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FCL 式运行时部署器（2026-11-02 重写，替代状态机/reconcile/调度器观察链路——用户指令）：
 *
 * 线性流程，完成后状态直达 UI（此前「处理中」卡死：完成后没有返回完成信息）：
 * 1. `.build` 标记 + release 文件带版本行 → READY（文件事实，立即返回）；
 * 2. 否则：APK 资产复制（sha256 校验）→ JreInstaller 解压安装（原子替换+写标记）→ READY；
 * 3. 任一步失败 → FAILED(errorCode)，重试即全流程重来。
 *
 * 无 Room 中间层、无状态机、无跨模块事件——每个槽位一条协程，一个 StateFlow 汇报。
 * `ensure()` 幂等（READY/在途跳过）；`retry()` 手动路径。
 */
class SimpleRuntimeInstaller(
    private val scope: CoroutineScope,
    private val isReady: (slot: Int) -> Boolean,
    private val assetProvider: suspend (slot: Int) -> File?,
    private val installer: suspend (slot: Int, archive: File) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    enum class Phase { NOT_INSTALLED, INSTALLING, READY, FAILED }

    data class RuntimeState(val phase: Phase, val errorCode: String? = null)

    private val _states: MutableStateFlow<Map<Int, RuntimeState>> = MutableStateFlow(emptyMap())
    val states: StateFlow<Map<Int, RuntimeState>> = _states

    private val jobs: ConcurrentHashMap<Int, Job> = ConcurrentHashMap()

    /** 幂等部署：在途跳过；标记在 = 立即 READY；否则资产 → 安装 → READY。 */
    fun ensure(slot: Int) {
        if (jobs.containsKey(slot)) return
        if (_states.value[slot]?.phase == Phase.READY) return
        if (isReady(slot)) {
            // FCL 式：文件标记即完成事实——完成后立即返回完成信息
            _states.update { it + (slot to RuntimeState(Phase.READY)) }
            Timber.i("runtime ready (marker): slot=%d", slot)
            return
        }
        _states.update { it + (slot to RuntimeState(Phase.INSTALLING)) }
        jobs.putIfAbsent(slot, scope.launch(ioDispatcher) {
            try {
                val archive: File = assetProvider(slot)
                    ?: throw IllegalStateException("no_asset")
                installer(slot, archive)
                if (!isReady(slot)) throw IllegalStateException("install_incomplete")
                _states.update { it + (slot to RuntimeState(Phase.READY)) }
                Timber.i("runtime ready (installed): slot=%d", slot)
            } catch (t: Throwable) {
                Timber.w(t, "runtime install failed: slot=%d", slot)
                _states.update { it + (slot to RuntimeState(Phase.FAILED, errorCode = errorCodeOf(t))) }
            } finally {
                jobs.remove(slot)
            }
        })
    }

    /** 手动安装/重试：清状态后全流程重来。 */
    fun retry(slot: Int) {
        _states.update { it - slot }
        ensure(slot)
    }

    fun phaseOf(slot: Int): Phase = _states.value[slot]?.phase ?: Phase.NOT_INSTALLED

    private fun errorCodeOf(t: Throwable): String = when (t) {
        is IllegalStateException -> t.message ?: "illegal_state"
        else -> t.javaClass.simpleName
    }

    companion object {
        /** 生产工厂：APK 资产 + JreInstaller + files/env 布局。 */
        fun create(context: Context, scope: CoroutineScope): SimpleRuntimeInstaller {
            val layout: EnvLayout = EnvLayout(context.filesDir)
            val assetCache: AssetJreCache = AssetJreCache(context)
            return SimpleRuntimeInstaller(
                scope = scope,
                isReady = { slot: Int ->
                    // .build 标记 + release 文件带 JAVA_VERSION 行（防半装）
                    layout.jreBuildMarker(slot).isFile && readReleaseMajor(layout.jreReleaseFile(slot)) != null
                },
                assetProvider = { slot: Int ->
                    assetCache.fileFor(EnvItemEntity.jre(slot))
                },
                installer = { slot: Int, archive: File ->
                    val component: String = EnvItemEntity.jre(slot)
                    val spec: EnvDownloadSpec? = JreManifest.specs()[component]
                    val buildId: String? = JreManifest.buildIdFor(component)
                    if (spec == null || buildId == null) throw IllegalStateException("no_download_spec")
                    val result: AppResult<File> = JreInstaller.install(archive, layout, slot, buildId, spec.sha256)
                    if (result is AppResult.Err) throw IllegalStateException(result.error.code)
                },
            )
        }

        private fun readReleaseMajor(file: File): Int? {
            if (!file.isFile) return null
            return try {
                file.readLines().firstOrNull { it.trimStart().startsWith("JAVA_VERSION") }
                    ?.substringAfter('"')?.substringBefore('"')
                    ?.let { raw ->
                        val parts: List<String> = raw.split('.')
                        val first: Int = parts.firstOrNull()?.toIntOrNull() ?: return@let null
                        if (first == 1) parts.getOrNull(1)?.toIntOrNull() else first
                    }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
