# DeutschFlow — Audit & Refactoring Report

A line-by-line audit of the Kotlin / Compose / Room / SpeechRecognizer / Groq stack,
followed by the refactor described below. Findings are ordered by impact. Each entry
names the flaw, the fix, and where it lives now.

---

## 1. Vulnerability & Flaw Report (prioritized)

### P1 — TTS playback had no AudioFocus and could bleed into the microphone
`TTSHelper` called `TextToSpeech.speak()` with no audio-focus request, and nothing
stopped a phrase before the Practice screen opened the microphone. A still-playing
German phrase would be picked up by `SpeechRecognizer` and scored as the user's own
voice.
- **Fix:** `TTSHelper` now requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per phrase and
  abandons it in a `UtteranceProgressListener` (`onDone`/`onError`). A new `stop()`
  releases focus and halts playback, and `PracticeViewModel.startPractice()` calls it
  before `startListening`.
- **Files:** `service/TTSHelper.kt`, `ui/viewmodel/PracticeViewModel.kt`.

### P1 — Practice screen centre was a large void / dead space
Between the target card and the action row the screen had an unbounded empty region;
the only content (spinner / intro / result) hugged the top and left a slab of black.
- **Fix:** the middle is now one `GlassmorphicCard` that owns the space and always
  renders: a live `AudioWaveform` + partial text while listening, a spinner while
  processing, the instruction when idle, and the verdict + heard text after.
- **Files:** `ui/screens/PracticeScreen.kt`, `ui/components/AudioWaveform.kt`,
  `service/SpeechRecognizerHelper.kt` (RMS flow).

### P1 — Transcript screen layout "popped" when speech arrived
The transcript card was created only once text existed (`if (hasTranscript)`), so the
mic jumped upward the moment the first word streamed in.
- **Fix:** the transcript area is now always present with a placeholder, keeping its
  minimum footprint reserved before speech.
- **Files:** `ui/screens/TranscriptScreen.kt`, `res/values/strings.xml` (+ `-de`).

### P2 — Navigation hierarchy contradiction on Settings
Settings is a pushed sub-destination with a back arrow, yet the bottom navigation bar
(and the rail on expanded widths) stayed visible, advertising it as a primary tab.
- **Fix:** both the `NavigationBar` and `NavigationRail` hide on the Settings route;
  Settings stays reachable via the top-bar gear and returns via its back arrow.
- **File:** `ui/navigation/Navigation.kt`.

### P2 — Search inputs did not match the glassmorphic cards
History, Library and Settings each used an `OutlinedTextField` with a solid
`surfaceContainer` fill, a plain outline and a 16dp corner, while every card used the
24dp `GlassShape` with a gradient azure border.
- **Fix:** one `GlassTextField` (and a `SearchInput` convenience wrapper) with the same
  `glassBorderBrush`, translucent fill and 24dp corners, plus a cyan-tinted edge on
  focus. All three inputs now use it.
- **Files:** `ui/components/GlassComponents.kt`, `ui/theme/Obsidian.kt`,
  `ui/screens/HistoryScreen.kt`, `ui/screens/VocabularyScreen.kt`,
  `ui/screens/SettingsScreen.kt`.

### P2 — SpeechRecognizer error handling lacked silent resets
Every `ERROR_*` produced a persistent banner; `ERROR_NO_MATCH` and
`ERROR_RECOGNIZER_BUSY` (recoverable by simply retrying) left a red banner up, and
`ERROR_CLIENT` reused a recognizer object the framework had just declared broken.
- **Fix:** `ERROR_NO_MATCH`/`ERROR_RECOGNIZER_BUSY` show a hint and self-clear after
  2.5s (only if nothing newer superseded it); `ERROR_CLIENT` tears the recognizer down
  so the next attempt rebuilds it; `ERROR_LANGUAGE_UNAVAILABLE` still triggers the
  voice-pack download. All codes remain covered.
- **File:** `service/SpeechRecognizerHelper.kt`.

### P3 — No indexation on `timestamp`
`SELECT * ... ORDER BY timestamp DESC` ran a full sort on every emission because
neither `transcripts.timestamp` nor `vocabulary.timestamp` was indexed.
- **Fix:** indexes on both columns (DB v5 + `MIGRATION_4_5`).
- **Files:** `data/local/entities/*.kt`, `data/local/AppDatabase.kt`,
  `data/local/Migrations.kt`.
