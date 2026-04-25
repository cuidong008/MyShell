package com.dxkj.myshell.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String, // "password" | "key"
    val password: String?, // only for password auth (MVP; later move to encrypted storage)
    val privateKeyId: Long?, // only for key auth (future)
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "keys")
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val privateKeyPem: String, // MVP: plaintext; later encrypt with Keystore
    val passphrase: String?, // MVP; later avoid storing passphrase
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

