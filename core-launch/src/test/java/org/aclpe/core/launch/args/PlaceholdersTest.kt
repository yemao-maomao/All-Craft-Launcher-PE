package org.aclpe.core.launch.args

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholdersTest {

    @Test
    fun `全部关键占位符被替换`() {
        val input: String = "\${auth_player_name} \${version_name} \${natives_directory} \${arch} \${classpath_separator}"
        val output: String = Placeholders.replace(
            input,
            mapOf(
                "auth_player_name" to "Steve",
                "version_name" to "1.20.1",
                "natives_directory" to "/n",
                "arch" to "arm64",
                "classpath_separator" to ":",
            ),
        )
        assertEquals("Steve 1.20.1 /n arm64 :", output)
    }

    @Test
    fun `未知占位符原样保留`() {
        val out: String = Placeholders.replace("--flag \${unknown_thing}", emptyMap())
        assertEquals("--flag \${unknown_thing}", out)
    }

    @Test
    fun `classpath 与 classpath_separator 组合`() {
        val out: String = Placeholders.replace(
            "-cp \${classpath}",
            mapOf("classpath" to "a.jar:b.jar"),
        )
        assertEquals("-cp a.jar:b.jar", out)
    }

    @Test
    fun `遗留分词_引号感知`() {
        val tokens: List<String> = LaunchArgsBuilder.tokenizeLegacy(
            "--username Steve --demo --fullscreen --custom \"a b c\" --tail"
        )
        assertEquals(listOf("--username", "Steve", "--demo", "--fullscreen", "--custom", "a b c", "--tail"), tokens)
        assertTrue(LaunchArgsBuilder.tokenizeLegacy("").isEmpty())
    }
}
