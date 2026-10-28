package com.notifvault.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The local vault: a plain SQLite database holding every captured notification.
 *
 * Why hand-written SQLite rather than Room:
 *  - Room's annotation processor (KSP) has to be version-matched against the
 *    Kotlin plugin on every single upgrade, and a mismatch breaks the build for
 *    a beginner with no obvious error message.
 *  - The schema here is one table and a handful of queries, so the Room
 *    abstraction buys very little.
 * Everything is local. No network permission is declared anywhere in this app.
 */
class VaultDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    /**
     * Bumped on every write. The UI collects this and re-queries, which is how
     * new notifications appear in the History list without a manual refresh.
     */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    // ThreadFactory, not a lambda: ExecutorService has many methods, so it
    // cannot be SAM-converted.
    private val writeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor(
            java.util.concurrent.ThreadFactory { runnable ->
                Thread(runnable, "vault-writer").apply { isDaemon = true }
            },
        )

    private fun touch() {
        _revision.value = _revision.value + 1
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                sbn_key          TEXT    NOT NULL UNIQUE,
                package_name     TEXT    NOT NULL,
                app_label        TEXT    NOT NULL,
                title            TEXT    NOT NULL DEFAULT '',
                body             TEXT    NOT NULL DEFAULT '',
                sub_text         TEXT,
                sender           TEXT,
                conversation     TEXT,
                group_key        TEXT,
                posted_at        INTEGER NOT NULL,
                is_ongoing       INTEGER NOT NULL DEFAULT 0,
                is_clearable     INTEGER NOT NULL DEFAULT 1,
                channel_id       TEXT,
                category         TEXT,
                has_image        INTEGER NOT NULL DEFAULT 0,
                actions          TEXT    NOT NULL DEFAULT '',
                remote_inputs    INTEGER NOT NULL DEFAULT 0,
                during_quiet     INTEGER NOT NULL DEFAULT 0,
                is_read          INTEGER NOT NULL DEFAULT 0,
                starred          INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        // The History list is always ordered newest-first and is almost always
        // scoped to one app, so both columns get an index.
        db.execSQL("CREATE INDEX idx_posted_at ON $TABLE (posted_at DESC)")
        db.execSQL("CREATE INDEX idx_package ON $TABLE (package_name, posted_at DESC)")
        db.execSQL("CREATE INDEX idx_starred ON $TABLE (starred, posted_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipped schema, so there is nothing to migrate yet.
        // Future versions add ALTER TABLE statements here.
    }

    // ------------------------------------------------------------------ write

    /**
     * Inserts a notification, or refreshes the existing row with the same key.
     *
     * Apps routinely post the *same* notification several times (a progress
     * update, a re-fired message, a redelivered push), and each re-post arrives
     * here as another callback with an unchanged key. Upserting on that key
     * keeps one row per real-world notification instead of a pile of duplicates.
     */
    fun saveAsync(record: NotificationRecord) {
        writeExecutor.execute {
            try { saveSync(record) } catch (e: Exception) { /* ignore */ }
        }
    }

    fun saveSync(record: NotificationRecord) {
        val values = ContentValues().apply {
            put("sbn_key", record.key)
            put("package_name", record.packageName)
            put("app_label", record.appLabel)
            put("title", record.title)
            put("body", record.body)
            put("sub_text", record.subText)
            put("sender", record.sender)
            put("conversation", record.conversationTitle)
            put("group_key", record.groupKey)
            put("posted_at", record.postedAt)
            put("is_ongoing", if (record.isOngoing) 1 else 0)
            put("is_clearable", if (record.isClearable) 1 else 0)
            put("channel_id", record.channelId)
            put("category", record.category)
            put("has_image", if (record.hasImage) 1 else 0)
            put("actions", record.actions)
            put("remote_inputs", record.remoteInputCount)
            put("during_quiet", if (record.duringQuietHours) 1 else 0)
            put("is_read", if (record.isRead) 1 else 0)
            put("starred", if (record.starred) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        touch()
    }

    /**
     * Writes one of the two boolean columns. The column name is a constant
     * chosen by the two wrappers below, never anything from a query string or
     * a user-facing value, so it cannot be steered by input.
     */
    private fun setFlagAsync(id: Long, column: String, value: Boolean) {
        writeExecutor.execute {
            try {
                val values = ContentValues().apply { put(column, if (value) 1 else 0) }
                writableDatabase.update(TABLE, values, "id = ?", arrayOf(id.toString()))
                touch()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun setStarred(id: Long, value: Boolean) = setFlagAsync(id, COLUMN_STARRED, value)

    fun setRead(id: Long, value: Boolean) = setFlagAsync(id, COLUMN_READ, value)

    fun setReadForKey(sbnKey: String, read: Boolean) {
        writeExecutor.execute {
            try {
                val values = ContentValues().apply { put("is_read", if (read) 1 else 0) }
                writableDatabase.update(TABLE, values, "sbn_key = ?", arrayOf(sbnKey))
                touch()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun delete(id: Long) {
        writeExecutor.execute {
            try {
                writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
                touch()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun deleteAll() {
        writeExecutor.execute {
            try {
                writableDatabase.delete(TABLE, null, null)
                touch()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    /**
     * Retention sweep: drops anything past the age limit, then trims the oldest
     * rows until the table is back under the hard cap. Returns rows removed.
     */
    fun sweep(retentionDays: Int, maxStored: Int, now: Long): Int = try {
        var removed = 0
        if (retentionDays > 0) {
            val cutoff = now - retentionDays * 24L * 60L * 60L * 1000L
            removed += writableDatabase.delete(TABLE, "posted_at < ?", arrayOf(cutoff.toString()))
        }
        if (maxStored > 0) {
            // countAll() runs after the retention delete above, so the two
            // passes cannot both claim the same row.
            val count = countAll()
            if (count > maxStored) {
                var trimmed = 0
                val boundary = timestampAtOffset(maxStored - 1)
                if (boundary != null) {
                    // Strictly older rows are the bulk, and posted_at is
                    // indexed, so this is a range delete rather than a sort of
                    // the whole table into a temporary set.
                    trimmed += writableDatabase.delete(
                        TABLE,
                        "posted_at < ?",
                        arrayOf(boundary.toString()),
                    )
                    // Notifications sharing the cut-off second are a tie the
                    // ordering cannot break, so drop exactly as many of those
                    // as we are still over the cap by.
                    val excess = count - maxStored - trimmed
                    if (excess > 0) {
                        trimmed += writableDatabase.delete(
                            TABLE,
                            "id IN (SELECT id FROM $TABLE WHERE posted_at = ? ORDER BY id ASC LIMIT ?)",
                            arrayOf(boundary.toString(), excess.toString()),
                        )
                    }
                }
                removed += trimmed
            }
        }
        if (removed > 0) touch()
        removed
    } catch (e: Exception) {
        0
    }

    // ------------------------------------------------------------------- read

    fun countAll(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Runs the History screen's query.
     *
     * Text search uses LIKE rather than SQLite FTS. With a retention-capped
     * vault (a few thousand rows) this returns in well under a millisecond, and
     * it removes the FTS content-sync triggers, which are the usual source of
     * subtle "search misses rows" bugs.
     */
    fun query(
        filter: HistoryFilter,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): List<NotificationRecord> {
        val (where, whereArgs) = buildWhere(filter)

        val sql = buildString {
            append("SELECT * FROM $TABLE")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY posted_at DESC LIMIT ? OFFSET ?")
        }
        val args = whereArgs + limit.toString() + offset.toString()

        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.toRecord()) }
        }
    }

    /**
     * How many rows a filter matches, counted by SQLite.
     *
     * This replaced `query(filter, limit = 10_000).size`, which allocated one
     * complete NotificationRecord - every string field - per matching row, on
     * every keystroke in the search box.
     */
    fun countMatching(filter: HistoryFilter): Int {
        val (where, args) = buildWhere(filter)
        val sql = buildString {
            append("SELECT COUNT(*) FROM $TABLE")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
        }
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Turns a filter into a WHERE clause plus its bind arguments. */
    private fun buildWhere(filter: HistoryFilter): Pair<List<String>, List<String>> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()

        val term = filter.text.trim()
        if (term.isNotEmpty()) {
            where += "(title LIKE ? OR body LIKE ? OR sub_text LIKE ? OR sender LIKE ? " +
                "OR conversation LIKE ? OR app_label LIKE ?)"
            val like = "%$term%"
            repeat(6) { args += like }
        }
        if (filter.packages.isNotEmpty()) {
            where += "package_name IN (${filter.packages.joinToString(",") { "?" }})"
            args += filter.packages.toList()
        }
        filter.from?.let { where += "posted_at >= ?"; args += it.toString() }
        filter.to?.let { where += "posted_at <= ?"; args += it.toString() }
        if (filter.onlyUnread) where += "is_read = 0"
        if (filter.onlyStarred) where += "starred = 1"
        if (filter.hideQuietHours) where += "during_quiet = 0"

        return where to args
    }

    /** posted_at of the row [offset] places down the newest-first ordering. */
    private fun timestampAtOffset(offset: Int): Long? =
        readableDatabase.rawQuery(
            "SELECT posted_at FROM $TABLE ORDER BY posted_at DESC LIMIT 1 OFFSET ?",
            arrayOf(offset.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Distinct apps present in the vault, alphabetically by label. */
    fun packagesInVault(): List<Pair<String, String>> =
        readableDatabase.rawQuery(
            "SELECT package_name, MAX(app_label) FROM $TABLE GROUP BY package_name " +
                "ORDER BY MAX(app_label) COLLATE NOCASE ASC",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }

    fun labelFor(packageName: String): String? =
        readableDatabase.rawQuery(
            "SELECT app_label FROM $TABLE WHERE package_name = ? LIMIT 1",
            arrayOf(packageName),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun countFor(packageName: String, since: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE package_name = ? AND posted_at >= ?",
            arrayOf(packageName, since.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Busiest apps since [since], for the Analytics screen. */
    fun topApps(since: Long, limit: Int = 20): List<AppCount> =
        readableDatabase.rawQuery(
            "SELECT package_name, MAX(app_label) AS label, COUNT(*) AS n FROM $TABLE " +
                "WHERE posted_at >= ? GROUP BY package_name ORDER BY n DESC LIMIT ?",
            arrayOf(since.toString(), limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(AppCount(c.getString(0), c.getString(1), c.getInt(2)))
                }
            }
        }

    /** How many notifications landed in each hour-of-day bucket since [since]. */
    fun hourlyHistogram(since: Long): IntArray {
        val buckets = IntArray(24)
        readableDatabase.rawQuery(
            "SELECT CAST(strftime('%H', posted_at / 1000, 'unixepoch', 'localtime') AS INTEGER) " +
                "AS h, COUNT(*) FROM $TABLE WHERE posted_at >= ? GROUP BY h",
            arrayOf(since.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val hour = c.getInt(0)
                if (hour in 0..23) buckets[hour] = c.getInt(1)
            }
        }
        return buckets
    }

    /** Per-day counts, oldest first, for the analytics sparkline. */
    fun dailyCounts(since: Long, until: Long): List<Pair<String, Int>> =
        readableDatabase.rawQuery(
            "SELECT date(posted_at / 1000, 'unixepoch', 'localtime') AS d, COUNT(*) " +
                "FROM $TABLE WHERE posted_at >= ? AND posted_at <= ? GROUP BY d ORDER BY d ASC",
            arrayOf(since.toString(), until.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) }
        }

    fun countBetween(from: Long, to: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE posted_at >= ? AND posted_at <= ?",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Every record in a time window, oldest first - used by the exporters. */
    fun exportRange(from: Long, to: Long): List<NotificationRecord> =
        readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE posted_at >= ? AND posted_at <= ? ORDER BY posted_at ASC",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toRecord()) } }

    // -------------------------------------------------------------- internal

    private fun Cursor.toRecord(): NotificationRecord = NotificationRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        key = getString(getColumnIndexOrThrow("sbn_key")),
        packageName = getString(getColumnIndexOrThrow("package_name")),
        appLabel = getString(getColumnIndexOrThrow("app_label")),
        title = getString(getColumnIndexOrThrow("title")),
        body = getString(getColumnIndexOrThrow("body")),
        subText = nullableString("sub_text"),
        sender = nullableString("sender"),
        conversationTitle = nullableString("conversation"),
        groupKey = nullableString("group_key"),
        postedAt = getLong(getColumnIndexOrThrow("posted_at")),
        isOngoing = getInt(getColumnIndexOrThrow("is_ongoing")) == 1,
        isClearable = getInt(getColumnIndexOrThrow("is_clearable")) == 1,
        channelId = nullableString("channel_id"),
        category = nullableString("category"),
        hasImage = getInt(getColumnIndexOrThrow("has_image")) == 1,
        actions = getString(getColumnIndexOrThrow("actions")),
        remoteInputCount = getInt(getColumnIndexOrThrow("remote_inputs")),
        duringQuietHours = getInt(getColumnIndexOrThrow("during_quiet")) == 1,
        isRead = getInt(getColumnIndexOrThrow("is_read")) == 1,
        starred = getInt(getColumnIndexOrThrow("starred")) == 1,
    )

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        private const val DB_NAME = "notification_vault.db"
        private const val DB_VERSION = 1
        private const val TABLE = "notifications"

        /** Rows fetched per History page; the screen loads more on scroll. */
        const val PAGE_SIZE = 300

        private const val COLUMN_STARRED = "starred"
        private const val COLUMN_READ = "is_read"

        @Volatile
        private var instance: VaultDb? = null

        fun get(context: Context): VaultDb =
            instance ?: synchronized(this) {
                instance ?: VaultDb(context).also { instance = it }
            }
    }
}
