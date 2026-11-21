package com.example.autocaller.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.util.SimHelper

object CallPlacer {
    private const val TAG = "CallPlacer"

    /**
     * false = ACTION_CALL first, TelecomManager.placeCall as fallback (as requested).
     * Android 10+ may silently block activity launches from the background.
     * If calls don't start with the screen off, set this to true.
     */
    private const val TELECOM_FIRST = false

    /** Place a call for a full scheduled call (uses its saved SIM). */
    fun place(context: Context, call: ScheduledCall): Boolean =
        place(context, call.number, call.simSubscriptionId)

    /**
     * Place a call from the SIM saved for this call.
     * The SIM is passed to the system via EXTRA_PHONE_ACCOUNT_HANDLE so the
     * system SIM chooser is never shown. When no matching account exists
     * (single SIM, unknown subscription, old device) we fall back to the
     * system default account — the call still goes out.
     */
    fun place(context: Context, number: String, simSubscriptionId: Int = SimHelper.SIM_DEFAULT): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        val uri = Uri.fromParts("tel", number, null)
        val handle = if (simSubscriptionId != SimHelper.SIM_DEFAULT) {
            SimHelper.phoneAccountHandle(context, simSubscriptionId)
        } else null

        var ok = if (TELECOM_FIRST) viaTelecom(context, uri, handle) else viaIntent(context, uri, handle)
        if (!ok) ok = if (TELECOM_FIRST) viaIntent(context, uri, handle) else viaTelecom(context, uri, handle)
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun viaIntent(
        context: Context,
        uri: Uri,
        handle: android.telecom.PhoneAccountHandle?
    ): Boolean = try {
        val intent = Intent(Intent.ACTION_CALL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (handle != null) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "ACTION_CALL failed", e)
        false
    }

    @SuppressLint("MissingPermission")
    private fun viaTelecom(
        context: Context,
        uri: Uri,
        handle: android.telecom.PhoneAccountHandle?
    ): Boolean = try {
        val extras = Bundle()
        if (handle != null) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        context.getSystemService(TelecomManager::class.java).placeCall(uri, extras)
        true
    } catch (e: Exception) {
        Log.w(TAG, "TelecomManager.placeCall failed", e)
        false
    }
}
