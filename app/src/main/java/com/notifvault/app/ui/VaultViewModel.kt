package com.notifvault.app.ui

import android.app.Application
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notifvault.app.data.AppCatalog
import com.notifvault.app.data.AppCount
import com.notifvault.app.data.HistoryFilter
import com.notifvault.app.data.NotificationRecord
import com.notifvault.app.data.SettingsStore
import com.notifvault.app.data.TrackMode
import com.notifvault.app.data.VaultDb
import com.notifvault.app.service.CaptureBus
import com.notifvault.app.util.PinHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A snapshot of everything the Settings screen renders. */
data class SettingsState(
    val masterEnabled: Boolean = true,
    val retentionDays: Int = 30,
    val maxStored: Int = 5000,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinutes: Int = 22 * 60,
    val quietEndMinutes: Int = 7 * 60,
    val ignoreKeywords: Set<String> = emptySet(),
    val hiddenPackages: Set<String> = emptySet(),
    val appLockEnabled: Boolean = false,
    val biometricUnlockEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val totalStored: Int = 0,
)

class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val db = VaultDb.get(app)
    private val settings = SettingsStore.get(app)
    private val catalog = AppCatalog(app)

    // ---------------------------------------------------------------- filter

    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    // --------------------------------------------------------------- paging

    private val _records = MutableStateFlow<List<NotificationRecord>>(emptyList())
    val records: StateFlow<List<NotificationRecord>> = _records.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _paging = MutableStateFlow(false)
    val paging: StateFlow<Boolean> = _paging.asStateFlow()

    /** How many rows are on screen, always a whole number of pages. */
    private var loadedSpan = VaultDb.PAGE_SIZE

    /** Serialises reloads, which the two collectors below can overlap. */
    private val reloadLock = Mutex()

    init {
        viewModelScope.launch { reloadFirstPage() }
        viewModelScope.launch {
            // The capture service bumps the revision on every write. Re-read the
            // span already on screen rather than resetting to page one, so an
            // arriving notification does not collapse the user's scroll
            // position or discard the pages they had scrolled back through.
            db.revision.drop(1).collect { reloadLoadedSpan() }
        }
        viewModelScope.launch {
            // A filter change invalidates every page already loaded.
            _filter.drop(1).collect { reloadFirstPage() }
        }
    }

    private suspend fun reloadFirstPage() {
        loadedSpan = VaultDb.PAGE_SIZE
        reloadLock.withLock { reloadLoadedSpan() }
    }

    /**
     * Reads the whole loaded span, plus one probe row.
     *
     * The probe row answers "is there more?" without a second COUNT query, and
     * is then dropped from the published list.
     */
    private suspend fun reloadLoadedSpan() {
        val f = _filter.value
        val page = withContext(Dispatchers.IO) { db.query(f, limit = loadedSpan + 1) }
        _hasMore.value = page.size > loadedSpan
        _records.value = page.take(loadedSpan)
    }

    /** Called by the list as the user approaches the end. Safe to call often. */
    fun loadMore() {
        if (_paging.value || !_hasMore.value) return
        _paging.value = true
        viewModelScope.launch {
            try {
                loadedSpan += VaultDb.PAGE_SIZE
                reloadLock.withLock { reloadLoadedSpan() }
            } finally {
                _paging.value = false
            }
        }
    }

    /** Total matching the current filter, ignoring what has been paged in. */
    val matchCount: StateFlow<Int> =
        combine(_filter, db.revision) { f, _ -> f }
            .map { f -> withContext(Dispatchers.IO) { db.countMatching(f) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Apps that have at least one notification in the vault, for the filter sheet. */
    val packagesInVault: StateFlow<List<Pair<String, String>>> =
        db.revision
            .map { withContext(Dispatchers.IO) { db.packagesInVault() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ------------------------------------------------------------------ lock

    val isCapturing: StateFlow<Boolean> = CaptureBus.connected
    val capturedThisSession: StateFlow<Int> = CaptureBus.capturedCount

    // -------------------------------------------------------------- settings

    private val _settingsState = MutableStateFlow(SettingsState())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    init {
        refreshSettings()
    }

    /**
     * Preference-backed fields only, so this stays synchronous and can run from
     * a property initialiser. The stored-count is added by loadSettings().
     */
    private fun readSettings(): SettingsState = SettingsState(
        masterEnabled = settings.masterEnabled,
        retentionDays = settings.retentionDays,
        maxStored = settings.maxStored,
        quietHoursEnabled = settings.quietHoursEnabled,
        quietStartMinutes = settings.quietStartMinutes,
        quietEndMinutes = settings.quietEndMinutes,
        ignoreKeywords = settings.ignoreKeywords,
        hiddenPackages = settings.hiddenPackages,
        appLockEnabled = settings.appLockEnabled,
        biometricUnlockEnabled = settings.biometricUnlockEnabled,
        hasPin = settings.pinHash.isNotEmpty(),
        totalStored = 0,
    )

    /** Re-reads settings and adds the one value that needs a blocking DB hit. */
    private suspend fun loadSettings() {
        val count = withContext(Dispatchers.IO) { db.countAll() }
        _settingsState.value = readSettings().copy(totalStored = count)
    }

    fun refreshSettings() {
        viewModelScope.launch { loadSettings() }
    }

    // -------------------------------------------------------------- analytics

    private val _analytics = MutableStateFlow(AnalyticsState())
    val analytics: StateFlow<AnalyticsState> = _analytics.asStateFlow()

    fun refreshAnalytics(days: Int = 30) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val since = now - days * 24L * 60L * 60L * 1000L
            _analytics.value = withContext(Dispatchers.IO) {
                AnalyticsState(
                    windowDays = days,
                    topApps = db.topApps(since),
                    hourly = db.hourlyHistogram(since),
                    daily = db.dailyCounts(since, now),
                    totalInWindow = db.countBetween(since, now),
                )
            }
        }
    }

    // ---------------------------------------------------------------- filter

    fun setSearchText(text: String) {
        _filter.value = _filter.value.copy(text = text)
    }

    fun togglePackageFilter(pkg: String) {
        val current = _filter.value.packages
        _filter.value = _filter.value.copy(
            packages = if (pkg in current) current - pkg else current + pkg,
        )
    }

    fun setDateRange(from: Long?, to: Long?) {
        _filter.value = _filter.value.copy(from = from, to = to)
    }

    fun setOnlyUnread(value: Boolean) {
        _filter.value = _filter.value.copy(onlyUnread = value)
    }

    fun setOnlyStarred(value: Boolean) {
        _filter.value = _filter.value.copy(onlyStarred = value)
    }

    fun setHideQuietHours(value: Boolean) {
        _filter.value = _filter.value.copy(hideQuietHours = value)
    }

    fun clearFilter() {
        _filter.value = HistoryFilter()
    }

    // ----------------------------------------------------------------- record

    fun toggleStar(record: NotificationRecord) {
        db.setStarred(record.id, !record.starred)
    }

    fun toggleRead(record: NotificationRecord) {
        db.setRead(record.id, !record.isRead)
    }

    fun delete(record: NotificationRecord) {
        db.delete(record.id)
    }

    fun deleteAll() {
        db.deleteAll()
    }

    fun runSweepNow() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.sweep(settings.retentionDays, settings.maxStored, System.currentTimeMillis())
            }
            refreshSettings()
        }
    }

    // --------------------------------------------------------------- settings

    fun setMasterEnabled(value: Boolean) = update { settings.masterEnabled = value }

    fun setRetentionDays(value: Int) = update { settings.retentionDays = value }

    fun setMaxStored(value: Int) = update { settings.maxStored = value }

    fun setQuietHoursEnabled(value: Boolean) = update { settings.quietHoursEnabled = value }

    fun setQuietWindow(startMinutes: Int, endMinutes: Int) = update {
        settings.quietStartMinutes = startMinutes
        settings.quietEndMinutes = endMinutes
    }

    fun setAppMode(pkg: String, mode: TrackMode) = update { settings.setMode(pkg, mode) }

    fun addIgnoreKeyword(keyword: String) = update {
        val trimmed = keyword.trim()
        if (trimmed.isNotEmpty()) settings.ignoreKeywords = settings.ignoreKeywords + trimmed
    }

    fun removeIgnoreKeyword(keyword: String) = update {
        settings.ignoreKeywords = settings.ignoreKeywords - keyword
    }

    fun toggleHidden(pkg: String) = update {
        val current = settings.hiddenPackages
        settings.hiddenPackages = if (pkg in current) current - pkg else current + pkg
    }

    fun setAppLockEnabled(value: Boolean) = update { settings.appLockEnabled = value }

    fun setBiometricEnabled(value: Boolean) = update {
        settings.biometricUnlockEnabled = value
    }

    fun setPinHash(hash: String) = update { settings.pinHash = hash }

    /** Checks a typed PIN against the stored PBKDF2 hash. */
    fun verifyPin(pin: String): Boolean =
        settings.pinHash.isNotEmpty() && PinHasher.verify(pin, settings.pinHash)

    /** True when the device has a fingerprint, face, or device credential set up. */
    fun hasBiometricsAvailable(): Boolean =
        BiometricManager.from(getApplication()).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun markWelcomeSeen() = update { settings.hasSeenWelcome = true }

    // ------------------------------------------------------------------ utils

    fun appMode(pkg: String): TrackMode = settings.modeFor(pkg)

    fun appLabel(pkg: String): String = catalog.labelFor(pkg)

    fun launchApp(pkg: String): Boolean = catalog.launch(pkg)

    fun installedApps() = catalog.installedApps()

    /** Records for the export picker, honouring the current filter's date range. */
    suspend fun recordsForExport(): List<NotificationRecord> = withContext(Dispatchers.IO) {
        val f = _filter.value
        db.exportRange(f.from ?: 0L, f.to ?: Long.MAX_VALUE)
    }

    private inline fun update(block: () -> Unit) {
        block()
        refreshSettings()
    }
}

/** Everything the Analytics screen draws. */
data class AnalyticsState(
    val windowDays: Int = 30,
    val topApps: List<AppCount> = emptyList(),
    val hourly: IntArray = IntArray(24),
    val daily: List<Pair<String, Int>> = emptyList(),
    val totalInWindow: Int = 0,
) {
    // IntArray gives no structural equality, so compare contents for the
    // Compose recomposition check.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnalyticsState) return false
        return windowDays == other.windowDays &&
            topApps == other.topApps &&
            hourly.contentEquals(other.hourly) &&
            daily == other.daily &&
            totalInWindow == other.totalInWindow
    }

    override fun hashCode(): Int {
        var result = windowDays
        result = 31 * result + topApps.hashCode()
        result = 31 * result + hourly.contentHashCode()
        result = 31 * result + daily.hashCode()
        result = 31 * result + totalInWindow
        return result
    }
}
