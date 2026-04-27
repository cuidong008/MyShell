package com.dxkj.myshell.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.animateDpAsState
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
import com.dxkj.myshell.ui.screens.OverviewScreen
import com.dxkj.myshell.ui.screens.SessionsScreen
import com.dxkj.myshell.ui.screens.TerminalFullScreen
import com.dxkj.myshell.ui.screens.TerminalHubScreen
import com.dxkj.myshell.ui.screens.TerminalScreen
import com.dxkj.myshell.terminal.TerminalSessionPool
import com.dxkj.myshell.terminal.SessionState
import com.dxkj.myshell.terminal.SafeEmulatorView
import jackpal.androidterm.emulatorview.EmulatorView
import androidx.compose.ui.viewinterop.AndroidView

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    // 响应式分档（更贴近真实设备）：
    // - >= 840dp：展开侧栏（含会话列表）
    // - 600–839dp：紧凑左侧导航 Rail（仅主 tab）
    // - < 600dp：底部导航
    val isExpanded = cfg.screenWidthDp >= 840 || cfg.smallestScreenWidthDp >= 840
    val isMedium = cfg.screenWidthDp >= 600 || cfg.smallestScreenWidthDp >= 600
    val useRail = isLandscape && isMedium
    val useSidebar = isLandscape && isExpanded

    // 底部导航：手机竖屏/小屏使用，保留“会话”入口
    val items = listOf(BottomTab.Servers, BottomTab.Sessions, BottomTab.Overview, BottomTab.Keys)
    // 侧栏/左侧导航：这里“会话”用动态列表呈现，因此主功能只保留 3 个 tab，避免重复出现两套“会话入口”
    val sideTabs = listOf(BottomTab.Servers, BottomTab.Overview, BottomTab.Keys)

    val isTerminalFull = currentRoute?.startsWith("terminal_full/") == true || currentRoute?.startsWith("terminal_hub") == true
    val showNav = !isTerminalFull
    val showBottomBar = showNav && !useRail
    val showRail = showNav && useRail

    TerminalSessionPool.init(context.applicationContext as android.app.Application)
    val allSessions by TerminalSessionPool.sessions.collectAsState()

    Scaffold(
        topBar = {
            // 全局顶栏统一隐藏：避免真机/虚拟机在不同尺寸与方向下表现不一致，并把垂直空间留给内容。
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { tab ->
                        val selected = when (tab) {
                            BottomTab.Sessions -> currentRoute?.startsWith(BottomTab.Sessions.route) == true
                            else -> currentRoute == tab.route
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (tab.route == BottomTab.Servers.route) {
                                    // 强制切回“服务器”，避免某些情况下回栈/恢复状态不生效
                                    navController.navigate(BottomTab.Servers.route) {
                                        popUpTo(BottomTab.Servers.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else if (tab.route == BottomTab.Sessions.route) {
                                    // 进入会话页时，尽量保持当前 active session；sid=-1 会让页面自行选中最后/当前会话
                                    navController.navigate("${BottomTab.Sessions.route}?sid=-1") {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                } else if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        launchSingleTop = true
                                        restoreState = true
                                    }
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
        // 横屏侧边栏模式下：不要给“整个右侧工作区”统一加大块顶部 padding（会显得空白很大）。
        // 改为由各页面的“顶部控件”自己避开状态栏（只对需要点击的顶栏做 statusBarsPadding）。
        val contentPaddingForPane = if (useRail) PaddingValues(0.dp) else innerPadding
        Box(modifier = Modifier.fillMaxSize()) {
            // 关键修复：当用户快速切换导航导致终端 UI 被销毁时，库内部会出现 TermKeyListener 为 null 的 NPE。
            // 这里常驻一个“不可见的 EmulatorView”，确保每个活跃 TermSession 始终有 KeyListener 绑定，避免崩溃。
            KeepAliveEmulatorViews(sessions = allSessions)

            Row(modifier = Modifier.fillMaxSize()) {
                if (showRail && useSidebar) {
                    val sessions = allSessions
                    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
                    // 复制按钮：复制“会话”（克隆连接），不走剪贴板
                    var renameSid by remember { mutableStateOf<Long?>(null) }
                    var renameText by remember { mutableStateOf("") }
                    var railExpanded by remember { mutableStateOf(true) }
                    val railWidth by animateDpAsState(targetValue = if (railExpanded) 180.dp else 0.dp, label = "railWidth")

                    // 折叠状态：在左侧边缘保留一个小把手按钮用于展开
                    if (!railExpanded) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .fillMaxHeight()
                                .systemBarsPadding(),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            IconButton(onClick = { railExpanded = true }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "open sidebar")
                            }
                        }
                    }

                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(railWidth),
                    ) {
                        if (railExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // 顶部：折叠按钮（参考 ShellBean iPad）
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    IconButton(onClick = { railExpanded = false }, modifier = Modifier.size(36.dp)) {
                                        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "collapse sidebar")
                                    }
                                }
                            // 顶部分组（无图标占位，文字右对齐，高亮选中）
                            sideTabs.forEach { tab ->
                                val selected = currentRoute == tab.route
                                SidebarRow(
                                    title = tab.label,
                                    selected = selected,
                                    onClick = {
                                        if (tab.route == BottomTab.Servers.route) {
                                            navController.navigate(BottomTab.Servers.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                                launchSingleTop = true
                                                restoreState = false
                                            }
                                        } else if (currentRoute != tab.route) {
                                            navController.navigate(tab.route) {
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    trailing = {},
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text("会话", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())

                            sessions.forEach { s ->
                                val selected = (activeId == s.sessionId) && (currentRoute?.startsWith(BottomTab.Sessions.route) == true)
                                val title = TerminalSessionPool.getDisplayTitle(s)
                                SidebarRow(
                                    title = title,
                                    selected = selected,
                                    onClick = {
                                        TerminalSessionPool.setActive(s.sessionId)
                                        navController.navigate("${BottomTab.Sessions.route}?sid=${s.sessionId}") { launchSingleTop = true }
                                    },
                                    trailing = {
                                        SmallIconButton(onClick = {
                                            renameSid = s.sessionId
                                            renameText = title
                                        }) {
                                            Icon(imageVector = Icons.Outlined.Edit, contentDescription = "rename")
                                        }
                                        SmallIconButton(onClick = { TerminalSessionPool.close(s.sessionId) }) {
                                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "delete")
                                        }
                                        SmallIconButton(onClick = {
                                            val newId = TerminalSessionPool.duplicateSession(s.sessionId)
                                            if (newId != null) {
                                                navController.navigate("${BottomTab.Sessions.route}?sid=$newId") { launchSingleTop = true }
                                            }
                                        }) {
                                            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "copy")
                                        }
                                    },
                                )
                            }
                            }
                        }
                    }

                    if (renameSid != null) {
                        val sid = renameSid!!
                        AlertDialog(
                            onDismissRequest = { renameSid = null },
                            title = { Text("重命名会话") },
                            text = {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                    label = { Text("名称") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    TerminalSessionPool.renameSession(sid, renameText)
                                    renameSid = null
                                }) { Text("确定") }
                            },
                            dismissButton = { TextButton(onClick = { renameSid = null }) { Text("取消") } },
                        )
                    }
                }
                if (showRail && !useSidebar) {
                    // 中等宽度设备：也展示“会话列表”，满足“点服务器创建会话 -> 左侧追加一条会话”的交互期望
                    val sessions = allSessions
                    val activeId by TerminalSessionPool.activeSessionId.collectAsState()
                    var railExpanded by remember { mutableStateOf(true) }
                    val railWidth by animateDpAsState(targetValue = if (railExpanded) 200.dp else 0.dp, label = "railWidthMedium")

                    if (!railExpanded) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .fillMaxHeight()
                                .systemBarsPadding(),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            IconButton(onClick = { railExpanded = true }, modifier = Modifier.size(40.dp)) {
                                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "open sidebar")
                            }
                        }
                    }
                    Surface(
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(railWidth),
                    ) {
                        if (railExpanded) {
                            var renameSid by remember { mutableStateOf<Long?>(null) }
                            var renameText by remember { mutableStateOf("") }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    IconButton(onClick = { railExpanded = false }, modifier = Modifier.size(36.dp)) {
                                        Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "collapse sidebar")
                                    }
                                }

                                sideTabs.forEach { tab ->
                                    val selected = currentRoute == tab.route
                                    SidebarRow(
                                        title = tab.label,
                                        selected = selected,
                                        onClick = {
                                            if (tab.route == BottomTab.Servers.route) {
                                                navController.navigate(BottomTab.Servers.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                                                    launchSingleTop = true
                                                    restoreState = false
                                                }
                                            } else if (currentRoute != tab.route) {
                                                navController.navigate(tab.route) {
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        trailing = {},
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text("会话", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (sessions.isEmpty()) {
                                        item {
                                            Text(
                                                "暂无会话（在「服务器」点一条即可创建）",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    } else {
                                        items(sessions.size) { idx ->
                                            val s = sessions[idx]
                                            val selected = (activeId == s.sessionId) && (currentRoute?.startsWith(BottomTab.Sessions.route) == true)
                                            val title = TerminalSessionPool.getDisplayTitle(s)
                                            SidebarRow(
                                                title = title,
                                                selected = selected,
                                                onClick = {
                                                    TerminalSessionPool.setActive(s.sessionId)
                                                    navController.navigate("${BottomTab.Sessions.route}?sid=${s.sessionId}") { launchSingleTop = true }
                                                },
                                                trailing = {
                                                    SmallIconButton(onClick = {
                                                        renameSid = s.sessionId
                                                        renameText = title
                                                    }) {
                                                        Icon(imageVector = Icons.Outlined.Edit, contentDescription = "rename")
                                                    }
                                                    SmallIconButton(onClick = { TerminalSessionPool.close(s.sessionId) }) {
                                                        Icon(imageVector = Icons.Outlined.Delete, contentDescription = "delete")
                                                    }
                                                    SmallIconButton(onClick = {
                                                        val newId = TerminalSessionPool.duplicateSession(s.sessionId)
                                                        if (newId != null) {
                                                            navController.navigate("${BottomTab.Sessions.route}?sid=$newId") { launchSingleTop = true }
                                                        }
                                                    }) {
                                                        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "copy")
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            if (renameSid != null) {
                                val sid = renameSid!!
                                AlertDialog(
                                    onDismissRequest = { renameSid = null },
                                    title = { Text("重命名会话") },
                                    text = {
                                        OutlinedTextField(
                                            value = renameText,
                                            onValueChange = { renameText = it },
                                            singleLine = true,
                                            label = { Text("名称") },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            TerminalSessionPool.renameSession(sid, renameText)
                                            renameSid = null
                                        }) { Text("确定") }
                                    },
                                    dismissButton = { TextButton(onClick = { renameSid = null }) { Text("取消") } },
                                )
                            }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = BottomTab.Servers.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                composable(BottomTab.Servers.route) {
                    HostsScreen(
                        contentPadding = contentPaddingForPane,
                        onAddHost = { navController.navigate("host_edit?hostId=-1") },
                        onEditHost = { id -> navController.navigate("host_edit?hostId=$id") },
                        onOpenSession = { hostId ->
                            val sid = TerminalSessionPool.openNewSession(hostId)
                            navController.navigate("${BottomTab.Sessions.route}?sid=$sid") { launchSingleTop = true }
                        },
                    )
                }
                composable(
                    route = "${BottomTab.Sessions.route}?sid={sid}",
                    arguments = listOf(navArgument("sid") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    val sid = entry.arguments?.getLong("sid") ?: -1L
                    SessionsScreen(contentPadding = contentPaddingForPane, initialSessionId = sid.takeIf { it > 0 })
                }
                composable(BottomTab.Overview.route) { OverviewScreen(contentPadding = contentPaddingForPane) }
                composable(BottomTab.Keys.route) { KeysScreen(contentPadding = contentPaddingForPane) }

            composable(
                route = "host_edit?hostId={hostId}",
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
            ) { entry ->
                val hostId = entry.arguments?.getLong("hostId") ?: -1L
                HostEditScreen(
                    contentPadding = contentPaddingForPane,
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
                    immersive = true,
                    showBack = true,
                )
            }
            }
            }
        }
    }
}

@Composable
private fun KeepAliveEmulatorViews(sessions: List<SessionState>) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    // 尽量不影响交互：透明、极小、不可聚焦。仅用于让库内部 KeyListener 不为 null。
    Column(modifier = Modifier.size(1.dp).alpha(0f)) {
        sessions.forEach { s ->
            val term = s.term ?: return@forEach
            AndroidView(
                factory = { ctx ->
                    SafeEmulatorView(ctx, term, dm).apply {
                        setUseCookedIME(false)
                        setTermType("xterm-256color")
                        isFocusable = false
                        isFocusableInTouchMode = false
                        onResume()
                    }
                },
                update = { v ->
                    if (v.getTermSession() !== term) v.attachSession(term)
                    v.onResume()
                },
                modifier = Modifier.size(1.dp),
            )
        }
    }
}

@Composable
private fun SidebarRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End, // 右对齐
    ) {
        // 只让“文字区域”可点击，避免整行 clickable 吞掉右侧按钮点击
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(end = 6.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = trailing)
    }
}

@Composable
private fun SmallIconButton(
    onClick: () -> Unit,
    size: Dp = 28.dp,
    iconSize: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

