package com.dxkj.myshell.terminal

import android.util.Log
import com.dxkj.myshell.crypto.CryptoManager
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.KeyRepository
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * JSch-based interactive shell session (Haven-style).
 *
 * We use JSch ChannelShell + PTY so the server-side shell behaves the same as Haven.
 */
class JschShellSession(
    private val keyRepo: KeyRepository,
) : Closeable {
    private var jsch: JSch? = null
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var resizeExecutor: ScheduledThreadPoolExecutor? = null
    @Volatile private var pendingResize: ScheduledFuture<*>? = null
    @Volatile private var closed: Boolean = false

    val input: InputStream
        get() = channel?.inputStream ?: error("Shell not started")
    val output: OutputStream
        get() = channel?.outputStream ?: error("Shell not started")

    fun connectAndOpenShell(
        host: HostEntity,
        term: String = "xterm-256color",
        cols: Int = 80,
        rows: Int = 24,
    ) {
        close()
        closed = false
        resizeExecutor = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "jsch-pty-resize").apply { isDaemon = true }
        }
        val j = JSch()
        jsch = j

        when (host.authType) {
            "password" -> {
                // handled via session.setPassword
            }
            "key" -> {
                val keyId = host.privateKeyId ?: error("privateKeyId required for key auth")
                val k = runBlockingNoThrow { keyRepo.getDecryptedById(keyId) }
                    ?: error("key not found")
                val pass = k.passphrase?.toByteArray(Charsets.UTF_8)
                j.addIdentity("myshell-key-$keyId", k.privateKeyPem.toByteArray(Charsets.UTF_8), null, pass)
            }
            else -> error("unknown authType=${host.authType}")
        }

        val s = j.getSession(host.username, host.host, host.port)
        // Match Haven: accept any key at SSH lib level; TOFU would be handled at app layer (not implemented here yet).
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 3

        if (host.authType == "password") {
            val pwd = CryptoManager.decryptFromBase64(host.passwordEnc) ?: ""
            s.setPassword(pwd)
        }

        s.connect(10_000)
        session = s

        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType(term, cols, rows, 0, 0)
        ch.connect(10_000)
        channel = ch
    }

    fun resize(cols: Int, rows: Int) {
        if (closed) return
        if (cols <= 0 || rows <= 0) return
        val exec = resizeExecutor ?: return
        if (exec.isShutdown) return
        // Debounce like Haven: IME/layout animations can fire resize every frame.
        try {
            pendingResize?.cancel(false)
            pendingResize = exec.schedule({
                try {
                    channel?.setPtySize(cols, rows, 0, 0)
                } catch (t: Throwable) {
                    Log.w("JschShellSession", "resize failed: ${t.message}")
                }
            }, 150, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor already shut down during teardown; ignore late resize callbacks.
        }
    }

    fun debugState(): String {
        val s = session
        val ch = channel
        val sessConnected = try { s?.isConnected == true } catch (_: Throwable) { false }
        val chConnected = try { ch?.isConnected == true } catch (_: Throwable) { false }
        val chClosed = try { ch?.isClosed == true } catch (_: Throwable) { false }
        val exit = try { ch?.exitStatus } catch (_: Throwable) { null }
        return "sessConnected=$sessConnected chConnected=$chConnected chClosed=$chClosed exitStatus=$exit"
    }

    override fun close() {
        closed = true
        try { pendingResize?.cancel(false) } catch (_: Throwable) {}
        try { resizeExecutor?.shutdownNow() } catch (_: Throwable) {}
        resizeExecutor = null
        try { channel?.disconnect() } catch (_: Throwable) {}
        channel = null
        try { session?.disconnect() } catch (_: Throwable) {}
        session = null
        jsch = null
    }
}

private fun <T> runBlockingNoThrow(block: suspend () -> T): T? {
    return try {
        kotlinx.coroutines.runBlocking { block() }
    } catch (_: Throwable) {
        null
    }
}

