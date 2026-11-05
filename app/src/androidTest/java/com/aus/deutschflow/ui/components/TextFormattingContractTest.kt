package com.aus.deutschflow.ui.components

import android.content.ClipDescription
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TextFormattingContractTest {
    private fun localized(tag: String): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return context.createConfigurationContext(config)
    }

    @Test fun reviewCountsUseSingularAndPluralInBothLanguages() {
        val english = localized("en-US").resources
        assertEquals("1 card reviewed", english.getQuantityString(R.plurals.study_reviewed_stat, 1, 1))
        assertEquals("2 cards reviewed", english.getQuantityString(R.plurals.study_reviewed_stat, 2, 2))
        val german = localized("de-DE").resources
        assertEquals("1 Karte wiederholt", german.getQuantityString(R.plurals.study_reviewed_stat, 1, 1))
        assertEquals("2 Karten wiederholt", german.getQuantityString(R.plurals.study_reviewed_stat, 2, 2))
    }

    @Test fun transcriptClipboardMetadataIsSensitiveAndLocalized() {
        for ((tag, label) in listOf("en-US" to "German Transcript", "de-DE" to "Deutsches Transkript")) {
            val clip = transcriptClip(localized(tag), "Synthetic audit fixture")
            assertEquals(label, clip.description.label.toString())
            val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            assertTrue(clip.description.extras?.getBoolean(sensitiveKey) == true)
            assertEquals("Synthetic audit fixture", clip.getItemAt(0).text.toString())
            assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        }
    }
}
