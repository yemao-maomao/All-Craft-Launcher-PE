package org.aclpe.core.account

import kotlinx.coroutines.test.runTest
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.aclpe.core.account.provider.LocalOfflineAccountProvider
import org.aclpe.core.account.provider.MockAuthProvider
import org.aclpe.core.account.testutil.FakeTokenStore
import org.aclpe.core.account.testutil.InMemoryAccountStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * AccountRepository 集成测试（Mock/离线 Provider + 内存存储，契约级）：
 * 登录/活跃账号/登出/会话刷新语义。
 */
class AccountRepositoryTest {

    private fun repo(): Pair<AccountRepository, FakeTokenStore> {
        val tokens: FakeTokenStore = FakeTokenStore()
        return AccountRepository(InMemoryAccountStore(), tokens) to tokens
    }

    @Test
    fun `离线账户登录_持久化并置活跃_无 token`() = runTest {
        val (repo, tokens) = repo()
        val result = repo.login(LocalOfflineAccountProvider("Steve"), profileId = "p1")
        assertTrue(result.isOk)
        val account: Account = result.getOrNull()!!
        assertEquals(AccountType.LOCAL_OFFLINE, account.type)
        assertEquals("Steve", account.username)
        // uuid 合法格式
        assertNotNull(UUID.fromString(account.uuid))

        val active = repo.activeAccount("p1")
        assertEquals(account.id, active.getOrNull()!!.id)

        val session: AuthSession = repo.activeSession("p1").getOrNull()!!
        assertEquals("", session.tokens.accessToken)
        assertNull(session.tokens.expiresAt)
    }

    @Test
    fun `Mock 提供者返回固定假身份`() = runTest {
        val (repo, _) = repo()
        val result = repo.login(MockAuthProvider, profileId = "p1")
        assertTrue(result.isOk)
        assertEquals(MockAuthProvider.MOCK_UUID, result.getOrNull()!!.uuid)
        assertEquals(AccountType.MICROSOFT, result.getOrNull()!!.type)
    }

    @Test
    fun `登出清除账号与 token`() = runTest {
        val (repo, tokens) = repo()
        repo.login(LocalOfflineAccountProvider("Steve"), profileId = "p1")
        assertTrue(repo.logout("p1").isOk)
        assertNull(repo.activeAccount("p1").getOrNull())
        assertNull(repo.activeSession("p1").getOrNull())
        assertEquals(1, tokens.saveCount)   // save 仅登录发生一次，登出不写
    }

    @Test
    fun `切换 Profile 隔离活跃账号`() = runTest {
        val (repo, _) = repo()
        repo.login(LocalOfflineAccountProvider("A"), profileId = "p1")
        repo.login(LocalOfflineAccountProvider("B"), profileId = "p2")
        assertEquals("A", repo.activeAccount("p1").getOrNull()!!.username)
        assertEquals("B", repo.activeAccount("p2").getOrNull()!!.username)
        // 登出 p1 不影响 p2
        repo.logout("p1")
        assertNull(repo.activeAccount("p1").getOrNull())
        assertEquals("B", repo.activeAccount("p2").getOrNull()!!.username)
    }

    @Test
    fun `无活跃账号时登录返回空会话`() = runTest {
        val (repo, _) = repo()
        assertNull(repo.activeSession("nobody").getOrNull())
        assertNull(repo.activeAccount("nobody").getOrNull())
    }

    @Test
    fun `过期的微软会话自动刷新`() = runTest {
        val store: InMemoryAccountStore = InMemoryAccountStore()
        val tokens: FakeTokenStore = FakeTokenStore()
        val repo = AccountRepository(store, tokens)
        // 手动放入一个即将过期的微软会话
        val account = Account(id = "ms1", type = AccountType.MICROSOFT, username = "M", uuid = "u1")
        store.save(account)
        store.setActive("p1", "ms1")
        tokens.save(AuthSession(account, AuthTokens("old", refreshToken = "r", expiresAt = 0L)))

        val refreshedToken = "new_token"
        val provider = object : org.aclpe.core.account.contract.AuthProvider {
            override val type: AccountType = AccountType.MICROSOFT
            override suspend fun login(): org.aclpe.core.common.result.AppResult<AuthSession> = error("not used")
            override suspend fun logout(session: AuthSession): org.aclpe.core.common.result.AppResult<Unit> =
                org.aclpe.core.common.result.AppResult.Ok(Unit)
            override suspend fun refresh(session: AuthSession): org.aclpe.core.common.result.AppResult<AuthSession> =
                org.aclpe.core.common.result.AppResult.Ok(
                    session.copy(tokens = session.tokens.copy(accessToken = refreshedToken, expiresAt = Long.MAX_VALUE))
                )
            override suspend fun skinUrl(session: AuthSession): org.aclpe.core.common.result.AppResult<String?> =
                org.aclpe.core.common.result.AppResult.Ok(null)
        }
        val session = repo.activeSession("p1", provider).getOrNull()!!
        assertEquals(refreshedToken, session.tokens.accessToken)
        // 刷新结果已回写
        assertEquals(refreshedToken, tokens.load("ms1").getOrNull()!!.tokens.accessToken)
    }
}
