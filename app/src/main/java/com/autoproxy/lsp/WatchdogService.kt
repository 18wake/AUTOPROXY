package com.autoproxy.lsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID = "watchdog"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val labels = mutableMapOf<String, String>()
    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RunLog.init(this)
        createChannel()
        startForegroundCompat(buildNotification("代理监控已就绪"))
        RunLog.i("守护服务已启动（通知刷新）")
        startTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startTick()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTick() {
        if (ticking) return
        ticking = true
        scope.launch {
            while (isActive) {
                tick()
                delay(Consts.TICK_MS)
            }
        }
    }

    private fun tick() {
        updateNotification(CmfaControl.readDaemonState())
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun updateNotification(state: Map<String, String>) {
        val fg = state["fg"].orEmpty()
        val proxy = state["proxy"] == "1"
        val inGrace = state["ingrace"] == "1"
        val rate = state["rate"]?.toIntOrNull() ?: 0
        val remain = state["remain"]?.toIntOrNull() ?: 0
        val text = when {
            fg.isNotEmpty() -> {
                val names = fg.split(" ").filter { it.isNotEmpty() }.joinToString("、") { label(it) }
                "代理运行中 · $names 前台"
            }
            inGrace && rate > 0 -> "后台流量 $rate KB/s · 保持代理"
            inGrace -> "空闲确认中 · ${remain}s 后关闭代理"
            proxy -> "代理运行中"
            else -> "代理监控中"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "代理监控", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("无感代理")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}