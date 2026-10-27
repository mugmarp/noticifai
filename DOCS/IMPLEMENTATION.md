# Implementation Guide
## NotificationVault — Android Notification Capture & History App

---

## 1. Project Structure

```
NotificationVault/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/notifvault/app/
│   │   │   ├── NotificationVaultApp.kt          # Application class, DI
│   │   │   ├── data/
│   │   │   │   ├── Models.kt                    # Data classes (NotificationRecord, Filter, etc.)
│   │   │   │   ├── AppCatalog.kt                # PackageManager → app metadata
│   │   │   │   ├── SettingsStore.kt             # DataStore preferences
│   │   │   │   ├── VaultDb.kt                   # SQLiteOpenHelper implementation
│   │   │   │   └── TrackMode.kt                 # Enum: ALWAYS/UNREAD_ONLY/NEVER
│   │   │   ├── service/
│   │   │   │   └── NotificationCaptureService.kt # NotificationListenerService
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt              # Tab scaffold, app-lock gate
│   │   │   │   ├── VaultViewModel.kt            # StateFlows, pagination, reload logic
│   │   │   │   ├── theme/Theme.kt               # Material3 theme
│   │   │   │   ├── components/Common.kt         # Shared composables
│   │   │   │   └── screens/
│   │   │   │       ├── HistoryScreen.kt         # Main list, search, pagination
│   │   │   │       ├── AnalyticsScreen.kt       # Stats, charts
│   │   │   │       ├── AppRulesScreen.kt        # Per-app TrackMode
│   │   │   │       ├── SettingsScreen.kt        # PIN, biometric, quiet-hours, export
│   │   │   │       └── LockScreen.kt            # PIN entry + BiometricPrompt
│   │   │   └── util/
│   │   │       ├── Exporter.kt                  # CSV/JSON via SAF
│   │   │       ├── NotificationAccess.kt        # Settings intent helper
│   │   │       └── PinHasher.kt                 # PBKDF2-HMAC-SHA256
│   │   └── res/
│   │       ├── values/colors.xml
│   │       ├── values/strings.xml
│   │       ├── values/themes.xml
│   │       ├── xml/data_extraction_rules.xml
│   │       ├── drawable/ic_launcher_foreground.xml
│   │       └── mipmap-anydpi-v26/ic_launcher*.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew / gradlew.bat
└── DOCS/
    ├── SRS.md
    ├── ARCHITECTURE.md
    ├── IMPLEMENTATION.md (this file)
    ├── BUILD_DEPLOY.md
    ├── TESTING.md
    └── API.md
```

---

## 2. Core Implementation Patterns

### 2.1 Dependency Injection (Manual, Zero-Framework)

**File**: `NotificationVaultApp.kt`

```kotlin
class NotificationVaultApp : Application() {
    companion object {
        @Volatile private var _settings: SettingsStore? = null
        @Volatile private var _db: VaultDb? = null
        @Volatile private var _catalog: AppCatalog? = null
        @Volatile private var _executor: ExecutorService? = null

        fun settings(context: Context): SettingsStore = _settings ?: synchronized(this) {
            _settings ?: SettingsStore.get(context).also { _settings = it }
        }
        fun db(context: Context): VaultDb = _db ?: synchronized(this) {
            _db ?: VaultDb.get(context).also { _db = it }
        }
        fun catalog(context: Context): AppCatalog = _catalog ?: synchronized(this) {
            _catalog ?: AppCatalog(context).also { _catalog = it }
        }
        fun executor(): ExecutorService = _executor ?: synchronized(this) {
            _executor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "vault-writer").apply { isDaemon = true }
            }.also { _executor = it }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Pre-warm singletons
        settings(this); db(this); catalog(this); executor()
    }

    override fun onTerminate() {
        _executor?.shutdown()
        super.onTerminate()
    }
}
```

**Usage in Service**:
```kotlin
override fun onCreate() {
    super.onCreate()
    settings = NotificationVaultApp.settings(this)
    db = NotificationVaultApp.db(this)
    catalog = NotificationVaultApp.catalog(this)
}
```

**Usage in ViewModel**:
```kotlin
class VaultViewModel @Inject constructor(
    private val db: VaultDb = NotificationVaultApp.db(appContext),
    private val settings: SettingsStore = NotificationVaultApp.settings(appContext),
    private val catalog: AppCatalog = NotificationVaultApp.catalog(appContext)
) : ViewModel() { ... }
```

