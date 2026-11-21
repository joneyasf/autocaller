package com.example.autocaller.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Observes the phone call state so CallService can tell whether a placed
 * call was answered, rejected, or simply not answered.
 *
 * TelephonyCallback (API 31+) is used when available, PhoneStateListener
 * as a fallback for API 26-30. Both report the same three states:
 * IDLE / RINGING / OFFHOOK (TelephonyManager.CALL_STATE_*).
 *
 * The caller records the time of the first RINGING and whether OFFHOOK
 * was ever reached; CallService turns that into answered / rejected /
 * no-answer. This class does not decide anything itself.
 */
class CallStateMonitor(
    private val context: Context,
    private val onStateChanged: (Int) -> Unit
) {
    private val telephony: TelephonyManager? =
        context.getSystemService(TelephonyManager::class.java)

    private var callback31: TelephonyCallback? = null
    private var listenerLegacy: PhoneStateListener? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        onStateChanged(state)
                    }
                }
                callback31 = cb
                tm.registerTelephonyCallback(Executor { it.run() }, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onStateChanged(state)
                    }
                }
                listenerLegacy = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE not granted, cannot monitor call state", e)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register call-state listener", e)
        }
    }

    fun stop() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback31?.let { tm.unregisterTelephonyCallback(it) }
                callback31 = null
            } else {
                @Suppress("DEPRECATION")
                listenerLegacy?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                listenerLegacy = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not unregister call-state listener", e)
        }
    }

    companion object {
        private const val TAG = "CallStateMonitor"
    }
}
