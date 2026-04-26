package com.dxkj.myshell.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Window
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Servers : BottomTab("servers", "服务器", Icons.Outlined.Storage)
    data object Sessions : BottomTab("sessions", "会话", Icons.Outlined.Window)
    data object Overview : BottomTab("overview", "概览", Icons.Outlined.Dashboard)
    data object Keys : BottomTab("keys", "密钥", Icons.Outlined.Key)
}

