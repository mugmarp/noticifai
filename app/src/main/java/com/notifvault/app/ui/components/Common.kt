package com.notifvault.app.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.Result.runCatching

/** Human-friendly timestamps used across the History and Detail screens. */
object TimeFormat {

    private val timeOfDay = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayMonth = SimpleDateFormat("d MMM", Locale.getDefault())
    private val dayMonthYear = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private val fullStamp = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())

    /** "now", "4 min ago", "Yesterday 21:03", "3 Mar 09:14". */
    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - epochMillis
        return when {
            diff < 60_000 -> "now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            isYesterday(epochMillis, now) -> "Yesterday ${timeOfDay.format(Date(epochMillis))}"
            isToday(epochMillis, now) -> timeOfDay.format(Date(epochMillis))
            isSameYear(epochMillis, now) -> "${dayMonth.format(Date(epochMillis))} " +
                timeOfDay.format(Date(epochMillis))
            else -> dayMonthYear.format(Date(epochMillis))
        }
    }

    fun clock(epochMillis: Long): String = timeOfDay.format(Date(epochMillis))

    fun full(epochMillis: Long): String = fullStamp.format(Date(epochMillis))

    /** Sticky header text for the grouped History list. */
    fun dayHeader(epochMillis: Long, now: Long = System.currentTimeMillis()): String = when {
        isToday(epochMillis, now) -> "Today"
        isYesterday(epochMillis, now) -> "Yesterday"
        isSameYear(epochMillis, now) -> dayMonth.format(Date(epochMillis))
        else -> dayMonthYear.format(Date(epochMillis))
    }

    private fun calendar(millis: Long) =
        Calendar.getInstance().apply { timeInMillis = millis }

    private fun isToday(a: Long, b: Long): Boolean {
        val ca = calendar(a)
        val cb = calendar(b)
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(a: Long, b: Long): Boolean =
        isToday(a, b - 24 * 60 * 60 * 1000L)

    private fun isSameYear(a: Long, b: Long): Boolean =
        calendar(a).get(Calendar.YEAR) == calendar(b).get(Calendar.YEAR)
}

/**
 * An app's launcher icon, loaded lazily and cached for the life of the
 * composition. Falls back to the first letter of the label on a tinted circle
 * if the icon cannot be loaded.
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(128, 128).asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            modifier = modifier.size(size),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(size / 4),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Placeholder shown when a list has nothing in it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A titled card used to group related settings. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

/** One number with a caption, used on the Analytics screen. */
@Composable
fun StatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small coloured pill, e.g. "Quiet hours" or an app name. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Context wrapper so screens can resolve labels without threading it manually. */
@Composable
fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) { resolveLabel(context, packageName) }
}

private fun resolveLabel(context: Context, packageName: String): String =
    runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

/** A right-aligned label/value pair for settings rows. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
