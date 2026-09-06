# DeutschFlow — Comprehensive Audit, September 2026

> **Current report.** Supersedes `AUDIT_REPORT.md`, which is kept as a historical
> record of the database-v5 era and should be read as one.
>
> Scope: both applications — `app/` (Android, Room v12) and `web/` (Next.js PWA,
> Dexie v4) — across product, learning quality, security, privacy, accessibility,
> performance, data integrity and test coverage.

---

## Executive summary

- **This is a mature, unusually well-reasoned codebase.** Nearly every non-obvious
  decision carries a comment explaining the failure it exists to prevent. The
  baseline was fully green before any change: 66 Android unit tests, 99 web unit
  tests, 12 browser tests, lint, R8 release build, both design-token checks, and
  `npm audit` at zero vulnerabilities.
- **No P0 was found.** No data loss, no credential leakage, no XSS, no destructive
  migration, no crash in a core flow.
- **The single largest real problem was a privacy misrepresentation, and it was in
  the documentation rather than the code.** The README advertised "On-Device
  Speech Recognition … full offline privacy" as a product-wide feature while the
  repository ships a second complete application whose recognition is the
  browser's, and is not on-device in Chrome or Edge. The README never mentioned
  the PWA existed. Fixed, on both the doc and the in-app side.
- **The Android privacy posture is genuinely strong and was being undersold.**
  `createOnDeviceSpeechRecognizer` is bound explicitly rather than taking the
  system default, the API key is AES-GCM under a Keystore key, and that key is
  excluded from cloud backup and device transfer. All verified.
- **The web key vault is real but was described in the code as stronger than it
  is.** A non-extractable WebCrypto key protects a copied profile directory; it
  does not protect against script on the origin. `next.config.ts` already said so
  and cited a README privacy section that did not exist. It exists now.
- **Accessibility was the weakest dimension and the richest source of fixable
  defects.** The mobile bottom bar — the primary navigation on a phone — exposed
  no current-page state; the dialect and language pickers were plain buttons with
  no radio semantics; dialogs claimed `aria-modal` while trapping nothing and
  ignoring Escape. All three fixed, all three now covered by browser tests.
- **One cross-platform divergence let malformed AI output poison saved data on the
  web only.** Android's `org.json` coercion already rejected non-primitives;
  the web's `String(value)` turned `{}` into the literal `"[object Object]"` and
  offered it as a German word to save. Closed, with tests.
- **Prompt-injection posture is structurally correct on both platforms** — system
  and user turns are separate messages, and the roleplay scenario is a constant,
  not user input. This was asserted only by prose; it is now asserted by a test.
- **The service worker does not cache anything private.** Groq is a cross-origin
  POST and is not intercepted. Verified by reading, not assumed.
- **SRS is a faithful SM-2 and the two platforms agree**, including the rounding
  that was deliberately made to match. Streak arithmetic compares calendar days
  in the device's zone, which is what makes it survive DST.
- **Security headers gained the two directives they were missing** — a
  `Permissions-Policy` (this is a microphone app and had none) and `object-src`,
  which was inheriting `'self'` from `default-src` by omission.
- **Running the instrumented suite on a real device found three more defects that
  no amount of reading would have.** A DataStore teardown race in the shared test
  rule, an accessibility-event listener that was deaf on API 36 (so the banner
  announcement test proved nothing there), and one genuinely flaky ViewModel test.
  Two are fixed; the third is characterised with evidence.
- **Neither platform let a learner correct AI-authored grammar.** Both editors
  carried German and translation only, so a wrong `der/die/das` could not be fixed
  except by deleting the word — which also threw away its review history. Both
  editors now carry an article picker and an example field.
- **The largest remaining risk is not technical.** The cloud-sync surface is a
  mock; it looked like a feature. The credential form that discarded every
  keystroke has been removed.

---

## Scorecard

| Dimension | Score | Basis |
| :--- | :---: | :--- |
| Product UX | 8 / 10 | The speak → understand → save → study loop is short and unobstructed; the dashboard answers "what now?" |
| Visual design | 9 / 10 | Obsidian & Azure is distinctive and consistently applied; glass is used deliberately rather than everywhere. |
| Android UX | 9 / 10 | Native-feeling, careful state handling, real audio-focus discipline. |
| Web / PWA UX | 8 / 10 | Genuinely responsive rather than a stretched phone layout; the desktop canvas is sparse on the Transcript route. |
| German-learning quality | 8 / 10 | Was 7. Grammar spotlight and case explanations are the right idea, AI grammar is now correctable on both platforms, and the pronunciation verdict no longer claims more than it measured. |
| Accessibility | 7 / 10 | Was 5. Nav state, radio semantics and dialog focus are fixed, and the Android banner announcement is now actually verified on device; a full AT pass has not been run. |
| Speech experience | 9 / 10 | One utterance per recording, superseded sessions cannot close newer ones, no microphone left open. |
| AI integration robustness | 9 / 10 | Role separation, cancellation tokens, timeouts, per-status errors, and now type-safe parsing. |
| SRS correctness | 9 / 10 | SM-2 faithfully implemented and cross-platform identical, including rounding. |
| Data integrity | 9 / 10 | Twelve tested Room migrations, no destructive fallback in release, Dexie upgrades that seed indexed fields. |
| Security | 9 / 10 | No XSS surface, signed session tokens, pinned CI actions, encrypted keys, and now a complete header set. |
| Privacy | 8 / 10 | Was 5, on documentation alone. The behaviour was always sound; the description of it was not. |
| Android performance | 8 / 10 | RMS read in the draw phase, indexed timestamp columns, no main-thread I/O found. |
| Web performance | 8 / 10 | Ten prerendered routes, no unbounded list rendering at plausible library sizes. |
| Cross-platform parity | 8 / 10 | Deliberate and documented, with one real divergence found and closed. |
| Test quality | 9 / 10 | Adversarial rather than decorative. 115 web unit + 16 browser + 66 Android unit + 51 instrumented tests, with two test-infrastructure bugs fixed. |
| Code quality | 9 / 10 | Comments explain causes, not mechanics. Very little dead code. |
| Maintainability | 8 / 10 | Two implementations of one rule set is the standing cost; the parity comments are what make it survivable. |
| Production readiness | 8 / 10 | Ships. The mock cloud surface is the thing most likely to mislead a user. |

