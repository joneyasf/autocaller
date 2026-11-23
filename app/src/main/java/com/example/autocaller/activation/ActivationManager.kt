package com.example.autocaller.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * v1.5: first-launch activation with a single shared key.
 *
 * The key check is LOCAL (hardcoded) so the app unlocks even offline.
 * Firebase is only used for counting how many devices activated the app:
 * one document per device in the "activations" collection, document ID =
 * ANDROID_ID, so the same device re-activating overwrites its own document
 * instead of double-counting. No name or phone number is collected.
 */
object ActivationManager {

    /** The exact activation key chosen by the user. */
    const val KEY = "صلى الله عليه وسلم"

    const val APP_VERSION = "1.5"

    private const val PREFS = "activation"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_REPORTED = "reported"

    /**
     * Normalises Arabic text for comparison: trims whitespace, strips
     * tashkeel (diacritics U+064B–U+0652, U+0670) and tatweel (U+0640),
     * and collapses repeated whitespace to a single space — so a
     * diacritised typing of the same words also matches.
     */
    fun normalize(text: String): String {
        return text
            .replace(Regex("[\\u064B-\\u0652\\u0670\\u0640]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun isCorrectKey(input: String): Boolean {
        return normalize(input) == normalize(KEY)
    }

    fun isActivated(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVATED, false)
    }

    fun setActivated(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVATED, true).apply()
    }

    private fun isReported(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REPORTED, false)
    }

    private fun setReported(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REPORTED, true).apply()
    }

    /**
     * Writes this device's activation record to Firestore, once.
     * Safe to call on every launch: if the device is not activated yet,
     * or already reported, or offline, or Firebase is still the placeholder
     * config, it does nothing and the next launch retries.
     */
    fun reportIfNeeded(context: Context) {
        if (!isActivated(context) || isReported(context)) return
        val androidId = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            null
        }
        if (androidId.isNullOrBlank()) return
        try {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener {
                    val data = hashMapOf<String, Any>(
                        "model" to (Build.MODEL ?: ""),
                        "brand" to (Build.BRAND ?: ""),
                        "androidVersion" to (Build.VERSION.RELEASE ?: ""),
                        "appVersion" to APP_VERSION,
                        "activatedAt" to FieldValue.serverTimestamp()
                    )
                    FirebaseFirestore.getInstance()
                        .collection("activations")
                        .document(androidId)
                        .set(data, SetOptions.merge())
                        .addOnSuccessListener { setReported(context) }
                        // On failure (offline / placeholder config): keep
                        // reported=false so the next launch retries.
                }
        } catch (e: Exception) {
            // Firebase not configured yet (placeholder google-services.json)
            // or unavailable: ignore, retry on next launch.
        }
    }
}
