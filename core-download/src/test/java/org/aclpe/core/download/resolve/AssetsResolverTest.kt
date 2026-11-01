package org.aclpe.core.download.resolve

import org.aclpe.core.download.mirror.MirrorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetsResolverTest {

    @Test
    fun `索引解析与分片`() {
        val index: String = """{"objects":{"a/b.txt":{"hash":"abcd1234","size":100},"c/d.png":{"hash":"efab5678","size":200}}}"""
        val result = AssetsResolver.parseIndex(index)
        assertTrue(result.isOk)
        val objects = result.getOrNull()!!.objects
        assertEquals(2, objects.size)

        val shards: List<List<AssetsFile>> = AssetsResolver.shard(objects, maxFilesPerShard = 1)
        assertEquals(2, shards.size)
        assertEquals("a/b.txt", shards[0][0].name)
    }

    @Test
    fun `资源地址_官方与镜像`() {
        assertEquals(
            "https://resources.download.minecraft.net/ab/abcd1234",
            AssetsResolver.objectUrl("abcd1234", MirrorId.OFFICIAL),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/assets/ab/abcd1234",
            AssetsResolver.objectUrl("abcd1234", MirrorId.BMCLAPI),
        )
    }
}
