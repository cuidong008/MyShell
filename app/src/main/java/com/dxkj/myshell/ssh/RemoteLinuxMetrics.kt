package com.dxkj.myshell.ssh

import kotlin.math.roundToInt

/**
 * 通过 SSH 在远端执行轻量 shell 采集的一帧指标（对齐常见「SSH 监控」思路：依赖 /proc，主要为 Linux）。
 */
data class LinuxHostMetricsSnapshot(
    val load1: Float?,
    val load5: Float?,
    val load15: Float?,
    val memTotalKb: Long?,
    val memAvailKb: Long?,
    val memUsedPct: Float?,
    val swapTotalKb: Long?,
    val swapFreeKb: Long?,
    val swapUsedPct: Float?,
    val rootDiskUsePct: Float?,
    val processLines: List<String>,
)

object RemoteLinuxMetrics {
    /**
     * 整段交给 [SshSessionManager.execRemoteCapture]；用 ---SECTION--- 分段，便于解析。
     * 使用单引号包裹的 /bin/sh -c，避免 bash 路径差异。
     */
    val remoteCollectCommand: String =
        "/bin/sh -c 'export LANG=C LC_ALL=C; " +
            "echo ---LOAD---; cat /proc/loadavg 2>/dev/null || echo NA; " +
            "echo ---MEM---; " +
            "grep ^MemTotal: /proc/meminfo 2>/dev/null; " +
            "grep ^MemAvailable: /proc/meminfo 2>/dev/null; " +
            "grep ^MemFree: /proc/meminfo 2>/dev/null; " +
            "grep ^SwapTotal: /proc/meminfo 2>/dev/null; " +
            "grep ^SwapFree: /proc/meminfo 2>/dev/null; " +
            "echo ---DISK---; df -Pk / 2>/dev/null | tail -n +2 | head -n 1; " +
            "echo ---PS---; ps aux 2>/dev/null | head -n 7'"

    suspend fun collect(ssh: SshSessionManager): Result<LinuxHostMetricsSnapshot> =
        ssh.execRemoteCapture(remoteCollectCommand, timeoutSec = 18L).map { parseCollectorOutput(it) }

    private fun extractSection(text: String, start: String, end: String?): String {
        val i = text.indexOf(start)
        if (i < 0) return ""
        val from = i + start.length
        val j = if (end != null) text.indexOf(end, from) else -1
        return if (j >= 0) text.substring(from, j).trim() else text.substring(from).trim()
    }

    private fun parseKbFromMeminfoLine(line: String): Long? {
        val m = Regex("(\\d+)\\s*kB").find(line) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    fun parseCollectorOutput(output: String): LinuxHostMetricsSnapshot {
        val loadBlock = extractSection(output, "---LOAD---", "---MEM---").lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val (l1, l5, l15) = parseLoadavg(loadBlock)

        val memBlock = extractSection(output, "---MEM---", "---DISK---")
        var memTotal: Long? = null
        var memAvail: Long? = null
        var memFree: Long? = null
        var swapTotal: Long? = null
        var swapFree: Long? = null
        for (line in memBlock.lines()) {
            val t = line.trim()
            when {
                t.startsWith("MemTotal:") -> memTotal = parseKbFromMeminfoLine(t)
                t.startsWith("MemAvailable:") -> memAvail = parseKbFromMeminfoLine(t)
                t.startsWith("MemFree:") -> memFree = parseKbFromMeminfoLine(t)
                t.startsWith("SwapTotal:") -> swapTotal = parseKbFromMeminfoLine(t)
                t.startsWith("SwapFree:") -> swapFree = parseKbFromMeminfoLine(t)
            }
        }
        val avail = memAvail ?: memFree
        val memUsedPct = if (memTotal != null && memTotal > 0L && avail != null) {
            (((memTotal - avail).coerceAtLeast(0L)).toFloat() / memTotal.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            null
        }
        val swapUsedPct = if (swapTotal != null && swapTotal > 0L && swapFree != null) {
            (((swapTotal - swapFree).coerceAtLeast(0L)).toFloat() / swapTotal.toFloat() * 100f).coerceIn(0f, 100f)
        } else {
            null
        }

        val diskLine = extractSection(output, "---DISK---", "---PS---").lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val diskPct = parseDfCapacityPct(diskLine)

        val psBlock = extractSection(output, "---PS---", null)
        val procLines = psBlock.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("USER ") || it.startsWith("PID ") }

        return LinuxHostMetricsSnapshot(
            load1 = l1,
            load5 = l5,
            load15 = l15,
            memTotalKb = memTotal,
            memAvailKb = avail,
            memUsedPct = memUsedPct,
            swapTotalKb = swapTotal,
            swapFreeKb = swapFree,
            swapUsedPct = swapUsedPct,
            rootDiskUsePct = diskPct,
            processLines = procLines.take(6),
        )
    }

    private fun parseLoadavg(line: String): Triple<Float?, Float?, Float?> {
        if (line.isBlank() || line == "NA") return Triple(null, null, null)
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 3) return Triple(null, null, null)
        val a = parts[0].toFloatOrNull()
        val b = parts[1].toFloatOrNull()
        val c = parts[2].toFloatOrNull()
        return Triple(a, b, c)
    }

    private fun parseDfCapacityPct(line: String): Float? {
        if (line.isBlank() || line == "NODISK") return null
        val tok = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        val pctTok = tok.firstOrNull { it.endsWith("%") } ?: return null
        return pctTok.trimEnd('%').toFloatOrNull()
    }

    fun formatPct(p: Float?): String =
        if (p == null) "—" else "${p.roundToInt()}%"

    fun formatLoad(a: Float?, b: Float?, c: Float?): String =
        if (a == null && b == null && c == null) "—" else listOfNotNull(a, b, c).joinToString(" / ") { String.format("%.2f", it) }
}
