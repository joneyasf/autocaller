package com.example.autocaller.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.autocaller.activation.ActivationManager
import com.example.autocaller.ui.theme.AutoCallerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v1.5: retry the one-time activation report if it hasn't gone
        // through yet (e.g. first launch was offline).
        val initiallyActivated = ActivationManager.isActivated(this)
        if (initiallyActivated) ActivationManager.reportIfNeeded(this)
        viewModel.rescheduleAll() // safety net on every launch
        setContent {
            AutoCallerTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    // v1.5: first-launch activation gate. Activated devices
                    // never see the activation screen again.
                    var activated by remember { mutableStateOf(initiallyActivated) }
                    if (activated) {
                        MainScreen(viewModel)
                    } else {
                        ActivationScreen(onActivated = { activated = true })
                    }
                }
            }
        }
    }
}
