package org.aclpe.core.launch.args

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {

    @Test
    fun `Xmx 不超过可用内存一半`() {
        assertEquals(2048, MemoryPolicy.heapMb(org.aclpe.core.launch.model.MemoryTier.MEDIUM, deviceMemoryMb = 4096))
        assertEquals(1536, MemoryPolicy.heapMb(org.aclpe.core.launch.model.MemoryTier.EXTREME, deviceMemoryMb = 3072))
    }

    @Test
    fun `低内存设备下限 512`() {
        assertEquals(512, MemoryPolicy.heapMb(org.aclpe.core.launch.model.MemoryTier.MEDIUM, deviceMemoryMb = 512))
        assertEquals(512, MemoryPolicy.heapMb(org.aclpe.core.launch.model.MemoryTier.EXTREME, deviceMemoryMb = 512))
    }

    @Test
    fun `档位上限生效`() {
        assertEquals(1024, MemoryPolicy.heapMb(org.aclpe.core.launch.model.MemoryTier.LIGHT, deviceMemoryMb = 8192))
    }

    @Test
    fun `GC 日志参数按 javaMajor 分流`() {
        val ctx = org.aclpe.core.launch.model.LaunchContext(
            version = org.aclpe.core.download.json.VersionJson(id = "x", mainClass = "M"),
            auth = org.aclpe.core.launch.model.LaunchAuth("p", "u"),
            gameDir = java.io.File("/tmp/g"),
            assetsDir = java.io.File("/tmp/a"),
            assetsIndexId = "i",
            nativesDir = java.io.File("/tmp/n"),
            libraries = emptyList(),
            clientJar = java.io.File("/tmp/c.jar"),
            javaMajor = 17,
        )
        val args17: List<String> = MemoryPolicy.args(ctx, "/tmp/g/logs")
        assertTrue(args17.any { it.startsWith("-Xlog:gc*,safepoint") })
        assertTrue(args17.any { it == "-XX:+UseG1GC" })
        assertTrue(args17.any { it == "-XX:ReservedCodeCacheSize=512m" })
        assertTrue(args17.any { it.startsWith("-Xmx") })

        val args8: List<String> = MemoryPolicy.args(ctx.copy(javaMajor = 8), "/tmp/g/logs")
        assertTrue(args8.any { it.startsWith("-Xloggc:") })
    }
}
