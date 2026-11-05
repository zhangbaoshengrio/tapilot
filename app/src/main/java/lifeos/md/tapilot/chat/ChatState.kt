package lifeos.md.tapilot.chat

import org.json.JSONObject
import org.json.JSONArray
import lifeos.md.tapilot.conversation.ExecutionMode

data class ChatMessage(val id: String, val role: String, val text: String, val status: String, val reportId: String,
    val entryId: String = "", val attachment: ChatAttachment? = null, val task: ChatTask? = null,
    val reports: List<ChatReport> = emptyList())
data class ChatReport(val id: String, val title: String) {
    companion object {
        fun from(values: JSONArray?): List<ChatReport> {
            val entries = if (values == null) emptyList() else List(values.length()) { values.getJSONObject(it) }
            val available = entries.filter { it.optString("reportId").isNotBlank() }
            val agents = available.filter { it.optString("kind") == "agent" }
            return (agents.ifEmpty { available }).distinctBy { it.getString("reportId") }.map {
                ChatReport(it.getString("reportId"), it.optString("instruction").ifBlank { it.optString("title") })
            }
        }
    }
}
data class ChatConversation(val id: String, val title: String, val pinned: Boolean = false)
data class ChatAttachment(val name: String, val kind: String, val preview: String = "",
    val id: String = "", val text: String = "", val images: List<String> = emptyList(), val pageCount: Int = 0) {
    companion object {
        fun parse(value: JSONObject?): ChatAttachment? = value?.let { data ->
            val images = data.optJSONArray("images")
            ChatAttachment(data.optString("name"), data.optString("kind"), data.optString("preview").ifBlank { images?.optString(0).orEmpty() },
                data.optString("id"), data.optString("text"),
                if (images == null) emptyList() else List(images.length()) { images.getString(it) }, data.optInt("pageCount"))
        }
    }
}
data class ChatOperation(val instruction: String, val success: Boolean, val result: String)

data class ChatTask(val phase: String, val plan: List<String>, val currentStep: Int,
    val modelTurns: Int, val phoneOperations: Int, val attempt: Int, val operations: List<ChatOperation>,
    val createdAt: Long = 0, val completedAt: Long = 0, val steps: List<ExecutionStep> = emptyList()) {
    val durationMillis: Long? get() = if (phase != "running" && createdAt > 0 && completedAt >= createdAt)
        completedAt - createdAt else null
}
data class ChatProgress(val title: String, val detail: String, val operation: String, val startedAt: Long, val step: Int, val stopping: Boolean = false)

data class ChatState(
    val ready: Boolean = false,
    val showFirstTaskGuide: Boolean = false,
    val language: String = "zh",
    val dark: Boolean = false,
    val paletteId: String = "minimal",
    val webSurfaceOpen: Boolean = false,
    val advancedMode: Boolean = false,
    val executionMode: ExecutionMode = ExecutionMode.YOLO,
    val activeConversationId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<ChatConversation> = emptyList(),
    val draft: String = "",
    val draftEpoch: Int = 0,
    val running: Boolean = false,
    val configured: Boolean = false,
    val modelName: String = "",
    val speechProvider: String = "volcengine",
    val statusTitle: String = "",
    val error: String = "",
    val errorEpoch: Int = 0,
    val needsAccessibility: Boolean = false,
    val attachment: ChatAttachment? = null,
    val confirmation: String? = null,
    val preview: ChatAttachment? = null,
    val progress: ChatProgress? = null,
    val handoffTaskId: String? = null,
) {
    val chinese: Boolean get() = language == "zh"

    companion object {
        fun parse(payload: String): ChatState {
            val data = JSONObject(payload)
            require(data.getInt("protocolVersion") == 1) { "Unsupported chat protocol" }
            val messages = data.getJSONArray("messages")
            val conversations = data.getJSONArray("conversations")
            val attachment = data.optJSONObject("attachment")
            return ChatState(
                ready = data.optBoolean("ready"), language = data.optString("language", "zh"),
                dark = data.optString("theme") == "dark", webSurfaceOpen = data.optBoolean("webSurfaceOpen"),
                advancedMode = data.optBoolean("advancedMode"), executionMode = ExecutionMode.from(data),
                activeConversationId = data.optString("activeConversationId"),
                messages = List(messages.length()) { index -> messages.getJSONObject(index).let {
                    ChatMessage(it.getString("id"), it.getString("role"), it.optString("text"),
                        it.optString("status"), it.optString("reportId"), it.optString("entryId", it.getString("id").substringBeforeLast("-")),
                        ChatAttachment.parse(it.optJSONObject("attachment")), reports = ChatReport.from(it.optJSONArray("reports")))
                } },
                conversations = List(conversations.length()) { index -> conversations.getJSONObject(index).let {
                    ChatConversation(it.getString("id"), it.getString("title"))
                } },
                draft = data.optString("draft"), draftEpoch = data.optInt("draftEpoch"),
                running = data.optBoolean("running"), configured = data.optBoolean("configured"),
                modelName = data.optString("modelName"), speechProvider = data.optString("speechProvider", "volcengine"),
                statusTitle = data.optString("statusTitle"), error = data.optString("error"), errorEpoch = data.optInt("errorEpoch"), needsAccessibility = data.optBoolean("needsAccessibility"),
                attachment = ChatAttachment.parse(attachment),
                preview = ChatAttachment.parse(data.optJSONObject("preview")),
                progress = data.optJSONObject("progress")?.let { ChatProgress(it.optString("title"), it.optString("detail"),
                    it.optString("operation"), it.optLong("startedAt"), it.optInt("step"), it.optBoolean("stopping")) },
                confirmation = data.optString("confirmation").takeIf { !data.isNull("confirmation") && it.isNotBlank() },
            )
        }
    }
}