---

## Findings

Status legend: **Fixed** · **Verified OK** · **Recommended** · **Needs manual verification**

### P1

| ID | Area | Finding | Evidence | Impact | Conf. | Effort | Status |
| :-- | :-- | :-- | :-- | :-: | :-: | :-: | :-- |
| **P1-1** | Privacy | README advertised on-device recognition with "full offline privacy" as a product feature. The repo ships a PWA whose recognition is the browser's engine — not on-device in Chrome or Edge — and the README never mentioned the PWA at all. | `README.md` feature list; `web/src/lib/speech/recognizer.ts` calls `new SpeechRecognition()` with no `processLocally`; the app's own `speech.errorNetwork` string reads *"Speech recognition needs network access"* — an error a local engine cannot raise. | 5 | 5 | M | **Fixed** |
| **P1-2** | Privacy | `next.config.ts` justified keeping `'unsafe-inline'` by saying the trade "is stated in the README's privacy section". No such section existed. | `web/next.config.ts` header comment vs. `README.md`. | 4 | 5 | S | **Fixed** |
| **P1-3** | A11y | The mobile bottom bar exposed no current-page state. The desktop rail set `aria-current="page"`; the bottom bar — the primary navigation on the app's main form factor — set nothing, conveying selection by colour and font weight alone. WCAG 2.2 §1.4.1 / §4.1.2. | `web/src/components/layout/AppShell.tsx`, the two `TABS.map` branches. | 4 | 5 | S | **Fixed** |

| **P1-4** | Learning quality | The practice verdict said *"Excellent! Perfect pronunciation."* `evaluateMatch` only checks whether each target word appears in what the recogniser returned — it is a recognition result, not phoneme analysis, and cannot support that claim. A learner told their pronunciation is perfect stops working on it. | `web/src/lib/scoring.ts`, `PracticeViewModel.evaluateMatch`, and the three feedback strings on each platform. | 4 | 5 | S | **Fixed** |
| **P1-5** | Learning quality | Neither platform let a learner correct AI-authored grammar. Both editors carried German + translation only, so a wrong `der/die/das` — the model's most consequential error — could only be fixed by deleting the word, which also discarded its SRS history. | `VocabularyEditorDialog` on both platforms (before). | 4 | 5 | M | **Fixed** |

### P2

