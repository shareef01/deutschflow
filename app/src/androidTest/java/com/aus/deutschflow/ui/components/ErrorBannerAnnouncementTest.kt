package com.aus.deutschflow.ui.components

import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Does the error banner actually reach a screen reader?
 *
 * It was declared a live region twice and stayed silent both times, because
 * "declared" and "announced" are different things and nothing here could tell
 * them apart. This is the instrument that can: UiAutomation registers as an
 * accessibility service, so it receives exactly the events TalkBack receives.
 * If no event carrying the message arrives, no screen reader can speak it,
 * whatever the semantics tree looks like.
 *
 * Deliberately not a Compose UI test. createComposeRule goes through
 * kotlinx-coroutines-test's runTest, which dies in this project with
 * "Exception handler was not found via a ServiceLoader" - the same
 * instrumentation classloader problem Await.kt documents for Dispatchers.setMain.
 * ActivityScenario over the ui-test-manifest's ComponentActivity avoids it
 * entirely, which is why this can run at all.
 */
@RunWith(AndroidJUnit4::class)
class ErrorBannerAnnouncementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val events = CopyOnWriteArrayList<String>()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val message = "Microphone permission is required."

    @Before
    fun listenForAccessibilityEvents() {
        events.clear()
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            // Only the change events a live region produces. The text of the event
            // is what a screen reader would have to work with.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT
            ) {
                event.text.filterNotNull().forEach { events.add(it.toString()) }
                event.contentDescription?.let { events.add(it.toString()) }
            }
        }
    }

    @After
    fun stopListening() {
        instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        if (this::scenario.isInitialized) scenario.close()
    }

    /**
     * The banner appears after the screen already exists, which is the whole point:
     * a message that was on screen from the start needs no announcing, and a
     * message that arrives is exactly the one a user who cannot see it will miss.
     */
    @Test
    fun theBannerReachesAScreenReaderWhenItAppears() {
        var current by mutableStateOf<String?>(null)

        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                DeutschflowTheme { ErrorBanner(current) }
            }
        }
        // Let the first composition settle, so the banner is genuinely a change
        // rather than part of the initial tree.
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        events.clear()

        scenario.onActivity { current = message }
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MS)

        val announced = events.any { it.contains("Microphone permission") }
        assertTrue(
            "no accessibility event carried the banner text, so no screen reader " +
                "could announce it. Events seen: $events",
            announced
        )
    }

    private companion object {
        /** Long enough for composition, the semantics diff and event dispatch. */
        const val SETTLE_MS = 1_500L
    }
}
