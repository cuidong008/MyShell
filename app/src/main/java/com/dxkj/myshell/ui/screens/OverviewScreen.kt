package com.dxkj.myshell.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dxkj.myshell.ssh.LinuxHostMetricsSnapshot
import com.dxkj.myshell.ssh.RemoteLinuxMetrics
import com.dxkj.myshell.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OverviewScreen(contentPadding: PaddingValues) {
    val cfg = LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp
    val vm: OverviewViewModel = viewModel()
    val hosts by vm.hosts.collectAsState()
    val anyRefreshing = hosts.any { it.refreshing }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = Dimens.ScreenPaddingH),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else Dimens.SpacingMd),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("概览", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { vm.refreshNow() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "立即刷新")
                }
            }
        }
        if (anyRefreshing && hosts.isNotEmpty()) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (hosts.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                    Column(Modifier.fillMaxWidth().padding(Dimens.CardPadding)) {
                        Text("暂无已连接主机", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Dimens.SpacingXs))
                        Text(
                            "请先在「服务器」打开会话并等待连接成功；概览会每 5 秒自动拉取一次负载、内存、根分区与进程快照。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(hosts, key = { it.hostId }) { card ->
                HostMonitorCard(card)
            }
        }
        item { Spacer(Modifier.height(Dimens.SpacingXl)) }
    }
}

@Composable
private fun HostMonitorCard(card: OverviewHostMonitorCardUi) {
    val timeStr = rememberTime(card.lastUpdatedEpochMs)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSm),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (card.lastUpdatedEpochMs > 0L) timeStr else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.error != null) {
                Text(card.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val m = card.metrics
            if (m != null && card.error == null) {
                MetricBlock(m)
            } else if (card.error == null && card.refreshing) {
                Text("正在采集…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun rememberTime(epoch: Long): String {
    val fmt = rememberFmt()
    return if (epoch <= 0L) "—" else fmt.format(Date(epoch))
}

@Composable
private fun rememberFmt(): SimpleDateFormat {
    return remember {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}

@Composable
private fun MetricBlock(m: LinuxHostMetricsSnapshot) {
    Text(
        "负载（1 / 5 / 15 分）: ${RemoteLinuxMetrics.formatLoad(m.load1, m.load5, m.load15)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    val memLine = when {
        m.memTotalKb != null && m.memTotalKb > 0L && m.memAvailKb != null -> {
            val usedPct = RemoteLinuxMetrics.formatPct(m.memUsedPct)
            val totalG = m.memTotalKb / 1024f / 1024f
            val availG = m.memAvailKb!! / 1024f / 1024f
            "内存: 已用约 $usedPct（可用 ${"%.1f".format(availG)} GiB / 共 ${"%.1f".format(totalG)} GiB）"
        }
        else -> "内存: 未解析（可能非 Linux 或缺少 MemAvailable）"
    }
    Text(memLine, style = MaterialTheme.typography.bodyMedium)
    val swapLine = when {
        m.swapTotalKb != null && m.swapTotalKb > 0L -> {
            val freeMi = (m.swapFreeKb ?: 0L) / 1024L
            val totMi = m.swapTotalKb / 1024L
            "交换: ${RemoteLinuxMetrics.formatPct(m.swapUsedPct)}（$freeMi / $totMi MiB 空闲/总量）"
        }
        m.swapTotalKb == 0L -> "交换: 无"
        else -> "交换: —"
    }
    Text(swapLine, style = MaterialTheme.typography.bodyMedium)
    Text(
        "根分区 / 使用率: ${RemoteLinuxMetrics.formatPct(m.rootDiskUsePct)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (m.processLines.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = Dimens.SpacingXs))
        Text("CPU 占用偏高进程（快照）", style = MaterialTheme.typography.labelLarge)
        m.processLines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