| ID | Area | Finding | Evidence | Impact | Conf. | Effort | Status |
| :-- | :-- | :-- | :-- | :-: | :-: | :-: | :-- |
| **P2-1** | Data integrity | `parseWordDetails` coerced any JSON value with `String()`. A model returning `{"word": {...}}` produced the literal `"[object Object]"`, passed the blank check, and was offered as a German word to save. `null` became `"null"`. Android already refused both — `org.json`'s `optString` returns its default for non-primitives — so this was a one-sided divergence. | `web/src/lib/ai/groq.ts`; compare `GroqHelper.parseWordDetails`/`parseList`. | 4 | 5 | S | **Fixed** |
| **P2-2** | A11y | Dialect and language pickers were plain `<button>`s: no `role="radiogroup"`, no `role="radio"`, no `aria-checked`, no group name. Which option was in force existed only as a colour and a filled dot. | `web/src/app/(app)/settings/page.tsx`, `RadioGroup`. | 4 | 5 | S | **Fixed** |
| **P2-3** | A11y | `ModalDialog` declared `role="dialog" aria-modal="true"` and kept none of it: no initial focus, no focus trap, no focus restoration, no Escape. Tab walked straight out into the page the dialog claimed was inert. The full-screen backdrop was also the first tab stop, ahead of every visible action. Untitled dialogs announced a hardcoded English `"Dialog"` in a German UI. | `web/src/components/ui/ModalDialog.tsx`. | 4 | 5 | M | **Fixed** |
| **P2-4** | Security | No `Permissions-Policy` header on an application whose premise is the microphone; `object-src` was inheriting `'self'` from `default-src` by omission rather than decision. | `web/next.config.ts`, `web/vercel.json`. | 3 | 5 | S | **Fixed** |
| **P2-5** | UX / safety | The cloud sign-in dialog rendered an email box and a password box with `value=""` and `onChange={() => {}}` — every keystroke discarded — above a button calling `signIn("", "")` against a mock that accepts anything. People type real passwords into boxes that look like that. | `web/src/app/(app)/settings/page.tsx`; `web/src/lib/ai/cloud.ts`. | 3 | 5 | S | **Fixed** |
| **P2-6** | Docs | Schema version drift: `web/src/lib/db/schema.ts` said "Room v11 → Dexie 4" and `docs/AUDIT_REPORT.md` said the schema "has since moved to v11". Room is at v12. | `DATABASE_VERSION = 12` in `AppDatabase.kt`; `app/schemas/…/12.json`. | 2 | 5 | S | **Fixed** |
| **P2-7** | Test coverage | No test asserted the prompt-injection posture, the due-card ordering, or the deletion scope. Each was a documented invariant held only by prose. | Absence in `web/tests/`. | 3 | 5 | M | **Fixed** |
| **P2-8** | PWA correctness | The service worker's `install` used `cache.addAll(APP_SHELL)`, which follows redirects. An install while the session was invalid would have written the login page into the cache under `/transcript`, `/study` and every other shell route, and an offline launch would then serve the login screen there. The `fetch` handler already refuses redirected responses for exactly this reason; `install` did not. | `web/public/sw.js`, install vs. navigate handlers. | 3 | 4 | S | **Fixed** |
| **P2-9** | Test infrastructure | `TestPreferencesRule.after()` called `scope.cancel()` without joining. Cancellation is only a request — DataStore releases the file when the coroutines actually finish — so the next test's `before()` could open a second store over the same file, which DataStore refuses outright. Surfaced as one arbitrary test per class failing on a fast device. | Device run: `IllegalStateException: There are multiple DataStores active for the same file: …/transcript-viewmodel-test.preferences_pb`. | 3 | 5 | S | **Fixed** |
| **P2-10** | Test infrastructure | `ErrorBannerAnnouncementTest` received no accessibility events at all on API 36, so its own guard failed it with *"fix the instrument first"*. `UiAutomation` delivers only the event types named in its `serviceInfo`, and the default mask did not include them. The banner's screen-reader announcement was therefore unverified on modern Android. | Device run on `Medium_Phone_API_36.1`; passes after setting `eventTypes = TYPES_ALL_MASK`. | 3 | 5 | S | **Fixed** |

### P3

| ID | Area | Finding | Evidence | Status |
| :-- | :-- | :-- | :-- | :-- |
| **P3-1** | Cleanliness | Six dead declarations the web `tsconfig` could not see, including a `currentLanguage` field in the web recognizer written on every session and read by nothing — a leftover of the port, where the Android field *is* read. `noUnusedLocals` is now on. | `npx tsc --noUnusedLocals`. | **Fixed** |
| **P3-5** | Test reliability | `TranscriptViewModelTest.aSupersededInterrogationNeverWinsTheSheet` is flaky under load: it passed 3/3 in isolation and in one full run, and failed the first `awaitCondition` in another. The 5-second real-time budget covers a DataStore read on an emulator running a whole suite. Not weakened to make it pass. | Four device runs, recorded above. | **Recommended** |
| **P3-2** | Repo hygiene | `gradlew.bat` shows as modified with no content change: the working tree is CRLF, the committed blob predates `.gitattributes` and is also CRLF, so every `git diff` shows a whole-file renormalization. `git add --renormalize gradlew.bat` in its own commit clears it permanently. | `git diff gradlew.bat`; `.gitattributes` (`*.bat text eol=crlf`). | **Recommended** |
| **P3-3** | Microcopy | The wipe dialog lists what it deletes but not what it keeps. It preserves the API key and preferences on both platforms, which is the right behaviour and worth one clause. | `settings.wipeBody`; `clearAllProgress` on both platforms. | **Recommended** |
| **P3-4** | Lint | Three unused string resources on Android (`settings_section_about`, `settings_api_key_helper`, `settings_api_key_saved_hint`). | `lintDebug` UnusedResources. | **Recommended** |

### Verified OK — checked, and sound

