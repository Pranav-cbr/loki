package com.loki.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.telecom.TelecomManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles spoken commands in English, Hinglish and Hindi.
 */
class CommandRouter(
    private val ctx: Context,
    private val cloud: CloudAi
) {
    data class Reply(
        val text: String,
        val stopListening: Boolean = false
    )

    suspend fun handle(raw: String, s: LokiSettings): Reply {
        val t = raw.lowercase(Locale.ROOT).trim()
        val hi = raw.any { it in '\u0900'..'\u097F' }

        // Explicit type fixes Kotlin's function-reference/type-inference issue.
        val say: (String, String) -> String = { en, hindi ->
            if (hi) hindi else en
        }

        return when {
            has(t, "torch", "flashlight", "टॉर्च", "फ्लैशलाइट") -> {
                val on = !has(t, "off", "band", "बंद")

                if (setTorch(on)) {
                    Reply(
                        if (on) say("Torch on.", "टॉर्च चालू।")
                        else say("Torch off.", "टॉर्च बंद।")
                    )
                } else {
                    Reply(
                        say(
                            "I couldn't reach the flashlight.",
                            "मैं फ्लैशलाइट तक नहीं पहुँच सका।"
                        )
                    )
                }
            }

            has(t, "battery", "बैटरी") -> {
                val p = ctx.getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                Reply(say("Battery is at $p percent.", "बैटरी $p प्रतिशत है।"))
            }

            has(
                t,
                "call", "dial", "phone lagao", "call karo",
                "फोन लगाओ", "कॉल करो"
            ) -> {
                val num = Regex("""\+?[0-9][0-9 \-]{5,}""")
                    .find(t)
                    ?.value
                    ?.replace(" ", "")
                    ?.replace("-", "")

                if (num == null) {
                    Reply(
                        say(
                            "Say a phone number after 'call'. Contact-name calling isn't enabled yet.",
                            "'कॉल' के बाद फ़ोन नंबर बोलिए। नाम से कॉल करना अभी उपलब्ध नहीं है।"
                        )
                    )
                } else {
                    placeCall(num, s, say)
                }
            }

            appName(t) != null -> {
                openApp(appName(t)!!, say)
            }

            Regex("""^(?:search|google)\s+(?:for\s+)?(.+)$""")
                .find(t) != null -> {
                val q = Regex("""^(?:search|google)\s+(?:for\s+)?(.+)$""")
                    .find(t)!!
                    .groupValues[1]

                val url = "https://www.google.com/search?q=" +
                    URLEncoder.encode(q, "UTF-8")

                val ok = launchOrNotify(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    "Search: $q"
                )

                if (ok) {
                    Reply(say("Searching for $q.", "$q खोज रहा हूँ।"))
                } else {
                    tapNote(say)
                }
            }

            has(
                t,
                "what time", "time", "kitne baje",
                "samay", "समय", "टाइम", "कितने बजे"
            ) -> {
                val now = SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date())

                Reply(say("It's $now.", "अभी $now बजे हैं।"))
            }

            has(t, "date", "today", "tarikh", "तारीख") -> {
                val d = SimpleDateFormat(
                    "EEEE, d MMMM",
                    Locale.getDefault()
                ).format(Date())

                Reply(say("Today is $d.", "आज $d है।"))
            }

            has(
                t,
                "stop listening", "go to sleep", "band karo",
                "बंद करो", "सुनना बंद"
            ) -> {
                Reply(
                    say(
                        "Okay, I'll stop listening.",
                        "ठीक है, मैं सुनना बंद करता हूँ।"
                    ),
                    true
                )
            }

            else -> {
                Reply(
                    cloud.ask(raw, s) ?: say(
                        "I can't answer that offline. Add a backend URL in settings for general questions.",
                        "यह मैं ऑफ़लाइन नहीं बता सकता। सामान्य सवालों के लिए सेटिंग्स में बैकएंड जोड़ें।"
                    )
                )
            }
        }
    }

    // Checks whether a command contains any supported keyword.
    private fun has(t: String, vararg keys: String): Boolean {
        return keys.any { key ->
            if (key.all { c -> c.code < 128 }) {
                Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(t)
            } else {
                t.contains(key)
            }
        }
    }

    // Extracts the app name from commands such as "open YouTube".
    private fun appName(t: String): String? {
        Regex
