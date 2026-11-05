# Historical Android audit revalidation — 5 November 2026

Baseline: `bd023db`, synchronized with origin/main at the start of verification. Rechecks the A-series observations in [the 2 November audit](AUDIT_2026-11-02.md). Initial verification changed no application code: a temporary instrumentation observation test was compiled, run, and removed. The four confirmed code findings were subsequently fixed and tested; see Subsequent remediation below.

## Verification findings at bd023db

| Original item | Verified status | Evidence and correction |
|---|---|---|
| A-01 / A-01b — backup thread and stream lifetime | Resolved | `696137f` moves processing to IO and opens/closes streams within the coroutine. SettingsViewModelTest includes off-main stream assertions, encrypted round-trip and error/closure regressions. Prior device verification is recorded in the 5 November report. |
| A-02 — Android AI retry | Open | `GroqHelper.postTo` (`service/GroqHelper.kt:541`) performs one POST, then either returns or throws. No retry loop/backoff exists in the Android production path. The web client still has bounded retries. The historical cross-client remediation claim remains overstated. |
| A-03 — singular review text | Open; device-reproduced | `values/strings.xml:126` and `values-de/strings.xml:132` remain plain strings; StudyScreen line 243 still calls stringResource. Device output for count 1: `1 cards reviewed` and `1 Karten wiederholt`. |
| A-04 — crash/diagnostic reporting | Operational choice; Play coverage unverified | No third-party reporting SDK or custom crash handler exists in the repository. This does not prove Play crash/ANR reporting is absent: Android vitals collects eligible, consenting devices' data independently. Handled errors logged locally still have no configured remote reporting path. Play Console data/access was not available for verification. Adding a custom uncaught handler is not a prerequisite for Android vitals. |
| A-05 — clipboard sensitivity | Open hardening item; source-verified | TextFormatting.kt line 31 still creates plain text without `ClipDescription.EXTRA_IS_SENSITIVE`. Android documents this flag as protecting the clipboard preview. It is not encryption and does not guarantee that every keyboard/clipboard consumer forgets copied text. The probe could not read clipboard metadata at its observation point, so no device-level metadata claim is made. |
| A-06 — clipboard label localization | Open; source-verified | TextFormatting.kt line 26 remains `TRANSCRIPT_CLIP_LABEL = "German Transcript"`; all copies use that label. The literal cannot localize. Whether a particular keyboard/system surface displays it was not established. |
| A-07 — default locale stays at system language | Refuted on API 36; historical cause incorrect | On an en-US system with app locale de-DE, the launched app reported Locale.getDefault() = de_DE and resource locale = de_DE; both date formatters produced `Januar`. English app locale produced en_US/January. This disproves the reported mismatch for the tested supported per-app language path; it is not an exhaustive OEM/API matrix. |
| A-08 — dependency ignores | Still a maintenance choice; narrower than the original wording | Current lint has 10 dependency warnings. Eight correspond to exact ignored versions: four Room artifacts, Compose BOM, core-ktx, navigation-compose, and WorkManager. KSP now reports 2.3.13, whereas the ignore is 2.3.12; Glance 1.2.0 is not ignored. The configuration allows later versions, rather than suppressing all future updates. Reviewing exact-version holds remains useful. |
| A-09 — unused strings | Partially resolved; five remain | `ai_cancelled` is now used by interrupted roleplay recovery. Current lint reports action_saving, roleplay_scenario_berlin_bakery, roleplay_voice_unavailable, practice_speech_unavailable, and settings_tts_privacy as unused. |
| A-10 — startup/profile support | Requires measurement; two historical assertions incorrect | MainApp still schedules daily work unconditionally and launches timezone/key housekeeping. However, the merged manifest already contains WorkManagerInitializer before Application.onCreate and a transitive ProfileInstallerInitializer. The release APK contains assets/dexopt/baseline.prof and baseline.profm. There is no dedicated app macrobenchmark/baseline-profile module, but that is not equivalent to having no profile support. The two housekeeping coroutines run on IO; their completion before the first frame was not demonstrated. No new startup latency/frame measurement was made. |

## Verification performed

- `testDebugUnitTest lintDebug assembleRelease`: build successful. 287 unit tests, 0 failures/errors/skips; unit results were up to date. The release variant compiled and R8/resource shrinking completed during this run.
- Lint: 18 warnings, 0 errors: 10 GradleDependency, 3 PluralsCandidate, 5 UnusedResources. Two plural warnings concern fixed input-length messages; the review-count warning is the reproduced defect.
- Release output: `app-release-unsigned.apk`, 4,252,151 bytes. R8 mapping.txt exists. Both baseline profile assets are packaged. This verifies build/shrinker output, not release runtime behavior or publication readiness.
- API 36 read-only emulator, system locale en-US, temporary observation test with English and German app locales. Both final locale runs passed. An initial English instrumentation launch reported process failure before producing observations during emulator startup; no successful result is attributed to that attempt. System services also logged startup ANRs/crashes, so this run is not evidence for app startup timing or a crash-free device matrix.
- German observations: `default=de_DE configured=de_DE defaultMonth=Januar configuredMonth=Januar`; review text `1 Karten wiederholt`.
- English observations: `default=en_US configured=en_US defaultMonth=January configuredMonth=January`; review text `1 cards reviewed`.
- Clipboard metadata getter returned null in the observation callback. The sensitivity/label conclusions above come from the actual helper source, not that null result.
- The emulator was shut down and the temporary test source removed. No private API key or live AI request was used.

