package com.dxkj.myshell.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.transport.cipher.AES128CTR
import net.schmizz.sshj.transport.cipher.AES256CTR
import net.schmizz.sshj.transport.cipher.TripleDESCBC
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.DHG1
import net.schmizz.sshj.transport.kex.DHG14
import net.schmizz.sshj.transport.kex.DHGexSHA1
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.mac.HMACSHA1
import net.schmizz.sshj.transport.mac.HMACSHA2256

/**
 * Some SSH servers only support legacy algorithms (e.g. diffie-hellman-group1-sha1, ssh-rsa).
 * sshj's defaults may not include them; in that case the server can drop the handshake (Connection reset).
 *
 * This config expands the enabled algorithms to improve compatibility.
 */
object SshCompatConfig {
    fun create(): DefaultConfig {
        val config = DefaultConfig()

        // Prefer modern KEX, but keep legacy fallbacks.
        config.setKeyExchangeFactories(
            listOf(
                Curve25519SHA256.Factory(),
                DHGexSHA256.Factory(),
                DHG14.Factory(),
                DHGexSHA1.Factory(),
                DHG1.Factory(),
            ),
        )

        // Enable common ciphers (include 3des as legacy fallback).
        config.setCipherFactories(
            listOf(
                AES256CTR.Factory(),
                AES128CTR.Factory(),
                TripleDESCBC.Factory(),
            ),
        )

        // Enable common MACs (include SHA1 as legacy fallback).
        config.setMACFactories(
            listOf(
                HMACSHA2256.Factory(),
                HMACSHA1.Factory(),
            ),
        )

        // Prefer ssh-rsa host key algorithm when needed (legacy servers).
        // Note: host key algorithms are managed as "KeyAlgorithms" in sshj, not signature factories.
        config.prioritizeSshRsaKeyAlgorithm()

        return config
    }
}

