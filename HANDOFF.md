# Notification Vault - Developer Handoff

## STATUS: READ THIS BEFORE YOU TRUST THE CODE

**This app has never been run.** It has not been installed on a device or
emulator, and nobody has seen a single frame of it render. Treat the first
launch as a debugging session, not a smoke test.

| Stage | Verified? | Evidence |
|---|---|---|
| Kotlin compiles *(pre-fix revision)* | **YES, once** | 18 files / 4,048 lines, **0 errors** on a real toolchain |
| **Kotlin compiles (as shipped)** | **UNKNOWN** | six defects were fixed after that run; the recompile never finished. Read below. |
| Resources, manifest, assets process | **YES** | `:app:processDebugResources` and the manifest/asset merge tasks all succeeded |
| Links into an installable `.apk` | **NO** | dexing was OOM-killed on the build machine (976 MB RAM, no swap). Not a code failure. |
| Launches without crashing | **NO** | never installed |
| Captures a real notification | **NO** | no device available |
| UI renders correctly | **NO** | never observed |

Verified against: JDK 17, Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.20, Compose BOM
2026.09.00, compileSdk 37, minSdk 26, build-tools 37.0.0, platform
`android-37.0`. The Gradle wrapper is committed, so version drift is not a risk.

### The shipped revision has NOT been compiled

Five defects were found by code review and fixed. Recompiling afterwards was
attempted and **did not complete**: the build machine has 976 MB of RAM and no
swap, the Kotlin compile drove it to 926 MB used, and the build wedged after 30
minutes and was killed. So the fixes were verified by **reading, not by
compiling**.

Your first `./gradlew assembleDebug` is therefore a real test of whether they
are type-correct. If it fails, expect something small and localised, not
structural. Every change is itemised under *Fixes applied after the last compile*
below so you can check them by eye.

Compile-clean and working are different bars, and Compose is where they diverge
hardest. The compiler happily accepts a `LazyColumn` whose rows overflow on a
narrow screen, a `StateFlow` that emits before the database is ready, or a
screen that throws during first composition. None of that is a compile error.

### Most likely breakages, ranked

1. **The five Compose screens** (across `ui/screens/`). Written without ever
   being rendered, and `HistoryScreen` has since gained paging logic on top.
   Expect at least one crash, layout overflow, or unhandled empty state on first
   launch. Start with `HistoryScreen.kt`: the largest file, the most intricate,
   and the one that changed most.
2. **Notification extraction** in `NotificationCaptureService.extract()`. The
   parsing compiles against SDK stubs, but real notifications carry field
   combinations stubs do not model. This is also the part that varies most by
   OEM and Android version, so test on more than one device.
3. **Cold-start ordering.** The listener service, the database, and the UI
   subscribe independently. Races here show up as an empty History screen or a
   crash on cold start, not as a compile error.
4. **First-run UX.** Every app defaults to `TrackMode.NEVER`, so a fresh
   install legitimately captures nothing. If the History tab looks broken, check
   that you have set at least one app to track before assuming a bug.

---

## Fixes applied after the last successful compile

Six defects found by code review and fixed. Each entry gives the file, the
mechanism, and how to check it. Nothing here has been run, only read.

### F1 (P0) - read state was destroyed by timed-out notifications

`NotificationCaptureService.onNotificationRemoved()` marked **every** removed
notification read without looking at why it was removed. `REASON_TIMEOUT` is the
damaging case: messaging apps routinely let a notification expire unread, and
those rows were marked read and then hidden by the default Unread filter. The
app was quietly discarding precisely the notifications it exists to rescue.

The removal reason now decides:

| Reason | Marked read? | Why |
|---|---|---|
| `REASON_USER_CANCELLED` | yes | user tapped it, so they saw it |
| `REASON_APP_CANCELLED` | yes | app withdrew it, normally after the chat was opened |
| `REASON_USER_DISMISSED` | no | an ordinary swipe, frequently done unread |
| `REASON_TIMEOUT` | no | expired unseen |
| anything else | no | leave the state alone rather than guess |

Those constants are on `StatusBarNotification` since API 24, below this app's
minSdk of 26, so no compatibility guard is needed.

**The consequence to know:** unread now means "never shown in any app", not
"left the shade untapped". If you want swipe-to-dismiss to count as read, change
the `REASON_USER_DISMISSED` branch, and reconsider `REASON_TIMEOUT` with it.

### F2 (P1) - only the newest 300 notifications were reachable

