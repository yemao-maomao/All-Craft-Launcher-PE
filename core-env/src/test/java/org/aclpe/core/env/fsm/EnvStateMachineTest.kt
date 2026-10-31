package org.aclpe.core.env.fsm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EnvStateMachine] 全转移矩阵测试（设计文档 §3.1 + §8）。 */
class EnvStateMachineTest {

    private fun t(state: EnvItemState, event: EnvEvent, retryCount: Int = 0): EnvTransition =
        EnvStateMachine.next(state, event, retryCount)

    @Test
    fun `探测事件绝对覆盖任何状态`() {
        assertEquals(EnvItemState.MISSING, t(EnvItemState.READY, EnvEvent.ProbeMissing).newState)
        assertEquals(EnvItemState.MISSING, t(EnvItemState.QUEUED, EnvEvent.ProbePartial).newState)
        assertEquals(EnvItemState.CORRUPTED, t(EnvItemState.READY, EnvEvent.ProbeCorrupted).newState)
        assertEquals(EnvItemState.READY, t(EnvItemState.MISSING, EnvEvent.ProbeReady).newState)
    }

    @Test
    fun `正常链路_缺失到就绪`() {
        var s: EnvItemState = EnvItemState.UNKNOWN
        var retry: Int = 0
        fun step(event: EnvEvent): EnvItemState {
            val tr = t(s, event, retry)
            s = tr.newState
            retry += tr.retryDelta
            return s
        }
        assertEquals(EnvItemState.MISSING, step(EnvEvent.ProbeMissing))
        assertEquals(EnvItemState.QUEUED, step(EnvEvent.Enqueued))
        assertEquals(EnvItemState.DOWNLOADING, step(EnvEvent.DownloadStarted))
        assertEquals(EnvItemState.VERIFYING, step(EnvEvent.DownloadCompleted))
        assertEquals(EnvItemState.INSTALLING, step(EnvEvent.VerifyPassed))
        assertEquals(EnvItemState.READY, step(EnvEvent.InstallCompleted))
        assertEquals(0, retry)
    }

    @Test
    fun `暂停与继续`() {
        val paused: EnvTransition = t(EnvItemState.DOWNLOADING, EnvEvent.DownloadPaused)
        assertEquals(EnvItemState.PAUSED, paused.newState)
        assertEquals(EnvItemState.DOWNLOADING, t(EnvItemState.PAUSED, EnvEvent.DownloadResumed).newState)
        // 非法事件被忽略
        assertEquals(EnvItemState.PAUSED, t(EnvItemState.PAUSED, EnvEvent.DownloadCompleted).newState)
    }

    @Test
    fun `失败重试_前三次退避第四次降级第五次永久`() {
        // 第 1 次失败
        val f1: EnvTransition = t(EnvItemState.DOWNLOADING, EnvEvent.NetworkFailure)
        assertEquals(EnvItemState.FAILED, f1.newState)
        assertEquals(1, f1.retryDelta)
        // retryCount=1：退避重试
        val r1: EnvTransition = t(EnvItemState.FAILED, EnvEvent.Retry, retryCount = 1)
        assertEquals(EnvItemState.QUEUED, r1.newState)
        assertFalse(r1.downgrade)
        // retryCount=3：降级单线程+换镜像
        val r3: EnvTransition = t(EnvItemState.FAILED, EnvEvent.Retry, retryCount = 3)
        assertEquals(EnvItemState.QUEUED, r3.newState)
        assertTrue(r3.downgrade)
        // retryCount=4：永久失败
        val r4: EnvTransition = t(EnvItemState.FAILED, EnvEvent.Retry, retryCount = 4)
        assertEquals(EnvItemState.FAILED_PERMANENT, r4.newState)
    }

    @Test
    fun `校验与安装失败计入重试`() {
        assertEquals(1, t(EnvItemState.VERIFYING, EnvEvent.VerifyFailed).retryDelta)
        assertEquals(EnvItemState.FAILED, t(EnvItemState.VERIFYING, EnvEvent.VerifyFailed).newState)
        assertEquals(1, t(EnvItemState.INSTALLING, EnvEvent.InstallFailed).retryDelta)
    }

    @Test
    fun `手动重试从永久失败恢复并清零重试计数`() {
        val tr: EnvTransition = t(EnvItemState.FAILED_PERMANENT, EnvEvent.ManualRetry, retryCount = 4)
        assertEquals(EnvItemState.QUEUED, tr.newState)
        assertEquals(-4, tr.retryDelta)
    }

    @Test
    fun `损坏状态重新入队下载`() {
        assertEquals(EnvItemState.CORRUPTED, t(EnvItemState.READY, EnvEvent.ProbeCorrupted).newState)
        assertEquals(EnvItemState.QUEUED, t(EnvItemState.CORRUPTED, EnvEvent.Enqueued).newState)
    }

    @Test
    fun `重复入队被忽略`() {
        assertEquals(EnvItemState.QUEUED, t(EnvItemState.QUEUED, EnvEvent.Enqueued).newState)
        assertEquals(EnvItemState.READY, t(EnvItemState.READY, EnvEvent.Enqueued).newState)
    }
}
