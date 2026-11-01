package org.aclpe.aclpe.core.env

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 资产优先调度器（参考 FCL：运行时打进 APK）：
 * - 组件存在内置资产（[assetSource] 非 null）→ 本地安装（秒级，不走网络），
 *   状态照样经 [states] 上报（QUEUED→RUNNING→SUCCEEDED/FAILED），UI 无感知差异；
 * - 无资产 → 原样委托 [delegate]（网络下载，双路径并存）。
 * 资产复制与安装全部在 [ioDispatcher]（默认 IO）执行，绝不阻塞调用线程（UI）。
 *
 * 审计修复（2026-11-01）：
 * - #8 并发安全：jobs/assetComponents 改并发容器（原普通 Map/Set 跨线程 CME 风险）；
 * - #9 在途去重：enqueue 对在途组件直接忽略（防双路径并发重复安装）；
 * - #6 原子更新：assetStates 用官方 update{} 扩展（原非原子读改写丢状态）。
 */
class AssetFirstScheduler(
    private val delegate: DownloadScheduler,
    private val scope: CoroutineScope,
    private val assetSource: (component: String) -> File?,
    private val installer: suspend (component: String, file: File) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadScheduler {

    private val assetStates: MutableStateFlow<Map<String, EnvDownloadState>> = MutableStateFlow(emptyMap())
    private val jobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    /** 已确认走资产安装的组件（job 内判定；网络回退组件不在此集合）。 */
    private val assetComponents: ConcurrentHashMap.KeySetView<String, Boolean> = ConcurrentHashMap.newKeySet()

    override val states = combine(delegate.states, assetStates) { delegateList, assetMap ->
        delegateList + assetMap.values
    }

    override fun enqueue(task: EnvDownloadTask) {
        // 在途去重（审计#9）：并发 reconcile/手动下载双路径触发时防重复安装
        if (jobs.containsKey(task.component)) return
        assetStates.update { it + (task.component to EnvDownloadState(task.component, DownloadTaskState.QUEUED, 0, null)) }
        val job: Job = scope.launch(ioDispatcher) {
            try {
                val assetFile: File? = assetSource(task.component)
                if (assetFile == null) {
                    assetStates.update { it - task.component }
                    delegate.enqueue(task)
                    return@launch
                }
                assetComponents += task.component
                assetStates.update { it + (task.component to EnvDownloadState(task.component, DownloadTaskState.RUNNING, 0, null)) }
                installer(task.component, assetFile)
                assetStates.update { it + (task.component to EnvDownloadState(task.component, DownloadTaskState.SUCCEEDED, 0, null)) }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t   // 取消不是失败：状态由 cancel() 清理，不得置 FAILED
            } catch (t: Throwable) {
                Timber.w(t, "内置资产安装失败: %s", task.component)
                assetStates.update { it + (task.component to EnvDownloadState(task.component, DownloadTaskState.FAILED, 0, null)) }
            } finally {
                jobs.remove(task.component)
                assetComponents.remove(task.component)
            }
        }
        // putIfAbsent：与并发 enqueue 竞争时保留先到者，撤回后到者
        if (jobs.putIfAbsent(task.component, job) != null) {
            job.cancel()
        }
    }

    override fun pause(component: String) {
        if (component in assetComponents) {
            // 本地安装不可暂停：秒级完成，忽略暂停语义
            Timber.d("资产安装任务不支持暂停: %s", component)
        } else {
            delegate.pause(component)
        }
    }

    override fun resume(component: String) {
        if (component !in assetComponents) delegate.resume(component)
    }

    override fun cancel(component: String) {
        jobs.remove(component)?.cancel()
        assetComponents.remove(component)
        assetStates.update { it - component }
        delegate.cancel(component)
    }
}
