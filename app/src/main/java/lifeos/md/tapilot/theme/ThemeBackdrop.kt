package lifeos.md.tapilot.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The classic settings canvas uses quiet purple and rose circles behind its cards. */
@Composable
internal fun ThemeBackdrop(dark: Boolean, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalThemePalette.current
    val classic = lifeos.md.tapilot.AppTheme.paletteId(androidx.compose.ui.platform.LocalContext.current) == "classic"
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).drawBehind {
        // Same soft radial glow as the chat page on the light ChatGPT-style theme.
        if (!dark) drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFEFEFEF)), center = Offset(size.width / 2f, size.height * .42f), radius = size.height * .8f))
    }.clipToBounds()) {
        if (classic) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(palette.softStrong),
                120.dp.toPx(), Offset(56.dp.toPx(), 24.dp.toPx()))
            drawCircle(Color(palette.softAlt),
                110.dp.toPx(), Offset(size.width - 26.dp.toPx(), 158.dp.toPx()))
        }
        content()
    }
}
