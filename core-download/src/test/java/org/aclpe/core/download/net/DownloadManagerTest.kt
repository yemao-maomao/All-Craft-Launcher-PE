package org.aclpe.core.download.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.download.testutil.TestFileServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** [DownloadManager] 全链路测试：完整/断点续传/分片/重试/镜像轮换/校验/进度。 */
class DownloadManagerTest {

    private lateinit var server: TestFileServer
    private lateinit var manager: DownloadManager
    private lateinit var tmp: File

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = TestFileServer()
        server.start()
        manager = DownloadManager(client)
        tmp = File(System.getProperty("java.io.tmpdir"), "dlmgr-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.stop()
        tmp.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun dest(name: String): File = File(tmp, name)

    @Test
    fun `完整下载并校验 sha256`() = runTest {
        val bytes: ByteArray = ByteArray(300_000) { (it % 251).toByte() }
        server.serve("/f.bin", bytes)
        val result: AppResult<File> = manager.download(
            DownloadRequest("t1", server.url("/f.bin"), destFile = dest("f.bin"), sha256 = sha256Hex(bytes))
        )
        assertTrue(result.isOk)
        assertArrayEquals(bytes, result.getOrNull()!!.readBytes())
    }

    @Test
    fun `断点续传_从已有 part 继续`() = runTest {
        val bytes: ByteArray = ByteArray(200_000) { (it % 241).toByte() }
        server.serve("/r.bin", bytes)
        val destFile: File = dest("r.bin")
        // 预置一半 part
        DownloadManager.partOf(destFile).apply {
            parentFile?.mkdirs()
            writeBytes(bytes.copyOfRange(0, 100_000))
        }
        val result: AppResult<File> = manager.download(
            DownloadRequest("t2", server.url("/r.bin"), destFile = destFile, sha256 = sha256Hex(bytes))
        )
        assertTrue(result.isOk)
        assertArrayEquals(bytes, result.getOrNull()!!.readBytes())
        // 至少发生一次 Range 请求（续传生效）
        assertTrue(server.requestCounts["/r.bin"]!!.get() >= 2)
    }

    @Test
    fun `分片并发下载`() = runTest {
        val bytes: ByteArray = ByteArray(1_000_000) { (it % 233).toByte() }
        server.serve("/c.bin", bytes)
        val result: AppResult<File> = manager.download(
            DownloadRequest("t3", server.url("/c.bin"), destFile = dest("c.bin"), sha256 = sha256Hex(bytes), chunkCount = 4)
        )
        assertTrue(result.isOk)
        assertArrayEquals(bytes, result.getOrNull()!!.readBytes())
    }

    @Test
    fun `sha 不匹配返回错误并清理 part`() = runTest {
        val bytes: ByteArray = ByteArray(50_000) { 1 }
        server.serve("/bad.bin", bytes)
        val destFile: File = dest("bad.bin")
        val result: AppResult<File> = manager.download(
            DownloadRequest("t4", server.url("/bad.bin"), destFile = destFile, sha256 = "0".repeat(64))
        )
        assertTrue(result.isErr)
        assertEquals("sha256_mismatch", result.errorOrNull()?.code)
        assertFalse(DownloadManager.partOf(destFile).exists())
    }

    @Test
    fun `失败重试后成功_退避与请求计数`() = runTest {
        val bytes: ByteArray = ByteArray(30_000) { 7 }
        server.serve("/retry.bin", bytes, failFirst = 2)
        val result: AppResult<File> = manager.download(
            DownloadRequest("t5", server.url("/retry.bin"), destFile = dest("retry.bin"), chunkCount = 1)
        )
        assertTrue(result.isOk)
        // 探针失败 ×2 → 探针成功 ×1 → 下载 ×1 = 4 次请求
        assertEquals(4, server.requestCounts["/retry.bin"]!!.get())
    }

    @Test
    fun `首镜像失败轮换到次镜像`() = runTest {
        val bytes: ByteArray = ByteArray(25_000) { 9 }
        // 主镜像 404（forceStatus 404），备用镜像正常
        server.serve("/m1.bin", bytes, forceStatus = 404)
        server.serve("/m2.bin", bytes)
        val result: AppResult<File> = manager.download(
            DownloadRequest("t6", server.url("/m1.bin"), mirrorUrls = listOf(server.url("/m2.bin")), destFile = dest("m.bin"))
        )
        assertTrue(result.isOk)
        assertTrue(server.requestCounts["/m2.bin"]!!.get() >= 1)
    }

    @Test
    fun `进度回调被调用且字节数单调`() = runTest {
        val bytes: ByteArray = ByteArray(120_000) { (it % 251).toByte() }
        server.serve("/p.bin", bytes)
        val progress: MutableList<Long> = mutableListOf()
        val result: AppResult<File> = manager.download(
            DownloadRequest(
                "t7", server.url("/p.bin"), destFile = dest("p.bin"),
                onProgress = { progress += it.downloadedBytes },
            )
        )
        assertTrue(result.isOk)
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.sorted(), progress)
    }
}
