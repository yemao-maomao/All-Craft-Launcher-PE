package org.aclpe.core.common.platform

/**
 * 平台身份常量 —— 硬性约束：core-download 的 rules 求值引擎（Step 2）与 core-launch 的
 * 启动参数拼装（Step 3）必须共用本常量源，禁止各自硬编码，防止「宣称值」与「实际值」漂移。
 *
 * 取值说明（Step 2 用 6 个真实 version json golden 测试锁定后生效）：
 * - [NAME] = "linux"：Android 上向 MC 版本 rules 声明 Linux 兼容平台，用于选中 LWJGL linux 系 natives；
 * - [ARCH] = "arm64"：rules 中 os.arch 的声明值；
 * - [ABI]  = "aarch64"：实际 Android ABI（arm64-v8a 的 ELF 机器标识），用于 JRE 目录与动态库加载；
 * - [SEPARATOR]：classpath 分隔符（linux 为 ':'）。
 */
object OsSpec {
    const val NAME: String = "linux"
    const val ARCH: String = "arm64"
    const val ABI: String = "aarch64"
    const val SEPARATOR: Char = ':'

    /**
     * os.version：Android 无等价概念。version json 里的 os.version 条件（多为 macOS 版本正则）
     * 按空串处理 → 正则不匹配（安全）。rules 引擎（core-download）必须经 [PlatformContext.fromOsSpec]
     * 取用本常量，禁止自行硬编码。
     */
    const val OS_VERSION: String = ""

    /**
     * features：启动器能力声明（如 has_custom_resolution / is_demo_user）。
     * 骨架期为空集：规则中 features 条件一律不匹配 → 不引入对应启动参数（安全默认）；
     * 后续实现对应能力时在此声明，rules 引擎与启动参数拼装（Step 3）自动同步。
     */
    val FEATURES: Set<String> = emptySet()
}
