# API & Interface Documentation
## NotificationVault — Android Notification Capture & History App

---

## 1. Public Data Models

### 1.1 `NotificationRecord`
**Package**: `com.notifvault.app.data`  
**File**: `Models.kt`

```kotlin
data class NotificationRecord(
    val id: Long = 0,                          // PK, auto-assigned by DB
    val key: String,                           // sbn.key — unique per notification
    val packageName: String,                   // Source app package
    val appLabel: String,                      // Resolved app label (from catalog)
    val title: String,                         // Notification title
    val body: String,                          // Merged body (bigText + text + textLines)
    val subText: String?,                      // Sub-text
    val sender: String?,                       // From MessagingStyle (sender name)
    val conversationTitle: String?,            // From MessagingStyle (group/conversation)
    val groupKey: String?,                     // Notification group key
    val postedAt: Long,                        // Epoch milliseconds
    val isOngoing: Boolean,                    // Ongoing notification (progress, etc.)
    val isClearable: Boolean,                  // User can swipe away
    val channelId: String?,                    // Notification channel ID
    val category: String?,                     // Notification category (CATEGORY_MESSAGE, etc.)
    val hasImage: Boolean,                     // BigPictureStyle image present
    val actions: String,                       // Comma-separated action labels
    val remoteInputCount: Int,                 // Count of actions with RemoteInput
    val duringQuietHours: Boolean,             // Captured during quiet-hours window
    var isRead: Boolean = false,               // User dealt with it (tapped/app-cancelled)
    var starred: Boolean = false,              // User starred
)
```

**Computed Properties** (extension functions):
```kotlin
// Formatted timestamp for UI
val NotificationRecord.displayTime: String
    get() = DateUtils.formatDateTime(context, postedAt, FORMAT_SHOW_TIME)

// Relative time (e.g., "2h ago")
val NotificationRecord.relativeTime: String
    get() = DateUtils.getRelativeTimeSpanString(postedAt).toString()

// Primary display text (title > sender > appLabel)
val NotificationRecord.primaryText: String
    get() = title.takeIf { it.isNotBlank() }
        ?: sender?.takeIf { it.isNotBlank() }
        ?: appLabel
```

---

### 1.2 `HistoryFilter`
**Package**: `com.notifvault.app.data`  
**File**: `Models.kt`

```kotlin
data class HistoryFilter(
    val text: String = "",                     // Search query (LIKE on 6 text columns)
    val packages: Set<String> = emptySet(),    // Package name filter
    val from: Long? = null,                    // posted_at >= from (epoch ms)
    val to: Long? = null,                      // posted_at <= to (epoch ms)
    val onlyUnread: Boolean = false,           // is_read = 0
    val onlyStarred: Boolean = false,          // starred = 1
    val hideQuietHours: Boolean = false,       // during_quiet = 0
)
```

**Usage**: Passed to `VaultDb.query()` and `VaultDb.countMatching()`.

---

### 1.3 `TrackMode`
**Package**: `com.notifvault.app.data`  
**File**: `TrackMode.kt`

```kotlin
enum class TrackMode {
    ALWAYS,       // Capture all notifications from this app
    UNREAD_ONLY,  // Capture only non-ongoing (typically unread) notifications
    NEVER,        // Never capture from this app
}
```

**Default**: `NEVER` for all apps (fresh install captures nothing until configured).

---

### 1.4 `AppEntry`
**Package**: `com.notifvault.app.data`  
**File**: `AppCatalog.kt`

```kotlin
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Icon?,              // Compose Icon (resolved lazily)
    var trackMode: TrackMode,
)
```

---

### 1.5 `AppCount` (Analytics)
**Package**: `com.notifvault.app.data`  
**File**: `VaultDb.kt`

```kotlin
data class AppCount(
    val packageName: String,
    val label: String,
    val count: Int,
)
```

---

### 1.6 `ExportFormat`
**Package**: `com.notifvault.app.util`  
**File**: `Exporter.kt`

```kotlin
enum class ExportFormat {
    CSV,    // text/csv
    JSON,   // application/json
}
```

---

## 2. Core Interfaces

### 2.1 `VaultDb`
**Package**: `com.notifvault.app.data`  
**File**: `VaultDb.kt`