| Area | What was checked | Verdict |
| :--- | :--- | :--- |
| XSS | `dangerouslySetInnerHTML`, `innerHTML`, `eval`, `document.write`, `new Function`, `insertAdjacentHTML`, user-controlled `href` | Zero occurrences anywhere in `web/src`. Every AI-authored string reaches the DOM as React text. |
| Secret logging | Every `Log.*` call site on Android; every `console.*` on web | Nine Android call sites, none carrying transcript text or key material. Two web `console.error`s, both infrastructure. R8 additionally strips `Log.d`/`Log.v`. |
| Service-worker privacy | `web/public/sw.js` | Handles GET only, same-origin only, navigations and `/_next/static/` only. Groq is a cross-origin POST and is never intercepted. No transcript, translation or key can reach Cache Storage. |
| Redirect caching | SW navigation handler | Already refuses to cache a redirected response or `/login`, so an expired session cannot poison an app route's offline shell. |
| Room migrations | All ten, `MIGRATION_2_3` … `MIGRATION_11_12` | Contiguous, exported schemas present for 2–12, no destructive fallback in release, `AppDatabaseMigrationTest` walks the chain. `MIGRATION_6_7` merges duplicates field-by-field rather than picking a row. |
| Dexie upgrades | v1 → v4 | Every version that adds an indexed field seeds it in `.upgrade()` — the IndexedDB failure mode where an `undefined` key path silently hides rows from an index is explicitly handled. |
| SM-2 parity | `SRSEngine.kt` vs `srs.ts` | Identical: same interval ladder, same ease formula, same 1.3 floor, same rounding, same `interval 0 → nextReview 0` "due now". |
| Streak / timezone | `nextStreak`, `daysBetween`, `todayKey` | Compares calendar days in the device's zone, so a 23- or 25-hour DST day still rounds to one. Activity-log keys are local, not `toISOString()`. |
| Prompt injection | Both platforms' request builders | System instructions and user speech are separate messages; the roleplay scenario is a compile-time constant, never user input. Now asserted by test. |
| Response-error precedence | `groq.ts` / `GroqHelper.kt` | Both prefer the provider's own `error.message`, then 401 → key-rejected, 429 → rate-limited, else the status code. The two agree. |
| Request cancellation | `useTranscript`, `useRoleplay`, `useStudy`, `usePractice` | Monotonic tokens and `inFlight` refs. A late answer for word A cannot overwrite word B. |
| TTS ↔ microphone | `TTSHelper.kt`, `tts.ts` | Playback is stopped before the microphone opens on both platforms; Android additionally takes and releases transient audio focus per utterance. |
| API-key storage | `KeystoreCipher.kt`, `vault.ts`, `data_extraction_rules.xml` | AES-GCM, fresh random IV prefixed to ciphertext, no plaintext fallback on either platform, never rehydrated into UI state, excluded from Android backup and transfer. |
| Session tokens | `session.ts` + `tests/session.test.ts` | HMAC-signed with expiry inside the signed payload; extension, truncation and the legacy literal cookie are all refused. |
| Deletion scope | `clearAllProgress` on both platforms | Clears exactly the four learning tables and keeps the key and preferences — matching what the dialog says. Now asserted by test. |
| CI | `.github/workflows/build.yml` | Least-privilege `permissions`, every action SHA-pinned, bounded timeouts, and instrumented Room tests on an API 31 emulator. |
| Dependencies | `npm audit`, `dependabot.yml` | Zero vulnerabilities. Grouped weekly updates across all three ecosystems. |
| Design tokens | `tools/contrast.py`, `tools/palette_parity.py` | 24 pairings all clearing WCAG, 22 paired tokens in agreement across the two apps. |

---

## Changes implemented

### 1. An honest privacy story for both applications

**Problem.** The README described DeutschFlow as "a modern, native Android
application" with on-device recognition and "full offline privacy". The repository
also contains a complete PWA, unmentioned, in which recognition is the browser's
and — in Chrome and Edge — sends captured audio to the browser vendor. A reader
would reasonably conclude the guarantee covered both.

**Root cause.** The README predates the PWA and was never revisited when a second
application with a different threat model was added beside it.

**Solution.** The README now opens by naming both applications, qualifies the
recognition claim per platform, and carries a `Privacy` section that states, in a
table, where voice is processed on each — including the sentence *"If microphone
audio staying on the device matters to you, use the Android app."* It also
documents what is sent to Groq and when, where the API key lives, the explicit
limit of the web vault (a copied profile directory, not script on the origin),
what the service worker caches, and what "Clear all progress" does and does not
delete. Every claim in it was verified against the code before being written.

The user-facing half is one muted line under the dialect picker in web Settings —
said once, where the setting lives, rather than as a banner on every screen.

**Files.** `README.md`, `web/src/lib/i18n.ts`, `web/src/app/(app)/settings/page.tsx`.
**Tests.** `smoke.spec.ts` — "the speech-privacy note is on the screen that owns the setting".
**Regression risk.** Low — additive copy.

### 2. The bottom bar tells assistive technology where you are

**Problem.** On a phone the bottom bar is the only navigation, and it exposed no
selected state to a screen reader.

**Root cause.** The rail and the bar are two `TABS.map` branches in one file. The
rail gained `aria-current` when it was written; the bar was never brought along.

**Solution.** `aria-current="page"` on the selected destination, plus a visible
focus ring the bar also lacked.

**Files.** `web/src/components/layout/AppShell.tsx`.
**Tests.** `smoke.spec.ts` — asserts exactly one `aria-current` and that it follows navigation.
**Regression risk.** Low.

### 3. Radio semantics for the dialect and language pickers

**Problem.** Both were plain buttons. Which dialect or language was in force was
conveyed only by colour and a filled dot.

**Solution.** `role="radiogroup"` with an accessible name taken from the section
heading, `role="radio"` and `aria-checked` per option, the decorative dot marked
`aria-hidden`, and a focus ring. No visual change.

**Files.** `web/src/app/(app)/settings/page.tsx`.
**Tests.** `smoke.spec.ts` — asserts the checked state and that it moves on click.
**Regression risk.** Low, but note it *correctly* changed the exposed role: two
existing browser tests selected these by `role: "button"` and were updated to
`role: "radio"`. That is the fix landing, not a test being weakened.

### 4. Dialogs that keep the promise `aria-modal` makes

