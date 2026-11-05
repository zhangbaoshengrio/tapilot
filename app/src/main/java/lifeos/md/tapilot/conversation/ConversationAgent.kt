package lifeos.md.tapilot.conversation

import org.json.JSONArray
import org.json.JSONObject

/** Process-independent task protocol. Credentials are supplied only to the model callback. */
internal interface ConversationAgent : AutoCloseable {
    fun prepare()
    fun steer(id: String, text: String, followUp: Boolean = false): Boolean
    fun stop()
    fun run(input: AgentInput, stopped: () -> Boolean, callbacks: AgentCallbacks): JSONObject
}

internal data class AgentInput(
    val messages: JSONArray,
    val resume: JSONArray?,
    val limits: AgentLimits,
    val resumeInputs: JSONArray = JSONArray(),
    val goal: String = "",
    val userInput: String = "",
    val language: String = "en",
)

internal data class AgentCallbacks(
    val complete: (JSONArray, JSONArray) -> JSONObject,
    val phone: (PhoneOperation) -> PhoneResult,
    val findApps: (String) -> JSONArray,
    val screen: (JSONObject) -> JSONObject,
    val event: (JSONObject) -> Unit,
)
