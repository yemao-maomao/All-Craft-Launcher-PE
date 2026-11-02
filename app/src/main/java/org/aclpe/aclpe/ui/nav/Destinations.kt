package org.aclpe.aclpe.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import org.aclpe.aclpe.R

/** 一级导航目的地：底部导航五页（社区资源 2026-11-02 上架）。 */
sealed class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object Home : Destination("home", R.string.tab_home, Icons.Filled.Home)
    data object Versions : Destination("versions", R.string.tab_versions, Icons.AutoMirrored.Filled.List)
    data object Community : Destination("community", R.string.tab_community, Icons.Filled.Search)
    data object Settings : Destination("settings", R.string.tab_settings, Icons.Filled.Settings)
    data object About : Destination("about", R.string.tab_about, Icons.Filled.Info)

    companion object {
        val all: List<Destination> = listOf(Home, Versions, Community, Settings, About)
    }
}
