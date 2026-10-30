# Comprehensive UX & Technical Audit: Cerevo / DeutschFlow

**Date:** 2026-10-21
**Auditor:** Principal Product Designer & Senior Full-Stack Engineer
**Evaluation Frameworks:** Nielsen 10 Usability Heuristics & WCAG 2.2 AA Standards
**Target Clients:** Web (Next.js 16 / React 19 / Tailwind v4) & Android (Jetpack Compose / Material 3)

---

## 1. Executive Summary

This audit assesses the user experience, accessibility, performance, and cross-platform parity of DeutschFlow across both Web and Android clients. DeutschFlow possesses strong foundations: a unified Obsidian & Azure dark palette, local-first storage (Dexie / Room), on-device speech recognition options, and zero-telemetry privacy principles.

However, several critical usability, accessibility, and performance deficiencies were uncovered across both clients:
1. **AudioWaveform Canvas High-DPI Scaling & Runaway rAF Loop (Web - P0 / Perf):** The Web canvas read backing buffer dimensions while the 2D context was already scaled by devicePixelRatio, causing severe clipping and 2x-4x overflow on HiDPI displays. Furthermore, the requestAnimationFrame loop ran continuously at 60Hz/120Hz even when idle, wasting CPU and battery.
2. **Hydration Layout Shift in Viewport Queries (Web - P1):** useViewport.ts used useState(false) + useEffect, forcing desktop browsers to render the mobile bottom navigation first and then shift to the desktop navigation rail after hydration, producing avoidable Cumulative Layout Shift (CLS).
3. **Input Method Restriction on Supported Browsers (Web - P1):** On browsers supporting Web Speech API (Chrome/Edge), the Transcript screen only showed the microphone interface with no option to type German text, blocking learners in quiet environments.
4. **Vocabulary Search German Folding Discrepancy (Web - P1):** useVocabulary.ts filtered via naive substring matching without German character folding, causing searches for "uber", "ueber", or "strasse" to fail for words like "Über" or "Straße".
5. **Search Input Missing Clear Button (Web - P2):** Neither the Vocabulary nor History search inputs featured a one-tap clear button.
6. **Word Details Bottom Sheet Small-Viewport Clipping (Android & Web - P1):** Neither Web nor Android bottom sheets handled vertical scrolling properly under short viewports (landscape mode / keyboard open), rendering the "Save" action unreachable.
7. **Study Dashboard Call to Action Absent on Web (Web - P1):** Android DailyGoalCard included a "Start Daily Review" primary action, whereas Web dashboard displayed passive metrics without an actionable entry point into review.
8. **Flashcard Pre-Flip Grading Exposure (Web - P2):** SRS grading buttons were exposed before the card was flipped to reveal the translation, confusing the learning workflow.

---

## 2. Screen-by-Screen Audit Findings

