package com.aus.deutschflow.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aus.deutschflow.TestPreferencesRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The API key is the one secret this app holds, and it belongs to the user rather
 * than to the app. These tests are about where it ends up on disk.
 */
@RunWith(AndroidJUnit4::class)
class ApiKeyStorageTest {

    /**
     * This test's own store. It used to be the app's, so the teardown that clears the
     * key deleted the user's real one off whatever device the suite ran on.
     */
    @get:Rule
    val store = TestPreferencesRule(STORE_NAME)

    private val preferences: PreferenceManager get() = store.preferences

    private fun storeFile(): File = store.file

    @Test
    fun theKeyComesBackOutAsItWentIn() = runBlocking {
        preferences.saveApiKey(SECRET)

        assertEquals(SECRET, preferences.apiKey.first())
    }

    /**
     * The point of the whole exercise. Anyone holding a copy of this file - a rooted
     * device, an adb backup of a debuggable build - must not be holding the key.
     */
    @Test
    fun theKeyNeverAppearsInTheFileOnDisk() = runBlocking {
        preferences.saveApiKey(SECRET)

        val file = storeFile()
        assertTrue("the DataStore file should exist by now", file.exists())

        // ISO-8859-1 maps every byte to a character, so a UTF-8 secret would still
        // be found by a substring search if it were written in the clear.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the API key is stored in the clear", raw.contains(SECRET))
    }

    @Test
    fun anEmptyKeyIsStoredAsAnEmptyKey() = runBlocking {
        preferences.saveApiKey(SECRET)
        preferences.saveApiKey("")

        assertEquals("", preferences.apiKey.first())
    }

    // --- the cipher itself ----------------------------------------------------

    @Test
    fun encryptingTwiceGivesDifferentCiphertext() {
        val cipher = KeystoreCipher()

        val first = cipher.encrypt(SECRET)
        val second = cipher.encrypt(SECRET)

        // A fresh IV per encryption. Equal ciphertexts would mean a reused one, which
        // with GCM leaks the key stream.
        assertNotEquals(first, second)
        assertEquals(SECRET, cipher.decrypt(first!!))
        assertEquals(SECRET, cipher.decrypt(second!!))
    }

    @Test
    fun rubbishDecryptsToNullRatherThanThrowing() {
        val cipher = KeystoreCipher()

        // What a restored backup looks like: ciphertext whose key never came with it.
        assertNull(cipher.decrypt("not base64 at all"))
        assertNull(cipher.decrypt("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo="))
        assertNull(cipher.decrypt(""))
    }

    @Test
    fun successfulLegacyMigrationEncryptsThenRemovesPlaintext() = runBlocking {
        store.dataStore.edit { it[stringPreferencesKey("groq_api_key")] = SECRET }
        val manager = PreferenceManager(store.dataStore, FakeCipher(encrypted = "ciphertext", decrypted = SECRET))

        assertEquals(
            PreferenceManager.ApiKeyMigrationResult.MIGRATED,
            manager.migrateLegacyApiKey()
        )
        assertTrue(manager.apiKeyState.first() is PreferenceManager.ApiKeyState.Available)
        val values = store.dataStore.data.first()
        assertNull(values[stringPreferencesKey("groq_api_key")])
        assertEquals("ciphertext", values[stringPreferencesKey("groq_api_key_encrypted")])
    }

