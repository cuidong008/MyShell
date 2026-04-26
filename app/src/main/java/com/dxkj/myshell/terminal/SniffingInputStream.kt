package com.dxkj.myshell.terminal

import java.io.InputStream

/**
 * 在不影响 TermSession 读数据的前提下，把终端回显副本交给端口嗅探（UTF-8，残缺序列用替换符）。
 */
class SniffingInputStream(
    private val upstream: InputStream,
    private val onBytes: (ByteArray, Int, Int) -> Unit,
) : InputStream() {

    override fun read(): Int {
        val v = upstream.read()
        if (v >= 0) {
            val b = byteArrayOf(v.toByte())
            onBytes(b, 0, 1)
        }
        return v
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = upstream.read(b, off, len)
        if (n > 0) {
            onBytes(b, off, n)
        }
        return n
    }

    override fun close() {
        upstream.close()
    }
}
