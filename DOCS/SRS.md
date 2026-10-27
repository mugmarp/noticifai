# Software Requirements Specification (SRS)
## NotificationVault — Android Notification Capture & History App

---

## 1. Introduction

### 1.1 Purpose
NotificationVault is an Android application that captures, stores, and presents a searchable history of **all notifications** posted on the device. It operates as a `NotificationListenerService` — the system delivers every notification to the service regardless of source app. The app provides per-app tracking rules, keyword filtering, quiet-hours suppression, PIN/biometric app lock, and local-only export.

### 1.2 Scope
- **In scope**: Capture all notifications (API 26+), persist to local SQLite, Compose UI with 5 tabs, app-lock, export via Storage Access Framework.
- **Out of scope**: Cloud sync, reply-from-history, media gallery, database encryption at rest, FTS search, Tasker hooks, scheduled digests.

### 1.3 Definitions
| Term | Meaning |
|------|---------|
| `TrackMode` | Per-app rule: `ALWAYS` \| `UNREAD_ONLY` \| `NEVER` |
| `NotificationRecord` | Flattened notification row stored in SQLite |
| `Revision` | Monotonic counter bumped on each capture; drives UI refresh |
| `CaptureBus` | In-process event bus (StateFlow) for revision propagation |

### 1.4 References
- Android `NotificationListenerService` API (minSdk 26)
- Compose BOM 2026.09.00, Material3
- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.20, JDK 17

---

## 2. Functional Requirements

### 2.1 Capture Pipeline
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-CAP-01 | Service receives `onNotificationPosted(sbn)` for all notifications except own package | P0 |
| FR-CAP-02 | `extract(sbn)` flattens to `NotificationRecord` (title, text, bigText, subText, summaryText, sender, conversation, package, channel, postedAt, group, isGroupSummary, remoteInputCount, actionCount, hasImage, largeIconUri) | P0 |
| FR-CAP-03 | `extractMessaging()` parses `MessagingStyle` for sender/conversation metadata | P0 |
| FR-CAP-04 | Per-app `TrackMode` applied: ALWAYS=store all, UNREAD_ONLY=store only if unread, NEVER=skip | P0 |
| FR-CAP-05 | Keyword filter (case-insensitive substring match on text fields) | P1 |
| FR-CAP-06 | Quiet-hours filter (suppress capture during configured window) | P1 |
| FR-CAP-07 | Own-package exclusion to prevent capture loops | P0 |
| FR-CAP-08 | `onNotificationRemoved(sbn, reason)` updates read-state per reason table (see HANDOFF.md F1) | P0 |

### 2.2 Storage
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-STO-01 | SQLite table `notifications` with columns matching `NotificationRecord` + `read`, `starred`, `deleted` flags | P0 |
| FR-STO-02 | `VaultDb.insert(record)` on background single-thread executor | P0 |
| FR-STO-03 | `query(filter, limit, offset)` returns page of records ordered by `posted_at DESC` | P0 |
| FR-STO-04 | `countMatching(filter)` returns `SELECT COUNT(*)` without materialising rows | P0 |
| FR-STO-05 | Retention sweep: keep max N rows (default 5000), delete oldest by `posted_at` with tie-handling | P1 |
| FR-STO-06 | `setStarred(id, value)`, `setRead(id, value)` — column names are private constants (no injection) | P1 |

### 2.3 State & UI
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-UI-01 | `VaultViewModel` collects `VaultDb.revision: StateFlow<Long>` and re-queries | P0 |
| FR-UI-02 | Pagination: `loadedSpan` extends via `loadMore()` when last visible item within 5 of end | P0 |
| FR-UI-03 | New capture re-reads **currently loaded span** (preserves scroll position) | P0 |
| FR-UI-04 | Filter change resets to page 1 | P0 |
| FR-UI-05 | Reloads serialised behind `Mutex` (revision + filter collectors can overlap) | P0 |
| FR-UI-06 | 5 tabs: History, Analytics, App Rules, Settings, Lock | P0 |
| FR-UI-07 | App lock: PIN (PBKDF2-HMAC-SHA256, 100k iterations) + BiometricPrompt | P1 |
| FR-UI-08 | Export via `CreateDocument` (SAF) — CSV or JSON | P1 |

