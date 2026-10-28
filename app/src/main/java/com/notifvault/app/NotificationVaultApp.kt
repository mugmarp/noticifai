package com.notifvault.app

import android.app.Application
import com.notifvault.app.data.AppCatalog
import com.notifvault.app.data.SettingsStore
import com.notifvault.app.data.VaultDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runCatching

class NotificationVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Touch the singletons once at startup so the first notification
        // captured after a cold boot does not pay for database creation.
        val settings = SettingsStore.get(this)
        val db = VaultDb.get(this)
        AppCatalog(this)

        // Run the retention sweep on launch as well as on capture, so a device
        // that receives no notifications still ages out its history.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                db.sweep(settings.retentionDays, settings.maxStored, System.currentTimeMillis())
            }
        }
    }
}
