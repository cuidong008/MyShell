package com.dxkj.myshell.sftp

import net.schmizz.sshj.xfer.LocalDestFile
import net.schmizz.sshj.xfer.LocalFileFilter
import net.schmizz.sshj.xfer.LocalSourceFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class CountingOutputStream(
    private val delegate: OutputStream,
    private val onBytes: (Long) -> Unit,
) : OutputStream() {
    private var count = 0L

    override fun write(b: Int) {
        delegate.write(b)
        count += 1
        onBytes(count)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len.toLong()
        onBytes(count)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

class CountingInputStream(
    private val delegate: InputStream,
    private val onBytes: (Long) -> Unit,
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val r = delegate.read()
        if (r >= 0) {
            count += 1
            onBytes(count)
        }
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            count += n.toLong()
            onBytes(count)
        }
        return n
    }

    override fun close() = delegate.close()
}

class OutputStreamDestFile(
    private val name: String,
    private val length: Long,
    private val open: (append: Boolean) -> OutputStream,
    private val onProgress: (Long) -> Unit,
) : LocalDestFile {
    override fun getLength(): Long = length

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream = getOutputStream(false)

    @Throws(IOException::class)
    override fun getOutputStream(append: Boolean): OutputStream {
        return CountingOutputStream(open(append), onProgress)
    }

    /**
     * sshj 的 Downloader 会把传入的 LocalDestFile 当作“目录”来解析，
     * 并调用 getTargetFile(remoteName) 获取真正的目标文件。
     * 我们这里下载的是单文件场景，因此返回一个“指向同一输出流工厂”的目标文件即可。
     */
    override fun getChild(child: String): LocalDestFile = getTargetFile(child)
    override fun getTargetFile(name: String): LocalDestFile =
        OutputStreamDestFile(
            name = name,
            length = length,
            open = open,
            onProgress = onProgress,
        )
    override fun getTargetDirectory(name: String): LocalDestFile = this
    override fun setPermissions(perms: Int) {}
    override fun setLastAccessedTime(time: Long) {}
    override fun setLastModifiedTime(time: Long) {}
}

class InputStreamSourceFile(
    private val name: String,
    private val length: Long,
    private val open: () -> InputStream,
    private val onProgress: (Long) -> Unit,
) : LocalSourceFile {
    override fun getName(): String = name
    override fun getLength(): Long = length

    @Throws(IOException::class)
    override fun getInputStream(): InputStream = CountingInputStream(open(), onProgress)

    // 默认上传权限：rw-r--r--（避免出现 ---------- 的不可读文件）
    override fun getPermissions(): Int = 420 // 0644
    override fun isFile(): Boolean = true
    override fun isDirectory(): Boolean = false
    override fun getChildren(filter: LocalFileFilter): Iterable<LocalSourceFile> = emptyList()
    override fun providesAtimeMtime(): Boolean = false
    override fun getLastAccessTime(): Long = 0
    override fun getLastModifiedTime(): Long = 0
}

