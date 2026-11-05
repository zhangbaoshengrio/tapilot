package lifeos.md.tapilot.conversation

import lifeos.md.tapilot.AppText.text as appText
import lifeos.md.tapilot.TaskExecutionStatus
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationTaskInput(
    val source: String,
    val userInput: String,
    val kind: String,
    val history: TaskHistorySnapshot,
    val resume: Boolean,
    val goal: String,
    val limits: AgentLimits,
    val language: String,
    val attachment: JSONObject?,
    val global: Boolean,
    val handoff: Boolean,
    val phoneStarted: Boolean,
    val environment: JSONObject,
)

internal enum class TaskStage { UNDERSTANDING, PREPARING_PHONE, OPERATING_PHONE, PREPARING_REPLY }

internal sealed interface TaskRunEvent {
    data class Progress(val stage: TaskStage, val detail: String = "", val increment: Boolean = false) : TaskRunEvent
    data class Attachment(val reference: JSONObject) : TaskRunEvent
    data class Operation(val reports: JSONArray, val operations: JSONArray) : TaskRunEvent
    data class Agent(val value: JSONObject) : TaskRunEvent
}

internal data class ConversationTaskResult(
    val outcome: Result<String>,
    val phase: String,
    val phoneStarted: Boolean,
    val reports: JSONArray,
    val completedOperations: JSONArray,
)