**Problem.** `ModalDialog` claimed the rest of the page was inert and enforced
nothing: no initial focus, no trap, no restoration, no Escape. The full-screen
backdrop button was the first tab stop, ahead of Cancel and Confirm.

**Solution.** Focus moves to the first control on open; Tab and Shift+Tab cycle
within the panel; Escape dismisses; focus returns to the opener on close, guarded
by `isConnected` so a detached opener does not silently send focus to `<body>`.
The backdrop is `tabIndex={-1}`. The untitled-dialog label is now localized.

**Files.** `web/src/components/ui/ModalDialog.tsx`, `web/src/lib/i18n.ts`.
**Tests.** `smoke.spec.ts` — "a dialog closes on Escape and hands focus back",
which asserts focus returns to the exact opener.
**Regression risk.** Medium — it touches every dialog. Verified in a real browser.

### 5. Malformed AI output can no longer become a saved word

**Problem.** `String(value)` coercion in `parseWordDetails`. `{"word":{"a":1}}`
became `"[object Object]"` and survived the blank check; `null` became `"null"`;
object members of `synonyms` were stringified into the list.

**Root cause.** A port that reproduced Android's *shape* but not its coercion.
`org.json`'s `optString` returns its default for anything that is not a primitive,
so Android had always rejected these.

**Solution.** `textField` accepts strings and finite numbers only; `textList`
applies the same rule per element and drops what does not survive it — Android's
`parseList` exactly. The two implementations agree again.

**Files.** `web/src/lib/ai/groq.ts`.
**Tests.** Six new cases in `groq.test.ts`, including one asserting that German
spelling (`Straße`, `Straßen`, `größer`) passes through unfolded.
**Regression risk.** Low — strictly narrows what is accepted.

### 6. Security headers completed

**Problem.** No `Permissions-Policy` on a microphone application; `object-src`
open by inheritance.

**Solution.** `microphone=(self)` — granted to this origin and not inheritable by
an embedded frame — with `camera`, `geolocation`, `payment` and `usb` denied
outright, plus `object-src 'none'`. Declared identically in `next.config.ts` and
`vercel.json`, and the existing drift test now covers `Permissions-Policy` too.

**Files.** `web/next.config.ts`, `web/vercel.json`, `web/tests/headers.test.ts`.
**Regression risk.** Low — verified by a full browser run, microphone unaffected.

### 7. The cloud sign-in form no longer asks for a password it throws away

**Problem.** An email field and a password field, both rendered `value=""` with
`onChange={() => {}}`, above a button calling `signIn("", "")` against a mock that
returns true for anything.

**Solution.** The credential fields are gone. The dialog keeps the placeholder
state the rest of the screen is built on, and now asks for an acknowledgement
rather than a secret. The reasoning is recorded at the call site.

**Files.** `web/src/app/(app)/settings/page.tsx`.
**Regression risk.** Low.

### 8. Documentation corrected

Schema drift fixed in `web/src/lib/db/schema.ts` and `docs/AUDIT_REPORT.md`, with
a note in the former explaining why the Room and Dexie version numbers are not
meant to track each other.

### 9. The practice verdict says what it measured

**Problem.** *"Excellent! Perfect pronunciation."* on a full word match.

**Root cause.** The verdict was written from what the screen wants to say, not
from what the scorer computed. `evaluateMatch` folds both sides and checks
whether each target word appears in the recogniser's output — so PERFECT means
"every word was recognised", which is real feedback but not a statement about
pronunciation quality. The two platforms had also drifted apart on the wording of
the other two verdicts.

**Solution.** Three sentences, identical on both platforms, that describe the
measurement: *"Every word was recognised. Nicely done."* / *"Most words were
recognised; the highlighted ones were not."* / *"Several words were not
recognised. Try the highlighted ones again."* No test asserted the literal
strings — only the enum — so the change is copy-only.

**Files.** `app/src/main/res/values/strings.xml` (+ `-de`), `web/src/lib/i18n.ts`.
**Regression risk.** Low.

### 10. AI-authored grammar is correctable, on both platforms

**Problem.** A learner could not fix a wrong `der/die/das`. Both editors carried
German and translation only; the article the model guessed was saved, scheduled,
and drilled by spaced repetition until it was learned. The only remedy was to
delete the word and retype it — which also discarded its review history.

**Root cause.** The editors were built for hand-typed words, before the model
started supplying grammar, and were never revisited when it did.

**Solution.** A four-way article picker (None / der / die / das) and an
example-sentence field in both editors. The web one is a real `radiogroup` with
`aria-checked`; the Android one is a `selectableGroup` of `FilterChip`s carrying
`Role.RadioButton`, so TalkBack announces one choice with one selected member
rather than four unrelated chips. "None" is stored as the empty string, which is
what every other write path on both platforms already means by "no article".
Plural and conjugation are deliberately left out — two fields is as much as this
dialog can carry without becoming a form.

**Files.** `web/src/app/(app)/vocabulary/page.tsx`, `web/src/hooks/useVocabulary.ts`,
`web/src/lib/i18n.ts`, `app/.../ui/screens/VocabularyScreen.kt`,
`app/.../ui/viewmodel/VocabularyViewModel.kt`, both `strings.xml`.
**Tests.** Browser test that saves `Straße` / `die` / an example and reopens the
editor to confirm the values round-trip.
**Regression risk.** Medium — it widens two save paths. Covered by the round-trip
test, the existing repository suite, and a full instrumented run.

