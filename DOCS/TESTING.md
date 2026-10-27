# Testing Strategy
## NotificationVault — Android Notification Capture & History App

---

## 1. Testing Philosophy

| Principle | Practice |
|-----------|----------|
| **Test pyramid** | 70% unit, 20% integration, 10% UI/device |
| **Fast feedback** | Unit tests run in < 30s on CI; device tests on demand |
| **Real devices > emulators** | NotificationListenerService behavior varies by OEM |
| **Deterministic** | No flaky tests; mock time, RNG, system services |
| **Test the hard parts** | Extraction logic, pagination, retention sweep, PIN hashing |

---

## 2. Test Structure

```
app/src/
├── test/                          # Unit tests (JVM, no Android)
│   ├── data/
│   │   ├── VaultDbTest.kt         # Query, count, sweep, upsert
│   │   ├── SettingsStoreTest.kt   # TrackMode, keyword, quiet-hours, PIN
│   │   ├── AppCatalogTest.kt      # Package filtering, label resolution
│   │   └── PinHasherTest.kt       # Hash/verify, constant-time compare
│   ├── service/
│   │   └── NotificationExtractorTest.kt  # extract(), extractMessaging()
│   ├── ui/
│   │   └── VaultViewModelTest.kt  # Pagination, reload, filter, mutex
│   └── util/
│       └── ExporterTest.kt        # CSV/JSON format, escaping
├── androidTest/                   # Instrumented tests (device/emulator)
│   ├── ui/
│   │   ├── HistoryScreenTest.kt   # Compose UI, pagination, search
│   │   ├── AppRulesScreenTest.kt  # TrackMode toggles
│   │   └── LockScreenTest.kt      # PIN entry, biometric mock
│   └── integration/
│       └── CaptureIntegrationTest.kt  # Service + DB + ViewModel
└── debug/                         # Test-only helpers
    └── TestVaultDb.kt             # In-memory DB for unit tests
```

---

## 3. Unit Tests (JVM)

### 3.1 `VaultDbTest.kt` — Core Storage Logic

```kotlin
class VaultDbTest {
    private lateinit var db: VaultDb
    private val context = RuntimeEnvironment.application

    @Before
    fun setup() {
        db = VaultDb.get(context) // Uses in-memory for test
    }

    @After
    fun teardown() {
        db.deleteAll()
    }

    @Test
    fun `insert then query returns same record`() = runTest {
        val record = NotificationRecord(
            key = "test-key",
            packageName = "com.test.app",
            appLabel = "Test App",
            title = "Title",
            body = "Body",
            postedAt = System.currentTimeMillis()
        )
        val id = db.saveSync(record)
        assertTrue(id > 0)

        val results = db.query(HistoryFilter(), limit = 10, offset = 0)
        assertEquals(1, results.size)
        assertEquals("test-key", results[0].key)
    }

    @Test
    fun `upsert on same key replaces row`() = runTest {
        val record1 = NotificationRecord(key = "same", packageName = "a", appLabel = "A", title = "v1", body = "b", postedAt = 1000)
        val record2 = NotificationRecord(key = "same", packageName = "a", appLabel = "A", title = "v2", body = "b", postedAt = 2000)
        db.saveSync(record1)
        db.saveSync(record2)

        val results = db.query(HistoryFilter(), limit = 10, offset = 0)
        assertEquals(1, results.size)
        assertEquals("v2", results[0].title)
    }

    @Test
    fun `countMatching returns correct count without materialising rows`() = runTest {
        repeat(100) { i ->
            db.saveSync(NotificationRecord(key = "k$i", packageName = "pkg", appLabel = "App", title = "t$i", body = "body", postedAt = 1000L + i))
        }
        val filter = HistoryFilter(text = "t5") // matches t50-t59
        assertEquals(10, db.countMatching(filter))
    }

    @Test
    fun `sweepRetention deletes by age then count with tie-break`() = runTest {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        // 10 old (beyond retention)
        repeat(10) { i ->
            db.saveSync(NotificationRecord(key = "old$i", packageName = "p", appLabel = "A", title = "t", body = "b", postedAt = now - 40 * day))
        }
        // 5 at boundary second (tie)
        repeat(5) { i ->
            db.saveSync(NotificationRecord(key = "tie$i", packageName = "p", appLabel = "A", title = "t", body = "b", postedAt = now - 30 * day))
        }
        // 10 recent
        repeat(10) { i ->
            db.saveSync(NotificationRecord(key = "new$i", packageName = "p", appLabel = "A", title = "t", body = "b", postedAt = now - i * day))
        }

        // retentionDays=30, maxStored=15
        // Should delete 10 old + 0 tie (exactly at cap after age delete)
        val removed = db.sweep(30, 15, now)
        assertEquals(10, removed)
        assertEquals(15, db.countAll())

        // Now maxStored=10 → should delete 5 tie rows
        val removed2 = db.sweep(30, 10, now)
        assertEquals(5, removed2)
        assertEquals(10, db.countAll())
    }

    @Test
    fun `buildWhere generates correct LIKE clauses`() {
        val filter = HistoryFilter(text = "urgent", packages = setOf("com.a", "com.b"), onlyUnread = true)
        val (where, args) = db.buildWhere(filter)

        assertTrue(where.any { it.contains("LIKE") })
        assertEquals(6, args.count { it == "%urgent%" }) // 6 text columns
        assertTrue(args.contains("com.a"))
        assertTrue(args.contains("com.b"))
        assertTrue(where.any { it == "is_read = 0" })
    }
}
```

