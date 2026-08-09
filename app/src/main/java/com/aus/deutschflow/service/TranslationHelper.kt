package com.aus.deutschflow.service

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class TranslationHelper {

    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.GERMAN)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()

    private val germanEnglishTranslator = Translation.getClient(options)

    suspend fun translate(text: String): String {
        return try {
            val conditions = DownloadConditions.Builder()
                .requireWifi()
                .build()
            
            germanEnglishTranslator.downloadModelIfNeeded(conditions).await()
            germanEnglishTranslator.translate(text).await()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun close() {
        germanEnglishTranslator.close()
    }
}
