package com.example.autocaller.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-schedules every enabled call after reboot, app update, clock/timezone change
 * or when the exact-alarm permission state changes.
 * (Does not start the foreground service: Android 15 forbids phoneCall FGS from BOOT_COMPLETED.)
 * Also re-arms any retry that was pending when the phone restarted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(appContext)
                AlarmScheduler.reschedulePendingRetries(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}