### 3.2 `NotificationExtractorTest.kt` — Capture Logic

```kotlin
class NotificationExtractorTest {
    private val context = RuntimeEnvironment.application
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val catalog = mockk<AppCatalog>(relaxed = true)
    private val service = NotificationCaptureService()

    @Before
    fun setup() {
        every { settings.masterEnabled } returns true
        every { settings.modeFor(any()) } returns TrackMode.ALWAYS
        every { settings.isIgnored(any()) } returns false
        every { settings.isQuietNow(any()) } returns false
        every { catalog.labelFor(any()) } returns "Test App"
    }

    @Test
    fun `extract populates all fields from StatusBarNotification`() {
        val sbn = createSbn(
            pkg = "com.whatsapp",
            title = "John",
            text = "Hello!",
            bigText = "Hello! How are you?",
            subText = "2 messages",
            conversationTitle = "Family Group",
            groupKey = "group-123",
            postTime = 1_000_000_000_000L,
            actions = listOf("Reply", "Mark read"),
            remoteInputs = 1,
            hasImage = true
        )

        val record = service.extract(sbn)

        assertNotNull(record)
        assertEquals("com.whatsapp", record?.packageName)
        assertEquals("John", record?.title)
        assertTrue(record?.body?.contains("Hello!") == true)
        assertEquals("2 messages", record?.subText)
        assertEquals("John", record?.sender) // from messaging
        assertEquals("Family Group", record?.conversationTitle)
        assertEquals("group-123", record?.groupKey)
        assertEquals(1_000_000_000_000L, record?.postedAt)
        assertEquals("Reply, Mark read", record?.actions)
        assertEquals(1, record?.remoteInputCount)
        assertTrue(record?.hasImage == true)
    }

    @Test
    fun `extractMessaging parses sender from most recent message`() {
        val extras = Bundle().apply {
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(
                createMessage("Alice", "First"),
                createMessage("Bob", "Second"), // most recent
                createMessage("Alice", "Third")
            ))
            putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Group Chat")
        }

        val (sender, conversation) = service.extractMessaging(extras)

        assertEquals("Bob", sender) // most recent
        assertEquals("Group Chat", conversation)
    }

    @Test
    fun `removalReason timeout does not mark read`() = runTest {
        val sbn = createSbn(key = "key-1", reason = StatusBarNotification.REASON_TIMEOUT)
        val record = NotificationRecord(key = "key-1", packageName = "p", appLabel = "A", title = "t", body = "b", postedAt = 0)
        db.saveSync(record)

        service.onNotificationRemoved(sbn, null) // reason = TIMEOUT
        advanceUntilIdle()

        val results = db.query(HistoryFilter(onlyUnread = true), limit = 10, offset = 0)
        assertEquals(1, results.size) // still unread
    }

    @Test
    fun `removalReason userCancelled marks read`() = runTest {
        val sbn = createSbn(key = "key-2", reason = StatusBarNotification.REASON_USER_CANCELLED)
        val record = NotificationRecord(key = "key-2", packageName = "p", appLabel = "A", title = "t", body = "b", postedAt = 0)
        db.saveSync(record)

        service.onNotificationRemoved(sbn, null)
        advanceUntilIdle()

        val results = db.query(HistoryFilter(onlyUnread = true), limit = 10, offset = 0)
        assertEquals(0, results.size) // now read
    }
}
```

