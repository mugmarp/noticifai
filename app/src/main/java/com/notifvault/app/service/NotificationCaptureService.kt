package com.notifvault.app.service

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notifvault.app.data.AppCatalog
import com.notifvault.app.data.NotificationRecord
import com.notifvault.app.data.SettingsStore
import com.notifvault.app.data.TrackMode
import com.notifvault.app.data.VaultDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Live state of the listener connection, so the UI can show a clear banner. */
object CaptureBus {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _capturedCount = MutableStateFlow(0)
    val capturedCount: StateFlow<Int> = _capturedCount

    fun markConnected(value: Boolean) {
        _connected.value = value
    }

    fun noteCaptured() {
        _capturedCount.value = _capturedCount.value + 1
    }
}

/**
 * Captures every notification posted on the device and writes it to the vault.
 *
 * The system binds this service only after the user grants Notification access
 * in Settings. Everything below runs on a background dispatcher, because
 * [onNotificationPosted] is called on the main thread and apps call it in bursts.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsStore
    private lateinit var db: VaultDb
    private lateinit var catalog: AppCatalog

    /** Notifications already handled in this process, to suppress re-posts. */
    private val seenKeys = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore.get(this)
        db = VaultDb.get(this)
        catalog = AppCatalog(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureBus.markConnected(true)
        // Notifications posted while the service was disconnected are still in
        // the shade, so seed from them rather than starting with an empty vault.
        scope.launch {
            try { activeNotifications?.forEach { handlePosted(it, seed = true) } } catch (e: Exception) { }
            maybeSweep()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        CaptureBus.markConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch {
            try { handlePosted(sbn, seed = false) } catch (e: Exception) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
            sbn ?: return
            // Only a deliberate interaction means the user has dealt with it.
            //
            // A notification that timed out (REASON_TIMEOUT) or was swiped away
            // (REASON_USER_DISMISSED) was frequently never seen at all, and marking
            // those read quietly discarded precisely the messages this app exists
            // to rescue: they vanished from the default Unread filter. So the
            // removal reason decides, not merely the fact of removal.
            //
            // The REASON_* constants have existed on StatusBarNotification since
            // API 24, comfortably below this app's minSdk of 26.
            // Use reflection to access reason property safely across API levels.
            val reason: Int? = try {
                sbn?.reason
            } catch (e: Exception) {
                null
            }
            val dealtWith = when (reason) {
                // Tapping the body. The user has certainly seen it.
                1 -> true  // REASON_USER_CANCELLED

                // The sending app withdrew it, which is what most messaging apps do
                // once their chat has been opened.
                2 -> true  // REASON_APP_CANCELLED

                // Swiped away. A normal gesture, and frequently done unread.
                3 -> false  // REASON_USER_DISMISSED

                // Expired on its own, probably without ever being seen.
                4 -> false  // REASON_TIMEOUT

                // Anything the platform adds later: leave the read state alone.
                else -> false
            }
            if (!dealtWith) return
            scope.launch {
                try { db.setReadForKey(sbn.key, true) } catch (e: Exception) { }
            }
        }

    // ---------------------------------------------------------------- capture

    private suspend fun handlePosted(sbn: StatusBarNotification, seed: Boolean) {
        if (!settings.masterEnabled) return
        if (sbn.packageName == packageName) return

        // A re-post of a key we already handled in this process is a refresh of
        // the same notification, not a new one. Letting it through would show
        // the user duplicate rows for e.g. a download progressing.
        if (!seed && !seenKeys.add(sbn.key)) return
        if (seed) seenKeys.add(sbn.key)

        when (settings.modeFor(sbn.packageName)) {
            TrackMode.NEVER -> return
            TrackMode.UNREAD_ONLY -> if (sbn.isOngoing) return
            TrackMode.ALWAYS -> Unit
        }

        val record = extract(sbn) ?: return
        if (settings.isIgnored(record)) return

        try { db.saveSync(record) } catch (e: Exception) { }
        CaptureBus.noteCaptured()
        maybeSweep()
    }

    /** Flattens a StatusBarNotification into the flat record the vault stores. */
    private fun extract(sbn: StatusBarNotification): NotificationRecord? {
        val notification = sbn.notification ?: return null

        // On Android 15+ the system redacts one-time passwords and other
        // sensitive strings before handing them to a listener. There is no way
        // around this from an app, and it is intentional.
        val extras = notification.extras

        // Bundle.getCharSequence(String) takes an argument, so it is a plain
        // method call and not a Kotlin synthetic property.
        fun text(key: String): String? = extras?.getCharSequence(key)?.toString()

        val bigText = text(Notification.EXTRA_BIG_TEXT)
        val normalText = text(Notification.EXTRA_TEXT)
        val textLines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }

        val body = listOfNotNull(
            bigText?.takeIf { it.isNotBlank() },
            normalText?.takeIf { it.isNotBlank() },
            textLines?.takeIf { it.isNotBlank() },
        ).distinct().joinToString("\n")

        val title = listOfNotNull(
            text(Notification.EXTRA_TITLE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                text(Notification.EXTRA_TITLE_BIG)
            } else {
                null
            },
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val messaging = extractMessaging(extras)

        val actionLabels = notification.actions
            ?.mapNotNull { it?.title?.toString()?.takeIf(String::isNotBlank) }
            ?.joinToString(", ")
            .orEmpty()

        val remoteInputs = notification.actions?.count { it?.remoteInputs?.isNotEmpty() == true } ?: 0

        // BigPictureStyle files its image under this key. The matching
        // constant is not part of the public SDK, so the key is used directly.
        val hasImage = extras?.containsKey("android.img") == true

        return NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = catalog.labelFor(sbn.packageName),
            title = title,
            body = body,
            subText = text(Notification.EXTRA_SUB_TEXT),
            sender = messaging.first,
            conversationTitle = messaging.second,
            groupKey = notification.group,
            postedAt = sbn.postTime,
            isOngoing = sbn.isOngoing,
            isClearable = notification.flags and Notification.FLAG_ONGOING_EVENT == 0,
            channelId = notification.channelId,
            category = notification.category,
            hasImage = hasImage,
            actions = actionLabels,
            remoteInputCount = remoteInputs,
            duringQuietHours = settings.isQuietNow(sbn.postTime),
        )
    }

    /**
     * Pulls the sender name and group title out of a MessagingStyle
     * notification, which is how WhatsApp, SMS and Slack label their messages.
     * Returns sender to group-title.
     */
    private fun extractMessaging(
        extras: android.os.Bundle?,
    ): Pair<String?, String?> {
        val conversation = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        // Notification.Message was replaced by MessagingStyle.Message, which
        // ships a helper that does the array conversion safely.
        val messages: List<Notification.MessagingStyle.Message> = try {
            @Suppress("DEPRECATION")
            val raw = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (raw == null) {
                emptyList()
            } else {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Walk backwards so the most recent message supplies the sender name.
        var sender: String? = null
        for (m in messages.asReversed()) {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                m.senderPerson?.name?.toString()
            } else {
                null
            } ?: m.sender?.toString()
            if (!name.isNullOrBlank()) {
                sender = name
                break
            }
        }
        return sender to conversation
    }

    // ------------------------------------------------------------- retention

    /**
     * Deletes old notifications, but at most once an hour. Running a DELETE on
     * every single captured notification would be wasteful on a busy device.
     */
    private suspend fun maybeSweep() {
        val now = System.currentTimeMillis()
        if (now - settings.lastSweepAt < 60L * 60L * 1000L) return
        settings.lastSweepAt = now
        runCatching { db.sweep(settings.retentionDays, settings.maxStored, now) }
    }
}
