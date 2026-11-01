package org.aclpe.core.launch.args

import org.aclpe.core.launch.model.LaunchContext
import org.aclpe.core.launch.model.MemoryTier

/**
 * 内存档位策略（硬性约束：-Xmx ≤ 可用内存一半）：
 * heap = min(档位值, max(512MB, 设备可用内存/2))，下限 512MB 防低内存设备 OOM。
 */
object MemoryPolicy {

    const val MIN_HEAP_MB: Int = 512

    fun heapMb(tier: MemoryTier, deviceMemoryMb: Int): Int {
        val half: Int = deviceMemoryMb / 2
        val cap: Int = maxOf(MIN_HEAP_MB, half)
        return minOf(tier.heapMb, cap)
    }

    /**
     * 生成内存/GC/日志参数：
     * -Xmx、G1GC、ReservedCodeCacheSize=512m；GC 日志按 javaMajor 分流
     * （Java 9+ 用统一日志 -Xlog:gc*，Java 8 用 -Xloggc）。
     */
    fun args(ctx: LaunchContext, gcLogDir: String): List<String> {
        val heap: Int = heapMb(ctx.memoryTier, ctx.deviceMemoryMb)
        val gcLog: String = if (ctx.javaMajor >= 9) {
            "-Xlog:gc*,safepoint=info:file=$gcLogDir/gc-%p.log:time,uptime:filecount=3,filesize=1m"
        } else {
            "-Xloggc:$gcLogDir/gc.log"
        }
        return listOf(
            "-Xmx${heap}M",
            "-XX:+UseG1GC",
            "-XX:ReservedCodeCacheSize=512m",
            gcLog,
        )
    }
}
