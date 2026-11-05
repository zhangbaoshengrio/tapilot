package lifeos.md.tapilot.chat

import lifeos.md.tapilot.theme.TapilotTheme
import lifeos.md.tapilot.theme.tapilotColors
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription


@Composable
fun ChatScreen(controller: ChatController, compact: Boolean = false, voiceConnecting: Boolean = false) {
    val state = controller.state
    val colors = tapilotColors(state.dark, state.paletteId)
    TapilotTheme(state.dark, state.paletteId) {
      CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        var sessions by remember { mutableStateOf(false) }
        var drawer by remember { mutableStateOf(false) }
        val list = rememberLazyListState()
        val busy = state.running || controller.recording || controller.transcribing || controller.picking
        val zh = state.chinese
        fun label(chinese: String, english: String) = if (zh) chinese else english
        val showGuide = state.showFirstTaskGuide && !state.running
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(state.activeConversationId, state.messages.size, state.messages.lastOrNull()?.text, compact, imeBottom, showGuide) {
            if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.lastIndex + if (showGuide) 1 else 0)
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).drawBehind {
            // ChatGPT style: a very soft radial glow on the light page.
            if (colors.background.luminance() > .5f) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Color(0xFFFFFFFF), Color(0xFFEFEFEF)), center = Offset(size.width / 2f, size.height * .42f), radius = size.height * .8f))
        }) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                // ChatGPT style: no bar, two floating white controls.
                Row(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.statusBarsPadding()).height(64.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).shadow(4.dp, CircleShape).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { drawer = true }, enabled = !busy) {
                            ChatIcon("menu", colors.onSurface, description = label("对话列表", "Chats"))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.running) Text(label("正在处理…", "Working…"),
                        color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    Row(Modifier.height(48.dp).shadow(4.dp, RoundedCornerShape(24.dp)).background(colors.surface, RoundedCornerShape(24.dp)).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller.command("new") }, enabled = !busy && state.ready) {
                            ChatIcon("compose", colors.onSurface, description = label("新对话", "New conversation"))
                        }
                        SpeechPlaybackToggle(controller)
                        Box {
                            IconButton(onClick = { sessions = true }, enabled = !busy) {
                                ChatIcon("more", colors.onSurface, description = label("会话管理", "Manage conversations"))
                            }
                            ChatMenuCard(controller, sessions, onDismiss = { sessions = false })
                        }
                    }
                }
                if (state.needsAccessibility) {
                    TextButton(onClick = { controller.command("accessibility") }, modifier = Modifier.fillMaxWidth()) {
                        Text(label("设备控制未连接 · 前往设置", "Device control is off · Settings"), color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                if (!state.configured && state.ready) {
                    TextButton(onClick = { controller.command("settings") }, modifier = Modifier.fillMaxWidth()) {
                        Text(label("配置模型 API，开始对话", "Configure model API to start chatting"))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showGuide) item(key = "first-task-guide") { FirstTaskCard(controller, busy) }
                    if (state.messages.isEmpty()) {
                        item(key = "welcome") {
                            Column(Modifier.fillMaxWidth().then(if (showGuide) Modifier else Modifier.fillParentMaxHeight()).padding(36.dp),
                                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label("有什么需要帮忙？", "How can I help?"), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(10.dp))
                                Text(label("输入任务，或长按输入框说话", "Type a task, or hold the input to speak"),
                                    color = colors.onSurfaceVariant, fontSize = 14.sp)
                            }
                        }
                    } else {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(message, zh, controller, state.running)
                        }
                    }
                }
                state.progress?.let { TaskProgressView(it, controller, zh) }
                state.handoffTaskId?.let { taskId ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(label("等待你处理", "Waiting for you"), fontWeight = FontWeight.Medium)
                        Text(label("按上方说明处理，或在输入框补充信息。继续前会重新检查画面。",
                            "Follow the instructions above, or add details below. The screen will be checked again before continuing."), fontSize = 12.sp)
                        Row {
                            TextButton(onClick = { controller.command("handoffOpenApp") }) { Text(label("返回应用", "Return to app")) }
                            TextButton(onClick = { controller.command("continueTask", id = taskId) }, enabled = !busy) {
                                Text(label("继续任务", "Continue task"))
                            }
                        }
                    }
                }
                if (controller.error.isNotBlank() || controller.playbackError.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(controller.error.ifBlank { controller.playbackError }, color = colors.error, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row {
                            if (controller.canRetrySpeech) TextButton(onClick = { controller.retrySpeech() }, enabled = !busy) {
                                Text(label("重试转写", "Retry transcription"))
                            }
                            TextButton(onClick = { controller.dismissError() }) { Text(label("关闭", "Dismiss")) }
                        }
                    }
                }
                if (controller.transcribing || controller.picking || !state.ready) {
                    Text(label(if (controller.picking) "正在读取附件…" else if (controller.transcribing) "正在转写…" else "正在加载…",
                        if (controller.picking) "Reading attachment…" else if (controller.transcribing) "Transcribing…" else "Loading…"),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = colors.primary, fontSize = 13.sp)
                }
                state.attachment?.let { attachment ->
                    Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AttachmentThumbnail(attachment.preview, Modifier.clickable { controller.command("preview") })
                        Text(attachment.name, Modifier.weight(1f).clickable { controller.command("preview") }.padding(horizontal = 10.dp), fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { controller.command("removeAttachment") }, enabled = !busy) { Text(label("移除", "Remove")) }
                    }
                }
                if (controller.voiceState.active || voiceConnecting) VoiceConversationPanel(controller, connecting = voiceConnecting) else ChatComposer(controller)
            }
            ChatDrawer(controller, drawer) { drawer = false }
            run {
                RecordingFeedback(controller.willCancel, controller.willSend,
                    if (controller.transcribing) label("正在转文字…", "Transcribing…")
                    else if (controller.willCancel) label("松开取消", "Release to cancel")
                    else if (!controller.willSend) label("松手 填入输入框", "Release to insert text")
                    else label("松开发送", "Release to send"),
                    label("取消", "Cancel"), label("发送", "Send"),
                    controller.audioLevels, controller.recordingSeconds, controller.liveTranscript, controller.transcribing,
                    visible = controller.recording || controller.transcribing,
                    onCancel = controller::cancelRecording,
                    onSend = { controller.moveRecording(RecordingGesture.Send); controller.releaseRecording() })
            }
        }
        state.preview?.let { AttachmentPreview(it, zh) { controller.command("closePreview") } }
        state.confirmation?.let { instruction ->
            var revised by remember(instruction) { mutableStateOf(instruction) }
            AlertDialog(onDismissRequest = { controller.confirm(false, revised) }, title = { Text(label("确认手机操作", "Confirm phone operation")) },
                text = { OutlinedTextField(revised, { revised = it }, maxLines = 6) },
                confirmButton = { TextButton(onClick = { controller.confirm(true, revised) }, enabled = revised.isNotBlank()) { Text(label("执行", "Run")) } },
                dismissButton = { TextButton(onClick = { controller.confirm(false, revised) }) { Text(label("取消", "Cancel")) } })
        }
      }
    }
}

