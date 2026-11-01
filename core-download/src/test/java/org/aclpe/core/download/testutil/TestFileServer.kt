package org.aclpe.core.download.testutil

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 测试服务器（MockWebServer）：
 * - 支持单段 Range（bytes=a-b 与 bytes=a-），返回 206 + Content-Range；
 * - 可注入失败次数（前 N 次 500）与状态码覆盖、可注册多路径（镜像轮换测试）；
 * - 统计每个路径的请求次数（重试/续传断言）。
 * 注：不直接用 JDK 的 com.sun.net.httpserver —— 其位于 jdk.httpserver 模块，
 * Android 单测的编译类路径（android.jar）不含该模块。
 */
class TestFileServer {

    private val server: MockWebServer = MockWebServer()
    private val contents: MutableMap<String, ByteArray> = mutableMapOf()
    private val failTimes: MutableMap<String, Int> = mutableMapOf()
    private val statusOverride: MutableMap<String, Int> = mutableMapOf()
    private val rangePaths: MutableSet<String> = mutableSetOf()
    val requestCounts: MutableMap<String, AtomicInteger> = mutableMapOf()

    val port: Int get() = server.port
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    fun url(path: String): String = server.url(path).toString()

    fun serve(path: String, bytes: ByteArray, supportRange: Boolean = true, failFirst: Int = 0, forceStatus: Int? = null) {
        contents[path] = bytes
        failTimes[path] = failFirst
        statusOverride[path] = forceStatus ?: -1
        requestCounts[path] = AtomicInteger(0)
        if (supportRange) rangePaths += path
    }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path: String = request.requestUrl?.encodedPath ?: "/"
                requestCounts[path]?.incrementAndGet()

                val body: ByteArray = contents[path] ?: return MockResponse().setResponseCode(404)

                val fail: Int = failTimes[path] ?: 0
                if (fail > 0) {
                    failTimes[path] = fail - 1
                    return MockResponse().setResponseCode(500)
                }
                val override: Int = statusOverride[path] ?: -1
                if (override > 0) {
                    return MockResponse().setResponseCode(override).setBody(Buffer().write(body))
                }

                if (path in rangePaths) {
                    val rangeHeader: String? = request.getHeader("Range")
                    if (rangeHeader != null) {
                        val m: MatchResult? = Regex("""bytes=(\d+)-(\d*)""").find(rangeHeader)
                        if (m != null) {
                            val start: Long = m.groupValues[1].toLong()
                            val end: Long = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: (body.size - 1).toLong()
                            if (start >= body.size) {
                                return MockResponse().setResponseCode(416)
                                    .setHeader("Content-Range", "bytes */${body.size}")
                            }
                            val sliceEnd: Int = minOf(end, body.size - 1L).toInt()
                            return MockResponse().setResponseCode(206)
                                .setHeader("Content-Range", "bytes $start-$sliceEnd/${body.size}")
                                .setBody(Buffer().write(body, start.toInt(), sliceEnd - start.toInt() + 1))
                        }
                    }
                }
                return MockResponse().setResponseCode(200).setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    fun stop() {
        server.shutdown()
    }
}