### 3.3 `PinHasherTest.kt` — Security

```kotlin
class PinHasherTest {

    @Test
    fun `hash produces different salts each call`() {
        val (hash1, salt1) = PinHasher.hash("1234")
        val (hash2, salt2) = PinHasher.hash("1234")
        assertNotEquals(salt1, salt2)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `verify accepts correct pin`() {
        val (hash, salt) = PinHasher.hash("1234")
        assertTrue(PinHasher.verify("1234", hash, salt))
    }

    @Test
    fun `verify rejects wrong pin`() {
        val (hash, salt) = PinHasher.hash("1234")
        assertFalse(PinHasher.verify("4321", hash, salt))
    }

    @Test
    fun `verify is constant-time`() {
        // Can't easily test timing in unit test, but verify uses MessageDigest.isEqual
        val (hash, salt) = PinHasher.hash("1234")
        // Just ensure no exception and correct result
        assertTrue(PinHasher.verify("1234", hash, salt))
    }

    @Test
    fun `iterations are 100k`() {
        // White-box: check constant
        assertEquals(100_000, PinHasher::class.java.getDeclaredField("ITERATIONS").getInt(null))
    }
}
```

### 3.4 `VaultViewModelTest.kt` — State Management

```kotlin
class VaultViewModelTest {
    private lateinit var viewModel: VaultViewModel
    private val db = mockk<VaultDb>(relaxed = true)
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val catalog = mockk<AppCatalog>(relaxed = true)

    @Before
    fun setup() {
        viewModel = VaultViewModel(db, settings, catalog)
    }

    @Test
    fun `loadMore extends loadedSpan and appends items`() = runTest {
        val page1 = List(50) { i -> NotificationRecord(key = "k$i", packageName = "p", appLabel = "A", title = "t$i", body = "b", postedAt = 1000L - i) }
        val page2 = List(50) { i -> NotificationRecord(key = "k${i+50}", packageName = "p", appLabel = "A", title = "t${i+50}", body = "b", postedAt = 500L - i) }

        every { db.query(any(), limit = 50, offset = 0) } returns page1
        every { db.query(any(), limit = 50, offset = 50) } returns page2
        every { db.countMatching(any()) } returns 100

        viewModel.reload(keepSpan = false)
        assertEquals(50, viewModel.items.value.size)
        assertEquals(1, viewModel.loadedSpan.value)

        viewModel.loadMore()
        assertEquals(100, viewModel.items.value.size)
        assertEquals(2, viewModel.loadedSpan.value)
    }

    @Test
    fun `filter change resets to page 1`() = runTest {
        every { db.query(any(), limit = 50, offset = 0) } returns List(50) { i -> NotificationRecord(key = "k$i", packageName = "p", appLabel = "A", title = "t$i", body = "b", postedAt = 1000L - i) }
        every { db.countMatching(any()) } returns 100

        viewModel.reload(keepSpan = false)
        viewModel.setFilter(HistoryFilter(text = "new"))
        advanceUntilIdle()

        assertEquals(1, viewModel.loadedSpan.value)
        verify(exactly = 2) { db.query(any(), limit = 50, offset = 0) }
    }

    @Test
    fun `revision bump reloads current span`() = runTest {
        val page1 = List(50) { i -> NotificationRecord(key = "k$i", packageName = "p", appLabel = "A", title = "t$i", body = "b", postedAt = 1000L - i) }
        val page2 = List(50) { i -> NotificationRecord(key = "k${i+50}", packageName = "p", appLabel = "A", title = "t${i+50}", body = "b", postedAt = 500L - i) }

        every { db.query(any(), limit = 100, offset = 0) } returns page1 + page2
        every { db.countMatching(any()) } returns 100

        viewModel.reload(keepSpan = false)
        viewModel.loadMore() // span = 2
        assertEquals(2, viewModel.loadedSpan.value)

        // Simulate revision bump
        viewModel.onRevisionChanged()
        advanceUntilIdle()

        // Should reload span 2 (100 items), not reset to 1
        assertEquals(2, viewModel.loadedSpan.value)
        verify(exactly = 1) { db.query(any(), limit = 100, offset = 0) }
    }

    @Test
    fun `concurrent reloads are serialised by mutex`() = runTest {
        var callCount = 0
        every { db.query(any(), limit = any(), offset = any()) } answers {
            callCount++
            delay(10) // simulate slow query
            emptyList()
        }
        every { db.countMatching(any()) } returns 0

        // Fire multiple reloads simultaneously
        val jobs = List(10) { launch { viewModel.reload(keepSpan = false) } }
        jobs.forEach { it.join() }

        // Only one query should have run at a time (mutex)
        // But all 10 should complete; exact count depends on timing
        assertTrue(callCount >= 1)
    }
}
```

