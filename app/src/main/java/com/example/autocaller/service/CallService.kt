package com.example.autocaller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.autocaller.R
import com.example.autocaller.data.AppDatabase
import com.example.autocaller.data.CallDao
import com.example.autocaller.data.RepeatType
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.scheduler.AlarmScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Places one attempt of a scheduled call, watches the outcome, and either
 * stops or schedules the next retry — all from the foreground service so
 * it survives the app being closed.
 *
 * Outcome rules (v3 — fixed):
 * - PRECISE_CALL_STATE_ACTIVE (API 31+) = the remote side ANSWERED.
 *   OFFHOOK alone NEVER means answered for an outgoing call: it only
 *   means dialling started. v2 completed every attempt as Answered on
 *   OFFHOOK, so retries 2, 3, 4 never rang.
 * - Call ends (DISCONNECTED / IDLE) without ACTIVE: resolve via CallLog
 *   (READ_CALL_LOG): last outgoing call to this number in the last
 *   3 minutes with duration >= 3s = answered, duration == 0 =
 *   no answer / rejected. Without CallLog, timing fallback:
 *   ended < 5s after dialling = rejected, ended after >=
 *   ringDurationSeconds = no answer.
 * - Still ringing when ringDurationSeconds + 10s grace expires:
 *   no answer -> schedule the retry immediately and stop monitoring.
 *   (A normal app cannot hang up, so the retry is queued if the line
 *   is still busy when it fires.)
 * - Another call already in progress when this attempt fires = queue:
 *   delay this attempt by 60s, do NOT count it as an attempt, never
 *   cancel it, never run two calls at once.
 * - A queue-delayed alarm is the SAME attempt delayed, not a new
 *   attempt: it reuses the stored attemptCount and does not increment
 *   it again on the next queue delay. Only an actual dial increments.
 * - Main trigger (!isRetry && !isQueueDelay): attempt 1, counter reset
 *   here in the service at the start of the attempt — never in the
 *   receiver, which raced the retry alarm.
 * - Repeating calls are re-armed for their next occurrence HERE, after
 *   the trigger cycle finishes (answered / rejected / max retries),
 *   never by the receiver before the attempt runs.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_running)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }

        val callId = intent?.getLongExtra(AlarmScheduler.EXTRA_CALL_ID, -1L) ?: -1L
        if (callId < 0) { // sticky restart without data
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val isRetry = intent?.getBooleanExtra(AlarmScheduler.EXTRA_RETRY, false) ?: false
        val isQueueDelay = intent?.getBooleanExtra(AlarmScheduler.EXTRA_QUEUE_DELAY, false) ?: false

        scope.launch {
            try {
                runAttempt(callId, isRetry, isQueueDelay)
            } catch (e: Exception) {
                Log.e(TAG, "Call execution failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private suspend fun runAttempt(callId: Long, isRetry: Boolean, isQueueDelay: Boolean) {
        val dao = AppDatabase.get(applicationContext).callDao()
        var call = dao.getById(callId) ?: return
        if (!call.enabled) return

        // --- Queue: one call at a time. If the line is busy, delay 60s. ---
        // No counter change: the delayed attempt is the same attempt.
        val telephony = applicationContext.getSystemService(TelephonyManager::class.java)
        if (telephony != null && telephony.callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.i(TAG, "Line busy, queueing call ${call.id} for 60s (attempt stays ${call.attemptCount})")
            notify(buildNotification(getString(R.string.notif_queued, call.name)))
            AlarmScheduler.scheduleQueueDelay(applicationContext, call.id)
            return
        }

        // Attempt number. Queue-delay reuses the stored counter (0 -> 1 for
        // the first attempt, otherwise the same number again, no increment).
        // Retry = stored + 1. Main trigger = 1, counter reset here.
        val attemptNumber = when {
            isQueueDelay -> if (call.attemptCount <= 0) 1 else call.attemptCount
            isRetry -> call.attemptCount + 1
            else -> 1
        }
        val attemptStartedAt = System.currentTimeMillis()
        call = call.copy(attemptCount = attemptNumber, lastAttemptMillis = attemptStartedAt)
        dao.update(call)

        val ringSeconds = call.ringDurationSeconds.coerceIn(5, 120)
        val outcome = CompletableDeferred<Outcome>()
        var everActive = false
        var dialStartedAt = -1L
        var endedAt = -1L

        val monitor = CallStateMonitor(
            applicationContext,
            onPreciseStateChanged = { precise ->
                when (precise) {
                    TelephonyManager.PRECISE_CALL_STATE_ACTIVE -> {
                        everActive = true
                        if (!outcome.isCompleted) outcome.complete(Outcome.Answered)
                    }
                    TelephonyManager.PRECISE_CALL_STATE_DIALING -> {
                        if (dialStartedAt < 0) dialStartedAt = System.currentTimeMillis()
                    }
                    TelephonyManager.PRECISE_CALL_STATE_DISCONNECTED -> {
                        if (!outcome.isCompleted) {
                            endedAt = System.currentTimeMillis()
                            outcome.complete(Outcome.Ended)
                        }
                    }
                    // PRECISE_CALL_STATE_RINGING is incoming only; ignore.
                }
            },
            onLegacyStateChanged = { state ->
                when (state) {
                    // OFFHOOK on an outgoing call = dialling / line active.
                    // It is NOT an answer. Record dial start only.
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (dialStartedAt < 0) dialStartedAt = System.currentTimeMillis()
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (!outcome.isCompleted) {
                            endedAt = System.currentTimeMillis()
                            outcome.complete(Outcome.Ended)
                        }
                    }
                    // RINGING is incoming only; ignore.
                }
            }
        )

        notify(buildNotification(getString(R.string.notif_calling, call.name)))
        monitor.start()
        try {
            val placed = CallPlacer.place(applicationContext, call)
            if (!placed) {
                handleOutcome(call, Outcome.NotPlaced, dao)
                return
            }
            if (dialStartedAt < 0) dialStartedAt = attemptStartedAt
            // Observe for the user-set ring duration (+10s grace for the
            // dialer / telecom to report state changes). ACTIVE completes
            // early; a timeout with the line still ringing = no answer.
            val raw = withTimeoutOrNull(ringSeconds * 1000L + 10_000L) {
                outcome.await()
            } ?: Outcome.Timeout
            val resolved = resolveOutcome(raw, call, dialStartedAt, endedAt, everActive)
            handleOutcome(call.copy(attemptCount = attemptNumber), resolved, dao)
        } finally {
            monitor.stop()
        }
    }

    /**
     * Turns a raw end-of-call signal into Answered / Rejected / NoAnswer.
     * CallLog is the authority when available; timing is the fallback.
     * The 5s rejected threshold applies only here, never to OFFHOOK.
     */
    private fun resolveOutcome(
        raw: Outcome,
        call: ScheduledCall,
        dialStartedAt: Long,
        endedAt: Long,
        everActive: Boolean
    ): Outcome {
        if (raw == Outcome.Answered || everActive) return Outcome.Answered
        if (raw == Outcome.NotPlaced) return Outcome.NotPlaced
        if (raw == Outcome.Timeout) return Outcome.NoAnswer
        // raw == Ended: the call finished without ACTIVE.
        val dialStart = if (dialStartedAt > 0) dialStartedAt else call.lastAttemptMillis
        val duration = CallStateMonitor.lastOutgoingCallDuration(
            applicationContext, call.number, dialStart - 5_000L
        )
        if (duration != null) {
            return if (duration >= ANSWERED_CALL_LOG_MIN_SECONDS) Outcome.Answered
            else if (endedAt > 0 && endedAt - dialStart < REJECTED_RING_THRESHOLD_MS) Outcome.Rejected
            else Outcome.NoAnswer
        }
        // No CallLog (permission missing or no row yet): timing fallback.
        if (endedAt > 0 && endedAt - dialStart < REJECTED_RING_THRESHOLD_MS) return Outcome.Rejected
        return Outcome.NoAnswer
    }

    private suspend fun handleOutcome(
        call: ScheduledCall,
        outcome: Outcome,
        dao: CallDao
    ) {
        when (outcome) {
            Outcome.Answered, Outcome.Rejected -> {
                // Cycle done: reset the counter. Re-arm a repeating call
                // for its next occurrence here, after the cycle finished.
                val finished = if (call.repeat == RepeatType.ONCE) {
                    call.copy(enabled = false, attemptCount = 0)
                } else {
                    call.copy(attemptCount = 0)
                }
                dao.update(finished)
                if (call.repeat != RepeatType.ONCE && finished.enabled) {
                    AlarmScheduler.schedule(applicationContext, finished)
                }
                notify(buildNotification(getString(R.string.notif_running)))
            }
            Outcome.NoAnswer, Outcome.Timeout, Outcome.NotPlaced -> {
                if (call.retryEnabled && call.attemptCount < call.maxRetries.coerceAtLeast(1)) {
                    Log.i(TAG, "No answer for ${call.name}, retry ${call.attemptCount}/${call.maxRetries}")
                    notify(
                        buildNotification(
                            getString(R.string.notif_retry, call.name, call.attemptCount, call.maxRetries)
                        )
                    )
                    AlarmScheduler.scheduleRetry(
                        applicationContext, call, call.retryIntervalMinutes.coerceAtLeast(1)
                    )
                    // attemptCount stays: the retry alarm resumes the cycle.
                } else {
                    // Max attempts reached (or retries off): cycle done.
                    val finished = if (call.repeat == RepeatType.ONCE) {
                        call.copy(enabled = false, attemptCount = 0)
                    } else {
                        call.copy(attemptCount = 0)
                    }
                    dao.update(finished)
                    if (call.repeat != RepeatType.ONCE && finished.enabled) {
                        AlarmScheduler.schedule(applicationContext, finished)
                    }
                    showFailure(call)
                }
            }
            Outcome.Ended -> {
                // Should have been resolved before handleOutcome; treat as no answer.
                handleOutcome(call, Outcome.NoAnswer, dao)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    private fun notify(notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }

    private fun showFailure(call: ScheduledCall) {
        val dial = PendingIntent.getActivity(
            this, call.id.toInt(),
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", call.number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(getString(R.string.notif_failed, call.name))
            .setContentText(getString(R.string.notif_failed_hint))
            .setContentIntent(dial)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(FAILURE_ID_BASE + call.id.toInt(), n)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }

    private enum class Outcome { Answered, Rejected, NoAnswer, Timeout, NotPlaced, Ended }

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "calls_channel"
        private const val NOTIFICATION_ID = 1001
        private const val FAILURE_ID_BASE = 2000
        /** Call ended sooner than this after dialling = "rejected" (fallback only). */
        private const val REJECTED_RING_THRESHOLD_MS = 5_000L
        /** CallLog duration (seconds) that counts as answered. */
        private const val ANSWERED_CALL_LOG_MIN_SECONDS = 3L
    }
}
