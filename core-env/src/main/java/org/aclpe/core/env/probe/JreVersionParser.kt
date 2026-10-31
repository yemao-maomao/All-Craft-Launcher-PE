package org.aclpe.core.env.probe

/**
 * `java -version` 输出解析结果。
 * Android 上 OpenJDK 构建（Pojav 系）版本信息输出在 stderr，且格式随构建有差异，
 * 因此解析采用「宽松正则 + 严格断言」：正则容忍差异，位宽/编译器断言不放松。
 */
data class JreVersionInfo(
    val major: Int,
    val minor: Int? = null,
    val patch: Int? = null,
    val bits: Bits,
    val vm: VmType,
) {
    enum class Bits { BITS_64, BITS_32, UNKNOWN }
    enum class VmType { SERVER, CLIENT, ZERO, UNKNOWN }
}

/** `java -version` 输出解析器（纯函数，JVM 可测）。 */
object JreVersionParser {

    /**
     * 解析失败（找不到版本号）返回 null，调用方按 ExecFailed 处理。
     */
    fun parse(output: String): JreVersionInfo? {
        val match: MatchResult = VERSION_REGEX.find(output) ?: return null
        val rawMajor: Int = match.groupValues[1].toIntOrNull() ?: return null
        val rawMinor: Int? = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        // 遗留格式：java version "1.8.0_292"（Java 8 及更早）→ 主版本记为 8
        val major: Int = if (rawMajor == 1 && rawMinor == 8) 8 else rawMajor
        val minor: Int? = if (major == 8 && rawMinor == 8) null else rawMinor
        val patch: Int? = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val bits: JreVersionInfo.Bits = when {
            output.contains("64-Bit") || output.contains("aarch64") -> JreVersionInfo.Bits.BITS_64
            output.contains("32-Bit") -> JreVersionInfo.Bits.BITS_32
            else -> JreVersionInfo.Bits.UNKNOWN
        }
        val vm: JreVersionInfo.VmType = when {
            output.contains("Server VM") -> JreVersionInfo.VmType.SERVER
            output.contains("Client VM") -> JreVersionInfo.VmType.CLIENT
            output.contains("Zero VM") -> JreVersionInfo.VmType.ZERO
            else -> JreVersionInfo.VmType.UNKNOWN
        }
        return JreVersionInfo(major = major, minor = minor, patch = patch, bits = bits, vm = vm)
    }

    // version "17.0.5" / version "21-internal" / version 1.8.0_292 均可解析出主版本
    private val VERSION_REGEX: Regex = Regex("""(?i)version\s+"?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
}
