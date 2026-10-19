package com.loki.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Microphone foreground service.
 *
 * Flow: Vosk listens for the wake phrase -> stop Vosk (frees the mic) -> "Yes?" ->
 * SpeechRecognizer captures one command -> CommandRouter -> TTS reply -> resume Vosk.
 * Only one stage owns the microphone at any time.
 */
class LokiService : Service() {

    companion object {
        const val ACTION_START = "com.loki.assistant.START"
        const val ACTION_STOP = "com.loki.assistant.STOP"
        const val ACTION_PTT = "com.loki.assistant.PTT"
        private const val NOTIF_ID = 1

        /** Call only while the app is visible (Android 12+ background-start rule). */
        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, LokiService::class.java).setAction(ACTION_START))

        fun pushToTalk(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, LokiService::class.java).setAction(ACTION_PTT))
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: SettingsStore
    private lateinit var speaker: Speaker
    private lateinit var recognizer: CommandRecognizer
    private lateinit var router: CommandRouter
    private var wake: WakeWordEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeMode = false
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() { tick(); main.postDelayed(this, 60_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        store = SettingsStore(applicationContext)
        speaker = Speaker(applicationContext)
        recognizer = CommandRecognizer(applicationContext)
        router = CommandRouter(applicationContext, CloudAi())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            persistListening(false); shutdown(); return START_NOT_STICKY
        }
        if (!goForeground("Starting…")) return START_NOT_STICKY
        main.removeCallbacks(ticker); main.postDelayed(ticker, 60_000)

        when (intent?.action) {
            ACTION_PTT -> if (!busy) { wake?.stop(); runCommandSession(ptt = true) }
            else -> scope.launch {  // ACTION_START, or a null intent after a system restart
                if (intent == null && !store.current().listeningEnabled) { shutdown(); return@launch }
                wakeMode = true
                persistListening(true)
                startWake()
            }
        }
        return START_STICKY
    }

    // ---------------- wake-word stage ----------------

    private fun startWake() {
        if (!wakeMode || busy) return
        scope.launch {
            val s = store.current()
            if (!batteryOk(s)) { pause("Paused: battery low or not charging"); return@launch }
            updateWakeLock(s)
            LokiState.setPhase(Phase.LOADING); updateNotif("Loading wake-word model…")
            val phrases = s.wakeWords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val e = wake ?: VoskWakeWord(applicationContext).also { wake = it }
            e.start(
                phrases,
                onListening = {
                    main.post {
                        if (wakeMode && !busy) {
                            LokiState.setPhase(Phase.WAKE_LISTENING)
                            updateNotif("Listening for “Hey Loki” — microphone on")
                        }
                    }
                },
                onDetected = { main.post { onWake() } },
                onFail = { m -> main.post { wakeFailed(m) } },
            )
        }
    }

    private fun pause(msg: String) {
        wake?.stop(); releaseLock()
        LokiState.setPhase(Phase.PAUSED); updateNotif(msg)
    }

    private fun wakeFailed(msg: String) {
        LokiState.log("Wake word unavailable: $msg")
        val pi = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        Notifs.tapToOpen(this, 2, "LOKI couldn't listen for “Hey Loki”", "$msg — push-to-talk still works.", pi)
        shutdown()
    }

    private fun onWake() {
        if (busy || !wakeMode) return
        wake?.stop()                       // frees the mic
        runCommandSession(ptt = false)
    }

    // ---------------- command stage ----------------

    private fun runCommandSession(ptt: Boolean) {
        busy = true
        LokiState.setPhase(Phase.COMMAND_LISTENING); updateNotif("Listening for your command — microphone on")
        scope.launch {
            val s = store.current()
            if (!recognizer.isAvailable()) {
                reply("Speech recognition isn't available on this device.", false); return@launch
            }
            if (ptt) listen(s) else speaker.speak("Yes?") { main.post { listen(s) } }
        }
    }

    private fun listen(s: LokiSettings) {
        recognizer.listen(s.language) { text ->
            if (text == null) {
                reply("Sorry, I didn't catch that.", false)
            } else {
                LokiState.log("You: $text")
                LokiState.setPhase(Phase.THINKING); updateNotif("Thinking…")
                scope.launch {
                    val r = router.handle(text, s)
                    LokiState.log("Loki: ${r.text}")
                    reply(r.text, r.stopListening)
                }
            }
        }
    }

    private fun reply(text: String, stopAfter: Boolean) {
        LokiState.setPhase(Phase.SPEAKING); updateNotif("Speaking…")
        speaker.speak(text) { main.post { finishSession(stopAfter) } }
    }

    private fun finishSession(stop: Boolean) {
        busy = false
        when {
            stop -> { persistListening(false); shutdown() }
            wakeMode -> main.postDelayed({ startWake() }, 500)   // let TTS tail die before reopening the mic
            else -> shutdown()
        }
    }

    // ---------------- battery / wake lock ----------------

    private fun tick() {
        scope.launch {
            val s = store.current()
            val ok = batteryOk(s)
            val phase = LokiState.phase.value
            when {
                !wakeMode -> {}
                !ok && phase == Phase.WAKE_LISTENING -> pause("Paused: battery low or not charging")
                ok && phase == Phase.PAUSED -> startWake()
                else -> updateWakeLock(s)
            }
        }
    }

    private fun batteryOk(s: LokiSettings): Boolean {
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return (charging || level > s.minBattery) && (!s.onlyCharging || charging)
    }

    /** Opt-in only; a timeout means it can never leak if the service dies. */
    private fun updateWakeLock(s: LokiSettings) {
        if (!s.wakeLock) { releaseLock(); return }
        val l = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loki:listen")
            .also { it.setReferenceCounted(false); wakeLock = it }
        l.acquire(10 * 60 * 1000L)
    }

    private fun releaseLock() { wakeLock?.let { if (it.isHeld) it.release() } }

    // ---------------- lifecycle / notification ----------------

    private fun goForeground(text: String): Boolean = try {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
        true
    } catch (e: Exception) {   // missing RECORD_AUDIO, or Android 12+ background-start refusal
        LokiState.log("Could not start foreground service: ${e.message}")
        LokiState.setPhase(Phase.ERROR)
        stopSelf()
        false
    }

    private fun buildNotification(text: String): Notification {
        fun svc(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, LokiService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifs.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("LOKI · microphone on")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop listening", svc(1, ACTION_STOP))
            .addAction(0, "Talk", svc(2, ACTION_PTT))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun persistListening(v: Boolean) {
        CoroutineScope(Dispatchers.IO).launch { store.set(Keys.LISTENING, v) }
    }

    private fun shutdown() {
        wakeMode = false; busy = false
        main.removeCallbacksAndMessages(null)
        wake?.release(); wake = null
        recognizer.cancel(); speaker.stop(); releaseLock()
        LokiState.setPhase(Phase.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        recognizer.cancel(); speaker.shutdown()
        wake?.release(); releaseLock()
        LokiState.setPhase(Phase.OFF)
        super.onDestroy()
    }
}
