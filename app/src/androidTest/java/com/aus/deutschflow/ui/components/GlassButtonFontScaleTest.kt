package com.aus.deutschflow.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.ui.theme.ActionButtonHeight
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AUDIT_2026-10-12 F-25: GlassButton had a fixed 56dp height, so at a large font
 * scale a label that wrapped - the four Study rating buttons share one row - was
 * cut off. It now grows to fit and keeps 56dp as its minimum.
 *
 * Hosted in a plain ComponentActivity, like ErrorBannerAnnouncementTest: this
 * project's test APK can't use createComposeRule's runTest.
 */
@RunWith(AndroidJUnit4::class)
class GlassButtonFontScaleTest {

    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After
    fun teardown() {
        scenario?.close()
    }

    /** Lays out one narrow GlassButton at [fontScale] and returns its height in dp. */
    private fun measuredHeightDp(fontScale: Float, label: String): Float {
        var heightPx = 0
        var density = 1f
        scenario = ActivityScenario.launch(ComponentActivity::class.java).also {
            it.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        DeutschflowTheme {
                            GlassButton(
                                onClick = {},
                                // About a quarter of a phone's width, as in the Study row.
                                modifier = Modifier
                                    .width(80.dp)
                                    .onGloballyPositioned { coords -> heightPx = coords.size.height }
                            ) {
                                Text(label, textAlign = TextAlign.Center, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return heightPx / density
    }

    @Test
    fun keepsItsUsualHeightAtNormalFontScale() {
        assertEquals(ActionButtonHeight.value, measuredHeightDp(1f, "Gut"), 1f)
    }

    @Test
    fun growsToFitAWrappedLabelAtDoubleFontScale() {
        val height = measuredHeightDp(2f, "Nochmal Nochmal")
        assertTrue(
            "a two-line label at 2x should need more than ${ActionButtonHeight.value}dp, got ${height}dp",
            height > ActionButtonHeight.value + 1f
        )
    }
}
