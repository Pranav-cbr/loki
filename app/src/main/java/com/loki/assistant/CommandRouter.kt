package com.loki.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maps a spoken command to an action. Deliberately simple keyword matching
 * (English / Hinglish / Devanagari Hindi); anything unmatched goes to the optional backend.
 */
class CommandRouter(private val ctx: Context, private val cloud: CloudAi) {
    data class Reply(val text: String, val stopListening: Boolean = false)

    suspend fun handle(raw: String, s: LokiSettings): Reply {
        val t = raw.lowercase(Locale.ROOT).trim()
        val hi = raw.any { it in '\u0900'..'\u097F' }
        fun say(en: String, h: String) = if (hi) h else en

        return when {
            has(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट") -> {
                val on = !has(t, "off", "band", "बंद")
                if (setTorch(on)) Reply(if (on) say("Torch on.", "टॉर्च चालू।") else say("Torch off.", "टॉर्च बंद।"))
                else Reply(say("I couldn't reach the flashlight.", "मैं फ्लैशलाइट तक नहीं पहुँच सका।"))
            }
            has(t, "battery", "बैटरी") -> {
                val p = ctx.getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Reply(say("Battery is at $p percent.", "बैटरी $p प्रतिशत है।"))
            }
            appName(t) != null -> openApp(appName(t)!!, ::say)
            has(t, "call", "dial") -> {
                val num = Regex("\\+?[0-9][0-9 ]{5,}").find(t)?.value?.replace(" ", "")
                if (num == null) Reply(say("Say the phone number after 'call'.", "'call' के बाद फ़ोन नंबर बोलिए।"))
                else {
                    val ok = launchOrNotify(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")), "Dial $num")
                    if (ok) Reply(say("Opening the dialer.", "डायलर खोल रहा हूँ।")) else tapNote(::say)
                }
            }
            Regex("^(?:search|google)\\s+(?:for\\s+)?(.+)$").find(t) != null -> {
                val q = Regex("^(?:search|google)\\s+(?:for\\s+)?(.+)$").find(t)!!.groupValues[1]
                val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
                val ok = launchOrNotify(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Search: $q")
                if (ok) Reply(say("Searching for $q.", "$q खोज रहा हूँ।")) else tapNote(::say)
            }
            has(t, "what time", "time", "kitne baje", "samay", "समय", "टाइम", "कितने बजे") -> {
                val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                Reply(say("It's $now.", "अभी $now बजे हैं।"))
            }
            has(t, "date", "today", "tarikh", "तारीख") -> {
                val d = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
                Reply(say("Today is $d.", "आज $d है।"))
            }
            has(t, "stop listening", "go to sleep", "band karo", "बंद करो", "सुनना बंद") ->
                Reply(say("Okay, I'll stop listening.", "ठीक है, मैं सुनना बंद करता हूँ।"), true)
            else -> Reply(
                cloud.ask(raw, s) ?: say(
                    "I can't answer that offline. Add a backend URL in settings for general questions.",
                    "यह मैं ऑफ़लाइन नहीं बता सकता। सामान्य सवालों के लिए सेटिंग्स में बैकएंड जोड़ें।"
                )
            )
        }
    }

    // ---- helpers ----
    private fun has(t: String, vararg keys: String) = keys.any {
        if (it.all { c -> c.code < 128 }) Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(t) else t.contains(it)
    }

    private fun appName(t: String): String? {
        Regex("^(?:open|launch|start|kholo|khol do)\\s+(.+)$").find(t)?.let { return it.groupValues[1] }
        Regex("^(.+?)\\s+(?:kholo|khol do|खोलो|खोल दो)$").find(t)?.let { return it.groupValues[1] }
        return null
    }

    private fun openApp(name: String, say: (String, String) -> String): Reply {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit: ResolveInfo = pm.queryIntentActivities(q, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(name) }
            ?: return Reply(say("I couldn't find an app called $name.", "$name नाम का ऐप नहीं मिला।"))
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return Reply(say("I can't open $name.", "मैं $name नहीं खोल सकता।"))
        val label = hit.loadLabel(pm).toString()
        return if (launchOrNotify(launch, label)) Reply(say("Opening $label.", "$label खोल रहा हूँ।"))
        else tapNote(say)
    }

    private fun tapNote(say: (String, String) -> String) = Reply(
        say(
            "Android blocks launching apps from the background, so I put a notification. Tap it to continue.",
            "एंड्रॉइड बैकग्राउंड से ऐप खोलने नहीं देता, इसलिए मैंने नोटिफ़िकेशन भेजा है। उस पर टैप कीजिए।"
        )
    )

    /** Android 10+ blocks activity starts from the background; fall back to a tap-to-open notification. */
    private fun launchOrNotify(intent: Intent, label: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (LokiState.appVisible) { ctx.startActivity(intent); return true }
        val id = 100 + (label.hashCode() and 0xff)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Notifs.tapToOpen(ctx, id, "LOKI: $label", "Tap to open", pi)
        return false
    }

    private fun setTorch(on: Boolean): Boolean = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) false else { cm.setTorchMode(id, on); true }
    } catch (e: Exception) {
        false
    }
}
