package lifeos.md.tapilot.services

import lifeos.md.tapilot.AppText.text as appText
import lifeos.md.tapilot.bridge.*
import lifeos.md.tapilot.device.*

import android.app.Activity
import android.content.Context
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import lifeos.md.tapilot.LaunchContext
import lifeos.md.tapilot.TaskRuntimeService
import lifeos.md.tapilot.accessibility.MidsceneAccessibilityService
import lifeos.md.tapilot.accessibility.FloatingBallPreferences
import lifeos.md.tapilot.accessibility.EdgeEffectGeometryMode
import lifeos.md.tapilot.accessibility.EdgeEffectGeometrySettings
import lifeos.md.tapilot.accessibility.EdgeEffectPreferences
import lifeos.md.tapilot.TaskExecutionStatus
import lifeos.md.tapilot.report.ReportRepository
import lifeos.md.tapilot.runtime.InstalledNodeRuntime
import lifeos.md.tapilot.runtime.NodeRuntimeEvent
import lifeos.md.tapilot.runtime.NodeRuntimeListener
import lifeos.md.tapilot.runtime.NodeRuntimeProcessSupervisor
import lifeos.md.tapilot.runtime.NodeRuntimeProvisioner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

class AppServices(
    context: Context,
    val device: DeviceService,
    private val onProgress: (String) -> Unit = {},
    private val onStep: (String, JSONObject) -> Unit = { _, _ -> },
    private val onLanguageChanged: (String) -> Unit = {},
    private val onThemeChanged: (String) -> Unit = {},
    private val onPickAttachment: (String, (Result<JSONObject?>) -> Unit) -> Unit = { _, callback ->
        callback(Result.failure(UnsupportedOperationException("Attachment picker unavailable")))
    },
) : ServiceGateway, AutoCloseable {
    private val appContext = context.applicationContext
    private var host = java.lang.ref.WeakReference(context as? Activity)
    private val activity: Context get() = host.get() ?: appContext
    fun attach(activity: Activity?) { host = java.lang.ref.WeakReference(activity) }
    private fun open(intent: Intent) {
        val context = activity
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, (Result<Any?>) -> Unit>()
    private var closed = false
    private var navigationEpoch = 0

    /** Native callers use the same services directly, without JavaScript or a WebView. */
    override fun call(method: String, params: JSONObject, sessionId: String,
             callback: (Result<Any?>) -> Unit) {
        val id = java.util.UUID.randomUUID().toString()
        handler.post {
            if (closed) { callback(Result.failure(IllegalStateException("Service closed"))); return@post }
            callbacks[id] = callback
            runCatching { handle(id, sessionId, method, params) }
                .onFailure { respondError(id, it) }
        }
    }

    private val attachments = AttachmentStore(File(activity.filesDir, "chat-attachments"))
    private val modelTransport = ModelTransport(onTiming = { android.util.Log.i("TapilotPerformance", it.toString()) })
    private val modelCredentialStore = ModelCredentialStore(activity)
    private val speech = SpeechService(appContext, modelCredentialStore)
    private val reportRepository = ReportRepository(File(activity.filesDir, "reports")).apply {
        cleanupStaleDrafts()
    }
    private val reports = ReportService({ activity }, reportRepository, reportExecutor, ::open)
    private val nodeRuntimeSupervisor = NodeRuntimeProcessSupervisor()
    private val nodeTestRunCoordinator = NodeTestRunCoordinator(
        activity,
        reportRepository,
        nodeRuntimeSupervisor,
        device = device,
        onStep = { session, step -> handler.post { if (!closed) onStep(session, step) } },
        onProgress = { method -> handler.post {
            onProgress(method)
        } },
    )

    private fun handle(requestId: String, sessionId: String, method: String, params: JSONObject) {
        if (method.startsWith("device.") || method in setOf("runtime.runTest", "runtime.prepareTest", "task.status")) {
            lifeos.md.tapilot.wireless.DeviceControlLease.checkAccess(sessionId)
        }
        val service = MidsceneAccessibilityService.current()

        when (method) {
            "app.archiveAttachment" -> executeReportOperation(requestId) {
                attachments.save(params.getJSONObject("attachment"))
            }
            "app.readAttachment" -> executeReportOperation(requestId) {
                attachments.read(params.getString("id"))
            }
            "app.pruneAttachments" -> executeReportOperation(requestId) {
                val ids = params.getJSONArray("ids")
                attachments.retain((0 until ids.length()).map { ids.getString(it) }.toSet())
                JSONObject.NULL
            }
            "app.pickAttachment" -> {
                val kind = params.getString("kind")
                require(kind == "photo" || kind == "camera" || kind == "file")
                onPickAttachment(kind) { result ->
                    result.fold(
                        onSuccess = { respondSuccess(requestId, it ?: JSONObject.NULL) },
                        onFailure = { respondError(requestId, it) },
                    )
                }
            }
            "app.getAssistantRole" -> {
                val manager = activity.getSystemService(RoleManager::class.java)
                respondSuccess(requestId, JSONObject()
                    .put("available", manager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true)
                    .put("held", manager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true))
            }
            "app.requestAssistantRole" -> {
                val manager = activity.getSystemService(RoleManager::class.java)
                check(manager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                    "Assistant role is unavailable on this device"
                }
                if (!manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    (host.get() ?: error("Open Tapilot to grant the assistant role")).startActivityForResult(
                        manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 2110,
                    )
                }
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.consumeLaunchContext" -> respondSuccess(
                requestId, LaunchContext.consume() ?: JSONObject.NULL,
            )
            "device.getCapabilities", "device.getSize", "device.capture", "device.prepareAction", "device.execute" ->
                device.execute(sessionId, DeviceProtocol.parse(method, params)) { result ->
                    result.fold({ respondSuccess(requestId, it) }, { respondError(requestId, it) })
                }
            "task.cancel" -> {
                navigationEpoch++
                device.execute(sessionId, DeviceCommand.Cancel) {}
                modelTransport.cancelSession(sessionId)
                respondSuccess(requestId, JSONObject.NULL)
            }
            "task.complete" -> {
                device.execute(sessionId, DeviceCommand.Complete) {}
                modelTransport.completeSession(sessionId)
                respondSuccess(requestId, JSONObject.NULL)
            }
            "task.status" -> {
                val status = runCatching {
                    TaskExecutionStatus.valueOf(params.getString("status").uppercase())
                }.getOrElse { throw IllegalArgumentException("Unsupported task status") }
                service?.updateTaskStatus(
                    status,
                    params.optString("detail").takeIf { it.isNotBlank() },
                )
                runCatching { TaskRuntimeService.report(activity, status) }
                    .onFailure { Log.w(LOG_TAG, "Unable to update task notification", it) }
                respondSuccess(requestId, JSONObject.NULL)
            }
            "model.request" -> modelTransport.execute(
                requestId,
                sessionId,
                params,
            ) { result ->
                result.fold(
                    onSuccess = { respondSuccess(requestId, it) },
                    onFailure = { respondError(requestId, it) },
                )
            }
            "model.cancel" -> {
                modelTransport.cancelRequest(
                    params.getString("targetRequestId"),
                )
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.getModelCredentials" -> respondSuccess(
                requestId,
                JSONObject(modelCredentialStore.getAll().filterKeys { !it.startsWith("speech-") }),
            )
            "app.setModelCredential" -> {
                modelCredentialStore.set(params.getString("preset"), params.getString("apiKey"))
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.deleteModelCredential" -> {
                modelCredentialStore.delete(params.getString("preset"))
                respondSuccess(requestId, JSONObject.NULL)
            }
            "speech.checkConnection", "speech.hasCredential", "speech.setProvider", "speech.setCredential", "speech.start", "speech.cancel", "speech.finish" ->
                speech.call(method, params) { it.fold({ value -> respondSuccess(requestId, value) }, { error -> respondError(requestId, error) }) }
            "report.begin", "report.append", "report.finish", "report.abort", "report.open", "report.openExternal", "report.share", "report.export", "report.delete" ->
                reports.call(method, params) { it.fold({ value -> respondSuccess(requestId, value) }, { error -> respondError(requestId, error) }) }
            "runtime.doctor" -> executeNodeRuntimeDoctor(requestId)
            "runtime.prepareTest" -> {
                val values = params.getJSONObject("environment")
                nodeTestRunCoordinator.prepare(sessionId, values.keys().asSequence().associateWith { values.getString(it) })
                respondSuccess(requestId, JSONObject.NULL)
            }
            "runtime.discardPrepared" -> nodeRuntimeExecutor.execute {
                runCatching { nodeTestRunCoordinator.discardPrepared(sessionId) }.fold(
                    onSuccess = { respondSuccess(requestId, JSONObject.NULL) },
                    onFailure = { respondError(requestId, it) },
                )
            }
            "runtime.runTest" -> executeNodeTestRun(requestId, sessionId, params)
            "runtime.stop" -> nodeRuntimeExecutor.execute {
                runCatching { nodeTestRunCoordinator.stopAndWait() }.fold(
                    onSuccess = { respondSuccess(requestId, JSONObject().put("stopped", it)) },
                    onFailure = { respondError(requestId, it) },
                )
            }
            "app.getVersion" -> {
                val context = activity
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                respondSuccess(
                    requestId,
                    JSONObject()
                        .put("versionName", packageInfo.versionName ?: "")
                        .put("buildNumber", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageInfo)),
                )
            }
            "app.setLanguage" -> {
                respondSuccess(requestId, JSONObject.NULL)
                onLanguageChanged(params.getString("language"))
            }
            "app.setTheme" -> {
                respondSuccess(requestId, JSONObject.NULL)
                onThemeChanged(params.getString("theme"))
            }
            "app.getFloatingBallState" -> respondSuccess(
                requestId,
                JSONObject()
                    .put("enabled", FloatingBallPreferences.isEnabled(activity))
                    .put("available", service != null),
            )
            "app.setFloatingBallEnabled" -> {
                val enabled = params.getBoolean("enabled")
                FloatingBallPreferences.setEnabled(activity, enabled)
                service?.setFloatingBallEnabled(enabled)
                respondSuccess(
                    requestId,
                    JSONObject().put("enabled", enabled).put("available", service != null),
                )
            }
            "app.getEdgeEffectSettings" -> respondSuccess(
                requestId,
                EdgeEffectPreferences.describe(activity),
            )
            "app.previewGestures" -> {
                service?.previewGestures()
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.setEdgeEffectSettings" -> {
                val settings = EdgeEffectGeometrySettings(
                    mode = EdgeEffectGeometryMode.fromWireValue(params.getString("mode")),
                    cornerRadiusDp = params.getDouble("cornerRadiusDp").toFloat(),
                    insetDp = params.getDouble("insetDp").toFloat(),
                )
                EdgeEffectPreferences.set(activity, settings)
                service?.previewEdgeEffect(settings)
                respondSuccess(requestId, EdgeEffectPreferences.describe(activity))
            }
            "app.goHome" -> {
                respondSuccess(requestId, JSONObject.NULL)
                navigateAfter(GO_HOME_RESPONSE_DELAY_MILLIS) {
                    open(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            "app.returnToPreviousApp" -> {
                respondSuccess(requestId, JSONObject.NULL)
                navigateAfter(GO_HOME_RESPONSE_DELAY_MILLIS) { host.get()?.moveTaskToBack(true) }
            }
            "app.showGlobalResult" -> {
                service?.showGlobalResult(params.getString("message"))
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.bringToFront" -> {
                respondSuccess(requestId, JSONObject.NULL)
                navigateAfter(BRING_TO_FRONT_RESPONSE_DELAY_MILLIS) {
                    open(
                        Intent(activity, lifeos.md.tapilot.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
            "app.openAccessibilitySettings" -> {
                android.widget.Toast.makeText(activity, appText("辅助功能 → 已安装／已下载的应用 → Tapilot 设备控制 → 打开开关并确认，然后返回 Tapilot", "Accessibility → Installed/Downloaded apps → Tapilot device control → Enable and confirm, then return to Tapilot"), android.widget.Toast.LENGTH_LONG).show()
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                respondSuccess(requestId, JSONObject.NULL)
            }
            "app.openBatterySettings" -> {
                open(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    },
                )
                respondSuccess(requestId, JSONObject.NULL)
            }
            else -> throw UnsupportedOperationException("Unsupported bridge method: $method")
        }
    }

    private fun navigateAfter(delayMillis: Long, action: () -> Unit) {
        val epoch = navigationEpoch
        handler.postDelayed({ if (!closed && epoch == navigationEpoch) action() }, delayMillis)
    }

    private fun executeNodeTestRun(requestId: String, sessionId: String, params: JSONObject) {
        val environmentJson = params.getJSONObject("environment")
        val environment = environmentJson.keys().asSequence().associateWith { name ->
            environmentJson.getString(name)
        }
        runCatching {
            nodeTestRunCoordinator.run(
                source = params.getString("source"),
                kind = params.getString("kind"),
                environment = environment,
                reportTaskStatus = params.optBoolean("reportTaskStatus", true),
                preparationSession = sessionId,
            ) { result ->
                result.fold(
                    onSuccess = { respondSuccess(requestId, it) },
                    onFailure = { respondError(requestId, it) },
                )
            }
        }.onFailure { respondError(requestId, it) }
    }

    private fun executeNodeRuntimeDoctor(requestId: String) {
        nodeRuntimeExecutor.execute {
            runCatching { NodeRuntimeProvisioner(activity).install() }.fold(
                onSuccess = { runtime -> runNodeRuntimeDoctor(requestId, runtime) },
                onFailure = { respondError(requestId, it) },
            )
        }
    }

    private fun runNodeRuntimeDoctor(requestId: String, runtime: InstalledNodeRuntime) {
        val responded = AtomicBoolean(false)
        var result: JSONObject? = null
        fun failOnce(error: Throwable) {
            if (responded.compareAndSet(false, true)) respondError(requestId, error)
        }
        runCatching {
            nodeRuntimeSupervisor.runDoctor(
                runtime,
                object : NodeRuntimeListener {
                    override fun onLog(line: String) {
                        Log.d(LOG_TAG, "Node runtime: $line")
                    }

                    override fun onEvent(event: NodeRuntimeEvent) {
                        Log.d(LOG_TAG, "Node runtime event: ${event.type}")
                        if (event.type == "doctor.completed") result = JSONObject(event.payload)
                        if (event.type == "run.failed") {
                            failOnce(IllegalStateException(JSONObject(event.payload).optString("message")))
                        }
                    }

                    override fun onComplete(exitCode: Int) {
                        val doctorResult = result
                        if (exitCode == 0 && doctorResult != null) {
                            if (responded.compareAndSet(false, true)) {
                                respondSuccess(requestId, doctorResult)
                            }
                        } else {
                            failOnce(IllegalStateException("Node runtime doctor exited with code $exitCode"))
                        }
                    }

                    override fun onFailure(error: Throwable) = failOnce(error)
                },
            )
        }.onFailure(::failOnce)
    }

    private fun executeReportOperation(requestId: String, operation: () -> Any?) {
        reportExecutor.execute {
            runCatching(operation).fold(
                onSuccess = { respondSuccess(requestId, it) },
                onFailure = { respondError(requestId, it) },
            )
        }
    }

    private fun respondSuccess(requestId: String, result: Any?) = respond(requestId, Result.success(result.takeUnless { it == JSONObject.NULL }))
    private fun respondError(requestId: String, error: Throwable) = respond(requestId, Result.failure(error))
    private fun respond(requestId: String, result: Result<Any?>) {
        // call() already dispatches asynchronously to main. A main-thread result
        // need not wait behind another UI frame; background results still hop back.
        if (android.os.Looper.myLooper() == handler.looper) {
            callbacks.remove(requestId)?.invoke(result)
        } else {
            handler.post { callbacks.remove(requestId)?.invoke(result) }
        }
    }

    override fun close() {
        closed = true
        callbacks.values.toList().forEach { it(Result.failure(IllegalStateException("Service closed"))) }
        callbacks.clear()
        speech.close()
        modelTransport.close()
        nodeTestRunCoordinator.close()
        reportRepository.close()
    }

    companion object {
        private const val LOG_TAG = "MidsceneBridge"
        private const val GO_HOME_RESPONSE_DELAY_MILLIS = 50L
        private const val BRING_TO_FRONT_RESPONSE_DELAY_MILLIS = 50L
        private val reportExecutor = Executors.newSingleThreadExecutor()
        private val nodeRuntimeExecutor = Executors.newSingleThreadExecutor()
    }
}
