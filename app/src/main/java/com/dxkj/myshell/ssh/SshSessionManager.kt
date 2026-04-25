package com.dxkj.myshell.ssh

import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.InputStream
import java.io.OutputStream

data class ConnectResult(val ok: Boolean, val message: String)

class SshSessionManager(
    private val keyRepo: KeyRepository,
) {
    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null
    private var writer: java.io.OutputStream? = null
    private var reader: java.io.InputStream? = null
    private var readerJob: Job? = null
    private var scope: CoroutineScope? = null

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

                // PTY might be rejected on some servers; try PTY first, then fallback without PTY.
                try {
                    sess.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
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
                sess.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
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

    suspend fun disconnect() {
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
}

