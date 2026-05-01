package com.dxkj.myshell.terminal

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.PortForwardHoldService
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.ssh.DiscoveredListen
import com.dxkj.myshell.ssh.PortForwardItem
import com.dxkj.myshell.ssh.SshSessionManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.dxkj.myshell.data.prefs.AppPreferences
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局多会话池：让多个 SSH 会话在 UI 切换时不丢失。
 *
 * 说明：
 * - 会话的网络连接与读写由 sshj + SshSessionManager 负责
 * - 终端渲染与 ANSI/VT 解析由 Haven 同款 termlib (TerminalEmulator) 负责
 */
object TerminalSessionPool {
    private const val TAG = "TerminalSessionPool"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimes = ConcurrentHashMap<Long, TerminalRuntime>()

    private data class TerminalRuntime(
        val emulator: TerminalEmulator,
        val readerJob: Job,
        val shell: JschShellSession,
    )

    private lateinit var app: Application
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var hostRepo: HostRepository
    private lateinit var keyRepo: KeyRepository

    /** 终端自动同号转发：按「会话|host:port」节流，避免日志刷屏触发风暴 */
    private val terminalAutoThrottle = ConcurrentHashMap<String, Long>()

    private val _sessions = MutableStateFlow<List<SessionState>>(emptyList())
    val sessions: StateFlow<List<SessionState>> = _sessions

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId

