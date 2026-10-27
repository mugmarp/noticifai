package com.notifvault.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifvault.app.data.InstalledApp
import com.notifvault.app.data.TrackMode
import com.notifvault.app.ui.VaultViewModel
import com.notifvault.app.ui.components.AppIcon
import com.notifvault.app.ui.components.EmptyState
import com.notifvault.app.ui.components.Pill

/**
 * Per-app capture control: choose which apps are recorded, and how strictly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRulesScreen(viewModel: VaultViewModel) {
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    // Reading the installed-app list touches PackageManager, so do it once and
    // hold it in state rather than recomputing it on every recomposition.
    val apps = remember { viewModel.installedApps() }
    var query by remember { mutableStateOf("") }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    // Held in local state so the segmented button reflects a tap immediately,
    // rather than waiting for the settings snapshot to come back around.
    var rules by remember {
        mutableStateOf(apps.associate { it.packageName to viewModel.appMode(it.packageName) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Apps")
                        Text(
                            text = "Choose what gets recorded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            if (filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Apps,
                    title = "No apps match",
                    message = "Try a different search term.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = filtered, key = { it.packageName }) { app ->
                        AppRuleRow(
                            app = app,
                            mode = rules[app.packageName] ?: TrackMode.ALWAYS,
                            hidden = app.packageName in settings.hiddenPackages,
                            onModeChange = { mode ->
                                rules = rules + (app.packageName to mode)
                                viewModel.setAppMode(app.packageName, mode)
                            },
                            onToggleHidden = { viewModel.toggleHidden(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRuleRow(
    app: InstalledApp,
    mode: TrackMode,
    hidden: Boolean,
    onModeChange: (TrackMode) -> Unit,
    onToggleHidden: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(packageName = app.packageName, label = app.label, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (app.isSystem) {
                Pill(text = "System")
            }
            IconButton(onClick = onToggleHidden) {
                Icon(
                    imageVector = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (hidden) {
                        "Show in History"
                    } else {
                        "Hide from History"
                    },
                    tint = if (hidden) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                TrackMode.ALWAYS to "Always",
                TrackMode.UNREAD_ONLY to "Unread only",
                TrackMode.NEVER to "Never",
            )
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { onModeChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
