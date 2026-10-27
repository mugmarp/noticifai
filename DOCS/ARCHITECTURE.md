# Architecture & Design Document
## NotificationVault — Android Notification Capture & History App

---

## 1. Architectural Overview

### 1.1 Style: Layered + Reactive
```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer (Compose)                    │
│  MainActivity → Tab Scaffold → 5 Screen Composables         │
│                      ↓ collects                             │
│                  VaultViewModel (StateFlow)                 │
└─────────────────────────────────────────────────────────────┘
                              ↓ calls
┌─────────────────────────────────────────────────────────────┐
│                      Domain / State Layer                    │
│  VaultViewModel, SettingsStore, AppCatalog, CaptureBus      │
└─────────────────────────────────────────────────────────────┘
                              ↓ calls
┌─────────────────────────────────────────────────────────────┐
│                        Data Layer                            │
│  VaultDb (SQLiteOpenHelper), Models, Exporter               │
└─────────────────────────────────────────────────────────────┘
                              ↓ receives
┌─────────────────────────────────────────────────────────────┐
│                     System Integration                       │
│  NotificationCaptureService (NotificationListenerService)   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **No Room/KSP** | Annotation processing = #1 first-build failure; AGP 9 built-in Kotlin makes KSP version alignment hazardous. At 5k rows, Room buys nothing measurable. |
| **No SQLCipher** | Encryption at rest not in v1 scope. PIN/biometric gates UI only. |
| **Hand-written SQLiteOpenHelper** | Full control, zero annotation processors, trivial schema migrations. |
| **StateFlow revision bus** | Replaces Room's invalidation tracker. Single `Long` counter bumped on insert; ViewModel collects and re-queries. |
| **Single-process, no WorkManager** | Retention sweep runs on listener connect + hourly timer. One less dependency. |
| **No INTERNET permission** | Hard privacy guarantee; Play Store data-safety advantage. |
| **SAF for export** | No storage permissions needed; user picks destination. |
| **LIKE-based search** | Fine at ≤10k rows. FTS5 can be added later if needed. |

---

## 2. Component Details

### 2.1 NotificationCaptureService (Capture Layer)
**File**: `service/NotificationCaptureService.kt` (244 lines)

**Responsibilities**:
- System callback entry point (`onNotificationPosted`, `onNotificationRemoved`)
- Flatten `StatusBarNotification` → `NotificationRecord` via `extract()`
- Parse `MessagingStyle` for sender/conversation via `extractMessaging()`
- Apply per-app `TrackMode`, keyword filter, quiet-hours
- Insert via `VaultDb.insert()` on background executor
- Bump `CaptureBus.revision` (StateFlow) to notify UI

**Critical Logic (from HANDOFF.md fixes)**:
- `onNotificationRemoved`: marks read **only** for `REASON_USER_CANCELLED` and `REASON_APP_CANCELLED`; preserves unread for `REASON_USER_DISMISSED`, `REASON_TIMEOUT`, others
- Own-package exclusion prevents capture loops
- Quiet-hours uses minute-of-day comparison (handles overnight windows)

**Threading**: All DB work on `ExecutorService` (single-thread) created in `NotificationVaultApp`.

---

### 2.2 VaultDb (Storage Layer)
**File**: `data/VaultDb.kt` (18,452 bytes, ~600 lines)

**Schema** (`notifications` table):
```sql
CREATE TABLE notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    posted_at INTEGER NOT NULL,
    package_name TEXT NOT NULL,
    channel_id TEXT,
    title TEXT,
    text TEXT,
    big_text TEXT,
    sub_text TEXT,
    summary_text TEXT,
    sender TEXT,
    conversation TEXT,
    group_key TEXT,
    is_group_summary INTEGER NOT NULL DEFAULT 0,
    remote_input_count INTEGER NOT NULL DEFAULT 0,
    action_count INTEGER NOT NULL DEFAULT 0,
    has_image INTEGER NOT NULL DEFAULT 0,
    large_icon_uri TEXT,
    is_read INTEGER NOT NULL DEFAULT 0,
    is_starred INTEGER NOT NULL DEFAULT 0,
    is_deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_notifications_posted_at ON notifications(posted_at DESC);
