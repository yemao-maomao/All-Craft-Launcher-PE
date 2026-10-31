package org.aclpe.core.env.bootstrap

import kotlinx.coroutines.test.runTest
import org.aclpe.core.env.fsm.EnvItemState
import org.aclpe.core.env.probe.EnvLayout
import org.aclpe.core.env.probe.EnvProbe
import org.aclpe.core.env.probe.EnvScope
import org.aclpe.core.env.probe.ExecResult
import org.aclpe.core.env.store.EnvItemEntity
import org.aclpe.core.env.testutil.FakeDownloadScheduler
import org.aclpe.core.env.testutil.FakeProcessExecutor
import org.aclpe.core.env.testutil.InMemoryEnvStore
import org.aclpe.core.env.testutil.OUTPUT_JRE17_SERVER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [EnvBootstrap] 编排测试（设计文档 §5 幂等与恢复）：
 * 覆盖 reconcile 幂等、spec 缺失/存在、在途任务不被打扰、tmp 清理。
 */
class EnvBootstrapTest {

    private fun setup(): Triple<EnvLayout, InMemoryEnvStore, FakeDownloadScheduler> {
        val root: File = File(System.getProperty("java.io.tmpdir"), "bootstrap-${System.nanoTime()}").apply { mkdirs() }
        val layout: EnvLayout = EnvLayout(root)
        val store: InMemoryEnvStore = InMemoryEnvStore()
        val scheduler: FakeDownloadScheduler = FakeDownloadScheduler()
        return Triple(layout, store, scheduler)
    }

    private fun bootstrap(layout: EnvLayout, store: InMemoryEnvStore, scheduler: FakeDownloadScheduler): EnvBootstrap =
        EnvBootstrap(EnvProbe(FakeProcessExecutor(ExecResult(0, "", timedOut = false))), scheduler, store, layout)

    private val scope17: EnvScope = EnvScope(jreMajors = setOf(17))
    private val spec17: EnvDownloadSpec = EnvDownloadSpec(url = "https://example/jre17.tar.xz", sha256 = "abc")

    @Test
    fun `无下载规格时状态 MISSING 且错误码 no_download_spec`() = runTest {
        val (layout, store, scheduler) = setup()
        val b: EnvBootstrap = bootstrap(layout, store, scheduler)
        val result = b.reconcile(scope17, emptyMap())
        assertTrue(result.isOk)
        val item: EnvItemEntity = store.snapshot().first { it.component == "jre:17" }
        assertEquals(EnvItemState.MISSING.name, item.state)
        assertEquals("no_download_spec", item.errorCode)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `有规格时自动入队并置 QUEUED`() = runTest {
        val (layout, store, scheduler) = setup()
        val b: EnvBootstrap = bootstrap(layout, store, scheduler)
        b.reconcile(scope17, mapOf("jre:17" to spec17))
        val item: EnvItemEntity = store.snapshot().first { it.component == "jre:17" }
        assertEquals(EnvItemState.QUEUED.name, item.state)
        assertEquals(1, scheduler.enqueued.size)
        assertEquals("jre:17", scheduler.enqueued.first().component)
        assertEquals(spec17.sha256, scheduler.enqueued.first().sha256)
    }

    @Test
    fun `reconcile 幂等_在途任务不重复入队`() = runTest {
        val (layout, store, scheduler) = setup()
        val b: EnvBootstrap = bootstrap(layout, store, scheduler)
        b.reconcile(scope17, mapOf("jre:17" to spec17))
        b.reconcile(scope17, mapOf("jre:17" to spec17))
        assertEquals(1, scheduler.enqueued.size)
        assertEquals(EnvItemState.QUEUED.name, store.snapshot().first().state)
    }

    @Test
    fun `JRE 就绪时 reconcile 置 READY 且不入队`() = runTest {
        val (layout, store, scheduler) = setup()
        val dir: File = layout.jreDir(17)
        File(dir, "bin").mkdirs()
        File(dir, "lib/arm64/server").mkdirs()
        layout.jreJavaBinary(17).writeText("x")
        layout.jreJliLib(17).writeText("x")
        layout.jreServerLib(17).writeText("x")
        layout.jreReleaseFile(17).writeText("JAVA_VERSION=\"17\"")
        val b: EnvBootstrap = EnvBootstrap(
            EnvProbe(FakeProcessExecutor(ExecResult(0, OUTPUT_JRE17_SERVER, timedOut = false))),
            scheduler, store, layout,
        )
        val result = b.reconcile(scope17, mapOf("jre:17" to spec17))
        assertTrue(result.isOk)
        assertEquals(EnvItemState.READY.name, store.snapshot().first { it.component == "jre:17" }.state)
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test
    fun `tmp 清理_在途 part 保留`() = runTest {
        val (layout, store, scheduler) = setup()
        layout.downloadsTmp.mkdirs()
        File(layout.downloadsTmp, "jre_17.part").writeText("x")
        File(layout.downloadsTmp, "stale.part").writeText("y")
        File(layout.downloadsTmp, "jre_17.staging").mkdirs()
        val b: EnvBootstrap = bootstrap(layout, store, scheduler)
        // 先置一个在途条目（QUEUED），再清理
        store.upsert(EnvItemEntity(component = "jre:17", state = EnvItemState.QUEUED.name))
        b.cleanupTmp()
        assertTrue(File(layout.downloadsTmp, "jre_17.part").exists())
        assertTrue(!File(layout.downloadsTmp, "stale.part").exists())
        assertTrue(!File(layout.downloadsTmp, "jre_17.staging").exists())
    }
}