@Composable
internal fun ChatIcon(kind: String, color: Color, modifier: Modifier = Modifier, description: String? = null) {
    Canvas(modifier.size(24.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val unit = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, point(x, y), point(x2, y2), 2f * unit, StrokeCap.Round)
        when (kind) {
            "conversations" -> {
                val bubble = Path().apply {
                    moveTo(3 * unit, 3 * unit); lineTo(17 * unit, 3 * unit)
                    lineTo(17 * unit, 13 * unit); lineTo(7 * unit, 13 * unit)
                    lineTo(3 * unit, 17 * unit); close()
                }
                drawPath(bubble, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
                line(8f, 17f, 17f, 17f); line(17f, 17f, 21f, 21f)
                line(21f, 21f, 21f, 7f)
            }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "send" -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f); line(18f, 11f, 12f, 5f) }
            "stop" -> drawRect(color, point(6f, 6f), androidx.compose.ui.geometry.Size(12 * unit, 12 * unit))
            "down" -> { line(7f, 9f, 12f, 14f); line(12f, 14f, 17f, 9f) }
            "waveform" -> {
                line(4f, 10f, 4f, 14f); line(8f, 6f, 8f, 18f); line(12f, 3f, 12f, 21f)
                line(16f, 7f, 16f, 17f); line(20f, 10f, 20f, 14f)
            }
            "mic" -> {
                drawRoundRect(color, point(9f, 3f), androidx.compose.ui.geometry.Size(6 * unit, 12 * unit),
                    androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(2 * unit))
                val path = Path().apply { moveTo(5 * unit, 11 * unit); cubicTo(5 * unit, 22 * unit, 19 * unit, 22 * unit, 19 * unit, 11 * unit) }
                drawPath(path, color, style = Stroke(2 * unit)); line(12f, 19f, 12f, 22f)
            }
            "settings" -> {
                line(4f, 7f, 7f, 7f); line(13f, 7f, 20f, 7f)
                drawCircle(color, 3 * unit, point(10f, 7f), style = Stroke(2 * unit))
                line(4f, 17f, 11f, 17f); line(17f, 17f, 20f, 17f)
                drawCircle(color, 3 * unit, point(14f, 17f), style = Stroke(2 * unit))
            }
            "keyboard" -> {
                drawRoundRect(color, point(2f, 5f), androidx.compose.ui.geometry.Size(20 * unit, 14 * unit),
                    androidx.compose.ui.geometry.CornerRadius(2 * unit), style = Stroke(2 * unit))
                for (x in listOf(6f, 10f, 14f, 18f)) line(x, 9f, x, 10f)
                line(6f, 15f, 18f, 15f)
            }
            "menu" -> { line(4f, 8f, 20f, 8f); line(4f, 16f, 13f, 16f) }
            "more" -> { drawCircle(color, 1.6f * unit, point(12f, 5f)); drawCircle(color, 1.6f * unit, point(12f, 12f)); drawCircle(color, 1.6f * unit, point(12f, 19f)) }
            "compose" -> {
                drawRoundRect(color, point(3f, 5f), androidx.compose.ui.geometry.Size(15 * unit, 16 * unit),
                    androidx.compose.ui.geometry.CornerRadius(4 * unit), style = Stroke(1.8f * unit))
                line(11f, 14f, 20f, 5f); line(20f, 5f, 21f, 4f)
                line(10f, 14f, 11f, 11f); line(10f, 14f, 13f, 13f)
            }
            "copy" -> {
                drawRoundRect(color, point(8f, 8f), androidx.compose.ui.geometry.Size(12 * unit, 12 * unit),
                    androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(1.8f * unit))
                val back = Path().apply { moveTo(16 * unit, 5 * unit); lineTo(16 * unit, 4 * unit); lineTo(5 * unit, 4 * unit); lineTo(4 * unit, 5 * unit); lineTo(4 * unit, 16 * unit); lineTo(5 * unit, 16 * unit); lineTo(8 * unit, 16 * unit) }
                drawPath(back, color, style = Stroke(1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            "speaker" -> {
                val cone = Path().apply { moveTo(4 * unit, 9.5f * unit); lineTo(8 * unit, 9.5f * unit); lineTo(13 * unit, 5 * unit); lineTo(13 * unit, 19 * unit); lineTo(8 * unit, 14.5f * unit); lineTo(4 * unit, 14.5f * unit); close() }
                drawPath(cone, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
                line(16f, 9f, 16f, 15f); line(19f, 6.5f, 19f, 17.5f)
            }
            "refresh" -> {
                drawArc(color, 40f, 290f, false, point(5f, 5f), androidx.compose.ui.geometry.Size(14 * unit, 14 * unit), style = Stroke(1.8f * unit, cap = StrokeCap.Round))
                line(17f, 3.5f, 17.5f, 7.5f); line(17.5f, 7.5f, 13.5f, 7.5f)
            }
            "share" -> {
                drawCircle(color, 2.6f * unit, point(17.5f, 5.5f), style = Stroke(1.8f * unit))
                drawCircle(color, 2.6f * unit, point(6.5f, 12f), style = Stroke(1.8f * unit))
                drawCircle(color, 2.6f * unit, point(17.5f, 18.5f), style = Stroke(1.8f * unit))
                line(8.8f, 10.8f, 15.2f, 6.8f); line(8.8f, 13.2f, 15.2f, 17.2f)
            }
            "pin" -> {
                val head = Path().apply { moveTo(14 * unit, 3.5f * unit); lineTo(20.5f * unit, 10 * unit); lineTo(17.5f * unit, 11 * unit); lineTo(13.5f * unit, 15 * unit); lineTo(13 * unit, 18 * unit); lineTo(6 * unit, 11 * unit); lineTo(9 * unit, 10.5f * unit); lineTo(13 * unit, 6.5f * unit); close() }
                drawPath(head, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round)); line(9.5f, 14.5f, 4f, 20f)
            }
            "pencil" -> {
                val body = Path().apply { moveTo(4 * unit, 20 * unit); lineTo(5.2f * unit, 15 * unit); lineTo(16 * unit, 4.2f * unit); lineTo(19.8f * unit, 8 * unit); lineTo(9 * unit, 18.8f * unit); close() }
                drawPath(body, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round)); line(13.5f, 6.8f, 17.2f, 10.5f)
            }
            "trash" -> {
                line(4.5f, 7f, 19.5f, 7f); line(9f, 7f, 9f, 4.5f); line(9f, 4.5f, 15f, 4.5f); line(15f, 4.5f, 15f, 7f)
                val can = Path().apply { moveTo(6.5f * unit, 7 * unit); lineTo(7.5f * unit, 20 * unit); lineTo(16.5f * unit, 20 * unit); lineTo(17.5f * unit, 7 * unit) }
                drawPath(can, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round)); line(10.2f, 11f, 10.2f, 16f); line(13.8f, 11f, 13.8f, 16f)
            }
            "sparkle" -> {
                val star = Path().apply { moveTo(12 * unit, 3 * unit); cubicTo(12.8f * unit, 8.5f * unit, 15.5f * unit, 11.2f * unit, 21 * unit, 12 * unit)
                    cubicTo(15.5f * unit, 12.8f * unit, 12.8f * unit, 15.5f * unit, 12 * unit, 21 * unit)
                    cubicTo(11.2f * unit, 15.5f * unit, 8.5f * unit, 12.8f * unit, 3 * unit, 12 * unit)
                    cubicTo(8.5f * unit, 11.2f * unit, 11.2f * unit, 8.5f * unit, 12 * unit, 3 * unit); close() }
                drawPath(star, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
            }
            "wifi" -> {
                for (r in listOf(4f, 8f, 12f)) drawArc(color, 225f, 90f, false, point(12f - r, 19f - r), androidx.compose.ui.geometry.Size(2 * r * unit, 2 * r * unit), style = Stroke(1.8f * unit, cap = StrokeCap.Round))
                drawCircle(color, 1.3f * unit, point(12f, 19f))
            }
            "sun" -> {
                drawCircle(color, 4f * unit, point(12f, 12f), style = Stroke(1.8f * unit))
                for (a in 0 until 8) { val t = Math.toRadians(a * 45.0); line(12f + 7f * Math.cos(t).toFloat(), 12f + 7f * Math.sin(t).toFloat(), 12f + 9.5f * Math.cos(t).toFloat(), 12f + 9.5f * Math.sin(t).toFloat()) }
            }
            "phone" -> {
                drawRoundRect(color, point(6.5f, 2.5f), androidx.compose.ui.geometry.Size(11 * unit, 19 * unit), androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(1.8f * unit))
                line(10.5f, 18.5f, 13.5f, 18.5f)
            }
            "clock" -> { drawCircle(color, 9f * unit, point(12f, 12f), style = Stroke(1.8f * unit)); line(12f, 7f, 12f, 12f); line(12f, 12f, 15.5f, 14f) }
            "info" -> { drawCircle(color, 9f * unit, point(12f, 12f), style = Stroke(1.8f * unit)); line(12f, 11f, 12f, 16.5f); drawCircle(color, 1.1f * unit, point(12f, 7.8f)) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "chevron" -> { line(9f, 5f, 15f, 12f); line(15f, 12f, 9f, 19f) }
            "camera" -> {
                drawRoundRect(color, point(3f, 7f), androidx.compose.ui.geometry.Size(18 * unit, 13 * unit),
                    androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(1.8f * unit))
                line(8f, 7f, 9.5f, 4f); line(9.5f, 4f, 14.5f, 4f); line(14.5f, 4f, 16f, 7f)
                drawCircle(color, 3.5f * unit, point(12f, 13.5f), style = Stroke(1.8f * unit))
            }
            "photo" -> {
                drawRoundRect(color, point(3f, 4f), androidx.compose.ui.geometry.Size(18 * unit, 16 * unit),
                    androidx.compose.ui.geometry.CornerRadius(3 * unit), style = Stroke(1.8f * unit))
                drawCircle(color, 1.8f * unit, point(9f, 10f), style = Stroke(1.8f * unit))
                line(4f, 18f, 10f, 13f); line(10f, 13f, 14f, 16f); line(14f, 16f, 20f, 11f)
            }
            "file" -> {
                val page = Path().apply {
                    moveTo(6 * unit, 3 * unit); lineTo(14 * unit, 3 * unit); lineTo(19 * unit, 8 * unit)
                    lineTo(19 * unit, 21 * unit); lineTo(6 * unit, 21 * unit); close()
                }
                drawPath(page, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
                line(14f, 3f, 14f, 8f); line(14f, 8f, 19f, 8f); line(12.5f, 17f, 12.5f, 11f)
                line(10f, 13.5f, 12.5f, 11f); line(15f, 13.5f, 12.5f, 11f)
            }
            "bolt" -> {
                val bolt = Path().apply {
                    moveTo(13 * unit, 2 * unit); lineTo(5 * unit, 13 * unit); lineTo(11 * unit, 13 * unit)
                    lineTo(10 * unit, 22 * unit); lineTo(19 * unit, 10 * unit); lineTo(13 * unit, 10 * unit); close()
                }
                drawPath(bolt, color, style = Stroke(1.8f * unit, join = StrokeJoin.Round))
            }
        }
    }
}
