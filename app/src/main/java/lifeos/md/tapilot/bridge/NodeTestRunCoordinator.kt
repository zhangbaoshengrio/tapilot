package lifeos.md.tapilot.bridge

import lifeos.md.tapilot.AppText.text as appText
import lifeos.md.tapilot.device.*
import android.content.Context
import android.util.Log
import lifeos.md.tapilot.AppLanguage
import lifeos.md.tapilot.TaskExecutionStatus
import lifeos.md.tapilot.TaskRuntimeService
import lifeos.md.tapilot.accessibility.deviceDisplayMetrics
import lifeos.md.tapilot.accessibility.MidsceneAccessibilityService
import lifeos.md.tapilot.report.ReportRepository
import lifeos.md.tapilot.runtime.InstalledNodeRuntime
import lifeos.md.tapilot.runtime.NodeRuntimeEvent
import lifeos.md.tapilot.runtime.NodeRuntimeListener
import lifeos.md.tapilot.runtime.NodeRuntimeProcessSupervisor
import lifeos.md.tapilot.runtime.NodeRuntimeProvisioner
import lifeos.md.tapilot.runtime.NodeTestRunRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

class NodeTestRunCoordinator(
    context: Context,
    private val reportRepository: ReportRepository,
    private val supervisor: NodeRuntimeProcessSupervisor = NodeRuntimeProcessSupervisor(),
    private val device: DeviceService = AndroidDeviceService(context),
    private val onProgress: (String) -> Unit = {},
    private val onStep: (String, JSONObject) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val authorizationExecutor = Executors.newSingleThreadExecutor()
    private val accounts = lifeos.md.tapilot.chatgpt.ChatGptAccounts.get(appContext)
    @Volatile private var chatGptAccountId: String? = null
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var completion = CompletableFuture.completedFuture(Unit)
    @Volatile private var activeSessionId: String? = null
    @Volatile private var stopRegistration: ActiveTaskStopRegistry.Registration? = null
    @Volatile private var reportTaskStatus = true

    private val preparationLock = Any()
    private var preparationEpoch = 0L
    private var preparationOwner: String? = null
    private data class PreparedRun(val owner: String, val runtime: InstalledNodeRuntime,
        val projectRoot: File, val environment: Map<String, String>)
    private var preparedRun: PreparedRun? = null

    /** Best effort, task scoped, and queued after Pi has installed the runtime. */
    fun prepare(owner: String, environment: Map<String, String>) {
        require(owner.isNotBlank())
        val effective = executionEnvironment(environment)
        val epoch = synchronized(preparationLock) {
            if (!completion.isDone || supervisor.isRunning()) return
            preparationOwner = owner
            ++preparationEpoch
        }
        executor.execute {
            runCatching {
                val runtime = NodeRuntimeProvisioner(appContext).install()
                synchronized(preparationLock) {
                    if (epoch != preparationEpoch || preparationOwner != owner || !completion.isDone) return@execute
                    val root = createProjectDirectory()
                    supervisor.prepareTest(runtime, request(runtime, root, effective))
                    preparedRun = PreparedRun(owner, runtime, root, effective)
                }
            }.onFailure {
                // Preparation must not turn an otherwise valid conversation into a failure.
                synchronized(preparationLock) {
                    if (epoch == preparationEpoch) {
                        supervisor.discardPrepared()
                        preparedRun = null
                    }
                }
            }
        }
    }

    fun discardPrepared(owner: String) = synchronized(preparationLock) {
        if (preparationOwner == owner) {
            ++preparationEpoch
            preparationOwner = null
            preparedRun = null
            supervisor.discardPrepared()
        }
    }

    private fun executionEnvironment(environment: Map<String, String>) = mapOf(
        "MIDSCENE_MODEL_TIMEOUT" to "45000", "MIDSCENE_MODEL_RETRY_COUNT" to "1",
    ) + environment + ("MIDSCENE_ANDROID_ASSISTANT_LANGUAGE" to AppLanguage.resolved(appContext)) + screenshotExperimentEnvironment()

    private fun request(runtime: InstalledNodeRuntime, root: File, environment: Map<String, String>) = NodeTestRunRequest(
        projectRoot = root, resultDirectory = File(root, "results"),
        configPath = File(runtime.runtimeDirectory, "android.config.mjs").absolutePath,
        projects = listOf("android-accessibility"), environment = environment,
    )

    fun run(
        source: String,
        kind: String,
        environment: Map<String, String>,
        reportTaskStatus: Boolean = true,
        preparationSession: String? = null,
        callback: (Result<JSONObject>) -> Unit,
    ) {
        require(source.isNotBlank()) { "YAML or task source must not be blank" }
        require(source.length <= MAX_SOURCE_CHARACTERS) { "YAML source is too large" }
        require(kind == "task" || kind == "yaml") { "Unsupported Node run kind: $kind" }
        check(completion.isDone && !supervisor.isRunning()) { "A Node runtime task is already running" }
        chatGptAccountId = environment["MIDSCENE_CHATGPT_ACCOUNT_ID"]?.takeIf { it.isNotBlank() }
        // Acquiring can fail while a wireless controller owns the device. Do not leave
        // an unfinished completion future behind when the task was never started.
        // Nested runs share the conversation's stop owner; only standalone runs register here.
        stopRegistration = if (reportTaskStatus) ActiveTaskStopRegistry.register(::stop) else null
        this.reportTaskStatus = reportTaskStatus
        completion = CompletableFuture()
        stopRequested.set(false)
        updateStatus(TaskExecutionStatus.STARTING)
        val queuedAt = android.os.SystemClock.elapsedRealtime()
        executor.execute {
            recordTiming("queue", queuedAt)
            runCatching {
                val installStarted = android.os.SystemClock.elapsedRealtime()
                val runtime = NodeRuntimeProvisioner(appContext).install()
                recordTiming("prepareRuntime", installStarted)
                throwIfStopRequested()
                synchronized(preparationLock) {
                    throwIfStopRequested()
                    val effective = executionEnvironment(environment)
                    val candidate = preparedRun?.takeIf { it.owner == preparationSession && it.runtime == runtime && it.environment == effective }
                    if (candidate == null) supervisor.discardPrepared()
                    preparedRun = null
                    preparationOwner = null
                    ++preparationEpoch
                    val projectStarted = android.os.SystemClock.elapsedRealtime()
                    val projectRoot = candidate?.projectRoot ?: createProjectDirectory()
                    val testSource = if (kind == "task") NaturalLanguageTaskWorkflow.asYaml(source) else source
                    File(projectRoot, "task.yaml").writeText(testSource)
                    recordTiming("prepareProject", projectStarted)
                    start(runtime, projectRoot, effective, preparationSession, callback)
                }
            }.onFailure { error ->
                synchronized(preparationLock) { supervisor.discardPrepared() }
                val finalError = if (stopRequested.get()) NodeRunStoppedException() else error
                updateStatus(
                    if (finalError is NodeRunStoppedException) {
                        TaskExecutionStatus.STOPPED
                    } else {
                        TaskExecutionStatus.FAILED
                    },
                    finalError.message,
                )
                clearStopRegistration()
                completion.complete(Unit)
                callback(Result.failure(finalError))
            }
        }
    }

    fun stop(): Boolean {
        val taskWasActive = !completion.isDone
        stopRequested.set(true)
        activeSessionId?.let { MidsceneAccessibilityService.current()?.cancelSession(it) }
        return synchronized(preparationLock) {
            ++preparationEpoch
            preparationOwner = null
            preparedRun = null
            supervisor.stop() || taskWasActive
        }
    }

    /** Called off the UI thread; includes cancellation during runtime preparation. */
    fun stopAndWait(): Boolean {
        val finished = completion
        val stopped = stop()
        finished.get(8, TimeUnit.SECONDS)
        return stopped
    }

    private fun recordTiming(phase: String, started: Long) {
        Log.i("TapilotPerformance", JSONObject().put("phase", "runner.$phase")
            .put("durationMs", android.os.SystemClock.elapsedRealtime() - started).toString())
    }

    private fun throwIfStopRequested() {
        if (stopRequested.get()) throw NodeRunStoppedException()
    }

    private fun clearStopRegistration() {
        val registration = stopRegistration
        stopRegistration = null
        ActiveTaskStopRegistry.unregister(registration)
    }

    private fun createProjectDirectory(): File {
        val runsRoot = File(appContext.filesDir, "node-runs")
        check(runsRoot.exists() || runsRoot.mkdirs()) { "Unable to create Node run directory" }
        val projectRoot = File(runsRoot, UUID.randomUUID().toString())
        check(projectRoot.mkdirs()) { "Unable to create Node test project" }
        pruneRuns(runsRoot, projectRoot)
        return projectRoot
    }

    private fun start(
        runtime: InstalledNodeRuntime,
        projectRoot: File,
        environment: Map<String, String>,
        ownerSession: String?,
        callback: (Result<JSONObject>) -> Unit,
    ) {
        val completed = AtomicBoolean(false)
        val logs = ArrayDeque<String>()
        var reportPath: String? = null
        var testRunReportPath: String? = null
        var agentReports = JSONArray()
        var finalResponse: String? = null
        var failureMessage: String? = null
        var nativeRpcTimings = JSONObject()

        fun finish(result: Result<JSONObject>) {
            if (!completed.compareAndSet(false, true)) return
            activeSessionId?.let { MidsceneAccessibilityService.current()?.completeSession(it) }
            activeSessionId = null
            chatGptAccountId = null
            clearStopRegistration()
            completion.complete(Unit)
            callback(result)
        }

        val processStarted = android.os.SystemClock.elapsedRealtime()
        var firstEvent = true
        var firstRpc = true
        var completedAt: Long? = null
        val reused = supervisor.runTest(
            runtime,
            request(runtime, projectRoot, environment),
            object : NodeRuntimeListener {
                override fun onLog(line: String) {
                    if (logs.size == MAX_LOG_LINES) logs.removeFirst()
                    logs.addLast(line)
                    Log.d(LOG_TAG, "Node test: $line")
                }

                override fun onEvent(event: NodeRuntimeEvent) {
                    if (firstEvent) { recordTiming("startToFirstEvent", processStarted); firstEvent = false }
                    if (firstRpc && event.type == "rpc.request") {
                        recordTiming("startToFirstRpc", processStarted); firstRpc = false
                    }
                    val payload = JSONObject(event.payload)
                    when (event.type) {
                        "execution.step" -> if (ownerSession != null && !completed.get() && !stopRequested.get()) {
                            onStep(ownerSession, payload)
                        }
                        "rpc.request" -> dispatchRpc(payload)
                        "runtime.prepared" -> Log.i("TapilotPerformance", JSONObject(event.payload)
                            .put("phase", "runner.preload").toString())
                        "capture.settled" -> Log.i("TapilotPerformance", JSONObject(event.payload)
                            .put("phase", "midscene.capture.settled").toString())
                        "model.network" -> Log.i("TapilotPerformance", JSONObject(event.payload)
                            .put("phase", "midscene.model.network").toString())
                        "model.timing" -> Log.i("TapilotPerformance", JSONObject(event.payload)
                            .put("phase", "midscene.model.http").toString())
                        "test.started" -> updateStatus(TaskExecutionStatus.RUNNING)
                        "test.completed" -> {
                            completedAt = android.os.SystemClock.elapsedRealtime()
                            nativeRpcTimings = payload.optJSONObject("nativeRpcTimings") ?: JSONObject()
                            Log.i("TapilotPerformance", JSONObject().put("phase", "midscene.nativeRpc").put("timings", nativeRpcTimings).toString())
                            reportPath = payload.optString("reportPath")
                            testRunReportPath = payload.optString("testRunReportPath")
                            agentReports = payload.optJSONArray("agentReports") ?: JSONArray()
                            finalResponse = payload.optString("finalResponse")
                                .takeIf { it.isNotBlank() }
                            failureMessage = payload.optString("failure")
                                .takeIf { it.isNotBlank() }
                        }
                        "run.failed" -> failureMessage = payload.optString("message")
                    }
                }

                override fun onComplete(exitCode: Int) {
                    completedAt?.let { recordTiming("completedToExit", it) }
                    if (stopRequested.get()) {
                        updateStatus(TaskExecutionStatus.STOPPED)
                        finish(Result.failure(NodeRunStoppedException()))
                        return
                    }
                    runCatching {
                        updateStatus(TaskExecutionStatus.FINALIZING)
                        // Startup failures and native crashes have no HTML report. Preserve
                        // their cause before report import can replace it with a secondary error.
                        if (completedAt == null) {
                            throw nodeRunIncompleteError(exitCode, failureMessage, logs.toList(), environment)
                        }
                        val importStarted = android.os.SystemClock.elapsedRealtime()
                        val importedReports = JSONArray()
                        for (index in 0 until agentReports.length()) {
                            val descriptor = agentReports.getJSONObject(index)
                            val source = File(descriptor.getString("path"))
                            val reportId = reportRepository.importFile(source)
                            importedReports.put(
                                JSONObject()
                                    .put("reportId", reportId)
                                    .put("kind", "agent")
                                    .put("title", descriptor.optString("caseName", "Agent report"))
                                    .putOptional("projectName", descriptor.optString("projectName"))
                                    .putOptional("caseName", descriptor.optString("caseName"))
                                    .putOptional("attempt", descriptor.optInt("attempt").takeIf { it > 0 }),
                            )
                        }
                        val summarySource = testRunReportPath?.takeIf { it.isNotBlank() }?.let(::File)
                        if (summarySource?.isFile == true) {
                            importedReports.put(
                                JSONObject()
                                    .put("reportId", reportRepository.importFile(summarySource))
                                    .put("kind", "test-runner")
                                    .put("title", "Test Runner summary"),
                            )
                        }
                        if (importedReports.length() == 0) {
                            val source = reportPath?.takeIf { it.isNotBlank() }?.let(::File)
                                ?: throw nodeRunIncompleteError(exitCode, failureMessage, logs.toList(), environment)
                            importedReports.put(
                                JSONObject()
                                    .put("reportId", reportRepository.importFile(source))
                                    .put("kind", "test-runner")
                                    .put("title", "Execution report"),
                            )
                        }
                        Log.i("TapilotPerformance", JSONObject().put("phase", "runner.importReports")
                            .put("durationMs", android.os.SystemClock.elapsedRealtime() - importStarted).toString())
                        val primaryReportId = (0 until importedReports.length())
                            .map { importedReports.getJSONObject(it) }
                            .lastOrNull { it.getString("kind") == "agent" }
                            ?.getString("reportId")
                            ?: importedReports.getJSONObject(0).getString("reportId")
                        JSONObject()
                            .put("exitCode", exitCode)
                            .put("reportId", primaryReportId)
                            .put("reports", importedReports)
                            .put("nativeRpcTimings", nativeRpcTimings)
                            .put("output", JSONArray(logs.toList()))
                            .putOptional("finalResponse", finalResponse)
                            .put(
                                "failure",
                                failureMessage?.takeIf { it.isNotBlank() }
                                    ?: if (exitCode == 0) JSONObject.NULL
                                    else "Node Test Runner exited with code $exitCode",
                            )
                    }.fold(
                        onSuccess = { result ->
                            if (exitCode == 0) {
                                updateStatus(TaskExecutionStatus.SUCCEEDED)
                            } else {
                                updateStatus(
                                    TaskExecutionStatus.FAILED,
                                    result.optString("failure"),
                                )
                            }
                            finish(Result.success(result))
                        },
                        onFailure = { error ->
                            updateStatus(TaskExecutionStatus.FAILED, error.message)
                            finish(Result.failure(error))
                        },
                    )
                }

                override fun onFailure(error: Throwable) {
                    val finalError = nodeRunFailure(error, stopRequested.get())
                    updateStatus(
                        if (finalError is NodeRunStoppedException) {
                            TaskExecutionStatus.STOPPED
                        } else {
                            TaskExecutionStatus.FAILED
                        },
                        finalError.message,
                    )
                    finish(Result.failure(finalError))
                }
            },
        )
        Log.i("TapilotPerformance", JSONObject().put("phase", "runner.preparedReuse").put("reused", reused).toString())
    }

    private fun dispatchRpc(request: JSONObject) {
        val requestId = request.getString("requestId")
        val sessionId = request.getString("sessionId")
        activeSessionId = sessionId
        val params = request.optJSONObject("params") ?: JSONObject()
        // Midscene sends JPEG to the model anyway. Encode once in Android instead
        // of PNG here followed by another full-image WASM conversion in Node.
        // Keep the protocol default PNG; only native AI task captures opt in.
        if (request.optString("method") == "device.capture" && !params.has("format")) {
            val debuggable = appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            val format = if (debuggable) appContext.getSharedPreferences("tapilot-performance", Context.MODE_PRIVATE)
                .getString("capture-format", "jpeg") else "jpeg"
            params.put("format", format)
        }
        if (request.getString("method") == "model.accessToken") {
            val account = chatGptAccountId
            authorizationExecutor.execute {
                runCatching {
                    throwIfStopRequested()
                    check(!account.isNullOrBlank()) { "ChatGPT is not enabled for this operation" }
                    accounts.accessToken(account, forceRefresh = params.optBoolean("forceRefresh"))
                }.fold({ token ->
                    if (stopRequested.get()) respondError(requestId, NodeRunStoppedException()) else respondSuccess(requestId, token)
                }, { respondError(requestId, it) })
            }
            return
        }
        appContext.mainExecutor.execute {
            runCatching {
                onProgress(request.getString("method"))
                when (request.getString("method")) {
                    "device.getCapabilities", "device.getSize", "device.capture", "device.execute", "task.cancel", "task.complete" ->
                        device.execute(sessionId, DeviceProtocol.parse(request.getString("method"), params)) { result ->
                            result.fold({ respondSuccess(requestId, it ?: JSONObject.NULL) }, { respondError(requestId, it) })
                        }
                    "task.status" -> {
                        val status = TaskExecutionStatus.valueOf(
                            params.getString("status").uppercase(),
                        )
                        updateStatus(status, params.optString("detail"))
                        respondSuccess(requestId, JSONObject.NULL)
                    }
                    else -> throw UnsupportedOperationException(
                        "Unsupported Node RPC method: ${request.getString("method")}",
                    )
                }
            }.onFailure { respondError(requestId, it) }
        }
    }

    private fun respondSuccess(requestId: String, result: Any?) {
        supervisor.respondToRpc(
            JSONObject().put("requestId", requestId).put("ok", true).put("result", result),
        )
    }

    private fun respondError(requestId: String, error: Throwable) {
        runCatching {
            supervisor.respondToRpc(
                JSONObject()
                    .put("requestId", requestId)
                    .put("ok", false)
                    .put(
                        "error",
                        JSONObject()
                            .put("code", BridgeErrors.code(error))
                            .put("message", error.message ?: error.javaClass.simpleName),
                    ),
            )
        }.onFailure { Log.w(LOG_TAG, "Unable to send Node RPC error", it) }
    }

    private fun updateStatus(status: TaskExecutionStatus, detail: String? = null) {
        // Conversation-owned runs keep the outer task active between phone operations.
        if (!reportTaskStatus) return
        MidsceneAccessibilityService.current()?.updateTaskStatus(status, detail)
        runCatching { TaskRuntimeService.report(appContext, status) }
            .onFailure { Log.w(LOG_TAG, "Unable to update task notification", it) }
    }

    private fun pruneRuns(root: File, active: File) {
        root.listFiles { file -> file.isDirectory && file != active }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_RETAINED_RUN_DIRECTORIES - 1)
            ?.forEach { stale -> stale.deleteRecursively() }
    }

    private fun screenshotExperimentEnvironment(): Map<String, String> =
        mapOf("TAPILOT_SCREENSHOT_SHRINK_FACTOR" to lifeos.md.tapilot.settings.ScreenshotQuality.shrinkFactor(appContext))

    override fun close() {
        stop()
        clearStopRegistration()
        supervisor.close()
        executor.shutdownNow()
        authorizationExecutor.shutdownNow()
    }

    class NodeRunStoppedException : IllegalStateException(appText("用户停止了任务", "User stopped the task"))

    companion object {
        private const val LOG_TAG = "MidsceneNodeTest"
        private const val MAX_SOURCE_CHARACTERS = 1_000_000
        private const val MAX_LOG_LINES = 200
        private const val MAX_RETAINED_RUN_DIRECTORIES = 3
    }
}

internal fun nodeRunFailure(error: Throwable, stopRequested: Boolean): Throwable =
    if (stopRequested) NodeTestRunCoordinator.NodeRunStoppedException() else error

private fun JSONObject.putOptional(key: String, value: Any?): JSONObject {
    if (value != null && value.toString().isNotBlank()) put(key, value)
    return this
}