- **Deliberately deferred:** FTS4. Text search is a `contains()` over the loaded list,
  which supports infix matching; FTS token queries (`MATCH`) would regress infix
  search (e.g. "ernen" → "lernen"), so in-memory filtering over an indexed sort is the
  correct trade-off at this dataset size.

### P3 — UI state models not explicitly stable
`WordResult` and the three Room entities carried no stability contract; any future
compiler that stops inferring it would silently lose list skipping.
- **Fix:** `@Immutable` on `WordResult`, `PracticeFeedback`, `VocabularyEntity`,
  `TranscriptEntity`, `UserStatsEntity`.
- **Files:** `ui/viewmodel/PracticeViewModel.kt`, `data/local/entities/*.kt`.

---

## 2. Refactored / new code modules

| Module | Location | Purpose |
| --- | --- | --- |
| `GlassmorphicCard` | `ui/components/GlassComponents.kt` | Unified card: `glassSurface` + padding, one corner/border. |
| `GlassTextField` / `SearchInput` | `ui/components/GlassComponents.kt` | Unified glass input (masking, IME, leading/trailing slots, focus glow). |
| `AudioWaveform` | `ui/components/AudioWaveform.kt` | Draw-phase level meter; reads `State<Float>` in `Canvas`, zero recomposition per RMS sample. |
| Corrected Transcript layout | `ui/screens/TranscriptScreen.kt` | Fixed transcript area with placeholder. |
| Corrected Practice layout | `ui/screens/PracticeScreen.kt` | Middle waveform/feedback card, anchored actions. |
| Hardened audio controller | `service/SpeechRecognizerHelper.kt` | Error classification, silent resets, RMS flow. |
| TTS focus | `service/TTSHelper.kt` | Per-phrase AudioFocus + `stop()` + `UtteranceProgressListener`. |

---

## 3. Already compliant — verified, no change needed

- **API-key security.** The key is encrypted with AES/GCM through the Android Keystore
  (`KeystoreCipher`), stored only as ciphertext in DataStore, never logged, and
  Settings is write-only (it never re-reads the plaintext into UI state).
- **LLM structured-output parsing.** `GroqHelper.parseResponse` tolerates markdown
  emphasis/bullets and returns `null` on unrecognised/partial output, which the caller
  turns into a translated "unreadable" failure rather than a storable error string;
  failure is a separate `AIResult` case so it can never be saved as a translation.
- **Modifier ordering.** `glassSurface` already obeys `clip → background → border`
  (padding is applied inside callers), and the new components follow
  `clip → background → border → padding`.
- **Animation efficiency.** `OracleMic`'s `rememberInfiniteTransition` values drive
  `graphicsLayer`/`drawBehind`/`Canvas` only — no recomposition per frame. The new
  waveform follows the same rule.
- **Recognizer lifecycle.** Instantiation, listener registration and `startListening`
  are confined to `Dispatchers.Main` via `mainHandler`, and the instance is destroyed
  in `ViewModel.onCleared()` plus `OnLeavingScreen`.
- **Room migration safety.** Version history and `MIGRATIONS` are intact and extended
  (now `2→3→4→5`); release builds keep no destructive fallback.

---

## 4. Known risks / deferred

- **`MIGRATION_4_5` and the v5 schema JSON** are produced/validated by the build
  (KSP exports `app/schemas/…/5.json`) and by `AppDatabaseMigrationTest` on device/CI;
  the migration itself is a pair of idempotent `CREATE INDEX IF NOT EXISTS` statements.
- **`kotlinx.collections.immutable`** was not adopted. Raw `List<T>` flows remain, but
  with stable element types (`@Immutable` entities/`WordResult`) the lists are already
  stable under the current Compose compiler; adopting immutable collections would be a
  heavier, optional change with no measurable win at this scale.
- The waveform visualises the normalised RMS level (`rmsdB / 10`, clamped 0..1); the
  exact raw range varies by engine, so the meter is a relative indicator, not a dB
  readout.
