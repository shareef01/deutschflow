# DeutschFlow Engineering Remediation & Verification Report

## 1. Executive Summary

This report documents the comprehensive remediation, hardening, data integrity verification, and architectural enhancements performed across the DeutschFlow Android and Web PWA codebases.

All work strictly adheres to the core invariants:
- **Zero telemetry, analytics, or third-party tracking**: Completely private, local-first operation.
- **Offline operation**: Full offline functionality with persistent local storage (Room on Android, Dexie/IndexedDB on Web).
- **100% preservation of user learning history**: SRS intervals, review events, XP, and streak calculations are preserved across edits and imports.
- **Zero credential exposure at rest, in logs, or in exports**: Hardware Keystore on Android, WebCrypto AES-GCM vault on Web.
- **1:1 English/German localization parity and design token alignment**: Verified by automated repository audits.

---

## 2. Commit & Environment Tracking

- **Baseline Commit SHA:** `41b29fc09b78685e0f6ad2e3387ca8558ae24e76`
- **Initial Implementation Commit SHA:** `26bbedecb9eebe2a50c82fb66a5e1eb2a144f83b`
- **Working Branch:** `fix/audit-hardening-and-data-integrity`
- **Verification Environment:** Windows 11, Node.js v20.18.0, Next.js 16.4.0, Turbopack, Gradle 8.11.1 / OpenJDK 21.

---

## 3. Remediation Matrix with Standardized Statuses

| Finding ID | Severity | Focus Area | Status | Key Source Files | Verification Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **F-01** | High | Vocabulary Collision, Study Recency & Referential Integrity | **FIXED AND VERIFIED** | `web/src/lib/db/repository.ts`<br>`web/src/lib/db/schema.ts`<br>`app/.../VocabularyEntity.kt`<br>`app/.../BackupManager.kt` | 25/25 tests in `repository.test.ts` and 27 tests in `VocabularyMergeTest.kt` pass. Collision remapping, study recency decoupled from `lastModifiedAt`, and elimination of fake ID 0 (nullable on Web, archived entity on Android). |
| **F-02** | High | Android Data Recovery & SAF Support | **IMPLEMENTED BUT NOT FULLY VERIFIED** | `app/.../ui/screens/SettingsScreen.kt`<br>`app/.../backup/BackupManager.kt`<br>`app/.../backup/BackupCrypto.kt` | Storage Access Framework launcher (`CreateDocument`/`OpenDocument`) implemented in `SettingsScreen.kt` and `SettingsViewModel.kt`. 225/225 Android unit tests pass. Instrumented on-device UI execution unverified due to lack of connected physical device/emulator. |
| **F-03** | Medium | Distributed Rate Limiting & Proxy Trust | **FIXED AND VERIFIED** | `web/src/lib/auth/throttle.ts`<br>`web/src/app/api/login/route.ts` | 19/19 tests in `throttle.test.ts` pass: atomic Upstash Lua scripts, `/multi-exec` batching, client IP format validation (`isValidIp()`), `fail-closed` vs `fail-secure-fallback` policies, and documented proxy trust boundaries. (Shared state requires provisioned Upstash backend). |
| **F-04** | Medium | Backup Scalability & Capacity Bounds | **IMPROVED WITH DOCUMENTED LIMITATION** | `web/src/lib/db/backup.ts`<br>`app/.../BackupModels.kt` | Scaled limits to 50 MiB plaintext / 70 MiB encrypted with 500-item chunking. Verified 22.4 MiB roundtrip in `backup-scalability.test.ts` and 12 MiB payload in `BackupCryptoTest.kt`. Classify 50 MiB ceiling as a documented capacity improvement (memory-bounded). |
| **F-05** | Medium | Encrypted Portable Backups & Cross-Platform Parity | **FIXED AND VERIFIED** | `web/src/lib/db/backup.ts`<br>`app/.../BackupCrypto.kt`<br>`CrossPlatformBackupTest.kt` | Bidirectional cross-platform tests pass: Android export → Web decrypt/import, Web export → Android decrypt/import, German umlauts/eszett preservation (`Straße`, `groß`, `Brücke`), nullable review events, wrong passphrase rejection, and corrupted ciphertext rejection. |
| **F-06** | Medium | Session Security & Configurable Lifetime | **FIXED AND VERIFIED** | `web/src/lib/auth/session.ts`<br>`web/src/app/api/login/route.ts` | 33/33 tests in `session.test.ts` pass: configurable `SESSION_MAX_AGE_SECONDS` (1h..90d), multi-factor HMAC key derivation, and instant epoch revocation. |
| **F-07** | Medium | CSP, XSS & Prohibited Sinks Auditing | **FIXED AND VERIFIED** | `web/next.config.ts`<br>`web/tests/security-sinks.test.ts` | Static scan confirms 0 usages of `dangerouslySetInnerHTML`, `innerHTML`, `outerHTML`, `eval`, `new Function`, or `document.write`. Architectural scope of `'unsafe-inline'` documented. |
| **F-08** | Low | SRS Elapsed-Time & Boundary Semantics | **FIXED AND VERIFIED** | `web/src/lib/ai/srs.ts`<br>`web/tests/srs-boundaries.test.ts` | Boundary tests verify UTC elapsed duration across midnight boundaries, DST transitions, timezone jumps, and maximum interval caps. |
| **F-09** | Low | AI Provider Resilience & Credential Scrubbing | **FIXED AND VERIFIED** | `web/src/lib/ai/groq.ts` | Centralized model config (`DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"`), bounded retries (2 max, 300ms/600ms backoff) for transient 5xx errors, and `redactSensitiveKey` protection. |
| **F-10** | Low | State-Changing Logout & CSRF Defenses | **FIXED AND VERIFIED** | `web/src/app/api/logout/route.ts`<br>`web/tests/session.test.ts` | `POST /api/logout` enforces CSRF origin validation. `GET /api/logout` strictly disabled returning 405 Method Not Allowed (`Allow: POST`) without state changes. |
| **F-11** | Low | Documentation & Static Parity | **FIXED AND VERIFIED** | `docs/BACKUP_FORMAT.md`<br>`docs/SECURITY.md`<br>`docs/AUDIT_REMEDIATION.md` | `tools/audit_repo.py` passes 100% across all 267 Android and 242 Web translation resources and color tokens. |

