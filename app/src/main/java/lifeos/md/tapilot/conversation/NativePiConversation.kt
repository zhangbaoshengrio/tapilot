package lifeos.md.tapilot.conversation

import lifeos.md.tapilot.AppText.text as appText
import android.content.Context
import android.os.SystemClock
import android.util.Log
import lifeos.md.tapilot.runtime.NodeRuntimeProvisioner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** A separate Pi process owns the transcript. It never receives API credentials. */
internal class NativePiConversation(context: Context) : ConversationAgent {
    private val provisioner = NodeRuntimeProvisioner(context.applicationContext)
    private val lock = Any()
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var accepting = false
    private val deferred = ArrayDeque<JSONObject>()

    override fun prepare() = synchronized(lock) { check(process == null); deferred.clear(); accepting = true }

    override fun steer(id: String, text: String, followUp: Boolean): Boolean = synchronized(lock) {
        if (!accepting) return false
        val command = JSONObject().put("type", if (followUp) "followUp" else "steer").put("id", id).put("text", text)
        runCatching { if (process == null) deferred.add(command) else write(command) }.isSuccess
    }
    override fun run(input: AgentInput, stopped: () -> Boolean, callbacks: AgentCallbacks): JSONObject {
        val (messages, resume, limits, resumeInputs, goal, userInput, language) = input
        val (complete, phone, findApps, screen, event) = callbacks
        if (stopped()) throw TaskFailure("task_stopped")
        val timings = JSONObject()
        val traceId = java.util.UUID.randomUUID().toString()
        val traceStarted = SystemClock.elapsedRealtime()
        fun record(phase: String, started: Long, success: Boolean = true) {
            val elapsed = SystemClock.elapsedRealtime() - started
            val summary = timings.optJSONObject(phase) ?: JSONObject().also { timings.put(phase, it) }
            summary.put("count", summary.optInt("count") + 1)
                .put("totalMs", summary.optLong("totalMs") + elapsed)
                .put("maxMs", maxOf(summary.optLong("maxMs"), elapsed))
                .put("failures", summary.optInt("failures") + if (success) 0 else 1)
            // Numeric phase boundaries only: no prompts, screenshots or credentials.
            Log.i("TapilotPerformance", JSONObject().put("traceId", traceId)
                .put("phase", phase).put("index", summary.optInt("count"))
                .put("startMs", started - traceStarted).put("durationMs", elapsed)
                .put("success", success).toString())
        }
        val prepareStarted = SystemClock.elapsedRealtime()
        val runtime = provisioner.install()
        record("runtime.prepare", prepareStarted)
        if (stopped()) throw TaskFailure("task_stopped")
        // Supply inexpensive device facts before planning rather than spending a
        // model round trip discovering them. A missing service must not block chat.
        val catalogStarted = SystemClock.elapsedRealtime()
        val catalog = runCatching { findApps("") }
        record("phone.appCatalog", catalogStarted, catalog.isSuccess)
        if (stopped()) throw TaskFailure("task_stopped")
        val startupStarted = SystemClock.elapsedRealtime()
        val child = synchronized(lock) {
            check(process == null) { appText("Pi Agent 已在运行", "Pi Agent is already running") }
            if (stopped()) throw TaskFailure("task_stopped")
            ProcessBuilder(runtime.command(java.io.File(runtime.runtimeDirectory, "pi-runner.mjs").absolutePath))
                .directory(runtime.runtimeDirectory).redirectErrorStream(true).apply {
                    environment()["LD_LIBRARY_PATH"] = runtime.nodeExecutable.parentFile!!.absolutePath
                    environment()["HOME"] = runtime.homeDirectory.absolutePath
                    environment()["TMPDIR"] = runtime.temporaryDirectory.absolutePath
                }.start().also {
                    process = it; writer = it.outputStream.bufferedWriter()
                    write(JSONObject().put("type", "init").put("messages", messages)
                        .put("language", language).put("installedApps", catalog.getOrDefault(JSONArray())).put("goal", goal).put("userInput", userInput).put("resume", resume ?: JSONArray()).put("pendingInputs", resumeInputs).put("limits", limits.json()))
                    while (deferred.isNotEmpty()) write(deferred.removeFirst())
                    accepting = true
                }
        }
        try {
            var receivedFirstEvent = false
            child.inputStream.bufferedReader().use { reader ->
                while (true) {
                    if (stopped()) throw TaskFailure("task_stopped")
                    val line = reader.readLine() ?: error(appText("Pi Agent 意外退出", "Pi Agent exited unexpectedly"))
                    // Library diagnostics are intentionally not copied to app logs or task context.
                    if (!line.startsWith("TAPILOT_AGENT ")) continue
                    if (!receivedFirstEvent) {
                        record("runtime.startup", startupStarted)
                        receivedFirstEvent = true
                    }
                    val value = JSONObject(line.removePrefix("TAPILOT_AGENT "))
                    when (value.getString("type")) {
                        "rpc" -> {
                            val rpcStarted = SystemClock.elapsedRealtime()
                            val result = runCatching {
                                if (stopped()) throw TaskFailure("task_stopped")
                                val args = value.getJSONObject("params")
                                when (value.getString("method")) {
                                    "model.complete" -> complete(args.getJSONArray("messages"), args.getJSONArray("tools"))
                                    "phone.execute" -> phone(PhoneOperation.from(args)).json()
                                    "phone.findApps" -> JSONObject().put("apps", findApps(args.getString("query")))
                                    "phone.screen" -> screen(args)
                                    else -> error(appText("未知 Agent 请求", "Unknown agent request"))
                                }
                            }
                            record(value.getString("method"), rpcStarted,
                                result.isSuccess && result.getOrNull()?.optBoolean("success", true) != false)
                            if (stopped()) throw TaskFailure("task_stopped")
                            val response = JSONObject().put("requestId", value.getString("requestId")).put("ok", result.isSuccess)
                            result.fold({ response.put("result", it) }, {
                                val failure = TaskFailure.find(it)
                                response.put("error", failure?.message ?: it.cause?.message ?: it.message ?: appText("请求失败", "Request failed"))
                                failure?.let { coded -> response.put("errorCode", coded.code) }
                            })
                            synchronized(lock) { if (process === child) write(response) }
                        }
                        "result" -> { synchronized(lock) { accepting = false }; return value.put("performance", timings) }
                        "failure" -> throw TaskFailure.from(value)
                        else -> event(value.put("performance", JSONObject(timings.toString())))
                    }
                }
            }
        } finally {
            Log.i("TapilotPerformance", JSONObject().put("traceId", traceId).put("phase", "conversation")
                .put("durationMs", SystemClock.elapsedRealtime() - traceStarted).put("timings", timings).toString())
            synchronized(lock) {
                if (process === child) { accepting = false; writer = null; process = null }
            }
            val shutdownStarted = SystemClock.elapsedRealtime()
            child.destroy()
            if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS) }
            record("runtime.shutdown", shutdownStarted)
        }
    }
    private fun write(value: JSONObject) {
        val output = checkNotNull(writer)
        val child = checkNotNull(process)
        val payload = value.toString()
        inputExecutor.execute {
            if (synchronized(lock) { process !== child || writer !== output }) return@execute
            runCatching { output.write(payload); output.newLine(); output.flush() }.onFailure { child.destroy() }
        }
    }
    override fun stop() {
        val child = synchronized(lock) {
            accepting = false; deferred.clear()
            runCatching { writer?.let { write(JSONObject().put("type", "abort")) } }
            process
        }
        child?.destroy()
    }
    override fun close() { stop(); inputExecutor.shutdownNow() }
}
