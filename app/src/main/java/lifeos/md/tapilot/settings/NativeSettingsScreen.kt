package lifeos.md.tapilot.settings

import android.app.Activity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lifeos.md.tapilot.AppLanguage
import lifeos.md.tapilot.AppTheme
import lifeos.md.tapilot.accessibility.EdgeEffectPreferences
import lifeos.md.tapilot.accessibility.EdgeEffectGeometrySettings
import lifeos.md.tapilot.accessibility.EdgeEffectGeometryMode
import lifeos.md.tapilot.accessibility.FloatingBallPreferences
import lifeos.md.tapilot.services.ServiceGateway
import lifeos.md.tapilot.conversation.ModelProfiles
import lifeos.md.tapilot.conversation.NativeConversationCoordinator
import lifeos.md.tapilot.theme.ThemePalettes
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import lifeos.md.tapilot.theme.TapilotTheme
import lifeos.md.tapilot.theme.ThemeBackdrop
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

@Composable
fun NativeSettingsScreen(coordinator: NativeConversationCoordinator, activity: Activity, services: ServiceGateway,
                         onThemeChanged: () -> Unit, onLanguageChanged: () -> Unit) {
    val zh = AppLanguage.resolved(activity) == "zh"
    fun label(a: String, b: String) = if (zh) a else b
    var paletteId by remember { mutableStateOf(AppTheme.paletteId(activity)) }
    var mode by remember { mutableStateOf(AppTheme.preference(activity)) }
    val dark = AppTheme.resolveDark(mode, androidx.compose.foundation.isSystemInDarkTheme())
    var model by remember(coordinator.preset, coordinator.data) { mutableStateOf(coordinator.profile()) }
    var apiKey by remember(coordinator.preset) { mutableStateOf("") }
    var speechKey by remember { mutableStateOf("") }
    var provider by remember(coordinator.data.optString("speechProvider")) { mutableStateOf(coordinator.data.optString("speechProvider", "volcengine")) }
    var picker by remember { mutableStateOf("") }
    var resetModel by remember { mutableStateOf(false) }
    val deviceControl = rememberDeviceControlStatus(activity)
    var accessibilityGuide by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var floating by remember { mutableStateOf(FloatingBallPreferences.isEnabled(activity)) }
    var edge by remember { mutableStateOf(EdgeEffectPreferences.get(activity)) }
    var page by remember { mutableStateOf("") }
    var shots by remember { mutableStateOf(ScreenshotQuality.get(activity)) }
    var effort by remember { mutableStateOf(lifeos.md.tapilot.chatgpt.ChatGptResponses.reasoningEffort) }
    val busy = coordinator.settingsBusy
    TapilotTheme(dark, paletteId) {
        ThemeBackdrop(dark) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            androidx.activity.compose.BackHandler(enabled = page.isNotEmpty()) { page = "" }
            val pageTitle = when (page) {
                "model" -> label("模型", "Model"); "wireless" -> label("无线控制", "Wireless control"); "voice" -> label("语音", "Voice")
                "appearance" -> label("外观与语言", "Appearance & language"); "device" -> label("设备控制", "Device control")
                "tasks" -> label("任务与记录", "Tasks & history"); "about" -> label("关于", "About"); else -> label("设置", "Settings")
            }
            Header(pageTitle) { if (page.isNotEmpty()) page = "" else coordinator.back() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (page.isEmpty()) {
                    MenuCard {
                        MenuRow("sparkle", label("模型", "Model"), if (coordinator.data.optBoolean("chatgptConversation")) "ChatGPT" else "API Key") { page = "model" }
                        MenuRow("wifi", label("无线控制", "Wireless control")) { page = "wireless" }
                    }
                    MenuCard {
                        MenuRow("waveform", label("语音", "Voice")) { page = "voice" }
                        MenuRow("sun", label("外观与语言", "Appearance & language")) { page = "appearance" }
                    }
                    MenuCard {
                        MenuRow("phone", label("设备控制", "Device control")) { page = "device" }
                        MenuRow("clock", label("任务与记录", "Tasks & history")) { page = "tasks" }
                        MenuRow("info", label("关于", "About")) { page = "about" }
                    }
                }
                if (page == "model") Group(label("模型接入方式", "Model connection")) {
                    val useChatGpt = coordinator.data.optBoolean("chatgptConversation")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = useChatGpt,
                            onClick = { coordinator.preference("chatgptConversation", true) },
                            enabled = !busy && !coordinator.chatGpt.busy,
                            label = { Text("ChatGPT (SIWC)") })
                        FilterChip(selected = !useChatGpt,
                            onClick = { coordinator.preference("chatgptConversation", false) },
                            enabled = !busy && !coordinator.chatGpt.busy,
                            label = { Text("API Key") })
                    }
                    Text(label("切换后，对话和手机操作使用所选方式。两种方式的配置会分别保留。",
                        "Conversation and phone operations use the selected connection. Both configurations are saved separately."), fontSize = 12.sp)
                }
                if (page == "model" && coordinator.data.optBoolean("chatgptConversation")) Group("ChatGPT (SIWC)") { ChatGptSettingsSection(coordinator, zh) }
                if (page == "model") Group(label("截图大小", "Screenshot size")) {
                    SettingRow(label("发给 AI 的截图", "Screenshots sent to the AI"),
                        if (shots == "fast") label("缩小（更快）", "Smaller (faster)") else label("原始大小（更清晰）", "Full size (sharper)")) { picker = "shots" }
                    Text(label("缩小后上传更快、识图更快，但很小的文字可能读不清。读错小字时改回原始大小。",
                        "Smaller screenshots upload and process faster, but very small text may be misread. Switch back to full size if small text is read wrongly."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page == "model" && coordinator.data.optBoolean("chatgptConversation")) Group(label("思考强度", "Thinking level")) {
                    SettingRow(label("规划思考强度", "Planning effort"), when (effort) {
                        "medium" -> label("中", "Medium"); "high" -> label("高", "High"); else -> label("低（更快）", "Low (faster)") }) { picker = "effort" }
                    Text(label("控制 AI 在每次规划前思考多久。越低越快，越高越细致。手机截图步骤本身已使用最低思考强度。",
                        "How long the AI thinks before each planning step. Lower is faster, higher is more careful. Screenshot steps already use minimal thinking."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (page == "model" && !coordinator.data.optBoolean("chatgptConversation")) Group(label("Midscene 模型", "Midscene model")) {
                    SettingRow(label("服务商", "Provider"), coordinator.preset) { if (!busy) picker = "model" }
                    listOf("baseUrl" to "API 地址", "modelName" to "模型名称", "modelFamily" to "模型系列").forEach { (field, title) ->
                        val en = when (field) { "baseUrl" -> "API URL"; "modelName" -> "Model name"; else -> "Model family" }
                        SettingsField(value = model.optString(field), onValueChange = { model = JSONObject(model.toString()).put(field, it) },
                            label = { Text(label(title, en)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    }
                    SettingsField(apiKey, { apiKey = it }, label = { Text("API Key") },
                        placeholder = { Text(if (coordinator.hasKey(coordinator.preset)) label("已保存；留空保留", "Saved; leave empty to retain") else label("输入 API Key", "Enter API Key")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactPrimaryAction(label("保存", "Save"), enabled = !busy) { coordinator.saveModel(model, apiKey); apiKey = "" }
                        TextButton(onClick = { coordinator.saveModel(model, apiKey); apiKey = ""; coordinator.testModel() }, enabled = !busy) { Text(label("检查连接", "Check connection")) }
                        TextButton(onClick = { resetModel = true }, enabled = !busy) { Text(label("重置", "Reset")) }
                    }
                    if (coordinator.settingsSection == "model") Status(coordinator.settingsStatus)
                }
                if (page == "wireless") Group(label("ChatGPT 无线控制", "ChatGPT wireless control")) { WirelessControlSettings(activity, zh) }
                if (page == "voice") Group(label("语音输入", "Voice input")) {
                    SettingRow(label("转写服务", "Provider"), if (provider == "volcengine") label("火山引擎", "Volcengine") else "Deepgram") { if (!busy) picker = "speech" }
                    Text(if (provider == "volcengine") label("Seed ASR 2.0 小时版 · API 接入", "Seed ASR 2.0 duration · API") else "Deepgram Nova-3 · API", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SpeechApiKeyHelp(provider, zh)
                    SettingsField(speechKey, { speechKey = it }, label = { Text("API Key") },
                        placeholder = { Text(if (coordinator.hasKey("speech-$provider")) label("已保存；留空保留", "Saved; leave empty to retain") else label("输入 API Key", "Enter API Key")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactPrimaryAction(label("保存", "Save"), enabled = !busy) { coordinator.saveSpeech(provider, speechKey); speechKey = "" }
                        TextButton(onClick = { coordinator.saveSpeech(provider, speechKey); speechKey = ""; coordinator.checkSpeech(provider) }, enabled = !busy) { Text(label("检查连接", "Check connection")) }
                    }
                    if (coordinator.hasKey("speech-$provider")) TextButton(onClick = { coordinator.resetSpeech(provider); speechKey = "" }, enabled = !busy) {
                        Text(label("清除转写密钥", "Remove transcription key"))
                    }
                    if (coordinator.settingsSection == "speech") Status(coordinator.settingsStatus)
                }
                if (page == "voice") Group(label("语音对话与播报", "Voice conversation & playback")) { VoiceOutputSettingsSection(activity, zh, provider) }
                if (page == "voice") Group(label("语音润色", "Voice polishing")) { SpeechPolishSettingsSection(activity, zh,
                    coordinator.data.optBoolean("chatgptConversation"), coordinator.chatGpt.model) }
                if (page.isNotEmpty() && coordinator.settingsSection == "general") Status(coordinator.settingsStatus)
                if (page == "appearance") Group(label("外观与语言", "Appearance & language")) {
                    ThemePalettePicker(paletteId, zh) { id ->
                        AppTheme.setPalette(activity, id); paletteId = id; onThemeChanged()
                    }
                    SettingRow(label("明暗模式", "Appearance"), when (mode) {
                        "light" -> label("浅色", "Light"); "dark" -> label("深色", "Dark"); else -> label("跟随系统", "System")
                    }) { picker = "theme" }
                    SettingRow(label("语言", "Language"), activity.getSharedPreferences("midscene-preferences", 0).getString("language", "system").orEmpty()) { picker = "language" }
                }
                if (page == "device") Group(label("全局助手", "Global assistant")) {
                    ToggleRow(label("悬浮球", "Floating assistant"), floating) { value ->
                        services.call("app.setFloatingBallEnabled", JSONObject().put("enabled", value)) { result -> if (result.isSuccess) floating = value }
                    }
                    Text(label("单击圆点展开对话，长按直接语音对答。", "Tap the floating button to expand chat; hold it to start voice chat."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingRow(label("设备控制（辅助功能）", "Device control (Accessibility)"),
                        when (deviceControl) {
                            DeviceControlStatus.READY -> label("已连接", "Connected")
                            DeviceControlStatus.NEEDS_RECONNECT -> label("已授权 · 需要恢复连接", "Authorized · Reconnect required")
                            DeviceControlStatus.DISABLED -> label("查看开启步骤", "How to enable")
                        }) { accessibilityGuide = true }
                    Text(label("辅助功能 → 已安装的应用／已下载的应用 → Tapilot 设备控制 → 打开开关并确认，然后返回 Tapilot。",
                        "Accessibility → Installed/Downloaded apps → Tapilot device control → Enable and confirm, then return to Tapilot."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingRow(label("系统默认助手", "Default assistant"), label("设置", "Set up")) { coordinator.settingsCall("app.requestAssistantRole") }
                    SettingRow(label("后台运行", "Background operation"), label("电池设置", "Battery settings")) { coordinator.settingsCall("app.openBatterySettings") }
                    Text(label("若系统会关闭后台任务，请将 Tapilot 的电池使用设为不受限制。", "If background tasks are interrupted, allow unrestricted battery use for Tapilot."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingRow(label("执行边框", "Execution border"), edge.mode.wireValue) { picker = "edge" }
                    SettingRow(label("手势预览", "Gesture preview"), label("播放", "Play")) { services.call("app.previewGestures", JSONObject()) {} }
                    if (edge.mode == EdgeEffectGeometryMode.CUSTOM) {
                        Text(label("圆角 ${edge.cornerRadiusDp.toInt()} dp", "Radius ${edge.cornerRadiusDp.toInt()} dp"), fontSize = 13.sp)
                        Slider(edge.cornerRadiusDp, { edge = edge.copy(cornerRadiusDp = it) }, valueRange = 0f..64f,
                            onValueChangeFinished = { saveEdge(services, edge) })
                        Text(label("内缩 ${edge.insetDp.toInt()} dp", "Inset ${edge.insetDp.toInt()} dp"), fontSize = 13.sp)
                        Slider(edge.insetDp, { edge = edge.copy(insetDp = it) }, valueRange = 0f..24f,
                            onValueChangeFinished = { saveEdge(services, edge) })
                    }
                }
                if (page == "tasks") Group(label("任务与记录", "Tasks & history")) {
                    AgentLimitSettings(coordinator, zh)
                    ToggleRow(label("高级 YAML 模式", "Advanced YAML mode"), coordinator.data.optBoolean("advanced")) { coordinator.preference("advanced", it) }
                    SettingRow(label("清空聊天记录", "Clear chat history"), "") { clearHistory = true }
                    SettingRow(label("检查执行引擎", "Check execution engine"), "") { coordinator.settingsCall("runtime.doctor") }
                }
                if (page == "tasks" && activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Group(label("开发实验", "Developer experiments")) {
                        SettingRow(label("后台屏幕（实验性）", "Background screen (Experimental)"), label("默认关闭", "Off by default")) {
                            activity.startActivity(android.content.Intent().setClassName(activity,
                                "lifeos.md.tapilot.shell.ShellSetupActivity"))
                        }
                    }
                }
                if (page == "about") Group(label("关于 Tapilot", "About Tapilot")) { AppUpdateSettings(activity, zh) }
            }
        }
        }
        if (picker.isNotEmpty()) {
            val choices = when (picker) {
                "model" -> ModelProfiles.ids
                "speech" -> listOf("volcengine", "deepgram")
                "theme" -> listOf("system", "light", "dark")
                "language" -> listOf("system", "zh", "en")
                "effort" -> listOf("low", "medium", "high")
                "shots" -> ScreenshotQuality.choices
                else -> listOf("auto", "square", "custom")
            }
            AlertDialog(onDismissRequest = { picker = "" }, title = { Text(label("选择", "Choose")) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { choices.forEach { choice ->
                    val title = when (choice) {
                        "system" -> label("跟随系统", "System"); "light" -> label("浅色", "Light"); "dark" -> label("深色", "Dark")
                        "zh" -> "简体中文"; "en" -> "English"; "auto" -> label("自动检测", "Automatic")
                        "square" -> label("直角", "Square"); "custom" -> label("自定义", "Custom")
                        "fast" -> label("缩小（更快）", "Smaller (faster)"); "sharp" -> label("原始大小（更清晰）", "Full size (sharper)")
                        "low" -> label("低（更快）", "Low (faster)"); "medium" -> label("中", "Medium"); "high" -> label("高（更慢更细致）", "High (slower, more careful)")
                        "volcengine" -> label("火山引擎", "Volcengine"); "doubao" -> label("豆包", "Doubao"); "qwen" -> label("通义千问", "Qwen"); else -> choice
                    }
                    Text(title, Modifier.fillMaxWidth().clickable {
                        when (picker) {
                            "model" -> { coordinator.selectPreset(choice); apiKey = "" }
                            "speech" -> { provider = choice; speechKey = "" }
                            "theme" -> { AppTheme.set(activity, choice); mode = choice; onThemeChanged() }
                            "language" -> if (AppLanguage.set(activity, choice)) onLanguageChanged()
                            "shots" -> { ScreenshotQuality.set(activity, choice); shots = choice }
                            "effort" -> { lifeos.md.tapilot.chatgpt.ChatGptResponses.saveEffort(activity, choice); effort = choice }
                            "edge" -> { edge = edge.copy(mode = EdgeEffectGeometryMode.fromWireValue(choice)); saveEdge(services, edge) }
                        }; picker = ""
                    }.padding(vertical = 14.dp))
                } }
            }, confirmButton = { TextButton(onClick = { picker = "" }) { Text(label("关闭", "Close")) } })
        }
        if (accessibilityGuide) AlertDialog(onDismissRequest = { accessibilityGuide = false },
            title = { Text(label("开启 Tapilot 设备控制", "Enable Tapilot device control")) },
            text = { Text(if (deviceControl == DeviceControlStatus.NEEDS_RECONNECT) label("系统开关已开启，但 Tapilot 设备控制服务未连接。请在系统辅助功能中找到 Tapilot 设备控制，将开关关闭再开启，并确认系统提示。返回 Tapilot 后，状态应显示「已连接」。无需撤销配对或重新扫码。", "The system switch is enabled, but Tapilot device control is not connected. In Accessibility settings, turn Tapilot device control off and on and confirm the system prompt. Return to Tapilot and check Connected. You do not need to revoke pairing or scan again.") else label("1. 在接下来打开的系统辅助功能页面，进入「已安装的应用」或「已下载的应用」（部分手机叫「更多已下载的服务」）。\n2. 找到「Tapilot 设备控制」。\n3. 打开「使用服务／允许」开关，并确认系统提示。\n4. 返回 Tapilot，设备控制应显示「已连接」。\n\n此权限用于读取屏幕及执行你要求的点击、滑动和输入。",
                "1. Open Installed apps, Downloaded apps, or Downloaded services.\n2. Select Tapilot device control.\n3. Enable Use service/Allow and confirm the system prompt.\n4. Return to Tapilot and check Connected.\n\nThis permission allows screen reading and the taps, swipes and text input you request.")) },
            confirmButton = { TextButton(onClick = { accessibilityGuide = false; coordinator.settingsCall("app.openAccessibilitySettings") }) { Text(label("打开系统辅助功能", "Open Accessibility settings")) } },
            dismissButton = { TextButton(onClick = { accessibilityGuide = false }) { Text(label("取消", "Cancel")) } })
        if (resetModel || clearHistory) AlertDialog(onDismissRequest = { resetModel = false; clearHistory = false },
            title = { Text(if (resetModel) label("重置当前模型？", "Reset this model?") else label("清空聊天记录？", "Clear chat history?")) },
            text = { Text(if (resetModel) label("将删除当前服务商的 API Key 并恢复默认参数。", "Deletes this provider's API Key and restores defaults.") else label("聊天和附件副本将被清除，执行报告仍保留。", "Chat and attachment copies will be removed. Reports are retained.")) },
            confirmButton = { TextButton(onClick = { if (resetModel) coordinator.saveModel(model, reset = true) else coordinator.clearHistory(); resetModel = false; clearHistory = false }) { Text(label("确认", "Confirm")) } },
            dismissButton = { TextButton(onClick = { resetModel = false; clearHistory = false }) { Text(label("取消", "Cancel")) } })
    }
}

private fun saveEdge(services: ServiceGateway, value: EdgeEffectGeometrySettings) {
    services.call("app.setEdgeEffectSettings", JSONObject().put("mode", value.mode.wireValue).put("cornerRadiusDp", value.cornerRadiusDp).put("insetDp", value.insetDp)) {}
}

@Composable private fun Header(title: String, back: () -> Unit) {
    // ChatGPT style: no bar, a floating round back button and a centred title.
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.CenterStart).size(44.dp)
            .shadow(4.dp, androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = back), contentAlignment = Alignment.Center) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}
/** A rounded card holding menu rows, separated by hairlines (ChatGPT settings style). */
@Composable private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
@Composable private fun ColumnScope.MenuRow(icon: String, title: String, value: String = "", click: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = click).heightIn(min = 64.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        lifeos.md.tapilot.chat.ChatIcon(icon, colors.onSurface, Modifier.size(26.dp))
        Text(title, Modifier.weight(1f).padding(start = 16.dp), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        if (value.isNotEmpty()) Text(value, fontSize = 16.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        lifeos.md.tapilot.chat.ChatIcon("chevron", colors.onSurfaceVariant, Modifier.size(18.dp))
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
}
@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
@Composable private fun SettingRow(title: String, value: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        lifeos.md.tapilot.chat.ChatIcon("chevron", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp))
    }
}
@Composable private fun ToggleRow(title: String, value: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium); Switch(value, changed) }
}

@Composable fun NativeAdvancedScreen(coordinator: NativeConversationCoordinator) {
    var source by remember { mutableStateOf(coordinator.data.optString("yaml").ifBlank { "cases:\n  - name: Tapilot\n    steps:\n      - aiAct: ${if (coordinator.chinese) "打开计算器" else "Open Calculator"}\n" }) }
    TapilotTheme(coordinator.dark) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
            Header("YAML") { coordinator.back() }
            SettingsField(source, { source = it; coordinator.preference("yaml", it) }, modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp), label = { Text("YAML") })
            Button(onClick = { coordinator.executeYaml(source) }, enabled = source.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(if (!coordinator.chinese) "Run" else "执行") }
        }
    }
}

@Composable private fun Status(text: String) {
    if (text.isNotBlank()) Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
}

/** Compact inline actions retain a 48 dp touch row without filling it with accent color. */
@Composable
private fun CompactPrimaryAction(title: String, enabled: Boolean, click: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Button(onClick = click, enabled = enabled, modifier = Modifier.height(40.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}


@Composable
private fun ThemePalettePicker(selected: String, zh: Boolean, changed: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (zh) "主题" else "Theme", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("classic", "minimal").forEach { id ->
                val light = ThemePalettes.get(id, false)
                val dark = ThemePalettes.get(id, true)
                val active = selected == id
                Surface(modifier = Modifier.weight(1f).selectable(active, role = Role.RadioButton, onClick = { changed(id) }),
                    shape = RoundedCornerShape(16.dp), color = colors.surface,
                    border = BorderStroke(1.dp, if (active) colors.primary else colors.outlineVariant)) {
                    Column(Modifier.heightIn(min = 86.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(light.background, light.primary, dark.background, dark.primary).forEach { color ->
                                Surface(Modifier.size(20.dp), shape = RoundedCornerShape(6.dp), color = Color(color),
                                    border = BorderStroke(.5.dp, colors.outlineVariant)) {}
                            }
                        }
                        val title = if (id == "classic") { if (zh) "经典" else "Classic" } else { if (zh) "素雅" else "Minimal" }
                        Text(title + if (active) " ✓" else "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text(if (selected == "minimal") { if (zh) "ChatGPT 风格：浅灰底、黑色点缀。" else "ChatGPT style: soft gray surfaces with black accents." }
            else { if (zh) "柔和纸色，淡紫点缀。" else "Warm paper tones with lavender accents." },
            fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
    }
}
