package org.aclpe.core.launch.args

import org.aclpe.core.common.platform.OsSpec
import org.aclpe.core.launch.model.LaunchContext

/**
 * 占位符替换表（硬性约束：os/arch 经 OsSpec 单一常量源，与 Step 2 rules 引擎一致）。
 * 未知占位符保留原样（官方 launcher 行为：不静默吞掉，便于发现模板变化）。
 */
internal object Placeholders {

    private val PATTERN: Regex = Regex("""\$\{([a-zA-Z0-9_]+)\}""")

    fun map(ctx: LaunchContext, classpathString: String): Map<String, String> = mapOf(
        "auth_player_name" to ctx.auth.name,
        "version_name" to ctx.version.id,
        "game_directory" to ctx.gameDir.absolutePath,
        "assets_root" to ctx.assetsDir.absolutePath,
        "assets_index_name" to ctx.assetsIndexId,
        "auth_uuid" to ctx.auth.uuid,
        "auth_access_token" to ctx.auth.accessToken,
        "auth_session" to "",                       // 历史占位符，offline 空
        "clientid" to "",
        "auth_xuid" to ctx.auth.xuid,
        "user_type" to ctx.auth.userType,
        "user_properties" to "{}",
        "version_type" to ctx.version.type,
        "natives_directory" to ctx.nativesDir.absolutePath,
        "launcher_name" to ctx.launcherName,
        "launcher_version" to ctx.launcherVersion,
        "classpath" to classpathString,
        "classpath_separator" to OsSpec.SEPARATOR.toString(),
        "arch" to OsSpec.ARCH,
    )

    fun replace(input: String, values: Map<String, String>): String =
        PATTERN.replace(input) { match ->
            values[match.groupValues[1]] ?: match.value   // 未知占位符原样保留
        }
}
