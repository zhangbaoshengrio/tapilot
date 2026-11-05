package lifeos.md.tapilot.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lifeos.md.tapilot.speech.SpeechPolishSettings
import lifeos.md.tapilot.speech.SpeechPolishStore
import lifeos.md.tapilot.speech.transcriptPolisher

/** Same independent API configuration and automatic saving as Vibelet. */
@Composable
internal fun SpeechPolishSettingsSection(context: Context, zh: Boolean, useChatGpt: Boolean = false, chatGptModel: String = "") {
    val store = remember(context) { SpeechPolishStore(context) }
    var settings by remember(useChatGpt, chatGptModel) { mutableStateOf(store.load()) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(store.hasKey()) }
    var status by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var promptsOpen by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkPolisher by remember { mutableStateOf<lifeos.md.tapilot.speech.TranscriptPolisher?>(null) }
    val scope = rememberCoroutineScope()
    fun label(a: String, b: String) = if (zh) a else b
    val latestSettings by rememberUpdatedState(settings)
    val latestKey by rememberUpdatedState(key)
    val latestEdited by rememberUpdatedState(edited)
    fun save() {
        runCatching { store.save(settings, key) }.fold({
            if (key.isNotBlank()) hasKey = true
            failed = false; status = label("已自动保存", "Saved automatically"); edited = false
        }, { failed = true; status = it.message ?: label("保存失败，请检查配置", "Could not save; check configuration") })
    }
    LaunchedEffect(settings, key) {
        if (!edited) return@LaunchedEffect
        status = label("正在保存…", "Saving…")
        if (!settings.enabled) save() else { delay(500); save() }
    }
    DisposableEffect(store) {
        onDispose { checkPolisher?.cancel(); if (latestEdited) runCatching { store.save(latestSettings, latestKey) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label("自动润色", "Enable polishing"), Modifier.weight(1f), fontSize = 16.sp)
            Switch(settings.enabled, { settings = settings.copy(enabled = it); edited = true })
        }
        Text(if (useChatGpt) label("使用当前 ChatGPT 账号和模型整理转写，无需单独配置润色 Key。失败时保留原文。",
            "Polish transcripts with the selected ChatGPT account and model. No separate polishing key is needed. Keep the original on failure.")
            else label("使用独立的 OpenAI 兼容 API 整理转写，去除口癖、修正口误和标点，保留原意。录音时预览，松手后处理完整文字；失败或超时保留原文。",
            "Clean transcripts with a separate OpenAI-compatible API. Preview while recording and polish the complete text on release. Keep the original on failure or timeout."),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.enabled) {
            if (useChatGpt) Text("ChatGPT · ${chatGptModel.ifBlank { label("请先登录并选择模型", "Sign in and choose a model") }}", fontSize = 14.sp)
            else {
            SettingsField(settings.baseUrl, { settings = settings.copy(baseUrl = it); edited = true },
                label = { Text("Base URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            SettingsField(settings.model, { settings = settings.copy(model = it); edited = true },
                label = { Text(label("润色模型", "Polishing model")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            SettingsField(key, { key = it; edited = true }, label = { Text(label("润色 API Key", "Polishing API Key")) },
                placeholder = { Text(if (hasKey) label("已保存；留空保留", "Saved; leave empty to retain") else "API Key") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            Text(label("转写文字会发送到上述 API；密钥使用 Android Keystore 加密保存。", "Transcripts go to this API; keys are encrypted with Android Keystore."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!hasKey && key.isBlank()) Text(label("填写润色 API Key 后生效", "Enter a polishing API key to activate"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = !checking, onClick = {
                save()
                if (!failed) {
                    checking = true
                    scope.launch {
                        val sample = label("嗯我我明天下午三点开会", "Um I I have a meeting tomorrow at three")
                        val polisher = transcriptPolisher(context)
                        checkPolisher = polisher
                        try {
                            val result = withContext(Dispatchers.IO) {
                                try { polisher.polish(sample) } finally { polisher.cancel() }
                            }
                            failed = result == sample
                            status = if (failed) polisher.failureReason ?: label("未取得润色结果，已保留原文", "No polishing result; original preserved") else result
                        } finally { checking = false; checkPolisher = null }
                    }
                }
            }) { Text(label(if (checking) "正在试润色…" else "试润色", if (checking) "Testing polishing…" else "Try polishing")) }
            TextButton(onClick = { promptsOpen = !promptsOpen }) { Text(label(if (promptsOpen) "收起提示词" else "自定义提示词", if (promptsOpen) "Hide prompts" else "Customize prompts")) }
            if (promptsOpen) {
                SettingsField(settings.promptZh, { settings = settings.copy(promptZh = it); edited = true }, label = { Text(label("中文提示词", "Chinese prompt")) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, shape = MaterialTheme.shapes.medium)
                SettingsField(settings.promptEn, { settings = settings.copy(promptEn = it); edited = true }, label = { Text(label("英文提示词", "English prompt")) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, shape = MaterialTheme.shapes.medium)
                TextButton(onClick = {
                    val defaults = SpeechPolishSettings()
                    settings = settings.copy(promptZh = defaults.promptZh, promptEn = defaults.promptEn); edited = true
                }) { Text(label("恢复默认提示词", "Restore default prompts")) }
            }
            if (!useChatGpt && hasKey) TextButton(onClick = {
                runCatching { store.deleteKey() }.fold({ key = ""; hasKey = false; status = label("密钥已删除", "Key removed") },
                    { failed = true; status = label("删除失败，请重试", "Could not remove key") })
            }) { Text(label("删除润色密钥", "Remove polishing key"), color = MaterialTheme.colorScheme.error) }
        }
        if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
