# NotificationVault — Project Documentation Index

This directory contains the complete specification, architecture, implementation guide, build/deployment instructions, testing strategy, and API documentation for the NotificationVault Android app.

---

## Documents

| Document | Description | Audience |
|----------|-------------|----------|
| [SRS.md](SRS.md) | **Software Requirements Specification** — Functional & non-functional requirements, data model, acceptance criteria, risks | Product, Engineering, QA |
| [ARCHITECTURE.md](ARCHITECTURE.md) | **Architecture & Design** — Layered architecture, component details, data flows, concurrency model, security model | Engineering, Architecture |
| [IMPLEMENTATION.md](IMPLEMENTATION.md) | **Implementation Guide** — Project structure, core patterns, key algorithms, code quality rules, migration path | Engineering |
| [BUILD_DEPLOY.md](BUILD_DEPLOY.md) | **Build & Deployment** — Local build, GitHub Actions CI/CD, device testing, release checklist, signing | DevOps, Engineering |
| [TESTING.md](TESTING.md) | **Testing Strategy** — Test pyramid, unit/integration/UI tests, CI execution, manual checklist, coverage goals | QA, Engineering |
| [API.md](API.md) | **API & Interface Documentation** — Public data models, core interfaces, service API, utility APIs, Compose components, extension points | Engineering, Integrators |

---

## Quick Start

### 1. Build Locally
```bash
cd /home/myvps/Programming/notifiactions_history/starter/extracted/NotificationVault
chmod +x gradlew
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 2. Run Tests
```bash
# Unit tests (fast)
./gradlew testDebugUnitTest

# Instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest
```

### 3. Push to GitHub (empty repo: https://github.com/mugmarp/noticifai)
```bash
cd /home/myvps/Programming/notifiactions_history/starter/extracted/NotificationVault
git init
git add .
git commit -m "Initial commit: NotificationVault v1.0.0"
git branch -M main
git remote add origin https://github.com/mugmarp/noticifai.git
git push -u origin main
```

### 4. Enable GitHub Actions
The repo includes workflows in `.github/workflows/`:
- `build.yml` — Build + unit tests + lint on every push/PR
- `release.yml` — Build signed AAB on version tags (`v*`)

Add signing secrets in GitHub repo settings for release workflow:
- `KEYSTORE_BASE64`
- `KEY_ALIAS`
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`

---

## Key Technical Decisions (Summary)

| Decision | Rationale |
|----------|-----------|
| **No Room/KSP** | Avoids #1 first-build failure (annotation processor version mismatch) |
| **Hand-written SQLiteOpenHelper** | Full control, zero annotation processors, trivial migrations |
| **No SQLCipher (v1)** | Encryption at rest deferred; PIN/biometric gates UI only |
| **No WorkManager** | Retention sweep on listener connect + hourly timer |
| **No `INTERNET` permission** | Hard privacy guarantee; Play Store advantage |
| **StateFlow revision bus** | Replaces Room invalidation; simple, observable |
| **LIKE-based search** | Fast enough at ≤10k rows; FTS5 can be added later |
| **SAF for export** | No storage permissions needed |

---

## Known Limitations (v1)

1. **No database encryption at rest** — Plaintext SQLite; documented in SRS
2. **No reply-from-history** — `RemoteInput` captured but not persisted/used
3. **No cloud/local sync** — Single-device only
4. **No scheduled digests** — Analytics tab shows data but no notifications
5. **No auto-categorization** — Manual per-app rules only
6. **LIKE search, not FTS** — Fine at current scale

---

## Next Steps (Roadmap)

| Version | Features | Effort |
|---------|----------|--------|
| v1.1 | SQLCipher encryption, FTS5 search, Room migration path | Medium |
| v1.2 | Reply-from-history (RemoteInput), media gallery | Medium |
| v2.0 | WebDAV/local sync, scheduled digests, auto-categorization | High |

---

## Support

- **Issues**: https://github.com/mugmarp/noticifai/issues
- **Documentation**: This directory
- **Build**: GitHub Actions (free tier)
- **Distribution**: Play Store (internal → production)

---

## License

MIT License — see [LICENSE](../LICENSE) in project root.