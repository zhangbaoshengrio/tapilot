package lifeos.md.tapilot.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lifeos.md.tapilot.conversation.NativeConversationCoordinator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatGptSettingsSection(coordinator: NativeConversationCoordinator, zh: Boolean) {
    val connection = coordinator.chatGpt
    val context = LocalContext.current
    fun label(a: String, b: String) = if (zh) a else b
    var accountPicker by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    val busy = connection.busy || coordinator.settingsBusy
    Text(label("使用 ChatGPT 套餐处理对话、任务规划和手机操作，无需另填模型 API Key。",
        "Use your ChatGPT plan for conversation, planning, and phone operations. No separate model API Key is needed."), fontSize = 13.sp)
    if (connection.accounts.isNotEmpty()) {
        TextButton(onClick = { accountPicker = true }, enabled = !busy) {
            Text(connection.accounts.firstOrNull { it.id == connection.activeId }?.label ?: label("选择账号", "Choose account"))
        }
        if (connection.accounts.any { it.id == connection.activeId && it.signedIn }) {
            ExposedDropdownMenuBox(expanded = modelPicker, onExpandedChange = { open ->
                if (!open) modelPicker = false
                else if (!busy) { modelPicker = true; connection.refreshModels() }
            }) {
                SettingsField(
                    value = connection.models.firstOrNull { it.id == connection.model }?.label
                        ?: connection.model.ifBlank { label("选择模型", "Choose model") },
                    onValueChange = {}, readOnly = true, enabled = !busy, singleLine = true,
                    label = { Text(label("模型", "Model")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelPicker) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(), shape = MaterialTheme.shapes.medium,
                )
                ExposedDropdownMenu(expanded = modelPicker, onDismissRequest = { modelPicker = false }) {
                    if (connection.busy) {
                        DropdownMenuItem(text = { Text(label("正在加载模型…", "Loading models…")) }, onClick = {}, enabled = false)
                    } else if (connection.models.isEmpty()) {
                        DropdownMenuItem(text = { Text(label("未能加载模型，点击重试", "Could not load models. Tap to retry")) },
                            onClick = connection::refreshModels)
                    } else connection.models.forEach { model ->
                        DropdownMenuItem(text = { Text(model.label) }, enabled = !busy,
                            onClick = { connection.selectModel(model.id); modelPicker = false })
                    }
                }
            }
            Row {
                TextButton(onClick = coordinator::testChatGpt, enabled = !busy && connection.ready) { Text(label("检查连接", "Check connection")) }
                TextButton(onClick = connection::signOut, enabled = !busy) { Text(label("退出登录", "Sign out")) }
            }
        }
        TextButton(onClick = { connection.signIn(connection.activeId) }, enabled = !busy) { Text(label("重新登录当前账号", "Sign in again")) }
    }
    Button(onClick = { connection.signIn() }, enabled = !busy) { Text("Continue with ChatGPT") }
    if (connection.signingIn) TextButton(onClick = connection::cancelLogin) { Text(label("取消登录", "Cancel sign-in")) }
    if (connection.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (connection.status.isNotBlank()) Text(connection.status, fontSize = 13.sp)
    if (coordinator.settingsSection == "chatgpt" && coordinator.settingsStatus.isNotBlank()) Text(coordinator.settingsStatus, fontSize = 13.sp)
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }) {
        Text(label("管理 ChatGPT 用量与授权", "Manage ChatGPT usage & access"))
    }
    if (accountPicker) AlertDialog(onDismissRequest = { accountPicker = false },
        title = { Text(label("选择账号", "Choose account")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            connection.accounts.forEach { account ->
                TextButton(onClick = { connection.selectAccount(account.id); accountPicker = false }) { Text(account.label) }
            }
        } }, confirmButton = { TextButton(onClick = { accountPicker = false }) { Text(label("关闭", "Close")) } })
}
