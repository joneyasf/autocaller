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

    /** Distinct Intent actions so main / retry / queue PendingIntents
     * can never match each other. Android ignores extras when matching
     * PendingIntents, so extras alone do NOT separate them — the action
     * (and request code) must differ. v2 shared both and one alarm
     * overwrote the other. */
    const val ACTION_MAIN = "com.example.autocaller.ACTION_MAIN"
    const val ACTION_RETRY = "com.example.autocaller.ACTION_RETRY"
    const val ACTION_QUEUE = "com.example.autocaller.ACTION_QUEUE"

    /** Distinct request codes: MAIN = callId, RETRY = +1,000,000,
     * QUEUE = +2,000,000. */
    private const val RETRY_CODE_OFFSET = 1_000_000
    private const val QUEUE_CODE_OFFSET = 2_000_000

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

    /** Schedules (or cancels, if disabled/expired) the main alarm of one call.
     * Cancels ONLY the main alarm — never the retry or queue-delay alarm
     * (v2 cancelled the retry here, racing CallService). */
    fun schedule(context: Context, call: ScheduledCall): Boolean {
        cancelMain(context, call.id)
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

    /** Cancel only the main alarm of a call. */
    fun cancelMain(context: Context, callId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = operationIntent(context, callId, isRetry = false, isQueueDelay = false)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** Cancel only the retry alarm of a call. */
    fun cancelRetry(context: Context, callId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = operationIntent(context, callId, isRetry = true, isQueueDelay = false)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** Cancel only the queue-delay alarm of a call. */
    fun cancelQueue(context: Context, callId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = operationIntent(context, callId, isRetry = false, isQueueDelay = true)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    /** Cancel all three alarms of a call. Use only when the user disables
     * or deletes a call. */
    fun cancelAll(context: Context, callId: Long) {
        cancelMain(context, callId)
        cancelRetry(context, callId)
        cancelQueue(context, callId)
    }

    /** Backwards-compatible: cancelling a call cancels all its alarms. */
    fun cancel(context: Context, callId: Long) = cancelAll(context, callId)

    suspend fun rescheduleAll(context: Context) {
        AppDatabase.get(context).callDao().getEnabled().forEach { schedule(context, it) }
    }

    /**
     * Overdue calls: an enabled call whose trigger time already passed
     * (previous call still running, phone off, alarm missed) and which
     * has not started a trigger cycle yet (attemptCount == 0,
     * lastAttemptMillis == 0) is scheduled 1 minute from now as a
     * queue-delay one-off instead of being dropped.
     * Note: ChronoUnit.between() returns Long, so range checks here
     * must use Long ranges (0L..24L), not Int ranges.
     * - One-time calls whose time passed (nextTriggerMillis == null)
     *   within the last 24h are re-fired once.
     * - Repeating calls whose *today's* occurrence passed within the
     *   last 60 minutes are re-fired once; older misses wait for the
     *   next scheduled occurrence.
     * Idempotent: once the delayed attempt dials, attemptCount becomes
     * 1, so a later rescheduleOverdue() call skips it.
     */
    suspend fun rescheduleOverdue(context: Context) {
        val dao = AppDatabase.get(context).callDao()
        val now = LocalDateTime.now()
        val nowMillis = System.currentTimeMillis()
        dao.getEnabled().forEach { call ->
            if (call.attemptCount != 0 || call.lastAttemptMillis != 0L) return@forEach
            val time = LocalTime.of(call.hour, call.minute)
            val startDate = LocalDate.ofEpochDay(call.dateEpochDay)
            val start = LocalDateTime.of(startDate, time)
            val next = nextTriggerMillis(call, now)
            if (next == null) {
                // Expired one-time call, missed within the last 24h.
                if (ChronoUnit.HOURS.between(start, now) in 0L..24L) {
                    Log.i(TAG, "Overdue one-time call ${call.id}, firing in 1 min")
                    scheduleQueueDelay(context, call.id)
                }
            } else {
                val todayTrigger = LocalDateTime.of(now.toLocalDate(), time)
                if (!todayTrigger.toLocalDate().isBefore(startDate) &&
                    todayTrigger.isBefore(now) &&
                    ChronoUnit.MINUTES.between(todayTrigger, now) <= 60 &&
                    next - nowMillis > 60 * 60_000L
                ) {
                    Log.i(TAG, "Overdue repeating call ${call.id}, firing in 1 min")
                    scheduleQueueDelay(context, call.id)
                }
            }
        }
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
        val (action, requestCode) = when {
            isRetry -> ACTION_RETRY to (callId + RETRY_CODE_OFFSET).toInt()
            isQueueDelay -> ACTION_QUEUE to (callId + QUEUE_CODE_OFFSET).toInt()
            else -> ACTION_MAIN to callId.toInt()
        }
        val intent = Intent(context, CallAlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CALL_ID, callId)
            .putExtra(EXTRA_RETRY, isRetry)
            .putExtra(EXTRA_QUEUE_DELAY, isQueueDelay)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