```kotlin
class VaultDb private constructor(context: Context) : SQLiteOpenHelper(...) {

    // Singleton access
    companion object {
        @Volatile private var instance: VaultDb? = null
        fun get(context: Context): VaultDb = ...
    }

    // Reactive revision counter (bumped on every write)
    val revision: StateFlow<Long>

    // ─── Write ───

    /** Fire-and-forget insert/upsert on background executor */
    fun saveAsync(record: NotificationRecord)

    /** Synchronous insert/upsert (for tests, seeding) */
    fun saveSync(record: NotificationRecord)

    /** Toggle starred flag */
    fun setStarred(id: Long, value: Boolean)

    /** Toggle read flag by row ID */
    fun setRead(id: Long, value: Boolean)

    /** Toggle read flag by sbn key (used by service on removal) */
    fun setReadForKey(sbnKey: String, read: Boolean)

    /** Soft delete (marks is_deleted=1; not implemented in v1 — use hard delete) */
    fun delete(id: Long)

    /** Delete all rows (used by "Clear vault" in settings) */
    fun deleteAll()

    /** Retention sweep: age-based + count-based with tie-break */
    fun sweep(retentionDays: Int, maxStored: Int, now: Long): Int

    // ─── Read ───

    /** Total row count */
    fun countAll(): Int

    /** Paged query with filter */
    fun query(
        filter: HistoryFilter,
        limit: Int = PAGE_SIZE,   // 300
        offset: Int = 0,
    ): List<NotificationRecord>

    /** Count matching filter (COUNT(*) — no row materialisation) */
    fun countMatching(filter: HistoryFilter): Int

    /** Distinct apps in vault (packageName, label) */
    fun packagesInVault(): List<Pair<String, String>>

    /** Label for package (from most recent notification) */
    fun labelFor(packageName: String): String?

    /** Count for package since timestamp */
    fun countFor(packageName: String, since: Long): Int

    /** Top apps since timestamp (for Analytics) */
    fun topApps(since: Long, limit: Int = 20): List<AppCount>

    /** Hourly histogram since timestamp (24 buckets) */
    fun hourlyHistogram(since: Long): IntArray

    /** Daily counts between timestamps (for sparkline) */
    fun dailyCounts(since: Long, until: Long): List<Pair<String, Int>>

    /** Count in time window */
    fun countBetween(from: Long, to: Long): Int

    /** All records in time window (oldest first — for export) */
    fun exportRange(from: Long, to: Long): List<NotificationRecord>

    // ─── Constants ───
    const val PAGE_SIZE = 300
}
```

---

### 2.2 `SettingsStore`
**Package**: `com.notifvault.app.data`  
**File**: `SettingsStore.kt`

```kotlin
class SettingsStore private constructor(private val prefs: DataStore<Preferences>) {

    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore = ...
    }

    // ─── TrackMode ───

    /** Current mode for package (default NEVER) */
    fun modeFor(packageName: String): TrackMode

    /** Set mode for package */
    fun setMode(packageName: String, mode: TrackMode)

    // ─── Keyword Filter ───

    /** Current keyword filter (empty = disabled) */
    val keywordFilter: Flow<String>

    /** Update keyword filter */
    fun setKeywordFilter(text: String)

    // ─── Quiet Hours ───

    /** (startMinute, endMinute) or null if disabled */
    val quietHours: Flow<Pair<Int, Int>?>

    /** Set quiet hours (minute-of-day, 0-1439; null to disable) */
    fun setQuietHours(start: Int?, end: Int?)

    /** Check if timestamp falls in quiet hours */
    fun isQuietNow(timestamp: Long): Boolean

    // ─── Retention ───

    /** Max stored notifications (default 5000) */
    val maxStored: Flow<Int>

    fun setMaxStored(n: Int)

    /** Retention days (0 = disabled) */
    val retentionDays: Flow<Int>

    fun setRetentionDays(days: Int)

    /** Last sweep timestamp (for hourly guard) */
    var lastSweepAt: Long

    // ─── Master Toggle ───

    /** Global capture enabled (default true) */
    var masterEnabled: Boolean

    // ─── PIN / Biometric ───

    /** Whether PIN is set */
    fun hasPin(): Boolean

    /** Verify PIN (constant-time) */
    fun verifyPin(input: String): Boolean

    /** Set new PIN (hashes with PBKDF2 100k) */
    suspend fun setPin(newPin: String)

    /** Clear PIN */
    suspend fun clearPin()

    /** Biometric enabled */
    val biometricEnabled: Flow<Boolean>

    suspend fun setBiometricEnabled(enabled: Boolean)

    // ─── Export ───

    /** Export all non-deleted records as CSV/JSON via SAF */
    suspend fun exportAll(format: ExportFormat, uri: Uri): Boolean
}
```

---

### 2.3 `AppCatalog`
**Package**: `com.notifvault.app.data`  
**File**: `AppCatalog.kt`

