package lifeos.md.tapilot.chat

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentBlue = Color(0xFF3B6CE6)

/** Shares plain text through the system sheet. */
internal fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** ChatGPT-style side drawer: conversation list, a blue "new chat" pill and a settings button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatDrawer(controller: ChatController, visible: Boolean, onClose: () -> Unit) {
    val state = controller.state
    val zh = state.chinese
    val colors = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf<ChatConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<ChatConversation?>(null) }
    BackHandler(enabled = visible, onBack = onClose)
    renaming?.let { target ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(if (zh) "重命名对话" else "Rename chat") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { controller.command("renameConversation", text = renameText, id = target.id); renaming = null }) { Text(if (zh) "保存" else "Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(if (zh) "取消" else "Cancel") } })
    }
    deleting?.let { target ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(if (zh) "删除对话？" else "Delete chat?") },
            text = { Text(if (zh) "「${target.title}」及其全部消息会被删除，无法恢复。" else "\"${target.title}\" and all its messages will be deleted. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { controller.command("deleteConversation", id = target.id); deleting = null }) { Text(if (zh) "删除" else "Delete", color = Color(0xFFE5173F)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(if (zh) "取消" else "Cancel") } })
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose))
    }
    AnimatedVisibility(visible, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(.8f).background(colors.background).statusBarsPadding().navigationBarsPadding()) {
            Text(if (zh) "对话" else "Chats", Modifier.padding(start = 20.dp, top = 22.dp, bottom = 14.dp),
                fontSize = 28.sp, fontWeight = FontWeight.Medium, color = colors.onBackground)
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.conversations, key = { it.id }) { conversation ->
                    val selected = conversation.id == state.activeConversationId
                    var menu by remember(conversation.id) { mutableStateOf(false) }
                    Box {
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected) colors.surfaceVariant else Color.Transparent)
                            .combinedClickable(onClick = { onClose(); controller.command("select", id = conversation.id) },
                                onLongClick = { menu = true })
                            .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(conversation.title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onBackground)
                            if (conversation.pinned) ChatIcon("pin", colors.onSurfaceVariant, Modifier.size(18.dp), if (zh) "已置顶" else "Pinned")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.width(260.dp),
                            shape = RoundedCornerShape(28.dp), containerColor = colors.surface, tonalElevation = 0.dp, shadowElevation = 14.dp) {
                            DropdownMenuItem(text = { Text(if (conversation.pinned) (if (zh) "取消置顶" else "Unpin") else (if (zh) "置顶" else "Pin"), fontSize = 17.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = { ChatIcon("pin", colors.onSurface, Modifier.size(24.dp)) }, modifier = Modifier.heightIn(min = 56.dp),
                                onClick = { menu = false; controller.command("pinConversation", id = conversation.id) })
                            DropdownMenuItem(text = { Text(if (zh) "重命名" else "Rename", fontSize = 17.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = { ChatIcon("pencil", colors.onSurface, Modifier.size(24.dp)) }, modifier = Modifier.heightIn(min = 56.dp),
                                onClick = { menu = false; renameText = conversation.title; renaming = conversation })
                            DropdownMenuItem(text = { Text(if (zh) "删除" else "Delete", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE5173F)) },
                                leadingIcon = { ChatIcon("trash", Color(0xFFE5173F), Modifier.size(24.dp)) }, modifier = Modifier.heightIn(min = 56.dp),
                                enabled = !state.running, onClick = { menu = false; deleting = conversation })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(AccentBlue)
                    .clickable(enabled = state.ready && !state.running) { onClose(); controller.command("new") }
                    .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChatIcon("compose", Color.White, Modifier.size(24.dp))
                    Text(if (zh) "新对话" else "New chat", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(56.dp).shadow(4.dp, CircleShape).background(colors.surface, CircleShape)
                    .clickable { onClose(); controller.command("settings") }, contentAlignment = Alignment.Center) {
                    ChatIcon("settings", colors.onSurface, Modifier.size(24.dp), if (zh) "设置" else "Settings")
                }
            }
        }
    }
}

/** ChatGPT-style rounded menu card for the "⋮" button, limited to actions Tapilot really has. */
@Composable
internal fun ChatMenuCard(controller: ChatController, expanded: Boolean, onDismiss: () -> Unit) {
    val state = controller.state
    val zh = state.chinese
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val title = state.conversations.firstOrNull { it.id == state.activeConversationId }?.title.orEmpty()
    fun item(icon: String, text: String, enabled: Boolean = true, click: () -> Unit) = @Composable {
        DropdownMenuItem(text = { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { ChatIcon(icon, colors.onSurface, Modifier.size(24.dp)) }, enabled = enabled,
            modifier = Modifier.heightIn(min = 56.dp), onClick = { onDismiss(); click() })
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(300.dp),
        shape = RoundedCornerShape(28.dp), containerColor = colors.surface, tonalElevation = 0.dp, shadowElevation = 14.dp) {
        Text(title.ifBlank { if (zh) "当前对话" else "Conversation" }, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            color = colors.onSurfaceVariant, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item("share", if (zh) "分享对话" else "Share chat", state.messages.isNotEmpty()) {
            shareText(context, state.messages.joinToString("\n\n") { (if (it.role == "user") (if (zh) "我：" else "Me: ") else (if (zh) "助手：" else "Assistant: ")) + it.text })
        }()
        item("compose", if (zh) "新对话" else "New chat", state.ready && !state.running) { controller.command("new") }()
        item("settings", if (zh) "设置" else "Settings") { controller.command("settings") }()
        if (state.needsAccessibility) item("bolt", if (zh) "设备控制设置" else "Device control settings") { controller.command("accessibility") }()
    }
}
