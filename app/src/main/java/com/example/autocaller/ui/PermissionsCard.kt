package com.example.autocaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.autocaller.R
import com.example.autocaller.util.PermissionStatus

@Composable
fun PermissionsCard(
    status: PermissionStatus,
    onGrantRuntime: () -> Unit,
    onOpenExactSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status.allGranted) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleSmall)
            if (!status.hasCall) {
                PermissionRow(stringResource(R.string.perm_call), stringResource(R.string.grant), onGrantRuntime)
            }
            if (!status.hasPhoneState) {
                PermissionRow(stringResource(R.string.perm_phone_state), stringResource(R.string.grant), onGrantRuntime)
            }
            if (!status.hasCallLog) {
                PermissionRow(stringResource(R.string.perm_call_log), stringResource(R.string.grant), onGrantRuntime)
            }
            if (!status.hasNotif) {
                PermissionRow(stringResource(R.string.perm_notif), stringResource(R.string.grant), onGrantRuntime)
            }
            if (!status.canExact) {
                PermissionRow(stringResource(R.string.perm_exact), stringResource(R.string.open_settings), onOpenExactSettings)
            }
            TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.app_settings)) }
        }
    }
}

@Composable
private fun PermissionRow(label: String, buttonText: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick) { Text(buttonText) }
    }
}
