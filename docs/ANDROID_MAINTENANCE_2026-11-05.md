# Android maintenance follow-up — 5 November 2026

Baseline: e5c1020. Follow-up to the historical audit revalidation.

## Dependency holds

Removed historical exact-version Dependabot exclusions across Gradle, npm, and
GitHub Actions. Their recorded rationale was closed October update PRs, rather
than a documented incompatibility. This restores proposals, including versions
still reported by Android lint. It does not upgrade installed dependencies or
establish compatibility with those proposals. Weekly grouping remains enabled;
each proposed update must pass the existing build, release/R8, unit, and relevant
device checks before merging. Review Room runtime/compiler/testing together and
exercise database migrations; test navigation/back handling, widgets, and daily
work when their respective libraries change. Toolchain upgrades need the full
KSP/Hilt/Room processing and release build.

The version catalog's explicit serialization/coroutines/concurrent-futures
constraints retain their documented runtime/test compatibility reasons.

## Unused resources

Removed five unused Android string names from both English and German:
action_saving, roleplay_scenario_berlin_bakery, roleplay_voice_unavailable,
practice_speech_unavailable, and settings_tts_privacy. Repository-wide reference
search found definitions only. settings_tts_privacy_desc remains used and retained.

## Startup baseline

`tools/measure_android_startup.py` requires an explicit device serial, bounds the
run count and command timeout, checks launch success, and saves individual timing
samples plus device/build metadata. It force-stops and launches only DeutschFlow;
it does not clear data, alter compilation mode, or invoke AI features.

Example (device unlocked and idle; close competing apps):

```powershell
python tools/measure_android_startup.py --serial DEVICE --runs 10 --output startup.json
```

The [recorded baseline](measurements/android-startup-debug-2026-11-05.json) used the
previously installed e5c1020 debug APK on a physical Pixel 7, API 37, over wireless
ADB. Ten process-cold launches had median ActivityManager TotalTime 2255.5 ms,
minimum 1840 ms, maximum 2629 ms. No launches failed. Device data was retained.
This run does not control thermal state, compilation, disk caches, or background
device load. It measures activity launch completion, not full interactivity or a
production startup percentile. There is no before/after optimization claim.

Before changing startup scheduling or generating app-specific profiles, measure a
release-like, non-debuggable/profileable build with Macrobenchmark
StartupTimingMetric, cold/warm iterations and retained Perfetto traces on an idle
physical device. Compare identical compilation modes and datasets. Investigate
main-thread traces before changing WorkManager initialization or scheduling.
Existing packaged dependency profiles remain in place.

## Diagnostics review and operational handoff

The app has local warning paths for startup housekeeping and handled ViewModel
failures, and cancellation remains separate from errors. There is no configured
remote handled-error reporting destination. Local logs are not evidence of remote
collection. No reporting SDK, new data collection, or crash handler was added.

Play Console verification is still external work: select com.aus.deutschflow and
the published release/version, inspect Android vitals crash/ANR and startup views,
check device/version coverage and reporting delays, verify release R8 mapping
availability, and record the review date and observed coverage. Sideloaded debug
launches do not verify production vitals. Console access was unavailable here.

If remote handled-error reporting is desired, first choose its owner, destination,
retention and user disclosure. Limit events to operation/error categories and app
version; exclude API keys, transcripts, conversation history, vocabulary, and raw
provider bodies. Review throwable messages before transmitting any exception.
This is a product/privacy decision, not a prerequisite for platform vitals.

## Validation

Repository static audit passes with 315 Android strings in each locale and 295 web
keys. Android debug assembly, all 294 unit tests, and lint passed. Lint now has
12 warnings and no errors: ten dependency notices and two fixed-input-length
plural candidates. All five unused-resource warnings are gone. The startup tool
completed ten successful launches on the connected Pixel. No dependency upgrade
or release-runtime performance claim is made.

## Platform references

- [Android startup timing](https://developer.android.com/topic/performance/issues/launch-time)
- [Macrobenchmark setup and startup measurement](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [Android vitals and consent-dependent coverage](https://developer.android.com/google/play/vitals)
