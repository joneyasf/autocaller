package com.example.autocaller.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class RepeatType { ONCE, DAILY, WEEKLY }

@Entity(tableName = "scheduled_calls")
data class ScheduledCall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val number: String,
    /** Start date as LocalDate.toEpochDay() */
    val dateEpochDay: Long,
    val hour: Int,
    val minute: Int,
    val repeat: RepeatType = RepeatType.ONCE,
    val enabled: Boolean = true,
    /**
     * SIM to call from, as SubscriptionManager subscriptionId.
     * -1 = system default (first SIM when only one SIM is present).
     * Saved per call so each scheduled call can use a different SIM.
     */
    val simSubscriptionId: Int = -1,
    /** Auto-redial: re-dial when there is no answer. User-configurable, never hardcoded. */
    val retryEnabled: Boolean = true,
    /** How long (seconds) the phone rings on each attempt, set by the user. Default 30. */
    val ringDurationSeconds: Int = 30,
    /** Minutes between one attempt and the next, set by the user. Default 5. */
    val retryIntervalMinutes: Int = 5,
    /** Max attempts for one trigger (including the first attempt), set by the user. Default 3. */
    val maxRetries: Int = 3,
    /** Attempts already made for the current trigger cycle. Reset to 0 on a new trigger / answer / reject. */
    val attemptCount: Int = 0,
    /** Epoch millis of the last attempt start, used to re-arm a pending retry after reboot. */
    val lastAttemptMillis: Long = 0L
)

class Converters {
    @TypeConverter
    fun fromRepeat(value: RepeatType): String = value.name

    @TypeConverter
    fun toRepeat(value: String): RepeatType = RepeatType.valueOf(value)
}
