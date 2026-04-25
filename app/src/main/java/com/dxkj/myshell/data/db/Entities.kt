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
    val passwordEnc: String?, // encrypted; base64(iv+ciphertext)
    val privateKeyId: Long?, // only for key auth (future)
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "keys")
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val privateKeyPemEnc: String, // encrypted; base64(iv+ciphertext)
    val passphraseEnc: String?, // encrypted; base64(iv+ciphertext)
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