### 2.4 Permissions & Manifest
| ID | Requirement | Priority |
|----|-------------|----------|
| FR-PER-01 | `BIND_NOTIFICATION_LISTENER_SERVICE` on service | P0 |
| FR-PER-02 | `POST_NOTIFICATIONS` (API 33+) for own status notification | P0 |
| FR-PER-03 | `<queries>` for launchable apps (resolve label/icon) | P0 |
| FR-PER-04 | **No `INTERNET` permission** — app physically cannot upload | P0 |

---

## 3. Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-PER-01 | Capture-to-DB latency | < 100 ms p95 |
| NFR-PER-02 | History screen initial render (50 items) | < 300 ms |
| NFR-PER-03 | Search keystroke-to-results (10k rows) | < 200 ms |
| NFR-STO-01 | DB size at 5000 rows | < 20 MB |
| NFR-SEC-01 | PIN hash: PBKDF2-HMAC-SHA256, 100k iter, 16-byte salt | — |
| NFR-SEC-02 | Biometric: `BiometricPrompt` with `DEVICE_CREDENTIAL` fallback | — |
| NFR-SEC-03 | No network access (enforced by manifest) | — |
| NFR-REL-01 | Retention sweep runs on listener connect + hourly | — |
| NFR-CMP-01 | compileSdk 37, minSdk 26, targetSdk 37 | — |

---

## 4. Data Model

### 4.1 NotificationRecord (Kotlin data class)
```kotlin
data class NotificationRecord(
    val id: Long = 0,                    // PK, auto
    val postedAt: Long,                  // epoch ms
    val packageName: String,
    val channelId: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val summaryText: String?,
    val sender: String?,                 // from MessagingStyle
    val conversation: String?,           // from MessagingStyle
    val groupKey: String?,
    val isGroupSummary: Boolean,
    val remoteInputCount: Int,
    val actionCount: Int,
    val hasImage: Boolean,
    val largeIconUri: String?,
    var isRead: Boolean = false,
    var isStarred: Boolean = false,
    var isDeleted: Boolean = false
)
```

### 4.2 SettingsStore Keys
| Key | Type | Default |
|-----|------|---------|
| `track_mode_<package>` | String (TrackMode) | `NEVER` |
| `keyword_filter` | String | `""` |
| `quiet_hours_start` | Int (minute-of-day) | -1 (disabled) |
| `quiet_hours_end` | Int (minute-of-day) | -1 (disabled) |
| `max_stored` | Int | 5000 |
| `pin_hash` | String (base64) | null |
| `pin_salt` | String (base64) | null |
| `biometric_enabled` | Boolean | false |

### 4.3 AppCatalog Entry
```kotlin
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Icon?,      // resolved at UI time
    var trackMode: TrackMode
)
```

---

## 5. Interface Specification

### 5.1 NotificationCaptureService (System Callback)
```kotlin
class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification)
    override fun onNotificationRemoved(sbn: StatusBarNotification, reason: Int)
    override fun onListenerConnected()
    override fun onListenerDisconnected()
}
```

### 5.2 VaultDb (Storage API)
```kotlin
interface VaultDb {
    val revision: StateFlow<Long>
    suspend fun insert(record: NotificationRecord): Long
    suspend fun query(filter: Filter, limit: Int, offset: Int): List<NotificationRecord>
    suspend fun countMatching(filter: Filter): Int
    suspend fun setStarred(id: Long, value: Boolean)
    suspend fun setRead(id: Long, value: Boolean)
    suspend fun setDeleted(id: Long, value: Boolean)
    suspend fun sweepRetention(maxStored: Int)
    suspend fun exportAll(): List<NotificationRecord>
}
```

