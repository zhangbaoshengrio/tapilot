package lifeos.md.tapilot.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** ChatGPT-style settings input: a soft filled field with no outline until it is focused. */
@Composable
internal fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    @Suppress("UNUSED_PARAMETER") shape: Shape = RoundedCornerShape(14.dp),
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
        label = label, placeholder = placeholder, trailingIcon = trailingIcon, supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = colors.surfaceVariant, focusedContainerColor = colors.surfaceVariant,
            disabledContainerColor = colors.surfaceVariant,
            unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
            focusedBorderColor = colors.onSurface.copy(alpha = .35f),
        ),
    )
}
