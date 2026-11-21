package com.example.autocaller.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * One physical SIM as the user sees it.
 * subscriptionId is the stable key saved per scheduled call.
 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String
) {
    /** "الشريحة 1 - Vodafone" / "SIM 1" when no carrier name is available. */
    val label: String
        get() = if (carrierName.isNotBlank()) "SIM ${slotIndex + 1} - $carrierName"
        else "SIM ${slotIndex + 1}"
}

object SimHelper {
    private const val TAG = "SimHelper"
    const val SIM_DEFAULT = -1

    /**
     * Active SIMs, ordered by slot. Returns a single synthetic "default"
     * entry when READ_PHONE_STATE is missing or no SIM info is available,
     * so the UI never breaks on single-SIM phones.
     */
    fun getSims(context: Context): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return listOf(SimInfo(SIM_DEFAULT, 0, "الافتراضية", ""))
        }
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val list = manager?.activeSubscriptionInfoList.orEmpty()
            if (list.isEmpty()) {
                listOf(SimInfo(SIM_DEFAULT, 0, "الافتراضية", ""))
            } else {
                list.map {
                    SimInfo(
                        subscriptionId = it.subscriptionId,
                        slotIndex = it.simSlotIndex,
                        displayName = it.displayName?.toString().orEmpty(),
                        carrierName = it.carrierName?.toString().orEmpty()
                    )
                }.sortedBy { it.slotIndex }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_PHONE_STATE not granted", e)
            listOf(SimInfo(SIM_DEFAULT, 0, "الافتراضية", ""))
        } catch (e: Exception) {
            Log.w(TAG, "Could not list SIMs", e)
            listOf(SimInfo(SIM_DEFAULT, 0, "الافتراضية", ""))
        }
    }

    /** Human label for a saved subscriptionId, or "الافتراضية" when unknown. */
    fun labelFor(context: Context, subscriptionId: Int): String {
        if (subscriptionId == SIM_DEFAULT) return "الافتراضية"
        return getSims(context).firstOrNull { it.subscriptionId == subscriptionId }?.label
            ?: "SIM $subscriptionId"
    }

    /**
     * Find the PhoneAccountHandle for a subscriptionId.
     * Tries, in order: handle id == subscriptionId, handle id == slot index,
     * then slot-order fallback (handles sorted by id, pick slotIndex-th).
     * Returns null when nothing matches — the caller must fall back to
     * the system default account / ACTION_CALL.
     */
    fun phoneAccountHandle(context: Context, subscriptionId: Int): PhoneAccountHandle? {
        if (subscriptionId == SIM_DEFAULT) return null
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
            val handles = telecom.callCapablePhoneAccounts.orEmpty()
            if (handles.isEmpty()) return null
            handles.firstOrNull { it.id == subscriptionId.toString() }
                ?: handles.firstOrNull { it.id == simSlotIndex(context, subscriptionId).toString() }
                ?: run {
                    val slot = simSlotIndex(context, subscriptionId)
                    val sorted = handles.sortedBy { it.id }
                    if (slot in sorted.indices) sorted[slot] else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve PhoneAccountHandle", e)
            null
        }
    }

    private fun simSlotIndex(context: Context, subscriptionId: Int): Int =
        getSims(context).firstOrNull { it.subscriptionId == subscriptionId }?.slotIndex ?: 0
}
