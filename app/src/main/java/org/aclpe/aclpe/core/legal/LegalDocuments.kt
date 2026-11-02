package org.aclpe.aclpe.core.legal

import android.content.Context

/**
 * 法务文档（《ACL-PE 隐私政策》+《ACL-PE 用户协议》）：
 * - 正文放在 APK 资产目录 `assets/legal/` 下的 Markdown 文件（可审计、可随版本更新）；
 * - [VERSION] 是两份文件的共同版本号：**与用户本地同意记录比对**，
 *   不一致 → 下次启动强制重新弹窗并重新同意（用户协议第 1.2 条）；
 * - 版本号变更时，必须同步修改两份文档顶部的"版本/生效日期"。
 */
object LegalDocuments {

    /** 两份文件的当前版本（修订文档时同步提升）。 */
    const val VERSION: String = "1.0.0"

    /** 协议相对方（用户协议"接收与适用范围"中的主体名称）。 */
    const val DEVELOPER: String = "Said a L"

    const val CONTACT_EMAIL: String = "vyemao520@gmail.com"

    const val PRIVACY_TITLE: String = "ACL-PE 隐私政策"
    const val AGREEMENT_TITLE: String = "ACL-PE 用户协议"

    private const val PRIVACY_ASSET: String = "legal/privacy-policy.md"
    private const val AGREEMENT_ASSET: String = "legal/user-agreement.md"

    /** 同意状态未从 DataStore 读出时的哨兵（避免首帧误判为"已同意"）。 */
    const val NOT_LOADED: String = "__not_loaded__"

    fun privacyPolicy(context: Context): String = readAsset(context, PRIVACY_ASSET)

    fun userAgreement(context: Context): String = readAsset(context, AGREEMENT_ASSET)

    private fun readAsset(context: Context, path: String): String = try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (t: Throwable) {
        // 资产缺失不应让用户卡死：返回可读的兜底说明（构建配置错误会被发现）
        "（文档加载失败：$path。请重新安装应用，或前往开源仓库查看全文。）"
    }
}