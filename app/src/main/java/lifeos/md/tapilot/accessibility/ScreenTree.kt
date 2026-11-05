package lifeos.md.tapilot.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads the active window as text and acts on its nodes. This is the fast path: no screenshot
 * and no vision model. Node ids are only valid for the most recent read; an action re-checks
 * the live tree and refuses to act when the node is no longer there.
 */
internal object ScreenTree {
    private const val MAX_NODES = 220
    private const val MAX_TEXT = 80
    private const val SETTLE_MS = 600L

    private data class Entry(val id: Int, val bounds: Rect, val text: String, val editable: Boolean)

    @Volatile private var last: List<Entry> = emptyList()

    private fun collect(root: AccessibilityNodeInfo): Pair<List<Entry>, JSONArray> {
        val entries = ArrayList<Entry>()
        val out = JSONArray()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (entries.size >= MAX_NODES || depth > 40) return
            if (node.isVisibleToUser) {
                val text = (node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }).take(MAX_TEXT)
                val interactive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty && (text.isNotBlank() || interactive)) {
                    val id = entries.size
                    entries += Entry(id, bounds, text, node.isEditable)
                    val item = JSONObject().put("i", id)
                    if (text.isNotBlank()) item.put("t", text)
                    if (node.isClickable) item.put("k", 1)
                    if (node.isEditable) item.put("e", 1)
                    if (node.isScrollable) item.put("s", 1)
                    if (node.isCheckable) item.put("x", if (node.isChecked) 1 else 0)
                    if (!node.isEnabled) item.put("d", 1)
                    item.put("b", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    out.put(item)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return entries to out
    }

    fun read(service: MidsceneAccessibilityService): JSONObject {
        val root = service.rootInActiveWindow ?: error("No readable window on screen")
        val (entries, nodes) = collect(root)
        last = entries
        val metrics = service.resources.displayMetrics
        return JSONObject().put("app", root.packageName?.toString().orEmpty())
            .put("screen", JSONArray(listOf(metrics.widthPixels, metrics.heightPixels)))
            .put("nodes", nodes).put("count", entries.size)
            .put("legend", "i=id t=text k=clickable e=editable s=scrollable x=checked(1/0) d=disabled b=[l,t,r,b]")
    }

    private fun resolve(service: MidsceneAccessibilityService, id: Int): Pair<Entry, AccessibilityNodeInfo?> {
        val entry = last.getOrNull(id) ?: error("Unknown node id $id; call read_screen first")
        val root = service.rootInActiveWindow ?: error("No readable window on screen")
        var live: AccessibilityNodeInfo? = null
        var exists = false
        fun visit(node: AccessibilityNodeInfo) {
            if (exists && live != null) return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds == entry.bounds) {
                exists = true
                if (live == null && (!entry.editable || node.isEditable)) live = node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it) }
        }
        visit(root)
        check(exists) { "The screen changed and node $id is gone; call read_screen again" }
        return entry to live
    }

    private fun gesture(service: MidsceneAccessibilityService, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        service.physicalGesture(x1, y1, x2, y2, duration) { result -> failure = result.exceptionOrNull(); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "Gesture timed out" }
        failure?.let { throw it }
    }

    fun act(service: MidsceneAccessibilityService, args: JSONObject): JSONObject {
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        when (val action = args.getString("action")) {
            "tap", "long_press" -> {
                val (entry, _) = resolve(service, args.getInt("id"))
                gesture(service, entry.bounds.exactCenterX(), entry.bounds.exactCenterY(),
                    entry.bounds.exactCenterX(), entry.bounds.exactCenterY(), if (action == "tap") 50L else 800L)
            }
            "type" -> {
                val (_, live) = resolve(service, args.getInt("id"))
                val node = checkNotNull(live) { "Node ${args.getInt("id")} is not an editable field" }
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.getString("text"))
                })
                check(ok) { "The field rejected the text" }
            }
            "scroll_down" -> gesture(service, w / 2, h * 0.75f, w / 2, h * 0.30f, 350)
            "scroll_up" -> gesture(service, w / 2, h * 0.30f, w / 2, h * 0.75f, 350)
            "swipe_left" -> gesture(service, w * 0.80f, h / 2, w * 0.20f, h / 2, 300)
            "swipe_right" -> gesture(service, w * 0.20f, h / 2, w * 0.80f, h / 2, 300)
            "back" -> check(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) { "Back was rejected" }
            "home" -> check(service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) { "Home was rejected" }
            else -> error("Unsupported screen action: $action")
        }
        Thread.sleep(SETTLE_MS)
        return read(service)
    }
}
