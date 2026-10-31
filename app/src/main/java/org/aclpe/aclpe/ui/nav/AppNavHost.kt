package org.aclpe.aclpe.ui.nav

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.aclpe.aclpe.core.AppSettings
import org.aclpe.aclpe.ui.home.HomeViewModel
import org.aclpe.aclpe.ui.screens.AboutScreen
import org.aclpe.aclpe.ui.screens.HomeScreen
import org.aclpe.aclpe.ui.screens.SettingsScreen
import org.aclpe.aclpe.ui.screens.VersionsScreen

/** 导航宿主：底部导航 + 页面切换（单 Activity 壳）。 */
@Composable
fun AppNavHost(appSettings: AppSettings, homeViewModel: HomeViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute: String? = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.all.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Home.route) { HomeScreen(homeViewModel) }
            composable(Destination.Versions.route) { VersionsScreen() }
            composable(Destination.Settings.route) { SettingsScreen(appSettings) }
            composable(Destination.About.route) { AboutScreen() }
        }
    }
}
