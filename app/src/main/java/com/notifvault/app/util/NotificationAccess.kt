package com.notifvault.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.notifvault.app.service.NotificationCaptureService

/**
 * Helpers for the one permission this app cannot request at runtime:
 * Notification access, which the user must toggle in system Settings.
 */
object NotificationAccess {

    /**
     * True when the user has granted notification access to this app.
     *
     * The secure setting is the source of truth. It lists packages separated by
     * colons, so an exact component match is needed rather than a substring
     * check, which would false-positive on packages that share a prefix.
     */
    fun isGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false

        val expected =
            ComponentName(context, NotificationCaptureService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Intent that opens the system's notification access settings. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Some OEM builds (notably a few Xiaomi and Huawei skins) hide this screen.
     * The app settings page is the fallback that always exists.
     */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