### 11. The service worker cannot precache the login page

**Problem.** `install` used `cache.addAll(APP_SHELL)`, which follows redirects.
An install while the session was invalid would have stored the login page under
`/transcript`, `/study` and every other shell route, and an offline launch would
then serve the login screen there with nothing saying why.

**Root cause.** The `fetch` navigation handler already refuses redirected
responses, with a comment explaining exactly this failure. `install` was written
first and never got the same guard, so the case was moved earlier rather than
prevented.

**Solution.** One request per shell entry with the same rule: skip anything that
is not `ok`, that redirected, or that landed on `/login`. A genuine network
failure still rejects and fails the install, which is what should happen.

**Files.** `web/public/sw.js`.
**Tests.** The existing offline-boot browser test still passes.
**Regression risk.** Low.

### 12. Two test-infrastructure bugs the device found

Neither would have surfaced without running the instrumented suite.

**`TestPreferencesRule` cancelled without joining.** `scope.cancel()` only
*requests* cancellation; DataStore releases the file when the coroutines actually
finish. Returning before that let the next test's `before()` open a second store
over the same file, which DataStore refuses with *"There are multiple DataStores
active for the same file"*. The rule existed to prevent precisely this, so the
race left it half-working: one arbitrary test per class failed on a fast device.
Now `cancelAndJoin()`.

**`ErrorBannerAnnouncementTest` was deaf on API 36.** `UiAutomation` delivers only
the event types named in its `serviceInfo`, and the default mask did not include
them, so the listener received nothing and the test's own guard failed it with
*"fix the instrument first"* — correctly refusing to pass vacuously. Setting
`eventTypes = TYPES_ALL_MASK` makes the instrument match what it claims to be.
The banner's screen-reader announcement is now genuinely verified on Android 16.

**Files.** `app/src/androidTest/.../TestPreferences.kt`,
`app/src/androidTest/.../ErrorBannerAnnouncementTest.kt`.
**Regression risk.** Low — test-only.

### 13. Dead code, and CI that can see it

Six dead declarations removed, including a `currentLanguage` field in the web
recognizer written on every session and read by nothing — a leftover of the port,
where the Android field *is* read by `requestLanguageDownload`. `noUnusedLocals`
is now on in the web `tsconfig` so the next one fails CI. `noUnusedParameters` is
deliberately not set: React's `useActionState` hands the action a `prevState` it
must declare and this app does not use.

### 14. The wipe dialog says what it keeps

Both platforms listed what is deleted and stopped there. `clearAllProgress`
touches four tables and never the settings, so the API key and preferences
survive — which is right, and worth a clause: a user who reads "delete
everything" and expects to be signed out of the model is surprised in the wrong
direction. The two platforms had also drifted apart on the wording; they are one
sentence pair again.

---

## Security & privacy

**Confirmed vulnerabilities:** none.

**Privacy mismatches (fixed):** P1-1 and P1-2 above. Both were documentation
describing behaviour more favourably than the code delivers it. No user data was
ever mishandled; the description of how it is handled was wrong.

**Hardening added:** `Permissions-Policy`, `object-src 'none'`, type-safe AI
response parsing, removal of a credential form that discarded input.

**Theoretical risks, stated rather than fixed:**

- `'unsafe-inline'` in `script-src` is Next.js's requirement for its inline
  bootstrap. Both documented escapes were already tried and rejected for reasons
  recorded in `next.config.ts`, and both reasons still hold. The mitigating fact
  is the total absence of an HTML-injection surface, which this audit re-verified
  by search. **This is the assumption to re-check on any future change that adds
  a third-party script or renders untrusted markup.**
- A browser-delivered API key is necessarily in page memory while a request is in
  flight. The vault's goal is to prevent unnecessary *persistence*, not to make
  runtime use impossible. The README now says this in as many words.
- `WordWidgetReceiver` is exported with an `APPWIDGET_UPDATE` filter, which is the
  required pattern for an `AppWidgetProvider`. A third-party app could trigger a
  widget refresh; the effect is a redraw of a word the user already chose to place
  on their own home screen. Accepted.

---

## Learning quality

- **SRS.** A faithful SM-2, and the two platforms genuinely agree — including the
  `Math.round` that was deliberately introduced on the Kotlin side so that one
  synced library would not schedule the same card 15 days on one device and 16 on
  the other. Ease floors at 1.3, intervals never go negative, and `interval 0`
  means "due now" rather than "never due" on both.
- **German processing.** Two folds exist and they are deliberately different, which
  is correct: `foldGermanKey` folds ASCII only, reproducing SQLite `NOCASE`, so
  `Hund ≡ hund` but `Äpfel ≢ äpfel`; `foldGerman` in the scorer additionally
  transliterates `ä→ae`, `ö→oe`, `ü→ue`, `ß→ss`, so a word typed `Uebung` matches
  the `Übung` the recogniser returns. Neither touches display text — the target
  word is kept as written for the reader. Verified against `Straße`, `Grüße`,
  `größer`, `Mädchen`, `Fußball`.
