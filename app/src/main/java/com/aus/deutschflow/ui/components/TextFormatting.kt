package com.aus.deutschflow.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Locale

/** The word separator every count and score splits on, named once. */
val WORD_SPLIT = Regex("\\s+")

/**
 * The count the history rows and the transcript header show under a transcript.
 *
 * [com.aus.deutschflow.ui.viewmodel.PracticeViewModel] scores with a stricter
 * fold - punctuation stripped, umlauts transliterated - because scoring decides
 * right and wrong. Counting is presentation, so this stays a plain whitespace
 * split; the two used to be written out inline, three times, in two spellings.
 */
fun wordCount(text: String): Int =
    if (text.isBlank()) 0 else text.trim().split(WORD_SPLIT).count { it.isNotBlank() }

/** One clip label for every copy action, so paste sheets agree about the source. */
const val TRANSCRIPT_CLIP_LABEL = "German Transcript"

/** The copy action, duplicated verbatim in two screens before it moved here. */
fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(TRANSCRIPT_CLIP_LABEL, text))
}

/**
 * Date formats are built per pattern and remembered - the same instance formats
 * every row, instead of a fresh SimpleDateFormat per composition.
 */
@Composable
fun rememberDateFormat(pattern: String): SimpleDateFormat =
    remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
