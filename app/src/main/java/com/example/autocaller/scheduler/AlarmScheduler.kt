package com.example.autocaller.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.autocaller.data.AppDatabase
import com.example.autocaller.data.RepeatType
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object AlarmScheduler {
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_RETRY = "is_retry"
    const val EXTRA_QUEUE_DELAY = "is_queue_delay"
    private const val TAG = "AlarmScheduler"

    /** Request-code offset for retry / queue-delay alarms so they never
     * collide with (or cancel) the main alarm of the same call. */
    private const val RETRY_CODE_OFFSET = 1_000_000

    /** Next trigger time in epoch millis, or null if there is none (expired one-time call). */
    fun nextTriggerMillis(call: ScheduledCall, now: LocalDateTime = LocalDateTime.now()): Long? {
        val time = LocalTime.of(call.hour, call.minute)
        val startDate = LocalDate.ofEpochDay(call.dateEpochDay)
        val start = LocalDateTime.of(startDate, time)

        val next: LocalDateTime? = when {
            start.isAfter(now) -> start
            call.repeat == RepeatType.ONCE -> null
            call.repeat == RepeatType.DAILY -> {
                val today = LocalDateTime.of(now.toLocalDate(), time)
                if (today.isAfter(now)) today else today.plusDays(1)
            }
            else -> { // WEEKLY: same weekday as the start date
                val days = ChronoUnit.DAYS.between(startDate, now.toLocalDate())
                var candidate = LocalDateTime.of(startDate.plusDays(days / 7 * 7), time)
                while (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
                candidate
            }
        }
        return next?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    /** Schedules (or cancels, if disabled/expired) the alarm of one call. */
    fun schedule(context: Context, call: ScheduledCall): Boolean {
        cancel(context, call.id)
        if (!call.enabled) return false
        val triggerAt = nextTriggerMillis(call) ?: return false

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val showIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            // setAlarmClock => fires exactly, wakes the device and bypasses Doze.
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                operationIntent(context, call.id, isRetry = false, isQueueDelay = false)
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot schedule exact alarm", e)
            false
        }
    }

    /**
     * Schedule one retry attempt for [call] after [delayMinutes] (user-set).
     * Uses setAlarmClock too, so a retry fires exactly even if the app was
     * killed or the phone is dozing. The retry does not disturb the main
     * alarm and does not re-arm the repeating schedule.
     */
    fun scheduleRetry(context: Context, call: ScheduledCall, delayMinutes: Int): Boolean {
        val triggerAt = System.currentTimeMillis() + delayMinutes.coerceAtLeast(1) * 60_000L
        return scheduleOneOff(context, call.id, triggerAt, isRetry = true, isQueueDelay = false)
    }

    /**
     * Queue delay: another call is already in progress, so this attempt is
     * postponed by 60 seconds instead of being cancelled. Its attempt
     * counter is NOT incremented — the attempt simply runs a minute later.
     */
    fun scheduleQueueDelay(context: Context, callId: Long): Boolean {
        val triggerAt = System.currentTimeMillis() + 60_000L
        return scheduleOneOff(context, callId, triggerAt, isRetry = false, isQueueDelay = true)
    }

    private fun scheduleOneOff(
        context: Context,
        callId: Long,
        triggerAt: Long,
        isRetry: Boolean,
        isQueueDelay: Boolean
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val showIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                operationIntent(context, callId, isRetry, isQueueDelay)
            )
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot schedule one-off alarm", e)
            false
        }
    }

    fun cancel(context: Context, callId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = operationIntent(context, callId, isRetry = false, isQueueDelay = false)
        alarmManager.cancel(pi)
        pi.cancel()
        val retryPi = operationIntent(context, callId, isRetry = true, isQueueDelay = false)
        alarmManager.cancel(retryPi)
        retryPi.cancel()
    }

    suspend fun rescheduleAll(context: Context) {
        AppDatabase.get(context).callDao().getEnabled().forEach { schedule(context, it) }
    }

    /**
     * After a reboot, re-arm any retry that was pending when the phone
     * restarted. A retry whose time already passed fires 1 minute from now
     * instead of being lost. Main alarms are re-armed by rescheduleAll().
     */
    suspend fun reschedulePendingRetries(context: Context) {
        val dao = AppDatabase.get(context).callDao()
        val now = System.currentTimeMillis()
        dao.getPendingRetries().forEach { call ->
            val dueAt = call.lastAttemptMillis + call.retryIntervalMinutes.coerceAtLeast(1) * 60_000L
            val delayMinutes = if (dueAt > now) {
                ((dueAt - now) / 60_000L).toInt().coerceAtLeast(1)
            } else 1
            scheduleRetry(context, call, delayMinutes)
        }
    }

    private fun operationIntent(
        context: Context,
        callId: Long,
        isRetry: Boolean,
        isQueueDelay: Boolean
    ): PendingIntent {
        val intent = Intent(context, CallAlarmReceiver::class.java)
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_RETRY, isRetry)
            .putExtra(EXTRA_QUEUE_DELAY, isQueueDelay)
        val requestCode = if (isRetry || isQueueDelay) {
            (callId + RETRY_CODE_OFFSET).toInt()
        } else {
            callId.toInt()
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
