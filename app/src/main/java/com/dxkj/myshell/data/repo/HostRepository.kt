package com.dxkj.myshell.data.repo

import com.dxkj.myshell.data.db.HostDao
import com.dxkj.myshell.data.db.HostEntity
import kotlinx.coroutines.flow.Flow

class HostRepository(
    private val dao: HostDao,
) {
    fun observeAll(): Flow<List<HostEntity>> = dao.observeAll()

    suspend fun getById(id: Long): HostEntity? = dao.getById(id)

    suspend fun upsert(
        id: Long?,
        name: String,
        host: String,
        port: Int,
        username: String,
        authType: String,
        password: String?,
        privateKeyId: Long?,
        nowEpochMs: Long,
    ): Long {
        val cleanName = name.trim()
        val cleanHost = host.trim()
        val cleanUsername = username.trim()

        require(cleanName.isNotEmpty()) { "name required" }
        require(cleanHost.isNotEmpty()) { "host required" }
        require(port in 1..65535) { "port invalid" }
        require(cleanUsername.isNotEmpty()) { "username required" }
        require(authType == "password" || authType == "key") { "authType invalid" }
        if (authType == "password") require(!password.isNullOrBlank()) { "password required" }
        // key auth: privateKeyId is optional for now; we will enforce once key UI is fully wired.

        return if (id == null) {
            dao.insert(
                HostEntity(
                    name = cleanName,
                    host = cleanHost,
                    port = port,
                    username = cleanUsername,
                    authType = authType,
                    password = if (authType == "password") password else null,
                    privateKeyId = if (authType == "key") privateKeyId else null,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        } else {
            val existing = dao.getById(id) ?: error("host not found")
            dao.update(
                existing.copy(
                    name = cleanName,
                    host = cleanHost,
                    port = port,
                    username = cleanUsername,
                    authType = authType,
                    password = if (authType == "password") password else null,
                    privateKeyId = if (authType == "key") privateKeyId else null,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            id
        }
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}

