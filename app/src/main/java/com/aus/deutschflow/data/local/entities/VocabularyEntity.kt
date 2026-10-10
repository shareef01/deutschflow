package com.aus.deutschflow.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.Normalizer
import java.util.UUID

/**
 * Folds a German word to the form its *spellings* share, for database identity.
 *
 * Unicode NFC, trimmed, locale-invariant lowercase, then the transliteration an
 * English keyboard produces: ue for ü, oe for ö, ae for ä.
 *
 * `lowercase()` with no locale is deliberate: it is locale-invariant in Kotlin, and
 * a default-locale one would map I to a dotless ı under a Turkish locale and stop
 * matching.
 *
 * ## ß is deliberately NOT folded to ss
 *
 * This fold used to end `.replace("ß", "ss")`, and that single line silently merged
 * words that are not the same word:
 *
 * - **Maße** (measurements) vs **Masse** (mass, dough) — both `masse`
 * - **Buße** (penance) vs **Busse** (fine) — both `busse`
 *
 * The umlaut replacements cannot do this kind of damage. "Aerger" is not a German
 * word distinct from "Ärger"; it is merely how someone types it on a keyboard with no
 * umlauts, so ä→ae folds two spellings of one word and nothing else. ß→ss is
 * different in kind: German writes both "ss" and "ß" natively and distinguishes
 * them, so the replacement collapses genuinely different vocabulary.
 *
 * Uniqueness is enforced on this column, so the consequence was not cosmetic. Saving
 * "Maße" after "Masse" found the existing row and merged into it via
 * [VocabularyEntity.mergedWith] - one word's translations, grammar and SRS progress
 * fused into another's, and the loser deleted. That is silent data loss.
 *
 * ## Why the conservative direction, and what it costs
 *
 * A fold that keeps ß cannot merge Straße with Strasse, so those become two rows.
 * That is a real cost, and it is the lesser one, because the two mistakes are not
 * symmetric: a false *merge* destroys a vocabulary entry and cannot be undone from
 * inside the app, while a false *split* leaves the user looking at a near-duplicate
 * they can delete. Nothing that consumed the loose fold reads this key: search and
 * scoring filter on the word as written and fold independently, so tightening
 * [germanKey] changed neither. See [germanMatchKey] for the loose rule itself.
 *
 * Mirrors foldGermanKey in web/src/lib/db/schema.ts.
 */
fun germanKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
    .trim()
    .lowercase()
    .replace("ä", "ae")
    .replace("ö", "oe")
    .replace("ü", "ue")

/**
 * The loose fold, for *matching* rather than for identity.
 *
 * [germanKey] plus ß→ss, so it also matches Strasse to Straße and Masse to Maße.
 * Deliberately never used as a uniqueness key: see [germanKey] for why that would
 * destroy rows. Use it to *find* candidates, then disambiguate on the word as
 * written.
 *
 * No production caller yet. Search filters on `germanText` substrings and speech
 * scoring has its own [PracticeViewModel.foldGerman], so neither needs this today -
 * both are unaffected by the stricter identity key either way, which is why nothing
 * had to change when [germanKey] was tightened. It exists as the named, tested
 * statement of the loose rule, and as somewhere for a future substring or fuzzy
 * search to fold to instead of writing the fold inline a third time.
 *
 * Mirrors germanMatchKey in web/src/lib/db/schema.ts.
 */
fun germanMatchKey(text: String): String = germanKey(text).replace("ß", "ss")

/**
 * Immutable so Compose treats the vocabulary list as skippable when it is handed to a
 * composable unchanged; the DAO only ever swaps whole entities, never mutates one.
 */
