# Build & Deployment Guide
## NotificationVault — Android Notification Capture & History App

---

## 1. Prerequisites

### 1.1 Local Development Machine
| Tool | Version | Install |
|------|---------|---------|
| JDK | 17 (required by AGP 9.4.0) | `sudo apt install openjdk-17-jdk` / `brew install openjdk@17` |
| Android Studio | Ladybug (2024.2) or newer | Download from developer.android.com |
| Android SDK | API 37 (android-37.0), Build Tools 37.0.0 | SDK Manager in Android Studio |
| Git | Any recent | `git --version` |

### 1.2 CI/CD (GitHub Actions)
- No local secrets required
- Uses `actions/setup-java@v4` with JDK 17
- Gradle cache via `actions/cache@v4`
- Artifact upload for APK/AAB

---

## 2. Local Build

### 2.1 First-Time Setup
```bash
# Clone or copy the extracted source
cd /home/myvps/Programming/notifiactions_history/starter/extracted/NotificationVault

# Make gradlew executable
chmod +x gradlew

# Verify Gradle wrapper
./gradlew --version
# Should show: Gradle 9.6.0
```

### 2.2 Android Studio Import
1. Open Android Studio → **Open** → select `NotificationVault` folder (not the zip)
2. Wait for Gradle sync (downloads SDK components, dependencies)
3. `local.properties` is auto-generated with your SDK path
4. **Run** → **Run 'app'** on device/emulator (API 26+)

### 2.3 Command-Line Build
```bash
# Debug APK (installable)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned, minify=false per build.gradle.kts)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk

# Release AAB (for Play Store)
./gradlew bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab

# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests (requires device)
./gradlew connectedDebugAndroidTest
```

### 2.4 Common Build Issues

| Issue | Fix |
|-------|-----|
| `Could not find android-37.0` | Install API 37 in SDK Manager |
| `Kotlin version mismatch` | AGP 9.4.0 bundles Kotlin 2.4.20; don't apply `kotlin-android` plugin |
| `OutOfMemoryError: Java heap space` | `export GRADLE_OPTS="-Xmx4g"` or increase `org.gradle.jvmargs` in `gradle.properties` |
| `License not accepted` | `yes | sdkmanager --licenses` |
| `Daemon disappeared` | `./gradlew --stop` then retry |

---

## 3. GitHub Actions CI/CD

### 3.1 Workflow: `.github/workflows/build.yml`
```yaml
name: Build & Test

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

permissions:
  contents: read

jobs:
  build:
    name: Build Debug APK
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 37
          build-tools-version: 37.0.0
          ndk-version: r27c
          cmdline-tools-version: latest

      - name: Accept Licenses
        run: yes | sdkmanager --licenses

      - name: Make gradlew executable
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 7

  test:
    name: Unit Tests
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 37

      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest --no-daemon

      - name: Upload Test Report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-report
          path: app/build/reports/tests/testDebugUnitTest/
          retention-days: 7

  lint:
    name: Lint Check
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 37

      - name: Run Lint
        run: ./gradlew lintDebug --no-daemon

      - name: Upload Lint Report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: lint-report
          path: app/build/reports/lint-results-debug.html
          retention-days: 7
```

### 3.2 Workflow: `.github/workflows/release.yml`
```yaml
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    name: Build Release AAB
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 37
          build-tools-version: 37.0.0

      - name: Accept Licenses
        run: yes | sdkmanager --licenses

      - name: Make gradlew executable
        run: chmod +x gradlew

      - name: Build Release AAB
        run: ./gradlew bundleRelease --no-daemon

      - name: Sign AAB (if keystore provided)
        if: env.KEYSTORE_BASE64 != ''
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: app/build/outputs/bundle/release
          signingKeyBase64: ${{ secrets.KEYSTORE_BASE64 }}
          alias: ${{ secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.KEYSTORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}

      - name: Upload Release AAB
        uses: actions/upload-artifact@v4
        with:
          name: app-release-aab
          path: app/build/outputs/bundle/release/*.aab
          retention-days: 30

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        if: env.KEYSTORE_BASE64 != ''
        with:
          files: app/build/outputs/bundle/release/*.aab
          generate_release_notes: true
```

