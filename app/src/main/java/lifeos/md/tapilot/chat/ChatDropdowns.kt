package lifeos.md.tapilot.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import lifeos.md.tapilot.conversation.ExecutionMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationDropdown(controller: ChatController, expanded: Boolean, onDismiss: () -> Unit) {
    val state = controller.state
    fun label(zh: String, en: String) = if (state.chinese) zh else en
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp).heightIn(max = 400.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 3.dp) {
        DropdownMenuItem(text = { Text(label("新对话", "New conversation")) }, enabled = state.ready,
            leadingIcon = { ChatIcon("plus", MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = { onDismiss(); controller.command("new") })
        if (state.advancedMode) {
            DropdownMenuItem(text = { Text("YAML") }, onClick = { onDismiss(); controller.command("advanced") })
        }
        if (state.conversations.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        state.conversations.forEach { conversation ->
            val selected = conversation.id == state.activeConversationId
            DropdownMenuItem(text = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                trailingIcon = if (selected) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
                onClick = { onDismiss(); controller.command("select", id = conversation.id) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(controller: ChatController, onDismiss: () -> Unit) {
    val state = controller.state
    val zh = state.chinese
    val colors = MaterialTheme.colorScheme
    var pickingMode by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    ChatIcon("close", colors.onSurface, Modifier.size(22.dp), if (zh) "关闭" else "Close")
                }
                Text(if (pickingMode) (if (zh) "执行确认" else "Execution confirmation") else (if (zh) "添加内容" else "Add context"),
                    style = MaterialTheme.typography.titleMedium)
            }
            if (!pickingMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Triple("camera", "camera", if (zh) "拍摄" else "Camera"),
                        Triple("photo", "photo", if (zh) "照片" else "Photos"),
                        Triple("file", "file", if (zh) "文件" else "Files")).forEach { (kind, icon, title) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(colors.surface)
                            .border(1.dp, colors.outlineVariant, RoundedCornerShape(18.dp))
                            .clickable { onDismiss(); controller.pick(kind) }.padding(vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChatIcon(icon, colors.onSurface, Modifier.size(26.dp))
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                val modeEnabled = state.ready && !state.running && !controller.recording
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)
                    .clickable(enabled = modeEnabled) { pickingMode = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                        ChatIcon("bolt", colors.onSurface, Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (zh) "执行确认" else "Execution confirmation", style = MaterialTheme.typography.bodyLarge)
                        Text(state.executionMode.title(zh), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    ChatIcon("chevron", colors.onSurfaceVariant, Modifier.size(20.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surface)) {
                    ExecutionMode.entries.forEachIndexed { index, mode ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                        Row(Modifier.fillMaxWidth().clickable { onDismiss(); controller.command("executionMode", mode.wireValue) }
                            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(mode.title(zh), style = MaterialTheme.typography.bodyLarge)
                                Text(when (mode) {
                                    ExecutionMode.YOLO -> if (zh) "直接执行，不弹出操作确认" else "Run without operation confirmation"
                                    ExecutionMode.CRITICAL -> if (zh) "发送、付款、删除等操作前确认" else "Confirm sending, paying and deleting"
                                    ExecutionMode.ALWAYS -> if (zh) "每次手机操作前确认" else "Confirm each phone operation"
                                }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            if (mode == state.executionMode) Text("✓", color = colors.primary, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}
