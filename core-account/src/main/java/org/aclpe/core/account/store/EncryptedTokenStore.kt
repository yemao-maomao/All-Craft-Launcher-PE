package org.aclpe.core.account.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.aclpe.core.account.contract.TokenStore
import org.aclpe.core.account.model.Account
import org.aclpe.core.account.model.AccountType
import org.aclpe.core.account.model.AuthSession
import org.aclpe.core.account.model.AuthTokens
import org.aclpe.core.common.result.AppResult
import org.aclpe.core.common.result.resultOf
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Token 加密存储（用户规格：Android Keystore + AES-GCM）。
 * - 密钥：AndroidKeyStore 内 AES-256/GCM/NoPadding，永不离开 Keystore；
 * - 落盘：files/accounts/<accountId>/tokens.enc（iv‖密文，每次写入新随机 IV）；
 * - 读取失败视为无 token（Err 仅在 IO 异常时抛出）；
 * - 离线账户 token 为空：仍走加密路径，只是密文为空 token（行为一致）。
 * JVM 单测不覆盖本类（依赖 Android Keystore），真机手工步骤见设计文档；契约级测试用 FakeTokenStore。
 */
class EncryptedTokenStore(
    private val root: File,
    private val keystoreAlias: String = KEY_ALIAS,
) : TokenStore {

    private val accountsDir: File = File(root, "accounts")

    override suspend fun save(session: AuthSession): AppResult<Unit> = resultOf {
        val dir: File = File(accountsDir, session.account.id)
        dir.mkdirs()
        val plain: String = buildString {
            append(session.account.type.name).append('\n')
            append(session.tokens.accessToken).append('\n')
            append(session.tokens.refreshToken ?: "").append('\n')
            append(session.tokens.expiresAt?.toString() ?: "").append('\n')
        }
        File(dir, TOKENS_FILE).writeBytes(encrypt(plain.toByteArray(Charsets.UTF_8)))
    }

    override suspend fun load(accountId: String): AppResult<AuthSession?> = resultOf {
        val file: File = File(File(accountsDir, accountId), TOKENS_FILE)
        if (!file.isFile) return@resultOf null
        val plain: String = decrypt(file.readBytes()).toString(Charsets.UTF_8)
        val lines: List<String> = plain.split('\n')
        if (lines.size < 4) return@resultOf null
        AuthSession(
            account = Account(
                id = accountId,
                type = runCatching { AccountType.valueOf(lines[0]) }.getOrDefault(AccountType.LOCAL_OFFLINE),
                username = "legacy",   // 用户名以 AccountStore 元数据为准（此处仅做 token 载体）
                uuid = "",
            ),
            tokens = AuthTokens(
                accessToken = lines[1],
                refreshToken = lines[2].takeIf { it.isNotEmpty() },
                expiresAt = lines[3].toLongOrNull(),
            ),
        )
    }

    override suspend fun delete(accountId: String): AppResult<Unit> = resultOf {
        File(File(accountsDir, accountId), TOKENS_FILE).delete()
    }

    private fun getKey(): SecretKey {
        val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator: KeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(keystoreAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val iv: ByteArray = payload.copyOfRange(0, IV_SIZE)
        val encrypted: ByteArray = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    companion object {
        const val KEY_ALIAS: String = "aclpe_account_tokens"
        const val TOKENS_FILE: String = "tokens.enc"
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val IV_SIZE: Int = 12
    }
}