CREATE INDEX idx_notifications_package ON notifications(package_name);
CREATE INDEX idx_notifications_read ON notifications(is_read);
```

**Key Methods**:
| Method | Implementation Note |
|--------|---------------------|
| `insert(record)` | `ContentValues` + `db.insert()`; returns row ID; bumps revision |
| `query(filter, limit, offset)` | Dynamic `WHERE` via `buildWhere()`; `ORDER BY posted_at DESC` |
| `countMatching(filter)` | `SELECT COUNT(*)` with same `buildWhere()` — **no row materialisation** (F3 fix) |
| `setStarred(id, val)` / `setRead(id, val)` | Column names are private constants — no injection (F5 fix) |
| `sweepRetention(maxStored)` | Two-phase: (1) cut-off via `ORDER BY posted_at DESC LIMIT 1 OFFSET maxStored-1`, delete older; (2) delete same-second ties to hit exact cap (F4 fix) |
| `exportAll()` | Returns all non-deleted records for CSV/JSON export |

**Threading**: All public methods are `suspend` and switch to `IO` dispatcher. Internal executor is single-thread.

---

### 2.3 SettingsStore (Preferences Layer)
**File**: `data/SettingsStore.kt` (7,475 bytes)

**Backing**: `DataStore<Preferences>` (proto-free, key-value).

**Keys** (namespaced):
- `track_mode_<package>` → String (`ALWAYS`/`UNREAD_ONLY`/`NEVER`)
- `keyword_filter` → String
- `quiet_hours_start` / `quiet_hours_end` → Int (minute-of-day, -1 = disabled)
- `max_stored` → Int (default 5000)
- `pin_hash` / `pin_salt` → String (base64)
- `biometric_enabled` → Boolean

**PinHasher** (`util/PinHasher.kt`):
- PBKDF2-HMAC-SHA256, 100,000 iterations, 16-byte salt
- `hash(pin: String, salt: ByteArray): String` (base64)
- `verify(pin: String, hashB64: String, saltB64: String): Boolean`

---

### 2.4 AppCatalog (App Metadata Layer)
**File**: `data/AppCatalog.kt` (2,782 bytes)

**Responsibilities**:
- Query `PackageManager` for launchable apps (`MAIN` + `LAUNCHER`)
- Resolve label + icon per package
- Cache `List<AppEntry>`; refresh on package changes
- Provide `TrackMode` per package via `SettingsStore`

**Threading**: `suspend` functions on `IO` dispatcher.

---

### 2.5 VaultViewModel (State Layer)
**File**: `ui/VaultViewModel.kt` (13,068 bytes, ~300 lines)

**StateFlows**:
```kotlin
val filter = MutableStateFlow(Filter())           // current filter
val loadedSpan = MutableStateFlow(1)              // pages loaded (pageSize=50)
val items = MutableStateFlow<List<NotificationRecord>>(emptyList())
val totalCount = MutableStateFlow(0)
val isLoading = MutableStateFlow(false)
```

**Key Logic**:
- `revision` collector → `reload(keepSpan = true)` — preserves scroll position on new captures
- `filter` collector → `reload(keepSpan = false)` — resets to page 1 on filter change
- `loadMore()` extends `loadedSpan` when last visible item within 5 of end
- `Mutex` serialises reloads (revision + filter can fire simultaneously)

**Page size**: 50 items per page.

---

### 2.6 UI Layer (Compose)
**Files**:
- `MainActivity.kt` — tab scaffold, app-lock gate, export launcher
- `HistoryScreen.kt` (31,838 bytes) — LazyColumn, pagination footer, search, filter chips
- `AnalyticsScreen.kt` (12,016 bytes) — stats cards, hourly/daily charts (placeholder)
- `AppRulesScreen.kt` (8,655 bytes) — per-app TrackMode list, search apps
- `SettingsScreen.kt` (17,005 bytes) — PIN, biometric, quiet-hours, max-stored, keyword, export
- `LockScreen.kt` (9,150 bytes) — PIN entry + BiometricPrompt
- `components/Common.kt` — shared composables (AppRow, NotificationRow, chips, etc.)
- `theme/Theme.kt` — Material3 theme, dark/light

**Navigation**: Single activity, tab-based (`TabRow`), no Navigation-Compose graph.

---

### 2.7 Utility Classes
| File | Purpose |
|------|---------|
| `util/Exporter.kt` | CSV/JSON export via `CreateDocument` (SAF); streams to avoid OOM |
| `util/NotificationAccess.kt` | Intent to open Notification Access settings |
| `util/PinHasher.kt` | PBKDF2-HMAC-SHA256 (see above) |

---

## 3. Data Flow Diagrams

### 3.1 Capture → Storage → UI
```
System posts notification
        │
        ▼
