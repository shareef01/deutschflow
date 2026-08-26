# DeutschFlow

**Speak German. See it translated. Keep what you learn.**

[![Build](https://github.com/shareef01/deutschflow/actions/workflows/build.yml/badge.svg)](https://github.com/shareef01/deutschflow/actions/workflows/build.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF?logo=kotlin)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=android)
![Minimum SDK](https://img.shields.io/badge/minSdk-31%20(Android%2012)-3DDC84?logo=android)
![Target SDK](https://img.shields.io/badge/targetSdk-37-3DDC84?logo=android)
![License](https://img.shields.io/badge/license-MIT-999999)

DeutschFlow is a modern, native Android application designed for learning German contextually. Instead of memorizing static, disconnected word lists, DeutschFlow builds a personalized vocabulary library derived directly from what you speak in real life.

Speak a sentence in German, and the app transcribes it on-device in real time, translates it, extracts key vocabulary with grammatical cases and genders, and schedules those words for spaced repetition and pronunciation shadowing practice.

---

## 📸 Key Interfaces

| 🎙️ Real-Time Speaking & Translation | 📚 Vocabulary Library & Filters |
| :---: | :---: |
| <img src="docs/screenshots/01-transcript.png" width="320" alt="Transcript Screen"/> | <img src="docs/screenshots/02-library.png" width="320" alt="Library Screen"/> |

| 🧠 Spaced Repetition (SM-2) Flashcards | 🗣️ Pronunciation Shadowing & Roleplay |
| :---: | :---: |
| <img src="docs/screenshots/03-study.png" width="320" alt="Study Flashcards"/> | <img src="docs/screenshots/04-practice.png" width="320" alt="Practice Screen"/> |

---

## ✨ Core Features

- **🎙️ On-Device Speech Recognition:** Powered by `createOnDeviceSpeechRecognizer` with multi-dialect support (Germany `de-DE`, Austria `de-AT`, Switzerland `de-CH`). Instant transcription with full offline privacy.
- **⚡ AI Grammar Spotlight & Translation:** Integrated with Groq AI (`gpt-oss-120b`) to provide instantaneous English translations, grammatical gender/case breakdowns (`der/die/das`, `Akkusativ`, `Dativ`), and contextual example sentences.
- **🧠 Spaced Repetition System (SRS):** Built-in SuperMemo-2 (SM-2) scheduling algorithm with 4-tier grading (*Again*, *Hard*, *Good*, *Easy*) and daily XP goal tracking.
- **🗣️ Pronunciation Shadowing & AI Roleplay:** Real-time speech diff scoring to pinpoint mispronounced German words alongside interactive situational roleplay scenarios.
- **🔒 Keystore-Backed Security:** API credentials encrypted via AES-GCM hardware-backed Android Keystore.
- **🎨 Glassmorphic Material 3 UI:** Fluid animations, spring interaction feedback, dynamic scroll fading edges, and dark theme support.

---

## 🏗️ Architecture & Tech Stack

```
com.aus.deutschflow
├── data
│   ├── db (Room Database & DAOs)
│   ├── model (Word, Transcript, SRS stats)
│   └── preferences (Encrypted DataStore)
├── service
│   ├── SpeechRecognitionService (On-Device STT)
│   ├── TextToSpeechService (Native TTS)
│   ├── SRSEngine (SuperMemo-2 Algorithm)
│   └── GroqHelper (AI Translation & Grammar)
├── ui
│   ├── components (Design System, Glassmorphic Surfaces, SegmentedTabs)
│   ├── screens (Transcript, Library, History, Study, Dashboard, Practice, Roleplay, Settings)
│   ├── theme (Color Tokens, Spacing, Typography, Dynamic Fading Edges)
│   └── viewmodel (StateFlow, Coroutines, MVVM)
└── di (Hilt Dependency Injection)
```

- **UI:** 100% Jetpack Compose with Material 3.
- **Architecture:** MVVM + Unidirectional Data Flow (UDF).
- **Concurrency & Reactivity:** Kotlin Coroutines & `StateFlow`.
- **Dependency Injection:** Hilt.
- **Storage:** Room SQLite Database + Jetpack DataStore.

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug / Meerkat or newer.
- Android SDK 35+ (Min SDK 31 / Android 12).
- Physical Android device or Emulator with Google Play services (for on-device speech model).

### Installation
1. **Clone the repository:**
   ```bash
   git clone https://github.com/shareef01/deutschflow.git
   cd deutschflow
   ```
2. **Build and Run:**
   ```bash
   ./gradlew assembleDebug
   ```
3. **Configure API Key (Optional):**
   - Open **Settings** inside the app (`⚙️` icon in top-right).
   - Enter your [Groq API Key](https://console.groq.com/) for instant AI translations and grammar notes.

---

## 📄 License

Distributed under the [MIT License](LICENSE).
