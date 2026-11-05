package lifeos.md.tapilot.chatgpt

import lifeos.md.tapilot.AppText.text as appText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Adapt the native agent's chat contract to the stateless SIWC Responses contract. */
object ChatGptResponses {
    fun body(model: String, messages: JSONArray, tools: JSONArray): JSONObject {
        val input = JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val role = message.getString("role")
            val savedOutput = message.optJSONArray("tapilotResponsesOutput")
            if (role == "assistant" && savedOutput != null) {
                for (i in 0 until savedOutput.length()) input.put(savedOutput.getJSONObject(i))
                continue
            }
            if (role == "tool") {
                input.put(JSONObject().put("type", "function_call_output").put("call_id", message.getString("tool_call_id"))
                    .put("output", message.optString("content")))
                continue
            }
            val content = JSONArray()
            val blocks = message.optJSONArray("content")
            if (blocks == null) {
                val text = if (message.isNull("content")) "" else message.optString("content")
                if (text.isNotBlank()) content.put(JSONObject().put("type", if (role == "assistant") "output_text" else "input_text").put("text", text))
            } else for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                when (block.getString("type")) {
                    "text" -> content.put(JSONObject().put("type", if (role == "assistant") "output_text" else "input_text").put("text", block.getString("text")))
                    "image_url" -> content.put(JSONObject().put("type", "input_image").put("image_url", block.getJSONObject("image_url").getString("url")))
                    else -> error("ChatGPT 不支持此消息内容类型")
                }
            }
            if (content.length() > 0) input.put(JSONObject().put("role", if (role == "system") "developer" else role).put("content", content))
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i); val function = call.getJSONObject("function")
                input.put(JSONObject().put("type", "function_call").put("namespace", "tapilot").put("call_id", call.getString("id"))
                    .put("name", function.getString("name")).put("arguments", function.getString("arguments")))
            }
        }
        val body = JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("reasoning", JSONObject().put("effort", reasoningEffort))
        if (tools.length() > 0) {
            body.put("parallel_tool_calls", false)
            val functions = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.getJSONObject(i); require(tool.getString("type") == "function")
                functions.put(JSONObject(tool.getJSONObject("function").toString()).put("type", "function").put("strict", false))
            }
            body.put("tools", JSONArray().put(JSONObject().put("type", "namespace").put("name", "tapilot")
                .put("description", "Tapilot local task planning and Android phone operations").put("tools", functions)))
        }
        return body
    }
    /** How long the conversation planner may think before answering: "low", "medium" or "high". */
    @Volatile var reasoningEffort: String = "low"
    private const val EFFORT_KEY = "chatgpt.reasoningEffort"

    fun loadEffort(context: android.content.Context) {
        reasoningEffort = context.getSharedPreferences("midscene-preferences", android.content.Context.MODE_PRIVATE)
            .getString(EFFORT_KEY, null)?.takeIf { it in setOf("low", "medium", "high") } ?: "low"
    }

    fun saveEffort(context: android.content.Context, value: String) {
        require(value in setOf("low", "medium", "high"))
        context.getSharedPreferences("midscene-preferences", android.content.Context.MODE_PRIVATE).edit().putString(EFFORT_KEY, value).apply()
        reasoningEffort = value
    }
    fun params(model: String, token: String, messages: JSONArray, tools: JSONArray): JSONObject = JSONObject()
        .put("url", "${ChatGptOAuth.RESOURCE}/responses").put("method", "POST")
        .put("headers", JSONObject().put("Authorization", "Bearer $token").put("Content-Type", "application/json").put("Accept", "text/event-stream"))
        .put("bodyBase64", Base64.getEncoder().encodeToString(body(model, messages, tools).toString().toByteArray()))

    fun decode(response: JSONObject): JSONObject {
        val text = String(Base64.getDecoder().decode(response.getString("bodyBase64")), Charsets.UTF_8)
        if (response.getInt("status") !in 200..299) {
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            throw IllegalStateException(failure(error, response.getInt("status")))
        }
        var completed: JSONObject? = null
        val completedItems = sortedMapOf<Int, JSONObject>()
        val data = mutableListOf<String>()
        fun event() {
            if (data.isEmpty()) return
            val payload = data.joinToString("\n"); data.clear()
            if (payload == "[DONE]") return
            val value = JSONObject(payload)
            when (value.optString("type")) {
                "response.output_item.done" -> completedItems[value.getInt("output_index")] = value.getJSONObject("item")
                "response.completed" -> {
                    val result = value.getJSONObject("response")
                    check(result.optString("status") == "completed") { appText("ChatGPT 响应未完成，请重试", "ChatGPT response incomplete. Try again") }
                    completed = result
                }
                "response.failed" -> error(failure(value.optJSONObject("response")?.optJSONObject("error")))
                "response.incomplete" -> error(appText("ChatGPT 响应未完成，请重试或更换模型", "ChatGPT response incomplete. Retry or choose another model"))
                "error" -> error(failure(value.optJSONObject("error") ?: value))
            }
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) event() else if (line.startsWith("data:")) data += line.substring(5).removePrefix(" ")
        }
        event()
        val result = completed ?: error(appText("ChatGPT 连接中断，未收到完整结果，请重试", "ChatGPT disconnected before the full response. Try again"))
        val output = result.optJSONArray("output")?.takeIf { it.length() > 0 }
            ?: JSONArray(completedItems.values.toList())
        val textParts = mutableListOf<String>(); val calls = JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val block = content.getJSONObject(j)
                        if (block.optString("type") == "output_text") textParts += block.getString("text")
                        if (block.optString("type") == "refusal") textParts += block.getString("refusal")
                    }
                }
                "function_call" -> {
                    require(item.optString("namespace", "tapilot") == "tapilot") { "Unexpected ChatGPT tool namespace" }
                    calls.put(JSONObject().put("id", item.getString("call_id")).put("type", "function")
                        .put("function", JSONObject().put("name", item.getString("name")).put("arguments", item.getString("arguments"))))
                }
            }
        }
        check(textParts.isNotEmpty() || calls.length() > 0) { appText("ChatGPT 未返回文字或操作指令", "ChatGPT returned no text or actions") }
        val message = JSONObject().put("role", "assistant").put("content", textParts.joinToString("\n"))
        if (calls.length() > 0) message.put("tool_calls", calls)
        val metrics = JSONObject()
        result.optString("model").takeIf { it.isNotBlank() }?.let { metrics.put("model", it) }
        result.optJSONObject("usage")?.let { usage ->
            for (key in listOf("input_tokens", "output_tokens", "total_tokens")) {
                (usage.opt(key) as? Number)?.let { metrics.put(key, it) }
            }
            (usage.optJSONObject("output_tokens_details")?.opt("reasoning_tokens") as? Number)
                ?.let { metrics.put("reasoning_tokens", it) }
        }
        return JSONObject().put("modelMetrics", metrics).put("tapilotResponsesOutput", output).put("choices", JSONArray().put(JSONObject().put("message", message)
            .put("finish_reason", if (calls.length() > 0) "tool_calls" else "stop")))
    }
    private fun failure(error: JSONObject?, status: Int = 0): String = when (error?.optString("code")) {
        "subscription_sharing_usage_limit_exceeded" -> appText("ChatGPT 套餐共享额度已用完，请在 ChatGPT 设置中检查用量或稍后重试", "ChatGPT shared plan quota exhausted. Check usage in ChatGPT settings or try later")
        "subscription_sharing_usage_unavailable" -> appText("此 ChatGPT 账号暂时无法共享套餐，请检查账号与工作区权限", "ChatGPT plan sharing unavailable. Check account and workspace permissions")
        else -> when (status) {
            401 -> appText("ChatGPT 授权已失效，请重新登录", "ChatGPT authorization expired. Sign in again")
            403 -> appText("ChatGPT 未获准使用此模型或套餐，请检查授权", "ChatGPT model or plan access denied. Check authorization")
            429 -> appText("ChatGPT 请求限流或额度不足，请稍后重试", "ChatGPT rate limit or quota reached. Try later")
            else -> "ChatGPT 请求失败${if (status > 0) " · HTTP $status" else ""}，请重试或检查模型权限"
        }
    }
}
