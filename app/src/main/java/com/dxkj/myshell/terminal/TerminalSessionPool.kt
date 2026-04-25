package com.dxkj.myshell.terminal

import android.app.Application
import android.content.Context
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
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

    fun setActive(sessionId: Long) {
        _activeSessionId.value = sessionId
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

    fun open(hostId: Long) {
        val sessionId = nextId.getAndIncrement()
        val autoReconnect = prefs.getBoolean("autoReconnect_$hostId", true)

        val ssh = SshSessionManager(keyRepo = keyRepo)
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
    }

    fun close(sessionId: Long) {
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

        _sessions.update { list ->
            list.map {
                if (it.sessionId == sessionId) it.copy(title = "${host.username}@${host.host}")
                else it
            }
        }

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

        val (out, input) = streams.getOrThrow()
        val term = TermSession()
        term.setTitle("SSH")
        term.setTermIn(input)
        term.setTermOut(out)
        term.initializeEmulator(80, 24)
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
    val connecting: Boolean,
    val connected: Boolean,
    val status: String?,
    val autoReconnect: Boolean,
    val reconnectAttempt: Int = 0,
    val ssh: SshSessionManager,
    val term: TermSession?,
)

