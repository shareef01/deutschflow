package com.aus.deutschflow.ui.screens

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aus.deutschflow.MainActivity
import com.aus.deutschflow.R
import com.aus.deutschflow.data.local.AppDatabase
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.service.GroqHelper
import com.aus.deutschflow.service.TTSHelper
import com.aus.deutschflow.service.VocabularyProcessor
import com.aus.deutschflow.ui.theme.DeutschflowTheme
import com.aus.deutschflow.ui.viewmodel.VocabularyViewModel
import com.aus.deutschflow.ui.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The daily notification and the widget open the word they show.
 *
 * They used to open the app and stop there, so "Today's word: Übung" was followed by
 * a hunt through the library for Übung.
 *
 * Hosted in a plain ComponentActivity and read through Compose's own semantics tree,
 * as ErrorBannerAnnouncementTest hosts its content: createComposeRule's runTest cannot
 * find kotlinx-coroutines' platform exception handler on this project's test APK.
 */
@RunWith(AndroidJUnit4::class)
class OpenWordFromOutsideTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private var scenario: ActivityScenario<ComponentActivity>? = null

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        scenario?.close()
        database.close()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun aWordAskedForFromOutsideOpensItsDetail() {
        val dao = database.vocabularyDao()
        val id = runBlocking {
            dao.save(VocabularyEntity(germanText = "Haus", englishTranslation = "house"))
            requireNotNull(dao.findByGermanText("Haus")).id
        }
        val viewModel = VocabularyViewModel(
            vocabularyDao = dao,
            vocabularyProcessor = VocabularyProcessor(GroqHelper(context)),
            ttsHelper = TTSHelper(context),
            widgetUpdater = WidgetUpdater(context)
        )
        val handedBack = AtomicBoolean(false)

        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity { activity ->
            activity.setContent {
                DeutschflowTheme {
                    VocabularyScreen(
                        // Compact: the detail replaces the list, so it is unmistakable.
                        windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp)),
                        openWordId = id,
                        onWordOpened = { handedBack.set(true) },
                        viewModel = viewModel
                    )
                }
            }
        }

        // A heading only the detail view draws.
        val detailOnly = context.getString(R.string.detail_linguistic_connections)
        assertTrue(
            "the requested word's detail never opened",
            awaitText(detailOnly)
        )
        assertTrue("the request should be handed back so it opens only once", handedBack.get())
    }

    @Test
    fun theWordIdIsReadFromTheIntentAndNothingElseCounts() {
        fun intentWith(id: Int?) = Intent().apply { id?.let { putExtra(MainActivity.EXTRA_WORD_ID, it) } }

        assertEquals(7, MainActivity.wordIdFrom(intentWith(7)))
        assertNull("no extra: an ordinary launch", MainActivity.wordIdFrom(intentWith(null)))
        assertNull("Room ids start at 1", MainActivity.wordIdFrom(intentWith(0)))
        assertNull(MainActivity.wordIdFrom(intentWith(-3)))
        assertNull(MainActivity.wordIdFrom(null))
    }

    /** Polls the composition's semantics tree for [text], as the Compose test rule would. */
    private fun awaitText(text: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var found = false
            requireNotNull(scenario).onActivity { activity ->
                found = textsIn(activity.window.decorView).any { it == text }
            }
            if (found) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun textsIn(view: View): List<String> {
        val root = composeRootIn(view) ?: return emptyList()
        val texts = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.children.forEach(::walk)
        }
        walk(root.semanticsOwner.unmergedRootSemanticsNode)
        return texts
    }

    private fun composeRootIn(view: View): ViewRootForTest? = when (view) {
        is ViewRootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeRootIn(view.getChildAt(it)) }
        else -> null
    }
}
