package com.loki.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech wrapper. Callbacks arrive on a TTS thread. */
class Speaker(ctx: Context) {
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var queued: (() -> Unit)? = null
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) { ready = true; queued?.invoke(); queued = null } else failed = true
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
        })
    }

    fun speak(text: String, onDone: () -> Unit) {
        val action: () -> Unit = {
            val hindi = text.any { it in '\u0900'..'\u097F' }
            val loc = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
            tts.language = if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) loc else Locale.US
            val id = UUID.randomUUID().toString()
            callbacks[id] = onDone
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                callbacks.remove(id); onDone()
            }
        }
        when {
            ready -> action()
            failed -> onDone()
            else -> queued = action
        }
    }

    fun stop() { tts.stop(); callbacks.clear() }
    fun shutdown() { tts.stop(); tts.shutdown() }
}

/** One-shot command recognition using the system SpeechRecognizer. Main thread only. */
class CommandRecognizer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var active = false

    fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen(language: String, onResult: (String?) -> Unit) {
        if (active) return  // never run two sessions at once
        active = true
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r

        fun finish(text: String?) {
            if (!active) return
            main.removeCallbacksAndMessages(null)
            cancel()
            onResult(text)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(error: Int) { finish(null) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(i)
        main.postDelayed({ finish(null) }, 12_000)  // hard timeout
    }

    fun cancel() {
        main.removeCallbacksAndMessages(null)
        rec?.let { it.cancel(); it.destroy() }
        rec = null
        active = false
    }
}
