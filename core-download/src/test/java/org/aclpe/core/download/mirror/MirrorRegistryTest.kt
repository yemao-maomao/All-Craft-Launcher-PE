package org.aclpe.core.download.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorRegistryTest {

    @Test
    fun `官方源原样返回`() {
        val url = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
        assertEquals(url, MirrorRegistry.rewrite(url, MirrorId.OFFICIAL))
    }

    @Test
    fun `BMCLAPI 重写各类官方地址`() {
        assertEquals(
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            MirrorRegistry.rewrite("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json", MirrorId.BMCLAPI),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/v1/packages/x/1.json",
            MirrorRegistry.rewrite("https://piston-meta.mojang.com/v1/packages/x/1.json", MirrorId.BMCLAPI),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
            MirrorRegistry.rewrite("https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", MirrorId.BMCLAPI),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/assets/ab/abcdef1234",
            MirrorRegistry.rewrite("https://resources.download.minecraft.net/ab/abcdef1234", MirrorId.BMCLAPI),
        )
    }

    @Test
    fun `不可重写地址原样返回`() {
        val githubUrl = "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/x/y.tar.xz"
        assertEquals(githubUrl, MirrorRegistry.rewrite(githubUrl, MirrorId.BMCLAPI))
    }

    @Test
    fun `启用的镜像列表_官方与 BMCLAPI_Gitee 预留`() {
        val ids: List<MirrorId> = MirrorRegistry.enabledIds()
        assertTrue(MirrorId.OFFICIAL in ids)
        assertTrue(MirrorId.BMCLAPI in ids)
        assertFalse(MirrorId.GITEE in ids)   // 端点待定，暂不启用
    }
}
