package com.loki.assistant

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Anything that can listen for the wake phrase and release the microphone on stop(). */
interface WakeWordEngine {
    fun start(phrases: List<String>, onListening: () -> Unit, onDetected: () -> Unit, onFail: (String) -> Unit)
    /** Must release the microphone so SpeechRecognizer can use it. */
    fun stop()
    fun release()
}

/**
 * Offline keyword spotting with Vosk using a restricted grammar
 * (only the wake phrases plus [unk]). Runs entirely on-device.
 */
class VoskWakeWord(private val ctx: Context) : WakeWordEngine {
    private val lock = Any()
    private var model: Model? = null
    private var service: SpeechService? = null
    private var generation = 0

    override fun start(
        phrases: List<String>,
        onListening: () -> Unit,
        onDetected: () -> Unit,
        onFail: (String) -> Unit,
    ) {
        val gen = synchronized(lock) { ++generation }
        Thread {
            try {
                val m = synchronized(lock) { model } ?: Model(ModelInstaller.ensure(ctx)).also {
                    synchronized(lock) { model = it }
                }
                val grammar = JSONArray(phrases + "[unk]").toString()
                val svc = SpeechService(Recognizer(m, 16000f, grammar), 16000f)
                synchronized(lock) {
                    if (gen != generation) { svc.shutdown(); return@Thread }
                    service = svc
                }
                var fired = false
                svc.startListening(object : RecognitionListener {
                    private fun check(json: String, key: String) {
                        if (fired) return
                        val text = JSONObject(json).optString(key)
                        if (phrases.any { text.contains(it) }) { fired = true; onDetected() }
                    }
                    override fun onPartialResult(hypothesis: String?) { hypothesis?.let { check(it, "partial") } }
                    override fun onResult(hypothesis: String?) { hypothesis?.let { check(it, "text") } }
                    override fun onFinalResult(hypothesis: String?) { hypothesis?.let { check(it, "text") } }
                    override fun onError(exception: Exception?) { onFail(exception?.message ?: "recognizer error") }
                    override fun onTimeout() {}
                })
                onListening()
            } catch (t: Throwable) {
                onFail(t.message ?: t.javaClass.simpleName)
            }
        }.start()
    }

    override fun stop() {
        val s = synchronized(lock) { generation++; service.also { service = null } }
        s?.let { it.stop(); it.shutdown() }
    }

    override fun release() {
        stop()
        synchronized(lock) { model?.close(); model = null }
    }
}

/** Copies the bundled Vosk model from assets to internal storage once. */
object ModelInstaller {
    private const val DIR = "vosk-model"

    fun ensure(ctx: Context): String {
        val dest = File(ctx.filesDir, DIR)
        val marker = File(dest, ".ok")
        if (!marker.exists()) {
            dest.deleteRecursively()
            copy(ctx.assets, DIR, dest)
            check(File(dest, "am").exists()) {
                "Vosk model missing: put an unzipped model in app/src/main/assets/vosk-model (see README)"
            }
            marker.writeText("1")
        }
        return dest.absolutePath
    }

    private fun copy(am: AssetManager, path: String, dest: File) {
        val kids = am.list(path) ?: emptyArray()
        if (kids.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            kids.forEach { copy(am, "$path/$it", File(dest, it)) }
        }
    }
}
