package org.aclpe.core.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aclpe.core.account.contract.AuthProvider
import org.aclpe.core.account.contract.TokenStore
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.store.AccountStore
import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult

/**
 * 账号仓库（契约落地）：登录/登出/活跃账号/会话（含过期自动刷新）。
 * 全部基于 [AuthProvider]/[TokenStore]/[AccountStore] 契约——未来微软登录仅新增 Provider 实现即可。
 */
class AccountRepository(
    private val store: AccountStore,
    private val tokenStore: TokenStore,
) {

    private val accounts: MutableStateFlow<List<Account>> = MutableStateFlow(emptyList())

    fun observeAccounts(): Flow<List<Account>> = accounts

    /** 登录：provider.login → 持久化 → 设为该 Profile 活跃账号。 */
    suspend fun login(provider: AuthProvider, profileId: String): AppResult<Account> {
        val session: AuthSession = when (val r = provider.login()) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> return r
        }
        store.save(session.account)
        tokenStore.save(session)
        store.setActive(profileId, session.account.id)
        refreshCache()
        return AppResult.Ok(session.account)
    }

    suspend fun logout(profileId: String): AppResult<Unit> {
        val activeId: String = store.active(profileId) ?: return AppResult.Ok(Unit)
        val account: Account = accounts.value.firstOrNull { it.id == activeId }
            ?: return AppResult.Ok(Unit)
        tokenStore.delete(activeId)
        store.delete(activeId)
        refreshCache()
        return AppResult.Ok(Unit)
    }

    suspend fun activeAccount(profileId: String): AppResult<Account?> =
        AppResult.Ok(accounts.value.firstOrNull { it.id == store.active(profileId) })

    /**
     * 活跃会话（token）：过期（剩余 < 60s）且可刷新时自动经 provider.refresh 续期；
     * 无 provider（离线）直接返回存储会话。
     */
    suspend fun activeSession(profileId: String, provider: AuthProvider? = null): AppResult<AuthSession?> {
        val accountId: String = store.active(profileId) ?: return AppResult.Ok(null)
        val session: AuthSession = when (val r = tokenStore.load(accountId)) {
            is AppResult.Ok -> r.value ?: return AppResult.Ok(null)
            is AppResult.Err -> return r
        }
        val expiresAt: Long? = session.tokens.expiresAt
        if (expiresAt != null && expiresAt < System.currentTimeMillis() + REFRESH_WINDOW_MS) {
            val refreshed: AppResult<AuthSession> = provider?.refresh(session) ?: AppResult.Ok(session)
            when (refreshed) {
                is AppResult.Ok -> {
                    tokenStore.save(refreshed.value)
                    return AppResult.Ok(refreshed.value)
                }
                is AppResult.Err -> return refreshed
            }
        }
        return AppResult.Ok(session)
    }

    private suspend fun refreshCache() {
        accounts.value = store.list()
    }

    companion object {
        const val REFRESH_WINDOW_MS: Long = 60_000L
    }
}
