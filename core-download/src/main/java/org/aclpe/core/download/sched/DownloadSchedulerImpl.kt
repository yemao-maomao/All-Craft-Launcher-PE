package org.aclpe.core.download.sched

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry
import org.aclpe.core.download.net.DownloadManager
import org.aclpe.core.download.net.DownloadProgress
import org.aclpe.core.download.net.DownloadRequest
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.store.DownloadTaskDao
import org.aclpe.core.env.store.DownloadTaskEntity
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * core-env [DownloadScheduler] 的真实实现（Step 2 交付）：
 * 经 [DownloadManager] 获得分片并发 / Range 续传 / 重试退避 / 镜像轮换 / SHA 校验，
 * 任务状态落 Room（进程重启可续），进度经 [states] Flow 供 UI 订阅。
 * 下载成功后可注入 [onSucceeded]（app 侧接 JreInstaller → 原子安装 → 重新探测）。
 *
 * 审计修复（2026-11-01）：
 * - #3 状态断链：任务终态（成功/失败）经 [onTerminal] 回调通知 env 侧回写 EnvItemState，
 *   否则 env_items 停 QUEUED ∈ ACTIVE_STATES 成为永久死区（下载失败重启不能自愈的根因）；
 * - #2 异常收敛：runTask 整体 try/catch，任何异常都不击穿 applicationScope 协程（防崩溃+防 jobs 泄漏）；
 * - #6 原子性：状态流用 MutableStateFlow.update{}（原非原子读改写丢进度）；
 * - #7 DB 写风暴：进度持久化节流 ≥250ms（原每 64KB 一次 = 55MB/860 次写）。
 */
