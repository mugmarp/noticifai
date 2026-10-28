package com.notifvault.app.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.notifvault.app.ui.screens.AnalyticsScreen
import com.notifvault.app.ui.screens.AppRulesScreen
import com.notifvault.app.ui.screens.HistoryScreen
import com.notifvault.app.ui.screens.LockScreen
import com.notifvault.app.ui.screens.SettingsScreen
import com.notifvault.app.ui.theme.NotificationVaultTheme
import com.notifvault.app.util.Exporter
import com.notifvault.app.util.NotificationAccess
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotificationVaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VaultRoot()
                }
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    History("history", "History", Icons.Filled.Notifications),
    Apps("apps", "Apps", Icons.Filled.Apps),
    Insights("insights", "Insights", Icons.Filled.Insights),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultRoot(viewModel: VaultViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val settings by viewModel.settingsState.collectAsStateWithLifecycle()

    // Re-read settings every time the app comes to the foreground: the user may
    // have granted notification access, or set a PIN, in system Settings.
    val context = LocalContext.current
    var accessGranted by remember { mutableStateOf(NotificationAccess.isGranted(context)) }
    LaunchedEffect(Unit) {
        accessGranted = NotificationAccess.isGranted(context)
        viewModel.refreshSettings()
    }

    val scope = rememberCoroutineScope()

    fun writeExport(context: Context, uri: Uri, format: Exporter.Format) {
        scope.launch {
            runCatching {
                val records = viewModel.recordsForExport()
                val text = Exporter.render(records, format)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(text.toByteArray(StandardCharsets.UTF_8))
                } ?: error("Could not open the chosen file")
                records.size
            }.onSuccess { count ->
                Toast.makeText(context, "Exported $count notifications", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Exporter.Format.CSV.mimeType),
    ) { uri -> uri?.let { writeExport(context, it, Exporter.Format.CSV) } }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(Exporter.Format.JSON.mimeType),
    ) { uri -> uri?.let { writeExport(context, it, Exporter.Format.JSON) } }

    fun startExport(format: Exporter.Format) {
        val name = Exporter.fileName(format)
        when (format) {
            Exporter.Format.CSV -> csvLauncher.launch(name)
            Exporter.Format.JSON -> jsonLauncher.launch(name)
        }
    }

    // ------------------------------------------------------------------ lock

    var unlocked by remember { mutableStateOf(false) }
    LaunchedEffect(settings.appLockEnabled, settings.hasPin, settings.biometricUnlockEnabled) {
        if (!settings.appLockEnabled ||
            (!settings.hasPin && !settings.biometricUnlockEnabled)
        ) {
            unlocked = true
        }
    }

    // A lock with no PIN and no biometrics set would be impossible to get past,
    // so it only engages when there is a way in.
    val lockActive = settings.appLockEnabled &&
        (settings.hasPin || settings.biometricUnlockEnabled)

    if (lockActive && !unlocked) {
        LockScreen(
            viewModel = viewModel,
            hasPin = settings.hasPin,
            biometricEnabled = settings.biometricUnlockEnabled,
            onUnlocked = { unlocked = true },
        )
        return
    }

    // ---------------------------------------------------------------- layout

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (!accessGranted) {
                GrantAccessBanner(
                    onGrant = {
                        runCatching { context.startActivity(NotificationAccess.settingsIntent()) }
                            .onFailure { context.startActivity(NotificationAccess.appSettingsIntent(context)) }
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            NavHost(
                navController = navController,
                startDestination = Tab.History.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding()),
            ) {
                composable(Tab.History.route) {
                    HistoryScreen(
                        viewModel = viewModel,
                        accessGranted = accessGranted,
                        onExportCsv = { startExport(Exporter.Format.CSV) },
                        onExportJson = { startExport(Exporter.Format.JSON) },
                    )
                }
                composable(Tab.Apps.route) {
                    AppRulesScreen(viewModel = viewModel)
                }
                composable(Tab.Insights.route) {
                    AnalyticsScreen(viewModel = viewModel)
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onExportCsv = { startExport(Exporter.Format.CSV) },
                        onExportJson = { startExport(Exporter.Format.JSON) },
                    )
                }
            }
        }
    }
}

/**
 * Shown across the top of the app until notification access is granted. Without
 * it the vault stays empty, so this needs to be unmissable.
 */
@Composable
private fun GrantAccessBanner(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "Notification access is off",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = "Nothing can be recorded until you turn it on. Android only " +
                        "allows you to do this from system Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onGrant) { Text("Open notification access") }
            }
        }
    }
}
