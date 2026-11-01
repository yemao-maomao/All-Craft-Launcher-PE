package org.aclpe.core.settings.options

import org.aclpe.core.common.result.AppError
import org.aclpe.core.common.result.AppResult
import java.io.File

/**
 * options.txt 行模型（顺序保真）：
 * - 注释（# 开头）与空行原样保留（序列化回写不丢格式）；
 * - 无冒号的行视为注释行保留（不进入键值视图——即「非法值丢弃」语义，但不破坏回写保真）；
 * - 键值以第一个 ':' 分割（值可含冒号，如按键映射 key.mouse.left:key.mouse.middle）。
 */
sealed interface OptionsLine {
    data class Comment(val text: String) : OptionsLine
    data object Blank : OptionsLine
    data class Entry(val key: String, val value: String) : OptionsLine
}

/** 变更项（对比差异用）。 */
data class OptionsChange(val key: String, val oldValue: String?, val newValue: String?)

/**
 * options.txt 双向解析/序列化：
 * - [parse] 容忍坏行（保留为注释），未知 key 原样保留（模组自定义项不丢）；
 * - [serialize] 顺序保真；[writeAtomic] 先备份（.bak，仅保留一份）再临时文件写入 → rename，
 *   任何失败保留原文件（硬性约束：写入失败保留原文件）。
 */
class OptionsFile(val lines: List<OptionsLine>) {

    fun entries(): LinkedHashMap<String, String> {
        val map: LinkedHashMap<String, String> = LinkedHashMap()
        lines.forEach { line ->
            if (line is OptionsLine.Entry) map[line.key] = line.value
        }
        return map
    }

    fun get(key: String): String? = entries()[key]

    fun with(key: String, value: String): OptionsFile {
        val updated: MutableList<OptionsLine> = mutableListOf()
        var replaced: Boolean = false
        for (line in lines) {
            when {
                line is OptionsLine.Entry && line.key == key -> {
                    updated += OptionsLine.Entry(key, value)
                    replaced = true
                }
                else -> updated += line
            }
        }
        if (!replaced) updated += OptionsLine.Entry(key, value)
        return OptionsFile(updated)
    }

    fun without(key: String): OptionsFile =
        OptionsFile(lines.filterNot { it is OptionsLine.Entry && it.key == key })

    fun serialize(): String = buildString {
        lines.forEach { line ->
            when (line) {
                is OptionsLine.Comment -> append(line.text).append('\n')
                OptionsLine.Blank -> append('\n')
                is OptionsLine.Entry -> append(line.key).append(':').append(line.value).append('\n')
            }
        }
    }

    /** 与另一份对比，返回变更键列表（只比较键值，忽略注释/空行差异）。 */
    fun diff(other: OptionsFile): List<OptionsChange> {
        val mine: Map<String, String> = entries()
        val theirs: Map<String, String> = other.entries()
        val keys: Set<String> = mine.keys + theirs.keys
        return keys.mapNotNull { key ->
            val old: String? = mine[key]
            val new: String? = theirs[key]
            if (old == new) null else OptionsChange(key, old, new)
        }
    }

    companion object {

        fun parse(text: String): OptionsFile {
            val lines: MutableList<OptionsLine> = mutableListOf()
            // 末尾换行符是行终止符而非空行（split 会产生多余 "" 元素，丢弃之，保证 round-trip）
            val rawLines: List<String> = text.split('\n')
            val content: List<String> = if (rawLines.lastOrNull() == "") rawLines.dropLast(1) else rawLines
            content.forEach { rawLine ->
                val line: String = rawLine.trimEnd('\r')
                when {
                    line.isBlank() -> lines += OptionsLine.Blank
                    line.trimStart().startsWith("#") -> lines += OptionsLine.Comment(line)
                    else -> {
                        val idx: Int = line.indexOf(':')
                        if (idx <= 0) {
                            // 无冒号的坏行：保留为注释（键值视图不可见 = 丢弃语义，回写保真）
                            lines += OptionsLine.Comment(line)
                        } else {
                            lines += OptionsLine.Entry(line.substring(0, idx), line.substring(idx + 1))
                        }
                    }
                }
            }
            return OptionsFile(lines)
        }

        /**
         * 原子写入 + 备份：先备份现有文件为 .bak（仅一份），写临时文件 → rename。
         * [runningCheck] 返回 true（游戏运行中）时拒绝写入（硬性约束：游戏运行时禁止覆盖）。
         */
        fun writeAtomic(file: File, options: OptionsFile, runningCheck: () -> Boolean = { false }): AppResult<Unit> =
            try {
                if (runningCheck()) {
                    return AppResult.Err(AppError.Io("game_running", "游戏运行中，禁止覆盖 options.txt"))
                }
                file.parentFile?.mkdirs()
                if (file.isFile) {
                    File(file.parentFile, file.name + ".bak").let { bak ->
                        file.copyTo(bak, overwrite = true)
                    }
                }
                val tmp: File = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(options.serialize())
                // NIO 原子替换：Windows 上 File.renameTo 无法覆盖已存在目标
                try {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (t: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                tmp.delete()
                AppResult.Ok(Unit)
            } catch (t: Throwable) {
                AppResult.Err(AppError.Io("options_write_failed", cause = t))
            }

        /** 回退：用 .bak 恢复（崩溃/误改后手动触发）。 */
        fun restoreBak(file: File): AppResult<Unit> = try {
            val bak: File = File(file.parentFile, file.name + ".bak")
            if (!bak.isFile) return AppResult.Err(AppError.Io("no_bak", "无备份可回退"))
            bak.copyTo(file, overwrite = true)
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            AppResult.Err(AppError.Io("bak_restore_failed", cause = t))
        }
    }
}
