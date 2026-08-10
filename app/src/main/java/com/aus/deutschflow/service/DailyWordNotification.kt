package com.aus.deutschflow.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aus.deutschflow.MainActivity
import com.aus.deutschflow.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyWordNotification @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dailyWord: DailyWord
) {

    private val CHANNEL_ID = "daily_word_channel"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        // minSdk is 31, so channels always exist - no version guard needed.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * @return a message to show the user when nothing could be posted, or null on
     * success. A resource id rather than text: the caller is a ViewModel, which has
     * no business holding prose, and the Settings dialog resolves it.
     *
     * Not annotated @StringRes: a suspend function compiles to one returning Object,
     * and lint rejects the annotation on it.
     */
    suspend fun showNotification(): Int? {
        if (!hasNotificationPermission()) {
            return R.string.message_notifications_off
        }

        // The same word the widget is showing, not an independent random pick.
        val vocab = dailyWord.today() ?: return R.string.message_library_empty

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title, vocab.germanText))
            .setContentText(context.getString(R.string.notification_text, vocab.englishTranslation))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Without a content intent the notification does nothing when tapped,
            // and setAutoCancel has nothing to cancel on.
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
        return null
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
