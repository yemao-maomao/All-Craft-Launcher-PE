package org.aclpe.core.env.fsm

/** 单组件任务状态（设计文档 §3.1 状态机，状态落 DB，不驻留内存 → 进程被杀后重启即恢复）。 */
enum class EnvItemState {
    UNKNOWN, MISSING, QUEUED, DOWNLOADING, PAUSED, VERIFYING, INSTALLING,
    READY, FAILED, FAILED_PERMANENT, CORRUPTED,
}

/** 状态机事件。 */
sealed interface EnvEvent {
    data object ProbeMissing : EnvEvent
    data object ProbePartial : EnvEvent
    data object ProbeCorrupted : EnvEvent
    data object ProbeReady : EnvEvent
    data object Enqueued : EnvEvent
    data object DownloadStarted : EnvEvent
    data object DownloadPaused : EnvEvent
    data object DownloadResumed : EnvEvent
    data object DownloadCompleted : EnvEvent
    data object VerifyPassed : EnvEvent
    data object VerifyFailed : EnvEvent
    data object InstallCompleted : EnvEvent
    data object InstallFailed : EnvEvent
    data object NetworkFailure : EnvEvent
    data object Retry : EnvEvent
    data object ManualRetry : EnvEvent
}

/**
 * 转移结果：[retryDelta] 为 retryCount 增量（可为负）；[downgrade] 表示本轮重试应降级单线程+换镜像。
 */
data class EnvTransition(
    val newState: EnvItemState,
    val retryDelta: Int = 0,
    val downgrade: Boolean = false,
)

/**
 * 环境组件状态机（纯函数，全转移可测）。
 * 探测事件只在启动 reconcile 时注入（此时调度器空闲），因此按「绝对覆盖」处理。
 * 重试策略（设计文档 §3.1）：失败 1/2 次退避重试；第 3 次失败后降级单线程+换镜像再给一次机会；
 * 仍失败 → FAILED_PERMANENT（仅用户手动重试可救，且清零 retryCount）。
 */
object EnvStateMachine {

    const val MAX_RETRIES: Int = 3

    fun next(state: EnvItemState, event: EnvEvent, retryCount: Int): EnvTransition = when (event) {
        EnvEvent.ProbeMissing, EnvEvent.ProbePartial ->
            EnvTransition(EnvItemState.MISSING)   // 部分损坏按缺失重下（安装是原子的，无增量价值）

        EnvEvent.ProbeCorrupted -> EnvTransition(EnvItemState.CORRUPTED)
        EnvEvent.ProbeReady -> EnvTransition(EnvItemState.READY)

        EnvEvent.Enqueued -> when (state) {
            EnvItemState.MISSING, EnvItemState.CORRUPTED, EnvItemState.FAILED, EnvItemState.UNKNOWN ->
                EnvTransition(EnvItemState.QUEUED)
            EnvItemState.FAILED_PERMANENT ->
                EnvTransition(EnvItemState.QUEUED, retryDelta = -retryCount)   // 手动重试清零
            else -> EnvTransition(state)                                       // 重复入队忽略
        }

        EnvEvent.DownloadStarted ->
            if (state == EnvItemState.QUEUED) EnvTransition(EnvItemState.DOWNLOADING) else EnvTransition(state)

        EnvEvent.DownloadPaused ->
            if (state == EnvItemState.DOWNLOADING) EnvTransition(EnvItemState.PAUSED) else EnvTransition(state)

        EnvEvent.DownloadResumed ->
            if (state == EnvItemState.PAUSED) EnvTransition(EnvItemState.DOWNLOADING) else EnvTransition(state)

        EnvEvent.DownloadCompleted ->
            if (state == EnvItemState.DOWNLOADING) EnvTransition(EnvItemState.VERIFYING) else EnvTransition(state)

        EnvEvent.VerifyPassed ->
            if (state == EnvItemState.VERIFYING) EnvTransition(EnvItemState.INSTALLING) else EnvTransition(state)

        EnvEvent.VerifyFailed ->
            if (state == EnvItemState.VERIFYING) EnvTransition(EnvItemState.FAILED, retryDelta = 1) else EnvTransition(state)

        EnvEvent.InstallCompleted ->
            if (state == EnvItemState.INSTALLING) EnvTransition(EnvItemState.READY) else EnvTransition(state)

        EnvEvent.InstallFailed ->
            if (state == EnvItemState.INSTALLING) EnvTransition(EnvItemState.FAILED, retryDelta = 1) else EnvTransition(state)

        EnvEvent.NetworkFailure ->
            if (state == EnvItemState.DOWNLOADING) EnvTransition(EnvItemState.FAILED, retryDelta = 1) else EnvTransition(state)

        EnvEvent.Retry -> if (state == EnvItemState.FAILED) {
            when {
                retryCount < MAX_RETRIES -> EnvTransition(EnvItemState.QUEUED)
                retryCount == MAX_RETRIES -> EnvTransition(EnvItemState.QUEUED, downgrade = true)  // 最后一次机会：降级
                else -> EnvTransition(EnvItemState.FAILED_PERMANENT)
            }
        } else {
            EnvTransition(state)
        }

        EnvEvent.ManualRetry ->
            if (state == EnvItemState.FAILED_PERMANENT) EnvTransition(EnvItemState.QUEUED, retryDelta = -retryCount)
            else EnvTransition(state)
    }
}
