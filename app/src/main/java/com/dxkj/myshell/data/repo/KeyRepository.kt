package com.dxkj.myshell.data.repo

import com.dxkj.myshell.data.db.KeyDao
import com.dxkj.myshell.data.db.KeyEntity
import kotlinx.coroutines.flow.Flow

class KeyRepository(
    private val dao: KeyDao,
) {
    fun observeAll(): Flow<List<KeyEntity>> = dao.observeAll()

    suspend fun getById(id: Long): KeyEntity? = dao.getById(id)

    suspend fun insert(
        name: String,
        privateKeyPem: String,
        passphrase: String?,
        nowEpochMs: Long,
    ): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "name required" }
        require(privateKeyPem.contains("PRIVATE KEY")) { "not a private key" }
        return dao.insert(
            KeyEntity(
                name = cleanName,
                privateKeyPem = privateKeyPem,
                passphrase = passphrase?.takeIf { it.isNotBlank() },
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}

