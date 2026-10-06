package com.aus.deutschflow

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.aus.deutschflow.data.local.KeystoreCipher
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.util.cancelAndDrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File

private const val TAG = "TestPreferencesRule"

/**
 * A settings store belonging to one test, not to the user.
 *
 * Every test that needed a [PreferenceManager] used to build one over the app's own
 * DataStore, and two of them cleared the API key on the way in or out. That deleted a
 * real key from whatever device the suite ran on, and left [GroqLiveTest] - the only
 * test that talks to Groq - with nothing to authenticate with, so it skipped.
 *
 * A rule rather than a helper function, because DataStore permits one active instance
 * per file per process and only forgets an instance when its scope is cancelled.
 * `@Before` runs once per test *method*, so a plain factory call opened a second store
 * over the same file on the second test in a class and crashed the whole run. The
 * scope here is cancelled after each method, which is the release the error message
 * asks for.
 *
 * [name] must still be unique per test class, so two classes never overlap.
 */
class TestPreferencesRule(private val name: String) : ExternalResource() {

    /** The file backing this test's store, for assertions about what reaches disk. */
    lateinit var file: File
        private set

    lateinit var dataStore: DataStore<Preferences>
        private set

    lateinit var preferences: PreferenceManager
        private set

    private lateinit var scope: CoroutineScope

    override fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = context.preferencesDataStoreFile(name)
        // A store left behind by a previous run would carry its state into this one.
        file.delete()

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        preferences = PreferenceManager(dataStore, KeystoreCipher())
    }

    override fun after() {
        // DataStore allows one active instance per file per process, and only forgets
        // an instance once its scope has finished draining. Teardown must therefore
        // cancel AND wait for completion - not just cancel and assume - so the next
        // test's before() cannot see this store as still active and crash with
        // "multiple DataStores active for the same file" (a failure that lands on the
        // wrong test and reads as a flake).
        //
        // cancel+join run in runBlocking: the caller's coroutine, a separate active
        // scope, never this scope's own job (a coroutine cancelled along with its own
        // scope never reaches completion). cancelAndDrain is bounded, and a timeout is
        // a real failure: we must NOT delete the file when the drain timed out, because
        // a child may still be writing to it. Leaving it in place is noisy but safe;
        // deleting it under an in-flight write is what corrupts the next store.
        val drained = runBlocking(Dispatchers.IO) { cancelAndDrain(scope, 5_000) }
        if (drained) {
            file.delete()
        } else {
            Log.e(
                TAG,
                "DataStore scope for $file did not drain within 5s; leaving the file in place to avoid racing an in-flight write",
            )
        }
    }
}
