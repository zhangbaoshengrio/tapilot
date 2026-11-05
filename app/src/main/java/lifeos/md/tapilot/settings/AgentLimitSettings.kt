package lifeos.md.tapilot.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lifeos.md.tapilot.conversation.AgentLimits
import lifeos.md.tapilot.conversation.NativeConversationCoordinator

@Composable
internal fun AgentLimitSettings(coordinator: NativeConversationCoordinator, zh: Boolean) {
    val limits = AgentLimits.from(coordinator.data)
    fun label(a: String, b: String) = if (zh) a else b
    var editing by remember { mutableStateOf<String?>(null) }
    listOf("phone" to label("手机操作次数上限", "Phone operation limit"), "turns" to label("对话轮次上限", "Conversation turn limit")).forEach { (key, title) ->
        val value = if (key == "phone") limits.phoneOperations else limits.modelTurns
        Row(Modifier.fillMaxWidth().clickable { editing = key }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 16.sp)
            Text(value?.toString() ?: label("无限制", "Unlimited"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(label("每次重试重新计时、重新计算次数。一次手机调用可以执行多个点击或滑动。", "Each retry resets elapsed time and counts. One phone call can include multiple taps or swipes."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    editing?.let { key ->
        val current = if (key == "phone") limits.phoneOperations else limits.modelTurns
        var enabled by remember(key) { mutableStateOf(current != null) }
        var number by remember(key) { mutableStateOf((current ?: 100).toString()) }
        val parsed = number.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(if (key == "phone") label("手机操作次数上限", "Phone operation limit") else label("对话轮次上限", "Conversation turn limit")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label("启用上限", "Enable limit"), Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                if (enabled) SettingsField(number, { number = it }, singleLine = true,
                    label = { Text(label("上限数目", "Maximum count")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsed == null, supportingText = { if (parsed == null) Text(label("请输入 1–2147483647 的整数", "Enter an integer from 1 to 2147483647")) })
                else Text(label("关闭后不限制次数，任务仍可手动停止。", "With the limit disabled, you can still stop the task manually."), fontSize = 13.sp)
            }
        }, confirmButton = {
            TextButton(enabled = !enabled || parsed != null, onClick = {
                val value = if (enabled) parsed else null
                val changed = if (key == "phone") limits.copy(phoneOperations = value) else limits.copy(modelTurns = value)
                coordinator.preference("agentLimits", changed.json()); editing = null
            }) { Text(label("保存", "Save")) }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text(label("取消", "Cancel")) } })
    }
}