```kotlin
class AppCatalog(private val context: Context) {

    /** All launchable apps (MAIN + LAUNCHER) with current TrackMode */
    suspend fun loadApps(settings: SettingsStore): List<AppEntry>

    /** Label for package (from PackageManager or vault) */
    fun labelFor(packageName: String): String

    /** Icon for package (loads async, returns placeholder immediately) */
    suspend fun iconFor(packageName: String): Icon
}
```

---

### 2.4 `VaultViewModel`
**Package**: `com.notifvault.app.ui`  
**File**: `VaultViewModel.kt`

```kotlin
class VaultViewModel @Inject constructor(
    private val db: VaultDb,
    private val settings: SettingsStore,
    private val catalog: AppCatalog,
) : ViewModel() {

    // ─── UI State ───

    /** Current filter (collect to react) */
    val filter = MutableStateFlow(HistoryFilter())

    /** Current tab index (0=History, 1=Analytics, 2=Apps, 3=Settings, 4=Lock) */
    var currentTab: Int = 0
        private set

    /** Loaded pages (each page = PAGE_SIZE items) */
    val loadedSpan = MutableStateFlow(1)

    /** Visible items (current page) */
    val items = MutableStateFlow<List<NotificationRecord>>(emptyList())

    /** Total matching count */
    val totalCount = MutableStateFlow(0)

    /** Loading indicator */
    val isLoading = MutableStateFlow(false)

    /** App locked state (collect in Activity) */
    val appLocked = MutableStateFlow(false)

    // ─── Actions ───

    /** Reload current span (keepSpan=true) or reset to page 1 (false) */
    fun reload(keepSpan: Boolean)

    /** Load next page (appends to items, increments loadedSpan) */
    fun loadMore()

    /** Update filter (triggers reload with keepSpan=false) */
    fun setFilter(newFilter: HistoryFilter)

    /** Toggle starred on a record */
    fun toggleStarred(record: NotificationRecord)

    /** Toggle read on a record */
    fun toggleRead(record: NotificationRecord)

    /** Delete a record */
    fun delete(record: NotificationRecord)

    /** Lock the app (shows LockScreen) */
    fun lock()

    /** Unlock with PIN */
    fun unlock(pin: String): Boolean

    /** Called when CaptureBus.revision changes */
    fun onRevisionChanged()

    // ─── Analytics ───

    /** Top apps for current filter/time range */
    val topApps: StateFlow<List<AppCount>>

    /** Hourly histogram */
    val hourlyHistogram: StateFlow<IntArray>

    /** Daily counts for sparkline */
    val dailyCounts: StateFlow<List<Pair<String, Int>>>
}
```

---

### 2.5 `CaptureBus`
**Package**: `com.notifvault.app.service`  
**File**: `NotificationCaptureService.kt`

```kotlin
object CaptureBus {
    /** Listener connected to system */
    val connected: StateFlow<Boolean>

    /** Total notifications captured this session */
    val capturedCount: StateFlow<Int>

    /** Called by service on connect/disconnect */
    fun markConnected(value: Boolean)

    /** Called after each successful capture */
    fun noteCaptured()
}
```

---

## 3. Service API

### 3.1 `NotificationCaptureService`
**Package**: `com.notifvault.app.service`  
**File**: `NotificationCaptureService.kt`

```kotlin
class NotificationCaptureService : NotificationListenerService() {

    // System callbacks (do not call directly)

    override fun onCreate()
    override fun onDestroy()
    override fun onListenerConnected()
    override fun onListenerDisconnected()
    override fun onNotificationPosted(sbn: StatusBarNotification?)
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?)

    // Internal (public for testing)

    /** Flatten sbn → NotificationRecord (returns null if filtered) */
    fun extract(sbn: StatusBarNotification): NotificationRecord?

    /** Parse MessagingStyle for sender/conversation */
    fun extractMessaging(extras: Bundle?): Pair<String?, String?>
}
```

**Manifest Declaration** (required):
```xml
<service
    android:name=".service.NotificationCaptureService"
    android:exported="true"
    android:label="@string/app_name"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

---

## 4. Utility APIs

### 4.1 `PinHasher`
**Package**: `com.notifvault.app.util`  
**File**: `PinHasher.kt`

```kotlin
object PinHasher {
    /** Hash PIN → (base64Hash, base64Salt) */
    fun hash(pin: String): Pair<String, String>

    /** Verify PIN against stored hash/salt (constant-time) */
    fun verify(pin: String, hashB64: String, saltB64: String): Boolean
}
```

**Algorithm**: PBKDF2-HMAC-SHA256, 100,000 iterations, 256-bit key, 128-bit salt.

---

### 4.2 `Exporter`
**Package**: `com.notifvault.app.util`  
**File**: `Exporter.kt`

```kotlin
object Exporter {

