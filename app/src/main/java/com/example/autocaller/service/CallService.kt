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
 * Outcome rules (per scheduled call, each with its own retry counter):
 * - OFFHOOK at any point = answered -> stop retries, cycle done.
 * - IDLE after ringing < 5s and never OFFHOOK = rejected -> stop retries.
 * - IDLE after ringing >= ringDurationSeconds, or still ringing when the
 *   observation window ends = no answer -> retry after the user-set
 *   retryIntervalMinutes, until maxRetries attempts are done.
 * - Another call already in progress (OFFHOOK/RINGING) when this attempt
 *   fires = queue: delay this attempt by 60s, do NOT count it as an
 *   attempt, never cancel it, never run two calls at once.
 *
 * Known limit: Android does not let a normal app end a call, so if the
 * other side is still ringing when the ring-duration window ends we
 * cannot hang up for them — we schedule the retry and stop watching.
 * If they answer after that window, the already-scheduled retry may
 * still fire once; the next attempt then sees the line busy and queues.
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

        scope.launch {
            try {
                runAttempt(callId, isRetry)
            } catch (e: Exception) {
                Log.e(TAG, "Call execution failed", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private suspend fun runAttempt(callId: Long, isRetry: Boolean) {
        val dao = AppDatabase.get(applicationContext).callDao()
        var call = dao.getById(callId) ?: return
        if (!call.enabled) return

        // --- Queue: one call at a time. If the line is busy, delay 60s. ---
        val telephony = applicationContext.getSystemService(TelephonyManager::class.java)
        if (telephony != null && telephony.callState != TelephonyManager.CALL_STATE_IDLE) {
            Log.i(TAG, "Line busy, queueing call ${call.id} for 60s")
            notify(buildNotification(getString(R.string.notif_queued, call.name)))
            AlarmScheduler.scheduleQueueDelay(applicationContext, call.id)
            return
        }

        // Attempt number for this trigger cycle (receiver already reset it
        // to 0 on a main trigger; retries keep counting).
        val baseAttempts = if (isRetry) call.attemptCount else 0
        val attemptNumber = baseAttempts + 1
        val attemptStartedAt = System.currentTimeMillis()
        call = call.copy(attemptCount = attemptNumber, lastAttemptMillis = attemptStartedAt)
        dao.update(call)

        val ringSeconds = call.ringDurationSeconds.coerceIn(5, 120)
        val outcome = CompletableDeferred<Outcome>()
        var firstRingingAt = -1L
        var everOffhook = false

        val monitor = CallStateMonitor(applicationContext) { state ->
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    everOffhook = true
                    if (!outcome.isCompleted) outcome.complete(Outcome.Answered)
                }
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (firstRingingAt < 0) firstRingingAt = System.currentTimeMillis()
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (firstRingingAt >= 0 && !everOffhook && !outcome.isCompleted) {
                        val rangFor = System.currentTimeMillis() - firstRingingAt
                        // Rejected quickly (< 5s of ringing) vs rang out.
                        if (rangFor < REJECTED_RING_THRESHOLD_MS) {
                            outcome.complete(Outcome.Rejected)
                        } else {
                            outcome.complete(Outcome.NoAnswer)
                        }
                    }
                }
            }
        }

        notify(buildNotification(getString(R.string.notif_calling, call.name)))
        monitor.start()
        try {
            val placed = CallPlacer.place(applicationContext, call)
            if (!placed) {
                showFailure(call)
                handleOutcome(call, Outcome.NotPlaced, dao)
                return
            }
            // Observe for the user-set ring duration (+5s grace for the
            // dialer to report the state change). OFFHOOK completes early.
            val result = withTimeoutOrNull(ringSeconds * 1000L + 5_000L) {
                outcome.await()
            } ?: if (firstRingingAt >= 0) Outcome.NoAnswer else Outcome.Timeout
            handleOutcome(call.copy(attemptCount = attemptNumber), result, dao)
        } finally {
            monitor.stop()
        }
    }

    private suspend fun handleOutcome(
        call: ScheduledCall,
        outcome: Outcome,
        dao: com.example.autocaller.data.CallDao
    ) {
        when (outcome) {
            Outcome.Answered, Outcome.Rejected -> {
                // Cycle done: reset the counter. A repeating call stays
                // enabled for its next scheduled occurrence; a one-time
                // call is finished.
                val finished = if (call.repeat == RepeatType.ONCE) call.copy(enabled = false, attemptCount = 0)
                else call.copy(attemptCount = 0)
                dao.update(finished)
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
                    val finished = if (call.repeat == RepeatType.ONCE) call.copy(enabled = false, attemptCount = 0)
                    else call.copy(attemptCount = 0)
                    dao.update(finished)
                    showFailure(call)
                }
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

    private enum class Outcome { Answered, Rejected, NoAnswer, Timeout, NotPlaced }

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "calls_channel"
        private const val NOTIFICATION_ID = 1001
        private const val FAILURE_ID_BASE = 2000
        /** Ringing shorter than this without OFFHOOK counts as "rejected". */
        private const val REJECTED_RING_THRESHOLD_MS = 5_000L
    }
}
