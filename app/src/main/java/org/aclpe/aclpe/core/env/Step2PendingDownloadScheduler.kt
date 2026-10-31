package org.aclpe.aclpe.core.env

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import timber.log.Timber

/**
 * 占位下载调度器（诚实占位，非假实现）：
 * 真实下载（分片并发/断点续传/SHA 校验/镜像切换）由 Step 2 core-download 提供并替换本类。
 * 此间所有入队任务仅记录日志，UI 按钮点击会明确提示能力未接入。
 */
class Step2PendingDownloadScheduler : DownloadScheduler {

    private val _states: MutableStateFlow<List<EnvDownloadState>> = MutableStateFlow(emptyList())

    override val states: Flow<List<EnvDownloadState>> = _states

    override fun enqueue(task: EnvDownloadTask) {
        Timber.w("下载能力将在 Step 2（core-download）接入，任务未实际执行: %s", task.component)
    }

    override fun pause(component: String) = Unit

    override fun resume(component: String) = Unit

    override fun cancel(component: String) = Unit
}
