package com.voiceime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 模型下载通知（通知栏进度条）。
 * Android 13+ 需要 POST_NOTIFICATIONS 权限，未授权时静默跳过（不影响下载本身）。
 */
object DownloadNotifier {
    private const val CHANNEL_ID = "model_download"
    private const val NOTIFICATION_ID = 1001
    private const val TAG = "DownloadNotifier"

    private var active = false

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模型下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "语音模型下载进度" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun notify(context: Context, builder: Notification.Builder) {
        val nm = manager(context) ?: return
        ensureChannel(context, nm)
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }

    /** 下载进行中：total<=0 时为不确定进度（如 HF 多文件） */
    fun progress(context: Context, spec: ModelSpec, downloaded: Long, total: Long, fileDesc: String = "") {
        active = true
        val title = "正在下载 " + spec.label
        val text = if (fileDesc.isNotBlank()) {
            fileDesc
        } else {
            (downloaded / 1048576).toString() + " MB / " + (total / 1048576).toString() + " MB"
        }
        val builder = baseBuilder(context, title, text)
        if (total > 0) {
            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        notify(context, builder)
    }

    fun done(context: Context, spec: ModelSpec) {
        active = false
        notify(
            context,
            baseBuilder(context, "下载完成", spec.label + " 已就绪").setProgress(0, 0, false),
        )
    }

    fun failed(context: Context, spec: ModelSpec, reason: String) {
        active = false
        notify(
            context,
            baseBuilder(context, "下载失败", spec.label + "：" + reason).setProgress(0, 0, false),
        )
    }

    fun cancel(context: Context) {
        if (!active) return
        active = false
        manager(context)?.cancel(NOTIFICATION_ID)
    }

    private fun baseBuilder(context: Context, title: String, text: String): Notification.Builder {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }.apply {
            setSmallIcon(R.drawable.ic_mic)
            setContentTitle(title)
            setContentText(text)
            setContentIntent(pi)
            setAutoCancel(true)
        }
    }
}
