package io.github.logan0116.bfilter.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.logan0116.bfilter.MainActivity
import io.github.logan0116.bfilter.R

/**
 * 播放期间的前台身份。
 *
 * **它解决的是什么**：屏幕关闭后，一个普通后台进程会被系统与 ROM 收紧网络 ——
 * Wi-Fi 进省电、CPU 浅睡、HyperOS 的后台策略直接断网。播放器进程还活着、还在发请求，
 * 于是报出来的正是 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`
 * （红米 K80 至尊版实测：息屏静置约 1 分钟复现）。把进程抬成前台之后，
 * 系统的 Doze / 后台网络限制才不再适用。
 *
 * **它不做的是什么**：不持有播放器、不参与播放控制。播放器仍留在播放页里，
 * 这样播放页的手势、断点续播、状态显示全都不用动 —— 换来的代价是退出播放页就停，
 * 这恰好符合本 App「播完即停、不连播」的产品设定。
 *
 * 顺带给出通知栏条目，免得前台服务变成一颗用户看不见也关不掉的常驻通知。
 */
class PlaybackKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        ensureChannel()
        // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK 是 API 29 才引入的**编译期常量**（值 2），
        // 会被内联进字节码 —— 在 API 24 的设备上把它交给 ServiceCompat 是安全的，
        // ServiceCompat 只在 API 29+ 才真正转交给系统（lint 的 InlinedApi 是保守提示）。
        @Suppress("InlinedApi")
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(title), type)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 用户从最近任务划掉 App 时，通知不该留成孤儿
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "播放中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "息屏后继续播放时显示"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(title: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title.ifBlank { "正在播放" })
            .setContentText("息屏后继续播放")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "title"

        /**
         * 进入播放页时调用。必须在 App 可见时调用 —— Android 12+ 不允许后台启动前台服务，
         * 在后台调会抛 `ForegroundServiceStartNotAllowedException`。
         */
        fun start(context: Context, title: String) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java)
                .putExtra(EXTRA_TITLE, title)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PlaybackKeepAliveService::class.java)) }
        }
    }
}
