package org.aclpe.core.env.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [TmpCleaner] 幂等清理规则矩阵（设计文档 §5.3）。 */
class TmpCleanerTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "tmpclean-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `staging 一律删除`() {
        val dir: File = tempDir()
        File(dir, "jre_17.staging").mkdirs()
        val (toDelete, toKeep) = TmpCleaner.plan(dir, emptySet())
        assertEquals(1, toDelete.size)
        assertTrue(toKeep.isEmpty())
    }

    @Test
    fun `part 在途任务保留_其余删除`() {
        val dir: File = tempDir()
        val keepFile: File = File(dir, "jre_17.part").apply { writeText("x") }
        val staleFile: File = File(dir, "natives_h1.part").apply { writeText("y") }
        val (toDelete, toKeep) = TmpCleaner.plan(dir, activeComponents = setOf("jre:17"))
        assertEquals(listOf(staleFile), toDelete)
        assertEquals(listOf(keepFile), toKeep)
    }

    @Test
    fun `未知残留文件删除`() {
        val dir: File = tempDir()
        File(dir, "whatever.tmp").writeText("x")
        val (toDelete, _) = TmpCleaner.plan(dir, emptySet())
        assertEquals(1, toDelete.size)
    }

    @Test
    fun `目录不存在时安全返回空`() {
        val (toDelete, toKeep) = TmpCleaner.plan(File(tempDir(), "not-exist"), emptySet())
        assertTrue(toDelete.isEmpty())
        assertTrue(toKeep.isEmpty())
    }

    @Test
    fun `组件键与文件名互转`() {
        assertEquals("jre_17", TmpCleaner.componentToFileName("jre:17"))
        assertEquals("jre:17", TmpCleaner.fileNameToComponent("jre_17"))
    }
}
