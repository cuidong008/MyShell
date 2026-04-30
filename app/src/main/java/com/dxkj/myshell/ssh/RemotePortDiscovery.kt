package com.dxkj.myshell.ssh

import java.util.regex.Pattern

/**
 * 参考 VS Code Remote：`output` 模式从终端流里猜端口；
 * Linux 上再用 `ss` 列出监听（接近 `process`/系统级发现）。
 */
object RemotePortDiscovery {

    /**
     * ss -Hltn：`-H` 只去表头，常见发行版仍带 Netid 列，如 `tcp   LISTEN 0 128 0.0.0.0:22 ...`。
     * 也兼容无 Netid 的旧版/`ss` 实现。
     */
    private val ssListenLine = Pattern.compile(
        """^(?:(?:tcp6?|sctp)\s+)?LISTEN\s+\d+\s+\d+\s+((?:\[[^\]]+\]|[^\s:]+)):(\d+)\s+""",
    )

    /** 常见开发服务在终端里打印的地址（与 VS Code 终端检测思路一致） */
    private val terminalPatterns = listOf(
        // http://localhost:3000  /  https://127.0.0.1:8443/
        Pattern.compile(
            """(?i)https?://(?:localhost|127\.0\.0\.1|\[::1\])(?::(\d{2,5}))\b""",
        ),
        // localhost:3000  或  127.0.0.1:8080
        Pattern.compile(
            """(?i)(?:^|[^\w.])(?:localhost|127\.0\.0\.1|\[::1\])(?::(\d{2,5}))\b""",
        ),
        // 0.0.0.0:8000  *:5173  [::]:3000
        Pattern.compile("""(?i)(?:0\.0\.0\.0|\*|::|\[::\])(?::(\d{2,5}))\b"""),
        // Local: http://localhost:3000
        Pattern.compile("""(?i)Local:\s*https?://[^\s]*?:(\d{2,5})\b"""),
        // Port 3000 is open / listening on port 8080
        Pattern.compile("""(?i)\bport\s+(\d{2,5})\s+(?:is\s+)?(?:open|in\s+use|listening)"""),
    )

    fun parseSsListenTcp(output: String): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        for (line in output.lineSequence()) {
            val m = ssListenLine.matcher(line)
            if (!m.find()) continue
            val addrRaw = m.group(1) ?: continue
            val port = m.group(2)?.toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            val addr = normalizeListenAddr(addrRaw)
            if (addr.isNotEmpty()) out.add(addr to port)
        }
        return out.toList()
    }

    /** netstat -lnt 常见列：tcp 0 0 127.0.0.1:631 0.0.0.0:* LISTEN */
    private val netstatListen = Pattern.compile(
        """^(?:tcp|tcp6)\s+\S+\s+\S+\s+(\S+):(\d+)\s+\S+\s+LISTEN\s*$""",
    )

    fun parseNetstatListenTcp(output: String): List<Pair<String, Int>> {
        val out = LinkedHashSet<Pair<String, Int>>()
        for (line in output.lineSequence()) {
            val t = line.trim()
            val m = netstatListen.matcher(t)
            if (!m.find()) continue
            val addrRaw = m.group(1) ?: continue
            val port = m.group(2)?.toIntOrNull() ?: continue
            if (port !in 1..65535) continue
            val addr = normalizeListenAddr(addrRaw)
            if (addr.isNotEmpty()) out.add(addr to port)
        }
        return out.toList()
    }

    /** 扫描结果自动同号转发时排除的系统常见端口（避免误转 ssh/dns 等） */
    fun allowAutoForwardFromRemoteScan(port: Int): Boolean {
        if (port !in 1..65535) return false
        return port !in setOf(22, 53, 111, 631, 25, 110, 143, 993, 995)
    }

    /**
     * 从终端输出片段提取「从 SSH 服务器上应连过去」的 host:port。
     * 通配监听（* / 0.0.0.0）按 127.0.0.1 尝试（多数本机 dev server 可用）。
     */
    fun extractFromTerminalChunk(chunk: String): List<Pair<String, Int>> {
        if (chunk.length < 4) return emptyList()
        val found = LinkedHashSet<Pair<String, Int>>()
        for (p in terminalPatterns) {
            val m = p.matcher(chunk)
            while (m.find()) {
                val g1 = m.group(1) ?: continue
                val port = g1.toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                found.add("127.0.0.1" to port)
            }
        }
        return found.toList()
    }

    /** ss 地址列（不含端口）：127.0.0.1、*、0.0.0.0、[::]、[::1]、内网 IPv4 等 */
    private fun normalizeListenAddr(addrPart: String): String {
        val s = addrPart.trim()
        if (s == "*" || s == "0.0.0.0" || s == "::" || s == "[::]" || s == "[::1]" || s == "::1") {
            return "127.0.0.1"
        }
        if (s.startsWith("[::ffff:") && s.contains("127.0.0.1")) return "127.0.0.1"
        if (s.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) return s
        if (s.startsWith("[")) return s
        return s
    }
}
