# DeutschFlow Backup & Recovery Format Specification

This document defines the data structures, cryptographic envelopes, validation rules, collision resolutions, and compatibility guarantees for DeutschFlow learning library backups across Android and Web PWA clients.

---

## 1. Overview & Architectural Principles

DeutschFlow is a local-first German learning application. User data is stored on-device in:
- **Android**: Room SQLite database (`deutschflow-db`) and Jetpack DataStore.
- **Web PWA**: IndexedDB via Dexie (`deutschflow-pwa`).

To respect user privacy, operating-system-level automatic cloud backups are explicitly excluded via `app/src/main/res/xml/data_extraction_rules.xml`. Recovery is user-controlled via manual, portable, and optional password-encrypted backup archives.

### Invariants
1. **Zero Credential Export**: API keys (Groq), Keystore keys, session cookies, and authentication tokens are **never** exported into backup files.
2. **Stable Identity Preservation**: Primary keys (`id`), stable remote UUIDs (`remoteId`), and modification timestamps (`lastModifiedAt`) are preserved or deterministically reconciled.
3. **No Unintended History Loss**: Review events (`reviewEvents`) are preserved even if parent vocabulary entries are deleted.
4. **Collision Safety**: German text matching uses canonical folding (`foldGermanKey` / `germanKey`), preventing duplicate entries while distinguishing linguistic pairs (e.g., *Maße* vs *Masse*).

---

## 2. Format Specifications

DeutschFlow supports two top-level backup envelopes:
1. **Plaintext JSON**: `deutschflow-library` (Versions 1 and 2).
2. **Encrypted Authenticated Envelope**: `deutschflow-library-encrypted` (Version 1).

### 2.1 Plaintext Archive (`deutschflow-library`)

```json
{
  "format": "deutschflow-library",
  "version": 2,
  "exportedAt": "2026-10-17T00:00:00.000Z",
  "vocabulary": [ ... ],
  "transcripts": [ ... ],
  "userStats": [ ... ],
  "activityLog": [ ... ],
  "reviewEvents": [ ... ],
  "roleplayMessages": [ ... ]
}
```

#### Collections Schema:
- **`vocabulary`**:
  - `id`: number (optional, local primary key)
  - `germanText`: string (required, max 200 chars)
  - `germanTextKey`: string (derived fold)
  - `englishTranslation`: string (required, max 2000 chars)
  - `timestamp`: number (epoch ms)
  - `exampleSentence`: string (max 2000 chars)
  - `article`: `"der" | "die" | "das" | "none"`
  - `plural`: string (max 200 chars)
  - `conjugation`: string (max 200 chars)
  - `synonyms`: string
  - `antonyms`: string
  - `nextReview`: number (epoch ms, 0 if due immediately)
  - `interval`: number (days, max 365)
  - `easeFactor`: number (1.3 to 3.0, default 2.5)
  - `reviewCount`: number (non-negative integer)
  - `remoteId`: string (UUID v4)
  - `lastModifiedAt`: number (epoch ms, updated on content edits or imports)
  - `lastStudiedAt`: number (epoch ms, optional, dedicated timestamp of latest study event)

- **`transcripts`**:
  - `id`: number (optional)
  - `fullText`: string (max 10,000 chars)
  - `timestamp`: number (epoch ms)
  - `translation`: string (optional, max 10,000 chars)
  - `analysisJson`: string (optional JSON payload)
  - `remoteId`: string (UUID v4)
  - `lastModifiedAt`: number (epoch ms)

- **`userStats`**:
  - `id`: 1 (singleton row)
  - `xp`: number (non-negative integer)
  - `streak`: number (non-negative integer)
  - `lastActivityTimestamp`: number (epoch ms)

- **`activityLog`**:
  - `date`: string (`YYYY-MM-DD` calendar date)
  - `xpGained`: number (non-negative integer)
  - `timestamp`: number (epoch ms)

- **`reviewEvents`** (Introduced in v2):
  - `id`: number (optional)
  - `vocabularyId`: number | null (foreign key to vocabulary `id`, or `null` for preserved history of deleted or unmapped words)
  - `rating`: `"AGAIN" | "HARD" | "GOOD" | "EASY"`
  - `scheduledDays`: number
  - `actualDays`: number
  - `reviewedAtTimestamp`: number (epoch ms)
  - `isExtraPractice`: boolean
  - `remoteId`: string (UUID v4)

- **`roleplayMessages`** (Introduced in v2):
  - `position`: number (turn sequence index)
  - `scenario`: string
  - `role`: `"user" | "assistant"`
  - `content`: string
  - `translation`: string (optional)
  - `timestamp`: number (epoch ms)

---

### 2.2 Encrypted Authenticated Archive (`deutschflow-library-encrypted`)

Encrypted backups wrap the plaintext JSON representation using standard, authenticated symmetric encryption. Both Web (WebCrypto) and Android (Java Cryptography Architecture) share the exact parameter specification.

```json
{
  "format": "deutschflow-library-encrypted",
  "version": 1,
  "kdf": "PBKDF2-HMAC-SHA256",
  "iterations": 210000,
  "salt": "<base64-encoded 16 bytes>",
  "iv": "<base64-encoded 12 bytes>",
  "ciphertext": "<base64-encoded AES-256-GCM ciphertext + 128-bit auth tag>"
}
```

