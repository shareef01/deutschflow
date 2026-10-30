# DeutschFlow — engineering case study

DeutschFlow is a German learning app with two clients, a native Android app and an installable web PWA. This document explains why it is built the way it is. The [README](../README.md) covers what it does and how to run it. For the visual rules, the threat model and the backup envelope, see [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md), [`SECURITY.md`](SECURITY.md) and [`BACKUP_FORMAT.md`](BACKUP_FORMAT.md).

Nothing here reports user research, adoption or performance numbers, because none were collected. Where a claim rests on a test, the test is named.

---

## 1. The problem

Most German a learner meets is not in a course. It is a train announcement, a word on a menu, a phrase a colleague used. Those moments are the most relevant material a learner has, and they are almost always lost.

DeutschFlow is built around one loop:

1. **Capture.** Speak or type the sentence where you meet it.
2. **Understand.** Get a translation and the grammar behind it: which case, and why.
3. **Keep.** Save the words worth keeping, each with its article, plural and an example.
4. **Retain.** Review them with spaced repetition, and practise saying them.

The intended user is an adult learner at roughly A2–B2 who lives or works with German. They study in short sessions on a phone and longer ones at a desk, and have their own reasons to be wary of handing personal conversations to a service.

## 2. Constraints

| Constraint | Consequence |
| :-- | :-- |
| One developer, no backend budget | No accounts, no sync service, no server database |
| Learners talk about their own lives | Data stays on the device; the app collects nothing |
| AI calls cost money | The learner brings their own Groq key; the app never proxies it |
| Phones first, desktops too | Native Android for the phone; a responsive PWA for everything else |
| Recognition is imperfect | Recognition output is never presented as a judgement of pronunciation |

## 3. Why two clients

On Android, the platform offers what this app needs most:

- an **on-device speech recogniser** (`createOnDeviceSpeechRecognizer`), so audio can stay on the phone;
- **hardware-backed key storage** for the API key;
- a home-screen widget and a daily-word notification.

A PWA covers laptops, iPhones and anyone who will not install an app, with no store review.

Kotlin Multiplatform or React Native would share code, but at the cost of the parts that justify the Android client: direct speech and Keystore APIs, and native Compose UI. So the clients share **behaviour** instead of source.

## 4. Keeping two clients consistent

Two codebases drift unless something stops them. DeutschFlow relies on five mechanisms:

- **A shared contract fixture.** [`cross-platform-contract.json`](../app/src/test/resources/cross-platform-contract.json) holds input/output cases for behaviour that must match exactly: SM-2 scheduling, German key folding (NFC, umlauts, ß, apostrophes), practice tokenisation, and the limits and strict JSON parsing for AI payloads. `CrossPlatformContractTest.kt` and `web/tests/contract.test.ts` both run it, so a change on one side that breaks the other fails CI.
- **One backup format.** Each client imports what the other exports; `cross-platform-backup.test.ts` holds the web side to it.
- **Palette parity.** [`tools/palette_parity.py`](../tools/palette_parity.py) checks that every colour token in `Color.kt` matches its CSS custom property.
- **Contrast as a test.** [`tools/contrast.py`](../tools/contrast.py) measures every foreground/background pairing the design uses, in both themes, against WCAG thresholds, and exits non-zero on a failure. When this round added a filled primary button, its label-on-fill pairing went into the script first: 5.3:1 dark, 5.5:1 light.
- **String parity.** [`tools/audit_repo.py`](../tools/audit_repo.py) requires the English and German string sets to match key for key on each client.

The UI is deliberately not pixel-identical. Android follows Material conventions; the web uses links, a sidebar rail and keyboard shortcuts.

## 5. Local-first persistence

- **Android** uses Room (SQLite) at schema version 18. Each version step has an explicit migration, walked on a device in CI. Learning data is excluded from Android cloud backup and device transfer.
- **Web** uses IndexedDB through Dexie, at schema version 9.

