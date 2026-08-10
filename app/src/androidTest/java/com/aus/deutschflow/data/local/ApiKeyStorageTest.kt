package com.aus.deutschflow.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The API key is the one secret this app holds, and it belongs to the user rather
 * than to the app. These tests are about where it ends up on disk.
 */
@RunWith(AndroidJUnit4::class)
class ApiKeyStorageTest {

    private lateinit var context: Context
    private lateinit var preferences: PreferenceManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager(context, KeystoreCipher())
    }

    @After
    fun teardown() = runBlocking {
        // The DataStore is the real one, shared with the app.
        preferences.saveApiKey("")
    }

    private fun storeFile() = File(context.filesDir, "datastore/settings.preferences_pb")

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

    private companion object {
        const val SECRET = "gsk_TESTKEY_do_not_ship_9f3a2b7c1d"
    }
}
