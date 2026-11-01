package org.aclpe.core.download.rules

import org.aclpe.core.download.json.OsRule
import org.aclpe.core.download.json.Rule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rules 求值引擎用例集（附件 A 未提供全文，按官方语义 + 用户规格自拟：
 * 有序匹配、默认 disallow、os.name/arch/version、features、空规则不设防）。
 */
class RulesEngineTest {

    private val linuxArm64: PlatformContext = PlatformContext(
        osName = "linux", osVersion = "", osArch = "arm64", features = emptySet(),
    )

    private fun rule(action: String, os: OsRule? = null, features: Map<String, Boolean>? = null): Rule =
        Rule(action = action, os = os, features = features)

    @Test
    fun `空规则列表不设防_视为允许`() {
        assertTrue(RulesEngine.evaluate(emptyList(), linuxArm64))
    }

    @Test
    fun `有序匹配_第一个命中者决定`() {
        val allowLinuxThenDisallowAll: List<Rule> = listOf(
            rule("allow", OsRule(name = "linux")),
            rule("disallow"),
        )
        assertTrue(RulesEngine.evaluate(allowLinuxThenDisallowAll, linuxArm64))

        val disallowLinuxThenAllowAll: List<Rule> = listOf(
            rule("disallow", OsRule(name = "linux")),
            rule("allow"),
        )
        assertFalse(RulesEngine.evaluate(disallowLinuxThenAllowAll, linuxArm64))
    }

    @Test
    fun `无命中默认 disallow`() {
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(name = "osx"))), linuxArm64))
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(name = "windows"))), linuxArm64))
    }

    @Test
    fun `os name 精确匹配`() {
        assertTrue(RulesEngine.evaluate(listOf(rule("allow", OsRule(name = "linux"))), linuxArm64))
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(name = "Linux"))), linuxArm64))  // 大小写敏感（官方语义）
    }

    @Test
    fun `os arch 精确匹配`() {
        assertTrue(RulesEngine.evaluate(listOf(rule("allow", OsRule(arch = "arm64"))), linuxArm64))
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(arch = "x86"))), linuxArm64))
    }

    @Test
    fun `os version 整串正则`() {
        val ctx: PlatformContext = linuxArm64.copy(osVersion = "10.12.2")
        assertTrue(RulesEngine.evaluate(listOf(rule("allow", OsRule(version = "^10\\.12\\.2$"))), ctx))
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(version = "^11\\."))), ctx))
        // 空版本（Android）不匹配任何版本正则
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", OsRule(version = "^10\\."))), linuxArm64))
    }

    @Test
    fun `features_缺失视为 false`() {
        // {x:true} 需特性存在
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", features = mapOf("has_custom_resolution" to true))), linuxArm64))
        // {x:false} 需特性不存在 → 命中
        assertTrue(RulesEngine.evaluate(listOf(rule("allow", features = mapOf("is_demo_user" to false))), linuxArm64))
        // 特性存在时按值判定
        val withFeature: PlatformContext = linuxArm64.copy(features = setOf("has_custom_resolution"))
        assertTrue(RulesEngine.evaluate(listOf(rule("allow", features = mapOf("has_custom_resolution" to true))), withFeature))
        // is_demo_user 特性存在（演示模式用户）→ {is_demo_user:false} 规则不命中
        val demoUser: PlatformContext = linuxArm64.copy(features = setOf("is_demo_user"))
        assertFalse(RulesEngine.evaluate(listOf(rule("allow", features = mapOf("is_demo_user" to false))), demoUser))
    }

    @Test
    fun `action 大小写不敏感`() {
        assertTrue(RulesEngine.evaluate(listOf(rule("ALLOW", OsRule(name = "linux"))), linuxArm64))
    }

    @Test
    fun `多条件同时满足才命中`() {
        val both: List<Rule> = listOf(rule("allow", OsRule(name = "linux", arch = "arm64")))
        assertTrue(RulesEngine.evaluate(both, linuxArm64))
        assertFalse(RulesEngine.evaluate(both, linuxArm64.copy(osArch = "x86_64")))
    }
}