NotificationCaptureService.onNotificationPosted(sbn)
        │
        ▼
extract(sbn) ──────────────────→ NotificationRecord
        │                              │
        ▼                              ▼
extractMessaging()              SettingsStore.ruleFor(pkg)
        │                              │
        ▼                              ▼
Keyword/Quiet-hours filter ───→ ALWAYS / UNREAD_ONLY / NEVER
        │                              │
        ▼                              ▼
         └──────────────┬─────────────┘
                        ▼
              VaultDb.insert(record) [background executor]
                        │
                        ▼
              CaptureBus.noteCaptured() ──→ revision++
                        │
                        ▼
        VaultViewModel collects revision ──→ reload(keepSpan=true)
                        │
                        ▼
              items StateFlow updates ──→ Compose recomposes
```

### 3.2 Removal → Read-State Update
```
System removes notification
        │
        ▼
onNotificationRemoved(sbn, reason)
        │
        ├── REASON_USER_CANCELLED ──→ mark read
        ├── REASON_APP_CANCELLED  ──→ mark read
        ├── REASON_USER_DISMISSED ──→ keep unread
        ├── REASON_TIMEOUT        ──→ keep unread
        └── other                 ──→ keep unread
        │
        ▼
VaultDb.setRead(id, value) ──→ revision++ ──→ UI refresh
```

### 3.3 Pagination Flow
```
User scrolls HistoryScreen
        │
        ▼
snapshotFlow(listState.lastVisibleItemIndex) 
        │
        ├── within 5 of (loadedSpan * pageSize - 1) ──→ loadMore()
        │                                                │
        │                                                ▼
        │                                         loadedSpan++
        │                                                │
        │                                                ▼
        │                                         reload(keepSpan=true)
        │                                                │
        ▼                                                ▼