/** One attempt on the worker. UI ownership, cancellation acknowledgement and queues stay with the coordinator. */
internal class ConversationTaskRunner(
    private val agent: ConversationAgent,
    private val services: ConversationTaskServices,
    private val complete: (JSONArray, JSONArray) -> JSONObject,
    private val confirm: (String) -> String,
    private val emit: (TaskRunEvent) -> Unit,
) {
    fun run(input: ConversationTaskInput, stopped: () -> Boolean): ConversationTaskResult {
        val reports = JSONArray()
        val operations = JSONArray()
        var phoneStarted = input.phoneStarted
        var screenConfirmed = false
        var phase = ""
        fun checkActive() { if (stopped()) throw TaskFailure("task_stopped") }
        fun rememberReports(run: JSONObject, instruction: String = "") {
            run.optJSONArray("reports")?.objects()?.forEach {
                reports.put(JSONObject(it.toString()).apply { if (instruction.isNotBlank()) put("instruction", instruction) })
            }
        }
        val outcome = runCatching {
            checkActive()
            services.status(if (input.handoff) TaskExecutionStatus.RUNNING else TaskExecutionStatus.STARTING)
            val saved = input.attachment?.let { if (it.optString("id").isNotBlank()) it else services.archiveAttachment(it) }
            checkActive()
            if (saved != null) {
                val reference = JSONObject().put("id", saved.getString("id")).put("name", saved.optString("name")).put("kind", saved.getString("kind"))
                if (saved.has("pageCount")) reference.put("pageCount", saved.getInt("pageCount"))
                emit(TaskRunEvent.Attachment(reference))
            }
            if (input.kind == "yaml") {
                services.navigate(TaskNavigation.HOME)
                services.status(TaskExecutionStatus.RUNNING)
                val run = services.run(input.source, JSONObject(input.environment.toString()))
                rememberReports(run)
                checkActive()
                check(run.optInt("exitCode", 1) == 0) { run.optString("failure", appText("执行失败", "Execution failed")) }
                run.optString("finalResponse").ifBlank { appText("任务已完成", "Task completed") }
            } else {
                val attachments = input.history.prior.filter { it.optString("status") in listOf("succeeded", "waiting_user", "stopped") && it.has("attachment") }
                    .sortedBy { it.optLong("createdAt") }.takeLast(2).associate { entry ->
                        entry.getString("id") to runCatching { services.readAttachment(entry.getJSONObject("attachment").getString("id")) }
                            .getOrElse { checkActive(); JSONObject().put("text", appText("之前的附件已不可用。不能猜测内容，请用户重新提供。", "Previous attachment is unavailable. Do not guess its contents; ask the user to provide it again.")) }
                    }
                checkActive()
                val modelSource = input.source + saved?.optString("text").orEmpty().takeIf { it.isNotBlank() }
                    ?.let { "\n\nAttachment content:\n${it.take(30_000)}" }.orEmpty() +
                    if (saved?.optString("name")?.let { it.contains("屏幕") || it == "Current screen" } == true)
                        "\nThe attached image was saved before this message and is reference material only. This notice does not apply to fresh screenshots subsequently captured by operate_phone. Inspect current state through the tool." else ""
                val messages = NativeConversationAgent.messages(input.history.prior, modelSource, input.language == "zh", attachments,
                    saved?.optJSONArray("images")?.strings().orEmpty())
                var prepared = false
                val reply = agent.run(AgentInput(messages,
                    input.history.retry?.takeIf { input.resume || it.optString("phase") != "completed" }?.optJSONArray("agentContext"),
                    input.limits, input.history.pendingInputs, input.goal, input.userInput, input.language), stopped, AgentCallbacks(
                    complete = { chat, tools ->
                        checkActive()
                        if (!prepared) { prepared = true; services.prepare(JSONObject(input.environment.toString())) }
                        emit(TaskRunEvent.Progress(if (phoneStarted) TaskStage.PREPARING_REPLY else TaskStage.UNDERSTANDING))
                        complete(chat, tools)
                    },
                    findApps = { query -> checkActive(); PhoneOperationRoute.findApps(services.capabilities().optJSONArray("apps") ?: JSONArray(), query) },
                    screen = screen@{ request ->
                        checkActive()
                        val service = lifeos.md.tapilot.accessibility.MidsceneAccessibilityService.current()
                            ?: error(appText("请在无障碍设置中开启设备控制", "Enable device control in Accessibility settings"))
                        val requestOp = request.getString("op")
                        if (requestOp.startsWith("routine.")) {
                            val routines = lifeos.md.tapilot.accessibility.Routines
                            return@screen when (requestOp) {
                                "routine.list" -> routines.list(service)
                                "routine.save" -> routines.save(service, request)
                                "routine.run" -> {
                                    if (!screenConfirmed) {
                                        emit(TaskRunEvent.Progress(TaskStage.PREPARING_PHONE, request.optString("name"), true))
                                        confirm(request.optString("name"))
                                        checkActive()
                                        screenConfirmed = true
                                    }
                                    phoneStarted = true
                                    emit(TaskRunEvent.Progress(TaskStage.OPERATING_PHONE, request.optString("name")))
                                    services.status(TaskExecutionStatus.RUNNING)
                                    val outcome = routines.run(service, request.getString("name")) { stopped() }
                                    checkActive()
                                    operations.put(JSONObject().put("instruction", "routine " + request.optString("name"))
                                        .put("success", outcome.optBoolean("success")).put("result", outcome.toString().take(400)))
                                    emit(TaskRunEvent.Operation(JSONArray(reports.toString()), JSONArray(operations.toString())))
                                    outcome
                                }
                                else -> error("Unknown routine request: $requestOp")
                            }
                        }
                        val acting = requestOp == "act"
                        if (acting && !screenConfirmed) {
                            emit(TaskRunEvent.Progress(TaskStage.PREPARING_PHONE, request.optString("action"), true))
                            confirm(request.optString("action"))
                            checkActive()
                            screenConfirmed = true
                        }
                        if (!phoneStarted) {
                            services.navigate(if (input.global || !acting) TaskNavigation.PREVIOUS_APP else TaskNavigation.HOME)
                            phoneStarted = true
                        }
                        if (acting) { emit(TaskRunEvent.Progress(TaskStage.OPERATING_PHONE, request.optString("action"))); services.status(TaskExecutionStatus.RUNNING) }
                        checkActive()
                        val tree = if (acting) lifeos.md.tapilot.accessibility.ScreenTree.act(service, request)
                            else lifeos.md.tapilot.accessibility.ScreenTree.read(service)
                        checkActive()
                        operations.put(JSONObject().put("instruction", if (acting) request.optString("action") + " " + request.optString("id") else "read screen")
                            .put("success", true).put("result", "app=" + tree.optString("app") + " nodes=" + tree.optInt("count")))
                        emit(TaskRunEvent.Operation(JSONArray(reports.toString()), JSONArray(operations.toString())))
                        tree
                    },
                    phone = { operation ->
                        checkActive()
                        emit(TaskRunEvent.Progress(TaskStage.PREPARING_PHONE, operation.instruction, true))
                        val revised = confirm(operation.instruction)
                        checkActive()
                        val capabilities = services.capabilities()
                        check(capabilities.optBoolean("canCapture") && capabilities.optBoolean("canPerformGestures")) {
                            appText("请在无障碍设置中开启设备控制", "Enable device control in Accessibility settings")
                        }
                        val component = PhoneOperationRoute.resolveApp(capabilities.optJSONArray("apps") ?: JSONArray(), operation.app)
                        if (!phoneStarted) {
                            if (component.isBlank()) services.navigate(if (input.global || operation.mode == PhoneMode.OBSERVE) TaskNavigation.PREVIOUS_APP else TaskNavigation.HOME)
                            phoneStarted = true
                        }
                        emit(TaskRunEvent.Progress(TaskStage.OPERATING_PHONE, revised))
                        services.status(TaskExecutionStatus.RUNNING)
                        val environment = JSONObject(input.environment.toString()).apply {
                            if (operation.effort == PlanningEffort.DEEP_THINK) put("MIDSCENE_MODEL_TIMEOUT", "120000")
                        }
                        checkActive()
                        val run = services.run(NativeConversationAgent.verifiedTask(revised, operation.successCondition,
                            operation.mode.wireValue, component, operation.effort.wireValue), environment)
                        rememberReports(run, revised)
                        checkActive()
                        PhoneResult.fromRun(run).also { result ->
                            operations.put(JSONObject().put("instruction", revised).put("success", result.success).put("result", result.result)
                                .put("nativeRpcTimings", run.optJSONObject("nativeRpcTimings") ?: JSONObject()))
                            emit(TaskRunEvent.Operation(JSONArray(reports.toString()), JSONArray(operations.toString())))
                        }
                    },
                    event = { checkActive(); emit(TaskRunEvent.Agent(it)) },
                ))
                checkActive()
                phase = reply.getString("phase")
                if (phase !in setOf("completed", "waiting_user", "failed")) throw TaskFailure("task_terminal_missing")
                if (phase == "failed") throw TaskFailure.from(reply)
                if (phase == "completed") {
                    val generalAnswer = reply.optString("completionKind") == "answer" && reply.optInt("operations") == 0
                    if (!generalAnswer && reply.optJSONObject("lastPhoneResult")?.optBoolean("success") != true)
                        throw TaskFailure("task_verification_missing")
                }
                check(reply.optJSONObject("lastPhoneResult")?.optBoolean("success") != false || phase == "waiting_user") {
                    appText("手机操作未通过最终画面验证", "Phone operation did not pass final screen verification")
                }
                reply.optString("reply").takeIf { it.isNotBlank() } ?: throw TaskFailure("task_result_missing")
            }
        }
        val cleanup = runCatching { services.discardPrepared() }
        val settled = if (outcome.isFailure || cleanup.isSuccess) outcome else Result.failure(cleanup.exceptionOrNull()!!)
        return ConversationTaskResult(settled, phase, phoneStarted, reports, operations)
    }
}
