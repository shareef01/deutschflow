# DeutschFlow PWA — Blueprint (Phase 1)

> **Status: Phase 1 & 2 complete — awaiting command to proceed to Phase 3 (component generation).**
>
> Source of truth for every mapping below is the shipped Android app in
> `app/src/main/java/com/aus/deutschflow/`. Every web artifact names the Kotlin file it
> replaces, so fidelity is checkable line-by-line, not approximate.
>
> Stack decided by the principal: **Next.js (App Router) + TypeScript**, **Tailwind CSS v4**
> (CSS-first `@theme`), **Dexie 4** (IndexedDB), **Web Speech API** (recognition + synthesis),
> **WebCrypto** (API-key vault), hand-rolled **Groq client** (zero HTTP dependencies — the
> web equivalent of the Android app's dependency-free `HttpURLConnection` client).

---

## 1. Architecture map: Jetpack Compose → Next.js/React

| Android artifact | Web equivalent | Notes |
| --- | --- | --- |
| `MainActivity` / `MainApp` | `src/app/layout.tsx` + providers | Registers the Dexie instance, the WebCrypto vault, and the global audio session. No server state anywhere — the app is local-first. |
| `ui/navigation/Navigation.kt` (Scaffold + NavHost) | `src/components/layout/AppShell.tsx` | Responsive shell: bottom `NavigationBar` below 768px, glassmorphic left `NavigationRail` at ≥768px — the exact `WindowWidthSizeClass.Compact` split. |
| `ui/navigation/Screen.kt` (sealed class + `navItems`) | `src/lib/navigation/tabs.ts` | One tab config array: `transcript, history, vocabulary, study, practice`; `settings` is a pushed detail destination, not a tab — mirrored from `navigateToTab` vs `navigateToDetail`. |
| `ui/viewmodel/*` + `StateFlow` | Custom hooks (`src/hooks/*`) + `src/lib/stores/*` | One store module per screen. Cross-cutting singletons (recognizer, TTS) are module-level singletons — the web analogue of `@Singleton` + `SharedFlow`. |
| Room `AppDatabase` + DAOs | `src/lib/db/schema.ts` + `src/lib/db/repository.ts` | Tables, indexes and transactional behaviors ported 1:1 (see §4). |
| DataStore `PreferenceManager` | `src/lib/db/settings.ts` | `dialect` + `auto_play` as reactive settings rows; API key in the WebCrypto vault (see §5). |
| `KeystoreCipher` | `src/lib/db/vault.ts` | AES-256-GCM, non-extractable key in IndexedDB, fresh random IV per encryption, **no plaintext fallback**. |
| `service/GroqHelper` + `VocabularyProcessor` | `src/lib/ai/groq.ts` (Phase 3) | Same endpoint, same two system prompts, same tolerant parsers, same error extraction. Contract frozen in §6. |
| `service/SpeechRecognizerHelper` | `src/lib/speech/recognizer.ts` (Phase 3) | Web Speech API `SpeechRecognition` with `lang = dialect`, `continuous`, interim results, the same `SharedFlow`-style result channel, error mapping. |
| `service/TTSHelper` | `src/lib/speech/tts.ts` (Phase 3) | `speechSynthesis` wrapper: `de-DE` voice, stop-before-record, error surface shared with recognition. |
| `ui/theme/*` (Color, Type, Shape, Spacing, Obsidian) | `src/app/globals.css` `@theme` + utilities | All tokens ported exactly in §3. |
| Compose infinite animations (`rememberMeshRotation`, `rememberBreath`, `pressScale`) | CSS `@keyframes` + utilities | `mesh-rotation` 12s linear, `breath` 2.6s alternate alpha swell, `press-scale` spring-ish transform. |
| Window size classes | `src/hooks/useViewport.ts` | `matchMedia("(min-width: 768px)")` — Compact vs Expanded/Medium. |
| `WidgetUpdater` / `DailyWordWorker` / WorkManager | Optional Phase 6: service worker + Notification API | Home-screen widget has no web equivalent; the daily word can surface as an SW-triggered notification. |

### Data flow (Transcript — the app's spine)

```
User speaks
  → SpeechRecognition (the browser's own engine) — see §7: on Chrome, Edge and
    Safari the audio is sent to the browser vendor, unlike the Android app
  → interim + final results into the recognizer store (SharedFlow equivalent)
  → transcript row inserted (Dexie)                    [TranscriptDao.insertTranscript]
  → POST api.groq.com/openai/v1/chat/completions        [GroqHelper.translateAndExtract]
      system: exact SYSTEM_PROMPT (§6)  |  user: the transcript as data
  → tolerant parse → { translation, keywords, example } [GroqHelper.parseResponse]
  → chips rendered; tap chip → interrogation request     [GroqHelper.interrogateWord]
  → Save → vocabularyDao.save() with merge-on-conflict   [VocabularyDao.save]
```

---

## 2. Strict execution plan (phases with acceptance criteria)

### Phase 3 — Glass components + Transcript screen
| Step | Deliverable | Acceptance |
| --- | --- | --- |
| 3.1 | `GlassCard`, `GlassButton`, `GlassTextField`, `SearchInput`, `ErrorBanner`, `EmptyState`, `VocabularyChip`, `WordDetailsSheet`, `OracleMic` (breathing/rotating disc) | Every component renders the Obsidian & Azure treatment (§3); no solid primary buttons anywhere. |
| 3.2 | Transcript screen + `useTranscript` store | Mirrors `TranscriptScreen`/`TranscriptViewModel` state-for-state: `partialText, finalText, isListening, isBusy, translation, suggestedWords, aiError, interrogatingWord, wordDetails`; save returns `false` on blank input; interrogation cancels the previous in-flight job; failure never reaches the translation field. |
| 3.3 | Responsive AppShell (bottom bar ↔ rail) wired to the five routes | <768px bottom bar, ≥768px rail; Settings is a pushed detail with back arrow. |

### Phase 4 — History + Library + Word detail
| Step | Deliverable | Acceptance |
| --- | --- | --- |
| 4.1 | History screen + store | Live list via `db.transcripts` `liveQuery`, delete + delete-all mirror `TranscriptDao`. |
| 4.2 | Library screen + store | Search (case-insensitive like SQLite NOCASE), edit, delete, TTS playback per row, "Clear all progress". |
| 4.3 | Word detail screen + interrogation flow | `VocabularyProcessor.generateExample` template fallback ported verbatim; `mergedWith` merge on re-save. |

### Phase 5 — Study + Practice
| Step | Deliverable | Acceptance |
| --- | --- | --- |
| 5.1 | Study screen + store | Snapshot-and-shuffle on entry (not a live flow), flip animation, auto-play gated by setting, `nextCard` modulo wrap, once-per-session award set. |
| 5.2 | XP/streak transaction | `rewardCurrentCard` = one read-modify-write transaction; `nextStreak` compares **calendar days in the device zone**; 10 XP/card; `awardedCardIds` per session. |
| 5.3 | Practice screen + scoring | `evaluateMatch` ported exactly: word split, non-letter strip, `foldGerman` (ä→ae, ö→oe, ü→ue, ß→ss, locale-invariant lowercase), PERFECT/GOOD/KEEP_GOING thresholds; `stop()` TTS before mic opens. |

### Phase 6 — Settings + PWA hardening
| Step | Deliverable | Acceptance |
| --- | --- | --- |
| 6.1 | Settings screen | Write-only API-key field (masked, never echoed), 2×2 telemetry grid (XP, streak, saved words, transcripts), dialect `de-DE/de-AT/de-CH`, auto-play toggle, clear-all. |
| 6.2 | Service worker (`public/sw.js`) | Precache build assets + app shell; runtime cache for Groq responses (opaque, non-sensitive text only); offline boot; install prompt. |
| 6.3 | Verification | Lighthouse PWA ≥ 90, offline reload works, install from mobile Chrome/desktop works. |

### Phase 7 — Parity & quality
- i18n `en` + `de` (mirror `res/values` + `values-de`).
- Vitest unit tests porting the Android JVM tests: `parseResponse`, `parseWordDetails`, `evaluateMatch`, `nextStreak`, `generateExample`, merge-on-save, vault round-trip ("key never appears in the clear on disk").
- Playwright smoke: five tabs, responsive breakpoint, offline.

---

## 3. Design system — Obsidian & Azure tokens (Tailwind v4)

> Both schemes. §3's tokens are the dark palette; `globals.css` redefines the
> same custom properties under `@media (prefers-color-scheme: light)`, so every
> utility below re-themes without a class name changing. The light values are
> not the dark ones inverted — a cyan built to glow on a blue-black ground
> measures 1.68:1 on white — and both palettes are measured by
> `tools/contrast.py` and cross-checked against Color.kt by
> `tools/palette_parity.py`.

Implemented in `src/app/globals.css` as `@theme` tokens + utilities (Phase 2 deliverable, §8). Exact port of `ui/theme/`:

- **Ground:** `#000000` true black (an unlit OLED pixel).
- **Glass:** 3% white fill (`rgba(255,255,255,0.03)`), raised 5% for glass-on-glass; edge = 1px gradient `AzureGlow(0.35α) → AzureGlow(0.12α) → transparent` running **top-left → bottom-right** (`glassBorderBrush`: `Offset.Zero → Offset.Infinite`).
- **Accent ramp:** `AzureGlow #00E5FF → AzureDeep #0A84FF`; everything that glows is one of these or a ramp between them.
- **Muted text:** `#8892B0` (OnSurfaceVariant) and `#9C9CA1` (OnSurfaceMuted, flat 4.6:1 for body text).
- **Type:** Black/ExtraBold geometric sans (Inter, via `next/font`), negative tracking on headlines; sizes stepped down from Material defaults (content is long, read at arm's length).
- **Shape:** 8/12/16/20/28 + 24 (GlassShape) + pill (50%).
- **Buttons:** transparent over 5% white glass, cyan gradient edge, bold white label, `ActionButtonHeight` 56px.

---

## 4. Database — Room v4 → Dexie 1:1

Room entities → Dexie tables (implemented in Phase 2, §8):

| Room table | Dexie table | Schema port notes |
| --- | --- | --- |
| `vocabulary` | `vocabulary` | `++id, timestamp, &germanTextKey` — unique on the **NOCASE-folded** key (`germanTextKey`), because Dexie indexes are case-sensitive while SQLite `NOCASE` is not; display `germanText` stays intact (a re-save must not recapitalize the library). |
| `transcripts` | `transcripts` | `++id, timestamp` — `timestamp DESC` ordering. |
| `user_stats` | `userStats` | `id` (singleton row `id: 1`) — `xp, streak, lastActivityTimestamp`. |
| — (DataStore) | `settings` | key-value: `dialect`, `auto_play`; API key **never** in the clear here — only ciphertext from the vault. |

Transactional behaviors ported verbatim into `repository.ts`:
1. **`saveVocabulary`** — Room `VocabularyDao.save()`: `findByGermanText` (via folded key) → insert / update / **merge with `mergedWith`** (`incoming` field wins when non-blank; `timestamp = max`); one `rw` transaction so two concurrent saves can't both insert.
2. **`rewardCurrentCard`** — one `rw` transaction: one-shot read, `nextStreak` (calendar days, device zone), write; caller keeps a session-level `awardedCardIds` set.
3. **`deleteAll` / per-row delete** — mirror `TranscriptDao` / `VocabularyDao`.

---

## 5. Secrets — Android Keystore → WebCrypto (vault.ts)

| Android | Web |
| --- | --- |
| AES-256-GCM (`AES/GCM/NoPadding`), key in Android Keystore | AES-256-GCM via `crypto.subtle`, key generated **non-extractable** (`extractable: false`), stored in IndexedDB |
| Fresh random IV per encryption, enforced by Keystore | Fresh 12-byte IV per encryption, prefixed to ciphertext (`iv + data`, base64) |
| `saveApiKey` returns `false` on encryption failure — **no plaintext fallback** | Same contract: `saveApiKey` rejects; UI must not claim a save that didn't happen |
| Key is write-only from the UI; masked input; never echoed into a field | Same: password-masked input, `hasApiKey()` boolean only, key never reconstructed into the UI |
| Decryption failure (device key gone) = "enter it again", not a crash | Same: vault read failure surfaces as "API key required" |

---

## 6. AI contract (frozen for Phase 3 — `src/lib/ai/groq.ts`)

- Endpoint: `https://api.groq.com/openai/v1/chat/completions`
- Model: `openai/gpt-oss-120b`, timeout 30s, `Authorization: Bearer <key>`.
- Translation request: `temperature 0.2`, system + user roles split (the transcript is **data**, never instructions).
- Interrogation request: `temperature 0.1`, `response_format: { "type": "json_object" }`.
- The two system prompts are ported **byte-for-byte** from `GroqHelper.SYSTEM_PROMPT` and `WORD_SYSTEM_PROMPT` (both quoted verbatim in the file header of `groq.ts` — they are instructions to the model, stay English in every locale, and the parse functions match on their prefixes).
- `parseResponse`: per-line, strip `**`/`__`/`-`/`*`, prefix-match `Translation:` / `Keywords:` / `Example:` case-insensitively, `cleanValue` removes `[` `]`; returns `null` when translation is blank.
- `parseWordDetails`: first `{` → last `}` extraction (tolerates code fences), requires non-blank `word` + `meaning`; `article` defaults to `"none"`.
- Error mapping: 401 / 429 / provider body detail / generic — same precedence as `GroqHelper.errorMessage`.

---

## 7. Security & privacy parity (stated plainly, like the README)

- **Audio does NOT stay on the device here.** The Web Speech API delegates to the
  browser's own recognition service: Chrome and Edge stream the captured audio to
  Google, Safari to Apple, and Firefox does not implement the API at all. There is
  no offline hint the page can set — the API exposes none.

  This is the one place the PWA cannot match the Android app, which binds
  `createOnDeviceSpeechRecognizer` specifically so that the audio never leaves the
  phone. The claim that used to sit on this line said the opposite, and it was
  wrong in every browser where the feature actually works. Genuine on-device
  recognition on the web needs a WASM model (Whisper, Vosk) shipped with the app.

  The Settings screen states this to the user; it should not live only here.
- **Text leaves twice, as in Android:** each utterance to Groq; and — unlike Android — the PWA's IndexedDB is **never** synced anywhere by default, so nothing extra leaves.
- The API key is the one secret; WebCrypto vault per §5. No plaintext copy, ever.
- Release discipline: no transcript or key content in `console.log` (the PWA analogue of R8 stripping `Log.d`).
- Groq prompt injection defense is structural, not filtered: roles split, system message says the user message is data to translate.

---

## 8. Phase 2 deliverables (written to `web/`)

| File | What it is |
| --- | --- |
| `package.json`, `tsconfig.json`, `next.config.ts`, `postcss.config.mjs`, `tailwind.config.js` | Build config: Next.js App Router + Tailwind v4. `tailwind.config.js` is present for explicitness; in v4 the tokens live in CSS `@theme` (see `globals.css`). |
| `src/app/globals.css` | The **real** Tailwind v4 config: full Obsidian & Azure `@theme` (colors, type, shape, spacing) + glass utilities + keyframes. |
| `public/manifest.json` | Installable PWA manifest (name, icons, standalone, shortcuts to the four learning tabs). |
| `public/icons/icon-192.png`, `icon-512.png` | The Ü-on-glass launcher mark, generated to spec. |
| `src/lib/db/schema.ts` | Dexie schema (entities, tables, indexes) — Room v4 1:1. |
| `src/lib/db/repository.ts` | `saveVocabulary` (merge), `rewardCurrentCard`, deletes, stats — Room DAO transactions 1:1. |
| `src/lib/db/settings.ts` | Reactive settings store (`dialect`, `auto_play`) — DataStore 1:1. |
| `src/lib/db/vault.ts` | WebCrypto AES-256-GCM API-key vault — KeystoreCipher 1:1. |
| `src/lib/db/index.ts` | Singleton DB + public repository surface. |

## 9. Maintenance notes

**The `npm audit` high advisories in sharp (<0.35.0, CVE-2026-33327 et al.) were
resolved by upgrading to Next 16** (16.3.3), whose sharp no longer carries them;
`npm audit` reports 0 vulnerabilities. The advisories were previously tolerated
on the reasoning below - nothing invoked sharp, so vulnerable bytes were installed
but never executed. That tolerance was sound but temporary by design; the recorded
revisit triggers (a Next major, or any use of next/image / ImageResponse) have been
consumed by this upgrade rather than left open. What the move actually required:
`middleware.ts` became `proxy.ts` with a nodejs-only runtime (the gate's Web Crypto
signing carried over unchanged), Turbopack took over as the default bundler, and
the whole suite - typecheck, unit tests, build, and the browser smoke run including
offline boot - was executed green before shipping.

