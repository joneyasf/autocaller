package com.example.autocaller.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Query("SELECT * FROM scheduled_calls")
    fun observeAll(): Flow<List<ScheduledCall>>

    @Query("SELECT * FROM scheduled_calls WHERE id = :id")
    suspend fun getById(id: Long): ScheduledCall?

    @Query("SELECT * FROM scheduled_calls WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledCall>

    /**
     * Calls mid-retry-cycle (an attempt was made, max not reached yet).
     * Used by BootReceiver to re-arm a pending retry after a reboot.
     */
    @Query(
        "SELECT * FROM scheduled_calls WHERE enabled = 1 " +
            "AND retryEnabled = 1 AND attemptCount > 0 AND attemptCount < maxRetries"
    )
    suspend fun getPendingRetries(): List<ScheduledCall>

    @Insert
    suspend fun insert(call: ScheduledCall): Long

    @Update
    suspend fun update(call: ScheduledCall)

    @Delete
    suspend fun delete(call: ScheduledCall)
}
