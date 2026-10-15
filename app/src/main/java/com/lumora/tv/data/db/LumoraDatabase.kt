package com.lumora.tv.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        ProgramEntity::class,
        LockedGroupEntity::class,
        VodEntity::class,
        ProgressEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class LumoraDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun programDao(): ProgramDao
    abstract fun lockedGroupDao(): LockedGroupDao
    abstract fun vodDao(): VodDao
    abstract fun progressDao(): ProgressDao

    companion object {
        fun create(context: Context): LumoraDatabase =
            Room.databaseBuilder(context, LumoraDatabase::class.java, "lumora.db")
                // Safety net only: every schema change ships with a migration (auto-migrations above).
                .fallbackToDestructiveMigration()
                .build()
    }
}
