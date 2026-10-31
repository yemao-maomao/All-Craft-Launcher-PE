package org.aclpe.core.common.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 可测试树：子类桥接受保护的 [FileLoggingTree.log]，直接验证覆写实现本身
 * （不经 Timber 门面的格式化路径）。
 * 注意：不能直接 `tree.log(...)` 调用——非子类只能看到公开的 vararg 重载，
 * 语义完全不同（第二参数会被当作 message 格式化，tag 走 ThreadLocal）。
 */
private class TestableTree(
    logDir: File,
    maxFileBytes: Long = FileLoggingTree.DEFAULT_MAX_FILE_BYTES,
    keepFiles: Int = FileLoggingTree.DEFAULT_KEEP_FILES,
) : FileLoggingTree(logDir, maxFileBytes, keepFiles) {

    fun logRaw(priority: Int, tag: String?, message: String, t: Throwable?) {
        log(priority, tag, message, t)
    }
}

/** [FileLoggingTree] 单元测试：落盘、脱敏、轮转（JVM 环境，无 Robolectric）。 */
class FileLoggingTreeTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "filelog-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `日志落盘且敏感信息被脱敏`() {
        val dir: File = tempDir()
        val tree: TestableTree = TestableTree(dir)
        tree.logRaw(4, "tag", "access_token=abcdef1234567890", null)
        val content: String = File(dir, FileLoggingTree.LOG_FILE_NAME).readText()
        assertTrue(content.contains("access_token=<redacted>"))
        assertFalse(content.contains("abcdef1234567890"))
    }

    @Test
    fun `超过上限后轮转为 1 并继续写新日志`() {
        val dir: File = tempDir()
        // maxFileBytes 设小值，便于触发轮转
        val tree: TestableTree = TestableTree(dir, maxFileBytes = 64, keepFiles = 3)
        repeat(3) { tree.logRaw(4, "t", "line-${it}-${"x".repeat(64)}", null) }
        val main: File = File(dir, FileLoggingTree.LOG_FILE_NAME)
        val rotated: File = File(dir, FileLoggingTree.LOG_FILE_NAME + ".1")
        assertTrue(main.exists())
        assertTrue(rotated.exists())
    }
}