#### Cryptographic Parameters:
| Parameter | Value | Rationale |
| :--- | :--- | :--- |
| **Cipher** | `AES-256-GCM` | Authenticated encryption prevents tampering and truncation attacks. |
| **Key Derivation (KDF)** | `PBKDF2-HMAC-SHA256` | Universally supported across Android JCA and WebCrypto. |
| **KDF Iterations** | `210,000` | Strengthened OWASP work factor for PBKDF2-HMAC-SHA256. Decryption range `50,000` to `1,000,000` preserves full backward compatibility with existing `100,000`-iteration backups. |
| **Salt Length** | 16 bytes (128 bits) | Cryptographically secure random bytes generated per export. |
| **IV / Nonce Length** | 12 bytes (96 bits) | Standard GCM nonce length; fresh random nonce generated per export. |
| **Authentication Tag** | 128 bits (16 bytes) | Standard full-strength GCM tag appended to ciphertext. |

---

## 3. Data Integrity & Collision Resolution

When importing a backup into an existing library, records are merged transactionally:

### 3.1 Vocabulary Merging & Deletion Isolation
1. **Canonical Key Match**: Look up existing card by `germanTextKey = foldGermanKey(incoming.germanText)`.
2. **Same Word Edit Collision**: If editing an existing word to collide with another word, all associated `reviewEvents` pointing to the departing key are preserved and merged onto the surviving schedule. The departing card's actual review event recency and study timestamp are factored into `withFurthestScheduleOf` so newer lapses are never erased.
3. **Undo Review Event Isolation**: When a deleted card is restored, an explicit deletion history map (`deletion_history:${remoteId}` in `db.settings`) tracks which review events originally belonged to it. If its former numeric primary key has since been assigned to an unrelated card, a new primary key is allocated and only the card's original review events are reassociated, preventing review-event theft.
4. **Pedagogical SRS Progress Retention (`withFurthestScheduleOf`)**:
   - **Unreviewed vs Studied**: If one card has been studied (`reviewCount > 0` or `interval > 0`) and the other is unreviewed (`reviewCount == 0 && interval == 0`), the studied card schedule is preserved. Content edits on unreviewed cards never erase accumulated study progress.
   - **Recency of Study (Anti-Lapse-Erasure)**: Learning event recency is determined strictly by dedicated `lastStudiedAt` or maximum associated review-event timestamps, **decoupled from `lastModifiedAt`**. Because `lastModifiedAt` mutates during typographical fixes, translations, or imports, it cannot establish learning sequence. A recent failure (`interval = 0`, card lapsed) is never erased by an older high interval, even if the mature card underwent later typographical edits.
   - **Safety in Favor of Recall (Tied Timestamps)**: If study timestamps are tied or unavailable:
     * A card that is due or lapsed (`nextReview <= now || interval == 0`) takes precedence over a card scheduled for the future to prevent burying urgent reviews.
     * If both cards are scheduled for the future, the sooner due date (`smaller nextReview`) wins to ensure earlier reinforcement.
     * If both are due or intervals tie, the conservative ease factor (`smaller easeFactor`) and review count are preserved.
5. **Metadata Merging**: Non-empty fields (example sentences, article, plural, synonyms) from the newer `lastModifiedAt` record are preserved.
6. **Batch Deduplication Validation**: Backups undergo pre-import rejection if they contain duplicate canonical vocabulary keys (`germanTextKey`), duplicate transcript remote IDs, duplicate transcript content (`${fullText}:${ts}`), or duplicate review event remote IDs. Any duplicate triggers an immediate validation failure and full transactional rollback.

### 3.2 Review Event Integrity & Elimination of Fake ID 0
- Review event records retain their stable `remoteId`. Duplicate imports do not insert redundant events.
- **Elimination of Fake ID 0**:
  - Web: Review events support validated nullable references (`vocabularyId: number | null`). Unlinked review events explicitly store `null`. Version 10 schema migration migrates legacy `0` records to `null`.
  - Android: Review events referencing unmapped or deleted vocabulary are safely linked to an explicit, isolated archived entity (`[Gelöscht]`, key `__archived_deleted__`).
  - No valid review events are discarded, and no misleading foreign-key references are created.

### 3.3 Activity Log & Streak Calculation
- Activity entries for each `YYYY-MM-DD` date take `Math.max(existing.xpGained, incoming.xpGained)`.
- Streaks are re-calculated deterministically from contiguous active calendar dates (`xpGained > 0`).

---

## 4. Size Limits & Capacity Bounds (Documented Capacity Improvement)

To protect memory and prevent DoS / zip-bomb vectors while accommodating large libraries (exceeding 20 MiB):
- **Plaintext JSON export**: Maximum `50 MiB` (`52,428,800` bytes).
- **Encrypted Base64 envelope**: Maximum `70 MiB` (`73,400,320` bytes).
- **Documented Capacity Improvement**: The increase from 10 MiB to 50 MiB represents a 5x capacity improvement enabled by memory-efficient batching and array-based chunking. It mitigates previous truncation risks for typical personal libraries while preserving a safety ceiling against memory exhaustion in resource-constrained environments (e.g. mobile browsers and low-memory Android devices).
- **Maximum Salt Size**: `64` bytes.
- **Maximum Ciphertext Base64**: `68,000,000` characters.
- **Iterations Range**: `50,000` to `1,000,000` (default: `210,000`).
- **Bounded Batch Processing**: Both Web (Dexie/IndexedDB) and Android (Room/SQLite) process large imports and exports in bounded chunks of 500 items to avoid V8 heap exhaustion and SQLite cursor window limits.

---

## 5. Compatibility & Versioning Guarantees

- **Backward Compatibility**: Current implementations fully accept `version: 1` archives (upgrading them with default values for new tables) and `version: 2` archives.
- **Forward Compatibility**: Backups with `version > 2` (or encrypted `version > 1`) are rejected with explicit, user-friendly `unsupported_version` messages without modifying local storage.
- **Cross-Platform Interoperability**: Plaintext and encrypted backups adhere to the shared contract fixtures. Both platforms can export and restore each other's data collections, including German special characters, nullable review event references, and study event timestamps.
