package lifeos.md.tapilot.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ReplyContentView(text: String, messageId: String, zh: Boolean) {
    val parts = remember(text) { replyParts(text) }
    parts.forEachIndexed { index, part ->
        if (part.formattedJson == null) {
            SelectionContainer { Text(part.text.trim(), fontSize = 17.sp, lineHeight = 26.sp) }
        } else key(messageId, index, part.text) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val label = part.operationCount?.let { if (zh) "操作记录 · $it 条" else "Operation records · $it" }
                ?: if (zh) "JSON 详情" else "JSON details"
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text("$label · ${if (zh) (if (expanded) "收起" else "展开") else (if (expanded) "Collapse" else "Expand")}")
            }
            AnimatedVisibility(expanded) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                    SelectionContainer {
                        Text(part.formattedJson, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp))
                    }
                }
            }
        }
    }
}
