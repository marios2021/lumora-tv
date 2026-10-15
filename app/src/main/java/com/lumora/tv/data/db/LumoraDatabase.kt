package com.lumora.tv.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaylistEntity::class, ChannelEntity::class, FavoriteEntity::class, ProgramEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LumoraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun programDao(): ProgramDao

    companion object {
        fun create(context: Context): LumoraDatabase =
            Room.databaseBuilder(context, LumoraDatabase::class.java, "lumora.db")
                // Safety net only: every schema change ships with a migration (auto-migrations above).
                .fallbackToDestructiveMigration()
                .build()
    }
}
