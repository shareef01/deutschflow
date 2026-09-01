/**
 * i18n — the Android string resources (res/values + values-de) as a flat
 * dictionary. Every user-facing string in the app goes through t().
 *
 * The Android app is "du" throughout in German (a learning app — formal "Sie"
 * would sit oddly against flashcards and streaks); that tone is preserved here.
 */

export type Lang = "en" | "de";

export const STRINGS = {
  en: {
    // Navigation
    "nav.transcript": "Transcript",
    "nav.history": "History",
    "nav.library": "Library",
    "nav.study": "Study",
    "nav.practice": "Practice",
    "nav.settings": "Settings",
    "action.skipToContent": "Skip to content",
    "action.back": "Back",

    // Shared actions
    "action.speak": "Speak",
    "action.edit": "Edit",
    "action.delete": "Delete",
    "action.cancel": "Cancel",
    "action.save": "Save",
    "action.copy": "Copy",
    "action.ok": "OK",

    // Transcript
    "speech.unavailableBody": "This browser can't recognise speech. Chrome, Edge and Safari can — or type a sentence below.",
    "transcript.listeningFor": "Listening for {0}",
    "transcript.translateTyped": "Translate",
    "transcript.typeInstead": "Type a German sentence",
    "transcript.listening": "Listening…",
    "transcript.hint": "Tap to start",
    "transcript.transcribing": "Transcribing…",
    "transcript.startRecording": "Start recording",
    "transcript.stopRecording": "Stop recording",
    "transcript.emptyTitle": "Speak German naturally.",
    "transcript.emptyBody": "DeutschFlow transcribes what you say, translates it, and finds the words worth keeping.",
    "transcript.language": "German · {0}",
    "transcript.translation": "Translation",
    "transcript.vocabulary": "Vocabulary",
    "transcript.save": "Save to library",
    "transcript.saved": "Saved to your library.",
    "transcript.placeholder": "Your transcript will appear here.",
    "wordSheet.article": "Article: {0}",
    "wordSheet.plural": "Plural: {0}",
    "wordSheet.verb": "Infinitive: {0}",
    "wordSheet.meaning": "Meaning",

    // History
    "history.searchHint": "Search transcript history…",
    "history.emptyTitle": "No transcripts found",
    "history.emptyBody": "Your transcript sessions will appear here.",
    "history.today": "Today",
    "history.yesterday": "Yesterday",
    "history.deleted": "Transcript deleted.",
    "history.words": "{0} words",
    "action.undo": "Undo",

    // Library
    "library.wordDeleted": "Word deleted.",
    "library.searchHint": "Search words or translations…",
    "library.emptyTitle": "Your library is empty",
    "library.emptyBody": "Your vocabulary will appear here as you learn. Add a word, or save one from a transcript.",
    "library.addWord": "Add a word",
    "library.moreActions": "More actions",
    "library.statWords": "Words",
    "library.statPhrases": "Phrases",
    "library.statExamples": "With example",
    "library.sortNewest": "Newest",
    "library.sortAlphabetical": "A–Z",
    "library.dialogAddTitle": "Add a word",
    "library.dialogAddConfirm": "Add to library",
    "library.dialogEditTitle": "Edit word",
    "library.dialogEditConfirm": "Save changes",
    "library.fieldGerman": "German",
    "library.fieldTranslation": "Translation",

    // Word detail
    "detail.emptyTitle": "Select a word",
    "detail.emptyBody":
      "Choose an item from your library to view detailed information and examples.",
    "detail.context": "Context and usage",
    "detail.example": "Example sentence",
    "detail.back": "Back to library",

    // Study
    "study.extraPractice": "Nothing is due today — this is extra practice, so your schedule won't change.",
    "study.reviewNotSaved": "Couldn't save that review. Try answering the card again.",
    "study.emptyTitle": "Ready to study?",
    "study.emptyBody": "Save some German phrases and we'll build your first study session.",
    "study.session": "Study session",
    "study.remaining": "{0} cards remaining",
    "study.again": "Again",
    "study.hard": "Hard",
    "study.good": "Good",
    "study.easy": "Easy",
    "study.tapToFlip": "Tap to flip",
    "study.showGerman": "Show the German word",
    "study.showTranslation": "Show the translation",
    "study.gotIt": "Got it",
    "study.gotItAction": "Got it!",
    "study.skip": "Skip",
    "study.progress": "Progress: {0} / {1}",

    // Dashboard - the Study tab's first pane. The Android values live in
    // res/values/strings.xml under dashboard_*; keep the two in step.
    "dashboard.dailyGoal": "Daily goal",
    "dashboard.goalAchieved": "Goal achieved!",
    "dashboard.xpRemaining": "{0} XP remaining",
    "dashboard.streak": "🔥 {0} day streak",
    "dashboard.retention": "Vocabulary retention",
    "dashboard.mastered": "Mastered",
    "dashboard.learning": "Learning",
    "dashboard.new": "New",
    "dashboard.heatmap": "Activity heatmap",
    "dashboard.heatmapSub": "The past three months",
    "dashboard.xp": "XP",

    // Cloud sync. Not built yet; the copy says so rather than implying a backup.
    "cloud.header": "Cloud sync",
    "cloud.signedIn": "Signed in",
    "cloud.title": "Cloud sync",
    "cloud.unavailable": "Not available yet — your library is saved in this browser.",
    "cloud.signIn": "Sign in",
    "cloud.signOut": "Sign out",
    "cloud.signInBody": "Accounts are ready for a backend that does not exist yet. Signing in changes nothing here.",
    "cloud.email": "Email",
    "cloud.password": "Password",
    "cloud.syncUnavailable": "Cloud sync isn’t available yet. Your library is saved in this browser.",

    // Practice
    "practice.tab": "Repetition",
    "practice.intro": "Listen and repeat the sentence accurately to master your pronunciation.",
    "practice.listenRepeat": "Listen and repeat",
    "practice.listen": "Listen",
    "practice.speak": "Speak",
    "practice.evaluate": "Evaluate",
    "practice.next": "Next",
    "practice.wordMatch": "Word match: {0}%",
    "practice.wordCorrect": "Correct",
    "practice.wordTryAgain": "Try again",
    "practice.feedbackPerfect": "Excellent! Perfect pronunciation.",
    "practice.feedbackGood": "Good! You got most of it.",
    "practice.feedbackKeepGoing": "Keep practicing! Try to match the highlighted words.",

    // Roleplay
    "roleplay.tab": "Roleplay",
    "roleplay.thinking": "AI is thinking...",
    "roleplay.stopSend": "Stop & Send",
    "roleplay.speakReply": "Speak to Reply",

    // Settings
    "settings.backupHeader": "Backup",
    "settings.backupBody": "Your library lives only in this browser. Clearing site data, switching browser, or the browser reclaiming space will take it. Keep a copy.",
    "settings.backupDownload": "Download a copy",
    "settings.backupRestore": "Restore from a file",
    "settings.backupDownloaded": "Library downloaded.",
    "settings.backupRestored": "Library restored. Existing words were merged, not replaced.",
    "settings.backupFailed": "Couldn't build the backup. Try again.",
    "settings.backupInvalid": "That file isn't a DeutschFlow library export.",
    "settings.speechPrivacy": "Speech is recognised by your browser, which sends the audio to its vendor (Google in Chrome and Edge, Apple in Safari). The Android app recognises speech on the device instead.",
    "settings.aiHeader": "AI & Translation",
    "settings.apiKeyLabel": "Groq API key",
    "settings.apiKeyHint": "Paste your Groq key here",
    "settings.apiKeyReplace": "Enter a new key to replace the saved one",
    "settings.apiKeySavedState": "A key is saved on this device.",
    "settings.apiKeyNone": "No key saved — translation is unavailable.",
    "settings.apiKeyHelp":
      "Required for automatic vocabulary extraction and translations. A key is free at console.groq.com.",
    "settings.showKey": "Show API key",
    "settings.hideKey": "Hide API key",
    "settings.progressHeader": "Learning progress",
    "settings.statVocabulary": "Vocabulary",
    "settings.statSessions": "Sessions",
    "settings.statXp": "XP points",
    "settings.statStreak": "Streak",
    "settings.audioHeader": "Audio",
    "settings.autoplay": "Auto-play German audio",
    "settings.dialectHeader": "Speech recognition",
    "settings.notificationsHeader": "Notifications",
    "settings.dataHeader": "Data",
    "settings.dialectDe": "Germany (de-DE)",
    "settings.dialectAt": "Austria (de-AT)",
    "settings.dialectCh": "Switzerland (de-CH)",
    "settings.clear": "Clear all progress",
    "settings.wipeTitle": "Wipe all progress?",
    "settings.wipeBody":
      "This will permanently delete your library, history, and earnings. This action is final.",
    "settings.wipeConfirm": "Delete everything",
    "settings.wipeCancel": "Keep progress",
    "settings.version": "DeutschFlow v{0}",

    // Settings outcomes
    "message.apiKeySaved": "API key saved.",
    "message.apiKeyNotSaved": "The key couldn't be stored. Try again, or restart the device.",
    "message.progressCleared": "Library, history and stats cleared.",

    // Speech recognition
    "speech.unavailable": "Speech recognition isn't available in this browser.",
    "speech.startFailed": "Couldn't start recording. Try again.",
    "speech.errorAudio": "Microphone unavailable. Close anything else using it and try again.",
    "speech.errorPermission":
      "Microphone access is off. Allow it for DeutschFlow in your browser's site settings.",
    "speech.errorNetwork": "No connection. Speech recognition needs network access.",
    "speech.errorNoMatch": "Didn't catch that. Try speaking again, a little slower.",
    "speech.errorTimeout": "No speech detected.",
    "speech.errorLanguageUnsupported":
      "This browser can't recognise this dialect. Try another one in Settings.",
    "speech.errorGeneric": "Speech recognition failed. Try again.",

    // Text to speech
    "tts.noEngine":
      "No speech engine is set up. Choose one under Text-to-speech output in your browser's or system's settings.",
    "tts.noGerman":
      "German speech isn't installed. Add a German voice under Text-to-speech output in your system's settings.",

    // AI translation
    "db.upgradeBlocked": "DeutschFlow is open in another tab on an older version. Close the other tabs and reload to finish updating.",
    "ai.noKey": "Add your Groq API key in Settings to get translations.",
    "ai.failed": "Translation failed: {0}",
    "ai.noResponse": "no response from Groq",
    "ai.unreadable": "Couldn't read the response. Try again.",
    "ai.storageFailed": "Your data could not be saved or read. Free up space or try again.",
    "ai.keyRejected": "That API key was rejected. Check it in Settings.",
    "ai.rateLimited": "Too many requests for now. Try again in a minute.",
    "ai.status": "The service answered with {0}.",

    // Streak plural
    "streak.days": "{0} days",
    "streak.day": "{0} day",

    // Language selector (web-only)
    "settings.languageHeader": "Language",
    "language.english": "English",
    "language.german": "Deutsch",
  },
  de: {
    // Navigation — "du" throughout, like the Android German resources.
    "nav.transcript": "Transkript",
    "nav.history": "Verlauf",
    "nav.library": "Bibliothek",
    "nav.study": "Lernen",
    "nav.practice": "Üben",
    "nav.settings": "Einstellungen",
    "action.skipToContent": "Zum Inhalt springen",
    "action.back": "Zurück",

    "action.speak": "Vorlesen",
    "action.edit": "Bearbeiten",
    "action.delete": "Löschen",
    "action.cancel": "Abbrechen",
    "action.save": "Speichern",
    "action.copy": "Kopieren",
    "action.ok": "OK",

    "speech.unavailableBody": "Dieser Browser kann keine Sprache erkennen. Chrome, Edge und Safari können es — oder tippe unten einen Satz.",
    "transcript.listeningFor": "Hört auf {0}",
    "transcript.translateTyped": "Übersetzen",
    "transcript.typeInstead": "Deutschen Satz eingeben",
    "transcript.listening": "Hört zu…",
    "transcript.hint": "Zum Starten tippen",
    "transcript.transcribing": "Wird transkribiert…",
    "transcript.startRecording": "Aufnahme starten",
    "transcript.stopRecording": "Aufnahme beenden",
    "transcript.emptyTitle": "Sprich einfach Deutsch.",
    "transcript.emptyBody": "DeutschFlow transkribiert, übersetzt und findet die Wörter, die du behalten willst.",
    "transcript.language": "Deutsch · {0}",
    "transcript.translation": "Übersetzung",
    "transcript.vocabulary": "Wortschatz",
    "transcript.save": "In Bibliothek speichern",
    "transcript.saved": "In deiner Bibliothek gespeichert.",
    "transcript.placeholder": "Deine Mitschrift erscheint hier.",
    "wordSheet.article": "Artikel: {0}",
    "wordSheet.plural": "Plural: {0}",
    "wordSheet.verb": "Infinitiv: {0}",
    "wordSheet.meaning": "Bedeutung",

    "history.searchHint": "Verlauf durchsuchen…",
    "history.emptyTitle": "Keine Transkripte gefunden",
    "history.emptyBody": "Deine Transkript-Sitzungen erscheinen hier.",
    "history.today": "Heute",
    "history.yesterday": "Gestern",
    "history.deleted": "Transkript gelöscht.",
    "history.words": "{0} Wörter",
    "action.undo": "Rückgängig",

    "library.wordDeleted": "Wort gelöscht.",
    "library.searchHint": "Wörter oder Übersetzungen suchen…",
    "library.emptyTitle": "Deine Bibliothek ist leer",
    "library.emptyBody": "Dein Wortschatz erscheint hier, während du lernst. Füge ein Wort hinzu oder speichere eins aus einem Transkript.",
    "library.addWord": "Wort hinzufügen",
    "library.moreActions": "Weitere Aktionen",
    "library.statWords": "Wörter",
    "library.statPhrases": "Sätze",
    "library.statExamples": "Mit Beispielsatz",
    "library.sortNewest": "Neueste",
    "library.sortAlphabetical": "A–Z",
    "library.dialogAddTitle": "Wort hinzufügen",
    "library.dialogAddConfirm": "Zur Bibliothek hinzufügen",
    "library.dialogEditTitle": "Wort bearbeiten",
    "library.dialogEditConfirm": "Änderungen speichern",
    "library.fieldGerman": "Deutsch",
    "library.fieldTranslation": "Übersetzung",

    "detail.emptyTitle": "Wähle ein Wort",
    "detail.emptyBody":
      "Wähle einen Eintrag aus deiner Bibliothek, um Details und Beispiele zu sehen.",
    "detail.context": "Kontext und Verwendung",
    "detail.example": "Beispielsatz",
    "detail.back": "Zurück zur Bibliothek",

    "study.extraPractice": "Heute ist nichts fällig — das ist zusätzliche Übung, dein Plan ändert sich dadurch nicht.",
    "study.reviewNotSaved": "Diese Bewertung konnte nicht gespeichert werden. Beantworte die Karte noch einmal.",
    "study.emptyTitle": "Bereit zum Lernen?",
    "study.emptyBody": "Speichere ein paar deutsche Sätze und wir bauen deine erste Lernsitzung.",
    "study.session": "Lernsitzung",
    "study.remaining": "{0} Karten übrig",
    "study.again": "Nochmal",
    "study.hard": "Schwer",
    "study.good": "Gut",
    "study.easy": "Leicht",
    "study.tapToFlip": "Zum Umdrehen tippen",
    "study.showGerman": "Das deutsche Wort zeigen",
    "study.showTranslation": "Die Übersetzung zeigen",
    "study.gotIt": "Verstanden",
    "study.gotItAction": "Verstanden!",
    "study.skip": "Überspringen",
    "study.progress": "Fortschritt: {0} / {1}",

    "dashboard.dailyGoal": "Tagesziel",
    "dashboard.goalAchieved": "Ziel erreicht!",
    "dashboard.xpRemaining": "{0} XP verbleibend",
    "dashboard.streak": "🔥 {0} Tage Serie",
    "dashboard.retention": "Wortschatz-Gedächtnis",
    "dashboard.mastered": "Meisterhaft",
    "dashboard.learning": "Lernend",
    "dashboard.new": "Neu",
    "dashboard.heatmap": "Aktivitäts-Heatmap",
    "dashboard.heatmapSub": "Die letzten drei Monate",
    "dashboard.xp": "XP",

    "cloud.header": "Cloud-Synchronisierung",
    "cloud.signedIn": "Angemeldet",
    "cloud.title": "Cloud-Synchronisierung",
    "cloud.unavailable": "Noch nicht verfügbar — deine Bibliothek ist in diesem Browser gespeichert.",
    "cloud.signIn": "Anmelden",
    "cloud.signOut": "Abmelden",
    "cloud.signInBody": "Konten sind für ein Backend vorbereitet, das es noch nicht gibt. Das Anmelden ändert hier nichts.",
    "cloud.email": "E-Mail",
    "cloud.password": "Passwort",
    "cloud.syncUnavailable": "Cloud-Synchronisierung ist noch nicht verfügbar. Deine Bibliothek ist in diesem Browser gespeichert.",

    // Practice
    "practice.tab": "Wiederholung",
    "practice.intro":
      "Höre zu und wiederhole den Satz genau, um deine Aussprache zu verbessern.",
    "practice.listenRepeat": "Hören und nachsprechen",
    "practice.listen": "Anhören",
    "practice.speak": "Sprechen",
    "practice.evaluate": "Auswerten",
    "practice.next": "Weiter",
    "practice.wordMatch": "Worttreffer: {0}%",
    "practice.wordCorrect": "Richtig",
    "practice.wordTryAgain": "Noch einmal versuchen",
    "practice.feedbackPerfect": "Ausgezeichnet! Perfekte Aussprache.",
    "practice.feedbackGood": "Gut! Das meiste war richtig.",
    "practice.feedbackKeepGoing":
      "Weiter üben! Versuche, die markierten Wörter zu treffen.",

    // Roleplay
    "roleplay.tab": "Rollenspiel",
    "roleplay.thinking": "Denkt nach...",
    "roleplay.stopSend": "Stopp & senden",
    "roleplay.speakReply": "Tippen, um zu antworten",

    "settings.backupHeader": "Sicherung",
    "settings.backupBody": "Deine Bibliothek liegt nur in diesem Browser. Wenn du die Websitedaten löschst, den Browser wechselst oder der Browser Speicher freigibt, ist sie weg. Bewahre eine Kopie auf.",
    "settings.backupDownload": "Kopie herunterladen",
    "settings.backupRestore": "Aus Datei wiederherstellen",
    "settings.backupDownloaded": "Bibliothek heruntergeladen.",
    "settings.backupRestored": "Bibliothek wiederhergestellt. Vorhandene Wörter wurden zusammengeführt, nicht ersetzt.",
    "settings.backupFailed": "Die Sicherung konnte nicht erstellt werden. Versuche es erneut.",
    "settings.backupInvalid": "Diese Datei ist kein DeutschFlow-Bibliotheksexport.",
    "settings.speechPrivacy": "Die Spracherkennung übernimmt dein Browser und sendet die Aufnahme an dessen Anbieter (Google in Chrome und Edge, Apple in Safari). Die Android-App erkennt Sprache stattdessen auf dem Gerät.",
    "settings.aiHeader": "KI & Übersetzung",
    "settings.apiKeyLabel": "Groq-API-Schlüssel",
    "settings.apiKeyHint": "Füge hier deinen Groq-Schlüssel ein",
    "settings.apiKeyReplace": "Neuen Schlüssel eingeben, um den gespeicherten zu ersetzen",
    "settings.apiKeySavedState": "Ein Schlüssel ist auf diesem Gerät gespeichert.",
    "settings.apiKeyNone": "Kein Schlüssel gespeichert — Übersetzung nicht verfügbar.",
    "settings.apiKeyHelp":
      "Erforderlich für automatische Übersetzungen und Wortschatz-Extraktion. Ein Schlüssel ist auf console.groq.com kostenlos.",
    "settings.showKey": "API-Schlüssel anzeigen",
    "settings.hideKey": "API-Schlüssel verbergen",
    "settings.progressHeader": "Lernfortschritt",
    "settings.statVocabulary": "Wörter",
    "settings.statSessions": "Sitzungen",
    "settings.statXp": "XP-Punkte",
    "settings.statStreak": "Serie",
    "settings.audioHeader": "Audio",
    "settings.autoplay": "Deutsche Aussprache automatisch abspielen",
    "settings.dialectHeader": "Spracherkennung",
    "settings.notificationsHeader": "Benachrichtigungen",
    "settings.dataHeader": "Daten",
    "settings.dialectDe": "Deutschland (de-DE)",
    "settings.dialectAt": "Österreich (de-AT)",
    "settings.dialectCh": "Schweiz (de-CH)",
    "settings.clear": "Fortschritt löschen",
    "settings.wipeTitle": "Gesamten Fortschritt löschen?",
    "settings.wipeBody":
      "Damit werden Bibliothek, Verlauf und Punkte endgültig gelöscht. Das lässt sich nicht rückgängig machen.",
    "settings.wipeConfirm": "Alles löschen",
    "settings.wipeCancel": "Behalten",
    "settings.version": "DeutschFlow v{0}",

    "message.apiKeySaved": "API-Schlüssel gespeichert.",
    "message.apiKeyNotSaved":
      "Der Schlüssel konnte nicht gespeichert werden. Versuche es erneut oder starte das Gerät neu.",
    "message.progressCleared": "Bibliothek, Verlauf und Statistiken gelöscht.",

    "speech.unavailable": "Spracherkennung ist in diesem Browser nicht verfügbar.",
    "speech.startFailed": "Aufnahme konnte nicht gestartet werden. Versuche es erneut.",
    "speech.errorAudio":
      "Mikrofon nicht verfügbar. Schließe andere Programme, die es benutzen, und versuche es erneut.",
    "speech.errorPermission":
      "Der Mikrofonzugriff ist aus. Erlaube ihn für DeutschFlow in den Einstellungen deines Browsers.",
    "speech.errorNetwork": "Keine Verbindung. Die Spracherkennung braucht Netzzugang.",
    "speech.errorNoMatch": "Das habe ich nicht verstanden. Sprich noch einmal, etwas langsamer.",
    "speech.errorTimeout": "Keine Sprache erkannt.",
    "speech.errorLanguageUnsupported":
      "Dieser Browser erkennt diesen Dialekt nicht. Wähle in den Einstellungen einen anderen.",
    "speech.errorGeneric": "Spracherkennung fehlgeschlagen. Versuche es erneut.",

    "tts.noEngine":
      "Es ist keine Sprachausgabe eingerichtet. Wähle eine unter „Text-in-Sprache-Ausgabe“ in den Browser- oder Systemeinstellungen.",
    "tts.noGerman":
      "Deutsche Sprachausgabe ist nicht installiert. Füge eine deutsche Stimme unter „Text-in-Sprache-Ausgabe“ in den Systemeinstellungen hinzu.",

    "db.upgradeBlocked": "DeutschFlow ist in einem anderen Tab in einer älteren Version geöffnet. Schließe die anderen Tabs und lade neu, um das Update abzuschließen.",
    "ai.noKey": "Trage deinen Groq-API-Schlüssel in den Einstellungen ein, um Übersetzungen zu erhalten.",
    "ai.failed": "Übersetzung fehlgeschlagen: {0}",
    "ai.noResponse": "keine Antwort von Groq",
    "ai.unreadable": "Die Antwort war nicht lesbar. Versuche es erneut.",
    "ai.storageFailed": "Deine Daten konnten nicht gespeichert oder gelesen werden. Gib Speicherplatz frei oder versuche es erneut.",
    "ai.keyRejected": "Dieser API-Schlüssel wurde abgelehnt. Prüfe ihn in den Einstellungen.",
    "ai.rateLimited": "Zu viele Anfragen im Moment. Versuche es in einer Minute erneut.",
    "ai.status": "Der Dienst antwortete mit {0}.",

    "streak.days": "{0} Tage",
    "streak.day": "{0} Tag",

    "settings.languageHeader": "Sprache",
    "language.english": "English",
    "language.german": "Deutsch",
  },
} as const;

