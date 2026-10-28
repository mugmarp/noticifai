package com.notifvault.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifvault.app.ui.VaultViewModel
import com.notifvault.app.ui.components.Pill
import com.notifvault.app.ui.components.SectionCard
import com.notifvault.app.ui.components.SettingRow
import com.notifvault.app.util.PinHasher
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    var keywordInput by remember { mutableStateOf("") }
    var showPinDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var retention by remember(settings.retentionDays) {
        mutableStateOf(settings.retentionDays.toFloat())
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ------------------------------------------------------- capture

            SectionCard(
                title = "Capture",
                subtitle = "Turn recording on or off without changing your per-app rules",
            ) {
                SettingRow(
                    title = "Record notifications",
                    subtitle = "Master switch for the whole app",
                    trailing = {
                        Switch(
                            checked = settings.masterEnabled,
                            onCheckedChange = viewModel::setMasterEnabled,
                        )
                    },
                )
                Text(
                    text = "${settings.totalStored} notifications stored",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ----------------------------------------------------- retention

            SectionCard(
                title = "Retention",
                subtitle = "Old notifications are deleted automatically",
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Keep for", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (retention <= 0f) "Forever" else "${retention.roundToInt()} days",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = retention,
                        onValueChange = { retention = it },
                        onValueChangeFinished = { viewModel.setRetentionDays(retention.roundToInt()) },
                        valueRange = 0f..90f,
                        // One notch per day, so the label and the value always agree.
                        steps = 89,
                    )
                    Text(
                        text = "Notifications older than this are removed the next time the " +
                            "app runs or a notification is captured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.runSweepNow() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clean up now") }
            }

            // ---------------------------------------------------- quiet hours

            SectionCard(
                title = "Quiet hours",
                subtitle = "Still recorded, but folded away in the History list",
            ) {
                SettingRow(
                    title = "Enable quiet hours",
                    trailing = {
                        Switch(
                            checked = settings.quietHoursEnabled,
                            onCheckedChange = viewModel::setQuietHoursEnabled,
                        )
                    },
                )
                if (settings.quietHoursEnabled) {
                    QuietWindowRow(
                        label = "From",
                        minutes = settings.quietStartMinutes,
                        onChange = { viewModel.setQuietWindow(it, settings.quietEndMinutes) },
                    )
                    QuietWindowRow(
                        label = "Until",
                        minutes = settings.quietEndMinutes,
                        onChange = { viewModel.setQuietWindow(settings.quietStartMinutes, it) },
                    )
                }
            }

            // ------------------------------------------------- ignore list

            SectionCard(
                title = "Ignore keywords",
                subtitle = "Matching notifications are dropped and never stored",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = keywordInput,
                        onValueChange = { keywordInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("e.g. sale, promo") },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            viewModel.addIgnoreKeyword(keywordInput)
                            keywordInput = ""
                        },
                        enabled = keywordInput.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add keyword")
                    }
                }
                if (settings.ignoreKeywords.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        settings.ignoreKeywords.forEach { keyword ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Pill(text = keyword)
                                IconButton(
                                    onClick = { viewModel.removeIgnoreKeyword(keyword) },
                                    modifier = Modifier.padding(start = 2.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove $keyword",
                                        modifier = Modifier.padding(2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------- privacy

            SectionCard(
                title = "Privacy",
                subtitle = "Everything stays on this device. No internet permission is declared.",
            ) {
                SettingRow(
                    title = "App lock",
                    subtitle = "Require a PIN or biometrics to open the app",
                    trailing = {
                        Switch(
                            checked = settings.appLockEnabled,
                            onCheckedChange = viewModel::setAppLockEnabled,
                        )
                    },
                )
                SettingRow(
                    title = "Biometric unlock",
                    subtitle = if (viewModel.hasBiometricsAvailable()) {
                        "Use your fingerprint, face, or screen lock"
                    } else {
                        "No biometrics or screen lock set up on this device"
                    },
                    trailing = {
                        Switch(
                            checked = settings.biometricUnlockEnabled,
                            onCheckedChange = viewModel::setBiometricEnabled,
                            enabled = viewModel.hasBiometricsAvailable() && settings.hasPin,
                        )
                    },
                )
                Button(
                    onClick = { showPinDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (settings.hasPin) "Change PIN" else "Set PIN") }
            }

            // ----------------------------------------------------------- data

            SectionCard(
                title = "Export and backup",
                subtitle = "You choose where the file is saved",
            ) {
                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export as CSV") }
                OutlinedButton(
                    onClick = onExportJson,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export as JSON") }
            }

            SectionCard(
                title = "Danger zone",
                subtitle = "These cannot be undone",
            ) {
                Button(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all stored notifications") }
            }

            Text(
                text = "Notification Vault · everything stored in a local SQLite database " +
                    "on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                viewModel.setPinHash(PinHasher.hash(pin))
                viewModel.setAppLockEnabled(true)
                showPinDialog = false
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Delete everything?") },
            text = {
                Text("All ${settings.totalStored} stored notifications will be permanently removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAll()
                    showClearDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** A 30-minute stepper, which is finer-grained than a full time picker. */
@Composable
private fun QuietWindowRow(
    label: String,
    minutes: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((minutes - 30 + 1440) % 1440) }) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = "%02d:%02d".format(minutes / 60, minutes % 60),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = { onChange((minutes + 30) % 1440) }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Two-step PIN entry. The PIN is hashed before it ever reaches storage. */
@Composable
private fun PinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(0) } // 0 = choose, 1 = confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (stage == 0) "Choose a PIN" else "Confirm your PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (stage == 0) {
                        "Use 4 to 8 digits. It is stored as a salted hash, never as plain text."
                    } else {
                        "Enter the same PIN again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = if (stage == 0) first else second,
                    onValueChange = { value ->
                        val digits = value.filter(Char::isDigit).take(8)
                        if (stage == 0) first = digits else second = digits
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                val mismatch = stage == 1 && first.isNotEmpty() && first != second
                if (mismatch) {
                    Text(
                        text = "Those do not match.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (stage == 0) {
                        if (first.length >= 4) stage = 1
                    } else if (first == second && second.isNotEmpty()) {
                        onConfirm(first)
                    }
                },
                enabled = if (stage == 0) first.length >= 4 else first == second,
            ) { Text(if (stage == 0) "Next" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                if (stage == 1) stage = 0 else onDismiss()
            }) { Text(if (stage == 0) "Cancel" else "Back") }
        },
    )
}
