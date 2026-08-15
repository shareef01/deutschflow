package com.aus.deutschflow.service

/**
 * Open, and its two calls to the model with it, so a test can substitute a processor
 * whose answers arrive when the test says rather than when a server does.
 *
 * [TranscriptViewModel.interrogateWord] cancels an interrogation when a newer one
 * starts, and that ordering cannot be exercised against the real client: with no API
 * key it fails before it ever suspends, and with one it answers on the network's
 * schedule. Neither lets a test hold two requests open and choose which returns first,
 * which is the whole of what the cancellation is there to get right.
 */
open class VocabularyProcessor(
    private val languageModel: GroqHelper
) {

    /**
     * Translates a German utterance and extracts key words from it.
     */
    open suspend fun processText(text: String, apiKey: String): AIResult =
        languageModel.translateAndExtract(text, apiKey)

    /** Fetches the full linguistic anatomy of a single word. */
    open suspend fun interrogateWord(word: String, apiKey: String): WordDetailsResult =
        languageModel.interrogateWord(word, apiKey)

    /** @see Companion.generateExample */
    fun generateExample(word: String): String = Companion.generateExample(word)

    companion object {

        /**
         * A conversation example for a word, built from templates.
         *
         * The fallback for words typed in by hand, which never went near the model
         * and so carry no example of their own.
         *
         * German in every locale, and deliberately so: it is the material being
         * learned, not interface text. Translating it would defeat the point.
         *
         * On the companion so it can be tested without constructing a GroqHelper,
         * which needs a Context.
         */
        fun generateExample(word: String): String {
            val templates = listOf(
                "Kannst du mir helfen, das Wort '$word' zu verstehen?",
                "Ich möchte mehr über '$word' lernen.",
                "Wie sagt man '$word' auf Englisch?",
                "Heute habe ich das Wort '$word' im Unterricht gelernt.",
                "Kannst du '$word' in einem Satz verwenden?",
                "Das Wort '$word' ist sehr wichtig für mich.",
                "Ich übe gerade die Aussprache von '$word'.",
                "Warum benutzt du so oft das Wort '$word'?",
                "Es ist nicht einfach, '$word' richtig zu benutzen.",
                "Gestern habe ich '$word' in einem Buch gelesen."
            )

            return when (word.lowercase()) {
                "hallo" -> "Hallo, wie geht es dir?"
                "deutsch" -> "Ich lerne jeden Tag Deutsch."
                "lernen" -> "Wir lernen zusammen in der Schule."
                "sprechen" -> "Kannst du bitte langsamer sprechen?"
                else -> templates.random()
            }
        }
    }
}
