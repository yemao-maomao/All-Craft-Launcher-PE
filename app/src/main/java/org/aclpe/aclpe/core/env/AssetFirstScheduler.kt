package org.aclpe.aclpe.core.env

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import timber.log.Timber
import java.io.File

/**
 * 资产优先调度器（参考 FCL：运行时打进 APK）：
 * - 组件存在内置资产（[assetSource] 非 null）→ 本地安装（秒级，不走网络），
 *   状态照样经 [states] 上报（QUEUED→RUNNING→SUCCEEDED/FAILED），UI 无感知差异；
 * - 无资产 → 原样委托 [delegate]（网络下载，双路径并存）。
 * 资产安装失败自动回落网络：ENV 探测判损坏后再次 enqueue 时资产仍会被尝试——
 * 为此本地安装失败时置 FAILED 并清理缓存，下次 enqueue 重新复制（无网络兜底切换，保持简单语义）。
 */
class AssetFirstScheduler(
    private val delegate: DownloadScheduler,
    private val scope: CoroutineScope,
    private val assetSource: (component: String) -> File?,
    private val installer: suspend (component: String, file: File) -> Unit,
) : DownloadScheduler {

    private val assetStates: MutableStateFlow<Map<String, EnvDownloadState>> = MutableStateFlow(emptyMap())
    private val jobs: MutableMap<String, Job> = mutableMapOf()

    override val states = combine(delegate.states, assetStates) { delegateList, assetMap ->
        delegateList + assetMap.values
    }

    override fun enqueue(task: EnvDownloadTask) {
        val assetFile: File? = assetSource(task.component)
        if (assetFile == null) {
            delegate.enqueue(task)
            return
        }
        val job: Job = scope.launch {
            try {
                assetStates.update(task.component, EnvDownloadState(task.component, DownloadTaskState.RUNNING, 0, null))
                installer(task.component, assetFile)
                assetStates.update(task.component, EnvDownloadState(task.component, DownloadTaskState.SUCCEEDED, 0, null))
            } catch (t: Throwable) {
                Timber.w(t, "内置资产安装失败: %s", task.component)
                assetStates.update(task.component, EnvDownloadState(task.component, DownloadTaskState.FAILED, 0, null))
            } finally {
                jobs.remove(task.component)
            }
        }
        jobs[task.component] = job
        assetStates.update(task.component, EnvDownloadState(task.component, DownloadTaskState.QUEUED, 0, null))
    }

    override fun pause(component: String) {
        if (component in jobs) {
            // 本地安装不可暂停：秒级完成，忽略暂停语义
            Timber.d("资产安装任务不支持暂停: %s", component)
        } else {
            delegate.pause(component)
        }
    }

    override fun resume(component: String) {
        if (component !in jobs) delegate.resume(component)
    }

    override fun cancel(component: String) {
        jobs.remove(component)?.cancel()
        assetStates.update { it - component }
        delegate.cancel(component)
    }

    private fun MutableStateFlow<Map<String, EnvDownloadState>>.update(
        component: String,
        state: EnvDownloadState,
    ) {
        value = value + (component to state)
    }

    private fun MutableStateFlow<Map<String, EnvDownloadState>>.update(transform: (Map<String, EnvDownloadState>) -> Map<String, EnvDownloadState>) {
        value = transform(value)
    }
}