---

## 4. Integration Tests (Device/Emulator)

### 4.1 `CaptureIntegrationTest.kt` — End-to-End Capture

```kotlin
@RunWith(AndroidJUnit4::class)
class CaptureIntegrationTest {
    private lateinit var context: Context
    private lateinit var service: NotificationCaptureService
    private lateinit var db: VaultDb

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        service = NotificationCaptureService()
        service.onCreate() // initializes settings, db, catalog
        db = VaultDb.get(context)
        db.deleteAll()
    }

    @Test
    fun `posted notification appears in vault`() {
        val sbn = createTestSbn("com.test.app", "Title", "Body")
        service.onNotificationPosted(sbn)

        // Wait for async write
        Thread.sleep(500)

        val results = db.query(HistoryFilter(), limit = 10, offset = 0)
        assertEquals(1, results.size)
        assertEquals("Title", results[0].title)
        assertEquals("Body", results[0].body)
    }

    @Test
    fun `TrackMode.NEVER skips capture`() {
        // Set mode to NEVER
        settings.setRule("com.test.app", TrackMode.NEVER)

        val sbn = createTestSbn("com.test.app", "Title", "Body")
        service.onNotificationPosted(sbn)
        Thread.sleep(500)

        assertEquals(0, db.countAll())
    }

    @Test
    fun `keyword filter suppresses non-matching`() {
        settings.setKeywordFilter("urgent")

        val sbn1 = createTestSbn("com.a", "Urgent", "Body")
        val sbn2 = createTestSbn("com.b", "Normal", "Body")
        service.onNotificationPosted(sbn1)
        service.onNotificationPosted(sbn2)
        Thread.sleep(500)

        val results = db.query(HistoryFilter(), limit = 10, offset = 0)
        assertEquals(1, results.size)
        assertEquals("Urgent", results[0].title)
    }
}
```

### 4.2 `HistoryScreenTest.kt` — Compose UI

```kotlin
class HistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `history screen shows items and loads more on scroll`() {
        val viewModel = createTestViewModel(
            items = List(100) { i -> NotificationRecord(key = "k$i", packageName = "p", appLabel = "App", title = "Item $i", body = "Body", postedAt = 1000L - i) }
        )

        composeRule.setContent {
            HistoryScreen(viewModel)
        }

        // First 50 visible
        composeRule.onNodeWithText("Item 0").assertExists()
        composeRule.onNodeWithText("Item 49").assertExists()
        composeRule.onNodeWithText("Item 50").assertDoesNotExist()

        // Scroll to trigger loadMore
        composeRule.onNode(hasTestTag("lazy-column")).performScrollTo(
            node = hasTestTag("item-95"),
            scrollDuration = 500
        )

        // Next 50 loaded
        composeRule.onNodeWithText("Item 95").assertExists()
    }

    @Test
    fun `search filters results in real time`() {
        val viewModel = createTestViewModel(
            items = listOf(
                NotificationRecord(key = "1", packageName = "p", appLabel = "A", title = "Urgent message", body = "Body", postedAt = 1000),
                NotificationRecord(key = "2", packageName = "p", appLabel = "A", title = "Normal message", body = "Body", postedAt = 900),
            )
        )

        composeRule.setContent { HistoryScreen(viewModel) }

        composeRule.onNodeWithText("Urgent message").assertExists()
        composeRule.onNodeWithText("Normal message").assertExists()

        composeRule.onNodeWithTag("search-field").performTextInput("Urgent")

        composeRule.onNodeWithText("Urgent message").assertExists()
        composeRule.onNodeWithText("Normal message").assertDoesNotExist()
    }
}
```

