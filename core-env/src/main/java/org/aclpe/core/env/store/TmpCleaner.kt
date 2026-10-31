package org.aclpe.core.env.store

import java.io.File

/**
 * downloads/tmp 幂等清理规则（设计文档 §5.3，纯函数可测）：
 * - `<component>.part`：仅当存在对应活动任务（DB 在途）时保留（断点续传载体），否则删除；
 * - `.staging`：一律删除（解压无断点意义）；
 * - 其他未知文件：删除（脏残留）。
 * 组件键含 ':'，Windows 文件名不允许该字符，故落盘文件名将 ':' 替换为 '_'。
 */
object TmpCleaner {

    /** 返回 (toDelete, toKeep)。 */
    fun plan(tmpDir: File, activeComponents: Set<String>): Pair<List<File>, List<File>> {
        if (!tmpDir.isDirectory) return emptyList<File>() to emptyList<File>()
        val files: List<File> = tmpDir.listFiles()?.toList().orEmpty()
        val toDelete: MutableList<File> = mutableListOf()
        val toKeep: MutableList<File> = mutableListOf()
        for (file in files) {
            when {
                file.name.endsWith(STAGING_SUFFIX) -> toDelete += file
                file.name.endsWith(PART_SUFFIX) -> {
                    val component: String = fileNameToComponent(file.name.removeSuffix(PART_SUFFIX))
                    if (component in activeComponents) toKeep += file else toDelete += file
                }
                else -> toDelete += file
            }
        }
        return toDelete to toKeep
    }

    /** 组件键 → 文件名（' : ' 在文件名中非法，替换为 '_'）。 */
    fun componentToFileName(component: String): String = component.replace(':', '_')

    /** 文件名 → 组件键（'_' 回 ':'；不还原名称中本来就有的 '_'，这是已知取舍：组件键中的 '_' 无需回查）。 */
    fun fileNameToComponent(fileName: String): String = fileName.replace('_', ':')

    const val PART_SUFFIX: String = ".part"
    const val STAGING_SUFFIX: String = ".staging"
}
