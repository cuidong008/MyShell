package com.dxkj.myshell.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.dxkj.myshell.ui.screens.FilesScreen
import com.dxkj.myshell.ui.screens.HostsScreen
import com.dxkj.myshell.ui.screens.HostEditScreen
import com.dxkj.myshell.ui.screens.KeysScreen
import com.dxkj.myshell.ui.screens.TerminalScreen

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val items = listOf(
        BottomTab.Hosts,
        BottomTab.Terminal,
        BottomTab.Files,
        BottomTab.Keys,
    )

    val showBottomBar = currentRoute in items.map { it.route }

    Scaffold(
        topBar = {
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
        },
        bottomBar = {
            if (showBottomBar) {
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
        NavHost(
            navController = navController,
            startDestination = BottomTab.Hosts.route,
            modifier = Modifier,
        ) {
            composable(BottomTab.Hosts.route) {
                HostsScreen(
                    contentPadding = innerPadding,
                    onAddHost = { navController.navigate("host_edit?hostId=-1") },
                    onEditHost = { id -> navController.navigate("host_edit?hostId=$id") },
                )
            }
            composable(BottomTab.Terminal.route) { TerminalScreen(contentPadding = innerPadding) }
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
        }
    }
}

