package com.loki.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { LokiScreen() }
            }
        }
    }
    override fun onStart() { super.onStart(); LokiState.appVisible = true }
    override fun onStop() { LokiState.appVisible = false; super.onStop() }
}

@Composable
fun LokiScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(ctx.applicationContext) }
    val phase by LokiState.phase.collectAsState()
    val log by LokiState.log.collectAsState()
    val s by store.flow.collectAsState(initial = LokiSettings())

    var wakeWords by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var minBat by remember { mutableFloatStateOf(15f) }
    LaunchedEffect(Unit) {
        val c = store.current()
        wakeWords = c.wakeWords; url = c.backendUrl; token = c.backendToken; minBat = c.minBattery.toFloat()
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted(Manifest.permission.RECORD_AUDIO)) pending?.invoke()
        pending = null
    }
    fun withPerms(action: () -> Unit) {
        val need = buildList {
            if (!granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
                add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) action() else { pending = action; launcher.launch(need.toTypedArray()) }
    }

    val micOn = phase == Phase.WAKE_LISTENING || phase == Phase.COMMAND_LISTENING
    val running = phase != Phase.OFF && phase != Phase.ERROR

    Column(
        Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("LOKI", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(if (micOn) "🎙 Microphone is ON" else "Microphone is off", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (phase) {
                        Phase.OFF -> "Background listening is off."
                        Phase.LOADING -> "Loading the wake-word model…"
                        Phase.WAKE_LISTENING -> "Listening on-device for “Hey Loki”. Nothing is sent anywhere until you speak a command."
                        Phase.COMMAND_LISTENING -> "Listening to your command (system speech recognizer)."
                        Phase.THINKING -> "Working on it…"
                        Phase.SPEAKING -> "Speaking."
                        Phase.PAUSED -> "Paused to save battery."
                        Phase.ERROR -> "Couldn't start — see the log below."
                    }
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Background listening (“Hey Loki”)")
            Switch(checked = running, onCheckedChange = { on ->
                if (on) withPerms { LokiService.start(ctx) }
                else {
                    ctx.stopService(Intent(ctx, LokiService::class.java))
                    scope.launch { store.set(Keys.LISTENING, false) }
                }
            })
        }
        Button(onClick = { withPerms { LokiService.pushToTalk(ctx) } }, Modifier.fillMaxWidth()) {
            Text("Talk now (push-to-talk)")
        }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }, Modifier.fillMaxWidth()) { Text("Battery optimisation settings") }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com")))
        }, Modifier.fillMaxWidth()) { Text("Phone-maker background limits (guide)") }

        HorizontalDivider()
        Text("Settings", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.language == "en-IN", onClick = { scope.launch { store.set(Keys.LANGUAGE, "en-IN") } }, label = { Text("English / Hinglish") })
            FilterChip(selected = s.language == "hi-IN", onClick = { scope.launch { store.set(Keys.LANGUAGE, "hi-IN") } }, label = { Text("हिन्दी") })
        }
        SwitchRow("Hold CPU awake (partial wake lock)", s.wakeLock) { scope.launch { store.set(Keys.WAKE_LOCK, it) } }
        SwitchRow("Listen only while charging", s.onlyCharging) { scope.launch { store.set(Keys.ONLY_CHARGING, it) } }
        Text("Pause below ${minBat.toInt()}% battery")
        Slider(value = minBat, onValueChange = { minBat = it }, valueRange = 5f..50f,
            onValueChangeFinished = { scope.launch { store.set(Keys.MIN_BATTERY, minBat.toInt()) } })

        OutlinedTextField(wakeWords, { wakeWords = it }, Modifier.fillMaxWidth(), label = { Text("Wake phrases (comma-separated)") })
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Backend URL (https://…/ask, optional)") })
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Backend token (optional)") })
        Button(onClick = {
            scope.launch {
                store.set(Keys.WAKE_WORDS, wakeWords.trim()); store.set(Keys.BACKEND_URL, url.trim()); store.set(Keys.BACKEND_TOKEN, token.trim())
            }
        }) { Text("Save text settings (restart listening to apply)") }

        HorizontalDivider()
        Text("Log", style = MaterialTheme.typography.titleMedium)
        log.reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
