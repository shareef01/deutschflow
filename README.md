# DeutschFlow

**Speak German. See it translated. Keep what you learn.**

DeutschFlow is an Android app for learning German through real speech. You talk to it
in German; it transcribes what you said, translates it, pulls the key vocabulary out
of the sentence, and files everything into a personal library that grows into
flashcards, pronunciation practice and a daily word on your home screen.

Built with Kotlin, Jetpack Compose and a design system called *Obsidian & Azure* —
true black, glass surfaces, one azure light source.

## Screenshots

| | |
| --- | --- |
| **Transcript** — speak, get a translation, save it | **History** — everything ever said, searchable |
| ![Transcript](docs/screenshots/01-transcript.png) | ![History](docs/screenshots/02-history.png) |
| **Library** — your vocabulary, from AI and by hand | **Word detail** — translation and the model's example |
| ![Library](docs/screenshots/03-library.png) | ![Word detail](docs/screenshots/04-word-detail.png) |
| **Study** — flashcards that earn XP and a streak | **Practice** — speak a sentence, get word-by-word scoring |
| ![Study](docs/screenshots/05-study.png) | ![Practice](docs/screenshots/06-practice.png) |
| **Settings** — API key, dialect, stats, privacy controls | **Home screen** — the daily word, as a widget |
| ![Settings](docs/screenshots/07-settings.png) | ![App drawer](docs/screenshots/08-app-drawer.png) |

## What it does

- **Speech to transcript** — on-device speech recognition (no cloud audio), with
  partial results, per-error guidance and a language-pack download trigger for
  devices without German recognition.
- **AI translation** — each utterance goes to Groq's chat-completions endpoint
  (Llama 3.3 70B), which returns a translation, 3–5 key vocabulary words and a
  natural example sentence. The client is hand-rolled on `HttpURLConnection` +
  `org.json` — zero HTTP dependencies.
- **A growing library** — words from AI *or* typed by hand, with search, edit,
  delete and text-to-speech. Works fully offline once filled.
- **Study mode** — shuffled flashcards with card-flip animation, autoplay, and an
  XP/streak system with atomic read-modify-write and per-session award tracking.
- **Pronunciation practice** — speak the sentence; every word is scored, with
  umlaut-tolerant matching (`Uebung` = `Übung`) so typing conventions don't get
  marked wrong.
- **A daily word** — WorkManager picks one word per day (deterministically, by
  date), posts it as a notification and keeps the Glance home-screen widget in
  sync, so both always agree.
- **German or English UI** — full localization with per-app language support
  (Android 13+), plus `de-DE` / `de-AT` / `de-CH` dialect selection for
  recognition.

## Use cases

```mermaid
flowchart LR
    User([User]) --> A[Speak a sentence]
    A --> B[Read translation + keywords]
    B --> C[Save to library]
    C --> D[Study with flashcards]
    C --> E[Practice pronunciation]
    C --> F[Daily word widget + notification]
    D --> G[XP and streak]
    E --> H[Word-by-word feedback]
    G --> User
    H --> User
```

| Use case | Journey |
| --- | --- |
| **Understand something said to you** | Transcript → speak → read the translation → copy it or save the words you want to remember |
| **Build vocabulary from real life** | Every saved word keeps the model's example sentence; the library grows from what *you* actually heard |
| **Review with stakes** | Study shuffles the library into flashcards; each "Got it" banks 10 XP once per session and keeps the streak alive with calendar-day logic |
| **Train your pronunciation** | Practice picks a real sentence from your library, you repeat it, and each word is judged — tolerant of umlaut spelling variants |
| **Stay exposed daily** | At 9:00 the app posts the word of the day and the widget shows the same word, chosen by date so every surface agrees |

## Architecture

Single-activity, MVVM, dependency injection via Hilt. The UI is pure Compose and
reads only `StateFlow`s; everything that touches the world lives behind a service
or a DAO, so the pure logic (parsing, scoring, streaks, daily-word selection) is
JVM-testable without Android.

