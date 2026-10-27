package com.notifvault.app.data

/** How aggressively a single app is captured. */
enum class TrackMode {
    /** Store every notification this app posts. */
    ALWAYS,

    /** Only store the app's notifications while they are still unread. */
    UNREAD_ONLY,

    /** Never store anything from this app. */
    NEVER;

    companion object {
        fun fromName(raw: String?): TrackMode =
            entries.firstOrNull { it.name == raw } ?: ALWAYS
    }
}

/**
 * One captured notification. This is the single record type the whole app
 * works with: the capture service creates it, the database stores it, and
 * every screen renders it.
 */
data class NotificationRecord(
    /** Row id. 0 until the database assigns one. */
    val id: Long = 0,
    /** StatusBarNotification.key - stable per posted notification, used to dedupe. */
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val subText: String? = null,
    /** Sender name for messaging-style notifications (WhatsApp, SMS, Slack...). */
    val sender: String? = null,
    /** Group / channel title, e.g. a WhatsApp group name. */
    val conversationTitle: String? = null,
    val groupKey: String? = null,
    val postedAt: Long,
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    val channelId: String? = null,
    val category: String? = null,
    val hasImage: Boolean = false,
    /** Comma-joined action labels, e.g. "Reply, Mark as read, Mute". */
    val actions: String = "",
    /** How many of those actions accept a typed reply. */
    val remoteInputCount: Int = 0,
    val duringQuietHours: Boolean = false,
    val isRead: Boolean = false,
    val starred: Boolean = false,
) {
    /** One-line summary used in the timeline and in exports. */
    fun searchableText(): String = buildString {
        append(title).append(' ')
        append(body).append(' ')
        append(subText.orEmpty()).append(' ')
        append(sender.orEmpty()).append(' ')
        append(conversationTitle.orEmpty()).append(' ')
        append(appLabel)
    }
}

/** Everything the History screen can narrow the list down by. */
data class HistoryFilter(
    val text: String = "",
    val packages: Set<String> = emptySet(),
    val from: Long? = null,
    val to: Long? = null,
    val onlyUnread: Boolean = false,
    val onlyStarred: Boolean = false,
    val hideQuietHours: Boolean = false,
) {
    val isActive: Boolean
        get() = text.isNotBlank() || packages.isNotEmpty() || from != null ||
            to != null || onlyUnread || onlyStarred || hideQuietHours
}

/** A row in the "which apps notify me the most" report. */
data class AppCount(
    val packageName: String,
    val appLabel: String,
    val count: Int,
)
