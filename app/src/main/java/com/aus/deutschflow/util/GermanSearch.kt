package com.aus.deutschflow.util

import com.aus.deutschflow.ui.viewmodel.PracticeViewModel
import java.text.Normalizer

/**
 * Search matching for German text, as people actually type it.
 *
 * Three spellings of "Mädchen" reach the search box: the real one, the
 * transliteration ("maedchen", via [PracticeViewModel.foldGerman]), and the keyboard
 * without umlauts ("madchen"). Each is compared against the same form of the text, so
 * any of them finds the word. "ß" becomes "ss" in both folds: nobody types "strase"
 * for "Straße".
 *
 * The library and history searches used a plain case-insensitive contains, so only
 * the first spelling worked. Mirrors web/src/lib/search.ts.
 */
class GermanQuery private constructor(
    private val lower: String,
    private val folded: String,
    private val stripped: String
) {
    fun matchesGerman(text: String): Boolean =
        text.lowercase().contains(lower) ||
            PracticeViewModel.foldGerman(text).contains(folded) ||
            stripUmlauts(text).contains(stripped)

    /** Plain case-insensitive contains, for the English side. */
    fun matchesPlain(text: String): Boolean = text.lowercase().contains(lower)

    companion object {
        /** Null for a blank query, meaning "show everything". Folded once, not per row. */
        fun prepare(query: String): GermanQuery? {
            val raw = query.trim()
            if (raw.isEmpty()) return null
            return GermanQuery(raw.lowercase(), PracticeViewModel.foldGerman(raw), stripUmlauts(raw))
        }

        private val APOSTROPHES = Regex("['’]")

        private fun stripUmlauts(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
            .lowercase()
            .replace("ä", "a")
            .replace("ö", "o")
            .replace("ü", "u")
            .replace("ß", "ss")
            .replace(APOSTROPHES, "")
    }
}