```mermaid
flowchart TB
    subgraph UI["UI layer — Jetpack Compose"]
        S1[Transcript] --- S2[History]
        S3[Library] --- S4[Study] --- S5[Practice] --- S6[Settings]
        W[WordWidget — Glance]
    end
    subgraph VM["ViewModel layer"]
        V1[TranscriptViewModel]
        V3[VocabularyViewModel]
        V4[StudyViewModel]
        V5[PracticeViewModel]
        V6[SettingsViewModel]
    end
    subgraph SVC["Service layer"]
        SR[SpeechRecognizerHelper]
        TTS[TTSHelper]
        VP[VocabularyProcessor → GroqHelper]
        DW[DailyWord / DailyWordWorker / DailyWordNotification]
    end
    subgraph DATA["Data layer"]
        ROOM[(Room: vocabulary, transcripts, user_stats)]
        DS[(DataStore: settings)]
        KS[[AndroidKeyStore: AES-GCM key]]
    end
    S1 --- V1 --- SR & VP & ROOM
    S3 --- V3 --- TTS & ROOM
    S4 --- V4 --- TTS & ROOM
    S5 --- V5 --- SR & TTS
    S6 --- V6 --- DS & ROOM & DW
    W --- DW --- ROOM
    DW --> WN[WorkManager]
    VP --> GROQ[(Groq API — HTTPS)]
    DS --- KS
```

```mermaid
sequenceDiagram
    participant U as User
    participant SR as SpeechRecognizerHelper
    participant VM as TranscriptViewModel
    participant VP as VocabularyProcessor
    participant G as Groq (llama-3.3-70b)
    participant DB as Room
    participant WG as WidgetUpdater

    U->>SR: speak
    SR-->>VM: partial results → final utterance
    VM->>DB: insert transcript
    VM->>VP: processText(utterance, apiKey)
    VP->>G: POST /chat/completions (Bearer key)
    G-->>VP: translation, keywords, example
    VP-->>VM: AIResult.Success
    U->>VM: Save
    VM->>DB: insert vocabulary row
    VM->>WG: refresh home-screen widget
```

### Database

Room, version 4, with exported schemas and migrations validated by test. Release
builds have **no** destructive-migration fallback — a missing migration is a
failing test, not a wiped library.

```mermaid
erDiagram
    VOCABULARY {
        int id PK
        string germanText
        string englishTranslation
        long timestamp
        string exampleSentence
    }
    TRANSCRIPTS {
        int id PK
        string fullText
        long timestamp
    }
    USER_STATS {
        int id PK
        int xp
        int streak
        long lastActivityTimestamp
    }
```

## Technicalities worth reading

**The API key is the one secret, and it is treated like one.**
- Stored AES-GCM-encrypted under a key that lives in the **Android Keystore** and
  never enters the process; a fresh random IV per encryption, enforced by the
  Keystore itself.
- The DataStore file is **excluded from cloud backup and device transfer** — a
  restored copy would be undecryptable anyway, but a store of dead ciphertext is
  worse than no store.
- The key is **write-only** from the UI: Settings reports whether one exists but
  never reconstructs the plaintext into a text field, and the input is
  password-masked so password managers don't offer to "save" it.
- If encryption fails, the key is *not* written — there is no plaintext fallback,
  and the UI now says so instead of claiming a save that didn't happen.
- Release builds strip `Log.d`/`Log.v` via R8, so no future logging mistake can
  leak a transcript or a key. Recognition content is never logged.

**The AI client is deliberately dependency-free.** `HttpURLConnection` +
`org.json` replace an archived Gemini SDK and its whole transitive payload. The
request is the OpenAI chat shape most providers speak, the user's transcript and
the system prompt travel in separate message roles, and the prompt tells the model
explicitly which wins — so a spoken sentence can't inject instructions.

