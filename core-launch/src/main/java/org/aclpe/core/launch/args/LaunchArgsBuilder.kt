package org.aclpe.core.launch.args

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.aclpe.core.download.json.Rule
import org.aclpe.core.download.rules.PlatformContext
import org.aclpe.core.download.rules.RulesEngine
import org.aclpe.core.launch.model.LaunchContext

/**
 * 启动参数拼装（Step 3 核心）：
 * - 现代模板（arguments.game/jvm）：逐条展开，对象条目按 rules 过滤（复用 Step 2 的
 *   [RulesEngine]，同一 PlatformContext/OsSpec 常量源——硬性约束），value 支持单串/数组；
 * - 遗留模板（minecraftArguments）：引号感知分词 + 占位符替换；
 * - classpath 顺序敏感：库列表顺序 + client jar（与官方 launcher 等价）；
 * - 注入规则：模板已含 -cp 则不重复；mainClass 恒由启动器追加；内存参数恒由 [MemoryPolicy] 生成。
 */
object LaunchArgsBuilder {

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun classpathString(ctx: LaunchContext): String {
        val parts: List<String> = buildList {
            ctx.libraries.forEach { add(it.artifact.path!!) }
            add(clientJarRelative(ctx))
        }
        return parts.joinToString(org.aclpe.core.common.platform.OsSpec.SEPARATOR.toString())
    }

    /** client jar 相对版本目录路径（遗留版本无 downloads.client 时回退传统位置）。 */
    private fun clientJarRelative(ctx: LaunchContext): String {
        val downloadPath: String? = ctx.version.downloads?.client?.path
        return downloadPath ?: "versions/${ctx.version.id}/${ctx.version.id}.jar"
    }

    fun buildJvmArgs(ctx: LaunchContext): List<String> {
        val values: Map<String, String> = Placeholders.map(ctx, classpathString(ctx))
        val template: List<String> = ctx.version.arguments?.jvm
            ?.let { expandArgs(it, values) }
            .orEmpty()

        return buildList {
            addAll(template)
            // 模板已含 -cp 则不重复（1.20.1 起官方模板自带）
            if (template.none { it == "-cp" }) {
                add("-cp")
                add(values.getValue("classpath"))
            }
            addAll(MemoryPolicy.args(ctx, ctx.gameDir.absolutePath + "/logs"))
            add(ctx.version.mainClass)
        }
    }

    fun buildGameArgs(ctx: LaunchContext): List<String> {
        val values: Map<String, String> = Placeholders.map(ctx, classpathString(ctx))
        val modern: List<JsonElement>? = ctx.version.arguments?.game
        return if (modern != null) {
            expandArgs(modern, values)
        } else {
            tokenizeLegacy(ctx.version.minecraftArguments.orEmpty()).map { Placeholders.replace(it, values) }
        }
    }

    /** 现代模板展开：字符串直接替换；对象按 rules 过滤后取 value（单串或数组）。 */
    internal fun expandArgs(entries: List<JsonElement>, values: Map<String, String>): List<String> =
        buildList {
            for (entry in entries) {
                when (entry) {
                    is JsonPrimitive -> if (entry.isString) add(Placeholders.replace(entry.content, values))
                    is JsonObject -> {
                        val rulesElement: JsonElement? = entry["rules"]
                        if (rulesElement != null) {
                            val rules: List<Rule> = runCatching {
                                json.decodeFromJsonElement<List<Rule>>(rulesElement)
                            }.getOrNull() ?: emptyList()
                            if (!RulesEngine.evaluate(rules, PlatformContext.fromOsSpec())) continue
                        }
                        val value: JsonElement = entry["value"] ?: continue
                        when (value) {
                            is JsonPrimitive -> if (value.isString) add(Placeholders.replace(value.content, values))
                            is JsonArray -> value.forEach { item ->
                                if (item is JsonPrimitive && item.isString) {
                                    add(Placeholders.replace(item.content, values))
                                }
                            }
                            else -> Unit
                        }
                    }
                    else -> Unit
                }
            }
        }

    /** 遗留 minecraftArguments 分词：引号感知（"a b" 保持单 token）。 */
    internal fun tokenizeLegacy(argLine: String): List<String> {
        val tokens: MutableList<String> = mutableListOf()
        val current: StringBuilder = StringBuilder()
        var inQuotes: Boolean = false
        for (ch in argLine) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ' ' && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }
}
