package org.aclpe.core.env.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.aclpe.core.env.bootstrap.DownloadScheduler
import org.aclpe.core.env.bootstrap.EnvDownloadState
import org.aclpe.core.env.bootstrap.EnvDownloadTask
import org.aclpe.core.env.probe.ExecResult
import org.aclpe.core.env.probe.ProcessExecutor
import org.aclpe.core.env.store.EnvItemEntity
import org.aclpe.core.env.store.EnvStore
import java.io.File

/** 伪造进程执行器：返回固定结果，记录最近一次命令。 */
class FakeProcessExecutor(
    var result: ExecResult,
) : ProcessExecutor {
    var lastCommand: List<String>? = null
        private set
    var callCount: Int = 0
        private set

    override fun execute(command: List<String>, env: Map<String, String>, workingDir: File?, timeoutMs: Long): ExecResult {
        lastCommand = command
        callCount++
        return result
    }
}

/** 内存版 EnvStore：JVM 单测用。 */
class InMemoryEnvStore : EnvStore {
    private val items = MutableStateFlow<List<EnvItemEntity>>(emptyList())

    override fun observe(): Flow<List<EnvItemEntity>> = items

    override suspend fun get(component: String): EnvItemEntity? = items.value.firstOrNull { it.component == component }

    override suspend fun upsert(item: EnvItemEntity) {
        items.value = items.value.filterNot { it.component == item.component } + item
    }

    fun snapshot(): List<EnvItemEntity> = items.value
}

/** 记录式下载调度器：只记录入队/暂停/恢复/取消调用，不做真实下载。 */
class FakeDownloadScheduler : DownloadScheduler {
    val enqueued = mutableListOf<EnvDownloadTask>()
    val paused = mutableListOf<String>()
    val resumed = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    private val statesFlow = MutableStateFlow<List<EnvDownloadState>>(emptyList())

    override val states: Flow<List<EnvDownloadState>> = statesFlow

    override fun enqueue(task: EnvDownloadTask) {
        enqueued += task
    }

    override fun pause(component: String) {
        paused += component
    }

    override fun resume(component: String) {
        resumed += component
    }

    override fun cancel(component: String) {
        cancelled += component
    }
}

/** 标准 OpenJDK 17 输出样例（64-Bit Server）。 */
const val OUTPUT_JRE17_SERVER: String = """
openjdk version "17.0.5" 2022-10-18
OpenJDK Runtime Environment (build 17.0.5+8-1)
OpenJDK 64-Bit Server VM (build 17.0.5+8-1, mixed mode, sharing)
"""

/** OpenJDK 21 输出样例。 */
const val OUTPUT_JRE21_SERVER: String = """
openjdk version "21.0.1" 2023-10-17
OpenJDK Runtime Environment (build 21.0.1+12)
OpenJDK 64-Bit Server VM (build 21.0.1+12, mixed mode, sharing)
"""

/** Java 8 遗留格式输出样例。 */
const val OUTPUT_JRE8_SERVER: String = """
java version "1.8.0_292"
Java(TM) SE Runtime Environment (build 1.8.0_292-b10)
Java HotSpot(TM) 64-Bit Server VM (build 25.292-b10, mixed mode)
"""