`query()` always supported `offset`, but the ViewModel only ever called it once,
so with the default cap of 5,000 stored rows, 4,700 were unreachable with no
indication they existed.

`VaultViewModel` now keeps a `loadedSpan`, always a whole number of pages, and
`loadMore()` extends it. `HistoryScreen` triggers it from a `snapshotFlow` on
`listState` once the last visible item is within 5 of the end, and shows a
footer that doubles as a manual retry.

**Two design choices worth preserving.** A new capture re-reads the *currently
loaded span* rather than resetting to page one; resetting would collapse the
user's scroll position every time a notification arrived. A filter change *does*
reset, because every loaded page becomes invalid. Reloads are serialised behind
a `Mutex`, since the revision and filter collectors can otherwise overlap.

### F3 (P1) - a COUNT implemented by allocating 10,000 objects

`matchCount` was `db.query(f, limit = 10_000).size`, which constructed a full
`NotificationRecord`, every string field included, for each matching row, on
every keystroke in the search box. It is now `db.countMatching(f)`: a real
`SELECT COUNT(*)` sharing a `buildWhere()` helper with `query()`, so SQLite never
materialises the rows.

### F4 (P2) - the retention sweep sorted the whole table every hour

`sweep()` trimmed with `id NOT IN (SELECT id ... ORDER BY posted_at DESC LIMIT
?)`, sorting the entire table into a temporary set on every run. It now seeks the
cut-off with `ORDER BY posted_at DESC LIMIT 1 OFFSET maxStored - 1`, deletes the
older range (which uses the `posted_at` index), then deletes exactly the number
of same-second rows still needed to reach the cap. That second step exists
because `posted_at` is only second-granular and ties cannot be broken by the
ordering alone.

### F5 (P3) - a SQL column name accepted as a parameter

`setFlag(id, column, value)` passed its `column` argument straight into
`ContentValues`. Both callers passed literals, so it was never exploitable, but
that is the shape an injection bug takes. The name is now a private constant
reachable only through two named wrappers, `setStarred()` and `setRead()`.

### F6 - a "replyable" label advertising a feature that did not exist

`HistoryScreen` rendered `· replyable` whenever `remoteInputCount > 0`, but no
code in the project constructs a `RemoteInput` or sends a reply. The label is
gone. `remoteInputCount` is still captured and exported, so the data will be
there if reply is ever implemented.

---

## What the app does

It captures **every notification posted on the device**, not just messaging
apps. WhatsApp is not special-cased anywhere in the codebase.

`NotificationListenerService` is invoked by the system for all notifications
regardless of source. This app treats all of them uniformly: it flattens each
one into a `NotificationRecord`, applies the per-app tracking rule, and persists
it. WhatsApp just happens to be the app people notice first, because
`MessagingStyle` notifications carry sender and conversation metadata that most
apps omit. The same code path handles SMS, email, banking alerts, CI systems,
and anything else that posts a notification.

The one exclusion is the app's own notifications, which are skipped explicitly
to avoid capturing itself in a loop.

### Data flow

```
system posts notification
  -> NotificationCaptureService.onNotificationPosted(sbn)
  -> extract(sbn)                 flattens to NotificationRecord
  -> extractMessaging()           sender / conversation, when present
  -> SettingsStore.ruleFor(pkg)   ALWAYS | UNREAD_ONLY | NEVER
  -> keyword / quiet-hours filter
  -> VaultDb.insert(record)       background single-thread executor
  -> CaptureBus.noteCaptured()    bumps a revision counter
  -> VaultViewModel observes      re-queries, Compose recomposes
```

`VaultDb` exposes a `revision: StateFlow<Long>`. The ViewModel collects it and
re-runs its queries. This is a deliberate stand-in for Room's invalidation
tracker; see the deviations section.

### Layer map

| Layer | Files | Lines |
|---|---|---|
| Capture | `service/NotificationCaptureService.kt` | 244 |
| Storage | `data/VaultDb.kt`, `Models.kt`, `SettingsStore.kt`, `AppCatalog.kt` | 696 |
| State | `ui/VaultViewModel.kt` | 301 |
| UI | 5 screens + `MainActivity.kt`, `components/Common.kt`, `theme/Theme.kt` | 2,278 |
| Utils | `util/Exporter.kt`, `NotificationAccess.kt`, `PinHasher.kt` | 226 |

Read in that order. `MainActivity.kt` holds the tab scaffold, the app-lock gate,
and the export launcher; the ViewModel is the seam between capture and UI.

