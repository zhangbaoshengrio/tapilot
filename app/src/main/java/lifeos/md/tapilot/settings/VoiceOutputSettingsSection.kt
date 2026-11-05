package lifeos.md.tapilot.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lifeos.md.tapilot.bridge.ModelCredentialStore
import lifeos.md.tapilot.voice.ApiSpeechPlayback
import lifeos.md.tapilot.voice.VoiceOutputSettings

@Composable
internal fun VoiceOutputSettingsSection(context: Context, zh: Boolean, provider: String) {
    val config = remember { VoiceOutputSettings(context) }
    val credentials = remember { ModelCredentialStore(context) }
    var autoRead by remember { mutableStateOf(config.autoRead) }
    var speaker by remember(provider) { mutableStateOf(config.speaker(provider)) }
    var key by remember(provider) { mutableStateOf("") }
    var hasKey by remember(provider) { mutableStateOf(credentials.contains("speech-output-$provider")) }
    var status by remember(provider) { mutableStateOf("") }
    var busy by remember(provider) { mutableStateOf(false) }
    val player = remember(provider) { ApiSpeechPlayback(context) { provider } }
    var disposed by remember(provider) { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(player) { onDispose { disposed = true; player.close() } }
    fun label(a: String, b: String) = if (zh) a else b
    fun save(): Boolean = runCatching {
        config.saveSpeaker(provider, speaker.trim())
        if (key.isNotBlank()) {
            require(key.length <= 4096 && key.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Invalid API Key" }
            credentials.set("speech-output-$provider", key.trim()); key = ""; hasKey = true
        }
        status = label("已保存", "Saved")
    }.fold({ true }, { status = it.message.orEmpty(); false })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label("自动朗读助手回答", "Read assistant answers aloud"))
            Switch(checked = autoRead, onCheckedChange = { autoRead = it; config.autoRead = it })
        }

        Text(label("点击输入框右侧声波开始对话。收到指令会有提示音和语音回应；切到其他应用也能继续对答。通知栏可暂停、打断或结束。",
            "Start with the waveform. Hear an acknowledgement, then keep talking in other apps. Pause, interrupt or end from the notification."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (provider == "volcengine") label("火山引擎 Seed TTS 2.0 · 使用 API 播报", "Volcengine Seed TTS 2.0 · API playback")
            else label("Deepgram Aura-2 · 当前默认音色支持英文", "Deepgram Aura-2 · The default voice speaks English"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        VoicePicker(provider, speaker, zh, enabled = !busy, onSelect = { speaker = it })
        SettingsField(key, { key = it }, label = { Text(label("播报 API Key（可选）", "Playback API Key (optional)")) },
            placeholder = { Text(if (hasKey) label("已保存；留空保留", "Saved; leave empty to retain") else label("留空使用转写密钥", "Use transcription key when empty")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
        Text(label("转写和播报是两个服务，需要分别开通权限。", "Transcription and speech playback require separate service permissions."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { save() }, enabled = !busy) { Text(label("保存", "Save")) }
            TextButton(onClick = {
                if (save()) {
                    busy = true; status = label("正在准备播报…", "Preparing playback…")
                    player.speak(label("你好，我是 Tapilot。你可以和我连续对话。", "Hello, I'm Tapilot. We can talk back and forth."),
                        onStarted = { handler.post { if (!disposed) status = label("正在播报…", "Playing…") } },
                        callback = { result -> handler.post { if (!disposed) {
                            busy = false; status = result.fold({ label("播报成功", "Playback succeeded") }, { it.message.orEmpty() })
                        } } })
                }
            }, enabled = !busy) { Text(label("试听", "Test voice")) }
            if (busy) TextButton(onClick = { player.stop(); busy = false; status = label("已停止", "Stopped") }) { Text(label("停止", "Stop")) }
            if (hasKey && !busy) TextButton(onClick = {
                credentials.delete("speech-output-$provider"); hasKey = false; status = label("已改为使用转写密钥", "Using transcription key")
            }) { Text(label("使用转写密钥", "Use transcription key")) }
        }
        if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