- **Pronunciation feedback — fixed.** `evaluateMatch` compares recognised words to
  target words after folding. That is a *recognition* proxy, not phoneme analysis,
  and the UI said *"Excellent! Perfect pronunciation."* on a full word match. It
  now says *"Every word was recognised. Nicely done."* on both platforms, which is
  exactly what was measured and still reads as encouragement. The two platforms
  had also drifted apart on the other two verdicts; they are one wording again.
  Note that `practice.wordMatch` — *"Word match: {0}%"* — was already honest.
- **AI-authored vocabulary is now correctable — on both platforms.** An earlier
  draft of this report said Android had an edit path the web lacked. **That was
  wrong**: both editors carried German and translation only, and neither exposed
  the article. So a model that guessed `der Mädchen` produced a card that was then
  scheduled and drilled, with no way to fix it short of deleting the word and
  losing its review history. Both editors now carry a four-way article picker
  (None / der / die / das) and an example-sentence field, announced as a radio
  group on the web and a `selectableGroup` of `Role.RadioButton` chips on Android.
  Plural and conjugation are deliberately still absent: two fields is as much as
  this dialog can carry without becoming a form, and gender is the one that
  changes what the learner ends up believing.
- **Extraction quality.** Keywords come from the model as a comma-separated list
  and are filtered for emptiness only. Bare articles or filler words returned by
  the model would be offered as vocabulary. Not observed in fixtures; worth a
  stop-word filter if it shows up in real use.

---

## Performance

No performance work was undertaken, because no user-visible bottleneck was
established. Measuring first is the policy, and the measurements did not justify
changes:

| Measurement | Value |
| :--- | :--- |
| Web production build | ~14 s compile, 10 routes prerendered static |
| Web unit suite (115 tests) | 1.5 s |
| Browser smoke suite (16 tests) | 52 s including build and server start |
| Android unit suite (66 tests) + lint | 1 m 35 s |

Observations recorded without action: list rendering is unvirtualised on both
platforms, which is correct at plausible library sizes and would need revisiting
in the thousands; the RMS waveform is read inside the draw phase rather than
collected into composition, which is the expensive mistake and it is already
avoided; the timestamp columns the list screens order by are indexed on both
platforms.

**Not measured:** Android cold-start, recomposition counts, and large-library
behaviour beyond a few hundred rows — all need a device.

---

## Validation

Every command below was actually run in this session.