---

## 4. Test Execution & Verification Results

### 4.1 Web Test Suite (Vitest)
```
npm test
32 test files passed (32/32)
436 tests passed (436/436)
Duration: ~5.6s
```
Key Verified Suites:
- `tests/backup-scalability.test.ts`: 22.4 MiB round-trip verified (2.5s export/import).
- `tests/cross-platform-backup.test.ts`: Android ↔ Web plaintext and encrypted interoperability, nullable review event support (7/7 passed).
- `tests/throttle.test.ts`: Atomic Lua evaluation, `/multi-exec` batching, lockout expiry, spoofing, fail-closed, and fail-secure-fallback (19/19 passed).
- `tests/repository.test.ts`: Pedagogical merge, lapse preservation, content edits after lapse, collision remapping (25/25 passed).
- `tests/session.test.ts`: 405 on GET logout, session lifetimes, epoch revocation (33/33 passed).
- `tests/backup.test.ts`: Format validation, encrypted envelopes, tampering detection (33/33 passed).

### 4.2 Web Linter & Typecheck
- `npm run lint`: **PASSED** (0 errors, 0 warnings).
- `npm run typecheck`: **PASSED** (0 TypeScript errors).

### 4.3 Web Production Build (Turbopack)
- `npm run build`: **PASSED** (code 0, all 12 routes pre-rendered statically with proxy middleware intact).