Filter change ──→ reload(keepSpan=false) ──→ loadedSpan=1, items=page1
```

---

## 4. Concurrency Model

| Component | Threading |
|-----------|-----------|
| `NotificationCaptureService` callbacks | Main thread (system) → offload to `executor` |
| `VaultDb` public API | `suspend` → `withContext(Dispatchers.IO)` |
| `VaultDb` internal executor | Single-thread `ExecutorService` (serialises writes) |
| `VaultViewModel` | Main thread (ViewModelScope); collects StateFlows |
| `SettingsStore` (DataStore) | `suspend` → `Dispatchers.IO` |
| `AppCatalog` | `suspend` → `Dispatchers.IO` |
| `Exporter` | `suspend` → `Dispatchers.IO` (streaming write) |

**Synchronisation**:
- `VaultDb` internal executor serialises all DB writes
- `VaultViewModel` `Mutex` serialises `reload()` calls
- `CaptureBus.revision` is `MutableStateFlow` (thread-safe)

---

## 5. Security Model

### 5.1 Threat Model
| Asset | Threat | Mitigation |
|-------|--------|------------|
| Notification content (DB) | Device theft, forensic extraction | **Not encrypted in v1** — documented limitation |
| PIN credential | Shoulder surf, brute force | PBKDF2 100k iter; biometric preferred |
| Biometric | Spoofing | `BiometricPrompt` with `DEVICE_CREDENTIAL` fallback |
| Data exfiltration | Malicious app/attacker | **No INTERNET permission** — physically impossible |
| Notification access grant | Social engineering | User must manually enable in Settings |

### 5.2 PIN Storage
```
salt ← SecureRandom(16 bytes)
hash ← PBKDF2-HMAC-SHA256(pin, salt, 100_000, 32 bytes)
Store: pin_hash = base64(hash), pin_salt = base64(salt)
Verify: recompute hash with stored salt, constant-time compare
```

### 5.3 Biometric
- `BiometricPrompt.authenticate(promptInfo, cryptoObject)`
- CryptoObject: `Cipher` wrapping AES-GCM key in Android Keystore
- Fallback: device credential (PIN/pattern) if biometric unavailable

---

## 6. Error Handling & Resilience

| Scenario | Handling |
|----------|----------|
| DB insert fails | Log error, don't crash service; revision not bumped |
| DB query fails | Return empty list, show error toast, retry on next revision |
| SettingsStore read fails | Return defaults; log warning |
| PackageManager query fails | Return empty AppCatalog; retry on next resume |
| Export fails (disk full, permission) | Catch, show error toast, don't crash |
| Biometric unavailable | Fall back to PIN; don't block if neither set |
| Listener disconnected | `onListenerConnected()` triggers retention sweep + re-sync |

---

## 7. API 37 / Android 15 Compatibility (Landmines)

From HANDOFF.md — all fixed in current code:

| API Change | Handling in Code |
|------------|------------------|
| `Notification.getGroupSummary()` removed | Use `Notification.getGroup()` + `StatusBarNotification` |
| `Notification.Message` → `Notification.MessagingStyle.Message` | Use `MessagingStyle.Message.getMessagesFromBundleArray()` |
| `EXTRA_BIG_PICTURE` not a constant | Detect via literal extras key `"android.img"` |
| `Bundle.getCharSequence(key)` not synthetic | Call `extras.getCharSequence(key)` explicitly |
| OTP redaction on Android 15+ | Documented; no workaround possible |
| Secure apps expose minimal content | Expected; not a bug |
| `compileSdk 37` → `android-37.0` platform | Gradle resolves correctly |

---

## 8. Module Dependency Graph

```
:app
  ├── android (AGP 9.4.0)
  ├── kotlin-compose (2.4.20)
  ├── androidx.core:core-ktx:1.19.1
  ├── androidx.activity:activity-compose:1.11.0
  ├── androidx.lifecycle:lifecycle-runtime-ktx:2.11.0
  ├── androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0
  ├── androidx.lifecycle:lifecycle-runtime-compose:2.11.0
  ├── androidx.navigation:navigation-compose:2.10.2
  ├── androidx.biometric:biometric:1.1.0
  ├── androidx.compose.ui:ui
  ├── androidx.compose.ui:ui-graphics
  ├── androidx.compose.ui:ui-tooling-preview
  ├── androidx.compose.material:material-icons-extended
  ├── androidx.compose.material3:material3
  └── debug: androidx.compose.ui:ui-tooling
```

**No third-party libraries** beyond AndroidX — zero supply-chain risk.

---

## 9. Build & Runtime Requirements

| Requirement | Version |
|-------------|---------|
| JDK | 17 (required by AGP 9.4.0) |
| Gradle | 9.6.0 (wrapper committed) |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 (via AGP built-in + compose plugin) |
| compileSdk | 37 (`android-37.0`) |
| minSdk | 26 |
| targetSdk | 37 |
| Build Tools | 37.0.0 |
| Compose BOM | 2026.09.00 |

---

## 10. Future Architecture Evolution

| Phase | Change | Effort |
|-------|--------|--------|
| v1.1 | Add Room migration path (keep SQLiteOpenHelper compat) | Medium |
| v1.2 | SQLCipher for encryption at rest | Medium |
| v1.3 | FTS5 virtual table for full-text search | Low |
| v2.0 | Multi-device sync (WebDAV/local network) | High |
| v2.0 | Reply-from-history (RemoteInput persistence) | Medium |
| v2.0 | Media gallery (attachment extraction) | Medium |

---

## 11. Appendix: File-to-Layer Mapping

| Layer | Files | Lines (approx) |
|-------|-------|----------------|
| Capture | `service/NotificationCaptureService.kt` | 244 |
| Storage | `data/VaultDb.kt`, `Models.kt`, `SettingsStore.kt`, `AppCatalog.kt` | 696 |
| State | `ui/VaultViewModel.kt` | 301 |
| UI | `MainActivity.kt`, 5 screens, `Common.kt`, `Theme.kt` | 2,278 |
| Utils | `util/Exporter.kt`, `NotificationAccess.kt`, `PinHasher.kt` | 226 |
| **Total** | **23 Kotlin files** | **~3,745** |