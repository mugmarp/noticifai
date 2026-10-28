package com.notifvault.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifvault.app.data.HistoryFilter
import com.notifvault.app.data.NotificationRecord
import com.notifvault.app.ui.VaultViewModel
import com.notifvault.app.ui.components.AppIcon
import com.notifvault.app.ui.components.EmptyState
import com.notifvault.app.ui.components.Pill
import com.notifvault.app.ui.components.TimeFormat

private const val DAY_MS = 24 * 60 * 60 * 1000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalComposeUiApi::class,
)
@Composable
fun HistoryScreen(
    viewModel: VaultViewModel,
    accessGranted: Boolean,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val packages by viewModel.packagesInVault.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val matchCount by viewModel.matchCount.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val paging by viewModel.paging.collectAsStateWithLifecycle()

    var searchVisible by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showAppFilter by remember { mutableStateOf(false) }
    var showDateFilter by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<NotificationRecord?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Typing in the search field drives the same filter the chips use.
    LaunchedEffect(searchText) { viewModel.setSearchText(searchText) }

    // Start the next page while there is still runway, so the list never
    // visibly stops to wait. loadMore() is a no-op while a page is in flight or
    // once everything has been loaded, so firing this repeatedly is safe.
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0 && lastVisible >= total - 5) viewModel.loadMore()
            }
    }

    LaunchedEffect(searchVisible) {
        if (searchVisible) focusRequester.requestFocus()
    }

    // Apps the user chose to hide are filtered out of the main feed.
    val visibleRecords = remember(records, settings.hiddenPackages) {
        records.filterNot { it.packageName in settings.hiddenPackages }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("History")
                        Text(
                            text = if (filter.isActive) {
                                "$matchCount matching"
                            } else {
                                "${settings.totalStored} stored"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchVisible) "Close search" else "Search",
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
            if (searchVisible) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Search text, sender, app...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }

            FilterChipRow(
                filter = filter,
                onToggleUnread = { viewModel.setOnlyUnread(!filter.onlyUnread) },
                onToggleStarred = { viewModel.setOnlyStarred(!filter.onlyStarred) },
                onToggleHideQuiet = { viewModel.setHideQuietHours(!filter.hideQuietHours) },
                onOpenApps = { showAppFilter = true },
                onOpenDates = { showDateFilter = true },
                onClear = {
                    searchText = ""
                    viewModel.clearFilter()
                },
            )

            when {
                !accessGranted -> EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = "Not connected yet",
                    message = "Turn on notification access using the banner at the top, " +
                        "then notifications will start appearing here.",
                )

                visibleRecords.isEmpty() -> EmptyState(
                    icon = if (filter.isActive) Icons.Filled.Search else Icons.Filled.Inbox,
                    title = if (filter.isActive) "No matches" else "Nothing captured yet",
                    message = if (filter.isActive) {
                        "Try a different search, or clear the filters."
                    } else {
                        "Every notification your phone receives will be saved here " +
                            "and kept until you delete it."
                    },
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = visibleRecords,
                        key = { it.id },
                    ) { record ->
                        NotificationRow(
                            record = record,
                            onClick = { detail = record },
                            onToggleStar = { viewModel.toggleStar(record) },
                        )
                    }

                    // Footer doubles as the manual retry if the scroll trigger
                    // somehow never fires.
                    if (paging) {
                        item(key = "paging") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    } else if (hasMore) {
                        item(key = "loadMore") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                TextButton(onClick = { viewModel.loadMore() }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ app filter

    if (showAppFilter) {
        ModalBottomSheet(onDismissRequest = { showAppFilter = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = "Filter by app",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (packages.isEmpty()) {
                    Text(
                        text = "No apps in the vault yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                packages.forEach { (pkg, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.togglePackageFilter(pkg) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(packageName = pkg, label = label, size = 36.dp)
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Checkbox(
                            checked = pkg in filter.packages,
                            onCheckedChange = { viewModel.togglePackageFilter(pkg) },
                        )
                    }
                }
                if (filter.packages.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            filter.packages.forEach { viewModel.togglePackageFilter(it) }
                            showAppFilter = false
                        },
                    ) { Text("Clear app filter") }
                }
            }
        }
    }

    // ----------------------------------------------------------- date filter

    if (showDateFilter) {
        ModalBottomSheet(onDismissRequest = { showDateFilter = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Filter by date",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val now = System.currentTimeMillis()
                listOf(
                    Triple("Last hour", now - 60L * 60L * 1000L, now),
                    Triple("Last 24 hours", now - DAY_MS, now),
                    Triple("Last 7 days", now - 7 * DAY_MS, now),
                    Triple("Last 30 days", now - 30 * DAY_MS, now),
                ).forEach { (label, from, to) ->
                    TextButton(
                        onClick = {
                            viewModel.setDateRange(from, to)
                            showDateFilter = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                }
                TextButton(
                    onClick = {
                        viewModel.setDateRange(null, null)
                        showDateFilter = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Any time", modifier = Modifier.fillMaxWidth()) }
            }
        }
    }

    // --------------------------------------------------------- detail sheet

    detail?.let { record ->
        DetailSheet(
            record = record,
            onDismiss = { detail = null },
            onToggleStar = {
                viewModel.toggleStar(record)
                detail = record.copy(starred = !record.starred)
            },
            onToggleRead = {
                viewModel.toggleRead(record)
                detail = record.copy(isRead = !record.isRead)
            },
            onOpenApp = {
                if (!viewModel.launchApp(record.packageName)) {
                    detail = null
                }
            },
            onDelete = {
                viewModel.delete(record)
                detail = null
            },
        )
    }

    if (confirmClearAll) {
        ModalBottomSheet(onDismissRequest = { confirmClearAll = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Delete all history?", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "This permanently removes all ${settings.totalStored} stored " +
                        "notifications. It cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        viewModel.deleteAll()
                        confirmClearAll = false
                    }) { Text("Delete everything") }
                }
            }
        }
    }

    // Keep the detail sheet honest: if the row is deleted elsewhere, close it.
    LaunchedEffect(visibleRecords.size) {
        val open = detail ?: return@LaunchedEffect
        if (visibleRecords.none { it.id == open.id }) detail = null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipRow(
    filter: HistoryFilter,
    onToggleUnread: () -> Unit,
    onToggleStarred: () -> Unit,
    onToggleHideQuiet: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDates: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.onlyUnread,
            onClick = onToggleUnread,
            label = { Text("Unread") },
            leadingIcon = { Icon(Icons.Filled.DoneAll, null, Modifier.size(16.dp)) },
            colors = FilterChipDefaults.filterChipColors(),
        )
        FilterChip(
            selected = filter.onlyStarred,
            onClick = onToggleStarred,
            label = { Text("Starred") },
            leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) },
        )
        FilterChip(
            selected = filter.hideQuietHours,
            onClick = onToggleHideQuiet,
            label = { Text("Hide quiet") },
            leadingIcon = { Icon(Icons.Filled.NightsStay, null, Modifier.size(16.dp)) },
        )
        AssistChip(
            onClick = onOpenApps,
            label = {
                Text(if (filter.packages.isEmpty()) "Apps" else "Apps (${filter.packages.size})")
            },
            leadingIcon = { Icon(Icons.Filled.Apps, null, Modifier.size(16.dp)) },
        )
        AssistChip(
            onClick = onOpenDates,
            label = { Text(if (filter.from == null) "Date" else "Date set") },
            leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(16.dp)) },
        )
        if (filter.isActive) {
            AssistChip(
                onClick = onClear,
                label = { Text("Clear") },
                leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NotificationRow(
    record: NotificationRecord,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (record.isRead) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(packageName = record.packageName, label = record.appLabel, size = 40.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // For messaging apps the sender matters more than the app name.
                    val headline = record.sender
                        ?: record.conversationTitle
                        ?: record.appLabel
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (record.isRead) FontWeight.Normal else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!record.isRead) {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (record.hasImage) {
                        Pill(
                            text = "IMG",
                            modifier = Modifier.padding(start = 6.dp),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                if (record.title.isNotBlank() &&
                    record.title != record.sender &&
                    record.title != record.conversationTitle
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (record.body.isNotBlank()) {
                    Text(
                        text = record.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = TimeFormat.relative(record.postedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (record.appLabel != (record.sender ?: record.conversationTitle)) {
                        Text(
                            text = "· ${record.appLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (record.duringQuietHours) {
                        Icon(
                            imageVector = Icons.Filled.NightsStay,
                            contentDescription = "Arrived during quiet hours",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // remoteInputCount is captured and exported, but the
                    // RemoteInput objects themselves are deliberately not
                    // persisted, so there is nothing to reply from here yet and
                    // no label claiming otherwise.
                }
            }

            IconButton(onClick = onToggleStar, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (record.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (record.starred) "Unstar" else "Star",
                    modifier = Modifier.size(18.dp),
                    tint = if (record.starred) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun DetailSheet(
    record: NotificationRecord,
    onDismiss: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onOpenApp: () -> Unit,
    onDelete: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(packageName = record.packageName, label = record.appLabel, size = 44.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    record.sender?.let {
                        Text(
                            text = "From $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    record.conversationTitle?.let {
                        Text(
                            text = "In $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Text(text = TimeFormat.full(record.postedAt), style = MaterialTheme.typography.labelMedium)

            if (record.title.isNotBlank()) {
                Text(text = record.title, style = MaterialTheme.typography.titleSmall)
            }
            if (record.body.isNotBlank()) {
                Text(text = record.body, style = MaterialTheme.typography.bodyMedium)
            }
            if (record.subText?.isNotBlank() == true) {
                Text(
                    text = record.subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (record.actions.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Actions offered", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        record.actions.split(", ").forEach { Pill(text = it) }
                    }
                }
            }

            if (record.channelId != null || record.category != null) {
                Text(
                    text = listOfNotNull(
                        record.channelId?.let { "Channel: $it" },
                        record.category?.let { "Category: $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(record.body))
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text")
                }
                IconButton(onClick = onOpenApp) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = "Open app")
                }
                IconButton(onClick = onToggleStar) {
                    Icon(
                        imageVector = if (record.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Toggle star",
                    )
                }
                IconButton(onClick = onToggleRead) {
                    Icon(Icons.Filled.DoneAll, contentDescription = "Toggle read")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "Stored on this device only. Notification Vault has no internet " +
                        "permission and never uploads anything.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}
