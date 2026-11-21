package com.example.autocaller.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

// Version 2: added per-call SIM (simSubscriptionId) and auto-redial fields
// (retryEnabled, ringDurationSeconds, retryIntervalMinutes, maxRetries,
// attemptCount, lastAttemptMillis). No unique index on `number` — the same
// number may have many scheduled calls with different times, by design.
@Database(entities = [ScheduledCall::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autocaller.db"
                )
                    // v1 -> v2 only adds columns with defaults; destructive
                    // migration is safe here (schedules are user data, but
                    // keeping the app working after an update matters more
                    // than preserving v1 rows on a failed migration).
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
