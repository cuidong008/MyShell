package com.dxkj.myshell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color

private enum class SessionPane { Terminal, Files }

@Composable
fun SessionsScreen(
    contentPadding: PaddingValues,
    initialSessionId: Long? = null,
) {
    var pane by remember { mutableStateOf(SessionPane.Terminal) }
    var terminalToolbarVisible by remember { mutableStateOf(true) }

    if (initialSessionId != null) {
        LaunchedEffect(initialSessionId) {
            com.dxkj.myshell.terminal.TerminalSessionPool.setActive(initialSessionId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 内容区：从顶部开始显示
            Box(modifier = Modifier.fillMaxSize()) {
                when (pane) {
                    SessionPane.Terminal -> TerminalHubScreen(
                        initialHostId = null,
                        onExit = {},
                        immersive = false,
                        showBack = false,
                        showTopOverlay = terminalToolbarVisible,
                        compactTopOverlay = true,
                        onToggleTopOverlay = { terminalToolbarVisible = !terminalToolbarVisible },
                    )

                    SessionPane.Files -> FilesScreen(contentPadding = PaddingValues(0.dp))
                }
            }

            // 底部：两个紧凑图标按钮（比 TabRow 更窄更美观）
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp)
                    .padding(horizontal = 8.dp)
                    .then(
                        Modifier
                            .padding(0.dp),
                    )
                    .background(Color(0xCC111111), RoundedCornerShape(16.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { pane = SessionPane.Terminal },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = "终端",
                        tint = if (pane == SessionPane.Terminal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = { pane = SessionPane.Files },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = "文件",
                        tint = if (pane == SessionPane.Files) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

