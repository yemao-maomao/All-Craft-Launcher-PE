package org.aclpe.core.account.model

import kotlinx.serialization.Serializable

/** 账号类型：微软（本期仅预留契约）与本地离线账户。 */
@Serializable
enum class AccountType {
    MICROSOFT, LOCAL_OFFLINE,
}

/**
 * 账号（不含 token——token 单独经 TokenStore 加密存储）。
 * LOCAL_OFFLINE：uuid 本机生成；无皮肤同步、无法联机、可能违反 EULA（UI 必须明确标注，不得默认推荐）。
 */
@Serializable
data class Account(
    val id: String,
    val type: AccountType,
    val username: String,
    val uuid: String,
)

/** 认证 token（日志绝不出现明文——脱敏门面兜底；TokenStore 加密落盘）。 */
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    /** 过期时间（epoch ms）；离线账户为 null。 */
    val expiresAt: Long? = null,
)

/** 登录会话：账号 + token。 */
data class AuthSession(
    val account: Account,
    val tokens: AuthTokens,
)
