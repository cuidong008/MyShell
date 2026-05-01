package com.dxkj.myshell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.dxkj.myshell.ui.components.ScreenTitleRow
import com.dxkj.myshell.ui.theme.Dimens

@Composable
fun HelpScreen(contentPadding: PaddingValues) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.ScreenPaddingH)
                    .padding(bottom = 24.dp),
            ) {
                ScreenTitleRow(
                    title = "帮助",
                    modifier = Modifier.padding(top = 4.dp, bottom = Dimens.SpacingMd),
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Text(
                                "使用说明",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        HelpSubsection(
                            title = "后台保活与省电",
                            body = "SSH 会话和端口转发依赖应用在后台持续运行。请尽量避免整机「省电模式」「超级省电」等会批量结束后台进程的模式。\n" +
                                "在系统设置 → 电池（或应用信息 → 电池）中，将本应用设为「无限制」「不限制后台」或允许后台活动；各厂商路径不同，常见还有「自启动」「关联启动」「允许后台运行」等开关，请对本应用放行。\n" +
                                "连接 SSH 或存在端口转发时，通知栏会出现前台服务提示，有助于维持隧道；在仍需保持连接时，请勿从最近任务中强行划掉本应用。",
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )

                        HelpSubsection(
                            title = "端口转发：用浏览器访问服务器上的网页",
                            body = "在服务器上启动只监听本机的 Web 服务（例如监听 127.0.0.1:8080 的开发服务器）。\n" +
                                "打开「会话」页，选中已连接的终端，点工具栏上的「端口转发」图标。添加本地转发：远程地址填服务实际监听的地址与端口（多为 127.0.0.1 与对应端口）；本地端口可与远程相同或自定义。终端里若打印了 localhost:端口，也可使用界面上的扫描或自动同号转发能力。\n" +
                                "转发建立后，流量会从手机本机端口经 SSH 隧道转到服务器。在手机浏览器地址栏输入 http://127.0.0.1:加上「本地端口」（例如 http://127.0.0.1:8080），即可访问该服务。SSH 断开后转发会失效，需保持会话并注意上一节的保活设置。",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpSubsection(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp,
        )
    }
}
