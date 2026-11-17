# DeutschFlow production-readiness audit

> Historical audit of commit `e88d893`, completed on 16 November 2026. The decision and source line numbers below describe that revision. DeutschFlow 1.1.0 subsequently included remediation in [PR #103](https://github.com/shareef01/deutschflow/pull/103), merged as [`e3d409d`](https://github.com/shareef01/deutschflow/commit/e3d409d115776d775d915fe813513f105ed88bc5). Read the follow-up register below before using the original findings to assess a current release.

## Follow-up register, 17 November 2026

This register maps the original findings to the 1.1.0 source changes. It preserves the audit's original observations and proposed fixes below. Source review and existing regression coverage support the implementation notes. Deployment configuration, exploit replay, concurrency and cross-platform behavior still require the specific revalidation described here before a finding can be closed as independently verified.

| Finding | Follow-up status | Implementation and remaining verification |
| --- | --- | --- |
| SEC-01 | Implemented in 1.1.0, deployment revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) adds unique session tokens, Redis-backed revocation, logout revocation and proxy checks, with session and logout regressions. Verify copied-cookie replay, independent sessions and Redis failure behavior against the deployed ingress. |
| SEC-02 | Implemented in 1.1.0, deployment revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) rejects public production login when caller identity or HTTPS Redis configuration is unavailable and documents trusted ingress requirements. The check occurs on requests. Verify proxy header rewriting, direct-origin isolation and separate caller buckets on the actual host. |
| SEC-03 | Implemented in 1.1.0, cross-platform revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) validates imported study timestamps and changes schedule selection to use bounded review evidence. Replay poisoned legacy data and a subsequent valid import on both clients, including rollback for malformed timestamps. |
| DATA-01 | Partially addressed in 1.1.0 | [PR #103](https://github.com/shareef01/deutschflow/pull/103) introduces backup v3 XP award identities, merges distinct awards and uses logged activity totals. Legacy daily balances use a maximum baseline because their independent ancestry cannot be reconstructed. Revalidate same-day awards, overlapping history, legacy backups and repeated cross-platform import. |
| DATA-02 | Implemented in 1.1.0, concurrency revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) gathers Android export tables and the archived vocabulary ID inside one Room transaction. The original two-connection export/review interleaving experiment remains to be performed. |
| DATA-03 | Requires revalidation and remediation assessment | Android still derives imported streaks using UTC dates and preserves the maximum existing streak, while Web filters positive-XP activity using local dates. Revalidate timezone boundaries and zero-XP days before defining a shared rule. |
| DATA-04 | Requires revalidation and remediation assessment | The release change does not establish a user-visible roleplay import conflict workflow. Revalidate two distinct conversations, repeated import and cancellation, then decide how to report or preserve skipped content. |
| DATA-05 | Implemented in 1.1.0, interchange revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) normalizes Android's widened Float ease floor on export and in both importers. Regression coverage includes an Android export/import at the floor. Verify an actual exported file in both install-to-install directions. |
| DEP-01 | Version updated in 1.1.0, advisory revalidation required | [PR #103](https://github.com/shareef01/deutschflow/pull/103) updates Next.js from 16.4.4 to 16.4.6. Revalidate the original advisories and affected deployed routes against that version. This historical register does not establish current advisory status. |
| SRS-01 | Requires revalidation and remediation assessment | The original witness compares executed TypeScript with simulated Kotlin Float arithmetic. Reproduce the witness in the actual Kotlin scheduler and shared fixtures before choosing canonical arithmetic. |
| AI-01 | Requires revalidation and remediation assessment | Web still consumes Groq response bodies using `response.text()`. Measure bounded-read behavior with oversized synthetic success and error streams before closing the resource-bound finding. |
| PERF-01 | Requires measurement and remediation assessment | Web still loads and sorts a word's review events to obtain the latest timestamp. Measure realistic history sizes before choosing a compound-index migration. |
| CI-01 | Requires tooling alignment and revalidation | The workflow image remains Playwright 1.64.0 while the declared test client is 1.64.1. Align and verify the resolved client and image together, including the proposed digest pin. |

The [post-merge build for 1.1.0](https://github.com/shareef01/deutschflow/actions/runs/44011805535) passed. Passing CI supplies regression evidence for its covered paths. The follow-up experiments above remain pending, and this register does not issue a new production-readiness decision.

### Historical reproduction attachments

The [Kotlin observations](PRODUCTION_AUDIT_2026-11-16.repro.kt.txt) and [TypeScript observations](PRODUCTION_AUDIT_2026-11-16.repro.ts.txt) are preserved with their original instructions and assertions. Run them in an isolated checkout of the audited commit `e88d893195909cf5ab927891560e4ce38e0f07ae`. Their assertions intentionally reproduce the old defects, so a passing observation documents the original behavior. Current-release regression tests need assertions for the corrected behavior.

---

## Original audit

**Decision: Not ready for production.** Audited GitHub `main` at [e88d893195909cf5ab927891560e4ce38e0f07ae](https://github.com/shareef01/deutschflow/commit/e88d893195909cf5ab927891560e4ce38e0f07ae) on 2026-11-16. The existing checkout was on the sidebar-refinement branch. All source references below are relative to the isolated checkout of the audited commit.

## A. Executive summary

The Android and Web clients have meaningful safeguards: transactional review writes, bounded encrypted backup envelopes, migration fixtures, input limits on AI requests, and CI that builds both clients and tests real browsers and Android emulators. The web server is a shared-password gate around a local-first app. The principal release risks are bearer tokens that survive logout, unauthenticated lockout on a default self-hosted setup, and backup metadata that can override legitimate study state. Backup merge also undercounts independent learning activity and Android export can assemble a snapshot from different points in time. These findings affect availability, progress integrity, and the meaning of logout.

Seven audit-only observations were executed against production modules with synthetic data. The evidence files assert current behavior so an observation passing means the behavior was reproduced. The Android snapshot race and other source-only observations are labeled below. Production code was left untouched.

## B. Architecture and trust boundaries

| Area | Structure and flow |
|---|---|
| Android | `app/src/main/AndroidManifest.xml` → `MainApp.kt:17` and `MainActivity.kt:51` → Compose navigation (`ui/navigation/Navigation.kt:255–287`) → Hilt ViewModels → Room DAOs, settings DataStore and speech/AI helpers. Paths under this row are relative to `app/src/main/java/com/aus/deutschflow/` unless otherwise stated. |
| Android persistence | `data/local/AppDatabase.kt:39` and `di/DatabaseModule.kt:27–38` set the Room migration path. Destructive fallback is debug-only. `app/schemas/` contains versions 2–18. Device and cloud extraction exclusions cover settings and the learning database. The Groq bearer is decryptable by the running app using Android Keystore AES-GCM (`data/local/KeystoreCipher.kt:38–106`, `data/local/PreferenceManager.kt:80–98,138–166`). |
| Web | `web/src/proxy.ts:13–54` gates routes. `web/src/app/api/{login,logout}/route.ts` are the authentication endpoints. App Router provides statically generated shells and React client views/hooks. Dexie stores vocabulary, transcripts, reviews, roleplay, activity, stats and settings in the browser (`web/src/lib/db/schema.ts:200–267`). The service worker caches shell/static responses (`web/public/sw.js:236–365`). |
| Web trust boundaries | Browser → Next login → shared password, HMAC cookie, optional Redis throttle. Browser UI → IndexedDB and same-origin vault. A copied application DB alone does not include the vault key. A script executing on the origin can reach ciphertext and invoke WebCrypto with the stored key (`web/src/lib/db/vault.ts:2–22,65–74,257–265`). The site password gates server navigation. A browser profile remains the local data boundary. |
| External processing | Android uses an explicit on-device speech recognizer (`service/SpeechRecognizerHelper.kt:390–454`). Web speech uses the browser speech implementation (`web/src/lib/speech/recognizer.ts:140–193`). Local typed input offers a fallback, while AI translation, explanations and roleplay need network access. Both clients send selected text and a user-owned bearer directly to Groq. Redis receives throttle operations, not learning data. See `web/src/app/privacy/page.tsx:65–98`. |
| Cross-platform transfer | Explicit versioned JSON backups with optional authenticated encryption. No live account synchronization protocol was found. Web export uses a Dexie read transaction and import uses a multi-table write transaction (`backup.ts:76–103,553–563`). Android import uses Room's `withTransaction` (`BackupManager.kt:173–224`). |
| Repository | 464 tracked files. See Appendix 1 for the complete tracked directory map. `.github/` contains the build matrix and Dependabot. `gradle/` and the top-level build files define Android tooling. `app/src/{main,test,androidTest,benchmark}/` holds runtime code, JVM tests, device tests and benchmark configuration. `web/src/{app,components,hooks,lib,types}/`, `web/public/`, `web/tests/` and deployment/config files hold Web implementation and tests. `docs/` holds prior audits, design records, screenshots and measurements. `tools/` contains repository, contrast and startup scripts. |
| History and debt | Recent main changes include cross-tab erasure and transcript switching (#100), web shell/transcript changes (#101/#102) and dependency/startup work (#95). Parallel Kotlin/TypeScript implementations duplicate backup validation, schedule merge, practice grading and SRS. Throttle compatibility wrappers at `web/src/lib/auth/throttle.ts:729–757` are candidates for call-site cleanup once test dependencies are replaced. No production removal is proposed from a name match alone. |

Android uses AGP 9.5.1, Kotlin 2.4.21, Hilt 2.60.1, Room 2.8.5, Compose BOM 2026.09.00 and coroutines 1.11.0. The app targets/compiles SDK 37 with minSdk 31. Web declares React 19.3.1, Dexie 4.4.7, Next.js 16.4.4, Vitest 5.1.0 and Playwright 1.64.1. See `gradle/libs.versions.toml:1–32`, `app/build.gradle.kts:56–68` and `web/package.json:18–35`.

## C. Security findings

### SEC-01 · High · A copied bearer remains valid after logout

**Confirmed by direct execution of login, logout, and the real proxy.** At `web/src/app/api/logout/route.ts:37–57` the handler returns `clearSessionCookie(response, request)`. At `web/src/lib/auth/session.ts:184–185,193–206` the token is a signed expiry, and verification checks only expiry and signature. The default lifetime is 30 days and can reach 90 (`session.ts:24–36`).

A party who already obtained a valid cookie can replay it from another client after the original user logs out. The audit simulated copying the cookie, verified the logout response cleared it, then replayed it to `/study` and received proxy admission. This finding requires prior bearer acquisition and affects access to the gated shell. The replay does not transfer another browser's IndexedDB.

Issue unique bearer tokens and store a hash plus expiry in a durable session registry. Revoke the presented token before acknowledging logout. Preserve the existing client-side purge and cross-tab notification. Test replay from a separate context after logout, simultaneous sessions, expiry, remote backend failure, and global revocation. See replacement code in §J.

### SEC-02 · Medium · Default self-hosted throttle allows unauthenticated lockout

**Confirmed in the local route with Vercel and trusted-proxy configuration unset.** `web/src/app/api/login/route.ts:31–37,54–78,113–135` assigns unknown callers to the same `SHARED_CALLER_KEY` and throttles before password verification. `web/src/lib/auth/throttle.ts:31–54,145–173` begins backoff after three counted attempts, eventually reaching 30 seconds per admitted request.

An outside caller sends three wrong guesses and another caller's correct password receives 429. Continued requests at the cooldown boundaries can deny login to new or expired sessions. On distributed hosts without Redis, repetitions against different workers also limit the effectiveness of the per-process throttle. The test proves the default shared bucket. Production Vercel ingress and Redis policies were not exercised.

For public self-hosting, require a trusted ingress that strips and rewrites the client IP header, prevents direct backend access, and applies coarse request limits. Require an explicit distributed limiter configuration when scaling horizontally. Fail startup on missing production identity configuration, with an explicit single-user/private deployment mode if needed. Test separate identities, overwritten forged headers, direct origin access, IPv6 normalization, and store outages. See §J.

### SEC-03 · Medium · Imported study metadata can poison future schedule merges

**Confirmed on Web using the real Dexie merge and synthetic backup.** `web/src/lib/db/backup.ts:356–385` bounds adjacent schedule timestamps but leaves `lastStudiedAt` unchecked. The merge at lines 620–643 feeds `Math.max(num(row.lastStudiedAt, 0), incomingMaxReview)` into `withFurthestScheduleOf` (`web/src/lib/db/repository.ts:74–111`). Android reads the same unchecked field for an existing card (`BackupManager.kt:285–290,639–682`). Android exploit behavior was assessed from source.

A learner imports a malicious or corrupted backup containing `lastStudiedAt: 9007199254740991`. A later legitimate import which advances the card from one day to six days loses to the impossible timestamp. The observed card retained both the poisoned timestamp and the old interval. An attacker needs to induce import, and the impact is local study integrity.

Bound and type-check this field before merge on both platforms. Repair legacy poisoned stored values at merge time or via migration. Add atomic rollback cases for out-of-range, fractional and string timestamps and a valid cross-platform merge after repairing existing data. See §J.

### Verified controls and conditional risks

- **Sessions, CSRF, and input.** The proxy fails closed with missing signing configuration. Cookies are HttpOnly and SameSite=Lax (`login/route.ts:166–174`). The Secure flag depends on the production protocol header (`login/route.ts:146–150`), making trusted ingress part of the HTTPS boundary. Logout is POST-only, checks a supplied Origin and accepts an absent Origin (`logout/route.ts:25–34,51–69`). Login parses multipart before throttling and lacks a route-specific size check or parse-error handler (`login/route.ts:98–117`). These are hardening gaps. Cross-site session takeover, header spoofing through the real ingress, and remote resource exhaustion were unverified. Use small bounded URL-encoded or JSON input, strict origin checks for browser form traffic, and verify edge body limits and channel binding in staging. [Next.js self-hosting guidance](https://nextjs.org/docs/pages/guides/self-hosting)
- **XSS and injection.** Dynamic text paths render React text and Compose text. Scans of application source for HTML assignment, dynamic evaluation and SQL execution did not yield a confirmed injection sink. Room migration SQL and parameter-bound DAO queries are distinct from untrusted text. The CSP has an inline script allowance and outbound Groq access (`web/next.config.ts:54–60,75–77`). Same-origin script compromise would expose local data and the callable vault key. Audit script sources and move to nonce- or integrity-based enforcement with production hydration, offline and PWA verification. No XSS exploit was established.
- **Crypto and backup privacy.** Android and Web use PBKDF2-HMAC-SHA256 at 210,000 export iterations, random salt/IV and AES-256-GCM authentication. They bound envelope sizes and reject disallowed iteration counts before derivation (`BackupCrypto.kt:28–69,108–163`, `backup.ts:948–1000,1040–1129`). Plaintext exports contain learning content. Encrypted files can be guessed offline if protected with weak passphrases. Neither encryption nor passphrase possession validates semantic data, as SEC-03 illustrates. No committed live secrets were identified by filename review and a history diff search for Groq, GitHub, AWS and private-key patterns. Custom formats, encoded values, binary objects, unmerged remote-only refs, and hosting configuration were outside that search.
- **Dependencies.** npm audit returned zero advisories from its configured registry, while the Next.js maintainers explicitly list seven security fixes in 16.4.5 after the 16.4.4 pin (`web/package-lock.json:3506–3511`). This is a confirmed dependency exposure by version. A DeutschFlow exploit is unverified. The advisory prerequisites involve specific Cache Components, Edge, response-forwarding or image optimization paths. Source inspection found no app opt-in to Cache Components or Edge routing or handler returning third-party fetch responses. The self-hosted SVG advisory is particularly relevant to retest because `web/src/proxy.ts:59` excludes the image endpoint and `web/src/components/layout/AppShell.tsx:5` imports `next/image`. Default source allowlists constrain the candidate inputs seen in this app. Re-test the built image optimizer route and authenticated response headers against the specific advisories after updating. Dependabot [PR #98](https://github.com/shareef01/deutschflow/pull/98) is open and also bumps Playwright. [Next.js 16.4.5 release](https://github.com/vercel/next.js/releases/tag/v16.4.5) · [Maintainer advisories](https://github.com/vercel/next.js/security/advisories) · [SVG optimizer advisory](https://github.com/vercel/next.js/security/advisories/GHSA-7qx7-33qx-rqx2)
- **Supply chain and release.** Workflow permissions are read-only and action refs are SHA-pinned. npm uses `npm ci` and the Gradle distribution has a checksum. Playwright's image is tag-pinned at 1.64.0 while the JS package is 1.64.1 (`.github/workflows/build.yml:138`, `web/package-lock.json:1338`). CI succeeded at these pins. Align package and image digest together. Gradle lock/verification metadata and advisory scanning were not present in the tracked configuration. A full resolved Android advisory feed comparison was unverified. CI deliberately builds an unsigned APK and uploads reports (`.github/workflows/build.yml:21–28,62–81`). Publish signing, provenance, store rollout permissions, and byte-for-byte build reproducibility are unverified.
- **PWA privacy.** Service worker navigation caches observe logout and online auth redirects. Offline access uses the last local logout state (`web/public/sw.js:99–123,268–343`), so an expired server cookie remains usable for offline shell access until the browser can validate again. This is a deliberate offline policy with shared-device implications. Plain logout retains local study records by design (`web/src/lib/auth/logout.ts:285,291–296`). Verify user expectations and separate OS/browser profiles for shared devices. Tests cover explicit cross-tab erase.

## D. Bug report and subsystem assessment

### DATA-01 · High · Divergent backups silently undercount XP

Restore two branches with 10 XP and 15 XP from distinct days into one Web database. Both activity dates survive and cumulative XP is 15. Reimport is idempotent for this fixture. Web picks `Math.max` of lifetime totals at `web/src/lib/db/backup.ts:859–894`. Android does the same at `BackupManager.kt:336–370`. Max-per-day can additionally undercount independent same-day learning. A sum of aggregate balances would double-count common history. Use durable award IDs and an explicit legacy baseline, then materialize stats from unique awards. Exercise independent and shared ancestry, same day, repeated restores, deletion and legacy import in both directions.

### DATA-02 · High · Android export can split a committed review

Source-confirmed race. `BackupManager.kt:37–43` reads six tables through separate DAO calls, while `StudyViewModel.kt:235–268` commits schedule, review and XP together. If a review commits after vocabulary is read and before stats/events are read, the file can contain a newer review and XP paired with the earlier schedule. Restoring fresh can preserve the stale schedule and treat it as the most recently studied because the newer event supplies its timestamp. Move all export reads, including archived ID lookup at line 112, into one transaction. Exercise a two-connection/barrier interleave on real SQLite. No device interleaving run was performed.

### DATA-03 · Medium · Android/Web import calculate different streaks

Android feeds all dates into a UTC-anchored calculation and takes max with the existing streak (`BackupManager.kt:350–370,585–610`). Web filters positive XP and defaults to local today (`web/src/lib/db/backup.ts:241–264,859–894`). At 00:30 Berlin time, a backup with activity dated today can calculate streak 0 on an empty Android import if UTC is still yesterday. Android then derives the current dashboard streak from the stored timestamp (`data/local/entities/UserStatsEntity.kt:35–42`). Use the same time-zone-aware civil-date and qualifying-activity fixture for import and live stats. Include near-midnight UTC crossover, both DST transitions, zero-XP days and future dates. Source-derived and unverified on device.

### DATA-04 · Medium · A populated roleplay conversation shadows the imported one

Both importers restore roleplay only when the local conversation is empty (`web/src/lib/db/backup.ts:806–823`, `BackupManager.kt:375–394`). Import into a library with an unrelated local roleplay and the incoming conversation is skipped. This is a policy and UX defect if import is expected to preserve all backed-up content. Surface the skipped conversation in the import result and support an explicit replacement or archived conversation. Regression: two distinct conversations, repeat import, and cancellation without local mutation. Source-confirmed.

### DATA-05 · High · Both importers reject a valid Android export at the minimum ease

Android's scheduler clamps to `1.3f` (`service/SRSEngine.kt:98,120`), which the exporter widens to a Double at `data/local/backup/BackupManager.kt:69`. The resulting JSON number is approximately 1.2999999523162842. Both validators compare it against the Double/Number lower limit of 1.3 (`BackupManager.kt:671–675`, `web/src/lib/db/backup.ts:365–366`). One repeatedly missed card can make the whole Android backup unrestorable. The Web import and Android JVM validator rejected the serialized value in separate executed observations. A complete device-generated file was not used for this reproduction.

The ordinary Android and Web fixture vectors exercised plaintext validation, encrypted interchange, tamper/wrong-passphrase behavior and event-reference preservation. DATA-05 establishes a specific Android-to-Web and Android-to-Android boundary failure despite green fixture tests. The Web-to-Android tests validate Web-format fixtures on the JVM and the audited CI device suite exercises Room import. The audit did not run a full install-to-install export and restore. Add a fixture produced at the ease floor by the actual Android scheduler/exporter and restore on both platforms. Also include an older format without event arrays and malformed IDs that must roll back all writes.

### SRS-01 · Medium · Cross-platform interval divergence at a valid rounding boundary

For an Easy review at interval 15, ease 3 and count 3, the actual TypeScript scheduler returned 59 days. An exhaustive grid search applying the Kotlin Float operation order found 58 in binary32 (`app/src/main/java/com/aus/deutschflow/service/SRSEngine.kt:78–79,133`, `web/src/lib/ai/srs.ts:100–105`). The Float side of the grid is a simulation using `Math.fround`. The underlying Kotlin expression uses a 1.3f bonus, whose rounded product lies below 58.5. Choose canonical fixed-point arithmetic or equivalent Double arithmetic with explicit normalization of legacy Room Float values. Add the witness and generation across interval, ease, grade and caps to the shared fixture.

Both platforms reset on Again, grow on Hard, use the 1/6-day ramp on Good/Easy, clamp ease and cap interval at 365. Positive intervals target local midnight (`srs.ts:61–63,75–128`, `SRSEngine.kt:54–108,159–168`). Review event `actualDays` uses elapsed 24-hour periods (`review.ts:26–30`, `StudyViewModel.kt:447–448`), so calendar-day scheduling and observed elapsed days can differ during DST or when reviewing just after midnight. Decide the field's intended metric before changing its semantics. Stale snapshot checks, in-flight gates, event IDs and transactional writes reduce duplicate awards. Timezone-travel parity and recovery after process death between commit and UI acknowledgment remain unverified.

### Android reliability

Hilt scopes database/storage and ViewModels own scoped speech helpers. Compose uses lifecycle-aware collection and saveable tab state (`StudyScreen.kt:69–83,143–145`). Speech explicitly closes owned sessions, guards late callbacks and cancels pending delivery (`SpeechRecognizerHelper.kt:225–295,491–567`). Groq uses bounded read sizes and disconnects blocking streams on cancellation (`GroqHelper.kt:575–639,1084–1097`). The emulator suite exercises migrations and platform seams. Startup during a real upgrade, full process-death matrix, heap leaks, long-running battery behavior, release TalkBack behavior and real speech availability across OEMs remain unverified. Lint/build deprecations observed during local compilation include `announceForAccessibility` in `ErrorBanner.kt:78` and `TranscriptScreen.kt:102` plus a swipe state overload in `HistoryScreen.kt:419`.

### Web reliability and UX

The Web client has typed schema migrations, reports version-change blocking, aborts superseded AI operations and tests offline reload, erasure and late microphone permission. `web/src/lib/db/index.ts:17–55`, `web/src/hooks/useTranscript.ts:117–147,206–207`, and `web/src/hooks/useRoleplay.ts:128–154,242–287` are representative. No App Router `error.tsx` was found in the tracked app routes. Uncaught render or hydration errors fall through to framework handling. Local production Chromium checks exercised responsive breakpoints, two learning flows, keyboard controls, reduced motion, 200% text and axe. These are sampled checks and do not establish WCAG 2.2 AA conformance. German content has language attributes in learning and vocabulary views, notices use live regions, and tab controls have keyboard semantics. Production Safari/iOS install behavior, actual audio permission/provider behavior, manual screen-reader navigation and Android font-scale behavior across devices are unverified beyond CI fixtures.

For A1–C1 learners, level enters the prompt, so it modifies generated examples and roleplay without guaranteeing level-appropriate output. The workflow offers typed input, transcript → vocabulary, practice and review. Product suggestions are to show when a lesson item came from AI, offer correction before saving it, and explain that speech feedback measures recognized words in order. Functional defects above were classified independently of these suggestions.

### AI integration

The clients bound input size and roleplay history, use capped completion tokens, parse bounded fields and reject incomplete results. Translation and word lookup use JSON-object response mode. Roleplay permits tolerant text extraction. Model output flows into study surfaces and can be wrong even when valid. System prompts and user content are separated by role, but adversarial learner input can still cause plausible fabricated definitions, article/inflection errors or scenario escape. No model tool invocation, command execution or confirmed privilege escalation path was found. Sources: `web/src/lib/ai/groq.ts:67–108,121–143,308–350,388–505,633–682,749–830`; `service/GroqHelper.kt:196–244,349–393,516–568,830–1054`. [Groq structured outputs](https://console.groq.com/docs/structured-outputs)

Web reads success and error bodies with `response.text()` before parsing (`groq.ts:253–272`). That is an unbounded byte allocation despite per-field caps and an abort timer. A large, fast response from the fixed upstream can exhaust client memory. No hostile control of that endpoint was established. Use a bounded stream decoder and cancel unread retry bodies. Retry policy targets transient 5xx/network errors, with two retries and short backoffs. 429 maps to a message. Neither client was observed honoring provider Retry-After, pooling organization-level quota or caching translations deliberately. Add a local cooldown keyed by operation, respect rate reset and treat the retry budget as part of the request deadline. Verify with mocked 429, oversize response, partial stream, worker cancellation and a bilingual human-reviewed corpus including separable verbs, case ambiguity, gender, idioms and A1/C1 adaptation. Live German accuracy, live API latency and quota use were unverified. [Groq rate limit behavior](https://console.groq.com/docs/rate-limits)

## E. Performance findings

- **Web production output.** Local Next 16.4.4 production build compiled in 4.0 seconds and emitted 18 top-level chunk files totaling 1,104,345 raw bytes. The three largest JavaScript files were 229,052, 195,693 and 164,042 bytes. These are filesystem sizes and include chunks from multiple routes. Five authenticated Chromium `/study` navigations in fresh browser contexts against the local production server, with service workers blocked, recorded load-event ends of 144, 111, 117, 113 and 114 ms (114 ms median). Observed encoded JavaScript bodies totaled 216,276 bytes per navigation. This measures localhost on the audit host before deploying to a CDN, and it provides no mobile or paint latency claim.
- **Web backup.** The existing fake-IndexedDB case passed with 9,000 vocabulary rows, 5,000 transcripts, a plaintext payload over 20 MiB and encryption/decryption/restoration (`web/tests/backup-scalability.test.ts:25–110`). The byte cap is 50 MiB plaintext and 70 MiB encrypted. Import materializes string, parsed rows and table data in memory (`backup.ts:510–563,584–596,780,861`). The test establishes behavior on the audit host and provides no real browser peak memory figure. Run near-limit imports on mobile and measure peak heap, main-thread blocking and storage quota failures.
- **Review hot path.** Web sorts all events for the selected card to read the latest timestamp (`web/src/lib/db/review.ts:26–28`), whereas Android fetches one via DAO (`StudyViewModel.kt:239`). Add a Dexie compound key `[vocabularyId+reviewedAtTimestamp]` in a new version and read the final keyed event. Measure one card with 10,000 historical reviews. Web vocabulary pulls all rows before mapping cards to DOM (`repository.ts:473–482`, `web/src/app/(app)/vocabulary/page.tsx:327`). Measure filter/render at 1k and 10k and virtualize or page if interactions regress.
- **Android release size and startup.** The audited local build produced a 4,250,635-byte unsigned release APK. The repository has a prior Pixel 7/API 37 process-cold median of 275 ms over ten `am start -S -W` runs for a separately identified optimized benchmark APK. The earlier debug artifact reports 2,255.5 ms median. Different build modes and revisions prevent attributing that difference to a specific optimization or to this commit. See `docs/measurements/android-startup-{debug-2026-11-05,optimized-2026-11-07}.json`. Current-revision startup, memory, frame pacing, AI latency and speech power were not benchmarked.

## F. Tests and CI/CD

[GitHub Actions run 43787238945](https://github.com/shareef01/deutschflow/actions/runs/43787238945) identifies the exact audited SHA and reports seven successful jobs. Android runs JVM unit tests, debug lint, unsigned release assembly and API 31 full instrumentation, with API 35/36 platform smoke. Web runs lint, type checking, unit tests and production build, plus Chromium, WebKit and Firefox contracts. The Chromium project includes the full browser suite, while Firefox and WebKit are limited to selected smoke specs (`web/playwright.config.ts:38–52`). The GitHub check results establish job status. Device instrumentation and Firefox/WebKit were reviewed via GitHub CI results rather than rerun locally. The connected physical phone was not used to install a test build. Credential- or platform-dependent cases may skip. `GroqLiveTest.kt:42–52,87–95` conditionally skips when no key is set, so CI success supplies no evidence of live model correctness.

| Executed locally | Outcome |
|---|---|
| `python tools/audit_repo.py` | Passed, including 315 Android and 307 Web localized keys, token parity and enumerated contrast pairs. |
| Web `npm ci`, lint, TypeScript and production build | Completed successfully with Node 24.16.0. CI uses Node 22 in the named web job. |
| Android `testDebugUnitTest`, `lintDebug`, `assembleRelease` | Build succeeded. 294 JVM tests across 38 suites, zero skipped or failed. Lint reported zero errors and four warnings (two available dependency updates and two plural candidates). Release assembly used R8 and produced an unsigned APK. |
| Web `npm test` | 584 passed across 41 files. |
| Web Chromium production browser suite | 123 passed in 1.2 minutes. |
| Audit observations | Six Web observations and one Android JVM observation passed. Coverage includes route handlers/proxy, fake IndexedDB, the Web scheduler and the Android backup validator. See the adjacent `.repro.ts.txt` and `.repro.kt.txt` files. |
| npm advisory audit | Registry returned zero advisory records. Independent maintainer advisories supersede this as evidence of the version exposure. |

Priority gap tests are stolen-cookie replay, unconfigured multi-caller throttling, a cross-device golden backup with divergent history and archive mapping, semantically corrupt backup timestamps, atomic Android export, generated SRS parity, near-limit real browser import, and representative live German output. Existing backup vectors and mocked AI checks are useful contracts but do not exercise every real export/import interaction, device upgrade or live provider behavior. A test named for feature availability may early-return on absent configuration. Read the branch outcome or skip count before treating it as feature verification.

## G. Remediation roadmap

| Priority | Work | Complexity | Dependencies and acceptance |
|---|---|---|---|
| P0 | No Critical finding established | — | If staging demonstrates unauthenticated protected-route access or public cookie disclosure, stop rollout and rotate the session epoch after patching. |
| P1 | Revoke individual sessions and enforce safe ingress | High | Session store, ingress identity and HTTPS policy, replay and store failure tests. Covers SEC-01 and SEC-02. |
| P1 | Restore valid Android backups, validate schedule metadata and snapshot Android exports | Medium | Shared backup contract, an ease-floor fixture, repair migration and real Room concurrency test. Covers SEC-03, DATA-02 and DATA-05. |
| P1 | Preserve independent XP with idempotent merge | High | Stable event identities, legacy baselines, fixture and version migration design. Covers DATA-01. |
| P1 | Patch Next.js and align browser tooling | Low–Medium | Maintainer advisories, lockfile update, matched Playwright image, browser and staging route retests. |
| P2 | Harmonize SRS/streak and disclose import conflicts | Medium | Cross-platform fixtures, local date policy, roleplay conflict UX. |
| P2 | Bound AI response reads and test retry and accuracy | Medium | Stream fixtures, Groq sandbox key, A1–C1 review corpus, local quota state. |
| P2 | Add reproducible release verification | Medium | Dependency verification metadata, signed artifact path, deployment configuration and evidence of provenance. |
| P3 | Optimize large lists and review history queries | Medium | Start with route and heap traces, then schema migration or UI virtualization as supported by measurements. |

## H. Readiness decision

**Not ready for production at this commit.** The release gates are replay after logout, public self-host ingress and rate limiting, backup poisoning, coherent and restorable Android exports, and divergent progress preservation. Patch the pinned framework version and retest the affected deployment paths. Obtain evidence for a signed Android release and deployed HTTPS/cookie behavior before changing the decision. Actual production config, signing keys, provider-side data retention, and release-channel controls were outside the audited evidence.

## I. Detailed findings register

Android paths below are relative to `app/src/main/java/com/aus/deutschflow/`.

| ID | Severity | Category | File:Line | Evidence | Impact | Recommended Fix | Confidence |
|---|---|---|---|---|---|---|---|
| SEC-01 | High | Session replay | `web/src/lib/auth/session.ts:184,193–206`, `web/src/app/api/logout/route.ts:37–57` | Copied cookie admitted after logout | Continued shell access after bearer theft | Durable token-specific revocation | High, executed |
| DATA-01 | High | Progress merge | `web/src/lib/db/backup.ts:889–894`, `data/local/backup/BackupManager.kt:336–370` | 10 and 15 independent XP merge to 15 | Progress undercount | Deduped award ledger | High for Web, executed |
| DATA-02 | High | Backup snapshot | `data/local/backup/BackupManager.kt:37–43,112`, `ui/viewmodel/StudyViewModel.kt:235–268` | Independent backup reads overlap transactional review | Inconsistent restore | Transactional read snapshot | High, source |
| DATA-05 | High | Backup compatibility | `data/local/backup/BackupManager.kt:69,671–675`, `web/src/lib/db/backup.ts:365–366` | Both validators reject widened Android ease floor | Whole restore fails for a legitimate backup | Canonicalize Float floor, support valid legacy representation | High, executed |
| SEC-02 | Medium | Availability | `web/src/app/api/login/route.ts:31–37,54–78,113–135` | Three bad guesses yield 429 on a correct request | Shared login lockout | Verified ingress identity and limiter | High, executed |
| SEC-03 | Medium | Untrusted import | `web/src/lib/db/backup.ts:356–385,620–643`, `data/local/backup/BackupManager.kt:285–290` | Out-of-range timestamp defeats valid newer schedule | Sticky local schedule poisoning | Validate and repair timestamps | High for Web, executed |
| DEP-01 | Medium | Dependency | `web/package-lock.json:3506–3511` | Maintainer patches follow installed version | Advisory-specific reachability | Update and retest | High version, conditional exposure |
| DATA-03 | Medium | Streak | `data/local/backup/BackupManager.kt:350–370,585–610`, `web/src/lib/db/backup.ts:859–894` | UTC/local and qualification mismatch | Wrong streak around restore | Shared civil-date rule | High source, device unverified |
| DATA-04 | Medium | Conflict handling | `web/src/lib/db/backup.ts:806–823`, `data/local/backup/BackupManager.kt:375–394` | Existing conversation causes skip | Silent skipped history | Report and resolve conflict | High, source |
| SRS-01 | Medium | Scheduling | `service/SRSEngine.kt:78–79,133`, `web/src/lib/ai/srs.ts:100–105` | 58 versus 59 at 15 days, ease 3 | Due-date parity | Shared arithmetic and fixtures | High, Web executed and Float simulated |
| AI-01 | Low | Resource bounds | `web/src/lib/ai/groq.ts:253–272` | Entire response consumed before size checks | Upstream-dependent client memory exhaustion | Bounded streaming reads | High code, exploit unverified |
| PERF-01 | Low | Database | `web/src/lib/db/review.ts:26–28` | Reads and sorts history for a single latest timestamp | Review slows as history grows | Compound range lookup | High complexity, latency unmeasured |
| CI-01 | Low | Supply chain | `.github/workflows/build.yml:138`, `web/package-lock.json:1338` | Tag 1.64.0 and test client 1.64.1 | Upgrade drift | Paired version and image digest | High, source |

## J. Proposed code fixes

These are review snippets. They were not applied or tested against an implementation of the proposed session store or XP ledger.

**SEC-01. Token-specific registry.** Replace expiry-only token issuance and verification via a durable store with atomic TTL and lookup/delete. The example assumes a store with authenticated reads/writes, an expiry TTL and a namespace derived from the deployment's session epoch. Keep the shared password verification at login. During rollout invalidate existing stateless tokens and use an HTTPS-only cookie for production with a distinct local test setup.

```ts
import { createHash, randomBytes } from "node:crypto";

const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
const sessionKey = (token: string) =>
  `sessions:${sessionEpoch()}:${createHash("sha256").update(token).digest("hex")}`;

async function issueSession(ttlSeconds: number): Promise<string> {
  const token = randomBytes(32).toString("base64url");
  await sessionStore.putWithTtl(sessionKey(token), "active", ttlSeconds);
  return token;
}

async function hasSession(token: string | undefined): Promise<boolean> {
  return !!token && tokenPattern.test(token)
    && await sessionStore.get(sessionKey(token)) === "active";
}

async function revokeSession(token: string | undefined): Promise<void> {
  if (token && tokenPattern.test(token)) await sessionStore.delete(sessionKey(token));
}
```

Call `revokeSession` from the same-origin logout POST before clearing the cookie. Map store failures to a bounded 503 on login and logout, and deny protected access when verification fails. Include password/secret rotation in registry namespacing or bump the epoch on rotation.

**SEC-02. Require a trusted identity for public production ingress.** Apply this after binding the HTTP server to private ingress, and configure the ingress to overwrite the header. Use platform-specific verified identity on Vercel and canonicalize IP addresses before indexing the limiter.

```ts
const caller = extractCallerKey(request.headers);
if (process.env.PUBLIC_AUTH_INGRESS === "required" && caller === SHARED_CALLER_KEY) {
  return NextResponse.json({ error: "Login is temporarily unavailable." }, {
    status: 503,
    headers: { "Cache-Control": "no-store" },
  });
}
const admission = await admitLoginAttempt(caller);
```

Validate `PUBLIC_AUTH_INGRESS` and its Redis/identity settings at startup in the public deployment. Couple this snippet with ingress enforcement. Setting a trusted header in the app alone cannot authenticate its sender.

**SEC-03. Validate the import field on each platform and repair existing data.** Add next to the vocabulary timestamp checks before writes.

```ts
const studiedAt = row.lastStudiedAt;
if (studiedAt !== undefined
    && (typeof studiedAt !== "number" || !Number.isSafeInteger(studiedAt)
        || studiedAt < 0 || studiedAt > MAX_FUTURE_TIMESTAMP)) {
  throw new ImportError("invalid", "Invalid lastStudiedAt timestamp.");
}
```

```kotlin
if (row.has("lastStudiedAt")) {
    val studiedAt = when (val value = row.opt("lastStudiedAt")) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw BackupException.InvalidFormat("Invalid lastStudiedAt timestamp.")
    }
    if (studiedAt !in 0L..MAX_FUTURE_TIMESTAMP) {
        throw BackupException.InvalidFormat("Invalid lastStudiedAt timestamp.")
    }
}
```

For existing Web rows, replace invalid stored study times with the newest valid associated review event, or undefined if there is none. Test missing legacy fields and integer representations on both serializers.

**DATA-02. Snapshot Android export.** Group the existing DAO reads into one transaction and serialize/encrypt outside it.

```kotlin
val snapshot = database.withTransaction {
    BackupExportSnapshot(
        vocabulary = vocabularyDao.getAllVocabularyList(),
        transcripts = transcriptDao.getAllTranscriptsList(),
        userStats = userStatsDao.getUserStatsOnce(),
        activityLog = activityDao.getAllActivity(),
        roleplay = roleplayDao.getConversation(),
        reviewEvents = reviewEventDao.getAllEventsList(),
        archivedVocabId = vocabularyDao.findByKey(ARCHIVED_VOCABULARY_KEY)?.id,
    )
}
// Encode the captured snapshot with the current JSON format.
```

**DATA-01. Idempotent awards.** In a new format/schema version, persist an award record with a stable ID, amount and local date in the same transaction as each review-derived XP grant. During import, insert by stable ID with conflict detection, then compute daily and lifetime totals inside the transaction. Carry a legacy baseline with provenance and report overlapping legacy aggregates as ambiguous. The existing backups lack enough information to recover independent same-day awards universally.

**DATA-05. Canonicalize the ease floor on export and accept its legacy encoding.** In Android export, write `maxOf(1.3, item.easeFactor.toDouble())` for valid Room rows. In both importers, normalize the exact legacy floor before applying the existing finite/range check, and use the normalized value throughout merge. Other invalid values should still fail validation.

```ts
function normalizeBackupEase(value: unknown): unknown {
  return value === Math.fround(1.3) ? 1.3 : value;
}
```

```kotlin
private fun normalizeBackupEase(value: Double): Double =
    if (value == 1.3f.toDouble()) 1.3 else value
```

**SRS-01. Shared rounding.** Specify how legacy Float ease is normalized on load, then use the same scaled-integer multiply and half-up division on both clients. Extend the shared fixture with interval 15/ease 3/Easy and generated boundary cases around the half-day point. Simply casting a stored Float to Double can preserve its prior representation error.

## Appendix 1. Tracked directory map

Generated with `git ls-tree -d -r --name-only HEAD` at the audited SHA.

```text
.github
.github/workflows
app
app/schemas
app/schemas/com.aus.deutschflow.data.local.AppDatabase
app/src
app/src/androidTest
app/src/androidTest/java
app/src/androidTest/java/com
app/src/androidTest/java/com/aus
app/src/androidTest/java/com/aus/deutschflow
app/src/androidTest/java/com/aus/deutschflow/data
app/src/androidTest/java/com/aus/deutschflow/data/local
app/src/androidTest/java/com/aus/deutschflow/service
app/src/androidTest/java/com/aus/deutschflow/ui
app/src/androidTest/java/com/aus/deutschflow/ui/components
app/src/androidTest/java/com/aus/deutschflow/ui/screens
app/src/androidTest/java/com/aus/deutschflow/ui/viewmodel
app/src/benchmark
app/src/main
app/src/main/java
app/src/main/java/com
app/src/main/java/com/aus
app/src/main/java/com/aus/deutschflow
app/src/main/java/com/aus/deutschflow/data
app/src/main/java/com/aus/deutschflow/data/local
app/src/main/java/com/aus/deutschflow/data/local/backup
app/src/main/java/com/aus/deutschflow/data/local/dao
app/src/main/java/com/aus/deutschflow/data/local/entities
app/src/main/java/com/aus/deutschflow/data/model
app/src/main/java/com/aus/deutschflow/di
app/src/main/java/com/aus/deutschflow/service
app/src/main/java/com/aus/deutschflow/ui
app/src/main/java/com/aus/deutschflow/ui/components
app/src/main/java/com/aus/deutschflow/ui/navigation
app/src/main/java/com/aus/deutschflow/ui/screens
app/src/main/java/com/aus/deutschflow/ui/theme
app/src/main/java/com/aus/deutschflow/ui/viewmodel
app/src/main/java/com/aus/deutschflow/ui/widget
app/src/main/java/com/aus/deutschflow/util
app/src/main/res
app/src/main/res/drawable
app/src/main/res/layout
app/src/main/res/mipmap-anydpi-v26
app/src/main/res/values-de
app/src/main/res/values-night
app/src/main/res/values
app/src/main/res/xml
app/src/test
app/src/test/java
app/src/test/java/com
app/src/test/java/com/aus
app/src/test/java/com/aus/deutschflow
app/src/test/java/com/aus/deutschflow/contract
app/src/test/java/com/aus/deutschflow/data
app/src/test/java/com/aus/deutschflow/data/local
app/src/test/java/com/aus/deutschflow/data/local/backup
app/src/test/java/com/aus/deutschflow/data/model
app/src/test/java/com/aus/deutschflow/service
app/src/test/java/com/aus/deutschflow/ui
app/src/test/java/com/aus/deutschflow/ui/components
app/src/test/java/com/aus/deutschflow/ui/screens
app/src/test/java/com/aus/deutschflow/ui/viewmodel
app/src/test/java/com/aus/deutschflow/util
app/src/test/resources
docs
docs/measurements
docs/screenshots
docs/screenshots/android
docs/screenshots/portfolio
docs/screenshots/web
gradle
gradle/wrapper
tools
web
web/.vscode
web/public
web/public/icons
web/src
web/src/app
web/src/app/(app)
web/src/app/(app)/history
web/src/app/(app)/practice
web/src/app/(app)/settings
web/src/app/(app)/study
web/src/app/(app)/transcript
web/src/app/(app)/vocabulary
web/src/app/api
web/src/app/api/login
web/src/app/api/logout
web/src/app/login
web/src/app/privacy
web/src/components
web/src/components/layout
web/src/components/ui
web/src/hooks
web/src/lib
web/src/lib/a11y
web/src/lib/ai
web/src/lib/auth
web/src/lib/db
web/src/lib/navigation
web/src/lib/speech
web/src/lib/ui
web/src/types
web/tests
web/tests/portfolio
```
