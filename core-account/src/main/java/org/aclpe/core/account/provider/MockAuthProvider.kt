package org.aclpe.core.account.provider

import org.aclpe.core.account.contract.AuthProvider
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.aclpe.core.common.result.AppResult

/**
 * Mock 认证提供者：返回固定假 token/UUID（用户规格）。
 * **仅用于单元测试与开发演示，严禁在生产路径使用**（硬性约束：认证只走微软官方 OAuth2+PKCE 流）。
 */
object MockAuthProvider : AuthProvider {

    const val MOCK_ACCOUNT_ID: String = "mock-account"
    const val MOCK_UUID: String = "00000000-0000-0000-0000-000000000001"
    const val MOCK_TOKEN: String = "mock_access_token_do_not_use_in_production"

    override val type: AccountType = AccountType.MICROSOFT

    override suspend fun login(): AppResult<AuthSession> =
        AppResult.Ok(
            AuthSession(
                account = Account(id = MOCK_ACCOUNT_ID, type = type, username = "MockPlayer", uuid = MOCK_UUID),
                tokens = AuthTokens(accessToken = MOCK_TOKEN, expiresAt = Long.MAX_VALUE),
            )
        )

    override suspend fun logout(session: AuthSession): AppResult<Unit> = AppResult.Ok(Unit)

    override suspend fun refresh(session: AuthSession): AppResult<AuthSession> = AppResult.Ok(session)

    override suspend fun skinUrl(session: AuthSession): AppResult<String?> =
        AppResult.Ok("https://textures.minecraft.net/texture/0000000000000000000000000000000000000000000000000000000000000000")
}
