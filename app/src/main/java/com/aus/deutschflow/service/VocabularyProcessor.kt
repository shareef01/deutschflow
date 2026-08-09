package com.aus.deutschflow.service

class VocabularyProcessor(
    private val geminiHelper: GeminiHelper = GeminiHelper()
) {

    /**
     * Translates a German utterance and extracts key words from it using Gemini.
     */
    suspend fun processText(text: String, apiKey: String): AIResult =
        geminiHelper.translateAndExtract(text, apiKey)

    /**
     * Generates a natural conversation example for a given word using templates.
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
