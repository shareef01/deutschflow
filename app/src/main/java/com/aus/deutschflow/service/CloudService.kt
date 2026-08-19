package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the cloud backend (e.g. Supabase, Firebase).
 */
interface CloudService {

    /**
     * Pushes local changes to the cloud.
     */
    suspend fun pushVocabulary(list: List<VocabularyEntity>): Boolean
    suspend fun pushTranscripts(list: List<TranscriptEntity>): Boolean

    /**
     * Pulls remote changes since the last sync.
     */
    suspend fun pullVocabulary(since: Long): List<VocabularyEntity>
    suspend fun pullTranscripts(since: Long): List<TranscriptEntity>

    /**
     * Account management.
     *
     * [isAuthenticated] is a flow rather than a getter so callers can observe it.
     * Settings used to poll the getter every two seconds - on both platforms - for a
     * value that only ever changes in [signIn] and [signOut].
     */
    suspend fun signIn(email: String, password: String): Boolean
    fun signOut()
    val isAuthenticated: StateFlow<Boolean>
}
