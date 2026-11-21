package com.example.autocaller.util

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val hasCall: Boolean,
    val hasPhoneState: Boolean,
    val canExact: Boolean,
    val hasNotif: Boolean
) {
    val allGranted: Boolean get() = hasCall && hasPhoneState && canExact && hasNotif

    fun missingRuntime(): Array<String> = buildList {
        if (!hasCall) add(Manifest.permission.CALL_PHONE)
        if (!hasPhoneState) add(Manifest.permission.READ_PHONE_STATE)
        if (!hasNotif) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    companion object {
        fun read(context: Context): PermissionStatus {
            val hasCall = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            // Needed to list SIMs and to tell answered / rejected / no-answer apart.
            val hasPhoneState = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else true

            val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true

            return PermissionStatus(hasCall, hasPhoneState, canExact, hasNotif)
        }

        fun appSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))

        fun exactAlarmIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${context.packageName}"))
            } else appSettingsIntent(context)
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
