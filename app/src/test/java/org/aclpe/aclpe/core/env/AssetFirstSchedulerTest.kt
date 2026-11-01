package org.aclpe.aclpe.core.env

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.DownloadTaskState
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 资产优先调度器测试：有资产→本地安装不走网络；无资产→委托网络；状态流转正确。 */
class AssetFirstSchedulerTest {

    private class FakeDelegate : DownloadScheduler {
        val enqueued: MutableList<EnvDownloadTask> = mutableListOf()
        var paused: MutableList<String> = mutableListOf()
        var cancelled: MutableList<String> = mutableListOf()

        override val states: Flow<List<EnvDownloadState>> =
            kotlinx.coroutines.flow.MutableStateFlow(emptyList())

        override fun enqueue(task: EnvDownloadTask) {
            enqueued += task
        }

        override fun pause(component: String) {
            paused += component
        }

        override fun resume(component: String) = Unit

        override fun cancel(component: String) {
            cancelled += component
        }
    }

    private val task: EnvDownloadTask = EnvDownloadTask(
        component = "jre:17",
        url = "http://example.invalid/jre.tar.xz",
        sha256 = "ABC",
        sizeBytes = 100L,
    )

    @Test
    fun `有资产_本地安装_不触发网络`() = runTest {
        val delegate: FakeDelegate = FakeDelegate()
        val asset: File = File(System.getProperty("java.io.tmpdir"), "asset-${System.nanoTime()}").apply { writeText("x") }
        val installed: MutableList<Pair<String, File>> = mutableListOf()
        val scheduler = AssetFirstScheduler(
            delegate = delegate,
            scope = backgroundScope,
            assetSource = { component -> if (component == "jre:17") asset else null },
            installer = { component, file -> installed += component to file },
        )

        scheduler.enqueue(task)
        // 等待资产任务完成（状态流出现 SUCCEEDED）
        val states: List<EnvDownloadState> = scheduler.states.first { list -> list.any { it.component == "jre:17" && it.state == DownloadTaskState.SUCCEEDED } }
        assertTrue(states.any { it.component == "jre:17" && it.state == DownloadTaskState.SUCCEEDED })
        assertEquals(0, delegate.enqueued.size)
        assertEquals(1, installed.size)
        assertEquals("jre:17", installed[0].first)
    }

    @Test
    fun `无资产_委托网络调度器`() = runTest {
        val delegate: FakeDelegate = FakeDelegate()
        val scheduler = AssetFirstScheduler(
            delegate = delegate,
            scope = backgroundScope,
            assetSource = { null },
            installer = { _, _ -> },
        )
        scheduler.enqueue(task)
        assertEquals(1, delegate.enqueued.size)
        assertEquals(task, delegate.enqueued[0])
    }

    @Test
    fun `资产安装失败_状态 FAILED`() = runTest {
        val delegate: FakeDelegate = FakeDelegate()
        val asset: File = File(System.getProperty("java.io.tmpdir"), "asset-fail-${System.nanoTime()}").apply { writeText("x") }
        val scheduler = AssetFirstScheduler(
            delegate = delegate,
            scope = backgroundScope,
            assetSource = { asset },
            installer = { _, _ -> throw IllegalStateException("boom") },
        )
        scheduler.enqueue(task)
        val states: List<EnvDownloadState> = scheduler.states.first { list ->
            list.any { it.component == "jre:17" && it.state == DownloadTaskState.FAILED }
        }
        assertTrue(states.any { it.component == "jre:17" && it.state == DownloadTaskState.FAILED })
    }

    @Test
    fun `pause_cancel 分流_资产任务取消本地_无资产转网络`() = runTest {
        val delegate: FakeDelegate = FakeDelegate()
        val asset: File = File(System.getProperty("java.io.tmpdir"), "asset-c-${System.nanoTime()}").apply { writeText("x") }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scheduler = AssetFirstScheduler(
            delegate = delegate,
            scope = backgroundScope,
            assetSource = { component -> if (component == "jre:17") asset else null },
            installer = { _, _ -> gate.await() },
        )
        scheduler.enqueue(task)
        scheduler.pause("jre:17")   // 资产任务仍在运行：不落 delegate
        scheduler.cancel("jre:17")  // 取消本地任务
        gate.complete(Unit)
        scheduler.enqueue(task.copy(component = "jre:21"))
        scheduler.pause("jre:21")   // 网络任务转 delegate
        assertEquals(listOf("jre:21"), delegate.paused)
    }

    @Test
    fun `状态流合并委托与资产两条流`() = runTest {
        val delegate: FakeDelegate = FakeDelegate()
        val asset: File = File(System.getProperty("java.io.tmpdir"), "asset-m-${System.nanoTime()}").apply { writeText("x") }
        val scheduler = AssetFirstScheduler(
            delegate = delegate,
            scope = backgroundScope,
            assetSource = { asset },
            installer = { _, _ -> },
        )
        val final = scheduler.states.map { list -> list.firstOrNull { it.component == "jre:17" } }
        scheduler.enqueue(task)
        val done: EnvDownloadState? = final.first { it?.state == DownloadTaskState.SUCCEEDED }
        assertEquals(DownloadTaskState.SUCCEEDED, done?.state)
    }
}
