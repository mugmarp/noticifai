package com.notifvault.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/** A launchable app on the device, as shown on the per-app rules screen. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * Reads the list of installed apps and resolves package names to labels.
 *
 * Labels are cached because the capture service needs one on every notification
 * and PackageManager lookups are not free.
 */
class AppCatalog(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager
    private val labelCache = ConcurrentHashMap<String, String>()

    /** Every app with a launcher icon, sorted by label. */
    fun installedApps(): List<InstalledApp> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved.asSequence()
            .mapNotNull { info ->
                val appInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val pkg = appInfo.packageName ?: return@mapNotNull null
                InstalledApp(
                    packageName = pkg,
                    label = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        pkg
                    },
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Human-readable name for a package, falling back to the package name. */
    fun labelFor(packageName: String): String = labelCache.getOrPut(packageName) {
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /** True when the app is currently installed (it may have been uninstalled). */
    fun isInstalled(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Launches an app by package name. Returns false if there is no launch intent. */
    fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
