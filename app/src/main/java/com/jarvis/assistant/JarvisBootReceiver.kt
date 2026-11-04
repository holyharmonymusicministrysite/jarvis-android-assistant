package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "jarvis_boot"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "JARVIS startup", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reminder to resume JARVIS after your phone restarts"
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 70, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, channelId)
            else android.app.Notification.Builder(context)
        manager.notify(7413, builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS is ready")
            .setContentText("Tap to open JARVIS and resume voice listening.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
