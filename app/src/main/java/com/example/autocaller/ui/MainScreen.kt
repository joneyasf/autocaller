package com.example.autocaller.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.autocaller.R
import com.example.autocaller.data.RepeatType
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.scheduler.AlarmScheduler
import com.example.autocaller.util.PermissionStatus
import com.example.autocaller.util.SimHelper
import com.example.autocaller.util.findActivity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val nextFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM، hh:mm a", Locale.forLanguageTag("ar"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val calls by vm.calls.collectAsState()

    var status by remember { mutableStateOf(PermissionStatus.read(context)) }
    var userInitiated by remember { mutableStateOf(false) }
    var dialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledCall?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduledCall?>(null) }

    // Re-check permissions every time the app comes to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = PermissionStatus.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        status = PermissionStatus.read(context)
        if (userInitiated) {
            userInitiated = false
            val activity = context.findActivity()
            val permanentlyDenied = result.any { (perm, granted) ->
                !granted && activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            if (permanentlyDenied) context.startActivity(PermissionStatus.appSettingsIntent(context))
        }
    }

    // Ask for missing runtime permissions on launch.
    LaunchedEffect(Unit) {
        val missing = status.missingRuntime()
        if (missing.isNotEmpty()) runtimeLauncher.launch(missing)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; dialogOpen = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_call))
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PermissionsCard(
                status = status,
                onGrantRuntime = {
                    val missing = status.missingRuntime()
                    if (missing.isNotEmpty()) {
                        userInitiated = true
                        runtimeLauncher.launch(missing)
                    }
                },
                onOpenExactSettings = {
                    try {
                        context.startActivity(PermissionStatus.exactAlarmIntent(context))
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(PermissionStatus.appSettingsIntent(context))
                    }
                },
                onOpenAppSettings = {
                    context.startActivity(PermissionStatus.appSettingsIntent(context))
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )

            if (calls.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.empty),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(calls, key = { it.id }) { call ->
                        CallItem(
                            call = call,
                            onToggle = { vm.setEnabled(call, it) },
                            onEdit = { editing = call; dialogOpen = true },
                            onDelete = { pendingDelete = call }
                        )
                    }
                }
            }
        }
    }

    if (dialogOpen) {
        CallEditDialog(
            initial = editing,
            onDismiss = { dialogOpen = false },
            onSave = { vm.save(it); dialogOpen = false }
        )
    }

    pendingDelete?.let { call ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, call.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(call); pendingDelete = null }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CallItem(
    call: ScheduledCall,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val next = AlarmScheduler.nextTriggerMillis(call)
    val time = LocalTime.of(call.hour, call.minute).format(timeFormatter)
    val whenText = if (call.repeat == RepeatType.ONCE) {
        "${LocalDate.ofEpochDay(call.dateEpochDay).format(dateFormatter)} - $time"
    } else time

    val statusText = when {
        !call.enabled -> stringResource(R.string.disabled)
        next == null -> stringResource(R.string.expired)
        else -> stringResource(
            R.string.next_call,
            Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()).format(nextFormatter)
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(call.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("\u202A${call.number}\u202C", style = MaterialTheme.typography.bodyMedium)
                Text("$whenText • ${repeatLabel(call.repeat)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.sim_label, SimHelper.labelFor(context, call.simSubscriptionId)),
                    style = MaterialTheme.typography.bodySmall
                )
                if (call.retryEnabled) {
                    Text(
                        stringResource(
                            R.string.retry_info,
                            call.attemptCount,
                            call.maxRetries,
                            call.retryIntervalMinutes
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Switch(
                checked = call.enabled,
                onCheckedChange = onToggle,
                enabled = next != null || call.enabled
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}