| Command | Result |
| :--- | :--- |
| `python tools/contrast.py` | **PASS** — 24 pairings, 0 below threshold |
| `python tools/palette_parity.py` | **PASS** — 22 paired tokens, 22 in agreement |
| `./gradlew testDebugUnitTest` | **PASS** — 66 tests across 8 classes, 0 failures |
| `./gradlew lintDebug` | **PASS** — warnings only (unused resources) |
| `./gradlew assembleRelease` | **PASS** — R8 release build |
| `npx tsc --noEmit` (web) | **PASS** |
| `npm test` (web) | **PASS** — 115 tests, 12 files (was 99) |
| `npm run build` (web) | **PASS** — 10 routes prerendered |
| `npx playwright test` | **PASS** — 16 tests (was 12) |
| `npm audit` | **PASS** — 0 vulnerabilities |
| Visual inspection at 320 / 390 / 768 / 1440 px | **PASS** — six routes each, no horizontal overflow, bottom bar intact at 320px |
| `./gradlew connectedDebugAndroidTest` | **PASS** — 51 tests, 0 failures, 1 skipped, on a `Medium_Phone_API_36.1` emulator (Android 16). Includes all 14 Room migration tests and both DAO suites. |
| Real Groq requests | **NOT RUN** — deliberately. `GroqLiveTest` and `GroqModelAvailabilityTest` read the device's own stored key and spend real credits, so both were excluded by class from every instrumented run. Everything else is fixtures and mocked `fetch`. |
| Instrumented run on the attached physical device | **BLOCKED** — the Pixel 7 attached to this machine holds `com.aus.deutschflow` signed with a different debug keystore, so the test APK could not install (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Proceeding would have meant uninstalling the app and its real learning data, so the clean emulator was used instead. |
| Manual Android critical-path walkthrough | **NOT RUN** — the emulator has no microphone input worth speaking into, so the speech path cannot be exercised end to end here. |

---

## Remaining work

### Do next

1. **Stabilise `TranscriptViewModelTest.aSupersededInterrogationNeverWinsTheSheet`.**
   It passed 3/3 in isolation and failed once under full-suite load, on its first
   `awaitCondition` — a 5-second real-time budget that has to cover a DataStore
   read on a loaded emulator. Deliberately not "fixed" here by inflating the
   timeout; the right change is probably to inject the key rather than read it
   through DataStore in this test.
2. **Resolve the debug-keystore mismatch on the Pixel 7**, so instrumented runs can
   use the real device. Uninstalling `com.aus.deutschflow` would clear its learning
   data, which is why this session did not do it.
3. **A manual critical-path walkthrough on a real phone** — speak, transcribe,
   save, study, practise. It is the one thing neither the emulator nor CI covers,
   and the new article picker is worth seeing under a thumb.
4. **`git add --renormalize gradlew.bat`** in its own commit, to stop every future
   diff opening with a whole-file change nobody made.

### Consider later

5. A stop-word filter on extracted keywords, if bare articles or filler words show
   up in real transcripts. Not observed in fixtures, so not built on speculation.
6. An axe or Lighthouse accessibility pass over all six routes, and a real TalkBack
   pass on Android. This audit fixed the defects it could prove and added browser
   tests for each; it did not run an automated sweep.
7. Plural and conjugation in the vocabulary editor, if the article and example
   fields prove they are used.
8. Consider whether `CACHE_NAME` should carry the build id. It is documented now
   as the manual bump it is; automating it would need the service worker to become
   a build artefact rather than a static file.

### Requires a product decision

10. **The cloud-sync surface is a mock presented as a feature.** The copy says "not
    available yet", but a Sign in button that succeeds and a sync button that spins
    still read as functionality. Either build it, or reduce it to a single line of
    text until there is a backend. Not decided autonomously — it is product
    direction, not a defect.
11. **Whether the web app should exist under the same privacy promise as Android.**
    The README is now honest about the difference. Whether shipping a
    speech-learning app whose audio leaves the device is acceptable for this
    product is the owner's call, not an engineering one.

---

## Files changed

### Documentation

| File | Why |
| :--- | :--- |
| `README.md` | Names both applications; per-platform recognition claim; new `Privacy` section covering voice, Groq, key storage, SW caching and deletion. |
| `docs/COMPREHENSIVE_AUDIT_2026.md` | This report. |
| `docs/AUDIT_REPORT.md` | Historical banner corrected to schema v12 and pointed at this report. |

### Web

| File | Why |
| :--- | :--- |
| `web/next.config.ts` | Added `Permissions-Policy`; added `object-src 'none'` to the CSP. |
| `web/vercel.json` | The identical declaration, so the two cannot drift. |
| `web/public/sw.js` | `install` now applies the navigation handler's redirect rule, so the login page cannot be precached under a shell route. `CACHE_NAME` documented as the manual bump it is. |
| `web/tsconfig.json` | `noUnusedLocals`, so the next dead declaration fails CI. |
| `web/src/components/layout/AppShell.tsx` | `aria-current` and a focus ring on the mobile bottom bar. |
| `web/src/components/ui/ModalDialog.tsx` | Focus trap, initial focus, focus restoration, Escape, non-tabbable backdrop, localized fallback label. |
| `web/src/app/(app)/settings/page.tsx` | Radio semantics; speech-privacy line; removed the credential form that discarded input; dead import. |
| `web/src/app/(app)/vocabulary/page.tsx` | Article picker and example field in the editor. |
| `web/src/app/(app)/study/page.tsx` | Dead translator binding. |
| `web/src/hooks/useVocabulary.ts` | `addVocabulary` carries the grammar the editor now collects. |
| `web/src/lib/ai/groq.ts` | `textField`/`textList` — type-safe parsing matching Android's coercion. |
| `web/src/lib/speech/recognizer.ts` | Removed a `currentLanguage` field written on every session and read by nothing. |
| `web/src/lib/db/repository.ts` | Dead type import. |
| `web/src/lib/db/schema.ts` | Header corrected to Room v12, with a note on why the two version numbers differ. |
| `web/src/lib/i18n.ts` | Dialog labels, speech-privacy note, grammar-editor labels, honest practice verdicts, wipe copy — all in both languages. |

### Android

| File | Why |
| :--- | :--- |
| `app/.../ui/screens/VocabularyScreen.kt` | Article picker (`selectableGroup` + `Role.RadioButton`) and example field in the editor. |
| `app/.../ui/viewmodel/VocabularyViewModel.kt` | `addVocabulary` carries article and example. |
| `app/src/main/res/values/strings.xml` (+ `-de`) | Grammar-editor labels, honest practice verdicts, wipe copy that says what it keeps. |
| `app/src/androidTest/.../TestPreferences.kt` | `cancelAndJoin` — closes the DataStore teardown race. |
| `app/src/androidTest/.../ErrorBannerAnnouncementTest.kt` | `UiAutomation` service info, so the listener actually receives events on API 36. |

### Tests

| File | Why |
| :--- | :--- |
| `web/tests/groq.test.ts` | Six hostile-type cases and two prompt-injection cases. |
| `web/tests/headers.test.ts` | `Permissions-Policy` drift coverage; `object-src` and per-feature assertions. |
| `web/tests/repository.test.ts` | Due-card ordering; deletion scope including "the key survives". |
| `web/tests/smoke.spec.ts` | Four accessibility regressions; two selectors updated from `button` to `radio`; dead type import. |
| `web/tests/recognizer.test.ts` | Dead helper. |

**Not touched:** the in-progress uncommitted work that was already in the tree when
this audit began — the `isSaved` transcript state, the library empty-state rework,
the practice sentence rotation, and the per-session XP guard on both platforms.
Each was read and verified before building around it.

---

## A note on what changed in this report

An earlier draft said Android had a vocabulary edit path the web lacked. That was
wrong: both editors carried German and translation only. The claim was corrected
once `VocabularyScreen.kt` was read rather than inferred from the presence of an
edit affordance, and the fix was then applied to both platforms rather than one.
