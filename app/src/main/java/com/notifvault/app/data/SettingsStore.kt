package com.notifvault.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * All user preferences, in one place.
 *
 * Backed by SharedPreferences rather than DataStore on purpose: every read here
 * happens on a hot path (the capture service checks the master switch and the
 * per-app rules for every notification that arrives), and SharedPreferences
 * keeps that synchronous and cheap.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- capture

    /** Master switch. When false nothing is captured at all. */
    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    // ----------------------------------------------------------------- rules

    /** Per-app capture rules, stored as "packageName|TRACK_MODE" strings. */
    fun modeFor(packageName: String): TrackMode {
        val raw = prefs.getString(KEY_RULES, null) ?: return TrackMode.ALWAYS
        val prefix = "$packageName|"
        val hit = raw.split('\n').firstOrNull { it.startsWith(prefix) } ?: return TrackMode.ALWAYS
        return TrackMode.fromName(hit.removePrefix(prefix))
    }

    fun allRules(): Map<String, TrackMode> =
        (prefs.getString(KEY_RULES, null) ?: "")
            .split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val idx = line.lastIndexOf('|')
                if (idx <= 0) null
                else line.substring(0, idx) to TrackMode.fromName(line.substring(idx + 1))
            }
            .toMap()

    fun setMode(packageName: String, mode: TrackMode) {
        val updated = allRules().toMutableMap().apply { put(packageName, mode) }
        writeRules(updated)
    }

    private fun writeRules(rules: Map<String, TrackMode>) {
        val encoded = rules.entries.joinToString("\n") { "${it.key}|${it.value.name}" }
        prefs.edit().putString(KEY_RULES, encoded).apply()
    }

    // --------------------------------------------------------------- filters

    /**
     * Keywords that cause a notification to be dropped before it is stored.
     * Compared case-insensitively against the full text of the notification.
     */
    var ignoreKeywords: Set<String>
        get() = prefs.getStringSet(KEY_IGNORE_KEYWORDS, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_IGNORE_KEYWORDS, value).apply()

    fun isIgnored(record: NotificationRecord): Boolean {
        val keywords = ignoreKeywords
        if (keywords.isEmpty()) return false
        val haystack = record.searchableText().lowercase()
        return keywords.any { it.isNotBlank() && haystack.contains(it.lowercase()) }
    }

    /** Apps whose notifications are still captured but hidden from the main list. */
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, value).apply()

    // ---------------------------------------------------------- quiet hours

    /**
     * Notifications arriving inside the quiet window are still stored, but are
     * tagged so the History screen can fold them away unless asked.
     */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUIET_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET_ENABLED, value).apply()

    /** Quiet window start, minutes since midnight. */
    var quietStartMinutes: Int
        get() = prefs.getInt(KEY_QUIET_START, 22 * 60)
        set(value) = prefs.edit().putInt(KEY_QUIET_START, value).apply()

    /** Quiet window end, minutes since midnight. */
    var quietEndMinutes: Int
        get() = prefs.getInt(KEY_QUIET_END, 7 * 60)
        set(value) = prefs.edit().putInt(KEY_QUIET_END, value).apply()

    fun isQuietNow(epochMillis: Long): Boolean {
        if (!quietHoursEnabled) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        val start = quietStartMinutes
        val end = quietEndMinutes
        // A window that wraps past midnight (22:00 -> 07:00) is the normal case.
        return if (start <= end) minuteOfDay in start until end
        else minuteOfDay >= start || minuteOfDay < end
    }

    // ------------------------------------------------------------- retention

    /** Notifications older than this are deleted. 0 means keep forever. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    /** Hard cap on stored rows, applied after the age-based cleanup. */
    var maxStored: Int
        get() = prefs.getInt(KEY_MAX_STORED, 5000)
        set(value) = prefs.edit().putInt(KEY_MAX_STORED, value).apply()

    // ------------------------------------------------------------------ lock

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var biometricUnlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    /** PBKDF2 hash of the PIN, or an empty string when no PIN is set. */
    var pinHash: String
        get() = prefs.getString(KEY_PIN_HASH, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    // ----------------------------------------------------------------- misc

    var hasSeenWelcome: Boolean
        get() = prefs.getBoolean(KEY_SEEN_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEN_WELCOME, value).apply()

    /** Last time the retention sweep ran, so it costs at most one query an hour. */
    var lastSweepAt: Long
        get() = prefs.getLong(KEY_LAST_SWEEP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SWEEP, value).apply()

    companion object {
        private const val NAME = "notification_vault_settings"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_RULES = "app_rules"
        private const val KEY_IGNORE_KEYWORDS = "ignore_keywords"
        private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        private const val KEY_QUIET_ENABLED = "quiet_enabled"
        private const val KEY_QUIET_START = "quiet_start"
        private const val KEY_QUIET_END = "quiet_end"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_MAX_STORED = "max_stored"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_SEEN_WELCOME = "seen_welcome"
        private const val KEY_LAST_SWEEP = "last_sweep"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