Browser storage can be evicted: Safari can drop a non-installed site's data after a week of non-use, and Chrome can drop it under storage pressure. Data on a phone is lost with the phone. Both clients therefore export the whole library as plain JSON or as a passphrase-encrypted envelope. The web app also requests persistent storage, shows whether it was granted, and reminds the learner when a sizeable library has no recent backup.

Import is **validated before anything is written**. Each record is checked: schedules must be coherent (a new card has no review date; a reviewed card has an interval), timestamps must fall within bounds, and size is capped. Only then does the import merge:

- matching words keep their review progress;
- XP never decreases;
- streaks are recomputed from the activity log;
- API keys are never exported, because their ciphertext only decrypts on the device that wrote it.

The portfolio screenshot script loads its synthetic library through this same import. While it was being written, the validator rejected a hand-made fixture with an incoherent schedule, which is the validator doing its job.

## 6. AI integration boundaries

- **The key** is encrypted at rest with AES-GCM, under Android Keystore or a non-extractable WebCrypto key, and is sent only to Groq.
- **Only text** goes to Groq: the sentence, the word, or the roleplay turn. Never audio.
- **Responses are untrusted input.** The model is asked for strict JSON. Both clients parse it against shared length limits and reject anything malformed instead of rendering it.
- **A truncated answer is reported as truncated.** It is not shown as if it were a complete translation.
- **Abandoned requests are cancelled**, so a learner who moves on does not pay for an answer nobody will read.

## 7. Speech privacy trade-offs

The two clients differ here, and the app says so rather than hiding it:

- **Android** asks for the on-device recogniser and prefers offline operation. With the German offline model installed, audio does not leave the phone.
- **Web** uses the Web Speech API, which the browser vendor implements, usually as a cloud service. The app cannot change that. Settings explains it, and the README does not claim on-device recognition for the web.
- **Firefox** has no recogniser. Every screen that takes speech also takes typing, through a Speak/Type switch, because everything after recognition only needs a string.

## 8. Spaced repetition

Scheduling is SM-2 with deliberate changes, identical on both clients and pinned by the shared fixture:

- an ease-factor floor, and an interval ceiling, which SM-2 lacks;
- a separate multiplier for **Hard**, so a struggle still counts as a success, with a shorter next interval;
- **extra practice** (studying when nothing is due) never pushes a schedule further out. A failure during extra practice still counts, because discovering a card is not known is real information;
- every review writes an immutable `ReviewEvent`, so history can be audited and backups can merge by stable identity.

Grades unlock only once the answer is showing. A grade given blind is a guess, and SM-2 would record it as recall. Each grade previews its next interval, and on the web Space flips the card and 1–4 grade it. `study-gate.spec.ts` and `study-reveal-gate.test.ts` hold this rule.

## 9. Testing and accessibility strategy

| Layer | Android | Web |
| :-- | :-- | :-- |
| Domain logic | JUnit: SRS, folding, Groq parsing, streaks | Vitest: the same, plus backup, vault, auth, recogniser, TTS |
| Shared behaviour | `CrossPlatformContractTest` | `contract.test.ts`, `srs-shared-fixture.test.ts`, `cross-platform-backup.test.ts` |
| Persistence | Room migration walk and DAO tests (instrumented) | Dexie upgrade and import tests against fake-indexeddb |
| UI behaviour | ViewModel tests, Compose announcement test | Playwright flows with Groq stubbed at the network layer |
| Accessibility | TalkBack announcements for errors | axe-core WCAG 2.1 AA on every route; keyboard tests for tabs, menus and dialogs |
| Layout | — | Navigation structure at 360/390/768/1440 px; scroll-region regressions |
| Cross-browser | — | WebKit and Firefox compatibility, speech-fallback and logout smoke tests |

Pixel baselines are a local design-review tool, not a CI gate. The same code renders 2–4% differently on a developer machine and in CI's Playwright image, from font rasterisation alone. Any budget loose enough to survive that would miss a real regression, so CI asserts structure instead.

Accessibility is built into the components rather than checked afterwards:

- **Focus:** one global focus ring; dialogs trap focus and restore it on close.
- **Motion:** reduced to near zero under `prefers-reduced-motion`, rather than removed, so transition-end handlers still fire.
- **Colour is never the only signal:** the selected navigation item carries a marker, not just a colour.
- **Errors:** they appear in live regions, so a refused microphone is announced.
- **Scroll regions:** each one can be reached with the keyboard.

## 10. Design system

The identity is a deep blue-black ground with one accent ramp, from cyan to electric blue. Colour is reserved for actions, progress and the material being learned. The tokens live in `globals.css` (CSS-first Tailwind 4) and mirror `Color.kt`, `Type.kt` and `Shape.kt`. The light palette was designed and measured on its own, not derived by inverting the dark one.

This round refined the system rather than replacing it:

- **One filled primary action per screen** (`glow="solid"`): Save to library, Start review, Add a word, the idle microphone. Before, every button was the same quiet tint, so nothing said which action a screen was for.
- **One section-label style** (`eyebrow`), replacing several tracked-uppercase heading styles in different blues.
- **Calmer tabs:** sentence case at label size, which also makes the tab bar one consistent component on Study and Practice.
- **Smaller empty states:** the disc dropped from 176 px to 80 px.

## 11. Problems found in this round, and how they were solved

**Long screens could not be scrolled with the mouse on desktop.** To make inner regions such as the roleplay log scroll, the app shell had been given a fixed viewport height. But the content area was a CSS grid with an implicit `auto` row, and an `auto` row grows to the full height of its content however short the container is, so no page region ever got a bounded height. Measured on a 1440×900 desktop with 30 saved words: the last word sat at y=3241 and a mouse wheel moved nothing. The fix is one declaration, `grid-rows-[minmax(0,1fr)]`. [`scroll-regions.spec.ts`](../web/tests/scroll-regions.spec.ts) now fails on the old shell and passes on the new one. The bug only surfaced once screenshots were taken against a realistic library; every earlier visual baseline was an empty state.

**The Library hid the grammar.** Rows showed the German word in accent blue, with nothing about its article or plural. Rows now lead with the word, its article set quietly beside it, and the meaning and plural underneath. Word details list plural and forms, show the example as a quotation, and say plainly when no synonyms are saved, instead of showing two boxes of em dashes. Edit and Delete sit in the details pane, so they are no longer reachable only through an overflow menu.

**An empty library said "All caught up!"** True, but no help to someone who has not started. The dashboard's Today card now has three states: start your library, which links to Transcript; cards due, with a filled Start review button; and caught up, which offers practice that leaves the schedule alone.

**Off-palette styling.** Settings used `amber-400`, `emerald-400`, a hard-coded `#00f0ff` and a `text-body-small` class that does not exist in the type scale, so that text fell back to the inherited size. Roleplay translations were rendered at 60% opacity in italics. All of these now use measured tokens.

**Screenshots that described the CI machine instead of the app.** Headless Chromium has no German voice, so every flashcard capture showed a "German speech isn't installed" banner. The capture script stubs a silent German voice. The banner itself is real behaviour, covered by the TTS unit tests.

## 12. Current limitations

- Web recognition quality and privacy depend on the browser vendor.
- Practice feedback measures recognition, not pronunciation.
- The Library and Study dashboard hierarchy now exists in Compose too, but the roleplay composer and Practice refinements are still web-only.
- The hosted PWA is password-gated, so there is no public demo.

## 13. Future improvements

- **Carry the Practice and roleplay refinements to Compose**, and add screenshot tests (Compose `captureToImage` or Roborazzi) so the two clients cannot drift visually unnoticed.
- **A public demo deployment.** Each visitor's data stays in their own browser and the server holds no learner data or keys, so a separate ungated build is low-risk. Deploying and operating it is the owner's decision.
- **Pixel baselines generated in CI's own image**, so they can become a gate rather than a review aid.
- **Phoneme-level pronunciation feedback**, if an on-device engine that can provide it becomes practical.
- **Homograph handling** (*Essen* vs *essen*), which needs a vocabulary key migration and is recorded as an open decision in the October audit.