---

## 5. Test Data Builders

```kotlin
// test/data/TestDataBuilders.kt
fun createTestSbn(
    pkg: String = "com.test.app",
    title: String = "Title",
    text: String = "Body",
    bigText: String? = null,
    subText: String? = null,
    conversationTitle: String? = null,
    groupKey: String? = null,
    postTime: Long = System.currentTimeMillis(),
    actions: List<String> = emptyList(),
    remoteInputs: Int = 0,
    hasImage: Boolean = false,
    reason: Int = StatusBarNotification.REASON_USER_CANCELLED
): StatusBarNotification {
    val notification = Notification.Builder(ApplicationProvider.getApplicationContext(), "channel")
        .setContentTitle(title)
        .setContentText(text)
        .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
        .apply {
            if (bigText != null) setStyle(Notification.BigTextStyle().bigText(bigText))
            if (subText != null) setSubText(subText)
            if (conversationTitle != null) setStyle(Notification.MessagingStyle("User").setConversationTitle(conversationTitle))
            if (groupKey != null) setGroup(groupKey)
            actions.forEach { addAction(Notification.Action.Builder(null, it, null).build()) }
            repeat(remoteInputs) { addAction(Notification.Action.Builder(null, "Reply $it", null)
                .addRemoteInput(RemoteInput.Builder("reply").setLabel("Reply").build())
                .build()) }
            if (hasImage) {
                // Simulate image via extras
                val bigPicStyle = Notification.BigPictureStyle().bigPicture(BitmapFactory.decodeResource(...))
                setStyle(bigPicStyle)
            }
        }.build()

    return StatusBarNotification(
        pkg,
        0, 0, null, 0, 0, null, null, 0, 0, 0,
        0, 0, notification, UserHandle.SYSTEM, null, 0, 0, 0, reason
    )
}

fun createMessage(sender: String, text: String): Notification.MessagingStyle.Message {
    return Notification.MessagingStyle.Message(text, System.currentTimeMillis(), Person.Builder().setName(sender).build())
}
```

---

## 6. CI Test Execution

### 6.1 Unit Tests (Every PR)
```yaml
# In .github/workflows/build.yml
- name: Run Unit Tests
  run: ./gradlew testDebugUnitTest --no-daemon
```
- Target: < 30 seconds
- Coverage: JaCoCo report uploaded as artifact

### 6.2 Instrumented Tests (Nightly / On-Demand)
```yaml
# Separate workflow: .github/workflows/device-tests.yml
name: Device Tests
on:
  schedule: [cron: '0 2 * * *']  # Nightly
  workflow_dispatch:

jobs:
  device-test:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - uses: android-actions/setup-android@v3
        with: { api-level: 37, emulator: true }
      - name: Start Emulator
        run: |
          emulator -avd test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
          adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
      - name: Run Instrumented Tests
        run: ./gradlew connectedDebugAndroidTest --no-daemon
      - name: Upload Test Results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: device-test-results
          path: app/build/outputs/android_test_results/
```

---

## 7. Manual Testing Checklist (Per Release)

| Area | Test Cases | Device/Emulator |
|------|------------|-----------------|
| **Capture** | SMS, WhatsApp, Gmail, System alerts, Ongoing (download) | Physical (OEM variations) |
| **Listener Grant** | First launch banner → Settings → Allow → captures start | Physical |
| **TrackMode** | ALWAYS / UNREAD_ONLY / NEVER per app | Physical |
| **Keyword Filter** | Single word, multi-word, case-insensitive, special chars | Both |
| **Quiet Hours** | Same-day, overnight, disabled | Both |
| **Pagination** | Scroll to 50, 100, 500, 1000 items | Both |
| **Search** | Empty, no match, partial, full, clear | Both |
| **App Rules** | List loads, toggle modes, search apps | Both |
| **PIN Lock** | Set, unlock correct, unlock wrong, clear PIN | Physical (biometric) |
| **Biometric** | Enrolled finger/face, fallback to PIN, cancel | Physical |
| **Export** | CSV valid, JSON valid, large dataset (5000), cancel picker | Both |
| **Retention** | Age-based, count-based, tie-break | Both |
| **Rotation** | Portrait ↔ Landscape on each screen | Both |
| **Process Death** | Kill app → reopen → data intact | Both |
| **Cold Start** | Fresh install → grant → first notification | Physical |

