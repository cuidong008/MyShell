package com.dxkj.myshell.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class SshTestInput(
    val host: String,
    val port: Int,
    val username: String,
    val authType: String, // "password" | "key"
    val password: String?,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
)

sealed class SshTestResult {
    data object Success : SshTestResult()
    data class Failure(val message: String) : SshTestResult()
}

object SshTestClient {
    /**
     * Security note: currently uses PromiscuousVerifier (trust all host keys).
     * Next step: implement known_hosts / fingerprint verification UI and storage.
     */
    fun testConnection(input: SshTestInput): SshTestResult {
        val host = input.host.trim()
        val username = input.username.trim()
        if (host.isEmpty()) return SshTestResult.Failure("主机地址不能为空")
        if (username.isEmpty()) return SshTestResult.Failure("用户名不能为空")
        if (input.port !in 1..65535) return SshTestResult.Failure("端口不合法")

        if (input.authType != "password" && input.authType != "key") {
            return SshTestResult.Failure("认证方式不支持：${input.authType}")
        }
        val password = input.password?.takeIf { it.isNotBlank() }
        val privateKeyPem = input.privateKeyPem?.takeIf { it.isNotBlank() }
        if (input.authType == "password" && password == null) return SshTestResult.Failure("密码不能为空")
        if (input.authType == "key" && privateKeyPem == null) return SshTestResult.Failure("私钥不能为空")

        val client = SSHClient(SshCompatConfig.create())
        return try {
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = 10_000
            client.timeout = 15_000
            try {
                client.connect(host, input.port)
            } catch (t: Throwable) {
                return SshTestResult.Failure(formatFailure(stage = "TCP/握手", t = t))
            }
            try {
                when (input.authType) {
                    "password" -> client.authPassword(username, password!!)
                    "key" -> {
                        val finder = object : PasswordFinder {
                            override fun reqPassword(resource: Resource<*>): CharArray =
                                input.passphrase?.toCharArray() ?: charArrayOf()

                            override fun shouldRetry(resource: Resource<*>): Boolean = false
                        }
                        val kp = client.loadKeys(privateKeyPem!!, null, finder)
                        client.authPublickey(username, kp)
                    }
                }
            } catch (t: Throwable) {
                return SshTestResult.Failure(formatFailure(stage = "认证", t = t))
            }
            SshTestResult.Success
        } catch (t: Throwable) {
            SshTestResult.Failure(formatFailure(stage = "未知阶段", t = t))
        } finally {
            try {
                client.disconnect()
            } catch (_: Throwable) {
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun formatFailure(stage: String, t: Throwable): String {
        val type = t::class.java.simpleName
        val msg = (t.message ?: "").trim()

        val hint = when (t) {
            is UnknownHostException -> "（域名无法解析）"
            is ConnectException -> "（无法建立 TCP 连接：端口不通/被防火墙拦截/地址不对）"
            is SocketTimeoutException -> "（连接超时：网络不通或被丢包）"
            is SocketException -> "（底层 Socket 错误：常见于服务器主动断开/Connection reset）"
            is UserAuthException -> "（认证失败：密码/密钥不对，或服务器禁用该认证方式）"
            is TransportException -> "（传输层错误：常见于算法不兼容/握手被服务器断开）"
            else -> ""
        }

        return buildString {
            append("阶段=").append(stage)
            append("，异常=").append(type)
            if (hint.isNotEmpty()) append(hint)
            if (msg.isNotEmpty()) append("，详情=").append(msg)
        }
    }
}

