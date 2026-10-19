package com.loki.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android 14+ forbids starting a microphone foreground service from BOOT_COMPLETED,
 * so after a reboot we only post a "tap to resume" notification.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SettingsStore(ctx.applicationContext).current().listeningEnabled) {
                    Notifs.ensureChannels(ctx)
                    val pi = PendingIntent.getActivity(
                        ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    Notifs.tapToOpen(ctx, 3, "LOKI is off after restart", "Tap to resume “Hey Loki” listening", pi)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
