package lifeos.md.tapilot.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import lifeos.md.tapilot.AppLanguage
import lifeos.md.tapilot.AppForegroundTracker
import lifeos.md.tapilot.TaskExecutionStatus
import lifeos.md.tapilot.TaskExecutionTracker
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@Suppress("DEPRECATION")
class MidsceneAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val screenshotPacer = ScreenshotRequestPacer()
    private val cancelledSessions = ConcurrentHashMap.newKeySet<String>()
    private lateinit var taskEffects: TaskEffectController
    private lateinit var gestureEffects: GestureEffectController
    private lateinit var floatingBall: FloatingBallController
    private val gestureInput = GestureInputGuard { floatingBall.setGesturePassThrough(it) }
    @Volatile private var latestCapture: CapturePayload? = null
    private val extendedActions by lazy {
        ExtendedDeviceActions(this) { description, callback ->
            dispatchManagedGesture(description, "Multi-finger gesture", callback)
        }
    }
    private val continuousEffectCapture by lazy { ContinuousEffectCapture(this) }
    private val textInput by lazy {
        TextInputController(this, handler, { it in cancelledSessions }, ::gesture, ::globalAction)
    }

    fun capabilityDetails(): org.json.JSONObject = org.json.JSONObject()
        .put("captureFingerprint", FrameFingerprint.ALGORITHM)
        .put("systemActions", org.json.JSONArray(extendedActions.availableSystemActions()))
        .put("apps", extendedActions.apps())

    override fun onServiceConnected() {
        AppLanguage.apply(this)
        if (::floatingBall.isInitialized) floatingBall.close()
        if (::taskEffects.isInitialized) taskEffects.close()
        if (::gestureEffects.isInitialized) gestureEffects.close()
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        taskEffects = TaskEffectController(this, windowManager, handler)
        gestureEffects = GestureEffectController(this, windowManager, handler)
        floatingBall = FloatingBallController(this, windowManager).apply {
            setAppForeground(AppForegroundTracker.isForeground)
            setTaskRunning(TaskExecutionTracker.isActive)
        }
        // Publish only after every operation dependency is ready. WebView RPC can
        // call current() immediately after receiving the connection callback.
        instance = WeakReference(this)
        notifyConnectionChanged(true)
        // Some OEMs rebind accessibility after updates without delivering the package broadcast.
        lifeos.md.tapilot.wireless.WirelessControlService.restoreIfEnabled(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        publishDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::floatingBall.isInitialized) floatingBall.close()
        if (::taskEffects.isInitialized) taskEffects.close()
        cancelledSessions.clear()
        latestCapture = null
        publishDisconnected()
        super.onDestroy()
    }

    private fun publishDisconnected() {
        if (instance.get() !== this) return
        instance.clear()
        notifyConnectionChanged(false)
    }

    fun cancelSession(sessionId: String) {
        cancelledSessions.add(sessionId)
        handler.postDelayed(
            { cancelledSessions.remove(sessionId) },
            CANCELLED_SESSION_RETENTION_MS,
        )
    }

    fun completeSession(sessionId: String) {
        cancelledSessions.remove(sessionId)
    }

    fun validateFrame(frameId: String) {
        val capture = latestCapture
        if (capture?.frameId != frameId) throw StaleFrameException()
        val metrics = deviceDisplayMetrics()
        val rotation = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation ?: 0
        if (capture.rotation != rotation ||
            capture.screenWidth != metrics.widthPixels ||
            capture.screenHeight != metrics.heightPixels
        ) {
            throw StaleFrameException()
        }
    }

    fun updateTaskStatus(status: TaskExecutionStatus, detail: String? = null, showEffect: Boolean = true) {
        TaskExecutionTracker.update(status)
        onMain {
            // Desktop control already shows progress in its panel; avoid flashing the phone edges.
            taskEffects.update(if (showEffect) status else TaskExecutionStatus.IDLE, if (showEffect) detail else null)
            floatingBall.setTaskRunning(TaskExecutionTracker.isActive)
        }
    }

    fun refreshTaskRunning() = onMain {
        if (::floatingBall.isInitialized) {
            floatingBall.setTaskRunning(TaskExecutionTracker.isActive)
        }
    }

    fun setAppForeground(foreground: Boolean) = onMain {
        if (::floatingBall.isInitialized) floatingBall.setAppForeground(foreground)
    }

    fun setFloatingBallEnabled(enabled: Boolean) = onMain {
        if (::floatingBall.isInitialized) floatingBall.setEnabled(enabled)
    }

    fun showGlobalResult(message: String) = onMain {
        if (::floatingBall.isInitialized) floatingBall.showResult(message)
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    internal fun previewGestures() {
        if (::gestureEffects.isInitialized) gestureEffects.preview()
    }

    internal fun previewEdgeEffect(settings: EdgeEffectGeometrySettings) {
        if (::taskEffects.isInitialized) taskEffects.preview(settings)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::floatingBall.isInitialized) { floatingBall.updatePosition(); floatingBall.refreshTheme() }
    }

    fun capture(sessionId: String, recordFrame: Boolean = true, jpeg: Boolean = false, metadataOnly: Boolean = false, hideOverlays: Boolean = true, callback: (Result<CapturePayload>) -> Unit) {
        if (isCancelled(sessionId, callback)) return
        // Live preview is for the user: preserve phone overlays instead of blinking
        // them off and on for every frame. Model captures retain the clean path.
        if (!hideOverlays) {
            captureFullDisplay(sessionId, callback, recordFrame = recordFrame, jpeg = jpeg, metadataOnly = metadataOnly, hideOverlays = false)
            return
        }
        if (continuousEffectCapture.tryCapture(deliver = { result ->
            if (cancelledSessions.contains(sessionId)) {
                result.hardwareBuffer.close()
                callback(Result.failure(SessionCancelledException()))
            } else {
                runCatching { encodeCapture(result, recordFrame, jpeg, metadataOnly) }.fold(
                    onSuccess = { callback(Result.success(it)) },
                    onFailure = { handler.postDelayed({ captureFullDisplay(sessionId, callback, recordFrame = recordFrame, jpeg = jpeg, metadataOnly = metadataOnly) }, 350L) },
                )
            }
        }, fallback = {
            // Some devices share a rate limit between window and display captures.
            handler.postDelayed({ captureFullDisplay(sessionId, callback, recordFrame = recordFrame, jpeg = jpeg, metadataOnly = metadataOnly) }, 350L)
        })) return
        captureFullDisplay(sessionId, callback, recordFrame = recordFrame, jpeg = jpeg, metadataOnly = metadataOnly)
    }

    private fun encodeCapture(result: ScreenshotResult, recordFrame: Boolean, jpeg: Boolean, metadataOnly: Boolean): CapturePayload {
        val started = android.os.SystemClock.elapsedRealtime()
        val hardwareBuffer = result.hardwareBuffer
        val bitmap = try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Unable to copy screenshot buffer")
        } finally { hardwareBuffer.close() }
        val copied = android.os.SystemClock.elapsedRealtime()
        try {
            val dataUrl = if (metadataOnly) "" else {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) (if (recordFrame) 90 else 75) else 100, output)) { "Unable to encode screenshot" }
                (if (jpeg) "data:image/jpeg;base64," else "data:image/png;base64,") + Base64.getEncoder().encodeToString(output.toByteArray())
            }
            val encoded = android.os.SystemClock.elapsedRealtime()
            val fingerprint = if (recordFrame && !metadataOnly) FrameFingerprint.capture(bitmap) else null
            captureTiming("encode", started, org.json.JSONObject()
                .put("copyMs", copied - started).put("encodeMs", encoded - copied)
                .put("fingerprintMs", android.os.SystemClock.elapsedRealtime() - encoded)
                .put("jpeg", jpeg).put("metadataOnly", metadataOnly))
            val metrics = deviceDisplayMetrics()
            return CapturePayload(
                dataUrl = dataUrl,
                frameId = UUID.randomUUID().toString(), capturedAt = System.currentTimeMillis(),
                rotation = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0,
                screenWidth = metrics.widthPixels, screenHeight = metrics.heightPixels,
                imageWidth = bitmap.width, imageHeight = bitmap.height,
                fingerprint = fingerprint,
            ).also { if (recordFrame) latestCapture = it }
        } finally { bitmap.recycle() }
    }

    // Debug numeric diagnostics only. Never record frame contents or session identifiers.
    private fun captureTiming(stage: String, started: Long, fields: org.json.JSONObject = org.json.JSONObject()) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            android.util.Log.i("TapilotPerformance", fields.put("phase", "capture.$stage")
                .put("durationMs", android.os.SystemClock.elapsedRealtime() - started).toString())
        }
    }

    private fun captureFullDisplay(
        sessionId: String,
        callback: (Result<CapturePayload>) -> Unit,
        retryAttempt: Int = 0,
        recordFrame: Boolean = true,
        jpeg: Boolean = false,
        metadataOnly: Boolean = false,
        hideOverlays: Boolean = true,
        paced: Boolean = false,
    ) {
        if (isCancelled(sessionId, callback)) return
        // After an observed Android rate limit, wait the remaining interval
        // before hiding overlays. Avoid a predictably rejected request followed
        // by a full backoff; retain the bounded retry policy for OEM differences.
        // Overlay settling happens before takeScreenshot too. Count that guaranteed
        // delay toward the request interval instead of waiting for it twice.
        val pacingDelay = screenshotPacer.delayMillis(
            android.os.SystemClock.elapsedRealtime(),
            if (hideOverlays) OVERLAY_SETTLE_MS else 0L,
        )
        // One pacing wait per attempt: concurrent preview traffic must not
        // postpone an AI capture indefinitely by moving the deadline again.
        if (!paced && pacingDelay > 0L) {
            val started = android.os.SystemClock.elapsedRealtime()
            handler.postDelayed({
                captureTiming("pacing", started)
                captureFullDisplay(sessionId, callback, retryAttempt, recordFrame, jpeg, metadataOnly, hideOverlays, paced = true)
            }, pacingDelay)
            return
        }
        // Full-display capture includes accessibility overlays. Hide only while
        // Android latches the frame so AI input and report-player frames stay
        // clean. The animator keeps advancing and resumes at its current phase.
        val queued = android.os.SystemClock.elapsedRealtime()
        if (hideOverlays && ::gestureEffects.isInitialized) gestureEffects.clear()
        val hiddenTaskEffect = if (hideOverlays) taskEffects.hideForCapture() else null
        val hiddenFloatingBall = if (hideOverlays) floatingBall.hideForCapture() else false
        handler.postDelayed({
            if (isCancelled(sessionId, callback)) {
                hiddenTaskEffect?.let { taskEffects.restoreAfterCapture(it) }
                if (hideOverlays) floatingBall.restoreAfterCapture(hiddenFloatingBall)
                return@postDelayed
            }
            captureTiming("overlayWait", queued)
            val requested = android.os.SystemClock.elapsedRealtime()
            screenshotPacer.requested(requested)
            runCatching { takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        captureTiming("system", requested)
                        try {
                            if (cancelledSessions.contains(sessionId)) {
                                result.hardwareBuffer.close()
                                callback(Result.failure(SessionCancelledException()))
                            } else callback(Result.success(encodeCapture(result, recordFrame, jpeg, metadataOnly)))
                        } catch (error: Throwable) {
                            callback(Result.failure(error))
                        } finally {
                            hiddenTaskEffect?.let { taskEffects.restoreAfterCapture(it) }
                            if (hideOverlays) floatingBall.restoreAfterCapture(hiddenFloatingBall)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) screenshotPacer.rateLimited()
                        captureTiming("systemFailure", requested, org.json.JSONObject().put("errorCode", errorCode))
                        hiddenTaskEffect?.let { taskEffects.restoreAfterCapture(it) }
                        if (hideOverlays) floatingBall.restoreAfterCapture(hiddenFloatingBall)
                        val retryDelay = ScreenshotFailurePolicy.retryDelayMillis(
                            errorCode,
                            retryAttempt,
                        )
                        if (retryDelay != null) {
                            handler.postDelayed(
                                { captureFullDisplay(sessionId, callback, retryAttempt + 1, recordFrame, jpeg, metadataOnly, hideOverlays) },
                                retryDelay,
                            )
                            return
                        }
                        val disconnected =
                            errorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR &&
                            runCatching { serviceInfo == null }.getOrDefault(true)
                        callback(Result.failure(IllegalStateException(
                            ScreenshotFailurePolicy.message(errorCode, disconnected),
                        )))
                    }
                },
            ) }.onFailure {
                hiddenTaskEffect?.let { taskEffects.restoreAfterCapture(it) }
                if (hideOverlays) floatingBall.restoreAfterCapture(hiddenFloatingBall)
                callback(Result.failure(it))
            }
        }, if (hideOverlays) OVERLAY_SETTLE_MS else 0L)
    }

    fun execute(sessionId: String, action: NativeAction, callback: (Result<Unit>) -> Unit) {
        if (isCancelled(sessionId, callback)) return
        when (action) {
            is NativeAction.System -> if (action.action == "saveScreenshot") {
                captureFullDisplay(sessionId, callback = { captureResult ->
                    captureResult.fold(onSuccess = { payload ->
                        screenshotSaveExecutor.execute {
                            val result = runCatching {
                                ScreenshotStore.save(contentResolver, payload.dataUrl) { sessionId in cancelledSessions }
                            }
                            handler.post { callback(result) }
                        }
                    }, onFailure = { callback(Result.failure(it)) })
                })
            } else callback(runCatching { extendedActions.system(action.action) })
            is NativeAction.EditText -> callback(runCatching { extendedActions.edit(action) })
            is NativeAction.MultiGesture -> {
                val (scaleX, scaleY) = gestureCoordinateScale()
                // A continued stroke must retain the same input window state from
                // pointer-down through pointer-up, including between dispatches.
                val lease = runCatching { gestureInput.acquire() }.getOrElse { callback(Result.failure(it)); return }
                runCatching {
                    extendedActions.gesture(action, scaleX, scaleY, { sessionId in cancelledSessions }) { result ->
                        runCatching { lease.close() }
                        callback(result)
                    }
                }.onFailure {
                    runCatching { lease.close() }
                    callback(Result.failure(it))
                }
            }
            is NativeAction.Tap -> gesture(
                action.x,
                action.y,
                action.x,
                action.y,
                action.duration ?: 50L,
            ) { result ->
                if (result.isSuccess) {
                    textInput.rememberTap(action.x, action.y)
                }
                callback(result)
            }
            is NativeAction.DoubleTap -> gesture(
                action.x,
                action.y,
                action.x,
                action.y,
                50L,
            ) { first ->
                if (first.isFailure) callback(first)
                else handler.postDelayed({
                    if (!isCancelled(sessionId, callback)) {
                        gesture(action.x, action.y, action.x, action.y, 50L, callback)
                    }
                }, 80L)
            }
            is NativeAction.LongPress -> gesture(
                action.x,
                action.y,
                action.x,
                action.y,
                action.duration ?: 800L,
                callback,
            )
            is NativeAction.Swipe -> gesture(
                action.startX,
                action.startY,
                action.endX,
                action.endY,
                action.duration ?: 350L,
                callback,
            )
            is NativeAction.Pinch -> pinchGesture(action, callback)
            is NativeAction.Input -> textInput.inputText(sessionId, action, callback)
            is NativeAction.ClearInput -> textInput.inputText(
                sessionId,
                NativeAction.Input("", action.x, action.y, true, false),
                callback,
            )
            is NativeAction.Key -> textInput.performKey(action.keyName, callback)
            is NativeAction.CursorMove -> textInput.performCursorMove(
                action.direction,
                action.times,
                callback,
            )
            NativeAction.Back -> globalAction(GLOBAL_ACTION_BACK, callback)
            NativeAction.Home -> globalAction(GLOBAL_ACTION_HOME, callback)
            NativeAction.RecentApps -> globalAction(GLOBAL_ACTION_RECENTS, callback)
            is NativeAction.AppDetails -> openAppDetails(action.packageOrName, callback)
            is NativeAction.Launch -> launch(action.uri, callback)
        }
    }

    private fun gesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
        callback: (Result<Unit>) -> Unit,
    ) {
        val (scaleX, scaleY) = gestureCoordinateScale()
        val path = Path().apply {
            moveTo(startX * scaleX, startY * scaleY)
            if (startX != endX || startY != endY) {
                lineTo(endX * scaleX, endY * scaleY)
            }
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
            .build()
        dispatchManagedGesture(description, "Gesture", callback)
    }

    private fun pinchGesture(action: NativeAction.Pinch, callback: (Result<Unit>) -> Unit) {
        val (scaleX, scaleY) = gestureCoordinateScale()
        val metrics = deviceDisplayMetrics()
        val geometry = pinchGeometry(action, metrics.widthPixels, metrics.heightPixels)
        val centerX = geometry.centerX * scaleX
        val centerY = geometry.centerY * scaleY
        val distanceScale = minOf(scaleX, scaleY)
        val startRadius = geometry.startRadius * distanceScale
        val endRadius = geometry.endRadius * distanceScale
        val leftFinger = Path().apply {
            moveTo(centerX - startRadius, centerY)
            lineTo(centerX - endRadius, centerY)
        }
        val rightFinger = Path().apply {
            moveTo(centerX + startRadius, centerY)
            lineTo(centerX + endRadius, centerY)
        }
        val duration = action.duration.coerceAtLeast(1L)
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(leftFinger, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(rightFinger, 0, duration))
            .build()
        dispatchManagedGesture(description, "Pinch gesture", callback)
    }

    private fun dispatchManagedGesture(description: GestureDescription, name: String, callback: (Result<Unit>) -> Unit) {
        val lease = runCatching { gestureInput.acquire() }.getOrElse { callback(Result.failure(it)); return }
        var completed = false
        fun finish(result: Result<Unit>) {
            if (completed) return
            completed = true
            // A restoration failure must not turn an executed mutation into a retry.
            runCatching { lease.close() }.onFailure {
                android.util.Log.w("TapilotGesture", "Unable to restore task overlay input", it)
            }
            callback(result)
        }
        // Apply overlay input changes before injecting on the next main-loop turn.
        handler.post {
            runCatching {
                if (::gestureEffects.isInitialized) gestureEffects.show(description)
                val accepted = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = finish(Result.success(Unit))
                    override fun onCancelled(gestureDescription: GestureDescription?) = finish(Result.failure(IllegalStateException("$name was cancelled")))
                }, handler)
                if (!accepted) finish(Result.failure(IllegalStateException("$name was rejected")))
            }.onFailure { finish(Result.failure(it)) }
        }
    }

    private fun gestureCoordinateScale(): Pair<Float, Float> {
        val metrics = deviceDisplayMetrics()
        val currentDisplay = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return 1f to 1f
        val mode = currentDisplay.mode
        val rotated = currentDisplay.rotation == android.view.Surface.ROTATION_90 ||
            currentDisplay.rotation == android.view.Surface.ROTATION_270
        val physicalWidth = if (rotated) mode.physicalHeight else mode.physicalWidth
        val physicalHeight = if (rotated) mode.physicalWidth else mode.physicalHeight
        return calculateGestureCoordinateScale(
            logicalWidth = metrics.widthPixels,
            logicalHeight = metrics.heightPixels,
            physicalWidth = physicalWidth,
            physicalHeight = physicalHeight,
        )
    }

    private fun launch(uri: String, callback: (Result<Unit>) -> Unit) {
        runCatching {
            val intent = when {
                uri.contains("://") -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                uri.contains("/") -> {
                    val component = ComponentName.unflattenFromString(uri)
                        ?: throw IllegalArgumentException("Invalid Android component: $uri")
                    Intent().setComponent(component)
                }
                else -> {
                    val packageName = packageManager.getLaunchIntentForPackage(uri)?.let { uri }
                        ?: LaunchableAppResolver.resolve(
                            uri,
                            packageManager.queryIntentActivities(
                                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                                0,
                            ).map { app ->
                                LaunchableApp(
                                    app.loadLabel(packageManager)?.toString().orEmpty(),
                                    app.activityInfo.packageName,
                                )
                            },
                        )
                        ?: throw IllegalArgumentException("No launchable app found for package or name: $uri")
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: throw IllegalArgumentException("No launchable app found for package: $packageName")
                }
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }.fold(
            onSuccess = { callback(Result.success(Unit)) },
            onFailure = { callback(Result.failure(it)) },
        )
    }

    private fun openAppDetails(packageOrName: String, callback: (Result<Unit>) -> Unit) {
        runCatching {
            val packageName = packageManager.getLaunchIntentForPackage(packageOrName)?.let {
                packageOrName
            } ?: LaunchableAppResolver.resolve(
                packageOrName,
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                ).map { app ->
                    LaunchableApp(
                        app.loadLabel(packageManager)?.toString().orEmpty(),
                        app.activityInfo.packageName,
                    )
                },
            ) ?: throw IllegalArgumentException(
                "No installed launcher app found for package or name: $packageOrName",
            )
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.fold(
            onSuccess = { callback(Result.success(Unit)) },
            onFailure = { callback(Result.failure(it)) },
        )
    }

    private fun globalAction(action: Int, callback: (Result<Unit>) -> Unit) {
        if (performGlobalAction(action)) callback(Result.success(Unit))
        else callback(Result.failure(IllegalStateException("Global action was rejected: $action")))
    }

    private fun <T> isCancelled(sessionId: String, callback: (Result<T>) -> Unit): Boolean {
        if (!cancelledSessions.contains(sessionId)) return false
        callback(Result.failure(SessionCancelledException()))
        return true
    }

    data class CapturePayload(
        val dataUrl: String,
        val frameId: String,
        val capturedAt: Long,
        val rotation: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val fingerprint: String? = null,
    )

    class SessionCancelledException : IllegalStateException("Task session was cancelled")
    class StaleFrameException : IllegalStateException(
        "The screen changed after it was captured; capture a fresh frame before acting",
    )

    companion object {
        private val screenshotSaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        // Two 60 Hz frames are enough for WindowManager to retire accessibility
        // overlay surfaces while keeping the running effect visually continuous.
        private const val OVERLAY_SETTLE_MS = 32L
        private const val CANCELLED_SESSION_RETENTION_MS = 5 * 60 * 1000L
        private var instance = WeakReference<MidsceneAccessibilityService>(null)
        private val connectionListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun current(): MidsceneAccessibilityService? = instance.get()?.takeIf { service ->
            // A service object can outlive its system connection on some ROMs.
            // Android returns null here when the accessibility connection is gone.
            runCatching { service.serviceInfo != null }.getOrDefault(false)
        }

        fun addConnectionListener(listener: (Boolean) -> Unit) {
            connectionListeners.add(listener)
            listener(current() != null)
        }

        fun removeConnectionListener(listener: (Boolean) -> Unit) {
            connectionListeners.remove(listener)
        }

        private fun notifyConnectionChanged(connected: Boolean) {
            connectionListeners.forEach { listener -> listener(connected) }
        }
    }
}
