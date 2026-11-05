package lifeos.md.tapilot.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import lifeos.md.tapilot.voice.voicePresets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePicker(provider: String, speaker: String, zh: Boolean, enabled: Boolean, onSelect: (String) -> Unit) {
    val voices = remember(provider) { voicePresets(provider) }
    var expanded by remember(provider) { mutableStateOf(false) }
    var custom by remember(provider) { mutableStateOf(false) }
    var customId by remember(provider) { mutableStateOf("") }
    fun label(a: String, b: String) = if (zh) a else b
    val selected = voices.firstOrNull { it.id == speaker }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        SettingsField(
            value = selected?.label(zh) ?: label("自定义音色", "Custom voice"), onValueChange = {},
            readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label("音色", "Voice")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier.fillMaxWidth().menuAnchor(), shape = MaterialTheme.shapes.medium,
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            voices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.label(zh)) }, onClick = { onSelect(voice.id); expanded = false })
            }
            DropdownMenuItem(text = { Text(label("自定义音色…", "Custom voice…")) }, onClick = {
                customId = speaker; expanded = false; custom = true
            })
        }
    }
    if (custom) AlertDialog(onDismissRequest = { custom = false },
        title = { Text(label("自定义音色", "Custom voice")) },
        text = { SettingsField(customId, { customId = it }, singleLine = true,
            label = { Text(label("音色 ID", "Voice ID")) }) },
        confirmButton = { TextButton(enabled = customId.trim().matches(Regex("[A-Za-z0-9_-]{1,160}")), onClick = {
            onSelect(customId.trim()); custom = false
        }) { Text(label("确定", "OK")) } },
        dismissButton = { TextButton(onClick = { custom = false }) { Text(label("取消", "Cancel")) } })
}
