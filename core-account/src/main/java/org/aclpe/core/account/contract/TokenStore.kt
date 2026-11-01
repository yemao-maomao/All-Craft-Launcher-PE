package org.aclpe.core.account.contract

import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.common.result.AppResult

/**
 * Token 加密存储契约（用户规格：Android Keystore + AES-GCM）。
 * 实现：[org.aclpe.core.account.store.EncryptedTokenStore]；
 * 测试替身：FakeTokenStore（内存，JVM 单测用）。
 */
interface TokenStore {

    suspend fun save(session: AuthSession): AppResult<Unit>

    suspend fun load(accountId: String): AppResult<AuthSession?>

    suspend fun delete(accountId: String): AppResult<Unit>
}
