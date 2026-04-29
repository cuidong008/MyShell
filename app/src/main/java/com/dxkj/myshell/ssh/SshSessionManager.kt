package com.dxkj.myshell.ssh

import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ConnectResult(val ok: Boolean, val message: String)

/** 一条本地监听 → 远端 TCP 的 SSH -L 风格转发（与 VS Code「端口」类似）。 */
data class PortForwardItem(
    val id: Long,
    val localHost: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
)

/** 扫描或终端嗅探到的「远端可转发」地址，尚未必已建立隧道。 */
data class DiscoveredListen(
    val remoteHost: String,
    val remotePort: Int,
    val source: String,
)

private data class ForwardRuntime(
    val item: PortForwardItem,
    val forwarder: LocalPortForwarder,
    val serverSocket: ServerSocket,
)

/** 用户曾建立的转发（本机绑定端口 + 远端），在 SSH 重连或从后台回到前台后用于自动恢复。 */
private data class ForwardRestoreRule(
    val remoteHost: String,
    val remotePort: Int,
    /** 希望在本机绑定的端口（与当时实际监听一致）；占用时退回随机端口。 */
    val preferredLocalPort: Int,
)

/** [onPortForwardUiChanged] 在转发列表 / 已发现 / 忽略集变化时调用（前台保活、按主机合并 UI 等），勿抛异常。 */
class SshSessionManager(
    private val keyRepo: KeyRepository,
    private val onPortForwardUiChanged: () -> Unit = {},
) {
    @Volatile private var lastPtyCols: Int = 80
    @Volatile private var lastPtyRows: Int = 24

    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var writer: java.io.OutputStream? = null
    private var reader: java.io.InputStream? = null
    private var readerJob: Job? = null
    private var scope: CoroutineScope? = null

    private val forwardSupervisor = SupervisorJob()
    private val forwardScope = CoroutineScope(forwardSupervisor + Dispatchers.IO)
    private val forwardIdGen = AtomicLong(1)
    private val activeForwards = ConcurrentHashMap<Long, ForwardRuntime>()
    private val restoreRules = ConcurrentHashMap<String, ForwardRestoreRule>()
    private val _portForwards = MutableStateFlow<List<PortForwardItem>>(emptyList())
    val portForwards: StateFlow<List<PortForwardItem>> = _portForwards.asStateFlow()

    private val discoveredMap = LinkedHashMap<String, DiscoveredListen>()
    private val _discoveredList = MutableStateFlow<List<DiscoveredListen>>(emptyList())
    val discoveredList: StateFlow<List<DiscoveredListen>> = _discoveredList.asStateFlow()

    private val terminalSniffBuf = StringBuilder(16_384)
    private val terminalSeenKeys = mutableSetOf<String>()

    /** 用户主动停止转发或从「已发现」移除的远端地址；批量/自动转发不再碰，除非用户单条再点「同号转发」。 */
    private val ignoredRemoteKeys = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val _ignoredRemotePorts = MutableStateFlow<Set<String>>(emptySet())
    val ignoredRemotePorts: StateFlow<Set<String>> = _ignoredRemotePorts.asStateFlow()

    private fun remotePortKey(host: String, port: Int) = "${host.trim().lowercase()}:$port"

    private fun refreshIgnoredFlow() {
        _ignoredRemotePorts.value = ignoredRemoteKeys.toSet()
        notifyPortForwardUiChanged()
    }

    fun isRemotePortIgnored(remoteHost: String, remotePort: Int): Boolean =
        ignoredRemoteKeys.contains(remotePortKey(remoteHost, remotePort))

    /** 同号转发成功后从「已发现」移除（不记入忽略，与手动停止转发不同）。 */
    @Synchronized
    fun removeDiscoveredFromListOnly(host: String, port: Int) {
        val key = remotePortKey(host, port)
        if (discoveredMap.remove(key) != null) {
            refreshDiscoveredFlow()
        }
    }

    fun clearAllDismissedRemotePorts() {
        ignoredRemoteKeys.clear()
        refreshIgnoredFlow()
    }

    /** 取消对某远端地址的「忽略」（同主机多会话合并展示时，会由池对全部连接调用）。 */
    @Synchronized
    fun clearIgnoredRemote(remoteHost: String, remotePort: Int) {
        val key = remotePortKey(remoteHost, remotePort)
        if (ignoredRemoteKeys.remove(key)) {
            refreshIgnoredFlow()
        }
    }

    private fun refreshDiscoveredFlow() {
        _discoveredList.value = discoveredMap.values.reversed()
        notifyPortForwardUiChanged()
    }

    @Synchronized
    fun mergeDiscoveredPort(host: String, port: Int, source: String) {
        val h = host.trim().ifEmpty { return }
        if (port !in 1..65535) return
        if (port == SSH_CONTROL_PORT) return
        val key = "${h.lowercase()}:$port"
        if (ignoredRemoteKeys.contains(key)) return
        discoveredMap.remove(key)
        discoveredMap[key] = DiscoveredListen(h, port, source)
        while (discoveredMap.size > 80) {
            val first = discoveredMap.keys.first()
            discoveredMap.remove(first)
        }
        refreshDiscoveredFlow()
    }

    /**
     * 将终端回显喂给嗅探器；返回**本轮新出现**的远端地址（用于自动同号转发去重）。
     */
    @Synchronized
    fun feedTerminalSniffForPorts(bytes: ByteArray, off: Int, len: Int): List<Pair<String, Int>> {
        if (len <= 0) return emptyList()
        val chunk = String(bytes, off, len, Charsets.UTF_8)
        terminalSniffBuf.append(chunk)
        if (terminalSniffBuf.length > 20_000) {
            terminalSniffBuf.delete(0, terminalSniffBuf.length - 10_000)
        }
        val text = terminalSniffBuf.toString()
        val extracted = RemotePortDiscovery.extractFromTerminalChunk(text)
        val news = mutableListOf<Pair<String, Int>>()
        for ((h, p) in extracted) {
            if (p == SSH_CONTROL_PORT) continue
            val k = "${h.lowercase()}:$p"
            if (ignoredRemoteKeys.contains(k)) continue
            mergeDiscoveredPort(h, p, "终端")
            if (terminalSeenKeys.add(k)) {
                news.add(h to p)
            }
        }
        return news
    }

    @Synchronized
    private fun clearPortDiscoveryState() {
        discoveredMap.clear()
        _discoveredList.value = emptyList()
        terminalSeenKeys.clear()
        terminalSniffBuf.clear()
        ignoredRemoteKeys.clear()
        _ignoredRemotePorts.value = emptySet()
        notifyPortForwardUiChanged()
    }

    fun isForwardingRemote(remoteHost: String, remotePort: Int): Boolean {
        val h = remoteHost.trim().lowercase()
        return activeForwards.values.any {
            it.item.remotePort == remotePort && it.item.remoteHost.trim().lowercase() == h
        }
    }

    /**
     * 优先本机端口与远端端口一致；若占用则改为系统分配本地端口。
     * @param explicitUserAction 为 true 时（单条「同号转发」）会先取消「忽略」；为 false 时（批量/自动）若该远端已被用户忽略则直接失败。
     */
    suspend fun startSamePortForwardIfAbsent(
        remoteHost: String,
        remotePort: Int,
        explicitUserAction: Boolean = true,
    ): Result<PortForwardItem> {
        if (remotePort == SSH_CONTROL_PORT) {
            return Result.failure(IllegalArgumentException("22 为 SSH 端口，无需转发"))
        }
        val key = remotePortKey(remoteHost, remotePort)
        if (!explicitUserAction && ignoredRemoteKeys.contains(key)) {
            return Result.failure(IllegalStateException("已忽略"))
        }
        if (explicitUserAction) {
            ignoredRemoteKeys.remove(key)
            refreshIgnoredFlow()
        }
        if (isForwardingRemote(remoteHost, remotePort)) {
            return Result.failure(IllegalStateException("已在转发"))
        }
        var r = startLocalPortForward(remotePort, remoteHost, remotePort)
        if (r.isFailure) {
            r = startLocalPortForward(0, remoteHost, remotePort)
        }
        return r
    }

    /**
     * 在远端执行 `ss`/`netstat` 合并到发现列表；若 [autoSamePortForward] 则对合适端口尝试「本地同端口 → 远端」转发。
     */
    suspend fun scanRemoteListenersAndMerge(autoSamePortForward: Boolean): Result<Int> =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext Result.failure(IllegalStateException("未连接"))
            val r = execCaptureUnscoped(
                c,
                "bash -lc 'export LANG=C LC_ALL=C; ss -Hltn 2>/dev/null || netstat -lnt 2>/dev/null || true'",
                timeoutSec = 12L,
            )
            if (r.isFailure) {
                return@withContext Result.failure(r.exceptionOrNull() ?: Exception("扫描失败"))
            }
            val text = r.getOrNull() ?: ""
            var list = RemotePortDiscovery.parseSsListenTcp(text)
            if (list.isEmpty()) {
                list = RemotePortDiscovery.parseNetstatListenTcp(text)
            }
            for ((h, p) in list) {
                mergeDiscoveredPort(h, p, "远端扫描")
            }
            if (autoSamePortForward) {
                for ((h, p) in list) {
                    if (!RemotePortDiscovery.allowAutoForwardFromRemoteScan(p)) continue
                    startSamePortForwardIfAbsent(h, p, explicitUserAction = false)
                }
            }
            Result.success(list.size)
        }

    /**
     * 在已连接的 SSH 上执行一条远端命令并合并 stdout/stderr（用于概览监控等）。
     */
    suspend fun execRemoteCapture(command: String, timeoutSec: Long = 12L): Result<String> =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext Result.failure(IllegalStateException("未连接"))
            execCaptureUnscoped(c, command, timeoutSec)
        }

    private suspend fun execCaptureUnscoped(c: SSHClient, command: String, timeoutSec: Long = 12L): Result<String> =
        withContext(Dispatchers.IO) {
            val sess = c.startSession()
            try {
                val cmd = sess.exec(command)
                cmd.join(timeoutSec.coerceIn(3L, 120L), TimeUnit.SECONDS)
                val out = cmd.inputStream.use { ins -> ins.readBytes().toString(Charsets.UTF_8) }
                val err = cmd.errorStream.use { es -> es.readBytes().toString(Charsets.UTF_8) }
                val merged = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n")
                Result.success(merged)
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                try {
                    sess.close()
                } catch (_: Throwable) {
                }
            }
        }

    private fun refreshPortForwardFlow() {
        _portForwards.value = activeForwards.values.map { it.item }.sortedWith(compareBy({ it.localPort }, { it.id }))
        notifyPortForwardUiChanged()
    }

    private fun notifyPortForwardUiChanged() {
        try {
            onPortForwardUiChanged()
        } catch (_: Throwable) {
        }
    }

    fun activeLocalPortForwardCount(): Int = activeForwards.size

    private fun rememberRestoreRule(remoteHost: String, remotePort: Int, boundLocalPort: Int) {
        val h = remoteHost.trim()
        if (h.isEmpty() || remotePort !in 1..65535 || remotePort == SSH_CONTROL_PORT || boundLocalPort !in 1..65535) return
        val key = remotePortKey(h, remotePort)
        if (ignoredRemoteKeys.contains(key)) return
        restoreRules[key] = ForwardRestoreRule(h, remotePort, boundLocalPort)
    }

    private fun forgetRestoreRule(remoteHost: String, remotePort: Int) {
        restoreRules.remove(remotePortKey(remoteHost, remotePort))
    }

    /**
     * 在 SSH 已连接的前提下，按保存的规则重建本地转发（断线重连、从后台返回后调用）。
     */
    suspend fun restorePortForwardsAfterReconnect() {
        if (client == null) return
        val snapshot = restoreRules.values.toList()
        for (rule in snapshot) {
            if (rule.remotePort == SSH_CONTROL_PORT) continue
            if (isRemotePortIgnored(rule.remoteHost, rule.remotePort)) continue
            if (isForwardingRemote(rule.remoteHost, rule.remotePort)) continue
            var r = startLocalPortForward(rule.preferredLocalPort, rule.remoteHost, rule.remotePort)
            if (r.isFailure) {
                r = startLocalPortForward(0, rule.remoteHost, rule.remotePort)
            }
        }
    }

    /**
     * @param localPort 本机监听端口；0 表示由系统分配临时端口
     */
    suspend fun startLocalPortForward(
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Result<PortForwardItem> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext Result.failure(IllegalStateException("未连接"))
        val host = remoteHost.trim().ifEmpty { return@withContext Result.failure(IllegalArgumentException("远端主机不能为空")) }
        ignoredRemoteKeys.remove(remotePortKey(host, remotePort))
        refreshIgnoredFlow()
        if (remotePort !in 1..65535) {
            return@withContext Result.failure(IllegalArgumentException("远端端口无效"))
        }
        if (remotePort == SSH_CONTROL_PORT) {
            return@withContext Result.failure(IllegalArgumentException("22 为 SSH 端口，无需转发"))
        }
        if (localPort !in 0..65535) {
            return@withContext Result.failure(IllegalArgumentException("本地端口无效"))
        }
        val bindAddr = InetAddress.getByName("127.0.0.1")
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            if (localPort == 0) {
                ss.bind(InetSocketAddress(bindAddr, 0))
            } else {
                ss.bind(InetSocketAddress(bindAddr, localPort))
            }
        } catch (t: Throwable) {
            try {
                ss.close()
            } catch (_: Throwable) {
            }
            return@withContext Result.failure(t)
        }
        val boundPort = ss.localPort
        val localHostStr = "127.0.0.1"
        val params = Parameters(localHostStr, boundPort, host, remotePort)
        val forwarder = try {
            c.newLocalPortForwarder(params, ss)
        } catch (t: Throwable) {
            try {
                ss.close()
            } catch (_: Throwable) {
            }
            return@withContext Result.failure(t)
        }
        val id = forwardIdGen.getAndIncrement()
        val item = PortForwardItem(id, localHostStr, boundPort, host, remotePort)
        activeForwards[id] = ForwardRuntime(item, forwarder, ss)
        rememberRestoreRule(host, remotePort, boundPort)
        refreshPortForwardFlow()
        forwardScope.launch {
            try {
                forwarder.listen()
            } catch (_: SocketException) {
            } catch (_: Throwable) {
            } finally {
                activeForwards.remove(id)
                refreshPortForwardFlow()
                try {
                    ss.close()
                } catch (_: Throwable) {
                }
            }
        }
        Result.success(item)
    }

    suspend fun stopLocalPortForward(id: Long, markRemoteDismissed: Boolean = true) = withContext(Dispatchers.IO) {
        val entry = activeForwards.remove(id) ?: return@withContext
        try {
            entry.forwarder.close()
        } catch (_: Throwable) {
        }
        try {
            entry.serverSocket.close()
        } catch (_: Throwable) {
        }
        if (markRemoteDismissed) {
            val rk = remotePortKey(entry.item.remoteHost, entry.item.remotePort)
            ignoredRemoteKeys.add(rk)
            forgetRestoreRule(entry.item.remoteHost, entry.item.remotePort)
            synchronized(this@SshSessionManager) {
                discoveredMap.remove(rk)
                refreshDiscoveredFlow()
            }
            refreshIgnoredFlow()
        }
        refreshPortForwardFlow()
    }

    /** 停止旧转发并以新的本机端口重新监听（远端 host:port 不变）。 */
    suspend fun replaceLocalForward(itemId: Long, newLocalPort: Int): Result<PortForwardItem> {
        val entry = activeForwards[itemId] ?: return Result.failure(IllegalStateException("记录不存在"))
        val remoteHost = entry.item.remoteHost
        val remotePort = entry.item.remotePort
        stopLocalPortForward(itemId, markRemoteDismissed = false)
        return startLocalPortForward(newLocalPort, remoteHost, remotePort)
    }

    private suspend fun stopAllLocalPortForwardsLocked() = withContext(Dispatchers.IO) {
        val snapshot = activeForwards.toMap()
        activeForwards.clear()
        for ((_, e) in snapshot) {
            try {
                e.forwarder.close()
            } catch (_: Throwable) {
                // listen 尚未进入时 runningThread 可能为 null，会 NPE；随后关闭 socket 即可
            }
            try {
                e.serverSocket.close()
            } catch (_: Throwable) {
            }
        }
        forwardSupervisor.cancelChildren()
        _portForwards.value = emptyList()
        notifyPortForwardUiChanged()
    }

    suspend fun connect(host: HostEntity): ConnectResult {
        disconnect()

        val c = SSHClient(SshCompatConfig.create())
        c.addHostKeyVerifier(PromiscuousVerifier())
        c.connectTimeout = 10_000
        c.timeout = 15_000

        return try {
            try {
                c.connect(host.host, host.port)
            } catch (t: Throwable) {
                return ConnectResult(false, formatFailure("TCP/握手", host, t))
            }

            when (host.authType) {
                "password" -> {
                    val pwd = CryptoManager.decryptFromBase64(host.passwordEnc) ?: ""
                    try {
                        c.authPassword(host.username, pwd)
                    } catch (t: Throwable) {
                        return ConnectResult(false, formatFailure("认证", host, t))
                    }
                }
                "key" -> {
                    val keyId = host.privateKeyId
                        ?: return ConnectResult(false, "该主机选择了密钥认证，但未关联密钥")
                    val key = keyRepo.getDecryptedById(keyId) ?: return ConnectResult(false, "密钥不存在或解密失败(id=$keyId)")

                    val finder = object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>): CharArray {
                            return key.passphrase?.toCharArray() ?: charArrayOf()
                        }

                        override fun shouldRetry(resource: Resource<*>): Boolean = false
                    }

                    val kp = c.loadKeys(key.privateKeyPem, null, finder)
                    try {
                        c.authPublickey(host.username, kp)
                    } catch (t: Throwable) {
                        return ConnectResult(false, formatFailure("认证", host, t))
                    }
                }
                else -> return ConnectResult(false, "未知认证方式：${host.authType}")
            }

            client = c
            ConnectResult(true, "连接成功")
        } catch (t: Throwable) {
            try {
                c.disconnect()
            } catch (_: Throwable) {
            }
            try {
                c.close()
            } catch (_: Throwable) {
            }
            ConnectResult(false, formatFailure("未知阶段", host, t))
        }
    }

    private fun formatFailure(stage: String, host: HostEntity, t: Throwable): String {
        val type = t::class.java.simpleName
        val msg = (t.message ?: "").trim()
        val hint = when (t) {
            is UnknownHostException -> "（域名无法解析）"
            is ConnectException -> "（无法建立 TCP 连接：端口不通/被防火墙拦截/地址不对）"
            is SocketTimeoutException -> "（连接超时）"
            is SocketException -> "（底层 Socket 错误：常见于服务器主动断开/Connection reset）"
            is UserAuthException -> "（认证失败：密码/密钥不对，或服务器禁用该认证方式）"
            is TransportException -> "（传输层错误：常见于算法不兼容/握手被断开）"
            else -> ""
        }
        return buildString {
            append("主机=").append(host.username).append("@").append(host.host).append(":").append(host.port)
            append("，阶段=").append(stage)
            append("，异常=").append(type)
            if (hint.isNotEmpty()) append(hint)
            if (msg.isNotEmpty()) append("，详情=").append(msg)
        }
    }
 
    private fun allocatePtyLikeHaven(sess: Session, cols: Int = 80, rows: Int = 24) {
        // 对齐 Haven：仅请求 xterm-256color PTY，不额外注入环境变量或降级 term。
        sess.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
    }

    fun resizePty(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastPtyCols = cols
        lastPtyRows = rows
        try {
            shell?.changeWindowDimensions(cols, rows, 0, 0)
        } catch (_: Throwable) {
        }
    }

    fun startShell(
        onOutput: (String) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        val c = client
        if (c == null) {
            onClosed("未连接")
            return
        }

        // Ensure session/shell open happens on IO thread (it performs network I/O).
        val s = CoroutineScope(Dispatchers.IO)
        scope = s

        s.launch {
            try {
                val sess = c.startSession()
                session = sess

                // 对齐 Haven：固定 xterm-256color PTY，失败则继续尝试启动 shell。
                try {
                    allocatePtyLikeHaven(sess, cols = lastPtyCols, rows = lastPtyRows)
                } catch (t: Throwable) {
                    onOutput("PTY 分配失败（将尝试无 PTY）：${t::class.java.simpleName}: ${t.message}\n")
                }

                val sh = sess.startShell()
                shell = sh
                writer = sh.outputStream
                reader = sh.inputStream

                val input = sh.inputStream
                readerJob = s.launch {
                    val buf = ByteArray(4096)
                    try {
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            onOutput(String(buf, 0, n, Charsets.UTF_8))
                        }
                        onClosed("会话结束")
                    } catch (t: Throwable) {
                        onClosed("${t::class.java.simpleName}: ${t.message ?: "会话异常结束"}")
                    }
                }
            } catch (t: Throwable) {
                onClosed("启动 shell 失败：${t::class.java.simpleName}: ${t.message ?: ""}".trim())
            }
        }
    }

    /**
     * Opens a shell channel and returns its (stdin, stdout) streams for terminal emulation views.
     * Must be called after [connect] succeeded.
     */
    suspend fun openShellStreams(): Result<Pair<OutputStream, InputStream>> {
        val c = client ?: return Result.failure(IllegalStateException("未连接"))
        return try {
            val sess = c.startSession()
            session = sess
            try {
                allocatePtyLikeHaven(sess, cols = lastPtyCols, rows = lastPtyRows)
            } catch (_: Throwable) {
            }
            val sh = sess.startShell()
            shell = sh
            writer = sh.outputStream
            reader = sh.inputStream
            Result.success(writer!! to reader!!)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun send(text: String) {
        try {
            val out = writer ?: return
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Throwable) {
        }
    }

    fun sendBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        try {
            val out = writer ?: return
            out.write(bytes, offset, length)
            out.flush()
        } catch (_: Throwable) {
        }
    }

    suspend fun disconnect() {
        stopAllLocalPortForwardsLocked()
        clearPortDiscoveryState()
        try {
            readerJob?.cancel()
        } catch (_: Throwable) {
        } finally {
            readerJob = null
        }
        try {
            scope?.cancel()
        } catch (_: Throwable) {
        } finally {
            scope = null
        }

        try {
            shell?.close()
        } catch (_: Throwable) {
        } finally {
            shell = null
        }
        try {
            session?.close()
        } catch (_: Throwable) {
        } finally {
            session = null
        }
        try {
            client?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            client?.close()
        } catch (_: Throwable) {
        } finally {
            client = null
        }
        writer = null
        reader = null
    }

    companion object {
        private const val SSH_CONTROL_PORT = 22
    }
}

