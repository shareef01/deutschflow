# DeutschFlow Engineering Remediation Report

## 1. Executive Summary

This report documents the comprehensive remediation, hardening, data integrity verification, and architectural enhancements performed across the DeutschFlow Android and Web PWA codebases.

All work strictly adheres to the core invariants:
- Zero telemetry, analytics, or third-party tracking.
- Local-first architecture with full offline capability.
- 100% preservation of user learning history, SRS intervals, review events, and XP.
- Zero credential exposure at rest, in logs, or in exports.
- 1:1 English/German localization parity and design token alignment.

All 416 web unit/integration tests and all 26 Android unit test suites execute and pass cleanly.

---

## 2. Baseline Revision

- **Working Branch**: `fix/audit-hardening-and-data-integrity`
- **Initial Baseline Branch**: `main`
- **Initial Baseline Commit**: `69f5483`
- **Verification Environment**: Windows 11, Node.js v20.18.0, Next.js 16.4.0, Gradle 8.11.1 / JDK 21.

---

## 3. Remediation Matrix

| Finding ID | Severity | Focus Area | Status | Key Source Files | Verification Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **F-01** | High | Vocabulary Collision & Referential Integrity | Implemented & Verified | `web/src/lib/db/repository.ts`<br>`web/src/lib/db/schema.ts` | 17/17 tests in `repository.test.ts` pass, including collision remapping, SRS merge, atomic rollback, and 20 historical review preservation. |
| **F-02** | High | Android Data Recovery & SAF Support | Implemented & Verified | `app/src/main/java/.../backup/BackupManager.kt`<br>`BackupCrypto.kt` | All 26 Android test suites pass via `./gradlew testDebugUnitTest --rerun-tasks`. |
| **F-03** | Medium | Distributed Rate Limiting | Implemented & Verified | `web/src/lib/auth/throttle.ts`<br>`web/src/app/api/login/route.ts` | 15/15 tests in `throttle.test.ts` pass, including simulated multi-instance edge cluster, Upstash Redis REST backend, and fallback on failure. |
| **F-04** | Medium | Web Backup Scalability & Capacity Bounds | Implemented & Verified | `web/src/lib/db/backup.ts` | 33/33 tests in `backup.test.ts` pass, testing 10 MiB limits, streaming boundaries, and large collections. |
| **F-05** | Medium | Encrypted Portable Backups | Implemented & Verified | `web/src/lib/db/backup.ts`<br>`web/src/app/(app)/settings/page.tsx` | Full AES-256-GCM + PBKDF2 cross-platform cryptographic parity. Correct password, wrong passphrase, tampered ciphertext, and corrupted IV tests pass. |
| **F-06** | Medium | Session Security & Configurable Lifetime | Implemented & Verified | `web/src/lib/auth/session.ts`<br>`web/src/app/api/login/route.ts` | 33/33 tests in `session.test.ts` pass, covering configurable `SESSION_MAX_AGE_SECONDS` (1h..90d), multi-factor HMAC key derivation, and instant epoch revocation. |
| **F-07** | Medium | CSP, XSS & Prohibited Sinks Auditing | Implemented & Verified | `web/next.config.ts`<br>`web/tests/security-sinks.test.ts` | Static scan confirms 0 usages of `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `new Function`, or `document.write`. |
| **F-08** | Low | SRS Elapsed-Time & Boundary Semantics | Implemented & Verified | `web/src/lib/ai/srs.ts`<br>`web/tests/srs-boundaries.test.ts` | Boundary tests verify UTC elapsed duration across midnight boundaries, DST transitions, timezone jumps, and maximum interval caps. |
| **F-09** | Low | AI Provider Resilience & Credential Scrubbing | Implemented & Verified | `web/src/lib/ai/groq.ts` | Centralized model config (`DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b"`), bounded retries (2 max, 300ms/600ms backoff) for transient 5xx errors, and `redactSensitiveKey` protection. |
| **F-10** | Low | State-Changing Logout & CSRF Defenses | Implemented & Verified | `web/src/app/api/logout/route.ts` | POST logout validates `Origin`/`Referer` headers, rejecting cross-site forgery with 403 Forbidden. Cookie cleared with maxAge 0 and Cache-Control: no-store. |
| **F-11** | Low | Documentation & Static Parity | Implemented & Verified | `docs/BACKUP_FORMAT.md`<br>`docs/SECURITY.md`<br>`docs/AUDIT_REMEDIATION.md` | `tools/audit_repo.py` passes 100% across all 267 Android and 242 Web translation resources and color tokens. |

---

## 4. Test Results Summary

### 4.1 Web Test Suite (Vitest)
```
Test Files: 30 passed (30 total)
Tests:      416 passed (416 total)
Duration:   ~5.3s
```
Key Suites:
- `tests/repository.test.ts`: 17 passed
- `tests/backup.test.ts`: 33 passed
- `tests/throttle.test.ts`: 15 passed
- `tests/session.test.ts`: 33 passed
- `tests/security-sinks.test.ts`: 6 passed
- `tests/srs-boundaries.test.ts`: 6 passed
- `tests/data-integrity.test.ts`: 14 passed
- `tests/groq.test.ts`: 22 passed
- `tests/logout-privacy.test.ts`: 24 passed
- `tests/contract.test.ts`: 64 passed

### 4.2 Web Production Build (Turbopack)
- `npm run build` completed successfully with code 0.
- All 12 routes pre-rendered statically with proxy middleware intact.

### 4.3 Android Test Suite (Gradle)
```
./gradlew testDebugUnitTest --rerun-tasks
33 actionable tasks: 33 executed
BUILD SUCCESSFUL in 1m 25s
All 26 test classes passed.
```

### 4.4 Static Parity Audit
```
python tools/audit_repo.py
PASS: All 267 Android string resources match 1:1 between English and German.
PASS: All 242 Web translation keys match 1:1 between English and German.
PASS: Color palette tokens agree 100% across Android and Web.
PASS: All contrast pairings pass WCAG thresholds in both light and dark themes.
```

---

## 5. Architectural Decisions & Security Rationale

1. **Deterministic SRS Merging (`withFurthestScheduleOf`)**:
   Instead of naively resetting or taking arbitrary timestamps during a collision, the system compares `reviewCount` first (measuring successful reinforcement), followed by `interval`. This ensures users never lose accumulated spaced-repetition memory strength when editing cards.
2. **Fail-Secure Distributed Rate Limiting**:
   When configured with Upstash Redis REST, rate limits synchronize globally across all Vercel serverless edge workers. If Redis fails or times out, the local bounded LRU map serves as an immediate fallback, ensuring high availability without leaving the gate unprotected.
3. **Preserved `'unsafe-inline'` with Prohibited Sinks Auditing**:
   Because DeutschFlow is an offline-capable PWA with zero server-side rendering, Next.js static bootstrap requires inline hydration scripts. Rather than dismantling offline functionality, we established a strict zero-sink policy verified continuously by automated static analysis.
4. **Shared Portable Cryptographic Envelope**:
   Both Android JCA and Web WebCrypto use identical PBKDF2-HMAC-SHA256 (100,000 iterations) and AES-256-GCM configurations, enabling bidirectional, encrypted backup interoperability.

---

## 6. Remaining Deployment Prerequisites

- Configure production secrets on deployment host:
  - `SITE_PASSWORD`
  - `SESSION_SECRET` (at least 32 bytes)
  - `SESSION_EPOCH` (`"1"`)
  - (Optional) `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`
- Do not commit real production credentials.
