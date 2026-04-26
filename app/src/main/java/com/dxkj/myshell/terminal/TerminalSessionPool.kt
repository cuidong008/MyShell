package com.dxkj.myshell.terminal

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.PortForwardHoldService
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.ssh.SshSessionManager
import jackpal.androidterm.emulatorview.TermSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局多会话池：让多个 SSH 会话在 UI 切换时不丢失。
 *
 * 说明：
 * - 会话的网络连接与读写由 sshj + SshSessionManager 负责
 * - 终端渲染与 ANSI/VT 解析由 TermSession + EmulatorView 负责
 */
object TerminalSessionPool {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nextId = AtomicLong(1)

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
            val n = _sessions.value.sumOf { it.ssh.activeLocalPortForwardCount() }
            PortForwardHoldService.update(app, n)
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
            onActiveForwardsChanged = { syncPortForwardHoldService() },
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
            term = null,
        )
        _sessions.update { it + state }
        _activeSessionId.value = sessionId
        persistOpenHosts()

        scope.launch {
            connectInternal(sessionId)
        }
        return sessionId
    }

    fun close(sessionId: Long) {
        terminalAutoThrottle.keys.filter { it.startsWith("$sessionId|") }.forEach { terminalAutoThrottle.remove(it) }
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
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
        scope.launch {
            try {
                s.ssh.disconnect()
            } catch (_: Throwable) {
            }
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(connected = false, connecting = false, status = "已断开", term = null)
                    else it
                }
            }
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
                s.term?.write(text)
            } catch (_: Throwable) {
            }
        }
    }

    fun exportLog(sessionId: Long): String? {
        val s = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return null
        val term = s.term ?: return null
        return try {
            val dir = java.io.File(app.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val file = java.io.File(dir, "ssh-${s.hostId}-${System.currentTimeMillis()}.txt")
            file.writeText(term.transcriptText)
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(status = "日志已保存：${file.name}")
                    else it
                }
            }
            file.absolutePath
        } catch (t: Throwable) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(status = "保存日志失败：${t.message}")
                    else it
                }
            }
            null
        }
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
        val s0 = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
        terminalAutoThrottle.keys.filter { it.startsWith("$sessionId|") }.forEach { terminalAutoThrottle.remove(it) }
        _sessions.update { list ->
            list.map {
                if (it.sessionId == sessionId) it.copy(connecting = true, status = "连接中…", term = null)
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

        val result = withContext(Dispatchers.IO) { s0.ssh.connect(host) }
        if (!result.ok) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(connecting = false, connected = false, status = result.message, term = null)
                    else it
                }
            }
            return
        }

        val streams = withContext(Dispatchers.IO) { s0.ssh.openShellStreams() }
        if (streams.isFailure) {
            _sessions.update { list ->
                list.map {
                    if (it.sessionId == sessionId) it.copy(
                        connecting = false,
                        connected = false,
                        status = "启动终端失败：${streams.exceptionOrNull()?.message}",
                        term = null,
                    ) else it
                }
            }
            return
        }

        val (out, rawIn) = streams.getOrThrow()
        val input = SniffingInputStream(rawIn) { b, o, l ->
            terminalSniffHandler(sessionId, b, o, l)
        }
        val term = TermSession()
        term.setTitle("SSH")
        term.setTermIn(input)
        term.setTermOut(out)
        // 关键：不要在没有 TermKeyListener 的情况下提前 initializeEmulator。
        // 否则库内部会创建 TerminalEmulator(keyListener=null)，后续即使 attach 到 EmulatorView 也可能不会补齐，从而在解析转义序列时 NPE。
        // 避免首次连接时协商序列（如 `1;2c`）残留在屏幕上
        try {
            term.setDefaultUTF8Mode(true)
            term.reset()
        } catch (_: Throwable) {
        }
        // 某些服务端会在登录后立刻发起设备属性(DA)握手；若远端处于 ECHO 开启状态，可能把控制序列回应回显成 "1;2c"。
        // 这里在会话建立后主动关闭远端回显，减少“控制序列当作普通字符显示”的概率。
        // （不影响 vim/top 等全屏程序，它们本来就会切换 raw/noecho。）
        term.write("stty -echo\n")
        term.setFinishCallback(object : TermSession.FinishCallback {
            override fun onSessionFinish(s: TermSession) {
                scope.launch {
                    _sessions.update { list ->
                        list.map {
                            if (it.sessionId == sessionId) it.copy(connected = false, connecting = false, status = "连接已断开", term = null)
                            else it
                        }
                    }
                    val cur = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return@launch
                    if (cur.autoReconnect) scheduleReconnect(sessionId)
                }
            }
        })

        updateRecentHosts(prefs, host.id)
        prefs.edit().putLong("lastHostId", host.id).apply()

        _sessions.update { list ->
            list.map {
                if (it.sessionId == sessionId) it.copy(connecting = false, connected = true, status = "已连接", term = term)
                else it
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                s0.ssh.restorePortForwardsAfterReconnect()
            } catch (_: Throwable) {
            }
        }
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
    val term: TermSession?,
)