**Concurrency is where the bugs lived, so the fixes are structural.** XP awards
run in a Room transaction with a one-shot read (a Flow read would leave the
transaction); a card awards once per session; the recognizer's results are a
`SharedFlow` so callers never read a previous session's text; the widget and the
notification pick the daily word through one date-deterministic selection, ordered
by id so two words saved in the same millisecond can't race.

**Small correctness details that matter to learners.** `Übung` and `Uebung` score
as the same word (German keyboards without umlauts produce the latter);
`lowercase()` is locale-invariant so a Turkish system locale can't break scoring;
streaks compare **calendar days in the device's zone**, not "24 hours apart".

## Design system — Obsidian & Azure

- True black ground (`#000000` — an unlit OLED pixel), surfaces drawn as **glass**:
  3% white fill plus a 1dp azure border that falls off diagonally from the
  top-left corner — one light source for the whole app.
- One accent ramp: `AzureGlow #00E5FF → AzureDeep #0A84FF`. Everything that glows
  is a gradient between those two stops.
- One shape scale (8/12/16/20/28dp + pill), one type scale, one button language —
  and one theatrical element: the recording control, a breathing, rotating azure
  disc.
- The launcher icon is the system in miniature: a circular glass disc carrying an
  **Ü**, the one glyph that cannot be anything but German, filled with the same
  ramp; a monochrome variant feeds Android 13+ themed icons.
- Full dark-only Material 3 theme, `en` + `de` localized, WCAG-checked muted
  colors (4.6:1 minimum for body text).

## Tech stack

| Concern | Choice |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3, navigation-compose, window size classes |
| DI | Hilt (incl. EntryPoints for widget and worker) |
| Persistence | Room (exported schemas, migrations 2→3→4), DataStore Preferences |
| Background | WorkManager (periodic daily word, `KEEP` policy) |
| Home screen | Glance app-widget |
| Networking | `HttpURLConnection` + `org.json` (no HTTP/JSON dependencies) |
| Speech | Android `SpeechRecognizer` (on-device), `TextToSpeech` |
| Build | Gradle 9, AGP 9.3, Kotlin 2.4, KSP2, version catalog, R8 + resource shrink |
| Target | minSdk 31 (Android 12) / targetSdk 36 / compileSdk 37 |

## Testing & CI

- **45 JVM unit tests** for the pure logic: response parsing, pronunciation
  scoring, streak math, daily-word selection, worker delay, error extraction.
- **Instrumented suite** (CI, API 31 emulator): Room migration validation against
  historical schemas, DAO behavior, ViewModel state, and the API-key storage
  contract — including "the key never appears in the file on disk".
- **`GroqLiveTest`** proves the real request path against the real service once,
  using the device's own stored key, and skips rather than fails when no key is
  configured.
- GitHub Actions runs unit tests, lint and a full R8 **release build** on every
  push, and the instrumented suite on an emulator.

## Building

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest lintDebug
./gradlew assembleRelease          # unsigned unless keystore.properties exists
```

Release signing is opt-in: drop a `keystore.properties` next to
`settings.gradle.kts` (storeFile/storePassword/keyAlias/keyPassword); it and
`*.jks` are gitignored.

## Project layout

```
app/src/main/java/com/aus/deutschflow/
├── data/local/          Room entities, DAOs, migrations, KeystoreCipher, PreferenceManager
├── di/                  Hilt modules and EntryPoints
├── service/             speech, TTS, Groq client, daily word + worker + notification
├── ui/
│   ├── screens/         the six screens + dialogs
│   ├── components/      OracleMic, ErrorBanner, EmptyState, OnLeavingScreen
│   ├── navigation/      NavHost, tab/rail switching, Screen definitions
│   ├── viewmodel/       one ViewModel per screen
│   ├── widget/          Glance widget + updater
│   └── theme/           Obsidian & Azure design system
└── MainApp / MainActivity
```
