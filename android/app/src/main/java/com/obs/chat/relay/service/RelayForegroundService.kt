package com.obs.chat.relay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.obs.chat.relay.MainActivity
import com.obs.chat.relay.OBSChatRelayApp
import com.obs.chat.relay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RelayForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var keepAliveJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBSChatRelay::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (e: Exception) {}

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OBSChatRelay::WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {}

        OBSChatRelayApp.instance.controller.setBackgroundServiceRunning(true)

        // Keep WebViews and sockets active in background
        keepAliveJob = serviceScope.launch {
            while (isActive) {
                try {
                    OBSChatRelayApp.instance.controller.resumeAllWebViews()
                } catch (e: Exception) {}
                delay(8000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            OBSChatRelayApp.instance.controller.stopRelayService()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val controller = OBSChatRelayApp.instance.controller
        if (!controller.isServerRunning.value) {
            controller.startRelayService()
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val controller = OBSChatRelayApp.instance.controller
        val ip = controller.localIp.value
        val port = controller.prefs.httpPort

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RelayForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stream = controller.streamState.value
        val subText = if (stream.liveState == "live") {
            "🔴 Live: ${stream.title ?: "Active"} (${stream.viewers} viewers)"
        } else {
            "Background Relay Active • Dock: http://$ip:$port/chatdock.html"
        }

        return NotificationCompat.Builder(this, OBSChatRelayApp.CHANNEL_ID)
            .setContentTitle("OBS Chat Relay Active")
            .setContentText(subText)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        keepAliveJob?.cancel()
        OBSChatRelayApp.instance.controller.setBackgroundServiceRunning(false)
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
    }
}