### Screen 1: Transcript & Live Speaking (Primary Experience)
- **Source Files:** web/src/app/(app)/transcript/page.tsx, app/src/main/java/com/aus/deutschflow/ui/screens/TranscriptScreen.kt
- **Issue 1.1 (P1 - Usability / Discoverability):** Input modality locked to microphone when Speech API is available.
  - *Observation:* Users on desktop Chrome or Edge have no toggle to type German text unless speech recognition is completely unavailable in the browser.
  - *Impact:* High cognitive friction in public, quiet, or noisy settings.
  - *Heuristic:* Flexibility and efficiency of use (Nielsen #7).
  - *Solution:* Provide a clear, accessible segmented toggle ("Speak" / "Type") allowing learners to seamlessly type or speak regardless of browser engine.
- **Issue 1.2 (P2 - Learning Usability):** Lack of audio pronunciation playback on recognized transcript.
  - *Observation:* After speaking or typing a sentence, the user could copy the text or save vocabulary, but could not listen to the sentence pronounced via Text-to-Speech (TTS).
  - *Solution:* Add a dedicated TTS audio playback button alongside the copy action.
- **Issue 1.3 (P2 - Transparency):** AI translation loading feedback.
  - *Observation:* While Groq is processing the translation, the feedback was subtle; network requirements (requiring API key and internet) were not prominently communicated when idle.
  - *Solution:* Provide clear state messages for transcription vs. translation and transparently note Groq requirements.

---

### Screen 2: Vocabulary Library
- **Source Files:** web/src/app/(app)/vocabulary/page.tsx, web/src/hooks/useVocabulary.ts, app/src/main/java/com/aus/deutschflow/ui/screens/LibraryScreen.kt
- **Issue 2.1 (P1 - Usability / Search):** Non-normalized German character matching in search.
  - *Observation:* Searching for "ubersetzung" failed to match "Übersetzung". Searching for "fussball" failed to match "Fußball".
  - *Impact:* Learners frequently type without umlaut keys (e.g. on English keyboards); failing to find saved words degrades trust.
  - *Heuristic:* Error prevention & Recognition rather than recall (Nielsen #5, #6).
  - *Solution:* Integrate foldGerman normalization across both German target text and query strings, while preserving English translation search.
- **Issue 2.2 (P2 - Ergonomics):** Missing one-tap clear button in search input.
  - *Observation:* Clearing a search term required selecting all text and hitting backspace.
  - *Solution:* Provide an accessible, instant clear button (X) when the search field contains text.

---

### Screen 3: Word-Detail Bottom Sheet
- **Source Files:** web/src/components/ui/WordDetailsSheet.tsx, app/src/main/java/com/aus/deutschflow/ui/components/WordDetailsBottomSheet.kt
- **Issue 3.1 (P1 - Accessibility / Responsiveness):** Missing scrollability on short viewports.
  - *Observation:* On Android, WordDetailsBottomSheet.kt lacked verticalScroll(). On Web, WordDetailsSheet.tsx lacked max-h-[85dvh] and overflow-y-auto.
  - *Impact:* In landscape orientation (height < 450dp) or with accessibility font scaling (>1.3x), the "Save" button and example sentence were completely pushed off-screen and unreachable.
  - *WCAG:* 1.4.4 Resize Text & 1.4.10 Reflow.
  - *Solution:* Add Modifier.verticalScroll(rememberScrollState()) to Android Column and max-h-[85dvh] overflow-y-auto with safe-area padding on Web.
- **Issue 3.2 (P2 - Ergonomics):** Explicit close action on Web sheet.
  - *Observation:* Web bottom sheet only closed via clicking the outer backdrop or pressing Escape; no explicit top-right close icon existed.
  - *Solution:* Add an explicit Close icon button at the top-right corner.

---

### Screen 4: Study Dashboard
- **Source Files:** web/src/components/ui/DashboardContent.tsx, app/src/main/java/com/aus/deutschflow/ui/screens/DashboardScreen.kt
- **Issue 4.1 (P1 - Usability / Action Orientation):** Missing "Start Review" action on Web dashboard.
  - *Observation:* Android DailyGoalCard included PrimaryActionButton("Start Daily Review"). The Web dashboard showed only passive analytics (XP circle, retention stats, heatmap) without an action button.
  - *Impact:* Learners navigating to Study -> Dashboard were left wondering what action to take next.
  - *Heuristic:* Visibility of system status & Prompting user action.
  - *Solution:* Introduce an actionable Due Today hero card in DashboardContent with real review counts and an immediate "Start Review" button that switches to Flashcards.
- **Issue 4.2 (P2 - Consistency):** Missing dashboard.startReview translation key on Web.
  - *Solution:* Add dashboard.startReview to both en and de in i18n.ts.

---

### Screen 5: Spaced Repetition Flashcards
- **Source Files:** web/src/app/(app)/study/page.tsx, app/src/main/java/com/aus/deutschflow/ui/screens/StudyScreen.kt
- **Issue 5.1 (P2 - Interaction Hierarchy):** SRS grading buttons visible prior to card flip.
  - *Observation:* In FlashcardMode, the 4 grading buttons ("Again", "Hard", "Good", "Easy") were active before the user flipped the card.
  - *Impact:* Users could accidentally grade a card before seeing the translation, violating spaced repetition principles.
  - *Solution:* Clearly indicate the reveal step ("Tap to flip / Space to flip") and highlight grading buttons when the answer is revealed. Display the German word on the back of the card alongside the English translation for reinforcement.

---

### Screen 6: History
- **Source Files:** web/src/app/(app)/history/page.tsx, web/src/hooks/useHistory.ts
- **Issue 6.1 (P1 - Search Discoverability):** History search did not search English translations.
  - *Observation:* useHistory.ts filtered solely on t.fullText (German). If a user searched for "airport" or "supermarket", no past transcripts were found.
  - *Solution:* Filter on both fullText and translation, utilizing German normalization for the German field.
- **Issue 6.2 (P2 - Empty States):** Missing "no matching results" state during search.
  - *Solution:* Render a dedicated EmptyState with a reset action when search yields 0 matches.

---

### Screen 7: Application Shell & Viewport Management
- **Source Files:** web/src/components/layout/AppShell.tsx, web/src/hooks/useViewport.ts
- **Issue 7.1 (P1 - Performance / CLS):** Layout shift during hydration.
  - *Observation:* useMediaQuery in useViewport.ts initialized with useState(false). On desktop, this rendered the mobile bottom bar on SSR and initial client paint, switching to the desktop rail only after useEffect executed.
  - *Solution:* Use useSyncExternalStore with matching SSR snapshot (false) and client snapshot (window.matchMedia(query).matches).

---

## 3. Prioritized Remediation Matrix

| ID | Screen / Area | Severity | Description | Status |
|---|---|---|---|---|
| AUD-01 | Canvas / Perf | P0 | AudioWaveform HiDPI coordinate scaling and infinite rAF loop | Implemented & Verified |
| AUD-02 | AppShell / Perf | P1 | Hydration layout shift in useViewport.ts | Implemented & Verified |
| AUD-03 | Transcript | P1 | Dual Speak / Type input switcher for all browsers | Implemented & Verified |
| AUD-04 | Vocabulary | P1 | German-aware foldGerman search matching | Implemented & Verified |
| AUD-05 | Word Details | P1 | Bottom sheet vertical scrolling in short viewports (Android & Web) | Implemented & Verified |
| AUD-06 | Dashboard | P1 | Due Today Action Hero Banner & Start Review CTA | Implemented & Verified |
| AUD-07 | History | P1 | History search across German & English translations | Implemented & Verified |
| AUD-08 | Flashcards | P2 | Reveal-before-grade workflow & back-side German reminder | Implemented & Verified |
| AUD-09 | Search UI | P2 | Instant clear button in SearchInput | Implemented & Verified |
| AUD-10 | Transcript | P2 | German TTS playback for transcribed speech | Implemented & Verified |


---

## 4. Native Android Physical Device Verification (Google Pixel 7)

In addition to headless unit test suites, the redesigned native Android application was installed and verified on a physical Google Pixel 7 ("panther", 1080x2400 resolution) connected via Android Wireless Debugging.

### 4.1 Verified Screens on Hardware
1. **TranscriptScreen**: Live German speech recognition affordance, dialect selection chip (`de-DE`, `de-AT`, `de-CH`), and real-time audio waveform canvas.
2. **LibraryScreen & VocabularyScreen**: Words list, alphabetical and recency sorting, word count statistics, and one-tap German TTS playback.
3. **HistoryScreen**: Daily grouping of transcript sessions, timestamp metadata, word counts, and search filtering.
4. **StudyScreen & Flashcards**: Daily goal progress ring, current streak badge, SRS retention breakdown tiles (Mastered / Learning / New), and active review mode.
5. **PracticeScreen**: Pronunciation practice with sentence repetition, recognized speech comparison, and conversational AI roleplay scenarios.
6. **SettingsScreen**: Model configuration, speech dialect selection, on-device speech privacy disclosures, and AES-GCM encrypted backup import/export.

### 4.2 Verified Screenshots
The verified device captures are stored in `docs/screenshots/android/`:
The original Pixel 7 hardware captures were replaced on 30 October 2026 by API 36 emulator captures (1080×2400) taken after the Library and Study dashboard port to Compose:
- `docs/screenshots/android/emulator-api36-transcript.png`
- `docs/screenshots/android/emulator-api36-library.png`
- `docs/screenshots/android/emulator-api36-word-detail.png`
- `docs/screenshots/android/emulator-api36-history.png`
- `docs/screenshots/android/emulator-api36-study.png`
- `docs/screenshots/android/emulator-api36-study-empty.png`
- `docs/screenshots/android/emulator-api36-study-caught-up.png`
- `docs/screenshots/android/emulator-api36-study-progress.png`
- `docs/screenshots/android/emulator-api36-flashcards.png`
- `docs/screenshots/android/emulator-api36-practice.png`
- `docs/screenshots/android/emulator-api36-settings.png`
