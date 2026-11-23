package com.example.autocaller.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.autocaller.R
import com.example.autocaller.activation.ActivationManager

/**
 * v1.5: first-launch activation screen, shown before the main screen
 * until the user enters the correct key once. The key check is local,
 * so it works offline; a successful activation also triggers the
 * one-time Firestore report (which retries on later launches if offline).
 */
@Composable
fun ActivationScreen(onActivated: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Opening dua, same line as the main screen banner.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                stringResource(R.string.opening_dua),
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            stringResource(R.string.activation_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it; showError = false },
            label = { Text(stringResource(R.string.activation_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (showError) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.activation_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (ActivationManager.isCorrectKey(key)) {
                    ActivationManager.setActivated(context)
                    ActivationManager.reportIfNeeded(context)
                    onActivated()
                } else {
                    showError = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.activation_button))
        }
    }
}
