package com.notifvault.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertChartOutlined
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notifvault.app.ui.VaultViewModel
import com.notifvault.app.ui.components.AppIcon
import com.notifvault.app.ui.components.EmptyState
import com.notifvault.app.ui.components.SectionCard
import com.notifvault.app.ui.components.StatTile

/**
 * Where the noise is coming from: which apps notify you most, and when.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(viewModel: VaultViewModel) {
    val analytics by viewModel.analytics.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    LaunchedEffect(settings.totalStored) {
        viewModel.refreshAnalytics(analytics.windowDays)
    }

    val state = analytics
    val peakHour = state.hourly.withIndex().maxByOrNull { it.value }?.index
    val busiestApp = state.topApps.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Insights")
                        Text(
                            text = "Last ${state.windowDays} days",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(7, 30, 90).forEach { days ->
                    FilterChip(
                        selected = state.windowDays == days,
                        onClick = { viewModel.refreshAnalytics(days) },
                        label = { Text("${days}d") },
                    )
                }
            }

            if (state.totalInWindow == 0) {
                EmptyState(
                    icon = Icons.Filled.InsertChartOutlined,
                    title = "Nothing to report yet",
                    message = "Once notifications have been captured for a while, this " +
                        "screen shows which apps are the loudest and when you are most " +
                        "interrupted.",
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            value = state.totalInWindow.toString(),
                            caption = "Notifications",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = state.topApps.size.toString(),
                            caption = "Apps",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = busiestApp?.appLabel?.take(8) ?: "-",
                            caption = "Loudest",
                            modifier = Modifier.weight(1f),
                            accent = MaterialTheme.colorScheme.tertiary,
                        )
                        StatTile(
                            value = peakHour?.let { "%02d:00".format(it) } ?: "-",
                            caption = "Peak hour",
                            modifier = Modifier.weight(1f),
                            accent = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                item {
                    SectionCard(
                        title = "By hour of day",
                        subtitle = "When notifications arrive across the whole day",
                    ) {
                        HourHistogram(hourly = state.hourly)
                    }
                }

                if (state.daily.isNotEmpty()) {
                    item {
                        SectionCard(
                            title = "By day",
                            subtitle = "Total captured per day, oldest first",
                        ) {
                            DailyBars(
                                daily = state.daily,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp),
                            )
                        }
                    }
                }

                item {
                    SectionCard(
                        title = "Loudest apps",
                        subtitle = "Ranked by how many notifications they sent",
                    ) {
                        if (state.topApps.isEmpty()) {
                            Text(
                                text = "No data in this window.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(items = state.topApps, key = { it.packageName }) { app ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(
                                packageName = app.packageName,
                                label = app.appLabel,
                                size = 32.dp,
                            )
                            Text(
                                text = app.appLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = app.count.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (state.topApps.isEmpty()) 0f
                                else app.count.toFloat() / state.topApps.first().count
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourHistogram(hourly: IntArray) {
    val max = hourly.maxOrNull()?.takeIf { it > 0 } ?: 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        hourly.forEachIndexed { hour, count ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((86 * (count.toFloat() / max)).dp.coerceAtLeast(2.dp))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(
                            if (count > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                )
                if (hour % 6 == 0) {
                    Text(
                        text = "%02d".format(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = " ",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyBars(
    daily: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    val max = daily.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        daily.forEach { (_, count) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight((count.toFloat() / max).coerceIn(0.03f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
