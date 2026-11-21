package com.example.autocaller.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.autocaller.R
import com.example.autocaller.data.RepeatType
import com.example.autocaller.data.ScheduledCall
import com.example.autocaller.util.SimHelper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ArabicLocale: Locale = Locale.forLanguageTag("ar")
internal val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", ArabicLocale)
internal val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a", ArabicLocale)

/** Converts Arabic-Indic digits to ASCII and drops everything except digits, +, * and #. */
private fun normalizeNumber(raw: String): String = buildString {
    for (c in raw) {
        when {
            c.isDigit() -> {
                val d = Character.digit(c, 10)
                if (d >= 0) append('0' + d)
            }
            c == '+' || c == '*' || c == '#' -> append(c)
        }
    }
}

@Composable
fun repeatLabel(repeat: RepeatType): String = stringResource(
    when (repeat) {
        RepeatType.ONCE -> R.string.once
        RepeatType.DAILY -> R.string.daily
        RepeatType.WEEKLY -> R.string.weekly
    }
)

@Composable
fun CallEditDialog(
    initial: ScheduledCall?,
    onDismiss: () -> Unit,
    onSave: (ScheduledCall) -> Unit
) {
    val context = LocalContext.current
    val defaultDt = remember { LocalDateTime.now().plusMinutes(2) }

    // SIM list: "الافتراضية" first, then SIM 1 / SIM 2 with carrier name.
    // When there is exactly one real SIM, it becomes the default.
    val sims = remember { SimHelper.getSims(context) }
    val defaultSimId = remember {
        val real = sims.filter { it.subscriptionId != SimHelper.SIM_DEFAULT }
        if (real.size == 1) real[0].subscriptionId else SimHelper.SIM_DEFAULT
    }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var number by remember { mutableStateOf(initial?.number ?: "") }
    var date by remember {
        mutableStateOf(initial?.let { LocalDate.ofEpochDay(it.dateEpochDay) } ?: defaultDt.toLocalDate())
    }
    var time by remember {
        mutableStateOf(
            initial?.let { LocalTime.of(it.hour, it.minute) }
                ?: LocalTime.of(defaultDt.hour, defaultDt.minute)
        )
    }
    var repeat by remember { mutableStateOf(initial?.repeat ?: RepeatType.ONCE) }
    var simSubscriptionId by remember { mutableStateOf(initial?.simSubscriptionId ?: defaultSimId) }
    // Auto-redial: all three values are set by the user, never hardcoded.
    // The numbers below are only the initial values shown in the fields.
    var retryEnabled by remember { mutableStateOf(initial?.retryEnabled ?: true) }
    var ringDurationText by remember { mutableStateOf((initial?.ringDurationSeconds ?: 30).toString()) }
    var retryIntervalText by remember { mutableStateOf((initial?.retryIntervalMinutes ?: 5).toString()) }
    var maxRetriesText by remember { mutableStateOf((initial?.maxRetries ?: 3).toString()) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.add_call else R.string.edit_call))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it; errorRes = null },
                    label = { Text(stringResource(R.string.number)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    isError = errorRes == R.string.err_number,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> date = LocalDate.of(y, m + 1, d); errorRes = null },
                            date.year, date.monthValue - 1, date.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("${stringResource(R.string.date)}: ${date.format(dateFormatter)}") }

                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, min -> time = LocalTime.of(h, min); errorRes = null },
                            time.hour, time.minute, false
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("${stringResource(R.string.time)}: ${time.format(timeFormatter)}") }

                Text(stringResource(R.string.repeat), style = MaterialTheme.typography.labelLarge)
                RepeatType.values().forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = repeat == option,
                                onClick = { repeat = option; errorRes = null },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = repeat == option, onClick = null)
                        Text(repeatLabel(option), Modifier.padding(start = 8.dp))
                    }
                }

                // --- SIM selection (inside the app, no system chooser) ---
                Text(stringResource(R.string.sim), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = simSubscriptionId == SimHelper.SIM_DEFAULT,
                            onClick = { simSubscriptionId = SimHelper.SIM_DEFAULT },
                            role = Role.RadioButton
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = simSubscriptionId == SimHelper.SIM_DEFAULT, onClick = null)
                    Text(stringResource(R.string.sim_default), Modifier.padding(start = 8.dp))
                }
                sims.filter { it.subscriptionId != SimHelper.SIM_DEFAULT }.forEach { sim ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = simSubscriptionId == sim.subscriptionId,
                                onClick = { simSubscriptionId = sim.subscriptionId },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = simSubscriptionId == sim.subscriptionId, onClick = null)
                        Text(sim.label, Modifier.padding(start = 8.dp))
                    }
                }

                // --- Auto-redial: user sets every duration ---
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.retry_title), style = MaterialTheme.typography.labelLarge)
                    Switch(checked = retryEnabled, onCheckedChange = { retryEnabled = it })
                }
                if (retryEnabled) {
                    OutlinedTextField(
                        value = ringDurationText,
                        onValueChange = { ringDurationText = it.filter { ch -> ch.isDigit() }; errorRes = null },
                        label = { Text(stringResource(R.string.ring_duration)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = retryIntervalText,
                        onValueChange = { retryIntervalText = it.filter { ch -> ch.isDigit() }; errorRes = null },
                        label = { Text(stringResource(R.string.retry_interval)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = maxRetriesText,
                        onValueChange = { maxRetriesText = it.filter { ch -> ch.isDigit() }; errorRes = null },
                        label = { Text(stringResource(R.string.max_retries)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                errorRes?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = normalizeNumber(number)
                val ringSeconds = ringDurationText.toIntOrNull()?.coerceIn(5, 120) ?: 30
                val intervalMinutes = retryIntervalText.toIntOrNull()?.coerceIn(1, 60) ?: 5
                val maxAttempts = maxRetriesText.toIntOrNull()?.coerceIn(1, 10) ?: 3
                if (clean.isEmpty()) {
                    errorRes = R.string.err_number
                } else if (retryEnabled && (ringDurationText.toIntOrNull() == null ||
                        retryIntervalText.toIntOrNull() == null ||
                        maxRetriesText.toIntOrNull() == null)
                ) {
                    errorRes = R.string.err_retry_value
                } else if (repeat == RepeatType.ONCE &&
                    !LocalDateTime.of(date, time).isAfter(LocalDateTime.now())
                ) {
                    errorRes = R.string.err_past
                } else {
                    // A saved (or edited) call starts a fresh retry cycle.
                    // The same number may be saved again as a different
                    // call with a different time — no uniqueness check.
                    onSave(
                        ScheduledCall(
                            id = initial?.id ?: 0L,
                            name = name.trim().ifEmpty { clean },
                            number = clean,
                            dateEpochDay = date.toEpochDay(),
                            hour = time.hour,
                            minute = time.minute,
                            repeat = repeat,
                            enabled = true,
                            simSubscriptionId = simSubscriptionId,
                            retryEnabled = retryEnabled,
                            ringDurationSeconds = ringSeconds,
                            retryIntervalMinutes = intervalMinutes,
                            maxRetries = maxAttempts,
                            attemptCount = 0,
                            lastAttemptMillis = 0L
                        )
                    )
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
