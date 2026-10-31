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
}
