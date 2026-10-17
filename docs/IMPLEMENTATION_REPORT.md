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
| **F-01** | High | Vocabulary Collision & Referential Integrity | **FIXED AND VERIFIED** | `web/src/lib/db/repository.ts`<br>`web/src/lib/db/schema.ts`<br>`app/.../VocabularyEntity.kt` | 22/22 tests in `repository.test.ts` pass, including collision remapping, pedagogical SRS merge, atomic rollback, and review event preservation. |
| **F-02** | High | Android Data Recovery & SAF Support | **PRE-EXISTING AND VERIFIED** | `app/.../ui/screens/SettingsScreen.kt`<br>`app/.../backup/BackupManager.kt`<br>`app/.../backup/BackupCrypto.kt` | Pre-existing Storage Access Framework launcher (`CreateDocument`/`OpenDocument`) verified in `SettingsScreen.kt` and `SettingsViewModel.kt`. 27/27 Android unit test classes pass via `./gradlew.bat testDebugUnitTest`. |
| **F-03** | Medium | Distributed Rate Limiting & Proxy Trust | **FIXED AND VERIFIED** | `web/src/lib/auth/throttle.ts`<br>`web/src/app/api/login/route.ts` | 17/17 tests in `throttle.test.ts` pass: atomic Upstash Redis pipelines, `fail-closed` vs `fail-secure-fallback` policies, lockout expiry, and spoofing protection requiring `x-vercel-id`. |
| **F-04** | Medium | Backup Scalability & Capacity Bounds | **FIXED AND VERIFIED** | `web/src/lib/db/backup.ts`<br>`app/.../BackupModels.kt` | Scaled limits to 50 MiB plaintext / 70 MiB encrypted with 500-item batching. Verified 22.4 MiB roundtrip (9,000 vocab + 5,000 transcripts) in `backup-scalability.test.ts` (passed in 2.5s) and 12 MiB payload in `BackupCryptoTest.kt`. |
| **F-05** | Medium | Encrypted Portable Backups & Cross-Platform Parity | **FIXED AND VERIFIED** | `web/src/lib/db/backup.ts`<br>`app/.../BackupCrypto.kt`<br>`CrossPlatformBackupTest.kt` | Bidirectional cross-platform tests pass: Android export → Web decrypt/import, Web export → Android decrypt/import, German umlauts/eszett preservation (`Straße`, `groß`, `Brücke`), wrong passphrase rejection, and corrupted ciphertext rejection. |
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
430 tests passed (430/430)
Duration: ~5.7s
```
Key Verified Suites:
- `tests/backup-scalability.test.ts`: 22.4 MiB round-trip verified (1.4s export/import).
- `tests/cross-platform-backup.test.ts`: Android ↔ Web plaintext and encrypted interoperability (6/6 passed).
- `tests/throttle.test.ts`: Atomic pipelines, lockout expiry, spoofing, fail-closed, and fail-secure-fallback (17/17 passed).
- `tests/repository.test.ts`: Pedagogical merge, lapse preservation, tie-breakers, collision remapping (22/22 passed).
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
33 actionable tasks: 1 executed, 32 up-to-date
BUILD SUCCESSFUL in 6s
All 27 test classes passed (including CrossPlatformBackupTest and VocabularyMergeTest).
```

### 4.6 Android Lint & Release Build
- `./gradlew.bat lintDebug`: **PASSED** (`BUILD SUCCESSFUL in 1m 24s`, 0 errors).
- `./gradlew.bat assembleRelease`: **PASSED** (`BUILD SUCCESSFUL in 2m 44s`, R8 minification, lintVitalRelease, resource shrinking passed).

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

### 5.1 Pedagogical Vocabulary Scheduling Merge (`withFurthestScheduleOf`)
The initial audit remediation claimed that comparing `reviewCount` followed by `interval` was sufficient. An adversarial review revealed this was pedagogically unsound:
- If a user mastered a card weeks ago (`interval = 30`, `reviewCount = 5`) and reviewed it today but failed (`quality = AGAIN`, resulting in `interval = 0`, `nextReview = 0`), merging by highest `interval` or `reviewCount` would resurrect the 30-day interval and hide the forgotten word for a month!
- **Implemented Pedagogical Policy**:
  1. **Unreviewed vs Studied**: Content edits on unreviewed cards never erase accumulated study progress on studied cards.
  2. **Recency of Study (Anti-Lapse-Erasure)**: When timestamps differ, the card with more recent `lastModifiedAt` wins. A recent lapse (`interval = 0`) wins over an older mature interval because the learner needs immediate reinforcement.
  3. **Safety in Favor of Recall (Tied Timestamps)**:
     - Due/lapsed cards take precedence over future scheduled cards.
     - Between two future cards, the sooner due date (`smaller nextReview`) wins.
     - Tied intervals favor the more conservative ease factor.

### 5.2 Distributed Rate Limiting & Fail-Closed Support
- **Atomic Operations**: Operations in `web/src/lib/auth/throttle.ts` now execute via Upstash Redis REST `/pipeline` endpoints, ensuring atomicity across concurrent login attempts.
- **Explicit Failure Policy**: Production deployments can choose:
  - `fail-secure-fallback` (default): In-memory LRU fallback during Redis outages, balancing availability with burst protection.
  - `fail-closed`: Returns HTTP 503 `ThrottleStoreError` when the distributed store is down, preventing any distributed brute-force bypass.
- **Proxy Boundary Validation**: Forwarding headers (`x-forwarded-for`, `x-real-ip`) are only trusted if the deployment is verified through both `process.env.VERCEL === "1"` and the edge-injected signature header `x-vercel-id`.

### 5.3 Scalable Backup & Portability
- Raised backup limits from 10 MiB to 50 MiB plaintext and 15 MiB to 70 MiB encrypted.
- Memory-safe validation uses `Buffer.byteLength` without memory-duplicating re-encodings.
- Batch processing in 500-item chunks ensures smooth imports without browser or SQLite thread exhaustion.
- Verified cross-platform compatibility with realistic German strings (`Straße`, `groß`, `süß`, `Brücke`, `Fußball`) and strict UUID lowercase hex formatting.

### 5.4 State-Changing Logout & CSRF Hardening
- `GET /api/logout` was hardened to return HTTP 405 Method Not Allowed (`Allow: POST`), preventing browser prefetching and unauthorized GET CSRF.
- `POST /api/logout` enforces strict origin/referer validation and clears session cookies with `maxAge: 0` and `Cache-Control: no-store`.

---

## 6. Remaining Limitations & Infrastructure Requirements

1. **Distributed Rate Limiting Infrastructure**:
   - Requires provisioned Upstash Redis instance with `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN` configured.
   - Without Upstash, rate limiting operates in local in-memory fallback mode, which protects individual instances but does not share state across serverless nodes.
2. **Content Security Policy `'unsafe-inline'`**:
   - Required by Next.js static HTML hydration scripts for offline PWA operation.
   - CSP alone does not prevent inline XSS if untrusted HTML is injected into the DOM; protection relies on React JSX automatic escaping, strict zero-sink enforcement, and automated static tests (`security-sinks.test.ts`).
3. **Android Device Verification**:
   - Android unit tests, lint, and release compilation passed completely.
   - Instrumented tests (`connectedAndroidTest`) require an active physical device or emulator and were marked `NOT EXECUTED`.
