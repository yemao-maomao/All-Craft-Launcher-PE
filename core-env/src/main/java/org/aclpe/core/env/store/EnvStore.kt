package org.aclpe.core.env.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.io.File

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

@Database(entities = [EnvItemEntity::class], version = 1, exportSchema = false)
abstract class EnvDatabase : RoomDatabase() {

    abstract fun envItemDao(): EnvItemDao

    companion object {
        @Volatile
        private var instance: EnvDatabase? = null

        /**
         * 数据库按存储布局放在 files/launcher.db（-wal/-shm 随行，同目录）。
         * Room 2.8 的 databaseBuilder 无 File 重载，传绝对路径字符串即可落在指定目录。
         * 单例进程内共享。
         */
        fun get(context: Context): EnvDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EnvDatabase::class.java,
                File(context.filesDir, "launcher.db").absolutePath,
            ).build().also { instance = it }
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
