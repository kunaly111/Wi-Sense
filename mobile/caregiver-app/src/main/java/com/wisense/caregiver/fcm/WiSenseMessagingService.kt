package com.wisense.caregiver.fcm

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wisense.caregiver.MainActivity
import com.wisense.caregiver.R
import com.wisense.caregiver.data.registerFcmToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Data messages only (never plain notification messages) so we control the
 * full-screen incoming-call-style presentation ourselves, matching doc §10 —
 * a plain notification message would be auto-displayed by the OS with no
 * way to make it full-screen or route the tap into the live stream.
 */
class WiSenseMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registerFcmToken() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "emergency_active" -> showFullScreenAlert()
            "emergency_resolved" -> clearAlert()
        }
    }

    private fun showFullScreenAlert() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Fall detected")
            .setContentText("Tap to view live video")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(openApp, true)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun clearAlert() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ALERT_NOTIFICATION_ID)
    }

    companion object {
        const val ALERT_CHANNEL_ID = "wisense_alert"
        private const val ALERT_NOTIFICATION_ID = 2001
    }
}
