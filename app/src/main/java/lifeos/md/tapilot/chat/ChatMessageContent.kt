package lifeos.md.tapilot.chat

import lifeos.md.tapilot.AppText.text as appText
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import lifeos.md.tapilot.theme.LocalThemePalette
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(message: ChatMessage, zh: Boolean, controller: ChatController, busy: Boolean) {
    val user = message.role == "user"
    val colors = MaterialTheme.colorScheme
    val palette = LocalThemePalette.current
    val bubbleColor = Color(if (user) palette.bubbleStart else palette.assistantBubble)
    // ChatGPT style: user messages in a soft gray pill-bubble, assistant replies as plain text without a bubble.
    val bubbleShape = RoundedCornerShape(22.dp)
    val context = LocalContext.current
    var menu by remember(message.id) { mutableStateOf(false) }
    var details by remember(message.id) { mutableStateOf(false) }
    var rerun by remember(message.id) { mutableStateOf(false) }
    var reportPicker by remember(message.id) { mutableStateOf(false) }
    val failed = message.status == "failed" || message.status == "stopped"
    val quotaFailure = !user && failed && message.text.contains("Arrearage", true)
    fun copy() {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tapilot", message.text))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val bubbleWidth = maxWidth * if (user) .82f else 1f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = bubbleWidth)
            .then(if (user) Modifier.background(bubbleColor, bubbleShape) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(horizontal = if (user) 16.dp else 4.dp, vertical = if (user) 10.dp else 4.dp)) {
            val text = if (!user && message.status == "running") (if (zh) "正在处理…" else "Working…")
                else if (quotaFailure && !details) (if (zh) "模型账户欠费，请充值或在设置中选择可用的模型 API。" else "The model account is overdue. Check your model settings.")
                else message.text.ifBlank { if (zh) "任务已结束" else "Task finished" }
            if (user) Text(text, color = Color(palette.bubbleText), fontSize = 17.sp, lineHeight = 24.sp)
            else ReplyContentView(text, message.id, zh)
            if (!user) message.task?.let { TaskDetailsView(it, zh) }
            message.attachment?.let {
                TextButton(onClick = { controller.command("preview", id = message.entryId) }) {
                    Text((if (it.name in listOf("当前屏幕", "Current screen")) appText("引用：", "Reference: ") else appText("附件：", "Attachment: ")) + it.name, color = if (user) Color(palette.bubbleText) else colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!user && message.status != "running") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tint = colors.onSurfaceVariant
                    if (message.text.isNotBlank()) IconButton(onClick = { controller.readReply(message) }, modifier = Modifier.size(40.dp),
                        enabled = !controller.voiceState.active && !controller.recording && !controller.transcribing) {
                        ChatIcon(if (controller.speakingId == message.id) "stop" else "speaker", if (controller.speakingId == message.id) colors.primary else tint,
                            Modifier.size(20.dp), if (controller.speakingId == message.id) (if (zh) "停止播报" else "Stop voice") else (if (zh) "朗读" else "Read aloud"))
                    }
                    IconButton(onClick = { copy() }, modifier = Modifier.size(40.dp)) {
                        ChatIcon("copy", tint, Modifier.size(20.dp), if (zh) "复制" else "Copy")
                    }
                    if (message.text.isNotBlank()) IconButton(onClick = { shareText(context, message.text) }, modifier = Modifier.size(40.dp)) {
                        ChatIcon("share", tint, Modifier.size(20.dp), if (zh) "分享" else "Share")
                    }
                    IconButton(onClick = { if (failed) controller.command("retry", id = message.entryId) else rerun = true },
                        enabled = !busy, modifier = Modifier.size(40.dp)) {
                        ChatIcon("refresh", tint, Modifier.size(20.dp), if (zh) (if (failed) "重试" else "重新执行") else "Retry")
                    }
                    if (message.reportId.isNotEmpty()) TextButton(onClick = {
                        if (message.reports.size > 1) reportPicker = true else controller.command("report", id = message.reportId)
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(if (zh) "执行记录 →" else "Activity →", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tint)
                    }
                }
            }
            if (quotaFailure) TextButton(onClick = { details = !details }) { Text(if (details) "收起详情" else "查看详情") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (zh) "复制" else "Copy") }, onClick = { copy(); menu = false })
                if (user) DropdownMenuItem(text = { Text(if (zh) "编辑后重发" else "Edit and resend") }, enabled = !busy,
                    onClick = { menu = false; controller.command("editMessage", id = message.entryId) })
            }
        }
    }
    }
    if (reportPicker) AlertDialog(onDismissRequest = { reportPicker = false },
        title = { Text(if (zh) "执行记录" else "Activity") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(message.reports.size, key = { message.reports[it].id }) { index ->
                    val report = message.reports[index]
                    TextButton(onClick = { reportPicker = false; controller.command("report", id = report.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${index + 1}. ${report.title.ifBlank { if (zh) "执行记录" else "Execution record" }}",
                            modifier = Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { reportPicker = false }) { Text(if (zh) "关闭" else "Close") } })
    if (rerun) AlertDialog(onDismissRequest = { rerun = false }, title = { Text(if (zh) "重新执行？" else "Run again?") },
        text = { Text(if (zh) "将重新发送原消息和附件，手机操作可能再次执行。" else "Resend the original message and attachment. Phone actions may run again.") },
        confirmButton = { TextButton(onClick = { rerun = false; controller.command("retry", id = message.entryId) }) { Text(if (zh) "执行" else "Run") } },
        dismissButton = { TextButton(onClick = { rerun = false }) { Text(if (zh) "取消" else "Cancel") } })
}

@Composable
internal fun TaskProgressView(progress: ChatProgress, controller: ChatController, zh: Boolean) {
    var seconds by remember(progress.startedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(progress.startedAt) {
        while (true) { seconds = ((System.currentTimeMillis() - progress.startedAt) / 1000).coerceAtLeast(0); delay(1000) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress.title + " · ${seconds}s", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { controller.command("takeOver") }, enabled = !progress.stopping) {
                Text(if (zh) "我来处理" else "Take over", fontSize = 13.sp)
            }
            TextButton(onClick = { controller.command("stop") }, enabled = !progress.stopping, contentPadding = PaddingValues(0.dp)) {
                Text(if (zh) "停止" else "Stop", fontSize = 13.sp)
            }
        }
        Text(if (zh) "继续输入或长按说话可补充要求；切换请说“换个任务”" else "Type or hold to add instructions; say “new task” to switch",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress.detail.isNotBlank()) Text(progress.detail, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress.operation.isNotBlank()) Text((if (zh) "操作 ${progress.step}" else "Operation ${progress.step}") + " · ${progress.operation}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AttachmentThumbnail(preview: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, preview) {
        value = withContext(Dispatchers.IO) { runCatching {
            val bytes = Base64.decode(preview.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), appText("附件预览", "Attachment preview"), modifier.size(48.dp)) }
}

@Composable
internal fun AttachmentPreview(attachment: ChatAttachment, zh: Boolean, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(attachment.name, Modifier.weight(1f).padding(vertical = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = close) { Text(if (zh) "关闭" else "Close") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                    if (attachment.pageCount > attachment.images.size) item { Text(appText("共 ${attachment.pageCount} 页 · 当前提供前 ${attachment.images.size} 页", "${attachment.pageCount} pages · First ${attachment.images.size} pages available"), fontSize = 12.sp) }
                    attachment.images.forEachIndexed { index, image -> item { PreviewImage(image, index + 1) } }
                    if (attachment.text.isNotEmpty()) item { SelectionContainer { Text(attachment.text, fontSize = 15.sp, lineHeight = 23.sp) } }
                }
            }
        }
    }
}

@Composable
private fun PreviewImage(data: String, page: Int) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, data) {
        value = withContext(Dispatchers.IO) { runCatching {
            val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() }
    }
    bitmap?.let { Image(it.asImageBitmap(), appText("预览 $page", "Preview $page"), Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).padding(vertical = 8.dp)) }
}
