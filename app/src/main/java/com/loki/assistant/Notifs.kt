package com.loki.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

object Notifs {
    const val CH_LISTEN = "loki_listening"
    const val CH_ACTION = "loki_actions"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "LOKI listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while LOKI's microphone is active"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTION, "LOKI actions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tap-to-open prompts and alerts"
            }
        )
    }

    fun tapToOpen(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent) {
        val n = NotificationCompat.Builder(ctx, CH_ACTION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
