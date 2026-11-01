package org.aclpe.core.account.provider

import org.aclpe.core.account.contract.AuthProvider
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.aclpe.core.common.result.AppResult
import java.util.UUID

/**
 * 本地离线账户（本期正式实现）：
 * - 本机生成 UUID 作为游戏身份；无 token（accessToken 空串）；
 * - 无皮肤同步、无法联机、可能违反 Minecraft EULA——UI 必须明确标注（用户规格），
 *   本类只负责身份生成，不负责提示文案；
 * - 账号名由构造参数注入（UI 创建对话框采集），login() 幂等：返回新生成会话。
 */
class LocalOfflineAccountProvider(private val username: String) : AuthProvider {

    override val type: AccountType = AccountType.LOCAL_OFFLINE

    override suspend fun login(): AppResult<AuthSession> =
        AppResult.Ok(
            AuthSession(
                account = Account(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    username = username,
                    uuid = UUID.randomUUID().toString(),
                ),
                tokens = AuthTokens(accessToken = "", refreshToken = null, expiresAt = null),
            )
        )

    override suspend fun logout(session: AuthSession): AppResult<Unit> = AppResult.Ok(Unit)

    override suspend fun refresh(session: AuthSession): AppResult<AuthSession> = AppResult.Ok(session)

    override suspend fun skinUrl(session: AuthSession): AppResult<String?> = AppResult.Ok(null)
}