export type TKey = keyof (typeof STRINGS)["en"];

/** The shape of the reactive t from useI18n, for threading into components. */
export type TFunction = (key: TKey, params?: (string | number)[]) => string;

/** The language the app's non-React modules read at message-generation time. */
let currentLang: Lang = "en";
const langListeners = new Set<() => void>();

export function getCurrentLang(): Lang {
  return currentLang;
}

export function setCurrentLang(lang: Lang): void {
  if (currentLang === lang) return;
  currentLang = lang;
  for (const listener of langListeners) listener();
}

/** useSyncExternalStore surface — every useI18n instance sees the same value. */
export function subscribeLang(listener: () => void): () => void {
  langListeners.add(listener);
  return () => langListeners.delete(listener);
}

export function getLangSnapshot(): Lang {
  return currentLang;
}

/** Resolve the browser's preferred language: German UI when the browser is German. */
export function detectBrowserLang(): Lang {
  if (typeof navigator === "undefined") return "en";
  const preferred = navigator.language?.toLowerCase() ?? "";
  return preferred.startsWith("de") ? "de" : "en";
}

/**
 * Translate a key with {0}/{1} positional params, mirroring %1$s in the
 * Android resources. Pure — takes the language explicitly, so it is testable
 * and usable from React without reading the module's current-language state.
 */
export function translate(lang: Lang, key: TKey, params?: (string | number)[]): string {
  const template = STRINGS[lang][key] ?? STRINGS.en[key] ?? key;
  return template.replace(/\{(\d)\}/g, (_, index: string) => {
    const value = params?.[Number(index)];
    return value !== undefined ? String(value) : "";
  });
}

/**
 * Translate using the module's current language — for non-React modules
 * (recognizer, tts, groq) that generate messages at call time.
 */
export function t(key: TKey, params?: (string | number)[]): string {
  return translate(getCurrentLang(), key, params);
}
