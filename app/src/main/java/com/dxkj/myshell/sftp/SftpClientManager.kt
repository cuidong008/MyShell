package com.dxkj.myshell.sftp

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.dxkj.myshell.data.db.HostEntity
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.ui.screens.RemoteEntryUi
import com.dxkj.myshell.crypto.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.dxkj.myshell.ssh.SshCompatConfig
import android.provider.OpenableColumns
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import android.util.Log

data class ListResult(val ok: Boolean, val message: String, val entries: List<RemoteEntryUi>)

class SftpClientManager(
    private val keyRepo: KeyRepository,
) {
    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null

    suspend fun connect(host: HostEntity): com.dxkj.myshell.ssh.ConnectResult {
        disconnect()

        val c = SSHClient(SshCompatConfig.create())
        c.addHostKeyVerifier(PromiscuousVerifier())
        c.connectTimeout = 10_000
        c.timeout = 15_000
        return try {
            try {
                c.connect(host.host, host.port)
            } catch (t: Throwable) {
                return com.dxkj.myshell.ssh.ConnectResult(false, formatFailure("TCP/握手", host, t))
            }
            when (host.authType) {
                "password" -> {
                    try {
                        val pwd = CryptoManager.decryptFromBase64(host.passwordEnc) ?: ""
                        c.authPassword(host.username, pwd)
                    } catch (t: Throwable) {
                        return com.dxkj.myshell.ssh.ConnectResult(false, formatFailure("认证", host, t))
                    }
                }
                "key" -> {
                    val keyId = host.privateKeyId ?: return com.dxkj.myshell.ssh.ConnectResult(false, "未关联密钥")
                    val key = keyRepo.getDecryptedById(keyId) ?: return com.dxkj.myshell.ssh.ConnectResult(false, "密钥不存在或解密失败")
                    val finder = object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>): CharArray = key.passphrase?.toCharArray() ?: charArrayOf()
                        override fun shouldRetry(resource: Resource<*>): Boolean = false
                    }
                    val kp = c.loadKeys(key.privateKeyPem, null, finder)
                    try {
                        c.authPublickey(host.username, kp)
                    } catch (t: Throwable) {
                        return com.dxkj.myshell.ssh.ConnectResult(false, formatFailure("认证", host, t))
                    }
                }
                else -> return com.dxkj.myshell.ssh.ConnectResult(false, "未知认证方式：${host.authType}")
            }
            client = c
            sftp = c.newSFTPClient()
            com.dxkj.myshell.ssh.ConnectResult(true, "连接成功")
        } catch (t: Throwable) {
            try {
                c.disconnect()
            } catch (_: Throwable) {
            }
            try {
                c.close()
            } catch (_: Throwable) {
            }
            com.dxkj.myshell.ssh.ConnectResult(false, formatFailure("未知阶段", host, t))
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

    suspend fun list(path: String): ListResult = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext ListResult(false, "未连接", emptyList())
        val p = path.ifBlank { "/" }
        return@withContext try {
            val ls = s.ls(p)
            val entries = ls
                .filter { it.name != "." && it.name != ".." }
                .map {
                    val modeOctal = runCatching {
                        val attrs = it.attributes
                        // SSHJ：getMode() 返回 FileMode，不是整数；未带 MODE 位时表示服务端未返回权限
                        if (!attrs.has(FileAttributes.Flag.MODE)) return@runCatching ""
                        val perms = attrs.mode.permissionsMask
                        if (perms == 0) "" else String.format(Locale.US, "%o", perms)
                    }.getOrDefault("")
                    RemoteEntryUi(
                        name = it.name,
                        path = if (p.endsWith("/")) p + it.name else "$p/${it.name}",
                        isDir = it.attributes.type.name.contains("DIRECTORY", ignoreCase = true),
                        size = it.attributes.size,
                        modeOctal = modeOctal,
                    )
                }
                .sortedWith(compareByDescending<RemoteEntryUi> { it.isDir }.thenBy { it.name })
            ListResult(true, "列目录成功：${entries.size} 项", entries)
        } catch (t: Throwable) {
            ListResult(false, t.message ?: "列目录失败", emptyList())
        }
    }

    suspend fun homeDir(): Result<String> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(IllegalStateException("未连接"))
        return@withContext runCatching { s.canonicalize(".") }
    }

    suspend fun mkdir(path: String): String = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext "未连接"
        return@withContext try {
            s.mkdir(path)
            "创建目录成功：$path"
        } catch (t: Throwable) {
            "创建目录失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    suspend fun rm(remotePath: String): String = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext "未连接"
        return@withContext try {
            // Try file first, then directory.
            try {
                s.rm(remotePath)
            } catch (_: Throwable) {
                s.rmdir(remotePath)
            }
            "删除成功：$remotePath"
        } catch (t: Throwable) {
            "删除失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    suspend fun rename(oldPath: String, newPath: String): String = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext "未连接"
        return@withContext try {
            s.rename(oldPath, newPath)
            "重命名成功"
        } catch (t: Throwable) {
            "重命名失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    suspend fun chmod(remotePath: String, modeOctal: String): String = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext "未连接"
        val trimmed = modeOctal.trim()
        if (trimmed.isEmpty()) return@withContext "权限不能为空"
        val mode = trimmed.toIntOrNull(8) ?: return@withContext "无效的八进制权限（如 644、0755）"
        return@withContext try {
            s.chmod(remotePath, mode)
            "权限已更新：$remotePath → $trimmed"
        } catch (t: Throwable) {
            "chmod 失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    suspend fun readTextFile(remotePath: String, maxBytes: Int = 512 * 1024): Result<String> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(IllegalStateException("未连接"))
        return@withContext try {
            s.open(remotePath).use { rf: RemoteFile ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = rf.read(total.toLong(), buf, 0, buf.size)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) break
                    out.write(buf, 0, n)
                }
                val bytes = out.toByteArray()
                Result.success(bytes.toString(Charset.forName("UTF-8")))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun writeTextFile(remotePath: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        val s = sftp ?: return@withContext Result.failure(IllegalStateException("未连接"))
        return@withContext try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            s.open(remotePath, setOf(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.TRUNC, net.schmizz.sshj.sftp.OpenMode.WRITE)).use { rf ->
                rf.write(0, bytes, 0, bytes.size)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun downloadToDownloads(
        remotePath: String,
        filename: String,
        context: Context,
        resolver: ContentResolver,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String =
        withContext(Dispatchers.IO) {
            val s = sftp ?: return@withContext "未连接"
            val safeName = filename.ifBlank { "download.bin" }
            // Prefer saving into public Downloads via MediaStore (works best on Android 10+).
            val total = runCatching { s.size(remotePath) }.getOrElse { -1L }

            fun doDownloadToOutput(openOut: () -> java.io.OutputStream): String {
                val dest = OutputStreamDestFile(
                    name = safeName,
                    length = total,
                    open = { _ -> openOut() },
                    onProgress = { bytes -> onProgress(bytes, total) },
                )
                s.get(remotePath, dest)
                return "下载完成：$safeName"
            }

            // 1) Try MediaStore Downloads.
            val mediaStoreUri: Uri? = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    // RELATIVE_PATH is only honored on Android 10+; some ROMs may throw "not supported".
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/MyShell")
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            if (mediaStoreUri != null) {
                val r = runCatching {
                    doDownloadToOutput {
                        resolver.openOutputStream(mediaStoreUri) ?: throw IllegalStateException("无法写入下载文件")
                    }
                }
                if (r.isSuccess) return@withContext r.getOrThrow()
                try {
                    resolver.delete(mediaStoreUri, null, null)
                } catch (_: Throwable) {
                }
                Log.w("MyShell-SFTP", "download MediaStore failed: path=$remotePath name=$safeName", r.exceptionOrNull())
            }

            // 2) Fallback: app-internal downloads directory (always supported).
            return@withContext runCatching {
                val dir = File(context.filesDir, "downloads/MyShell").apply { mkdirs() }
                val outFile = File(dir, safeName)
                "${doDownloadToOutput { FileOutputStream(outFile) }}（已保存到应用内：${outFile.name}）"
            }.getOrElse { t ->
                Log.e("MyShell-SFTP", "download fallback failed: path=$remotePath name=$safeName", t)
                "下载失败：${t::class.java.simpleName}：${t.message ?: "unknown"}"
            }
        }

    suspend fun uploadFromUri(
        remoteDir: String,
        uri: Uri,
        resolver: ContentResolver,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String =
        withContext(Dispatchers.IO) {
            val s = sftp ?: return@withContext "未连接"
            val dir = remoteDir.ifBlank { "/" }
            val name = queryDisplayName(resolver, uri) ?: "upload.bin"
            fun buildRemotePath(d: String): String = if (d.endsWith("/")) d + name else "$d/$name"
            val dirCanon = runCatching { s.canonicalize(dir) }.getOrNull() ?: dir
            val remotePath = buildRemotePath(dirCanon)
            return@withContext try {
                val total = queryLength(resolver, uri)
                val src = InputStreamSourceFile(
                    name = name,
                    length = total,
                    open = { resolver.openInputStream(uri) ?: throw IllegalStateException("无法读取要上传的文件") },
                    onProgress = { bytes -> onProgress(bytes, total) },
                )
                s.put(src, remotePath)
                "上传完成：$remotePath"
            } catch (t: Throwable) {
                val msg = (t.message ?: "").trim().ifBlank { t::class.java.simpleName }
                val code = (t as? SFTPException)?.statusCode
                val home = runCatching { s.canonicalize(".") }.getOrNull()

                Log.w(
                    "MyShell-SFTP",
                    "upload failed: dir=$dir canon=$dirCanon target=$remotePath home=$home type=${t::class.java.simpleName} code=$code msg=$msg",
                    t,
                )

                buildString {
                    append("上传失败：").append(msg)
                    if (code != null) append("（SFTP code=").append(code).append("）")
                    append("（目标=").append(remotePath).append("）")
                    if (!home.isNullOrBlank()) append("（home=").append(home).append("）")
                    append("。如果你确认目录可写，通常是路径解析/服务端策略导致；请尝试先刷新目录后再上传。")
                }
            }
        }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryLength(resolver: ContentResolver, uri: Uri): Long {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) c.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun disconnect() {
        try {
            sftp?.close()
        } catch (_: Throwable) {
        } finally {
            sftp = null
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
    }
}

