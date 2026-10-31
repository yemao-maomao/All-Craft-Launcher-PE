package org.aclpe.core.env.store

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 环境组件持久化条目（launcher.db 表 env_items）。
 * state 存 [org.aclpe.core.env.fsm.EnvItemState] 名；component 为全局唯一组件键。
 */
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
