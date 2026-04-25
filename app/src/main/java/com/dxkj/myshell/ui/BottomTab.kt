package com.dxkj.myshell.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

sealed class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Hosts : BottomTab("hosts", "主机", Icons.Outlined.Storage)
    data object Terminal : BottomTab("terminal", "终端", Icons.Outlined.Terminal)
    data object Files : BottomTab("files", "文件", Icons.Outlined.Folder)
    data object Keys : BottomTab("keys", "密钥", Icons.Outlined.Key)
}