### 5.3 VaultViewModel (UI State)
```kotlin
class VaultViewModel @Inject constructor(
    private val db: VaultDb,
    private val settings: SettingsStore,
    private val catalog: AppCatalog
) : ViewModel() {
    val filter: MutableStateFlow<Filter>
    val loadedSpan: StateFlow<Int>           // pages loaded (page = 50 items)
    val items: StateFlow<List<NotificationRecord>>
    val totalCount: StateFlow<Int>
    val isLoading: StateFlow<Boolean>
    fun loadMore()
    fun refresh()
    fun setFilter(newFilter: Filter)
    fun toggleStarred(record: NotificationRecord)
    fun toggleRead(record: NotificationRecord)
    fun delete(record: NotificationRecord)
}
```

### 5.4 SettingsStore (Key-Value)
```kotlin
interface SettingsStore {
    fun ruleFor(packageName: String): TrackMode
    fun setRule(packageName: String, mode: TrackMode)
    fun getKeywordFilter(): String
    fun setKeywordFilter(text: String)
    fun getQuietHours(): Pair<Int, Int>?    // null = disabled
    fun setQuietHours(startMin: Int, endMin: Int)
    fun getMaxStored(): Int
    fun setMaxStored(n: Int)
    fun hasPin(): Boolean
    fun verifyPin(input: String): Boolean
    fun setPin(newPin: String)
    fun clearPin()
    fun isBiometricEnabled(): Boolean
    fun setBiometricEnabled(enabled: Boolean)
}
```

---

## 6. Constraints & Assumptions

| Constraint | Detail |
|------------|--------|
| Min SDK | 26 (Android 8.0) — `NotificationListenerService` removal reasons available |
| Compile SDK | 37 (Android 15) — platform `android-37.0` |
| No Room/KSP | Hand-written `SQLiteOpenHelper`; avoids annotation processing failures |
| No SQLCipher | DB is plaintext; PIN/biometric gates UI only |
| No WorkManager | Retention sweep is timer-based on listener connect |
| No INTERNET | Manifest omits it; privacy guarantee |
| Single-process | All components in `com.notifvault.app` process |

---

## 7. Acceptance Criteria (Launch Checklist)

| # | Criterion | Verification |
|---|-----------|--------------|
| 1 | `./gradlew assembleDebug` succeeds (compile, dex, package) | CI green |
| 2 | App installs on API 26+ device/emulator | `adb install` |
| 3 | Notification Access grant screen reachable from History banner | Manual |
| 4 | After grant, a test notification appears in History tab | Manual |
| 5 | Per-app TrackMode toggles work (ALWAYS/UNREAD_ONLY/NEVER) | Manual |
| 6 | Keyword filter narrows results in real time | Manual |
| 7 | Quiet-hours suppresses capture during window | Manual |
| 8 | PIN lock enables, blocks UI, unlocks with correct PIN | Manual |
| 9 | Biometric unlock works (if hardware present) | Manual |
| 10 | Export creates valid CSV/JSON via SAF picker | Manual |
| 11 | Pagination loads more items on scroll near end | Manual |
| 12 | Retention sweep respects `max_stored` limit | Unit test |
| 13 | No crashes on cold start, rotation, process death | Monkey test |

---

## 8. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| OEM notification redaction (Android 15+) | High | Medium | Document; cannot fix |
| Secure apps expose no content | Medium | Low | Expected behavior |
| Compose screen crashes on first render | High | High | Test on multiple screen sizes |
| DB schema migration needed later | Medium | Medium | `onUpgrade` stubbed; plan v2 |
| Build OOM on low-RAM machines | High | High | CI on GitHub Actions (7 GB RAM) |

---

## 9. Future Enhancements (Not in v1)

- Database encryption at rest (SQLCipher)
- Reply from history via `RemoteInput`
- Media gallery (image/video attachments)
- Cloud/WebDAV sync
- Scheduled digest notifications
- Auto-categorization (ML on-device)
- Tasker/automation intents
- Full-text search (FTS5)