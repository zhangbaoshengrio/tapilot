package lifeos.md.tapilot.accessibility

import android.accessibilityservice.AccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Saved phone routines: explored once by the model, then replayed natively with no model between
 * steps. Every tap re-checks the foreground app, so a replay stops instead of tapping the wrong app.
 */
internal object Routines {
    private fun directory(service: MidsceneAccessibilityService) = File(service.filesDir, "routines").apply { mkdirs() }
    private fun file(service: MidsceneAccessibilityService, name: String) = File(directory(service), RoutineSteps.fileName(name) + ".json")

    private fun screenSize(service: MidsceneAccessibilityService) =
        service.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    fun list(service: MidsceneAccessibilityService): JSONObject {
        val routines = JSONArray()
        directory(service).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
            runCatching { routines.put(RoutineSteps.summary(JSONObject(f.readText()))) }
        }
        return JSONObject().put("routines", routines)
    }

    fun delete(service: MidsceneAccessibilityService, name: String): Boolean = file(service, name).delete()

    fun save(service: MidsceneAccessibilityService, request: JSONObject): JSONObject {
        val name = request.getString("name")
        val steps = request.getJSONArray("steps")
        val (w, h) = screenSize(service)
        val expanded = RoutineSteps.validate(steps, w, h)
        val routine = JSONObject().put("name", name.trim()).put("description", request.optString("description"))
            .put("package", request.optString("package")).put("checkpoint", request.optString("checkpoint"))
            .put("screen", JSONArray(listOf(w, h))).put("steps", steps).put("runs", 0)
            .put("savedAt", System.currentTimeMillis())
        file(service, name).writeText(routine.toString())
        return JSONObject().put("saved", name.trim()).put("expandedSteps", expanded)
    }

    fun run(service: MidsceneAccessibilityService, name: String, stopped: () -> Boolean): JSONObject {
        val target = file(service, name)
        check(target.exists()) { "No routine named '$name'" }
        val routine = JSONObject(target.readText())
        val (w, h) = screenSize(service)
        val saved = routine.optJSONArray("screen")
        check(saved == null || (saved.getInt(0) == w && saved.getInt(1) == h)) {
            "This routine was saved on a ${saved?.getInt(0)}x${saved?.getInt(1)} screen; this screen is ${w}x$h"
        }
        val started = System.currentTimeMillis()
        var expected: String? = null
        var executed = 0
        fun foreground() = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        fun pause(ms: Long) {
            var left = ms
            while (left > 0) { check(!stopped()) { "Stopped" }; val slice = minOf(left, 200L); Thread.sleep(slice); left -= slice }
        }
        fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            service.physicalGesture(x1, y1, x2, y2, duration) { failure = it.exceptionOrNull(); done.countDown() }
            check(done.await(10, TimeUnit.SECONDS)) { "Gesture timed out" }
            failure?.let { throw it }
        }
        fun requireForeground(index: Int) {
            val want = expected ?: return
            val now = foreground()
            check(now == want) { "Step $index: the foreground app is '$now', expected '$want'. Stopped before tapping." }
        }
        fun exec(steps: JSONArray, base: String) {
            for (i in 0 until steps.length()) {
                check(!stopped()) { "Stopped" }
                val step = steps.getJSONObject(i)
                val label = "$base${i + 1}"
                when (step.getString("op")) {
                    "tap", "long_press" -> {
                        requireForeground(executed + 1)
                        gesture(step.getInt("x").toFloat(), step.getInt("y").toFloat(), step.getInt("x").toFloat(), step.getInt("y").toFloat(),
                            if (step.getString("op") == "tap") 50L else 800L)
                        executed++; pause(step.optLong("wait", 600))
                    }
                    "swipe" -> {
                        requireForeground(executed + 1)
                        gesture(step.getInt("x").toFloat(), step.getInt("y").toFloat(), step.getInt("x2").toFloat(), step.getInt("y2").toFloat(), step.optLong("ms", 300))
                        executed++; pause(step.optLong("wait", 600))
                    }
                    "wait" -> { pause(step.optLong("ms", 1000)); executed++ }
                    "wait_foreground" -> {
                        val pkg = step.getString("package")
                        val deadline = System.currentTimeMillis() + step.optLong("ms", 15_000).coerceAtMost(RoutineSteps.MAX_WAIT_MS)
                        while (foreground() != pkg) {
                            check(System.currentTimeMillis() < deadline) { "Step $label: '$pkg' did not come to the front (foreground is '${foreground()}')" }
                            pause(250)
                        }
                        expected = pkg; executed++; pause(step.optLong("wait", 1500))
                    }
                    "home" -> { check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) { "Home was rejected" }; expected = null; executed++; pause(step.optLong("wait", 800)) }
                    "back" -> { check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "Back was rejected" }; executed++; pause(step.optLong("wait", 600)) }
                    "repeat" -> repeat(step.getInt("times")) { n -> exec(step.getJSONArray("steps"), "$label.${n + 1}.") }
                }
            }
        }
        val result = JSONObject().put("name", routine.optString("name"))
        try {
            exec(routine.getJSONArray("steps"), "")
            routine.put("runs", routine.optInt("runs") + 1)
            target.writeText(routine.toString())
            result.put("success", true)
        } catch (e: Exception) {
            result.put("success", false).put("failure", e.message ?: e.javaClass.simpleName)
        }
        return result.put("stepsExecuted", executed).put("elapsedMs", System.currentTimeMillis() - started)
            .put("foreground", foreground()).put("checkpoint", routine.optString("checkpoint"))
    }
}