---

## Deliberate deviations from the original spec

These are decisions, not oversights. Each was made to reduce first-build
failure risk. Revert them only with a reason.

- **No Room, no KSP/KAPT.** `VaultDb` is a hand-written `SQLiteOpenHelper`.
  Annotation processing is the most common cause of first-build failure, and
  AGP 9's built-in Kotlin makes KSP version alignment a real hazard. At the
  intended scale (thousands of rows) Room buys nothing measurable.
- **No SQLCipher.** The database is plaintext SQLite. There is no encryption
  at rest. The spec asked for it; it is not implemented. The PIN/biometric lock
  gates the *UI* only and does not protect the data on disk.
- **No WorkManager.** Retention sweeps run on listener connect and then at most
  hourly, guarded by a timestamp. One less dependency.
- **No storage permissions.** Export uses the Storage Access Framework
  (`ActivityResultContracts.CreateDocument`), so the user picks the destination.
- **No `INTERNET` permission.** The manifest does not request it, so the app is
  physically incapable of uploading anything. Worth preserving: it is both a
  genuine privacy property and a strong Play Store argument.
- **Search is `LIKE`-based, not FTS.** Fine at a few thousand rows; revisit if
  users keep tens of thousands.

## Not implemented

From the original feature list, these are absent. Nothing in the code stubs them.

- Database encryption at rest
- Reply from history via `RemoteInput` (action *labels* are captured; the
  `RemoteInput` objects are not persisted, so replying is not possible yet).
  The misleading `· replyable` label was removed, see F6.
- Media gallery
- Cloud / WebDAV sync, and cross-device sync
- Scheduled daily/weekly digest notifications (the Insights tab shows the stats
  a digest would use, but nothing is scheduled)
- Auto-categorization of notifications
- Tasker / automation hooks

---

## API 37 landmines already hit

These cost real debugging time and are all fixed in the current code. They will
bite again if you write new extraction logic.

- `Notification.getGroupSummary()` has been **removed**. Do not use it. Group
  handling now reads `Notification.getGroup()` and the `StatusBarNotification`.
- `Notification.Message` no longer exists. It is now
  `Notification.MessagingStyle.Message`. There is a supported static helper,
  `Notification.MessagingStyle.Message.getMessagesFromBundleArray()`, and the
  current code uses it.
- `Notification.EXTRA_BIG_PICTURE` is not a constant on `Notification`. Image
  presence is detected via the literal extras key `"android.img"`.
- `Bundle.getCharSequence(String)` **is not** a Kotlin synthetic property,
  because it takes an argument. You must call `extras.getCharSequence(key)`.
  Kotlin reports the mistake as "Unresolved reference 'charSequence'", which
  sends you hunting in the wrong place.
- On Android 15+ the system **redacts** one-time passwords and similar strings
  before handing anything to a listener. There is no app-side workaround; this
  is intentional platform behavior and cannot be bypassed.
- Some apps (banking, system, secure apps) expose little or no content to
  listeners at all. This is not a bug in this app.
- Android now ships **minor-versioned** platforms. `compileSdk 37` resolves to
  the installed `android-37.0` package.

---

## Building and running

1. Unzip. Open the `NotificationVault` folder (not the zip) in Android Studio
   (Ladybug or newer).
2. `local.properties` is intentionally absent. It holds a machine-specific SDK
   path; Android Studio generates it on first open.
3. First sync downloads the SDK components and dependencies. This is slow.
4. Run on a device or emulator on **API 26+**.

**There is one manual step the app cannot do for you.** The system binds the
listener service only after the user grants notification access by hand:
Settings > Notifications > Device & app notifications > Notification Vault.
The app cannot silently obtain this; Android deliberately works this way. The
History screen shows a banner with a shortcut until it is granted. Nothing will
be captured until it is.

## Privacy and Play Store posture

This app reads every notification on the device, including message content and
one-time passcodes. Before distributing it, be aware that:

- Google Play's **Device and Network Abuse** policy restricts apps that
  "collect, store, or share sensitive information" outside the app, and
  notification listeners require prominent disclosure. Budget real time for the
  Play Console data-safety form and a first-run consent flow. The current app
  has a settings-driven grant flow, not a dedicated onboarding consent screen.
- The absence of `INTERNET` permission is a meaningful defensible property.
  Adding analytics or any network call destroys it.
- A plaintext database holding message history is a real liability on a lost
  or stolen device. Encryption at rest is the highest-value remaining work.