### 4.4 Playwright End-to-End Tests
```
npm run test:e2e
65 passed (43.9s)
Browsers: Chromium, Firefox-Smoke, WebKit-Smoke
```
- Real-browser logout flow & multi-tab session termination verified.
- Accessible navigation, keyboard roving focus, and axe-core compliance verified across all routes.
- Visual regression tests at mobile, tablet, and desktop viewports verified.

### 4.5 Android Test Suite (Gradle)
```
./gradlew.bat testDebugUnitTest
BUILD SUCCESSFUL in 8s
225 unit tests passed across all 27 test classes (including CrossPlatformBackupTest and VocabularyMergeTest).
```

### 4.6 Android Lint & Release Build
- `./gradlew.bat lintDebug`: **PASSED** (`BUILD SUCCESSFUL in 54s`, 0 errors).
- `./gradlew.bat assembleRelease`: **PASSED** (`BUILD SUCCESSFUL in 1m 52s`, R8 minification, lintVitalRelease, resource shrinking passed).

### 4.7 Android Instrumented Tests
- `./gradlew.bat connectedAndroidTest`: **NOT EXECUTED** (No physical device or emulator attached; confirmed via `adb devices`).

### 4.8 Repository Cleanliness & Static Parity
```
python tools/audit_repo.py
PASS: All 267 Android string resources match 1:1 between English and German.
PASS: All 242 Web translation keys match 1:1 between English and German.
PASS: No accidental files, debug dumps, or stale temporary files detected.
PASS: Color palette tokens agree 100% across Android and Web.
PASS: All contrast pairings pass WCAG thresholds in both light and dark themes.
ALL AUDITS PASSED.
```

### 4.9 Dependency Vulnerability Scan
- `npm audit`: **PASSED** (0 vulnerabilities found).

---

## 5. In-Depth Architectural & Security Analysis

### 5.1 Decoupling Review Dates from Content Edits (`withFurthestScheduleOf`)
- **Vulnerability in Previous Model**: Previously, schedule recency compared `lastModifiedAt`. However, `lastModifiedAt` changes whenever a user fixes a typo, adds a synonym, edits a translation, or imports a backup. Using `lastModifiedAt` meant a typographical fix on an older mastered card could wipe out a recent failure (lapse), burying a forgotten card for weeks!
- **Implemented Pedagogical Policy**:
  1. **Unreviewed vs Studied**: Content edits on unreviewed cards never overwrite accumulated study progress on studied cards.
  2. **Dedicated Study Recency (Anti-Lapse-Erasure)**: Learning recency is determined exclusively by dedicated `lastStudiedAt` or review-event timestamps. If a card recently lapsed (`interval = 0`), later typographical edits (`lastModifiedAt`) on another card copy **never** erase the lapse.
  3. **Safety in Favor of Recall (Tied Timestamps)**:
     - Due or lapsed cards take precedence over future scheduled cards.
     - Between two future cards, the sooner due date (`smaller nextReview`) wins.
     - Tied intervals favor the more conservative ease factor.
- **Cross-Platform Tests**: Verified in `web/tests/repository.test.ts` and `app/src/test/java/.../VocabularyMergeTest.kt` with tests explicitly verifying that editorial edits after a lapse do not erase the lapse.

### 5.2 Upstash Redis Atomicity & Proxy Trust Boundaries
- **Corrected Upstash Atomicity Model**:
  - Upstash documentation explicitly clarifies that `/pipeline` batches commands over a single HTTP connection but does **not** execute them atomically or isolate them from concurrent operations.
  - In `web/src/lib/auth/throttle.ts`, operations that require conditional decisions based on current counter state (`recordFailure`) are executed using an **atomic Lua script** via `POST /` (`["EVAL", script, "1", key, ...]`). The script atomically increments attempts, sets lockouts, and refreshes TTL in a single isolated step, preventing concurrent read-modify-write races.
  - Multi-command operations without conditional logic use `/multi-exec` for transactional batching.
