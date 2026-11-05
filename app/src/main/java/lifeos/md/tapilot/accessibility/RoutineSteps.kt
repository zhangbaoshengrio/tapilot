package lifeos.md.tapilot.accessibility

import org.json.JSONArray
import org.json.JSONObject

/** Pure validation of saved routines: only these steps run, with bounded size and waits. */
internal object RoutineSteps {
    const val MAX_STEPS = 200
    const val MAX_REPEAT = 20
    const val MAX_WAIT_MS = 30_000L
    private val coordinateOps = setOf("tap", "long_press")
    private val ops = coordinateOps + setOf("swipe", "wait", "wait_foreground", "home", "back", "repeat")

    /** Returns the number of executed steps once repeats are expanded; throws IllegalArgumentException when invalid. */
    fun validate(steps: JSONArray, width: Int, height: Int, depth: Int = 0): Int {
        require(depth <= 2) { "Routines may nest repeat at most twice" }
        require(steps.length() in 1..MAX_STEPS) { "A routine needs 1 to $MAX_STEPS steps" }
        var total = 0
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: throw IllegalArgumentException("Step ${index + 1} is not an object")
            val op = step.optString("op")
            require(op in ops) { "Step ${index + 1}: unsupported op '$op'" }
            fun point(x: String, y: String) {
                require(step.has(x) && step.has(y)) { "Step ${index + 1}: $op needs $x and $y" }
                require(step.getInt(x) in 0 until width && step.getInt(y) in 0 until height) { "Step ${index + 1}: point is outside the screen" }
            }
            when (op) {
                in coordinateOps -> point("x", "y")
                "swipe" -> { point("x", "y"); point("x2", "y2") }
                "wait_foreground" -> require(step.optString("package").isNotBlank()) { "Step ${index + 1}: wait_foreground needs package" }
                "repeat" -> {
                    val times = step.optInt("times", 0)
                    require(times in 1..MAX_REPEAT) { "Step ${index + 1}: repeat times must be 1 to $MAX_REPEAT" }
                    val inner = step.optJSONArray("steps") ?: throw IllegalArgumentException("Step ${index + 1}: repeat needs steps")
                    total += times * validate(inner, width, height, depth + 1)
                    continue
                }
            }
            require(step.optLong("wait", 0) in 0..MAX_WAIT_MS && step.optLong("ms", 0) in 0..MAX_WAIT_MS) { "Step ${index + 1}: wait is too long" }
            total++
        }
        require(total <= MAX_STEPS * 4) { "Routine expands to too many steps" }
        return total
    }

    fun fileName(name: String): String {
        val clean = name.trim().replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(40)
        require(clean.isNotBlank()) { "Routine name is empty" }
        return clean
    }

    fun summary(routine: JSONObject): JSONObject = JSONObject().put("name", routine.optString("name"))
        .put("description", routine.optString("description")).put("package", routine.optString("package"))
        .put("checkpoint", routine.optString("checkpoint")).put("runs", routine.optInt("runs"))
}