    /** Launcher for SAF document creation (use in Compose) */
    @Composable
    fun ExportLauncher(
        db: VaultDb,
        format: ExportFormat,
        onComplete: (Boolean) -> Unit
    ): () -> Unit  // Returns launch function

    /** Direct export to URI (for tests) */
    suspend fun exportToUri(
        db: VaultDb,
        format: ExportFormat,
        uri: Uri,
        from: Long = 0,
        to: Long = Long.MAX_VALUE
    ): Boolean
}
```

**CSV Columns** (in order):
```
id,key,package,label,title,body,sub_text,sender,conversation,
group_key,posted_at,is_ongoing,is_clearable,channel,category,
has_image,actions,remote_inputs,during_quiet,is_read,starred
```

**JSON Structure**:
```json
[
  {
    "id": 1,
    "key": "sbn-key",
    "package": "com.app",
    "label": "App Name",
    "title": "Title",
    "body": "Body text",
    "sub_text": "Sub text",
    "sender": "John",
    "conversation": "Group",
    "group_key": "grp-1",
    "posted_at": 1699999999000,
    "is_ongoing": false,
    "is_clearable": true,
    "channel": "channel_id",
    "category": "msg",
    "has_image": false,
    "actions": "Reply, Mark read",
    "remote_inputs": 1,
    "during_quiet": false,
    "is_read": false,
    "starred": false
  }
]
```

---

### 4.3 `NotificationAccess`
**Package**: `com.notifvault.app.util`  
**File**: `NotificationAccess.kt`

```kotlin
object NotificationAccess {
    /** Open system Notification Access settings */
    fun open(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
```

---

## 5. Compose UI Components (Public)

### 5.1 Screen Composables

| Composable | Package | Parameters |
|------------|---------|------------|
| `HistoryScreen` | `ui.screens` | `viewModel: VaultViewModel` |
| `AnalyticsScreen` | `ui.screens` | `viewModel: VaultViewModel` |
| `AppRulesScreen` | `ui.screens` | `viewModel: VaultViewModel` |
| `SettingsScreen` | `ui.screens` | `viewModel: VaultViewModel` |
| `LockScreen` | `ui.screens` | `onUnlocked: () -> Unit` |
| `LockSettingsScreen` | `ui.screens` | `viewModel: VaultViewModel` |

### 5.2 Shared Components (`ui.components.Common`)

```kotlin
// Notification row (used in HistoryScreen)
@Composable
fun NotificationRow(
    record: NotificationRecord,
    expanded: Boolean,
    onClick: () -> Unit,
    onStarClick: () -> Unit,
    onReadClick: () -> Unit,
)

// App row (used in AppRulesScreen)
@Composable
fun AppRow(
    entry: AppEntry,
    onModeChange: (TrackMode) -> Unit,
)

// Filter chips (used in HistoryScreen)
@Composable
fun FilterChips(
    filter: HistoryFilter,
    onClear: () -> Unit,
    onPackageToggle: (String) -> Unit,
)

// Empty state illustration
@Composable
fun EmptyStateIllustration()

// Loading indicator
@Composable
fun CenteredCircularProgressIndicator()
```

---

## 6. Extension Points (For Future Features)

### 6.1 Adding a New Filter Field
1. Add field to `HistoryFilter` data class
2. Update `VaultDb.buildWhere()` to generate WHERE clause
3. Add UI control in `HistoryScreen` filter bar
4. Update `VaultViewModel` to include in filter state

### 6.2 Adding a New TrackMode
1. Add value to `TrackMode` enum
2. Update `SettingsStore.modeFor()` / `setMode()`
3. Update `NotificationCaptureService.handlePosted()` when branch
4. Add UI in `AppRulesScreen`

### 6.3 Adding a New Analytics Metric
1. Add query method to `VaultDb` (e.g., `fun newMetric(): List<MetricRow>`)
2. Add `StateFlow` in `VaultViewModel` collecting from DB
3. Create composable in `AnalyticsScreen`

### 6.4 Adding Database Encryption (SQLCipher)
1. Replace `SQLiteOpenHelper` with `net.sqlcipher.database.SQLiteOpenHelper`
2. Pass PIN-derived key to `getWritableDatabase(key)`
3. Migrate existing DB on first unlock
4. Update `VaultDb` interface — no callers change

### 6.5 Adding Sync (WebDAV/Local)
1. Add `sync_status` column (PENDING/SYNCED/CONFLICT)
2. Add `SyncWorker` (WorkManager) for background sync
3. Add conflict resolution (last-write-wins or manual)
4. Add sync settings in `SettingsScreen`

---

## 7. System Intents & Permissions

### 7.1 Required Permissions (Manifest)
```xml
<!-- Post own status notification (API 33+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Query launchable apps for labels/icons -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

### 7.2 Declared but Not Requested
- **No `INTERNET`** — app cannot make network requests
- **No storage permissions** — export uses SAF (`CreateDocument`)

### 7.3 System Intents Used
| Intent | Purpose |
|--------|---------|
| `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` | Open Notification Access grant screen |
| `Intent.ACTION_CREATE_DOCUMENT` (SAF) | Export file picker |
| `BiometricPrompt` | Biometric authentication |

---

## 8. Database Schema (Reference)

```sql
-- Table: notifications
CREATE TABLE notifications (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    sbn_key          TEXT    NOT NULL UNIQUE,     -- StatusBarNotification.key
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
);

-- Indexes
CREATE INDEX idx_posted_at ON notifications(posted_at DESC);
CREATE INDEX idx_package ON notifications(package_name, posted_at DESC);
CREATE INDEX idx_starred ON notifications(starred, posted_at DESC);
```

---

## 9. Constants & Defaults

| Constant | Value | Location |
|----------|-------|----------|
| `PAGE_SIZE` | 300 | `VaultDb` |
| `DEFAULT_MAX_STORED` | 5000 | `SettingsStore` |
| `DEFAULT_RETENTION_DAYS` | 0 (disabled) | `SettingsStore` |
| `PIN_ITERATIONS` | 100,000 | `PinHasher` |
| `PIN_KEY_LENGTH` | 256 bits | `PinHasher` |
| `PIN_SALT_LENGTH` | 128 bits | `PinHasher` |
| `SWEEP_INTERVAL_MS` | 3,600,000 (1 hour) | `NotificationCaptureService` |
| `QUIET_HOURS_DISABLED` | -1 | `SettingsStore` |

---

## 10. Error Codes & Exceptions

| Scenario | Handling |
|----------|----------|
| DB insert fails | Logged, revision not bumped, capture continues |
| DB query fails | Returns empty list, UI shows error toast |
| Settings read fails | Returns defaults, logs warning |
| Export fails | Returns false, UI shows error toast |
| Biometric unavailable | Falls back to PIN automatically |
| Listener not granted | UI shows banner with settings shortcut |

---

## 11. Versioning & Compatibility

| Version | API Level | Notes |
|---------|-----------|-------|
| v1.0 | 26–35 | Initial release |
| compileSdk | 37 | Android 15 SDK |
| minSdk | 26 | Android 8.0 (Oreo) |
| targetSdk | 37 | Android 15 |

**Schema Version**: 1 (in `VaultDb.DB_VERSION`) — `onUpgrade()` is no-op for v1.

---

## 12. ProGuard / R8 Rules

```proguard
# Keep data classes for serialization
-keep class com.notifvault.app.data.** { *; }

# Keep service for system binding
-keep class com.notifvault.app.service.NotificationCaptureService { *; }

# Keep PinHasher for reflection (if used)
-keep class com.notifvault.app.util.PinHasher { *; }

# Compose runtime
-keep class androidx.compose.runtime.** { *; }
```

**Note**: `isMinifyEnabled = false` in debug and release (per `build.gradle.kts`). Enable only if APK size critical.

---

## 13. Changelog Template

```markdown
## [1.0.0] - YYYY-MM-DD

### Added
- Initial release: notification capture, history, search, pagination
- Per-app TrackMode (ALWAYS/UNREAD_ONLY/NEVER)
- Keyword filter, quiet hours
- PIN + biometric app lock
- CSV/JSON export via SAF
- Analytics (top apps, hourly, daily)
- Retention sweep (age + count with tie-break)

### Known Limitations
- No database encryption at rest
- No reply-from-history
- No cloud sync
- LIKE-based search (not FTS)
```

---

## 14. License

```
MIT License

Copyright (c) 2026 NotificationVault Contributors

Permission is hereby granted...
```

---

## 15. Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Run tests: `./gradlew testDebugUnitTest connectedDebugAndroidTest`
4. Run lint: `./gradlew lintDebug`
5. Commit changes: `git commit -m 'Add amazing feature'`
6. Push: `git push origin feature/amazing-feature`
7. Open Pull Request

**Code Style**: Follows Kotlin official style guide + project conventions in `IMPLEMENTATION.md`.