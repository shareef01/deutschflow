package com.aus.deutschflow.ui.components

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that ErrorBanner exposes correct accessibility semantics to screen readers:
 * - When message is null, no error banner node is rendered in the accessibility tree.
 * - When message appears, an accessibility node carrying the error text is exposed.
 * - The message is inside a polite live region.
 * - When message is cleared, the node disappears from the accessibility tree.
 */
@RunWith(AndroidJUnit4::class)
class ErrorBannerAnnouncementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val message = "Microphone permission is required."
    private val hostLabel = "Error banner test host"

    @After
    fun tearDown() {
        if (this::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun theBannerReachesAScreenReaderWhenItAppears() {
        val state = mutableStateOf<String?>(null)

        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            activity.setContent {
                val msg by state
                DeutschflowTheme {
                    Column {
                        Text(hostLabel)
                        ErrorBanner(msg)
                    }
                }
            }
        }

        fun hasNode(
            node: AccessibilityNodeInfo?,
            predicate: (AccessibilityNodeInfo) -> Boolean
        ): Boolean {
            if (node == null) return false
            return predicate(node) || (0 until node.childCount).any { hasNode(node.getChild(it), predicate) }
        }

        fun containsMessage(root: AccessibilityNodeInfo): Boolean =
            hasNode(root) { it.text?.contains(message) == true }

        val automation = instrumentation.uiAutomation
        fun getHostRoot(): AccessibilityNodeInfo? {
            // Another app can also have an application window, such as in split screen.
            val applicationRoots = automation.windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .map { it.root }
            return (sequenceOf(automation.rootInActiveWindow) + applicationRoots)
                .filterNotNull()
                .firstOrNull { root -> hasNode(root) { it.text?.toString() == hostLabel } }
        }

        // UiAutomation observes Compose animations and accessibility updates asynchronously.
        fun awaitHostTree(condition: (AccessibilityNodeInfo) -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                val root = getHostRoot()
                if (root != null && condition(root)) return true
                Thread.sleep(200L)
            }
            return false
        }

        assertTrue(
            "The test activity's accessibility tree must be readable before showing the banner",
            awaitHostTree { true }
        )
        assertTrue(
            "No error banner node should exist when message is null",
            awaitHostTree { root -> !containsMessage(root) }
        )

        scenario.onActivity { state.value = message }
        assertTrue(
            "The banner message must appear in a polite live region in the test activity",
            awaitHostTree { root ->
                hasNode(root) { node ->
                    node.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE && containsMessage(node)
                }
            }
        )

        scenario.onActivity { state.value = null }
        assertTrue(
            "Error banner node should be removed after dismissal",
            awaitHostTree { root -> !containsMessage(root) }
        )
    }
}
