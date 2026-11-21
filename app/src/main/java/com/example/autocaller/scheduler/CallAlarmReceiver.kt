package com.example.autocaller.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.autocaller.data.AppDatabase
import com.example.autocaller.data.RepeatType
import com.example.autocaller.service.CallPlacer
import com.example.autocaller.service.CallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(appContext).callDao()
                val call = dao.getById(callId) ?: return@launch
                // Main trigger only: re-arm the next occurrence right away
                // for repeating calls. Retries and queue-delays must not
                // re-arm the repeating schedule.
                if (!isRetry && !isQueueDelay && call.enabled && call.repeat != RepeatType.ONCE) {
                    AlarmScheduler.schedule(appContext, call)
                }
                // Main trigger only: a new trigger cycle starts, so the
                // retry counter resets. Retries keep their counter.
                if (!isRetry && !isQueueDelay) {
                    dao.update(call.copy(attemptCount = 0))
                }
                // Fallback: service could not start, place the call from here.
                if (!serviceStarted && call.enabled) {
                    CallPlacer.place(appContext, call)
                    if (call.repeat == RepeatType.ONCE) dao.update(call.copy(enabled = false, attemptCount = 0))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