    private val _hostPortForwardUi = MutableStateFlow<Map<Long, HostPortForwardUi>>(emptyMap())
    /** 同一 [SessionState.hostId] 下所有已连接会话的转发 / 已发现 / 忽略地址的合并视图（多标签一致）。 */
    val hostPortForwardUi: StateFlow<Map<Long, HostPortForwardUi>> = _hostPortForwardUi.asStateFlow()

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        prefs = application.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val db = DbProvider.get(application)
        hostRepo = HostRepository(db.hostDao())
        keyRepo = KeyRepository(db.keyDao())
    }

    /** 从后台回到前台时尝试恢复已断的本地监听，并刷新前台保活通知。 */
    fun onApplicationResume() {
        if (!::app.isInitialized) return
        syncPortForwardHoldService()
        scope.launch(Dispatchers.IO) {
            for (s in _sessions.value) {
                if (!s.connected) continue
                try {
                    s.ssh.restorePortForwardsAfterReconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun syncPortForwardHoldService() {
        if (!::app.isInitialized) return
        scope.launch {
            val forwardCount = _sessions.value.sumOf { it.ssh.activeLocalPortForwardCount() }
            val connectedSsh = _sessions.value.count { it.connected }
            PortForwardHoldService.update(app, forwardCount, connectedSsh)
        }
    }

    private fun refreshHostPortForwardUiForSession(sessionId: Long) {
        val hid = _sessions.value.firstOrNull { it.sessionId == sessionId }?.hostId ?: return
        rebuildHostPortForwardUi(hid)
    }

    /** 进入端口页或切换主机时主动拉一次合并结果（避免尚未收到子会话回调时列表为空）。 */
    fun refreshHostPortForwardUi(hostId: Long) {
        if (hostId <= 0L) return
        rebuildHostPortForwardUi(hostId)
    }

    private fun rebuildHostPortForwardUi(hostId: Long) {
        if (!::app.isInitialized) return
        val siblings = _sessions.value
            .filter { it.hostId == hostId && it.connected }
            .sortedBy { it.sessionId }
        val forwards = siblings.flatMap { s ->
            s.ssh.portForwards.value.map { MergedForwardUi(s.sessionId, it) }
        }.sortedWith(compareBy({ it.item.remotePort }, { it.item.localPort }, { it.owningSessionId }))
        val discoveredMerged = linkedMapOf<String, MergedDiscoveredUi>()
        for (s in siblings) {
            for (d in s.ssh.discoveredList.value) {
                val key = "${d.remoteHost.trim().lowercase()}:${d.remotePort}"
                if (!discoveredMerged.containsKey(key)) {
                    discoveredMerged[key] = MergedDiscoveredUi(s.sessionId, d)
                }
            }
        }
        val discovered = discoveredMerged.values.sortedWith(
            compareBy({ it.listen.remotePort }, { it.listen.remoteHost.lowercase() }),
        )
        val mergedIgnored = siblings.flatMap { it.ssh.ignoredRemotePorts.value }.toSet()
        val anyForHost = _sessions.value.any { it.hostId == hostId }
        _hostPortForwardUi.update { m ->
            val next = m.toMutableMap()
            if (!anyForHost) {
                next.remove(hostId)
            } else {
                next[hostId] = HostPortForwardUi(forwards, discovered, mergedIgnored)
            }
            next
        }
    }

    /** 同主机任一会话是否已对远端建立本地转发。 */
    fun isRemotePortForwardedOnHost(hostId: Long, remoteHost: String, remotePort: Int): Boolean =
        hostId > 0L && _sessions.value.any {
            it.hostId == hostId && it.connected && it.ssh.isForwardingRemote(remoteHost, remotePort)
        }

    fun unignoreRemotePortOnAllSameHostSessions(hostId: Long, remoteHost: String, remotePort: Int) {
        for (s in _sessions.value.filter { it.hostId == hostId }) {
            s.ssh.clearIgnoredRemote(remoteHost, remotePort)
        }
    }

    fun removeDiscoveredFromAllSameHostSessions(hostId: Long, remoteHost: String, remotePort: Int) {
        for (s in _sessions.value.filter { it.hostId == hostId }) {
            s.ssh.removeDiscoveredFromListOnly(remoteHost, remotePort)
        }
        rebuildHostPortForwardUi(hostId)
    }

    fun clearAllDismissedRemotePortsForHost(hostId: Long) {
        for (s in _sessions.value.filter { it.hostId == hostId }) {
            s.ssh.clearAllDismissedRemotePorts()
        }
    }

    fun setActive(sessionId: Long) {
        _activeSessionId.value = sessionId
    }

    fun portAutoFromTerminal(): Boolean = if (::prefs.isInitialized) prefs.getBoolean("port_auto_terminal", true) else true

    fun portAutoFromScan(): Boolean = if (::prefs.isInitialized) prefs.getBoolean("port_auto_scan", true) else true

    fun setPortAutoFromTerminal(v: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean("port_auto_terminal", v).apply()
    }

    fun setPortAutoFromScan(v: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean("port_auto_scan", v).apply()
    }

    private fun terminalSniffHandler(sessionId: Long, bytes: ByteArray, off: Int, len: Int) {
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        if (!s.connected) return
        val newPorts = try {
            s.ssh.feedTerminalSniffForPorts(bytes, off, len)
        } catch (_: Throwable) {
            return
        }
        if (!portAutoFromTerminal()) return
        for ((h, p) in newPorts) {
            if (p == 22) continue
            val key = "$sessionId|${h.lowercase()}:$p"
            val now = SystemClock.elapsedRealtime()
            if (now - terminalAutoThrottle.getOrDefault(key, 0L) < 2500L) continue
            terminalAutoThrottle[key] = now
            scope.launch(Dispatchers.IO) {
                val cur = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return@launch
                if (!cur.connected) return@launch
                try {
                    cur.ssh.startSamePortForwardIfAbsent(h, p, explicitUserAction = false)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        val t = newTitle.trim()
        if (t.isEmpty()) return
        _sessions.update { list ->
            list.map {
                if (it.sessionId == sessionId) it.copy(customTitle = t)
                else it
            }
        }
    }

    fun getDisplayTitle(s: SessionState): String = s.customTitle ?: s.title

    /**
     * 复制一个会话（同一 host 再开一个连接），并按 Shell 工具常见规则自动命名：
     * - aix -> aix(1) -> aix(2) ...
     * - aix(1) -> aix(2) ...
     */
    fun duplicateSession(fromSessionId: Long): Long? {
        val from = _sessions.value.firstOrNull { it.sessionId == fromSessionId } ?: return null
        val existingNames = _sessions.value
            .filter { it.hostId == from.hostId }
            .map { getDisplayTitle(it) }
            .toSet()

        val fromName = getDisplayTitle(from)
        val (base, fromN) = parseBaseAndSuffix(fromName)
        val start = if (fromN != null) (fromN + 1) else 1
        val newName = nextAvailableName(base, existingNames, start)

        val newId = openNewSession(from.hostId)
        renameSession(newId, newName) // 使用 customTitle 覆盖显示名
        setActive(newId)
        return newId
    }

    private fun parseBaseAndSuffix(name: String): Pair<String, Int?> {
        val m = Regex("""^(.*)\((\d+)\)$""").matchEntire(name.trim())
        return if (m != null) {
            val base = m.groupValues[1].trim()
            val n = m.groupValues[2].toIntOrNull()
            base to n
        } else {
            name.trim() to null
        }
    }

    private fun nextAvailableName(base: String, used: Set<String>, start: Int): String {
        var n = start.coerceAtLeast(1)
        while (true) {
            val candidate = "$base($n)"
            if (!used.contains(candidate)) return candidate
            n += 1
        }
    }

    fun ensureSession(hostId: Long) {
        // 已有会话则切换到它；否则新建并连接
        val existing = _sessions.value.firstOrNull { it.hostId == hostId }
        if (existing != null) {
            _activeSessionId.value = existing.sessionId
            return
        }
        open(hostId)
    }

    fun openNewSession(hostId: Long): Long = open(hostId)

    fun open(hostId: Long): Long {
        val sessionId = nextId.getAndIncrement()
        val autoReconnect = prefs.getBoolean("autoReconnect_$hostId", true)

        val ssh = SshSessionManager(
            keyRepo = keyRepo,
            onPortForwardUiChanged = {
                syncPortForwardHoldService()
                refreshHostPortForwardUiForSession(sessionId)
            },
        )
        val state = SessionState(
            sessionId = sessionId,
            hostId = hostId,
            title = "主机 $hostId",
            connecting = true,
            connected = false,
            status = "连接中…",
            autoReconnect = autoReconnect,
            ssh = ssh,
            emulator = null,
        )
        _sessions.update { it + state }
        _activeSessionId.value = sessionId
        persistOpenHosts()
        rebuildHostPortForwardUi(hostId)

        scope.launch {
            connectInternal(sessionId)
        }
        return sessionId
    }

    fun close(sessionId: Long) {
        terminalAutoThrottle.keys.filter { it.startsWith("$sessionId|") }.forEach { terminalAutoThrottle.remove(it) }
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val hid = s.hostId
        scope.launch {
            try {
                s.ssh.disconnect()
            } catch (_: Throwable) {
            }
        }
        _sessions.update { list -> list.filterNot { it.sessionId == sessionId } }
        if (_activeSessionId.value == sessionId) {
            _activeSessionId.value = _sessions.value.lastOrNull()?.sessionId
        }
        persistOpenHosts()
        rebuildHostPortForwardUi(hid)
        syncPortForwardHoldService()
    }

    fun closeAll() {
        val ids = _sessions.value.map { it.sessionId }
        ids.forEach { close(it) }
    }

    fun closeOthers(keepSessionId: Long) {
        val ids = _sessions.value.map { it.sessionId }.filterNot { it == keepSessionId }
        ids.forEach { close(it) }
    }

    fun disconnect(sessionId: Long) {
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val hid = s.hostId
        scope.launch {
            try {
                s.ssh.disconnect()
            } catch (_: Throwable) {
            }
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(connected = false, connecting = false, status = "已断开", emulator = null)
                    else it
                }
            }
            rebuildHostPortForwardUi(hid)
            syncPortForwardHoldService()
        }
    }

    fun reconnect(sessionId: Long) {
        scope.launch {
            disconnect(sessionId)
            connectInternal(sessionId)
        }
    }

    fun toggleAutoReconnect(sessionId: Long) {
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val next = !s.autoReconnect
        prefs.edit().putBoolean("autoReconnect_${s.hostId}", next).apply()
        _sessions.update { list -> list.map { if (it.sessionId == sessionId) it.copy(autoReconnect = next) else it } }
    }

    fun broadcastWrite(text: String) {
        val list = _sessions.value
        list.forEach { s ->
            try {
                sendInput(s.sessionId, text)
            } catch (_: Throwable) {
            }
        }
    }

    fun sendInput(sessionId: Long, text: String) {
        val rt = runtimes[sessionId]
        if (rt == null) {
            val s = _sessions.value.firstOrNull { it.sessionId == sessionId }
            Log.w(
                TAG,
                "sendInput ignored: runtime missing sessionId=$sessionId connected=${s?.connected} connecting=${s?.connecting} hasEmulator=${s?.emulator != null}",
            )
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendBytes(sessionId, bytes)
    }

    /** 直接写入原始字节（用于 Tab/方向键/粘贴等控制序列，避免 String 往返转换带来的问题）。 */
    fun sendBytes(sessionId: Long, bytes: ByteArray) {
        val rt = runtimes[sessionId]
        if (rt == null) {
            val s = _sessions.value.firstOrNull { it.sessionId == sessionId }
            Log.w(
                TAG,
                "sendBytes ignored: runtime missing sessionId=$sessionId connected=${s?.connected} connecting=${s?.connecting} hasEmulator=${s?.emulator != null} bytes=${bytes.size}",
            )
            return
        }
        val first = bytes.firstOrNull()?.toInt()?.and(0xFF)
        Log.d(TAG, "sendBytes: sessionId=$sessionId bytes=${bytes.size} first=${first?.toString(16)} shell=${rt.shell.debugState()}")
        rt.shell.sendToSsh(bytes)
    }

    fun exportLog(sessionId: Long): String? {
        // termlib 暂未在本项目实现 transcript 导出（Haven 用的是 Recorder）。
        // 先保留按钮入口不崩溃，后续如需可按 Haven 的 TerminalRecorder 接入。
        return null
    }

    fun loadOpenHostIds(): List<Long> {
        return (prefs.getString("openHostIds", "") ?: "")
            .split(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
            .filter { it > 0 }
    }

    private fun persistOpenHosts() {
        val hostIds = _sessions.value.map { it.hostId }.distinct()
        prefs.edit().putString("openHostIds", hostIds.joinToString(",")).apply()
    }

    private suspend fun connectInternal(sessionId: Long) {
        Log.d(TAG, "connectInternal(sessionId=$sessionId) begin")
        val s0 = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        terminalAutoThrottle.keys.filter { it.startsWith("$sessionId|") }.forEach { terminalAutoThrottle.remove(it) }
        _sessions.update { list ->
            list.map {
                // 不要在连接流程中把 emulator 置空：会导致 UI dispose Terminal，
                // termlib 的异步 focus/IME 流程随后触发 requestFocus 时会崩溃。
                if (it.sessionId == sessionId) it.copy(connecting = true, status = "连接中…")
                else it
            }
        }

        val host = withContext(Dispatchers.IO) { hostRepo.getById(s0.hostId) }
        if (host == null) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(connecting = false, connected = false, status = "主机不存在")
                    else it
                }
            }
            rebuildHostPortForwardUi(s0.hostId)
            syncPortForwardHoldService()
            return
        }

        // ShellBean 风格：会话名称基于“服务器配置名”，同一服务器多会话自动追加 (1)(2)…
        _sessions.update { list ->
            val sameHost = list.filter { it.hostId == host.id }.sortedBy { it.sessionId }
            val idx = sameHost.indexOfFirst { it.sessionId == sessionId }
            val suffix = if (idx <= 0) "" else "(${idx})"
            val title = host.name + suffix
            list.map {
                if (it.sessionId == sessionId) it.copy(title = title)
                else it
            }
        }

        // title already set above

        // Terminal path: match Haven — use JSch ChannelShell + PTY (not sshj).
        val shell = JschShellSession(keyRepo)
        try {
            withContext(Dispatchers.IO) {
                shell.connectAndOpenShell(host, term = "xterm-256color", cols = 80, rows = 24)
            }
        } catch (t: Throwable) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(
                        connecting = false,
                        connected = false,
                        status = "连接失败：${t::class.java.simpleName}: ${t.message}",
                    ) else it
                }
            }
            rebuildHostPortForwardUi(host.id)
            syncPortForwardHoldService()
            return
        }

        // Port scan / port-forward path: SSHJ (SshSessionManager) is used there.
        // Terminal connection can succeed while SSHJ is not connected; ensure SSHJ is ready for "扫描远端监听/端口转发".
        try {
            val cr = withContext(Dispatchers.IO) { s0.ssh.connect(host) }
            if (!cr.ok) {
                _sessions.update { list ->
                    list.map {
                        if (it.sessionId == sessionId) it.copy(status = "已连接（端口功能不可用：${cr.message}）")
                        else it
                    }
                }
            }
        } catch (t: Throwable) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(status = "已连接（端口功能不可用：${t::class.java.simpleName}: ${t.message}）")
                    else it
                }
            }
        }

        val rawIn = shell.input
        val sniffingInput = SniffingInputStream(rawIn) { b, o, l ->
            terminalSniffHandler(sessionId, b, o, l)
        }

        // Create Haven termlib emulator (same rendering/input stack as Haven)
        val (defaultFg, defaultBg) = AppPreferences.defaultTerminalColors()
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = defaultFg,
            defaultBackground = defaultBg,
            enableAltScreen = true,
            onKeyboardInput = { data ->
                // 对齐 Haven：所有输入（软键盘/硬件键盘/工具条）走同一个 sendToSsh，
                // 避免多写入线程导致乱序（例如 “ls” 还在排队时 Tab 先到远端）。
                if (data.isNotEmpty()) {
                    Log.d(TAG, "onKeyboardInput: ${data.size} bytes, first=${data[0].toInt() and 0xFF}")
                } else {
                    Log.d(TAG, "onKeyboardInput: 0 bytes")
                }
                shell.sendToSsh(data)
            },
            onResize = { dims ->
                if (dims.columns > 0 && dims.rows > 0) {
                    Log.d(TAG, "onResize: ${dims.columns}x${dims.rows} sessionId=$sessionId")
                    shell.resize(dims.columns, dims.rows)
                }
            },
        )
        // 工厂虽然传入 defaultForeground/Background，但 libvterm 原生实例不会在 lazy init 时自动同步；
        // 若不调用 setDefaultColors，快照里单元格背景仍是黑色，Compose 侧 drawLine 会为每个格子画黑底，只剩外层空隙能看到配色。
        emulator.setDefaultColors(defaultFg.toArgb(), defaultBg.toArgb())
        Log.d(TAG, "created emulator=${System.identityHashCode(emulator)} for sessionId=$sessionId")

        // Start reader: SSH -> emulator.writeInput (must run on main thread)
        val readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            var totalBytes = 0L
            try {
                while (true) {
                    val n = sniffingInput.read(buf)
                    // IMPORTANT: treat 0 as "no data yet", not EOF.
                    // Some streams may transiently return 0; only -1 means EOF.
                    if (n < 0) break
                    if (n == 0) continue
                    totalBytes += n.toLong()
                    val copy = buf.copyOfRange(0, n)
                    mainHandler.post {
                        try {
                            emulator.writeInput(copy, 0, copy.size)
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            Log.w(
                TAG,
                "reader ended for sessionId=$sessionId emulator=${System.identityHashCode(emulator)} totalBytes=$totalBytes shell=${shell.debugState()}",
            )
            scope.launch {
                val hidFinish = _sessions.value.firstOrNull { it.sessionId == sessionId }?.hostId
                _sessions.update { list ->
                    list.map {
                        if (it.sessionId == sessionId) it.copy(connected = false, connecting = false, status = "连接已断开", emulator = null)
                        else it
                    }
                }
                runtimes.remove(sessionId)
                try { shell.close() } catch (_: Throwable) {}
                if (hidFinish != null) rebuildHostPortForwardUi(hidFinish)
                syncPortForwardHoldService()
                val cur = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return@launch
                if (cur.autoReconnect) scheduleReconnect(sessionId)
            }
        }

        runtimes[sessionId] = TerminalRuntime(emulator = emulator, readerJob = readerJob, shell = shell)

        updateRecentHosts(prefs, host.id)
        prefs.edit().putLong("lastHostId", host.id).apply()

        _sessions.update { list ->
            list.map {
                if (it.sessionId == sessionId) it.copy(connecting = false, connected = true, status = "已连接", emulator = emulator)
                else it
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                s0.ssh.restorePortForwardsAfterReconnect()
            } catch (_: Throwable) {
            }
        }
        rebuildHostPortForwardUi(host.id)
        syncPortForwardHoldService()
    }

    private suspend fun scheduleReconnect(sessionId: Long) {
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        val attempt = (s.reconnectAttempt + 1).coerceAtMost(8)
        val backoffMs = (1_000L * attempt).coerceAtMost(8_000L)
        _sessions.update { list ->
            list.map { if (it.sessionId == sessionId) it.copy(reconnectAttempt = attempt, status = "断线，${backoffMs / 1000}s 后重连…") else it }
        }
        delay(backoffMs)
        val s2 = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        if (s2.autoReconnect && !s2.connected && !s2.connecting) {
            connectInternal(sessionId)
        }
    }

    private fun updateRecentHosts(prefs: android.content.SharedPreferences, hostId: Long) {
        val key = "recentHostIds"
        val max = 8
        val current = (prefs.getString(key, "") ?: "")
            .split(',')
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() }?.toLongOrNull() }
            .filter { it > 0 }
            .toMutableList()
        current.remove(hostId)
        current.add(0, hostId)
        val next = current.take(max).joinToString(",")
        prefs.edit().putString(key, next).apply()
    }
}

/** 合并列表里一条转发所属的标签会话（停止/改端口须调该会话的 [SshSessionManager]）。 */
data class MergedForwardUi(val owningSessionId: Long, val item: PortForwardItem)

data class MergedDiscoveredUi(val owningSessionId: Long, val listen: DiscoveredListen)

/** 同一主机配置下多 SSH 会话的端口页合并数据。 */
data class HostPortForwardUi(
    val forwards: List<MergedForwardUi>,
    val discovered: List<MergedDiscoveredUi>,
    val mergedIgnoredKeys: Set<String>,
) {
    companion object {
        val Empty = HostPortForwardUi(emptyList(), emptyList(), emptySet())
    }
}

data class SessionState(
    val sessionId: Long,
    val hostId: Long,
    val title: String,
    val customTitle: String? = null,
    val connecting: Boolean,
    val connected: Boolean,
    val status: String?,
    val autoReconnect: Boolean,
    val reconnectAttempt: Int = 0,
    val ssh: SshSessionManager,
    val emulator: TerminalEmulator?,
)

