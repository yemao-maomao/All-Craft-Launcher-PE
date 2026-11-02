package org.aclpe.core.mods.modrinth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Modrinth 客户端测试：facet 组装、UA、解析、错误码。 */
class ModrinthClientTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ModrinthClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ModrinthClient(OkHttpClient(), baseUrl = server.url("/v2").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search 组装 facet 与 UA 并解析结果`() = runTest {
        val body: String = """
            {"hits":[{"project_id":"aabbcc","slug":"sodium","title":"Sodium",
              "description":"Modern rendering engine","downloads":12345678,
              "icon_url":null,"categories":["fabric"],"client_side":"required"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = api.search(query = "sodium", gameVersion = "1.20.1", loader = "fabric")

        assertTrue(result.isOk)
        val hit: ModrinthProject = result.getOrNull()!!.hits.single()
        assertEquals("sodium", hit.slug)
        assertEquals(12345678L, hit.downloads)
        assertEquals("required", hit.client_side)

        val request = server.takeRequest()
        assertEquals("/v2/search", request.path?.substringBefore('?'))
        val path: String? = request.path
        assertTrue(path!!.contains("query=sodium"))
        assertTrue(path.contains("versions%3A1.20.1"))   // facet: versions:1.20.1 URL 编码后
        assertTrue(path.contains("categories%3Afabric"))
        assertEquals(ModrinthApi.USER_AGENT, request.getHeader("User-Agent"))
    }

    @Test
    fun `versions 解析文件 sha1 与 primary 标记`() = runTest {
        val body: String = """
            {"versions":[{"id":"v1","project_id":"p1","name":"Sodium 0.5",
              "version_number":"0.5.1","version_type":"release",
              "game_versions":["1.20.1"],"loaders":["fabric"],
              "downloads":1000,
              "files":[{"url":"https://cdn.modrinth.com/x.jar","filename":"sodium-0.5.1.jar",
                "primary":true,"size":123456,"sha1":"abc","sha512":"def"}]}]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = api.versions("sodium", gameVersion = "1.20.1", loader = "fabric")

        assertTrue(result.isOk)
        val version: ModrinthVersion = result.getOrNull()!!.versions.single()
        assertEquals("0.5.1", version.version_number)
        val file: ModrinthFile = version.files.single()
        assertEquals("sodium-0.5.1.jar", file.filename)
        assertEquals("abc", file.sha1)
        assertEquals(true, file.primary)
    }

    @Test
    fun `非 200 返回错误码`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"not_found\",\"description\":\"x\"}"))
        val result = api.search("ghost")
        assertTrue(result.isErr)
        assertEquals("modrinth_http_404", result.errorOrNull()?.code)
    }
}
