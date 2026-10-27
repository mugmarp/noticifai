package com.notifvault.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val VaultLight = lightColorScheme(
    primary = Color(0xFF1B6B7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E8F5),
    onPrimaryContainer = Color(0xFF002A33),
    secondary = Color(0xFF4C6269),
    tertiary = Color(0xFF5A5C8D),
    background = Color(0xFFFBFCFD),
    surface = Color(0xFFFBFCFD),
    surfaceVariant = Color(0xFFDBE4E7),
    error = Color(0xFFBA1A1A),
)

private val VaultDark = darkColorScheme(
    primary = Color(0xFF8DD3E3),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004F5B),
    onPrimaryContainer = Color(0xFFB8E8F5),
    secondary = Color(0xFFB3CBD3),
    tertiary = Color(0xFFC3C4F5),
    background = Color(0xFF0E1416),
    surface = Color(0xFF0E1416),
    surfaceVariant = Color(0xFF3F484B),
    error = Color(0xFFFFB4AB),
)

@Composable
fun NotificationVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Uses the wallpaper-derived palette on Android 12+, where it is available. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> VaultDark
        else -> VaultLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
