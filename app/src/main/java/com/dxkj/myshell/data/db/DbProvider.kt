package com.dxkj.myshell.data.db

import android.content.Context
import androidx.room.Room

object DbProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        val existing = instance
        if (existing != null) return existing

        return synchronized(this) {
            val again = instance
            if (again != null) {
                again
            } else {
                val created = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myshell.db",
                )
                    // MVP: schema changes are frequent; we'll implement migrations once stabilized.
                    .fallbackToDestructiveMigration()
                    .build()
                instance = created
                created
            }
        }
    }
}