- **Proxy Trust Boundaries**:
  - Clarified that `x-vercel-id` is an internal routing trace header generated by Vercel for diagnostic and debugging purposes, **not** an authenticated cryptographic signature.
  - The deployment trust boundary requires verifying `process.env.VERCEL === "1"` combined with strict client IP format validation using `isValidIp()` (validating IPv4 or IPv6 syntax).
  - Outside of Vercel or behind untrusted/unconfigured reverse proxies where headers could be spoofed, caller identities default to a shared fallback bucket rather than trusting arbitrary client-supplied headers.
- **Configurable Failure Policies**:
  - `fail-secure-fallback` (default): In-memory LRU fallback during Redis outages, balancing availability with burst protection.
  - `fail-closed`: Returns HTTP 503 `ThrottleStoreError` when Redis is down, preventing any distributed brute-force bypass.

### 5.3 Elimination of Fake Vocabulary ID 0 & 50 MiB Capacity Improvement
- **Elimination of Fake ID 0**:
  - On Web: Review events support validated nullable references (`vocabularyId: number | null`). Unlinked review events explicitly store `null`. Version 10 schema migration migrates legacy `0` records to `null`.
  - On Android: Foreign key constraints are maintained by linking unmapped or deleted review events to an explicit, isolated archived entity (`[Gelöscht]`, key `__archived_deleted__`).
  - No valid review events are discarded, and no misleading foreign-key references are created.
- **Documented Capacity Improvement**:
  - Scaled limits to 50 MiB plaintext and 70 MiB encrypted.
  - Classified as a **documented capacity improvement**: memory-efficient batching and streaming array chunking allow personal libraries exceeding 20 MiB to export and import smoothly, while maintaining a bounded ceiling to protect low-memory devices against OOM crashes.

---

## 6. Production Readiness Checklist & Missing Variable Consequences

| Variable | Required | Default / Fallback | Consequence if Missing or Misconfigured |
| :--- | :--- | :--- | :--- |
| **`SITE_PASSWORD`** | **YES** | None | **Critical**: Access gate fails closed or is inaccessible. Logins cannot succeed. |
| **`SESSION_SECRET`** / **`AUTH_SECRET`** | **YES** | Ephemeral runtime key | **High**: Sessions cannot be validated or are revoked upon every serverless cold start. |
| **`UPSTASH_REDIS_REST_URL`** | **RECOMMENDED** | Local in-memory Map | **Medium**: Rate limiting operates locally per container instance; distributed attacks across multiple serverless containers receive separate quotas. |
| **`UPSTASH_REDIS_REST_TOKEN`** | **RECOMMENDED** | None | Missing token causes Redis HTTP 401. Under `fail-secure-fallback`, falls back to local in-memory limiting; under `fail-closed`, blocks all logins with HTTP 503. |
| **`THROTTLE_FAILURE_POLICY`** | NO | `"fail-secure-fallback"` | `"fail-closed"` strictly blocks all logins with HTTP 503 during Redis outages. `"fail-secure-fallback"` preserves availability via local rate limiting. |
| **`SESSION_MAX_AGE_SECONDS`** | NO | `2592000` (30 days) | Session duration in seconds, clamped between 1 hour and 90 days. |
| **`SESSION_EPOCH`** | NO | `"1"` | Incrementing revokes all active sessions globally. |
| **`GROQ_API_KEY`** | OPTIONAL | Client Key Vault | AI features graceful fallback if omitted on server. |

### 6.1 Known Limitations Summary
1. **Distributed Rate Limiting Backend**: Distributed enforcement requires provisioned Upstash Redis. Without it, rate limiting is local to each process/container.
2. **CSP `'unsafe-inline'`**: Required for Next.js offline PWA static hydration. Mitigated via 0-sink policy and automated sink tests.
3. **Android Device Verification**: Unit tests, lint, and release compilation passed; instrumented tests (`connectedAndroidTest`) could not be run due to absence of connected hardware/emulator.
