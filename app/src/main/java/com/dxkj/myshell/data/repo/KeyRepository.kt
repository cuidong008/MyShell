package com.dxkj.myshell.data.repo

import com.dxkj.myshell.data.db.KeyDao
import com.dxkj.myshell.data.db.KeyEntity
import com.dxkj.myshell.crypto.CryptoManager
import kotlinx.coroutines.flow.Flow

class KeyRepository(
    private val dao: KeyDao,
) {
    fun observeAll(): Flow<List<KeyEntity>> = dao.observeAll()

    suspend fun getById(id: Long): KeyEntity? = dao.getById(id)

    suspend fun getDecryptedById(id: Long): DecryptedKey? {
        val e = dao.getById(id) ?: return null
        val pem = CryptoManager.decryptFromBase64(e.privateKeyPemEnc) ?: return null
        val pass = CryptoManager.decryptFromBase64(e.passphraseEnc)
        return DecryptedKey(
            id = e.id,
            name = e.name,
            privateKeyPem = pem,
            passphrase = pass,
        )
    }

    suspend fun insert(
        name: String,
        privateKeyPem: String,
        passphrase: String?,
        nowEpochMs: Long,
    ): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "name required" }
        require(privateKeyPem.contains("PRIVATE KEY")) { "not a private key" }

        val pemEnc = CryptoManager.encryptToBase64(privateKeyPem)
        val passEnc = passphrase?.takeIf { it.isNotBlank() }?.let { CryptoManager.encryptToBase64(it) }
        return dao.insert(
            KeyEntity(
                name = cleanName,
                privateKeyPemEnc = pemEnc,
                passphraseEnc = passEnc,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}

data class DecryptedKey(
    val id: Long,
    val name: String,
    val privateKeyPem: String,
    val passphrase: String?,
)

