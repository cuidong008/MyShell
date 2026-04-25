package com.dxkj.myshell.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyDao {
    @Query("SELECT * FROM keys ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<KeyEntity>>

    @Query("SELECT * FROM keys WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): KeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KeyEntity): Long

    @Update
    suspend fun update(entity: KeyEntity)

    @Delete
    suspend fun delete(entity: KeyEntity)

    @Query("DELETE FROM keys WHERE id = :id")
    suspend fun deleteById(id: Long)
}

