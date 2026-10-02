package com.example.nothing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Offline auto-fix: keeps running in the background and frees RAM when the phone starts to lag. */
class AutoFixService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto-fix lag", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Auto-fix lag is on")
            .setContentText("Frees memory when RAM drops below ${LocalOptimizer.AUTO_LOW_RAM_PERCENT}%")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        LocalOptimizer.auto = true
        LocalOptimizer.log("Auto mode ON, on-phone (optimize when RAM < ${LocalOptimizer.AUTO_LOW_RAM_PERCENT}%)")
        scope.launch {
            while (true) {
                try {
                    LocalOptimizer.autoCheck(applicationContext)
                } catch (e: Exception) { // keep the watcher alive no matter what
                    LocalOptimizer.log("Auto check error: ${e.message}")
                }
                delay(30_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        LocalOptimizer.auto = false
        LocalOptimizer.log("Auto mode OFF")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "autofix"

        fun setEnabled(context: Context, on: Boolean) {
            val intent = Intent(context, AutoFixService::class.java)
            if (!on) context.stopService(intent)
            else if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
