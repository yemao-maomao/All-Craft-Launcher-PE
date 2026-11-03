package org.aclpe.core.download.net

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aclpe.core.download.mirror.MirrorId
import org.aclpe.core.download.mirror.MirrorRegistry
import org.aclpe.core.download.resolve.AssetsIndex
import org.aclpe.core.download.resolve.AssetsResolver
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载吞吐基线基准（**默认跳过**；`ACLPE_NETWORK=true` 开启）：
 * 同一批真实 assets 小文件（BMCLAPI 镜像），在**同一次运行内**对比四组参数的耗时，
 * 消除网络波动带来的漂移——用于验证「并发上限」与「信号量并发度」各自的影响。
 *
 * 组别：
 * A. 默认 Dispatcher（maxRequestsPerHost=5）+ 并发 8   ← 优化前实际状态
 * B. 解锁 Dispatcher（32/host）+ 并发 8
 * C. 解锁 Dispatcher（32/host）+ 并发 16
 * D. 解锁 Dispatcher（32/host）+ 并发 24
 */
class DownloadThroughputBenchmark {

    private val baseUrl: String = MirrorRegistry.rewrite(
        "https://resources.download.minecraft.net", MirrorId.BMCLAPI,
    )

    @Test
    fun `baseline assets 并发吞吐对照`() = kotlinx.coroutines.runBlocking {
        Assume.assumeTrue(
            "网络基准默认跳过：设置环境变量 ACLPE_NETWORK=true 开启",
            System.getenv("ACLPE_NETWORK") == "true",
        )

        // 取 1.20.1 assets 索引的真实对象（前 240 个）
        val indexJson: String = fetch("https://bmclapi2.bangbang93.com/v1/packages/62b36534d83c028050e6fafe8c742879d1f3f1ca/5.json")
        val index: AssetsIndex = AssetsResolver.parseIndex(indexJson).getOrNull()!!
        val sample = index.objects.entries.take(48).map { (name, obj) -> name to obj }

        val plainClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val fastClient: OkHttpClient = plainClient.newBuilder()
            .dispatcher(Dispatcher().apply {
                maxRequests = MAX_REQUESTS
                maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            })
            .build()

        val results: MutableList<Triple<String, Int, Long>> = mutableListOf()
        results += Triple("A 默认Dispatcher(5/host) x 并发8", 8, runRound(plainClient, 8, sample))
        results += Triple("B 解锁(32/host) x 并发8", 8, runRound(fastClient, 8, sample))
        results += Triple("C 解锁(32/host) x 并发16", 16, runRound(fastClient, 16, sample))
        results += Triple("D 解锁(32/host) x 并发24", 24, runRound(fastClient, 24, sample))

        println("=== 下载吞吐基线（同批 ${sample.size} 个真实 assets 小文件，BMCLAPI）===")
        val baseline: Long = results.first().third
        results.forEach { (label, _, ms) ->
            val speedup: Double = if (ms > 0) baseline.toDouble() / ms else 0.0
            println("$label: ${ms}ms (相对 A 提速 ${"%.2f".format(speedup)}x)")
        }
        assertTrue(results.all { it.third > 0 })
    }

    private suspend fun runRound(
        client: OkHttpClient,
        concurrency: Int,
        sample: List<Pair<String, org.aclpe.core.download.resolve.AssetsObject>>,
    ): Long = coroutineScope {
        val dir: File = File(System.getProperty("java.io.tmpdir"), "bench-${System.nanoTime()}").apply { mkdirs() }
        val manager = DownloadManager(client)
        val semaphore = Semaphore(concurrency)
        val started: Long = System.currentTimeMillis()
        sample.map { (name, obj) ->
            async {
                semaphore.withPermit {
                    val prefix: String = obj.hash.take(2)
                    val dest: File = File(dir, "$prefix/${obj.hash}")
                    manager.download(
                        DownloadRequest(
                            id = "bench:$name",
                            url = "$baseUrl/$prefix/${obj.hash}",
                            destFile = dest,
                            sha1 = obj.hash,
                            sizeBytes = obj.size,
                            chunkCount = 1,
                        )
                    )
                }
            }
        }.forEach { it.await() }
        val elapsed: Long = System.currentTimeMillis() - started
        dir.deleteRecursively()
        elapsed
    }

    private fun fetch(url: String): String {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val request: Request = Request.Builder().url(url).header("User-Agent", "ACL-PE-bench").build()
        return client.newCall(request).execute().use { it.body!!.string() }
    }

    companion object {
        const val MAX_REQUESTS: Int = 64
        const val MAX_REQUESTS_PER_HOST: Int = 32
    }
}