### 2.2 Coroutine Scoping

| Component | Scope | Reason |
|-----------|-------|--------|
| `NotificationCaptureService` | `SupervisorJob() + Dispatchers.IO` | Fire-and-forget captures; one failure shouldn't cancel others |
| `VaultViewModel` | `viewModelScope` | Auto-cancelled on ViewModel clear |
| `SettingsStore` / `AppCatalog` | Caller's scope (`suspend` functions) | No long-lived coroutines |
| `Exporter` | Caller's scope (`suspend`) | User-initiated, short-lived |

### 2.3 Error Handling Pattern

```kotlin
// In service - never crash the listener
scope.launch {
    runCatching { db.saveSync(record) }
        .onFailure { Log.e(TAG, "Save failed", it) }
}
```

```kotlin
// In ViewModel - propagate to UI state
fun loadMore() {
    viewModelScope.launch {
        _isLoading.value = true
        try {
            val more = db.query(filter, limit = PAGE_SIZE, offset = loadedSpan * PAGE_SIZE)
            _items.value += more
            _loadedSpan.value++
        } catch (e: Exception) {
            _error.value = e.message
        } finally {
            _isLoading.value = false
        }
    }
}
```

---

## 3. Key Algorithms

### 3.1 Notification Extraction (`extract()`)

```kotlin
private fun extract(sbn: StatusBarNotification): NotificationRecord? {
    val notification = sbn.notification ?: return null
    val extras = notification.extras

    // Helper: Bundle.getCharSequence(key) is NOT a synthetic property
    fun text(key: String): String? = extras?.getCharSequence(key)?.toString()

    // Body: merge BIG_TEXT, TEXT, TEXT_LINES (dedup)
    val body = listOfNotNull(
        text(Notification.EXTRA_BIG_TEXT)?.takeIf { it.isNotBlank() },
        text(Notification.EXTRA_TEXT)?.takeIf { it.isNotBlank() },
        extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }?.takeIf { it.isNotBlank() },
    ).distinct().joinToString("\n")

    // Title: EXTRA_TITLE first, then EXTRA_TITLE_BIG (API 28+)
    val title = listOfNotNull(
        text(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() },
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            text(Notification.EXTRA_TITLE_BIG)?.takeIf { it.isNotBlank() }
        else null,
    ).firstOrNull().orEmpty()

    // MessagingStyle: sender + conversation
    val (sender, conversation) = extractMessaging(extras)

    // Image detection: literal key "android.img" (no SDK constant)
    val hasImage = extras?.containsKey("android.img") == true

    // Actions + RemoteInputs
    val actionLabels = notification.actions
        ?.mapNotNull { it?.title?.toString()?.takeIf(String::isNotBlank) }
        ?.joinToString(", ") ?: ""
    val remoteInputCount = notification.actions?.count { it?.remoteInputs?.isNotEmpty() == true } ?: 0

    return NotificationRecord(
        key = sbn.key,
        packageName = sbn.packageName,
        appLabel = catalog.labelFor(sbn.packageName),
        title = title,
        body = body,
        subText = text(Notification.EXTRA_SUB_TEXT),
        sender = sender,
        conversationTitle = conversation,
        groupKey = notification.group,
        postedAt = sbn.postTime,
        isOngoing = sbn.isOngoing,
        isClearable = notification.flags and Notification.FLAG_ONGOING_EVENT == 0,
        channelId = notification.channelId,
        category = notification.category,
        hasImage = hasImage,
        actions = actionLabels,
        remoteInputCount = remoteInputCount,
        duringQuietHours = settings.isQuietNow(sbn.postTime),
    )
}
```

### 3.2 MessagingStyle Parsing (`extractMessaging()`)

```kotlin
private fun extractMessaging(extras: Bundle?): Pair<String?, String?> {
    val conversation = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

    // Notification.Message → MessagingStyle.Message (API 28+)
    val messages: List<Notification.MessagingStyle.Message> = try {
        val raw = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (raw == null) emptyList()
        else Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
    } catch (_: Exception) {
        emptyList()
    }

    // Walk backwards: most recent message = current sender
    var sender: String? = null
    for (m in messages.asReversed()) {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            m.senderPerson?.name?.toString()
        else null
            ?: m.sender?.toString()
        if (!name.isNullOrBlank()) {
            sender = name
            break
        }
    }
    return sender to conversation
}
```