class DownloadSchedulerImpl(
    private val manager: DownloadManager,
    private val taskDao: DownloadTaskDao,
    private val scope: CoroutineScope,
    private val downloadTmpDir: File,
    private val mirrorIds: List<MirrorId> = MirrorRegistry.enabledIds(),
    /** 网络策略（app 层注入，如「仅 Wi-Fi」）：返回 false 时任务不启动并置 FAILED(wifi_required)。 */
    private val networkPolicy: (suspend () -> Boolean)? = null,
    private val onSucceeded: suspend (EnvDownloadTask, File) -> Unit = { _, _ -> },
    /** 任务终态回调（env 侧回写 EnvItemState：成功→READY 由探测收敛；失败→FAILED）。 */
    private val onTerminal: suspend (component: String, succeeded: Boolean, errorCode: String?) -> Unit = { _, _, _ -> },
) : DownloadScheduler {

    private val jobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val lastTasks: ConcurrentHashMap<String, EnvDownloadTask> = ConcurrentHashMap()
    private val _states: MutableStateFlow<List<EnvDownloadState>> = MutableStateFlow(emptyList())

    /** 进度落库节流：上次写入时间（component → epoch ms）。 */
    private val lastPersistMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    override val states: Flow<List<EnvDownloadState>> = _states

    override fun enqueue(task: EnvDownloadTask) {
        if (jobs.containsKey(task.component)) return   // 在途去重
        lastTasks[task.component] = task
        jobs[task.component] = scope.launch(Dispatchers.IO) {
            try {
                persist(task, DownloadTaskState.QUEUED)
                runTask(task)
            } catch (t: Throwable) {
                Timber.e(t, "任务协程异常 %s", task.component)
                persistState(task.component, DownloadTaskState.FAILED, errorCode = "internal_error")
                notifyTerminal(task.component, succeeded = false, errorCode = "internal_error")
            } finally {
                jobs.remove(task.component)
            }
        }
    }

    override fun pause(component: String) {
        jobs.remove(component)?.cancel()
        scope.launch(Dispatchers.IO) {
            persistState(component, DownloadTaskState.PAUSED, errorCode = null)
        }
    }

    override fun resume(component: String) {
        val task: EnvDownloadTask = lastTasks[component] ?: return
        enqueue(task)
    }

    override fun cancel(component: String) {
        jobs.remove(component)?.cancel()
        scope.launch(Dispatchers.IO) {
            persistState(component, DownloadTaskState.CANCELLED, errorCode = null)
            destFileFor(component).delete()
            DownloadManager.partOf(destFileFor(component)).delete()
        }
    }

    private suspend fun runTask(task: EnvDownloadTask) {
        // 网络策略门禁（如仅 Wi-Fi）：不满足则不下载，等用户切换网络后手动重试/继续
        if (networkPolicy?.invoke() == false) {
            persistState(task.component, DownloadTaskState.FAILED, errorCode = "wifi_required")
            notifyTerminal(task.component, succeeded = false, errorCode = "wifi_required")
            return
        }

        val mirrors: List<String> = mirrorIds.map { MirrorRegistry.rewrite(task.url, it) }
        val dest: File = destFileFor(task.component)

        val request: DownloadRequest = DownloadRequest(
            id = task.component,
            url = mirrors.first(),
            mirrorUrls = mirrors.drop(1),
            destFile = dest,
            sha256 = task.sha256,
            sizeBytes = task.sizeBytes,
            onProgress = { progress: DownloadProgress ->
                onProgressUpdate(task.component, progress)
            },
        )
        persistState(task.component, DownloadTaskState.RUNNING, errorCode = null)
        val result: AppResult<File> = try {
            manager.download(request)
        } catch (t: Throwable) {
            Timber.e(t, "下载异常 %s", task.component)
            AppResult.Err(org.aclpe.core.common.result.AppError.Network("internal_error", cause = t))
        }
        when (result) {
            is AppResult.Ok -> {
                persistState(task.component, DownloadTaskState.SUCCEEDED, errorCode = null)
                val install: AppResult<Unit> = try {
                    onSucceeded(task, result.value)
                    AppResult.Ok(Unit)
                } catch (t: Throwable) {
                    Timber.e(t, "安装回调异常 %s", task.component)
                    AppResult.Err(org.aclpe.core.common.result.AppError.Env("install_callback_failed", cause = t))
                }
                notifyTerminal(
                    task.component,
                    succeeded = install.isOk,
                    errorCode = if (install.isOk) null else "install_callback_failed",
                )
            }
            is AppResult.Err -> {
                Timber.w("下载失败 %s: %s", task.component, result.error.code)
                persistState(task.component, DownloadTaskState.FAILED, errorCode = result.error.code)
                notifyTerminal(task.component, succeeded = false, errorCode = result.error.code)
            }
        }
    }

    private suspend fun notifyTerminal(component: String, succeeded: Boolean, errorCode: String?) {
        try {
            onTerminal(component, succeeded, errorCode)
        } catch (t: Throwable) {
            Timber.e(t, "onTerminal 回调异常 %s", component)
        }
    }

    private fun onProgressUpdate(component: String, progress: DownloadProgress) {
        updateStates(component, progress.downloadedBytes, progress.totalBytes)
        // 节流（审计#7）：DB 写 ≥250ms 一次；UI 进度经内存 Flow 实时（不节流）
        val now: Long = System.currentTimeMillis()
        val last: Long = lastPersistMs[component] ?: 0L
        if (now - last >= PERSIST_THROTTLE_MS) {
            lastPersistMs[component] = now
            scope.launch(Dispatchers.IO) {
                taskDao.updateProgress(component, progress.downloadedBytes, System.currentTimeMillis())
            }
        }
    }

    private fun updateStates(component: String, downloaded: Long, total: Long?) {
        val updated: EnvDownloadState = EnvDownloadState(
            component = component,
            state = DownloadTaskState.RUNNING,
            progressBytes = downloaded,
            totalBytes = total,
        )
        // 原子更新（审计#6）：多分片/多组件并发下 filterNot+plus 会丢进度
        _states.update { list -> list.filterNot { it.component == component } + updated }
    }

    private fun persistState(component: String, state: DownloadTaskState, errorCode: String?) {
        val progress: Long = currentProgress(component)
        _states.update { list ->
            list.filterNot { it.component == component } + EnvDownloadState(
                component = component,
                state = state,
                progressBytes = progress,
                totalBytes = null,
            )
        }
        scope.launch(Dispatchers.IO) {
            taskDao.updateState(component, state.name, errorCode, System.currentTimeMillis())
        }
    }

    private fun currentProgress(component: String): Long =
        _states.value.firstOrNull { it.component == component }?.progressBytes ?: 0L

    private suspend fun persist(task: EnvDownloadTask, state: DownloadTaskState) {
        taskDao.upsert(
            DownloadTaskEntity(
                id = task.component,
                url = task.url,
                destPath = destFileFor(task.component).absolutePath,
                sha256 = task.sha256,
                sizeBytes = task.sizeBytes,
                state = state.name,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 目标文件（组件键经 TmpCleaner 同款编码，保证与其清理规则一致）。 */
    private fun destFileFor(component: String): File =
        File(downloadTmpDir, org.aclpe.core.env.store.TmpCleaner.componentToFileName(component))

    companion object {
        const val PERSIST_THROTTLE_MS: Long = 250L
    }
}
