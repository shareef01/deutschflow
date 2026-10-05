package com.aus.deutschflow.data.local

import com.aus.deutschflow.data.local.entities.germanKey
import com.aus.deutschflow.data.local.entities.germanMatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Duplicate detection, for a language with umlauts.
 *
 * Uniqueness used to be SQLite's NOCASE collation, which folds ASCII A-Z and
 * nothing else - so "Hund" and "hund" were one word while "Übung" and "übung" were
 * two. Every umlaut-initial German noun escaped deduplication.
 *
 * The table below is asserted identically in web/tests/germanfold.test.ts. The two
 * folds are the same rule written twice, and this is what keeps them honest.
 */
class GermanKeyTest {

    @Test
    fun caseIsFolded_includingUmlauts() {
        assertEquals(germanKey("Hund"), germanKey("hund"))
        assertEquals(germanKey("Übung"), germanKey("übung"))
        assertEquals(germanKey("Öl"), germanKey("öl"))
        assertEquals(germanKey("Ärger"), germanKey("ärger"))
    }

    /**
     * The umlaut transliteration is a spelling rule, so it still merges.
     *
     * "Uebung" is not a different word from "Übung"; it is how you type it on a
     * keyboard with no umlauts. That is the entire justification for folding these, and
     * unlike ß it cannot merge two different words.
     */
    @Test
    fun transliteratedUmlautsAreTheSameWord() {
        assertEquals(germanKey("Übung"), germanKey("Uebung"))
        assertEquals(germanKey("schön"), germanKey("schoen"))
        assertEquals(germanKey("Ärger"), germanKey("Aerger"))
        assertEquals(germanKey("Öl"), germanKey("Oel"))
    }

    /**
     * The regression: ß must not be folded to ss in the identity key.
     *
     * German writes "ss" and "ß" natively and distinguishes them, so folding them
     * together merged words that are not the same word - "Maße" (measurements) into
     * "Masse" (mass), "Buße" (penance) into "Busse" (fine). Uniqueness is enforced
     * on this column, so saving one after the other merged the loser into the winner and
     * deleted it: silent loss of a vocabulary entry and its SRS progress.
     */
    @Test
    fun eszettIsNotFoldedToDoubleS() {
        assertNotEquals(germanKey("Maße"), germanKey("Masse"))
        assertNotEquals(germanKey("Buße"), germanKey("Busse"))
        assertNotEquals(germanKey("Füße"), germanKey("Fusse"))
        // Not merely unequal - the sharp s must survive the fold intact, or the words
        // stay apart by accident rather than by intent.
        assertEquals("maße", germanKey("Maße"))
        assertEquals("buße", germanKey("Buße"))
        // The umlaut rule still applies alongside ß: Füße -> fueße, which is distinct
        // from Fusse -> fusee, so the pair stays apart.
        assertEquals("fueße", germanKey("Füße"))
    }

    /**
     * ß is not an umlaut, so it must not be transliterated into ue/oe/ae either, and
     * case folding still applies around it.
     */
    @Test
    fun eszettIsNotTreatedAsAnUmlautButStillFoldsCase() {
        assertEquals("fuß", germanKey("Fuß"))
        assertNotEquals(germanKey("Füße"), germanKey("Fusse"))
        assertEquals(germanKey("MAßE"), germanKey("Maße"))
    }

    /**
     * The trade-off, stated as a test so it cannot be quietly reverted.
     *
     * "Straße" and "Strasse" really are the same word, and keeping ß means they no
     * longer merge. That is the deliberate cost, and it is the smaller one because the
     * two mistakes are not symmetric: a false merge destroys a row and cannot be undone
     * from inside the app, while a false split leaves a duplicate the user can delete.
     * [germanMatchKey] keeps matching them, so search and speech scoring are unaffected.
     */
    @Test
    fun identityKeepsSharpSAndSseparateWhileMatchingStillFoldsThem() {
        assertNotEquals(germanKey("Straße"), germanKey("Strasse"))
        assertEquals(germanMatchKey("Straße"), germanMatchKey("Strasse"))
        assertEquals("strasse", germanMatchKey("Straße"))
    }

    /**
     * [germanMatchKey] must be a superset of [germanKey]: anything the identity key
     * matches, the match key matches too, so search never loses a word the database
     * deduplicates.
     */
    @Test
    fun matchKeyIsStrictlyLooserThanTheIdentityKey() {
        val words = listOf(
            "Hund", "hund", "Übung", "Uebung", "übung", "Öl", "Oel",
            "Ärger", "Straße", "Strasse", "Maße", "Masse", "Buße",
            "Busse", "schön", "schoen", "schon", "Haus", "  das Haus  "
        )
        for (a in words) {
            for (b in words) {
                if (germanKey(a) == germanKey(b)) {
                    assertEquals(
                        "matchKey must fold everything germanKey folds: $a vs $b",
                        germanMatchKey(a),
                        germanMatchKey(b)
                    )
                }
            }
        }
    }

    /**
     * The property MIGRATION_16_17 depends on.
     *
     * That migration recomputes every key under the stricter fold, which is only safe
     * because the new key is *finer*: two words sharing a new key must already have
     * shared the old one. Re-keying can therefore split groups but can never merge two
     * that were distinct, so it cannot collide against the unique index and needs no
     * merge pass. Exhaustively enumerated over the umlaut/ss/ß alphabet up to
     * length 3 rather than sampled: if germanKey is ever made coarser, this fails and
     * the migration is no longer safe.
     */
    @Test
    fun theIdentityKeyIsNeverCoarserThanTheOldSsFoldingKey() {
        fun oldKey(text: String) = germanKey(text).replace("ß", "ss")

        val alphabet = listOf("a", "A", "ä", "Ä", "ö", "Ö", "ü", "Ü", "ß", " ", "e")
        val words = mutableListOf<String>()
        for (a in alphabet) {
            words += a
            for (b in alphabet) {
                words += "$a$b"
                for (c in alphabet) words += "$a$b$c"
            }
        }

        for (a in words) {
            for (b in words) {
                if (germanKey(a) == germanKey(b)) {
                    assertEquals(
                        "new key must be finer: equal germanKey for '$a' and '$b' " +
                            "must imply equal old key",
                        oldKey(a),
                        oldKey(b)
                    )
                }
            }
        }
    }

    @Test
    fun decomposedUnicodeIsNormalizedToCanonicalNfc() {
        val decomposedUebung = "U\u0308bung"
        assertEquals(germanKey("Übung"), germanKey(decomposedUebung))
        assertEquals("uebung", germanKey(decomposedUebung))
        assertEquals("ae", germanKey("a\u0308"))
        assertEquals("oe", germanKey("o\u0308"))
        assertEquals("ue", germanKey("u\u0308"))
    }

    @Test
    fun genuinelyDifferentWordsStayApart() {
        assertNotEquals(germanKey("Hund"), germanKey("Hand"))
        assertNotEquals(germanKey("schon"), germanKey("schön"))
    }

    /** The shared table. Any change here must change germanfold.test.ts too. */
    @Test
    fun theSharedFixtureMatchesTheWebFold() {
        val table = listOf(
            "Hund" to "hund",
            "Übung" to "uebung",
            "übung" to "uebung",
            "Uebung" to "uebung",
            "Straße" to "straße",
            "Strasse" to "strasse",
            "Öl" to "oel",
            "Ärger" to "aerger",
            "  das Haus  " to "das haus"
        )

        for ((given, expected) in table) {
            assertEquals("germanKey(\"$given\")", expected, germanKey(given))
        }
    }
}
