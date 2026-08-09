package com.aus.deutschflow.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aus.deutschflow.data.local.dao.VocabularyDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyWordNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vocabularyDao: VocabularyDao
) {

    private val CHANNEL_ID = "daily_word_channel"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // minSdk is 31, so channels always exist - no version guard needed.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily German Word",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for your daily German word"
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * @return a message to show the user when nothing could be posted, or null on success.
     */
    suspend fun showNotification(): String? {
        if (!hasNotificationPermission()) {
            return "Notifications are turned off. Enable them for DeutschFlow in system settings."
        }

        val vocab = vocabularyDao.getAllVocabulary().firstOrNull()?.randomOrNull()
            ?: return "Save a word to your library first - there's nothing to send yet."

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Wort des Tages: ${vocab.germanText}")
            .setContentText("Translation: ${vocab.englishTranslation}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        return null
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}
