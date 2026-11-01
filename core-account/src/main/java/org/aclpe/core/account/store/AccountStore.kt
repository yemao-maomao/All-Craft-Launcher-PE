package org.aclpe.core.account.store

import kotlinx.serialization.json.Json
import org.aclpe.core.account.model.Account
import java.io.File

/**
 * 账号元数据存储契约（不含 token；token 经 TokenStore 加密）。
 * Profile ↔ Account 绑定：每个 profileId 可设独立活跃账号（切换 Profile 即切换账号上下文）。
 */
interface AccountStore {

    suspend fun list(): List<Account>

    suspend fun save(account: Account)

    suspend fun delete(accountId: String)

    /** 指定 Profile 的活跃账号 id；未绑定返回 null。 */
    suspend fun active(profileId: String): String?

    suspend fun setActive(profileId: String, accountId: String)
}

/**
 * 文件实现（存储布局）：files/accounts/<id>/account.json + files/accounts/active-<profileId>.txt。
 */
class FileAccountStore(
    private val root: File,
    private val serializer: (Account) -> String,
    private val deserializer: (String) -> Account?,
) : AccountStore {

    private val accountsDir: File = File(root, "accounts")

    override suspend fun list(): List<Account> {
        if (!accountsDir.isDirectory) return emptyList()
        return accountsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> File(dir, "account.json").takeIf { it.isFile }?.readText()?.let(deserializer) }
    }

    override suspend fun save(account: Account) {
        val dir: File = File(accountsDir, account.id)
        dir.mkdirs()
        File(dir, "account.json").writeText(serializer(account))
    }

    override suspend fun delete(accountId: String) {
        File(accountsDir, accountId).deleteRecursively()
    }

    override suspend fun active(profileId: String): String? {
        val file: File = File(accountsDir, "active-$profileId.txt")
        return if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    override suspend fun setActive(profileId: String, accountId: String) {
        accountsDir.mkdirs()
        File(accountsDir, "active-$profileId.txt").writeText(accountId)
    }

    companion object {
        /** 生产工厂：kotlinx JSON 序列化。 */
        fun create(root: File): FileAccountStore {
            val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            return FileAccountStore(
                root = root,
                serializer = { account: Account -> json.encodeToString(Account.serializer(), account) },
                deserializer = { text: String ->
                    runCatching { json.decodeFromString(Account.serializer(), text) }.getOrNull()
                },
            )
        }
    }
}
