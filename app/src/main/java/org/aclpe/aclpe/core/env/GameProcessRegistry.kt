package org.aclpe.aclpe.core.env

/**
 * 游戏进程运行状态注册表（core-settings 的「游戏运行时禁止覆盖 options.txt」检查源）。
 * 由启动链路在进程启动/退出时更新。
 */
object GameProcessRegistry {

    @Volatile
    private var running: Boolean = false

    val isRunning: Boolean get() = running

    fun markRunning(value: Boolean) {
        running = value
    }
}
