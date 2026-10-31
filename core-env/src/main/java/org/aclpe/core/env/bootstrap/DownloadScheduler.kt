package org.aclpe.core.env.bootstrap

import kotlinx.coroutines.flow.Flow

/** 下载任务（core-env 视角，不含 core-download 细节）。 */
data class EnvDownloadTask(
    val component: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long?,
)

enum class DownloadTaskState {
    QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED,
}

data class EnvDownloadState(
    val component: String,
    val state: DownloadTaskState,
    val progressBytes: Long,
    val totalBytes: Long?,
)

/**
 * 下载调度契约 —— Step 2（core-download）提供真实实现；core-env 只依赖本接口，绝不反向依赖。
 * 实现方硬性要求（设计文档 §5.1）：
 * - 暂停/续传：Range + If-Range/ETag，上游变更自动从头；
 * - 下载完成后整包 SHA-256 校验，失败重试（退避 1s/4s/16s，≤3 次后降级单线程+换镜像）；
 * - 进度经 [states] Flow 上报，UI 订阅；
 * - 所有操作线程安全且可取消。
 */
interface DownloadScheduler {

    val states: Flow<List<EnvDownloadState>>

    fun enqueue(task: EnvDownloadTask)

    fun pause(component: String)

    fun resume(component: String)

    fun cancel(component: String)
}
