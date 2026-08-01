package com.hub.media.core.database

import androidx.room.Room
import androidx.room.RoomDatabase

internal actual fun inMemoryAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    Room.inMemoryDatabaseBuilder<AppDatabase>(factory = AppDatabaseConstructor::initialize)
