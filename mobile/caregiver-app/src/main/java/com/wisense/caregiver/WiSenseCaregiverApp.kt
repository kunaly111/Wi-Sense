package com.wisense.caregiver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wisense.caregiver.fcm.WiSenseMessagingService

class WiSenseCaregiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            WiSenseMessagingService.ALERT_CHANNEL_ID,
            "Emergency alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Full-screen alert when a fall is detected"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
