package com.aus.deutschflow.ui.components

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that ErrorBanner exposes correct accessibility semantics to screen readers:
 * - When message is null, no error banner node is rendered in the accessibility tree.
 * - When message appears, an accessibility node carrying the error text is exposed.
 * - The node declares a polite live region (liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE)
 *   so screen readers announce it when it appears.
 * - When message is cleared, the node disappears from the accessibility tree.
 */
@RunWith(AndroidJUnit4::class)
class ErrorBannerAnnouncementTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    private val message = "Microphone permission is required."

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
                DeutschflowTheme { ErrorBanner(msg) }
            }
        }
        instrumentation.waitForIdleSync()

        fun getAppRoot(): AccessibilityNodeInfo? {
            val appWindow = instrumentation.uiAutomation.windows
                .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            return appWindow?.root ?: instrumentation.uiAutomation.rootInActiveWindow
        }

        fun findNodes(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            val list = mutableListOf<AccessibilityNodeInfo>()
            if (predicate(node)) list.add(node)
            for (i in 0 until node.childCount) {
                list.addAll(findNodes(node.getChild(i), predicate))
            }
            return list
        }

        // 1. When message is null, no accessibility node carrying the error message exists
        val initialRoot = getAppRoot()
        val initialTextNodes = findNodes(initialRoot) { it.text?.contains(message) == true }
        assertTrue("No error banner node should exist when message is null", initialTextNodes.isEmpty())

        // 2. When message appears, the accessibility tree exposes the message and marks it polite
        scenario.onActivity { state.value = message }
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000L) // allow AnimatedVisibility enter animation to settle

        val activeRoot = getAppRoot()
        assertNotNull("Root accessibility node should not be null", activeRoot)

        val textNodes = findNodes(activeRoot) { it.text?.contains(message) == true }
        assertTrue("Accessibility node with banner message must exist", textNodes.isNotEmpty())

        val liveNodes = findNodes(activeRoot) { it.liveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }
        assertTrue("Accessibility node with polite live region must exist", liveNodes.isNotEmpty())

        // 3. When message is dismissed, the error node exits
        scenario.onActivity { state.value = null }
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000L) // allow exit animation to settle

        val finalRoot = getAppRoot()
        val finalTextNodes = findNodes(finalRoot) { it.text?.contains(message) == true }
        assertTrue("Error banner node should be removed after dismissal", finalTextNodes.isEmpty())
    }
}
