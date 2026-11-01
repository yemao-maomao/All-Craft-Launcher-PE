package org.aclpe.core.account.testutil

import org.aclpe.core.account.contract.TokenStore
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.store.AccountStore
import org.aclpe.core.common.result.AppResult

/** 内存版 AccountStore（JVM 单测）。 */
class InMemoryAccountStore : AccountStore {
    private val accounts: MutableMap<String, Account> = mutableMapOf()
    private val active: MutableMap<String, String> = mutableMapOf()

    override suspend fun list(): List<Account> = accounts.values.toList()
    override suspend fun save(account: Account) {
        accounts[account.id] = account
    }

    override suspend fun delete(accountId: String) {
        accounts.remove(accountId)
    }

    override suspend fun active(profileId: String): String? = active[profileId]
    override suspend fun setActive(profileId: String, accountId: String) {
        active[profileId] = accountId
    }
}

/** 内存版 TokenStore（JVM 单测；加密实现在真机手工验证）。 */
class FakeTokenStore : TokenStore {
    private val sessions: MutableMap<String, AuthSession> = mutableMapOf()
    var saveCount: Int = 0
        private set

    override suspend fun save(session: AuthSession): AppResult<Unit> {
        saveCount++
        sessions[session.account.id] = session
        return AppResult.Ok(Unit)
    }

    override suspend fun load(accountId: String): AppResult<AuthSession?> = AppResult.Ok(sessions[accountId])

    override suspend fun delete(accountId: String): AppResult<Unit> {
        sessions.remove(accountId)
        return AppResult.Ok(Unit)
    }
}