### 3.3 Retention Sweep (`sweep()`)

```kotlin
fun sweep(retentionDays: Int, maxStored: Int, now: Long): Int {
    var removed = 0
    // Phase 1: Age-based delete
    if (retentionDays > 0) {
        val cutoff = now - retentionDays * 24L * 60L * 60L * 1000L
        removed += writableDatabase.delete(TABLE, "posted_at < ?", arrayOf(cutoff.toString()))
    }
    // Phase 2: Count-based trim (after age delete)
    if (maxStored > 0) {
        val count = countAll()
        if (count > maxStored) {
            var trimmed = 0
            val boundary = timestampAtOffset(maxStored - 1) // posted_at of row at cap
            if (boundary != null) {
                // Delete strictly older (uses index)
                trimmed += writableDatabase.delete(TABLE, "posted_at < ?", arrayOf(boundary.toString()))
                // Tie-break: delete excess from the boundary second
                val excess = count - maxStored - trimmed
                if (excess > 0) {
                    trimmed += writableDatabase.delete(
                        TABLE,
                        "id IN (SELECT id FROM $TABLE WHERE posted_at = ? ORDER BY id ASC LIMIT ?)",
                        arrayOf(boundary.toString(), excess.toString())
                    )
                }
            }
            removed += trimmed
        }
    }
    if (removed > 0) touch()
    return removed
}
```

### 3.4 Pagination with Scroll Preservation

```kotlin
// In VaultViewModel
private val PAGE_SIZE = 50

// Revision collector: new capture → reload current span (keep scroll)
_vaultDb.revision.distinctUntilChanged().collect { _ ->
    reload(keepSpan = true)
}

// Filter collector: filter change → reset to page 1
_filter.distinctUntilChanged().collect { _ ->
    reload(keepSpan = false)
}

private fun reload(keepSpan: Boolean) {
    mutex.withLock {
        _isLoading.value = true
        try {
            val span = if (keepSpan) _loadedSpan.value else 1
            val offset = 0
            val limit = span * PAGE_SIZE
            val results = db.query(_filter.value, limit, offset)
            _items.value = results
            _loadedSpan.value = span
            _totalCount.value = db.countMatching(_filter.value)
        } finally {
            _isLoading.value = false
        }
    }
}

fun loadMore() {
    mutex.withLock {
        if (_isLoading.value) return
        _isLoading.value = true
        try {
            val nextSpan = _loadedSpan.value + 1
            val offset = _items.value.size
            val more = db.query(_filter.value, PAGE_SIZE, offset)
            if (more.isNotEmpty()) {
                _items.value += more
                _loadedSpan.value = nextSpan
            }
        } finally {
            _isLoading.value = false
        }
    }
}
```

### 3.5 PIN Hashing (`PinHasher.kt`)

```kotlin
object PinHasher {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH = 32 // bytes (256 bits)
    private const val SALT_LENGTH = 16

    fun hash(pin: String): Pair<String, String> { // returns (hashB64, saltB64)
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH * 8)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = skf.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP) to
               Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun verify(pin: String, hashB64: String, saltB64: String): Boolean {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH * 8)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val computed = skf.generateSecret(spec).encoded
        return MessageDigest.isEqual(computed, expectedHash) // constant-time
    }
}
```

### 3.6 Biometric Authentication (`LockScreen.kt`)

```kotlin
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val biometricPrompt = remember {
        BiometricPrompt(
            context as FragmentActivity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Fallback to PIN handled by UI
                }
            }
        )
    }

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock NotificationVault")
        .setSubtitle("Use biometric or device credential")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build()

    // PIN entry UI + "Use Biometric" button → biometricPrompt.authenticate(promptInfo)
}
```

---

## 4. Export Implementation (`Exporter.kt`)

