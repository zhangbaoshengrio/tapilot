package lifeos.md.tapilot.conversation

import lifeos.md.tapilot.AppText.text as appText
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import lifeos.md.tapilot.AppLanguage
import lifeos.md.tapilot.AppTheme
import lifeos.md.tapilot.LaunchContext
import lifeos.md.tapilot.bridge.ActiveTaskStopRegistry
import lifeos.md.tapilot.accessibility.MidsceneAccessibilityService
import lifeos.md.tapilot.bridge.ModelCredentialStore
import lifeos.md.tapilot.services.ServiceGateway
import lifeos.md.tapilot.chat.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread state; blocking model and Node work is confined to one worker. */
class NativeConversationCoordinator(
    private val activity: Context,
    private val services: ServiceGateway,
    private val store: ConversationStore,
    private val emit: (ChatState) -> Unit,
    private val focus: () -> Unit,
    private val inputControl: ConversationDeviceControl,
    private val worker: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    var data by mutableStateOf(ConversationStore.empty())
        private set
    var ready by mutableStateOf(false)
        private set
    var settingsOpen by mutableStateOf(false)
        private set
    var advancedOpen by mutableStateOf(false)
        private set
    val settingsStatus get() = settings.status
    val settingsSection get() = settings.section
    val dark: Boolean get() = AppTheme.isDark(activity)
    val chinese: Boolean get() = AppLanguage.resolved(activity) == "zh"
    val settingsBusy get() = settings.busy
    private val handler = Handler(Looper.getMainLooper())
    private val messageLoads = ConversationMessageLoader(worker) { handler.post(it) }
    private val persistence = ConversationWriter(store, onTiming = { phase, duration ->
        android.util.Log.i("TapilotPerformance", JSONObject().put("phase", "persistence.$phase").put("durationMs", duration).toString())
    }) { failure -> handler.post {
        if (!closed) { stop(clearPending = false); fail(failure) }
    } }
    private val credentials = ModelCredentialStore(activity)
    private val pi = NativePiConversation(activity)
    init { lifeos.md.tapilot.chatgpt.ChatGptResponses.loadEffort(activity) }
    val chatGpt = lifeos.md.tapilot.chatgpt.ChatGptConnection(activity) { if (ready) publish() }
    private val execution = TaskExecution()
    private val activeTaskId get() = execution.taskId
    private var stopRegistration: ActiveTaskStopRegistry.Registration? = null
    private data class TaskHandoff(val startedAt: Long, val phoneStarted: Boolean)
    @Volatile private var closed = false
    var globalMode = false
    @Volatile var backgroundReplies = false
    private val pendingTurns = ArrayDeque<PendingTurn>()
    private val running get() = execution.running
    private var error = ""
        set(value) { field = value; errorEpoch++ }
    private var errorEpoch = 0
    private var draftEpoch = 0
    private var pendingAttachment: JSONObject?
        get() = data.optJSONObject("draftAttachment")
        set(value) { data.put("draftAttachment", value) }
    private var preview: ChatAttachment? = null
    private var progress: ChatProgress? = null
    private var confirmation: CompletableFuture<String>? = null
    private var confirmationText: String? = null
    private val cancellation get() = execution.cancellation
    private val stopBarrier get() = execution.stopBarrier
    private val session get() = execution.sessionId
    private val effects = ConversationEffects(services, persistence::flush) { closed }
    private val models = ConversationModelClient({ chatGpt.store.accessToken(it) },
        { params, session, stopped -> await("model.request", params, session, stopped) as JSONObject }, ::recordModelFailure,
        { phase, duration, success -> android.util.Log.i("TapilotPerformance", JSONObject()
            .put("phase", "model.$phase").put("role", "outer").put("durationMs", duration).put("success", success).toString()) })
    private val queuedContexts = ArrayDeque<JSONObject>()
    private var chatVisible = false
    private var phoneInteractionStarted = false

    fun setChatVisible(visible: Boolean) {
        if (visible == chatVisible) return
        chatVisible = visible
        if (running && !cancellation.get()) {
            if (visible && phoneInteractionStarted) inputControl.pause()
            else if (!visible) inputControl.resume()
            publish()
        }
    }

    private val settings = ConversationSettings(activity, { data }, { running }, { closed }, credentials, chatGpt, models, services, worker, handler, ::save, ::publish, ::local)
    val preset: String get() = settings.preset
    fun profile(id: String = preset): JSONObject = settings.profile(id)
    fun hasKey(id: String) = credentials.contains(id)
    private var initializing = false
    fun initialize() {
        if (ready || initializing || closed) return
        initializing = true
        worker.execute {
            val loaded = runCatching { store.read().also { state ->
                TaskLifecycle.recover(state)
                store.save(state)
            } }
            handler.post {
                if (closed) return@post
                initializing = false
                loaded.fold(onSuccess = { data = it
                    // Recover queued input into the composer; restarting never triggers phone actions.
                    val recovered = it.optJSONArray("pendingTurns")?.objects().orEmpty().map(PendingTurn::from)
                    if (recovered.isNotEmpty()) {
                        data.put("draft", (listOf(data.optString("draft")) + recovered.map { turn -> turn.text }).filter { text -> text.isNotBlank() }.joinToString("\n"))
                        pendingAttachment = pendingAttachment ?: recovered.firstNotNullOfOrNull { turn -> turn.attachment }
                        save()
                    }
                    ready = true; publish(); receiveLaunchContext() }, onFailure = { fail(it) })
            }
        }
    }
    private val persistDraft = Runnable { if (ready && !closed) persistence.saveDraft(data.optString("draft")) }
    private fun save() { handler.removeCallbacks(persistDraft); data.put("pendingTurns", JSONArray(pendingTurns.map { it.json() })); persistence.save(data); data = JSONObject().also { copy -> data.keys().forEach { copy.put(it, data.get(it)) } } }
    private fun history() = data.getJSONArray("history").objects()
    private fun conversation(entry: JSONObject) = entry.optString("conversationId", entry.optString("id"))
    private fun active() = data.getString("active")
    private fun conversationMeta(id: String, change: (JSONObject) -> Unit) {
        if (id.isBlank()) return
        val all = data.optJSONObject("conversationMeta") ?: JSONObject().also { data.put("conversationMeta", it) }
        val entry = all.optJSONObject(id) ?: JSONObject().also { all.put(id, it) }
        change(entry)
        if (entry.length() == 0 || (!entry.optBoolean("pinned") && entry.optString("title").isBlank())) all.remove(id)
        save()
    }
    private fun local(zh: String, en: String) = if (AppLanguage.resolved(activity) == "zh") zh else en
    fun publish() {
        if (closed) return
        val history = history()
        val messages = history.filter { conversation(it) == active() }.flatMap { e ->
            val id = e.getString("id")
            val status = e.optString("status")
            val reports = ChatReport.from(e.optJSONArray("reports"))
            val report = reports.lastOrNull()
            val response = TaskFeedback.response(e, chinese)
            val user = e.optLong("createdAt") to ChatMessage("$id-user", "user", e.optString("inputText", e.optString("source")).ifBlank { e.optString("source") }, status, "", id,
                ChatAttachment.parse(e.optJSONObject("attachment")))
            if (e.optString("kind") == "instruction" && status !in listOf("stopped", "failed")) listOf(user)
            else listOf(user, (if (status == "running") Long.MAX_VALUE else e.optLong("completedAt", e.optLong("createdAt") + 1)) to
                ChatMessage("$id-assistant", "assistant", response, status, report?.id.orEmpty(), id, task = ChatTask(e.optString("phase"),
                    e.optJSONArray("plan")?.strings().orEmpty(), e.optInt("currentStep"), e.optInt("modelTurns"),
                    e.optInt("phoneOperations"), e.optInt("attempt", 1), e.optJSONArray("completedOperations")?.objects().orEmpty()
                        .map { ChatOperation(it.optString("instruction"), it.optBoolean("success"), it.optString("result")) },
                    createdAt = e.optLong("createdAt"), completedAt = e.optLong("completedAt"), steps = ExecutionStep.from(e.optJSONArray("executionSteps"))), reports = reports))
        }.sortedBy { it.first }.map { it.second }
        // Build each conversation once; repeated capture progress must not scan
        // the entire history for every sidebar entry.
        val meta = data.optJSONObject("conversationMeta") ?: JSONObject()
        val conversations = history.sortedByDescending { it.optLong("createdAt") }
            .groupBy(::conversation).map { (id, entries) ->
                val first = entries.minBy { it.optLong("createdAt") }
                val custom = meta.optJSONObject(id)
                ChatConversation(id,
                    custom?.optString("title").orEmpty().ifBlank { first.optString("inputText", first.optString("source")).take(28).ifBlank { local("新对话", "New conversation") } },
                    custom?.optBoolean("pinned") == true)
            }.sortedByDescending { it.pinned }.toMutableList()
        if (conversations.none { it.id == active() }) conversations.add(0, ChatConversation(active(), local("新对话", "New conversation")))
        val model = profile()
        emit(ChatState(ready = ready, language = AppLanguage.resolved(activity), dark = AppTheme.isDark(activity), paletteId = AppTheme.paletteId(activity),
            showFirstTaskGuide = ready && FirstTaskGuide.visible(data),
            advancedMode = data.optBoolean("advanced"), executionMode = ExecutionMode.from(data), activeConversationId = active(), messages = messages, conversations = conversations,
            draft = data.optString("draft"), draftEpoch = draftEpoch, running = running,
            configured = if (data.optBoolean("chatgptConversation")) chatGpt.ready else model.optString("baseUrl").isNotBlank() && model.optString("modelName").isNotBlank() && model.optString("modelFamily").isNotBlank() && hasKey(preset),
            modelName = if (data.optBoolean("chatgptConversation")) chatGpt.model else model.optString("modelName"), speechProvider = data.optString("speechProvider", "volcengine"),
            error = error, errorEpoch = errorEpoch, needsAccessibility = MidsceneAccessibilityService.current() == null,
            attachment = ChatAttachment.parse(pendingAttachment), preview = preview,
            progress = if (inputControl.paused && running && !cancellation.get()) progress?.copy(
                title = local("已暂停 · 发送或离开聊天后继续", "Paused · send or leave to resume"), operation = "") else progress,
            handoffTaskId = if (!running && !execution.stopUnconfirmed) TaskHandoffState.pending(history, active())?.optString("id") else null,
            confirmation = confirmationText))
    }
    fun command(value: JSONObject) {
        if (closed || !ready) return
        if (value.optString("action") == "draft") {
            messageLoads.discard(MessageLoadAction.EDIT)
            data.put("draft", value.optString("text"))
            handler.removeCallbacks(persistDraft)
            handler.postDelayed(persistDraft, 350)
            return
        }
        runCatching {
            val action = value.getString("action")
            when (action) {
                "inputFocus" -> if (running && !cancellation.get()) inputControl.pause()
                "send" -> {
                    val preservedDraft = if (value.optBoolean("preserveDraft")) data.optString("draft") else null
                    submit(value.optString("text", data.optString("draft")))
                    if (preservedDraft != null) { data.put("draft", preservedDraft); draftEpoch++; save() }
                }
                "firstTaskExample" -> if (!running && pendingAttachment == null && data.optString("draft").isBlank()) {
                    data.put("draft", FirstTaskGuide.example(chinese)); draftEpoch++; save(); focus()
                }
                "dismissFirstTask" -> if (!running) { data.put("firstTaskDismissed", true); save() }
                "executionMode" -> {
                    val mode = ExecutionMode.entries.firstOrNull { it.wireValue == value.optString("text") }
                        ?: error("Unknown execution mode")
                    preference("executionMode", mode.wireValue)
                }
                "takeOver" -> if (running && !cancellation.get()) {
                    inputControl.pause()
                    stop(reason = "user_handoff")
                }
                "handoffOpenApp" -> if (!running && !execution.stopUnconfirmed && TaskHandoffState.pending(history(), active()) != null) {
                    services.call("app.returnToPreviousApp") { it.onFailure(::fail) }
                }
                "continueTask" -> if (!running && !execution.stopUnconfirmed) {
                    val task = TaskHandoffState.pending(history(), active())
                    if (task != null && task.optString("id") == value.optString("id")) {
                        start(local("继续任务。请先检查当前画面，核对我手动处理后的状态；不要重复已完成的发送、提交或付款。",
                            "Continue the task. Inspect the current screen and check the state after my manual changes. Do not repeat completed sends, submissions or payments."),
                            file = null, global = true, continueOf = task.getString("id"),
                            restoreDraft = data.optString("draft"), restoreAttachment = pendingAttachment)
                    }
                }
                "stop" -> stop()
                "settings" -> if (!running) { messageLoads.discard(); settingsOpen = true; settings.clearStatus() }
                "advanced" -> if (!running && data.optBoolean("advanced")) { messageLoads.discard(); advancedOpen = true }
                "new" -> if (!running) { messageLoads.discard(); panelResumeId = null; data.put("active", "chat-${UUID.randomUUID()}"); preview = null; save() }
                "select" -> if (!running && history().any { conversation(it) == value.optString("id") }) { messageLoads.discard(); panelResumeId = null; data.put("active", value.getString("id")); preview = null; save() }
                "pinConversation" -> conversationMeta(value.optString("id")) { it.put("pinned", !it.optBoolean("pinned")) }
                "renameConversation" -> conversationMeta(value.optString("id")) { meta ->
                    val title = value.optString("text").trim().take(60)
                    if (title.isBlank()) meta.remove("title") else meta.put("title", title)
                }
                "deleteConversation" -> if (!running) {
                    val id = value.optString("id")
                    if (id.isNotBlank()) {
                        messageLoads.discard(); preview = null
                        data.put("history", JSONArray(history().filter { conversation(it) != id }))
                        data.optJSONObject("conversationMeta")?.remove(id)
                        if (active() == id) { panelResumeId = null; data.put("active", "chat-${UUID.randomUUID()}") }
                        save()
                    }
                }
                "attachment", "removeAttachment" -> {
                    messageLoads.discard(MessageLoadAction.EDIT); messageLoads.discard(MessageLoadAction.PREVIEW)
                    pendingAttachment = if (action == "attachment") value.getJSONObject("attachment") else null; save()
                }
                "closePreview" -> { messageLoads.discard(MessageLoadAction.PREVIEW); preview = null }
                "preview" -> loadMessage(MessageLoadAction.PREVIEW, value.optString("id"))
                "editMessage" -> loadMessage(MessageLoadAction.EDIT, value.optString("id"))
                "retry" -> loadMessage(MessageLoadAction.RETRY, value.optString("id"))
                "report" -> services.call("report.open", JSONObject().put("reportId", value.getString("id"))) { result ->
                    result.onFailure { failure ->
                        if (failure is lifeos.md.tapilot.report.MissingReportException) {
                            removeReports(setOf(failure.reportId))
                            fail(IllegalStateException(local(
                                "此报告已被清理或删除，失效入口已移除。请及时导出需要长期保留的报告。",
                                "This report was cleaned up or deleted. Its unavailable link has been removed. Export reports you want to keep."
                            )))
                        } else fail(failure)
                    }
                }
                "accessibility" -> services.call("app.openAccessibilitySettings") { it.onFailure(::fail) }
                "confirm" -> {
                    if (value.optBoolean("accepted") && value.optString("text").isNotBlank()) {
                        confirmation?.complete(value.getString("text").trim())
                        inputControl.resume()
                    }
                    else stop(reason = "declined")
                    confirmation = null; confirmationText = null
                }
            }
            publish()
        }.onFailure(::fail)
    }
    private var panelResumeId: String? = null
    private fun submit(text: String) {
        if (text.isBlank() && pendingAttachment == null) return
        if (!running) { val resume = panelResumeId?.takeUnless { TaskLifecycle.isNewTask(text) }; panelResumeId = null; start(text, global = globalMode, continueOf = resume); return }
        if (pendingAttachment == null && text.trim().lowercase() in listOf("停止", "取消", "停一下", "别做了", "stop", "cancel")) {
            val control = JSONObject().put("id", UUID.randomUUID().toString()).put("conversationId", active())
                .put("createdAt", System.currentTimeMillis()).put("kind", "control").put("source", text).put("inputText", text)
                .put("status", "succeeded").put("reply", local("正在停止任务", "Stopping task")).put("reports", JSONArray())
            data.put("history", JSONArray(listOf(control) + history())).put("draft", ""); draftEpoch++; save(); stop(); return
        }
        if (pendingAttachment == null && TaskLifecycle.isNewTask(text)) {
            pendingTurns.add(PendingTurn(text, globalMode))
            data.put("draft", ""); draftEpoch++; save(); stop(clearPending = false, reason = "new_task")
            progress = progress?.copy(title = local("正在停止旧任务，随后开始新任务", "Stopping before starting the new task"))
            return
        }
        // Text amendments stay in the active Pi task. Persist before writing to its pipe.
        var continueOf = panelResumeId?.takeUnless { TaskLifecycle.isNewTask(text) }
        if (pendingAttachment == null && find(activeTaskId)?.optString("kind") == "task" && !cancellation.get()) {
            val inputId = UUID.randomUUID().toString()
            val amendment = JSONObject().put("id", inputId).put("conversationId", active()).put("createdAt", System.currentTimeMillis())
                .put("source", text).put("inputText", text).put("kind", "instruction").put("taskId", activeTaskId)
                .put("status", "queued").put("reports", JSONArray())
            data.put("history", JSONArray(listOf(amendment) + history())); save()
            if (pi.steer(inputId, text)) {
                data.put("draft", ""); draftEpoch++; save()
                inputControl.resume()
                progress = progress?.copy(title = local("已收到补充，将调整任务", "Update received, adjusting task")); publish()
                return
            }
            // Agent may still be provisioning or settling. Keep the input for the next attempt.
            continueOf = activeTaskId
            find(inputId)?.let { it.put("status", "stopped"); TaskLifecycle.finish(it, "stopped") }; save()
        }
        pendingTurns.add(PendingTurn(text, globalMode, pendingAttachment?.let { JSONObject(it.toString()) },
            continueOf))
        panelResumeId = null
        data.put("draft", ""); pendingAttachment = null; draftEpoch++; save()
        stop(clearPending = false, reason = "new_input")
        progress = progress?.copy(title = local("正在打断，随后处理新消息", "Interrupting, then processing your message"))
    }
    private fun consumePendingTurn(handoff: TaskHandoff? = null): Boolean {
        if (pendingTurns.isEmpty()) return false
        val turn = pendingTurns.removeFirst()
        start(turn.text, turn.attachment, global = turn.global,
            restoreDraft = data.optString("draft"), restoreAttachment = pendingAttachment, continueOf = turn.continueOf, handoff = handoff)
        if (!running) {
            data.put("draft", turn.text); pendingAttachment = turn.attachment
            draftEpoch++; save(); publish()
        }
        return running
    }
    private fun loadMessage(action: MessageLoadAction, id: String) {
        if (action != MessageLoadAction.PREVIEW && running) return
        val selected = history().find { it.optString("id") == id && conversation(it) == active() }
        if (id.isNotEmpty() && selected == null) return
        if (selected == null && action != MessageLoadAction.PREVIEW) return
        val entry = if (action == MessageLoadAction.RETRY && selected != null) TaskHistorySnapshot.retryTarget(selected, history()) else selected
        val continueOf = entry?.optString("id")?.takeIf { action == MessageLoadAction.RETRY && selected?.optString("kind") == "instruction" }
        val cid = active()
        val draftAttachment = pendingAttachment
        messageLoads.load(action, read = { canceled ->
            if (entry == null) draftAttachment
            else entry.optJSONObject("attachment")?.let {
                await("app.readAttachment", JSONObject().put("id", it.getString("id")), stopped = canceled) as JSONObject
            }
        }, complete = { result ->
            if (!closed && cid == active() && (action == MessageLoadAction.PREVIEW || !running)) {
                result.fold(onSuccess = { file ->
                    when (action) {
                        MessageLoadAction.PREVIEW -> preview = ChatAttachment.parse(file)
                        MessageLoadAction.EDIT -> { data.put("draft", entry!!.optString("inputText", entry.optString("source"))); pendingAttachment = file; draftEpoch++; save(); focus() }
                        MessageLoadAction.RETRY -> start(selected!!.optString("inputText", selected.optString("source")), file, entry!!.optString("id"),
                            kind = entry.optString("kind", "task"), restoreDraft = data.optString("draft"), restoreAttachment = pendingAttachment,
                            continueOf = continueOf)
                    }; publish()
                }, onFailure = ::fail)
            }
        })
    }
    fun openGlobalConversation() {
        // Opening a foreground panel changes the screen under Midscene. Stop safely,
        // then apply the next message to this task's saved context.
        if (running && activeTaskId.isNotBlank()) panelResumeId = activeTaskId
        settingsOpen = false; advancedOpen = false; preview = null
        stop(clearPending = false, reason = "panel")
        publish()
    }
    fun receiveLaunchContext() {
        if (!ready || closed) return
        LaunchContext.consume()?.let { queuedContexts.add(it) }
        consumeQueue()
    }
    private fun consumeQueue(handoff: TaskHandoff? = null) {
        if (running || queuedContexts.isEmpty() || !ready) return
        val context = queuedContexts.removeFirst()
        messageLoads.discard()
        val text = context.optString("text")
        val file = context.optString("dataUrl").takeIf { it.isNotBlank() }?.let {
            JSONObject().put("name", context.optString("label", appText("当前屏幕", "Current screen"))).put("kind", "image").put("images", JSONArray().put(it))
        }
        if (context.optBoolean("autoSend") && text.isNotBlank()) {
            val previousDraft = data.optString("draft")
            val previousAttachment = pendingAttachment
            start(text, file, global = context.optBoolean("fromPanel"), restoreDraft = previousDraft, restoreAttachment = previousAttachment, handoff = handoff)
        } else {
            data.put("draft", listOf(data.optString("draft"), text).filter { it.isNotBlank() }.joinToString("\n"))
            pendingAttachment = file ?: pendingAttachment; draftEpoch++; save(); publish()
            if (!context.optBoolean("voice")) focus()
        }
    }
    private fun start(text: String, file: JSONObject? = pendingAttachment, retryOf: String? = null, global: Boolean = globalMode,
                      restoreDraft: String? = null, restoreAttachment: JSONObject? = null, kind: String = "task", continueOf: String? = null, handoff: TaskHandoff? = null) {
        messageLoads.discard()
        if (running || (text.isBlank() && file == null)) return
        if (execution.stopUnconfirmed) {
            data.put("draft", text); pendingAttachment = file; draftEpoch++; save()
            error = local("旧任务尚未确认停止，消息已保留。停止确认后请再次发送。", "The previous task has not confirmed stopping. Your message is saved; send it again after the stop is confirmed.")
            execution.retryStop()?.let { barrier ->
                services.call("runtime.stop") { result ->
                    result.fold(barrier::complete, barrier::completeExceptionally)
                    handler.post {
                        if (!closed && !execution.stopUnconfirmed && !running) {
                            error = local("旧任务已停止，可以发送保留的消息。", "The previous task stopped. You can send your saved message.")
                            publish()
                        }
                    }
                }
            }
            publish(); return
        }
        runCatching { lifeos.md.tapilot.wireless.DeviceControlLease.checkAccess("local-conversation") }
            .onFailure { fail(it); return }
        val model = profile()
        val useChatGpt = data.optBoolean("chatgptConversation")
        val key = if (useChatGpt) "" else credentials.get(preset).orEmpty()
        if (!useChatGpt && (key.isBlank() || model.optString("modelName").isBlank() || model.optString("modelFamily").isBlank())) {
            fail(IllegalStateException(local("请先配置模型 API", "Configure model API first"))); settingsOpen = true; return
        }
        if (!useChatGpt) runCatching { ModelProfiles.endpoint(model.getString("baseUrl")) }.onFailure { fail(it); return }
        val chatGptAccount = if (useChatGpt) chatGpt.activeId else ""
        val chatGptModel = chatGpt.model
        if (useChatGpt && !chatGpt.ready) {
            fail(IllegalStateException(local("请先登录 ChatGPT 并选择模型", "Sign in to ChatGPT and choose a model first"))); settingsOpen = true; return
        }
        val environment = runCatching { if (useChatGpt) lifeos.md.tapilot.chatgpt.ChatGptMidscene.environment(chatGptModel, chatGptAccount)
                else JSONObject().put("MIDSCENE_MODEL_BASE_URL", model.getString("baseUrl"))
                    .put("MIDSCENE_MODEL_API_KEY", key).put("MIDSCENE_MODEL_NAME", model.getString("modelName"))
                    .put("MIDSCENE_MODEL_FAMILY", model.getString("modelFamily"))
        }.getOrElse { fail(it); return }
        val cid = active()
        val before = history()
        val taskHistory = runCatching { TaskHistorySnapshot.capture(before, cid, text, kind, retryOf, continueOf) }
            .getOrElse { fail(it); return }
        val runSession = UUID.randomUUID().toString()
        runCatching {
            if (kind == "task") pi.prepare()
            stopRegistration = ActiveTaskStopRegistry.register {
                // Registry callers may be off-thread; conversation state belongs to the main thread.
                val stopThisTask = { if (running && session == runSession) stop() }
                if (Looper.myLooper() == Looper.getMainLooper()) stopThisTask() else handler.post { stopThisTask() }
                true
            }
        }.onFailure { fail(it); return }
        error = ""; preview = null; settingsOpen = false; advancedOpen = false
        val id = UUID.randomUUID().toString()
        val stopped = execution.begin(id, runSession)
        phoneInteractionStarted = kind == "yaml"
        val startedAt = System.currentTimeMillis()
        val limits = AgentLimits.from(data)
        val retry = taskHistory.retry
        val source = text.trim().ifBlank { local("请查看附件", "Please inspect the attachment") }
        val entry = JSONObject().put("id", id).put("conversationId", cid).put("createdAt", startedAt)
            .put("source", source).put("inputText", text).put("kind", kind).put("modelName", if (useChatGpt) chatGptModel else model.getString("modelName"))
            .put("status", "running").put("reports", JSONArray())
        if (chatGptAccount.isNotBlank()) entry.put("conversationProvider", "chatgpt").put("conversationModel", chatGptModel)
        retry?.let { entry.put("retryOf", it.getString("id")) }
        TaskLifecycle.begin(entry, retry?.takeIf { continueOf != null || it.optString("phase") != "completed" })
        data.put("history", JSONArray(listOf(entry) + before).let { all -> JSONArray(all.objects().take(200)) })
        // Preserve an unrelated composer before the attempt, including if the app exits mid-task.
        data.put("draft", restoreDraft.orEmpty()); pendingAttachment = restoreAttachment; draftEpoch++; save()
        progress = ChatProgress(local("正在理解任务", "Understanding task"), "", "", handoff?.startedAt ?: startedAt, 0)
        publish()
        worker.execute {
            val taskServices = GatewayTaskServices(
                call = { method, params, seconds -> await(method, params, runSession, stopped, seconds) },
                dispatch = { method, params ->
                    if (method == "runtime.prepareTest") onMain {
                        val debuggable = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                        val enabled = !debuggable || activity.getSharedPreferences("tapilot-performance", Context.MODE_PRIVATE)
                            .getBoolean("runtime-preload", true)
                        if (enabled && !stopped.get() && !closed) services.call(method, params, runSession) {}
                    } else services.call(method, params, runSession) {}
                },
            )
            val runner = ConversationTaskRunner(pi, taskServices,
                complete = { chat, tools ->
                    waitFor(inputControl.ready, stopped, 0)
                    if (chatGptAccount.isNotBlank()) requestChatGpt(chatGptAccount, chatGptModel, chat, tools, runSession, stopped)
                    else requestCompletion(model, key, chat, runSession, stopped, tools)
                },
                confirm = { confirmOperation(it, stopped) },
                emit = { event -> onMain {
                    if (stopped.get() || !execution.owns(runSession)) return@onMain
                    when (event) {
                        is TaskRunEvent.Progress -> {
                            if (event.stage in listOf(TaskStage.PREPARING_PHONE, TaskStage.OPERATING_PHONE)) phoneInteractionStarted = true
                            val title = when (event.stage) {
                                TaskStage.UNDERSTANDING -> local("正在理解任务", "Understanding task")
                                TaskStage.PREPARING_PHONE -> local("准备操作手机", "Preparing phone operation")
                                TaskStage.OPERATING_PHONE -> local("正在操作手机", "Operating phone")
                                TaskStage.PREPARING_REPLY -> local("正在整理结果", "Preparing reply")
                            }
                            progress = progress?.let { it.copy(title = title, detail = event.detail, operation = "",
                                step = it.step + if (event.increment) 1 else 0) }
                        }
                        is TaskRunEvent.Attachment -> { find(id)?.put("attachment", event.reference); save() }
                        is TaskRunEvent.Operation -> {
                            find(id)?.put("completedOperations", event.operations)?.put("reports", event.reports); save()
                        }
                        is TaskRunEvent.Agent -> {
                            val value = event.value
                            find(id)?.let { TaskLifecycle.event(it, value) }
                            if (value.optString("type") == "plan") progress = progress?.copy(
                                title = local("正在推进任务", "Working on task"), detail = value.getJSONArray("steps").strings().joinToString(" · "))
                            if (value.optString("type") == "accepted") find(value.optString("id"))?.put("status", "running")
                            save()
                        }
                    }
                    publish()
                } },
            )
            val result = runner.run(ConversationTaskInput(source, text.trim(), kind, taskHistory, continueOf != null,
                entry.getString("goal"), limits, AppLanguage.resolved(activity), file, global, handoff != null,
                handoff?.phoneStarted ?: false, environment)) { stopped.get() || closed }
            val (outcome, finalPhase, phoneStarted, reports, completedOperations) = result
            // A stop can arrive while completion is waiting for the main thread. If it
            // does, wait for that cancellation barrier before publishing the final state.
            var settled = false
            while (!settled && !closed) {
                val barrier = stopBarrier
                val stopAcknowledged = if (!stopped.get()) true else runCatching {
                    checkNotNull(barrier).get(10, TimeUnit.SECONDS)
                }.isSuccess
                onMain {
                    if (!execution.owns(runSession)) { settled = true; return@onMain }
                    if (stopBarrier !== barrier) return@onMain
                    val executionStatus = TaskLifecycle.executionStatus(stopped.get(), stopAcknowledged, outcome.isSuccess, finalPhase)
                    val status = executionStatus.name.lowercase()
                    val message = when {
                        !stopAcknowledged -> local("无法确认任务已停止，请检查手机当前状态后重试。", "Could not confirm the task stopped. Check the phone before retrying.")
                        stopped.get() -> local("任务已停止", "Task stopped")
                        else -> outcome.getOrElse { TaskFailure.find(it)?.message ?: it.cause?.message ?: it.message ?: appText("任务失败", "Task failed") }
                    }
                    if (!stopped.get()) TaskFailure.find(outcome.exceptionOrNull())?.let { find(id)?.put("errorCode", it.code) }
                    find(id)?.put("status", status)?.put("reports", reports)?.put("completedOperations", completedOperations)
                        ?.put(if (status in listOf("succeeded", "waiting_user")) "reply" else "detail", message)
                    find(id)?.let {
                        TaskLifecycle.finish(it, status, if (stopped.get()) "" else finalPhase)
                        if (FirstTaskGuide.verifiedPhoneTask(it)) data.put("firstTaskCompleted", true)
                        android.util.Log.i("TapilotPerformance", JSONObject().put("phase", "task.wall")
                            .put("taskId", id).put("status", status)
                            .put("createdAt", it.getLong("createdAt")).put("completedAt", it.getLong("completedAt"))
                            .put("durationMs", it.getLong("completedAt") - it.getLong("createdAt")).toString())
                    }
                    find(id)?.let { task ->
                        TaskLifecycle.settleInputs(task, history(), local("补充消息已保存，请重试或继续", "Update saved. Retry or continue."))
                            .takeIf { it.isNotEmpty() }?.let { inputs ->
                                // A resumed Pi run receives all pending inputs together; enqueue it once.
                                pendingTurns.add(PendingTurn(inputs.sortedBy { it.optLong("createdAt") }.joinToString("\n") { it.getString("source") }, global, continueOf = id))
                            }
                    }
                    val feedback = find(id)?.let { TaskFeedback.response(it, chinese) } ?: message
                    val nextHandoff = TaskHandoff(progress?.startedAt ?: startedAt, phoneStarted)
                    execution.finish(runSession); progress = null; confirmation = null; confirmationText = null
                    if (stopAcknowledged) inputControl.resume()
                    ActiveTaskStopRegistry.unregister(stopRegistration); stopRegistration = null
                    // Keep cancellation marked if the runtime has not acknowledged stopping.
                    if (stopAcknowledged) services.call("task.complete", sessionId = runSession) {}
                    if (!stopAcknowledged) {
                        error = local("旧任务尚未确认停止，新消息已保留，请稍后重试。", "Previous task has not confirmed stopping. Your message is retained.")
                        data.put("draft", (listOf(data.optString("draft")) + pendingTurns.map { it.text }).filter { it.isNotBlank() }.joinToString("\n"))
                        pendingAttachment = pendingAttachment ?: pendingTurns.firstOrNull()?.attachment
                        pendingTurns.clear(); draftEpoch++
                    }
                    save()
                    val retained = history().mapNotNull { it.optJSONObject("attachment")?.optString("id") }.toMutableSet()
                    pendingAttachment?.optString("id")?.takeIf { it.isNotBlank() }?.let { retained.add(it) }
                    pendingTurns.mapNotNull { it.attachment?.optString("id") }.forEach { retained.add(it) }
                    services.call("app.pruneAttachments", JSONObject().put("ids", JSONArray(retained.toList()))) {}

                    // Settle amendments before deciding whether this is the user-visible end.
                    // A handoff keeps the notification and current phone screen uninterrupted.
                    if (stopAcknowledged) {
                        if (!consumePendingTurn(nextHandoff)) consumeQueue(nextHandoff)
                    }
                    if (!running) {
                        services.call("task.status", JSONObject().put("status", status).put("detail", message), runSession) {}
                        if (global) services.call("app.showGlobalResult", JSONObject().put("message", feedback), runSession) {}
                        else if (phoneStarted && !stopped.get() && !backgroundReplies) services.call("app.bringToFront", sessionId = runSession) {}
                        publish()
                    }
                    settled = true
                }
            }
        }
    }
    private fun find(id: String) = history().find { it.optString("id") == id }
    fun phoneStep(ownerSession: String, value: JSONObject) {
        if (!running || cancellation.get() || !execution.owns(ownerSession)) return
        val entry = find(activeTaskId) ?: return
        if (ExecutionSteps.update(entry, value)) { save(); publish() }
    }
    fun phoneProgress(method: String) {
        if (!running || cancellation.get()) return
        val current = progress ?: return
        val operation = when (method) {
            "device.capture" -> local("正在查看屏幕", "Viewing screen")
            "device.execute" -> local("正在执行屏幕操作", "Performing action")
            else -> return
        }
        if (current.operation == operation) return
        progress = current.copy(operation = operation)
        publish()
    }
    private fun confirmOperation(text: String, stopped: AtomicBoolean): String {
        waitFor(inputControl.ready, stopped, 0)
        if (!ExecutionMode.from(data).requiresConfirmation(text)) return text
        val promise = CompletableFuture<String>()
        onMain { confirmation = promise; confirmationText = text; publish() }
        return waitFor(promise, stopped, 0)
    }
    private fun requestChatGpt(account: String, model: String, messages: JSONArray, tools: JSONArray, sessionId: String, stopped: AtomicBoolean): JSONObject {
        android.util.Log.i("TapilotPerformance", JSONObject().put("phase", "model.config")
            .put("role", "outer").put("model", model).put("reasoningEffort", "provider-default").toString())
        val result = models.chatGpt(account, model, messages, tools, sessionId, stopped)
        result.optJSONObject("modelMetrics")?.let { metrics ->
            android.util.Log.i("TapilotPerformance", JSONObject(metrics.toString())
                .put("phase", "model.response").put("role", "outer").put("requestedModel", model).toString())
        }
        return result
    }
    fun testChatGpt() = settings.testChatGpt()
    private fun requestCompletion(model: JSONObject, key: String, messages: JSONArray, sessionId: String, stopped: AtomicBoolean, tools: JSONArray = JSONArray().put(NativeConversationAgent.tool)) =
        models.complete(ModelProfile.from(model), key, messages, tools, sessionId, stopped)

    private fun recordModelFailure(response: JSONObject, key: String) {
        if (response.optInt("status") !in 200..299 && activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug-only, private diagnostics. Never save the credential or raw response body.
            runCatching {
                val body = JSONObject(String(java.util.Base64.getDecoder().decode(response.getString("bodyBase64"))))
                val error = body.optJSONObject("error") ?: body
                val message = error.optString("message").replace(key, "[REDACTED]")
                    .replace(Regex("(?i)(sk-|ak-|Bearer\\s+)[a-zA-Z0-9._-]+"), "[REDACTED]").take(1_024)
                val code = error.optString("code", error.optString("type")).replace(key, "[REDACTED]").take(80)
                java.io.File(activity.filesDir, "native-model-error.json").writeText(JSONObject()
                    .put("status", response.getInt("status")).put("code", code).put("message", message).toString())
            }
        }
    }

    private fun await(method: String, params: JSONObject = JSONObject(), sessionId: String = "native", stopped: AtomicBoolean = AtomicBoolean(false), seconds: Long = 120): Any? {
        if (method == "runtime.runTest") waitFor(inputControl.ready, stopped, 0)
        val started = android.os.SystemClock.elapsedRealtime()
        try { return effects.call(method, params, sessionId, stopped, seconds) }
        finally {
            // Fixed internal method name only; never log arguments or returned data.
            android.util.Log.i("TapilotPerformance", JSONObject().put("phase", "conversation.effect")
                .put("method", method).put("durationMs", android.os.SystemClock.elapsedRealtime() - started).toString())
        }
    }
    private fun <T> waitFor(promise: CompletableFuture<T>, stopped: AtomicBoolean, seconds: Long): T = effects.await(promise, stopped, seconds)
    private fun onMain(action: () -> Unit) {
        if (closed) return
        val started = android.os.SystemClock.elapsedRealtime()
        val done = CompletableFuture<Unit>()
        handler.post { if (!closed) runCatching(action).fold({ done.complete(Unit) }, done::completeExceptionally) else done.complete(Unit) }
        done.get(10, TimeUnit.SECONDS)
        android.util.Log.i("TapilotPerformance", JSONObject().put("phase", "conversation.mainDispatch")
            .put("durationMs", android.os.SystemClock.elapsedRealtime() - started).toString())
    }
    fun stop(clearPending: Boolean = true, reason: String = "user") {
        messageLoads.discard()
        if (clearPending) { pendingTurns.clear(); queuedContexts.clear(); if (ready) save() }
        if (!running) return
        if (cancellation.get()) { publish(); return }
        val barrier = execution.requestStop()
        find(activeTaskId)?.put("stopReason", reason)
        save()
        pi.stop()
        progress = progress?.copy(title = local("正在停止", "Stopping"), stopping = true)
        confirmation?.completeExceptionally(IllegalStateException(appText("任务已停止", "Task stopped"))); confirmation = null; confirmationText = null
        services.call("task.cancel", sessionId = session) {}
        services.call("task.status", JSONObject().put("status", "stopping"), session) {}
        services.call("runtime.stop") { it.fold(barrier::complete, barrier::completeExceptionally) }
        publish()
    }
    fun back(): Boolean {
        messageLoads.discard()
        return when {
            confirmationText != null -> { stop(reason = "declined"); true }
            preview != null -> { preview = null; publish(); true }
            advancedOpen -> { advancedOpen = false; true }
            settingsOpen -> { settingsOpen = false; publish(); true }
            else -> false
        }
    }
    fun removeReports(ids: Set<String>) {
        if (!ready || ids.isEmpty()) return
        history().forEach { e -> e.put("reports", JSONArray(e.optJSONArray("reports")?.objects().orEmpty().filterNot { it.optString("reportId") in ids })) }
        save(); publish()
    }
    private fun fail(t: Throwable) { error = t.message ?: appText("操作失败", "Operation failed"); publish() }
    fun selectPreset(id: String) = settings.selectPreset(id)
    fun saveModel(profile: JSONObject, replacementKey: String = "", reset: Boolean = false) = settings.saveModel(profile, replacementKey, reset)
    fun preference(name: String, value: Any) = settings.preference(name, value)
    fun saveSpeech(provider: String, key: String) = settings.saveSpeech(provider, key)
    fun resetSpeech(provider: String) = settings.resetSpeech(provider)
    fun checkSpeech(provider: String) = settings.checkSpeech(provider)
    fun settingsCall(method: String, params: JSONObject = JSONObject()) = settings.call(method, params)
    fun testModel() = settings.testModel()
    fun clearHistory() {
        if (running) return
        messageLoads.discard()
        data.put("history", JSONArray()).put("active", "chat-${UUID.randomUUID()}"); preview = null; pendingAttachment = null; save(); publish()
        services.call("app.pruneAttachments", JSONObject().put("ids", JSONArray())) {}
    }
    fun executeYaml(source: String) { advancedOpen = false; start(source, file = null, kind = "yaml") }
    override fun close() { stop(reason = "app"); ActiveTaskStopRegistry.unregister(stopRegistration); stopRegistration = null; if (ready) persistence.saveDraft(data.optString("draft")); chatGpt.close(); pi.close(); closed = true; worker.shutdownNow(); persistence.close(); handler.removeCallbacksAndMessages(null) }
}