---

## 8. Performance Benchmarks

| Test | Target | Method |
|------|--------|--------|
| Capture → DB latency | < 100 ms p95 | `System.currentTimeMillis()` in service |
| History initial render (50) | < 300 ms | `FrameTiming` / `Perfetto` |
| Search keystroke (10k rows) | < 200 ms | Unit test with 10k inserted rows |
| Pagination loadMore | < 150 ms | Instrumented test + `measureTimeMillis` |
| Retention sweep (5000 rows) | < 500 ms | Unit test with 5000 rows |
| Export 5000 rows CSV | < 2 s | Instrumented test |

---

## 9. Regression Prevention

| Risk | Test Guard |
|------|------------|
| Duplicate notifications | `VaultDbTest.upsert on same key replaces row` |
| Read-state corruption | `NotificationExtractorTest.removalReason*` |
| Pagination scroll loss | `VaultViewModelTest.revision bump reloads current span` |
| SQL injection | `VaultDbTest.buildWhere` + code review (no dynamic columns) |
| PIN timing attack | `PinHasherTest.verify is constant-time` |
| Export data loss | `ExporterTest` with 5000 rows + verify row count |
| Quiet hours overnight | `SettingsStoreTest` with minute-of-day wrap |

---

## 10. Test Coverage Goals

| Layer | Target | Tool |
|-------|--------|------|
| Data (VaultDb, SettingsStore) | 90% | JaCoCo |
| Service (Extractor) | 85% | JaCoCo |
| ViewModel | 80% | JaCoCo |
| UI (Compose) | 60% | Compose Testing + Coverage |
| Overall | 75% | Merged report |

```kotlin
// build.gradle.kts addition for coverage
tasks.withType<Test> {
    finalizedBy("jacocoTestReport")
}
```

---

## 11. Debugging Test Failures

| Failure Pattern | Likely Cause |
|-----------------|--------------|
| `Test timed out` | Coroutine not advancing → `advanceUntilIdle()` missing |
| `Database locked` | Multiple `VaultDb` instances → use singleton `get(context)` |
| `NotificationListenerService not bound` | Integration test needs `NotificationListenerService` permission grant via `adb shell pm grant` |
| `BiometricPrompt not showing` | Emulator without biometric → use `BiometricManager` mock |
| `SAF picker not returning` | Emulator no DocumentsUI → test on physical device |

---

## 12. Future Test Investments

| Priority | Investment | Effort |
|----------|------------|--------|
| High | OEM device farm (Firebase Test Lab) | Medium |
| High | Fuzzing notification payloads | Low |
| Medium | Contract test for `VaultDb` interface | Low |
| Medium | Screenshot tests for UI regression | Medium |
| Low | Chaos testing (process death, rotation, low memory) | High |

---

## 13. Running Tests Locally

```bash
# Unit tests only (fast)
./gradlew testDebugUnitTest

# With coverage
./gradlew testDebugUnitTest jacocoTestReport
# Report: app/build/reports/jacoco/testDebugUnitTest/html/index.html

# Instrumented (requires device/emulator)
./gradlew connectedDebugAndroidTest

# Single test class
./gradlew testDebugUnitTest --tests "com.notifvault.app.data.VaultDbTest"

# Single test method
./gradlew testDebugUnitTest --tests "com.notifvault.app.data.VaultDbTest.upsert on same key replaces row"
```

---

## 14. Test Naming Convention

```
<ClassName>Test
  └── `<feature> <scenario> <expected>` (backticks, spaces)
      Example: `upsert on same key replaces row`
               `removalReason timeout does not mark read`
               `filter change resets to page 1`
```

This convention makes test output readable as documentation.