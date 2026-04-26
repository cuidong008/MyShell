package com.dxkj.myshell.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.dxkj.myshell.ui.screens.FilesScreen
import com.dxkj.myshell.ui.screens.HostsScreen
import com.dxkj.myshell.ui.screens.HostEditScreen
import com.dxkj.myshell.ui.screens.KeysScreen
import com.dxkj.myshell.ui.screens.TerminalFullScreen
import com.dxkj.myshell.ui.screens.TerminalHubScreen
import com.dxkj.myshell.ui.screens.TerminalScreen

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val isWide = cfg.screenWidthDp >= 840
    val useRail = isLandscape && isWide

    val items = listOf(
        BottomTab.Hosts,
        BottomTab.Terminal,
        BottomTab.Files,
        BottomTab.Keys,
    )

    val isTerminalFull = currentRoute?.startsWith("terminal_full/") == true || currentRoute?.startsWith("terminal_hub") == true
    val showBottomBar = (currentRoute in items.map { it.route }) && !isTerminalFull

    Scaffold(
        topBar = {
            if (!isTerminalFull) {
                TopAppBar(
                    title = {
                        Text(
                            when (currentRoute) {
                                BottomTab.Hosts.route -> BottomTab.Hosts.label
                                BottomTab.Terminal.route -> BottomTab.Terminal.label
                                BottomTab.Files.route -> BottomTab.Files.label
                                BottomTab.Keys.route -> BottomTab.Keys.label
                                "host_edit?hostId={hostId}" -> "编辑主机"
                                else -> "MyShell"
                            },
                        )
                    },
                    navigationIcon = {
                        if (!showBottomBar) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "back")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showBottomBar && !useRail) {
                NavigationBar {
                    items.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (showBottomBar && useRail) {
                NavigationRail {
                    items.forEach { tab ->
                        NavigationRailItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = BottomTab.Hosts.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(BottomTab.Hosts.route) {
                    HostsScreen(
                        contentPadding = innerPadding,
                        onAddHost = { navController.navigate("host_edit?hostId=-1") },
                        onEditHost = { id -> navController.navigate("host_edit?hostId=$id") },
                    )
                }
                composable(BottomTab.Terminal.route) {
                    TerminalScreen(
                        contentPadding = innerPadding,
                        onOpenFullTerminal = { hostId -> navController.navigate("terminal_hub?hostId=$hostId") },
                    )
                }
                composable(BottomTab.Files.route) { FilesScreen(contentPadding = innerPadding) }
                composable(BottomTab.Keys.route) { KeysScreen(contentPadding = innerPadding) }

            composable(
                route = "host_edit?hostId={hostId}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
            ) { entry ->
                val hostId = entry.arguments?.getLong("hostId") ?: -1L
                HostEditScreen(
                    contentPadding = innerPadding,
                    hostId = hostId.takeIf { it > 0 },
                    onDone = { navController.popBackStack() },
                )
            }

            composable(
                route = "terminal_full/{hostId}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
            ) { entry ->
                val hostId = entry.arguments?.getLong("hostId") ?: -1L
                TerminalFullScreen(
                    hostId = hostId,
                    onExit = { navController.popBackStack() },
                )
            }

            composable(
                route = "terminal_hub?hostId={hostId}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                val hostId = entry.arguments?.getLong("hostId") ?: -1L
                TerminalHubScreen(
                    initialHostId = hostId.takeIf { it > 0 },
                    onExit = { navController.popBackStack() },
                )
            }
            }
        }
    }
}