@Immutable
@Entity(
    tableName = "vocabulary",
    indices = [
        Index(value = ["timestamp"]),
        // Uniqueness lives on the folded key, not on the word as written - see
        // [germanTextKey]. germanText keeps its NOCASE collation because search and
        // ordering still read it.
        Index(value = ["germanTextKey"], unique = true),
        // Index for the SRS engine: the study screen only wants cards ready for review.
        Index(value = ["nextReview"])
    ]
)
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val germanText: String,
    /**
     * The word folded to the form all its spellings share, and the column uniqueness
     * is enforced on.
     *
     * Uniqueness used to be SQLite's NOCASE collation on [germanText], and NOCASE
          * folds ASCII A-Z and nothing else - so "Hund" and "hund" were one word while
          * "Übung" and "übung" were two, as were "Öl"/"öl" and "Ärger"/"ärger". Every
          * German noun beginning with an umlaut escaped deduplication, which is a poor
          * showing for an app about German. The fold here is the same one Practice has
          * always used for scoring ([PracticeViewModel.foldGerman]), so the app finally
          * answers "are these the same word" one way instead of two - with the one
          * deliberate exception of ß, which [germanKey] explains: Straße folds to
          * Strasse for *matching*, but Maße and Masse stay separate words here, because
          * they are.
     *
     * Derived, never entered. [VocabularyDao.save] recomputes it on every write, so
     * a `copy(germanText = ...)` that forgets to update it cannot store a stale key.
     */
    @ColumnInfo(defaultValue = "''")
    val germanTextKey: String = germanKey(germanText),
    val englishTranslation: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val exampleSentence: String = "",
    @ColumnInfo(defaultValue = "''")
    val article: String = "",
    @ColumnInfo(defaultValue = "''")
    val plural: String = "",
    @ColumnInfo(defaultValue = "''")
    val conjugation: String = "",
    @ColumnInfo(defaultValue = "''")
    val synonyms: String = "",
    @ColumnInfo(defaultValue = "''")
    val antonyms: String = "",

    // Spaced Repetition (SRS) fields — Ebbinghaus Engine
    /** Timestamp when the word is next due for review. 0 means it's a new word. */
    @ColumnInfo(defaultValue = "0")
    val nextReview: Long = 0,
    /** Current interval in days between reviews. */
    @ColumnInfo(defaultValue = "0")
    val interval: Int = 0,
    /** The difficulty multiplier (SuperMemo-2 style). Defaults to 2.5. */
    @ColumnInfo(defaultValue = "2.5")
    val easeFactor: Float = 2.5f,
    /** How many times this word has been successfully reviewed. */
    @ColumnInfo(defaultValue = "0")
    val reviewCount: Int = 0,

    // Stable identity and modification metadata used by backup merging.
    /** UUID that survives export/import without exposing the local Room id. */
    @ColumnInfo(defaultValue = "''")
    val remoteId: String = UUID.randomUUID().toString(),
    /** When this record was last touched, used by deterministic merge rules. */
    @ColumnInfo(defaultValue = "0")
    val lastModifiedAt: Long = System.currentTimeMillis()
) {

    fun mergedWith(incoming: VocabularyEntity, now: Long = System.currentTimeMillis()): VocabularyEntity = copy(
        englishTranslation = incoming.englishTranslation.ifBlank { englishTranslation },
        exampleSentence = incoming.exampleSentence.ifBlank { exampleSentence },
        article = incoming.article.ifBlank { article },
        plural = incoming.plural.ifBlank { plural },
        conjugation = incoming.conjugation.ifBlank { conjugation },
        synonyms = incoming.synonyms.ifBlank { synonyms },
        antonyms = incoming.antonyms.ifBlank { antonyms },
        timestamp = maxOf(timestamp, incoming.timestamp),
        // SRS data is usually kept from the existing row unless the incoming one
        // explicitly has newer progress (which wouldn't happen in the current UI).
        nextReview = nextReview,
        interval = interval,
        easeFactor = easeFactor,
        reviewCount = reviewCount,
        lastModifiedAt = maxOf(lastModifiedAt, incoming.lastModifiedAt, now)
    )
}