## Older coverage gaps revisited

- Release/R8 build uncertainty: now closed at build level. Runtime validation of the unsigned release APK was not performed.
- Backup thread-ownership regression gap: closed by the previously delivered SettingsViewModelTest coverage and device checks.
- Live AI CI coverage: still conditional. GroqLiveTest reads the device's stored key and returns early when absent; the workflow does not provision one. Offline parser/network tests do not establish a successful authenticated provider call. No live call was made during this verification.
- Android UI/accessibility coverage: existing Compose tests remain narrow; this pass did not perform a full Android accessibility audit.
- Startup performance and Play Console diagnostics: remain measurement/account-level questions, not verified application defects.

## Sources used to correct platform claims

- [Android per-app language preferences](https://developer.android.com/guide/topics/resources/app-languages) and [LocaleManager API](https://developer.android.com/reference/android/app/LocaleManager) describe per-app configuration and process locale preferences. Device observations above are the direct evidence for this app.
- [AOSP ActivityThread locale-list handling](https://android.googlesource.com/platform/frameworks/base/+/6e917454f218709a886a642c6baac2eea77805b8/core/java/android/app/ActivityThread.java) shows selection of the app resource locale via LocaleList.setDefault. This historical source supports the mechanism; it does not substitute for the API 36 observation.
- [Android vitals](https://developer.android.com/google/play/vitals) describes platform-collected, consent-dependent stability metrics available through Play Console and the reporting API.
- [Android copy/paste guidance](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) documents the sensitive-content flag and clipboard-preview behavior.

## Follow-up identified during verification

Concrete code fixes: A-02, A-03, A-05, A-06. Maintenance review: A-08 and the remaining five resources in A-09. Product/operations decisions: handled-error diagnostics and whether to add startup benchmarks/custom profiles. Do not implement the original A-07 recommendation as a fix for a mismatch that was not reproduced.


## Subsequent remediation

The four concrete code findings identified above are implemented:

- **A-02:** Android now uses at most two retries (three POST attempts total), with 300 ms and 600 ms backoff. HTTP 500/502/503/504 and transport IO failures are retryable. HTTP rejections such as 400/401/403/404/429, timeouts, parsing/size failures, and cooperative cancellation fail immediately. Each attempt retains bounded reads, stream closure, and the existing cancellation-driven socket disconnect. The actual retry loop is exercised with network-reset failures and real loopback HTTP responses. No live provider key is used.
- **A-03:** Review summaries use English/German quantity resources and pluralStringResource. One card is rendered as `1 card reviewed` / `1 Karte wiederholt`; larger counts use the plural forms.
- **A-05:** Both transcript/history copy actions use the shared sensitive clip builder. The metadata key fallback supports API 31/32; API 33+ uses the public ClipDescription constant. Copied text is unchanged.
- **A-06:** The shared clipboard label is a localized resource: `German Transcript` / `Deutsches Transkript`.

The Kotlin and TypeScript contract tests now read retry counts, delay values, and retryable/permanent status sets from the same cross-platform fixture. Web retry behavior itself is unchanged; its existing constants and predicate are exported for the contract assertions.

Regression coverage adds GroqRetryTest and TextFormattingContractTest. The resource/clipboard tests inspect the actual clip data passed to Android, avoiding the clipboard read restriction encountered by the earlier observation probe.

Final remediation verification:

- Android: 294 unit tests passed, with no failures, errors, or skips. Debug/test APK assembly, lint, and release/R8 assembly passed. Lint reports 17 warnings and no errors: 10 dependency notices, five unused resources, and two fixed-input-length plural candidates. The review-summary plural warning is resolved; the clipboard implementation introduces no API-level warning.
- Web: type checking, lint, and all 583 unit tests passed, including the shared retry contract.
- API 36 isolated emulator: all four selected instrumentation tests passed (two resource/clipboard regressions and two existing platform contracts). Both English/German labels and quantity forms were asserted, along with sensitive metadata, payload preservation, and plain-text MIME type. An earlier attempt failed at app startup before any test result during concurrent build/system startup load; it is not counted as passing. The successful rerun used the final APKs after release compilation completed.
- Repository static locale/palette/contrast audit and git diff whitespace check passed. No live provider request was made. The temporary isolated emulator was shut down and its workspace-local AVD/logs removed.

All four confirmed code findings are closed. Dependency holds, the five remaining unused strings, startup measurement, and Play Console/handled-error diagnostics remain separate maintenance or operational work.
