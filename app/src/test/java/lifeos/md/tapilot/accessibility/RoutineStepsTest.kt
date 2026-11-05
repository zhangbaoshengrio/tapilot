package lifeos.md.tapilot.accessibility

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RoutineStepsTest {
    private fun steps(vararg step: JSONObject) = JSONArray().also { list -> step.forEach { list.put(it) } }
    private fun tap(x: Int, y: Int) = JSONObject().put("op", "tap").put("x", x).put("y", y)

    @Test fun repeatCountsExpandedSteps() {
        val farm = steps(tap(10, 10), JSONObject().put("op", "repeat").put("times", 4).put("steps", steps(tap(20, 20), tap(30, 30))))
        assertEquals(9, RoutineSteps.validate(farm, 1272, 2772))
    }

    @Test fun rejectsPointsOutsideTheScreen() {
        assertThrows(IllegalArgumentException::class.java) { RoutineSteps.validate(steps(tap(1272, 10)), 1272, 2772) }
    }

    @Test fun rejectsUnknownOpsAndLongWaits() {
        assertThrows(IllegalArgumentException::class.java) { RoutineSteps.validate(steps(JSONObject().put("op", "shell")), 1272, 2772) }
        assertThrows(IllegalArgumentException::class.java) { RoutineSteps.validate(steps(tap(1, 1).put("wait", 60_000)), 1272, 2772) }
    }

    @Test fun rejectsRepeatBeyondLimit() {
        val loop = JSONObject().put("op", "repeat").put("times", 99).put("steps", steps(tap(1, 1)))
        assertThrows(IllegalArgumentException::class.java) { RoutineSteps.validate(steps(loop), 1272, 2772) }
    }

    @Test fun fileNamesKeepChineseAndDropPathCharacters() {
        assertEquals("种菜", RoutineSteps.fileName("种菜"))
        assertEquals("a_b", RoutineSteps.fileName("../a/b"))
    }
}