### 3.3 Required Secrets (for signed releases)
| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | `base64 -w0 keystore.jks` |
| `KEY_ALIAS` | Key alias in keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password (often same as keystore) |

**Generate keystore**:
```bash
keytool -genkeypair -v -keystore notification-vault.jks \
  -alias notificationvault -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass <password> -keypass <password>
```

---

## 4. Device Testing

### 4.1 Physical Device (Recommended)
1. Enable **Developer Options** → **USB Debugging**
2. Connect via USB
3. `adb devices` → should show device
4. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. **Critical**: Grant Notification Access
   - Settings → Notifications → Device & app notifications → NotificationVault → **Allow**
   - App shows banner with shortcut if not granted

### 4.2 Emulator
```bash
# Create AVD (API 37)
avdmanager create avd -n test_api37 -k "system-images;android-37;google_apis;arm64"

# Start emulator
emulator -avd test_api37 -no-snapshot-load

# Install & test
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4.3 Test Scenarios (Manual)
| Scenario | Steps | Expected |
|----------|-------|----------|
| First launch | Open app, no listener granted | Banner with "Grant access" button |
| Grant access | Tap banner → Settings → Allow | Banner disappears; captures start |
| Receive notification | Send test SMS/WhatsApp | Appears in History tab |
| Per-app toggle | App Rules → set WhatsApp to ALWAYS | New WhatsApp notifications captured |
| Keyword filter | Settings → Keyword: "urgent" | Only matching notifications shown |
| Quiet hours | Settings → Quiet hours 22:00-07:00 | Notifications during window marked `during_quiet=1` |
| PIN lock | Settings → Set PIN → Lock app | LockScreen shows; unlock with PIN |
| Biometric | Settings → Enable biometric → Lock | BiometricPrompt appears; fallback to PIN |
| Export | Settings → Export CSV → Save to Files | Valid CSV with all columns |
| Pagination | Scroll to bottom of History | Loads next 50 items |
| Retention | Settings → Max stored: 100 → send 150 | Only 100 kept (oldest removed) |

---

## 5. Release Checklist

### 5.1 Pre-Release
- [ ] `./gradlew assembleDebug` succeeds locally
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew lintDebug` — no errors (warnings OK)
- [ ] Manual test on physical device (API 26+ and API 34+)
- [ ] Notification Access grant flow works
- [ ] All 5 tabs render without crashes
- [ ] PIN + biometric lock/unlock works
- [ ] Export produces valid CSV/JSON
- [ ] Version code/name bumped in `app/build.gradle.kts`

### 5.2 Release
- [ ] Tag release: `git tag v1.0.0 && git push origin v1.0.0`
- [ ] GitHub Actions `release.yml` builds signed AAB
- [ ] Download AAB from Actions artifacts
- [ ] Upload to Play Console (internal testing track first)
- [ ] Fill Data Safety form (no INTERNET permission = simpler)
- [ ] Promote to production after testing

### 5.3 Post-Release
- [ ] Monitor crash reports (Play Console → Vitals)
- [ ] Collect user feedback on capture reliability
- [ ] Plan v1.1: encryption, FTS, sync

---

## 6. Troubleshooting Production Issues

| Symptom | Diagnosis | Fix |
|---------|-----------|-----|
| App not capturing | Listener not granted | In-app banner → Settings shortcut |
| Captures only some apps | TrackMode = NEVER for those apps | App Rules → set to ALWAYS |
| Duplicates in History | `seenKeys` not working / UPSERT failed | Check `sbn_key` UNIQUE constraint |
| Search misses results | LIKE pattern / filter logic | Verify `buildWhere()` in `VaultDb` |
| App crashes on launch | Missing permission / ClassNotFound | Check logcat; verify manifest |
| Export fails silently | SAF URI permission / disk full | Check `Exporter` try-catch; show toast |
| Biometric not offered | Hardware not enrolled / weak biometric | Fallback to PIN; check `BiometricManager` |

---

## 7. Performance Baseline (CI)

