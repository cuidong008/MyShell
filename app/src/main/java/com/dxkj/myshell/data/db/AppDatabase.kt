package com.dxkj.myshell.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostEntity::class,
        KeyEntity::class,
    ],
    version = 2,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun keyDao(): KeyDao
}

