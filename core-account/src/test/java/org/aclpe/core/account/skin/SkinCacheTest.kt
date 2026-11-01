package org.aclpe.core.account.skin

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 皮肤缓存 ETag 行为测试（MockWebServer：200 更新 / 304 命中 / 无皮肤 null）。 */
class SkinCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var cache: SkinCache
    private lateinit var tmp: File

    private val session: AuthSession = AuthSession(
        account = Account(id = "a1", type = AccountType.MICROSOFT, username = "M", uuid = "uuid-1"),
        tokens = AuthTokens(accessToken = "t"),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmp = File(System.getProperty("java.io.tmpdir"), "skin-${System.nanoTime()}").apply { mkdirs() }
        cache = SkinCache(OkHttpClient(), File(tmp, "cache"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    @Test
    fun `离线无皮肤返回 null`() = runTest {
        val result = cache.getSkin(session, skinUrl = null)
        assertTrue(result.isOk)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `首次 200 写入图片与 ETag`() = runTest {
        val body: ByteArray = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("ETag", "\"v1\"").setBody(okio.Buffer().write(body))
        )
        val result = cache.getSkin(session, server.url("/skin.png").toString())
        assertTrue(result.isOk)
        val skin = result.getOrNull()!!
        assertFalse(skin.fromCache)
        assertTrue(skin.file.isFile)
        assertTrue(skin.file.readBytes().contentEquals(body))
        assertEquals("\"v1\"", File(tmp, "cache/uuid-1.etag").readText().trim())
    }

    @Test
    fun `二次 304 命中缓存且带 If-None-Match`() = runTest {
        // 预置缓存与 ETag
        val png: File = File(tmp, "cache/uuid-1.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 9)) }
        File(tmp, "cache/uuid-1.etag").writeText("\"v1\"")

        server.enqueue(MockResponse().setResponseCode(304))
        val result = cache.getSkin(session, server.url("/skin.png").toString())
        assertTrue(result.isOk)
        val skin = result.getOrNull()!!
        assertTrue(skin.fromCache)
        assertEquals(png, skin.file)

        val request = server.takeRequest()
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
    }
}
