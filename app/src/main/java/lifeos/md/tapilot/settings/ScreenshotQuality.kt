package lifeos.md.tapilot.settings

import android.content.Context

/** Size of the screenshots sent to the vision model: "fast" shrinks them by 1.5x, "sharp" sends full size. */
object ScreenshotQuality {
    private const val KEY = "screenshot.quality"
    val choices = listOf("fast", "sharp")

    fun get(context: Context): String = context.getSharedPreferences("midscene-preferences", Context.MODE_PRIVATE)
        .getString(KEY, null)?.takeIf { it in choices } ?: "fast"

    fun set(context: Context, value: String) {
        require(value in choices)
        context.getSharedPreferences("midscene-preferences", Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }

    fun shrinkFactor(context: Context): String = if (get(context) == "fast") "1.5" else "1"
}
