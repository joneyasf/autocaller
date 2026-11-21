package com.example.autocaller.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * Observes the phone call state so CallService can tell whether a placed
 * OUTGOING call was answered, rejected, or simply not answered.
 *
 * CRITICAL (v3 fix): for OUTGOING calls, TelephonyManager.CALL_STATE_OFFHOOK
 * means "dialling started / line is active", NOT "the other side answered".
 * RINGING only occurs for INCOMING calls. The v2 code completed every
 * attempt as Answered the instant OFFHOOK fired, so retries 2, 3, 4
 * never rang.
 *
 * Correct detection:
 * - API 31+: TelephonyCallback.PreciseCallStateListener.
 *   CallStateMonitor.PRECISE_ACTIVE (= 1) = the remote side
 *   answered. PRECISE_DIALING (= 3) = ringing out, NOT answered.
 *   PRECISE_DISCONNECTED (= 7) = call ended.
 *   (Values copied from AOSP PreciseCallState, which is @SystemApi
 *   and cannot be referenced from an app.)
 * - API 26-30 (and as a safety net on all APIs): legacy
 *   PhoneStateListener OFFHOOK/RINGING/IDLE. OFFHOOK is recorded only
 *   as "line active", never as answered. The outcome is resolved when
 *   the call ends (IDLE / DISCONNECTED) by querying CallLog: the last
 *   outgoing call to this number in the last 3 minutes with
 *   duration >= 3s = answered, duration == 0 = no answer / rejected.
 *   Without READ_CALL_LOG the timing fallback applies (ended < 5s
 *   after dialling = rejected, ended after >= ringDurationSeconds
 *   = no answer).
 */
class CallStateMonitor(
    private val context: Context,
    private val onPreciseStateChanged: (Int) -> Unit,
    private val onLegacyStateChanged: (Int) -> Unit = {}
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
                val cb = object : TelephonyCallback(),
                    TelephonyCallback.PreciseCallStateListener,
                    TelephonyCallback.CallStateListener {
                    override fun onPreciseCallStateChanged(preciseCallState: Int) {
                        onPreciseStateChanged(preciseCallState)
                    }

                    override fun onCallStateChanged(state: Int) {
                        onLegacyStateChanged(state)
                    }
                }
                callback31 = cb
                tm.registerTelephonyCallback(Executor { it.run() }, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onLegacyStateChanged(state)
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

        /**
         * Precise call-state values, copied from AOSP
         * `android.telephony.PreciseCallState` — that class is @SystemApi,
         * so its constants are NOT in the public SDK and cannot be
         * referenced (neither as PreciseCallState.* nor as
         * TelephonyManager.PRECISE_CALL_STATE_* — the latter does not
         * exist at all). The public TelephonyCallback.PreciseCallStateListener
         * still delivers these ints, so we define the values here:
         * IDLE = 0, ACTIVE = 1 (remote side answered), DIALING = 3
         * (ringing out, NOT answered), DISCONNECTED = 7 (call ended).
         */
        const val PRECISE_IDLE = 0
        const val PRECISE_ACTIVE = 1
        const val PRECISE_DIALING = 3
        const val PRECISE_DISCONNECTED = 7

        /** Duration in seconds of the most recent outgoing call to [number]
         * that started after [sinceMillis], or null if READ_CALL_LOG is not
         * granted, the call log has no matching row yet, or the query fails.
         * Number matching uses the last 9 digits so country-code formatting
         * differences do not break the match. */
        fun lastOutgoingCallDuration(
            context: Context,
            number: String,
            sinceMillis: Long
        ): Long? {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED
            ) return null
            val wanted = number.filter { it.isDigit() }.takeLast(9)
            if (wanted.isEmpty()) return null
            return try {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE),
                    "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ?",
                    arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), sinceMillis.toString()),
                    "${CallLog.Calls.DATE} DESC"
                ) ?: return null
                cursor.use {
                    while (it.moveToNext()) {
                        val logged = it.getString(0).orEmpty().filter { c -> c.isDigit() }.takeLast(9)
                        if (logged == wanted || logged.endsWith(wanted) || wanted.endsWith(logged)) {
                            return it.getLong(1)
                        }
                    }
                    null
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "READ_CALL_LOG not granted", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "CallLog query failed", e)
                null
            }
        }
    }
}
