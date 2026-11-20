package com.obs.chat.relay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.obs.chat.relay.controller.RelayController

class OBSChatRelayApp : Application() {

    lateinit var controller: RelayController
        private set

    companion object {
        lateinit var instance: OBSChatRelayApp
            private set
        const val CHANNEL_ID = "obs_chat_relay_channel"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        controller = RelayController(this)

        createNotificationChannel()

        if (controller.prefs.autoStartMonitoring) {
            if (controller.prefs.backgroundModeEnabled) {
                com.obs.chat.relay.service.RelayForegroundService.start(this)
            } else {
                controller.startRelayService()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OBS Chat Relay Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps OBS chat relay, live chat capture, and AI active even on lockscreen"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
