package com.aus.deutschflow.service

import com.aus.deutschflow.data.local.dao.TranscriptDao
import com.aus.deutschflow.data.local.dao.VocabularyDao
import com.aus.deutschflow.data.local.PreferenceManager
import com.aus.deutschflow.data.local.entities.VocabularyEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val vocabularyDao: VocabularyDao,
    private val transcriptDao: TranscriptDao,
    private val preferenceManager: PreferenceManager,
    private val cloudService: CloudService
) {

    /**
     * Executes a full synchronization between local and remote stores.
     * 1. Pull changes from cloud since lastSyncTimestamp.
     * 2. Merge them locally (using lastModifiedAt for conflict resolution).
     * 3. Push local changes back to cloud.
     */
    suspend fun performSync(): Boolean {
        if (!cloudService.isAuthenticated.value) return false

        // 1. Pull
        val lastSync = 0L // TODO: get from preferenceManager
        val remoteVocab = cloudService.pullVocabulary(lastSync)
        
        // 2. Local Merge
        remoteVocab.forEach { remoteItem ->
            val localItem = vocabularyDao.findByGermanText(remoteItem.germanText)
            if (localItem == null || remoteItem.lastModifiedAt > localItem.lastModifiedAt) {
                // Remote is newer or brand new
                vocabularyDao.save(remoteItem)
            }
        }

        // 3. Push (Simplistic: push everything for now, in real life use 'isDirty' flag)
        val allLocalVocab = vocabularyDao.getAllVocabulary().first()
        cloudService.pushVocabulary(allLocalVocab)

        return true
    }
}