    /**
     * A save that lands while the migration is mid-flight must win.
     *
     * migrateLegacyApiKey reads the plaintext value and then encrypts it, and DataStore
     * forbids suspending inside an edit - so the read and the write are two separate
     * operations and a save from Settings can land between them. The naive version wrote
     * the encrypted *stale* value afterwards, silently replacing the key the user had
     * just typed. The fix re-checks inside the write, which is what this pins.
     *
     * Uses the rule's single store rather than constructing a PreferenceManager: a
     * second DataStore over the same file is refused outright ("multiple DataStores
     * active for the same file"), which is the same constraint production gets from
     * the @Singleton provider - so this test cannot accidentally prove anything about
     * a shape the app never uses.
     */
    @Test
    fun aKeySavedDuringMigrationIsNotOverwrittenByTheStaleValue() = runBlocking {
        // The store must outlive every coroutine this test starts, including on the
        // failure path, hence the explicit join and the runBlocking scope.
        store.dataStore.edit { it[stringPreferencesKey("groq_api_key")] = SECRET }
        val manager = PreferenceManager(store.dataStore, SequencedCipher)

        // The migration reads SECRET, then blocks in the Keystore; the save lands in
        // that window with a different key.
        //
        // join() before the assertion, not just await(): if this method returned while
        // the migration was still touching the store, TestPreferencesRule.after() would
        // cancel its scope and delete the file underneath the in-flight coroutine. The
        // next test method then opens a second DataStore over the same file and the
        // whole run dies with "multiple DataStores active for the same file" - a failure
        // that looks like it belongs to an unrelated test.
        val migration = async { manager.migrateLegacyApiKey() }
        yield()
        manager.saveApiKey(NEWER_SECRET)
        assertEquals(PreferenceManager.ApiKeyMigrationResult.MIGRATED, migration.await())
        migration.join()

        val values = store.dataStore.data.first()
        // The newer key survives. Had the migration written last, this would be
        // "ciphertext-of-stale" and the user would have lost what they just typed.
        assertEquals("ciphertext-of-newer", values[stringPreferencesKey("groq_api_key_encrypted")])
        assertNull(values[stringPreferencesKey("groq_api_key")])
    }

    @Test
    fun failedLegacyMigrationKeepsRecoverablePlaintextAndReportsFailure() = runBlocking {
        store.dataStore.edit { it[stringPreferencesKey("groq_api_key")] = SECRET }
        val manager = PreferenceManager(store.dataStore, FakeCipher(encrypted = null, decrypted = null))

        assertEquals(
            PreferenceManager.ApiKeyMigrationResult.FAILED,
            manager.migrateLegacyApiKey()
        )
        val state = manager.apiKeyState.first()
        assertTrue(state is PreferenceManager.ApiKeyState.LegacyPlaintext)
        assertEquals(SECRET, manager.apiKey.first())
        assertEquals(SECRET, store.dataStore.data.first()[stringPreferencesKey("groq_api_key")])
    }

    @Test
    fun corruptCiphertextIsRepresentedAsUnreadableInsteadOfMissing() = runBlocking {
        store.dataStore.edit {
            it[stringPreferencesKey("groq_api_key_encrypted")] = "corrupt"
        }
        val manager = PreferenceManager(store.dataStore, FakeCipher(encrypted = null, decrypted = null))

        assertEquals(PreferenceManager.ApiKeyState.Unreadable, manager.apiKeyState.first())
        assertEquals("", manager.apiKey.first())
    }

    /**
     * Pins the saveApiKey guard: a cipher whose encrypt throws (rather than returning
     * null) - the failure shape KeystoreCipher's own catch does not cover, plus any IO
     * error from the DataStore write it wraps. The save must report failure and the key
     * saved beforehand must survive unchanged.
     */
    @Test
    fun aFailedSaveKeepsThePreviousUsableCredential() = runBlocking {
        preferences.saveApiKey(SECRET)
        assertEquals(SECRET, preferences.apiKey.first())

        val manager = PreferenceManager(store.dataStore, ThrowingCipher())
        assertFalse(manager.saveApiKey(NEWER_SECRET))

        // The real-ciphertext key saved above is untouched: the throw aborted the
        // write before it could remove or overwrite it.
        assertEquals(SECRET, manager.apiKey.first())
    }

    private class ThrowingCipher : KeystoreCipher() {
        override fun encrypt(plainText: String): String? =
            throw RuntimeException("simulated Keystore/IO failure during save")
    }

    private class FakeCipher(
        private val encrypted: String?,
        private val decrypted: String?
    ) : KeystoreCipher() {
        override fun encrypt(plainText: String): String? = encrypted
        override fun decrypt(stored: String): String? = decrypted
    }

    /** Encrypts by identity of the plaintext, so each key maps to a distinct value. */
    private object SequencedCipher : KeystoreCipher() {
        override fun encrypt(plainText: String): String? =
            if (plainText == SECRET) "ciphertext-of-stale" else "ciphertext-of-newer"

        override fun decrypt(stored: String): String? = SECRET
    }

    private companion object {
        const val SECRET = "gsk_TESTKEY_do_not_ship_9f3a2b7c1d"
        const val NEWER_SECRET = "gsk_TESTKEY_typed_after_upgrade_5e1d"
        const val STORE_NAME = "api-key-storage-test"
    }
}