| Metric | Target | Measurement |
|--------|--------|-------------|
| Clean build time | < 3 min | GitHub Actions `build.yml` |
| APK size (debug) | < 15 MB | `ls -lh app-debug.apk` |
| Method count | < 65k (no multidex) | `./gradlew dexCountDebug` |
| Unit test time | < 30 sec | `test` job |
| Lint time | < 2 min | `lint` job |

---

## 8. Signing for Play Store

### 8.1 Play App Signing (Recommended)
1. Generate upload keystore (keep secure)
2. In Play Console: **Setup → App integrity → Play App Signing**
3. Upload `bundleRelease.aab` — Google manages final signing key

### 8.2 Self-Managed Signing
```bash
# Build signed AAB locally
./gradlew bundleRelease \
  -Pandroid.injected.signing.store.file=../notification-vault.jks \
  -Pandroid.injected.signing.store.password=<storepass> \
  -Pandroid.injected.signing.key.alias=notificationvault \
  -Pandroid.injected.signing.key.password=<keypass>
```

---

## 9. Rollback Procedure

| Situation | Action |
|-----------|--------|
| Critical crash in production | `git revert <bad-commit>` → tag new version → rebuild |
| DB schema migration needed | Implement `onUpgrade()` in `VaultDb`; test migration locally |
| Listener permission revoked | App shows banner; user re-grants — no code change needed |

---

## 10. Monitoring & Observability

### 10.1 Built-In (No Network)
- `CaptureBus.capturedCount` — total notifications captured this session
- `CaptureBus.connected` — listener status
- `VaultDb.countAll()` — total rows
- Logcat: `NotificationVault` tag for errors

### 10.2 Play Console Vitals (Automatic)
- ANR rate
- Crash rate
- Battery usage (listener is low-impact)
- Slow rendering

### 10.3 Custom Metrics (Future)
- Capture-to-DB latency histogram
- Query latency p50/p95/p99
- Retention sweep frequency

---

## 11. Security Review (Pre-Launch)

| Check | Status | Notes |
|-------|--------|-------|
| No `INTERNET` permission | ✅ | Manifest verified |
| PIN hashed with PBKDF2 100k | ✅ | `PinHasher.kt` |
| Biometric uses Keystore-backed crypto | ✅ | `BiometricPrompt` + `Cipher` |
| No sensitive data in logs | ✅ | Only error messages |
| Export requires user action (SAF) | ✅ | No auto-upload |
| Notification Access disclosure | ⚠️ | Add first-run consent screen for Play |
| Database encryption at rest | ❌ | Documented limitation; v1.1 |

---

## 12. Appendix: Useful Commands

```bash
# View logcat for app
adb logcat -s NotificationVault:V *:S

# Check notification listener status
adb shell dumpsys notification | grep -A5 NotificationVault

# Inspect database on device (debug build only)
adb shell run-as com.notifvault.app \
  sqlite3 databases/notification_vault.db ".schema"

# Pull database for inspection
adb exec-out run-as com.notifvault.app cat databases/notification_vault.db > vault.db

# Force stop app
adb shell am force-stop com.notifvault.app

# Clear app data (reset everything)
adb shell pm clear com.notifvault.app
```

---

## 13. Support Matrix

| Android Version | API | Capture | UI | Notes |
|-----------------|-----|---------|-----|-------|
| 8.0 (Oreo) | 26 | ✅ | ✅ | Min SDK; removal reasons available |
| 9 (Pie) | 28 | ✅ | ✅ | `EXTRA_TITLE_BIG` available |
| 10 (Q) | 29 | ✅ | ✅ | Scoped storage (SAF export works) |
| 11 (R) | 30 | ✅ | ✅ | |
| 12 (S) | 31 | ✅ | ✅ | |
| 13 (Tiramisu) | 33 | ✅ | ✅ | `POST_NOTIFICATIONS` for own status |
| 14 (UpsideDownCake) | 34 | ✅ | ✅ | |
| 15 (VanillaIceCream) | 35 | ✅ | ✅ | OTP redaction; `compileSdk 37` covers |

**Target**: API 26–35 tested; API 37 compiled against.