```kotlin
object Exporter {
    private const val CSV_HEADER = "id,key,package,label,title,body,sub_text,sender,conversation," +
        "group_key,posted_at,is_ongoing,is_clearable,channel,category,has_image,actions,remote_inputs,during_quiet,is_read,starred\n"

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun ExportLauncher(
        db: VaultDb,
        format: ExportFormat, // CSV or JSON
        onComplete: (Boolean) -> Unit
    ) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                mimeType = if (format == ExportFormat.CSV) "text/csv" else "application/json",
                defaultFileName = "notification_vault_${System.currentTimeMillis()}.${format.extension}"
            )
        ) { uri ->
            uri?.let { writeExport(it, format, db, onComplete) }
        }

        fun launch() = launcher.launch(Unit)
        // Call launch() from SettingsScreen button click
    }

    private suspend fun writeExport(uri: Uri, format: ExportFormat, db: VaultDb, callback: (Boolean) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri).use { out ->
                    out?.use { stream ->
                        val records = db.exportRange(0, Long.MAX_VALUE)
                        if (format == ExportFormat.CSV) {
                            stream.write(CSV_HEADER.toByteArray())
                            records.forEach { stream.write(it.toCsv().toByteArray()) }
                        } else {
                            stream.write("[".toByteArray())
                            records.forEachIndexed { i, r ->
                                if (i > 0) stream.write(",".toByteArray())
                                stream.write(r.toJson().toByteArray())
                            }
                            stream.write("]".toByteArray())
                        }
                    }
                }
                callback(true)
            } catch (e: Exception) {
                Log.e("Exporter", "Export failed", e)
                callback(false)
            }
        }
    }
}
```

---

## 5. Compose UI Patterns

### 5.1 Tab Scaffold (`MainActivity.kt`)

```kotlin
@Composable
fun MainScreen(viewModel: VaultViewModel, onExportClick: () -> Unit) {
    val appLocked by viewModel.appLocked.collectAsState()
    val listenerConnected by CaptureBus.connected.collectAsState()

    // Lock gate
    if (appLocked) {
        LockScreen { viewModel.unlock() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NotificationVault") },
                actions = {
                    if (!listenerConnected) {
                        Text("No listener", modifier = Modifier.padding(16.dp))
                        IconButton(onClick = { NotificationAccess.open(context) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Open settings")
                        }
                    }
                    IconButton(onClick = onExportClick) {
                        Icon(Icons.Default.Download, contentDescription = "Export")
                    }
                }
            )
        }
    ) { innerPadding ->
        TabRow(
            selectedTabIndex = viewModel.currentTab,
            onTabSelected = { viewModel.currentTab = it },
            modifier = Modifier.padding(innerPadding)
        ) {
            Tab(text = stringResource(R.string.tab_history), icon = { Icon(Icons.Default.History, "") })
            Tab(text = stringResource(R.string.tab_analytics), icon = { Icon(Icons.Default.Analytics, "") })
            Tab(text = stringResource(R.string.tab_apps), icon = { Icon(Icons.Default.Apps, "") })
            Tab(text = stringResource(R.string.tab_settings), icon = { Icon(Icons.Default.Settings, "") })
            Tab(text = stringResource(R.string.tab_lock), icon = { Icon(Icons.Default.Lock, "") })
        }

        // Tab content
        when (viewModel.currentTab) {
            0 -> HistoryScreen(viewModel)
            1 -> AnalyticsScreen(viewModel)
            2 -> AppRulesScreen(viewModel)
            3 -> SettingsScreen(viewModel)
            4 -> LockSettingsScreen(viewModel)
        }
    }
}
```

### 5.2 History Screen with Pagination (`HistoryScreen.kt`)

```kotlin
@Composable
fun HistoryScreen(viewModel: VaultViewModel) {
    val listState = rememberLazyListState()
    val items = viewModel.items.collectAsState()
    val isLoading = viewModel.isLoading.collectAsState()

    // Trigger loadMore when near end
    LaunchedEffect(listState.lastVisibleItemIndex, items.value.size, isLoading.value) {
        val lastIndex = listState.lastVisibleItemIndex
        val totalLoaded = items.value.size
        if (!isLoading.value && lastIndex >= totalLoaded - 5 && totalLoaded > 0) {
            viewModel.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar + filter chips
        SearchAndFilterBar(viewModel)

        // List
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items.value) { record ->
                NotificationRow(record, onClick = { viewModel.toggleExpanded(record) })
            }
            // Load more footer
            if (isLoading.value && items.value.size >= viewModel.loadedSpan * 50) {
                item { CenteredCircularProgressIndicator() }
            }
        }

        // Empty state
        if (items.value.isEmpty() && !isLoading.value) {
            EmptyStateIllustration()
        }
    }
}
```

