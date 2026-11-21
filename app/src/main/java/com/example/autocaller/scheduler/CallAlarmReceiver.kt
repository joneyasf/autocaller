package com.example.autocaller.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.autocaller.data.AppDatabase
import com.example.autocaller.service.CallPlacer
import com.example.autocaller.service.CallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Thin trigger only (v3 fix).
 *
 * v2 had an async coroutine here that reset attemptCount to 0 and
 * re-armed the repeating schedule. That raced CallService: the receiver
 * could wipe the retry alarm / attempt counter after CallService had
 * already incremented it, so retries 2, 3, 4 never rang.
 *
 * The receiver now ONLY starts CallService with the callId / isRetry /
 * isQueueDelay extras. All counter resets and re-arming of the next
 * repeating occurrence happen inside CallService, after the trigger
 * cycle finishes. No database writes here except the fallback path.
 */
class CallAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getLongExtra(AlarmScheduler.EXTRA_CALL_ID, -1L)
        if (callId < 0) return
        val isRetry = intent.getBooleanExtra(AlarmScheduler.EXTRA_RETRY, false)
        val isQueueDelay = intent.getBooleanExtra(AlarmScheduler.EXTRA_QUEUE_DELAY, false)
        val appContext = context.applicationContext

        var serviceStarted = true
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, CallService::class.java)
                    .putExtra(AlarmScheduler.EXTRA_CALL_ID, callId)
                    .putExtra(AlarmScheduler.EXTRA_RETRY, isRetry)
                    .putExtra(AlarmScheduler.EXTRA_QUEUE_DELAY, isQueueDelay)
            )
        } catch (e: Exception) {
            Log.e("CallAlarmReceiver", "Could not start CallService", e)
            serviceStarted = false
        }

        if (!serviceStarted) {
            // Fallback only: place the call from here. No counter reset,
            // no re-arming — CallService / BootReceiver / app launch will
            // re-arm the schedule; touching the counter here would race
            // the service on a later start.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val dao = AppDatabase.get(appContext).callDao()
                    val call = dao.getById(callId)
                    if (call != null && call.enabled) {
                        CallPlacer.place(appContext, call)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
