package org.aclpe.core.env.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.io.File

// ---------------------------------------------------------------------------
// 实体（launcher.db 唯一所有者 = 本模块；业务实体随模块演进归拢于此，
// 因为存储布局规定单一 launcher.db 文件，多模块各建一个 RoomDatabase 会互相破坏 schema）
// ---------------------------------------------------------------------------

/** 环境组件条目（表 env_items）。state 存 EnvItemState 名。 */
@Entity(tableName = "env_items")
data class EnvItemEntity(
    @PrimaryKey val component: String,
    val state: String,
    val buildId: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val progressBytes: Long = 0L,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val COMPONENT_JRE_PREFIX: String = "jre:"
        const val COMPONENT_NATIVES_PREFIX: String = "natives:"
        const val COMPONENT_ASSETS_PREFIX: String = "assets:"

        fun jre(javaMajor: Int): String = "$COMPONENT_JRE_PREFIX$javaMajor"
        fun natives(hash: String): String = "$COMPONENT_NATIVES_PREFIX$hash"
        fun assets(indexId: String): String = "$COMPONENT_ASSETS_PREFIX$indexId"
    }
}

/** 下载任务条目（表 download_tasks）。state 存 DownloadTaskState 名；进程重启后可据此续传。 */
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val url: String,
    val mirrorUrl: String? = null,
    val destPath: String,
    val sha1: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val progressBytes: Long = 0L,
    val state: String,
    val retryCount: Int = 0,
    val errorCode: String? = null,
    val updatedAt: Long = 0L,
)

/** 已安装/已知版本（表 versions）。 */
@Entity(tableName = "versions")
data class VersionEntity(
    @PrimaryKey val id: String,
    val type: String,
    val javaMajor: Int? = null,
    val assetsIndexId: String? = null,
    val assetsIndexSha1: String? = null,
    val installed: Boolean = false,
    val installedAt: Long? = null,
    val updatedAt: Long = 0L,
)

/** 全局池化库条目（表 libraries，group:name:ver:hash 去重）。 */
@Entity(tableName = "libraries", primaryKeys = ["groupId", "artifactId", "version", "hash"])
data class LibraryEntryEntity(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val hash: String,
    val path: String,
    val sha1: String? = null,
    val sizeBytes: Long? = null,
    val installedAt: Long = 0L,
)

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
interface EnvItemDao {

    @Query("SELECT * FROM env_items ORDER BY component")
    fun observeAll(): Flow<List<EnvItemEntity>>

    @Query("SELECT * FROM env_items WHERE component = :component")
    suspend fun get(component: String): EnvItemEntity?

    @Upsert
    suspend fun upsert(item: EnvItemEntity)

    @Query("UPDATE env_items SET state = :state, retryCount = :retryCount, errorCode = :errorCode, updatedAt = :updatedAt WHERE component = :component")
    suspend fun updateState(component: String, state: String, retryCount: Int, errorCode: String?, updatedAt: Long)

    @Query("UPDATE env_items SET progressBytes = :progress WHERE component = :component")
    suspend fun updateProgress(component: String, progress: Long)
}

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY id")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun get(id: String): DownloadTaskEntity?

    @Upsert
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET progressBytes = :progress, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, progress: Long, updatedAt: Long)

    @Query("UPDATE download_tasks SET state = :state, errorCode = :errorCode, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, errorCode: String?, updatedAt: Long)
}

@Dao
interface VersionDao {

    @Query("SELECT * FROM versions ORDER BY id")
    fun observeAll(): Flow<List<VersionEntity>>

    @Upsert
    suspend fun upsert(version: VersionEntity)
}

@Dao
interface LibraryEntryDao {

    @Query("SELECT * FROM libraries")
    fun observeAll(): Flow<List<LibraryEntryEntity>>

    @Upsert
    suspend fun upsert(entry: LibraryEntryEntity)
}

// ---------------------------------------------------------------------------
// 数据库
// ---------------------------------------------------------------------------

@Database(
    entities = [EnvItemEntity::class, DownloadTaskEntity::class, VersionEntity::class, LibraryEntryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LauncherDatabase : RoomDatabase() {

    abstract fun envItemDao(): EnvItemDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun versionDao(): VersionDao
    abstract fun libraryEntryDao(): LibraryEntryDao

    companion object {
        @Volatile
        private var instance: LauncherDatabase? = null

        /** v1 → v2：新增 download_tasks。 */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `download_tasks` (
                        `id` TEXT NOT NULL, `url` TEXT NOT NULL, `mirrorUrl` TEXT, `destPath` TEXT NOT NULL,
                        `sha1` TEXT, `sha256` TEXT, `sizeBytes` INTEGER, `progressBytes` INTEGER NOT NULL,
                        `state` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, `errorCode` TEXT,
                        `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
                )
            }
        }

        /** v2 → v3：新增 versions 与 libraries。 */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `versions` (
                        `id` TEXT NOT NULL, `type` TEXT NOT NULL, `javaMajor` INTEGER, `assetsIndexId` TEXT,
                        `assetsIndexSha1` TEXT, `installed` INTEGER NOT NULL, `installedAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `libraries` (
                        `groupId` TEXT NOT NULL, `artifactId` TEXT NOT NULL, `version` TEXT NOT NULL,
                        `hash` TEXT NOT NULL, `path` TEXT NOT NULL, `sha1` TEXT, `sizeBytes` INTEGER,
                        `installedAt` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `artifactId`, `version`, `hash`))"""
                )
            }
        }

        /**
         * 数据库按存储布局放在 files/launcher.db（-wal/-shm 随行）。
         * Room 2.8 的 databaseBuilder 无 File 重载，传绝对路径字符串即可落在指定目录。
         */
        fun get(context: Context): LauncherDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LauncherDatabase::class.java,
                File(context.filesDir, "launcher.db").absolutePath,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}

/** 存储抽象：EnvBootstrap 只依赖本接口（Room 实现 / 内存实现可互换，便于 JVM 单测）。 */
interface EnvStore {
    fun observe(): Flow<List<EnvItemEntity>>
    suspend fun get(component: String): EnvItemEntity?
    suspend fun upsert(item: EnvItemEntity)
}

/** Room 实现。 */
class RoomEnvStore(private val dao: EnvItemDao) : EnvStore {
    override fun observe(): Flow<List<EnvItemEntity>> = dao.observeAll()
    override suspend fun get(component: String): EnvItemEntity? = dao.get(component)
    override suspend fun upsert(item: EnvItemEntity) = dao.upsert(item)
}