---

## 6. Configuration Files

### 6.1 `gradle.properties`
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
```

### 6.2 `gradle/wrapper/gradle-wrapper.properties`
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
```

### 6.3 `build.gradle.kts` (root)
```kotlin
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
```

### 6.4 `app/build.gradle.kts`
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.notifvault.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.notifvault.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

---

## 7. Adding New Features

### 7.1 New TrackMode
1. Add to `TrackMode.kt` enum
2. Update `SettingsStore.modeFor()` logic
3. Update `AppRulesScreen` UI
4. Update `NotificationCaptureService.handlePosted()` when branch

### 7.2 New Filter Field
1. Add to `HistoryFilter` data class (`Models.kt`)
2. Update `VaultDb.buildWhere()` to generate WHERE clause
3. Update `HistoryScreen` filter chips UI
4. Update `VaultViewModel` filter state

### 7.3 New Analytics Metric
1. Add query method to `VaultDb` (e.g., `topApps`, `hourlyHistogram`)
2. Add state to `VaultViewModel`
3. Create composable in `AnalyticsScreen`

---

## 8. Common Pitfalls & Solutions

| Pitfall | Solution |
|---------|----------|
| `Bundle.getCharSequence(key)` unresolved | Call `extras.getCharSequence(key)` — NOT `extras.charSequence[key]` |
| `Notification.getGroupSummary()` missing | Use `notification.group` + `sbn` |
| `EXTRA_BIG_PICTURE` constant missing | Use literal key `"android.img"` |
| OOM on dexing | CI on GitHub Actions (7 GB RAM); local: `org.gradle.jvmargs=-Xmx2048m` |
| Listener not receiving notifications | User must grant in Settings → app shows banner with shortcut |
| Duplicate notifications | `seenKeys` set in service + `UPSERT` on `sbn_key` in DB |
| Search slow on keystroke | Use `countMatching()` (COUNT) not `query(limit=10000).size` |
| Retention sweep sorts whole table | Use `timestampAtOffset()` + range delete + tie-break |
| SQL injection via column name | Column names are private constants (`COLUMN_STARRED`, `COLUMN_READ`) |

---

## 9. Code Quality Rules

- **No `!!`** — use `?.let` or `requireNotNull`
- **No `GlobalScope`** — always structured concurrency (`viewModelScope`, `SupervisorJob`)
- **No hardcoded strings in UI** — use `strings.xml`
- **No `Log.d` in release** — use `Timber` or `BuildConfig.DEBUG` guard
- **Compose previews** for every screen (`@Preview` annotations)
- **Kotlin `explicit` API** — public API surfaces documented with KDoc

---

## 10. Migration Path (Future)

| From | To | Approach |
|------|-----|----------|
| SQLiteOpenHelper | Room | Keep `VaultDb` interface; swap impl; run side-by-side |
| Plaintext DB | SQLCipher | `net.zetetic:android-database-sqlcipher`; migrate on unlock |
| LIKE search | FTS5 | Add virtual table; sync triggers; fallback to LIKE |
| Local only | Sync | Add `sync_status` column; background worker; conflict resolution |

---

## 11. Debugging Checklist

1. **Build fails**: Check JDK 17, Gradle wrapper, AGP 9.4.0, Kotlin 2.4.20
2. **App crashes on launch**: Check `logcat` for `ClassNotFoundException` (multidex?), `SecurityException` (permission)
3. **No notifications captured**: Verify listener granted in Settings; check `CaptureBus.connected`
4. **History empty**: Check `TrackMode` for test app (default NEVER); set to ALWAYS
5. **Search returns nothing**: Verify `buildWhere()` LIKE pattern; check `filter.text`
6. **Pagination stuck**: Check `loadMore()` mutex; verify `lastVisibleItemIndex` logic
7. **Export fails**: Check SAF picker returns URI; verify `contentResolver.openOutputStream`
8. **PIN not working**: Verify `PinHasher.verify()` constant-time compare; check salt/hash storage