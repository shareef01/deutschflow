package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.entities.VocabularyEntity
import com.aus.deutschflow.data.local.entities.TranscriptEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockCloudService @Inject constructor() : CloudService {

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override suspend fun pushVocabulary(list: List<VocabularyEntity>): Boolean = true
    override suspend fun pushTranscripts(list: List<TranscriptEntity>): Boolean = true

    override suspend fun pullVocabulary(since: Long): List<VocabularyEntity> = emptyList()
    override suspend fun pullTranscripts(since: Long): List<TranscriptEntity> = emptyList()

    override suspend fun signIn(email: String, password: String): Boolean {
        _isAuthenticated.value = true
        return true
    }

    override fun signOut() {
        _isAuthenticated.value = false
    }
}
