package com.hackson.spatialnav.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.hackson.spatialnav.BuildConfig
import com.hackson.spatialnav.R
import com.hackson.spatialnav.ai.DestinationResolver
import com.hackson.spatialnav.ai.OpenAiDestinationService
import com.hackson.spatialnav.ar.BackgroundRenderer
import com.hackson.spatialnav.ar.CloudAnchorStrategy
import com.hackson.spatialnav.ar.ManualOriginStrategy
import com.hackson.spatialnav.ar.SpatialReferenceStrategy
import com.hackson.spatialnav.ar.TrackingStabilizer
import com.hackson.spatialnav.databinding.ActivityRoomBinding
import com.hackson.spatialnav.model.Destination
import com.hackson.spatialnav.model.RoomMap
import com.hackson.spatialnav.model.SpatialReferenceRecord
import com.hackson.spatialnav.model.Vec3
import com.hackson.spatialnav.navigation.GuidanceAnnouncer
import com.hackson.spatialnav.navigation.NavigationEngine
import com.hackson.spatialnav.navigation.NavigationFrame
import com.hackson.spatialnav.perception.DepthClearanceAnalyzer
import com.hackson.spatialnav.perception.DepthSampler
import com.hackson.spatialnav.persistence.RoomStore
import com.hackson.spatialnav.util.RollingFps
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * The whole product in one screen: align the space, pick a destination, walk there.
 *
 * All three phases share a single ARCore session because relocalizing is expensive and the
 * pose must stay continuous between picking a destination and following it.
 *
 * The room's coordinate frame is defined by the spatial reference anchor, *not* by wherever
 * the session happened to start — that is what makes the saved destinations mean the same
 * physical place tomorrow. Every frame the camera pose is re-expressed relative to that
 * anchor, so ARCore's continuous corrections to the anchor pose are picked up for free.
 */
class RoomActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private enum class Relocalization { NOT_STARTED, SEARCHING, SUCCESS, FAILED }

    private enum class Phase { ALIGNING, PICKING, NAVIGATING }

    private lateinit var binding: ActivityRoomBinding
    private lateinit var store: RoomStore
    private val renderer = BackgroundRenderer()
    private val fps = RollingFps()
    private val stabilizer = TrackingStabilizer()
    private val announcer = GuidanceAnnouncer()
    private val destinationService = OpenAiDestinationService()
    private val obstacles = DepthClearanceAnalyzer()

    /** Work that must run on the GL thread, where the ARCore session is driven. */
    private val glTasks = ConcurrentLinkedQueue<(Frame) -> Unit>()

    private lateinit var strategy: SpatialReferenceStrategy
    private var session: Session? = null
    private var sessionStatus = "not created yet"
    private var installRequested = false
    private var glInitialized = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportDirty = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Null in create mode until the room is anchored. */
    @Volatile
    private var room: RoomMap? = null

    private var pendingRoomName: String = ""

    @Volatile
    private var relocalization = Relocalization.NOT_STARTED

    @Volatile
    private var relocalizationDetail = ""

    @Volatile
    private var trackingText = "starting"

    @Volatile
    private var localPose: NavigationFrame.Local? = null

    private var phase = Phase.ALIGNING

    @Volatile
    private var target: Destination? = null

    @Volatile
    private var fix: NavigationEngine.Fix? = null

    @Volatile
    private var obstacleState = DepthClearanceAnalyzer.State.UNKNOWN

    private var lastSpokenObstacle: DepthClearanceAnalyzer.State? = null
    private var lastObstacleSpeechMs = 0L
    private var depthSupported = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                sessionStatus = "camera permission denied"
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RoomStore(RoomStore.defaultDirectory(this))

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId != null) {
            room = store.load(roomId) ?: run {
                Toast.makeText(this, "space not found", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else {
            pendingRoomName = intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty().ifEmpty { "Room" }
        }
        strategy = chooseStrategy(room?.spatialReference?.strategy)
        Log.i(TAG, "ROOM_OPEN mode=${if (roomId == null) "create" else "open"} strategy=${strategy.id}")

        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.primaryButton.setOnClickListener { onPrimaryAction() }
        binding.addDestinationButton.setOnClickListener { askDestinationToAdd() }
        binding.findDestinationButton.setOnClickListener { askAssistant() }
        binding.askInput.setOnEditorActionListener { _, _, _ ->
            askAssistant()
            true
        }
        binding.stopButton.setOnClickListener { stopNavigation() }
        binding.debugToggle.setOnClickListener {
            binding.debugText.visibility =
                if (binding.debugText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.US
        }

        render()
    }

    /**
     * Cloud Anchors are preferred because they need no physical marker, but an existing room
     * must be reopened with the strategy it was created with, and a build without an API key
     * can only do manual origins.
     */
    private fun chooseStrategy(savedStrategy: String?): SpatialReferenceStrategy = when {
        savedStrategy == ManualOriginStrategy.ID -> ManualOriginStrategy()
        savedStrategy == CloudAnchorStrategy.ID ->
            CloudAnchorStrategy(BuildConfig.ARCORE_API_KEY_CONFIGURED)

        BuildConfig.ARCORE_API_KEY_CONFIGURED -> CloudAnchorStrategy(true)
        else -> ManualOriginStrategy()
    }

    override fun onResume() {
        super.onResume()
        if (session == null && !ensureSession()) {
            render()
            return
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            sessionStatus = "camera not available: ${e.message}"
            session?.close()
            session = null
            render()
            return
        }
        binding.surfaceView.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
        if (session != null) {
            binding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        destinationService.shutdown()
        tts?.shutdown()
        tts = null
        if (::strategy.isInitialized) strategy.close()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun ensureSession(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            binding.root.postDelayed({ if (session == null) { ensureSession(); render() } }, 200L)
            sessionStatus = "checking availability (${availability.name})"
            return false
        }
        if (!availability.isSupported) {
            sessionStatus = "unsupported device (${availability.name})"
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sessionStatus = "waiting for camera permission"
            requestCamera.launch(Manifest.permission.CAMERA)
            return false
        }
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    sessionStatus = "installing Google Play Services for AR"
                    return false
                }

                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val created = Session(this)
            created.configure(
                Config(created).apply {
                    depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                    depthSupported = depthMode == Config.DepthMode.AUTOMATIC
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    // Hosting and resolving both need this; harmless when unused.
                    cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                }
            )
            session = created
            sessionStatus = "created"
            if (glInitialized) {
                created.setCameraTextureName(renderer.textureId)
                viewportDirty = true
            }
            return true
        } catch (e: UnavailableException) {
            sessionStatus = "session creation failed: ${e.javaClass.simpleName}: ${e.message}"
            return false
        }
    }

    // ---- room actions -------------------------------------------------------------------

    /** Anchors a new room, or aligns an existing one, depending on the mode and strategy. */
    private fun onPrimaryAction() {
        if (stabilizer.state != TrackingStabilizer.State.READY) {
            toast("Tracking is not ready yet — keep moving the phone slowly.")
            return
        }
        val existing = room
        if (existing == null) {
            askOriginDescription { description -> createRoom(description) }
        } else {
            startResolve(existing)
        }
    }

    private fun askOriginDescription(onConfirmed: (String) -> Unit) {
        if (strategy.id == CloudAnchorStrategy.ID) {
            // A cloud anchor is found by looking around, so the description is only a note.
            onConfirmed("hosted where the room was created")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.origin_description)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                onConfirmed(input.text.toString().trim().ifEmpty { "the starting spot" })
            }
            .show()
    }

    private fun createRoom(originDescription: String) {
        val active = session ?: return
        relocalization = Relocalization.SEARCHING
        relocalizationDetail = "creating spatial reference"
        render()
        glTasks += { frame ->
            strategy.create(active, frame.camera.pose, originDescription) { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = { record -> onRoomAnchored(record) },
                        onFailure = { error -> onReferenceFailed(error) },
                    )
                }
            }
        }
    }

    private fun onRoomAnchored(record: SpatialReferenceRecord) {
        val now = System.currentTimeMillis()
        val created = RoomMap(
            roomId = UUID.randomUUID().toString().take(8),
            displayName = pendingRoomName,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            spatialReference = record,
        )
        room = created
        store.save(created)
        relocalization = Relocalization.SUCCESS
        relocalizationDetail = "reference created"
        Log.i(TAG, "ROOM_CREATED id=${created.roomId} strategy=${record.strategy} " +
            "cloudAnchorId=${record.cloudAnchorId ?: "-"}")
        onAligned("Space saved. Walk to a place and add it.")
    }

    private fun startResolve(existing: RoomMap) {
        val active = session ?: return
        if (relocalization == Relocalization.SEARCHING) return
        relocalization = Relocalization.SEARCHING
        relocalizationDetail = "resolving ${existing.spatialReference.strategy}"
        Log.i(TAG, "RELOCALIZATION=SEARCHING room=${existing.roomId}")
        render()
        glTasks += { frame ->
            strategy.resolve(active, existing.spatialReference, frame.camera.pose) { result ->
                runOnUiThread {
                    result.fold(
                        onSuccess = {
                            relocalization = Relocalization.SUCCESS
                            relocalizationDetail = "reference recovered"
                            Log.i(TAG, "RELOCALIZATION=SUCCESS room=${existing.roomId}")
                            onAligned("${existing.displayName} is ready.")
                        },
                        onFailure = { error -> onReferenceFailed(error) },
                    )
                }
            }
        }
    }

    private fun onAligned(spokenMessage: String) {
        phase = Phase.PICKING
        speak(spokenMessage)
        render()
    }

    private fun onReferenceFailed(error: Throwable) {
        relocalization = Relocalization.FAILED
        relocalizationDetail = error.message ?: error.javaClass.simpleName
        Log.w(TAG, "RELOCALIZATION=FAILED $relocalizationDetail")
        render()
    }

    private fun askDestinationToAdd() {
        val presets = arrayOf(
            getString(R.string.mark_bathroom),
            getString(R.string.mark_door),
            getString(R.string.mark_desk),
            getString(R.string.mark_custom),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_destination)
            .setItems(presets) { _, index ->
                if (index == presets.lastIndex) askCustomDestination() else markDestination(presets[index])
            }
            .show()
    }

    private fun askCustomDestination() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.destination_name)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) markDestination(name)
            }
            .show()
    }

    /**
     * Records where the phone is *now* in room coordinates. Re-marking a name overwrites it,
     * so a badly placed destination is fixed by walking back and pressing the button again.
     */
    private fun markDestination(name: String) {
        val current = room
        val local = localPose
        if (current == null || relocalization != Relocalization.SUCCESS || local == null) {
            toast("Align the space first.")
            return
        }
        val now = System.currentTimeMillis()
        val destination = Destination(
            id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_"),
            name = name,
            position = Vec3(local.x, local.y, local.z),
            approachHeadingDeg = local.yawDeg,
            createdAtEpochMs = now,
        )
        val updated = current.withDestination(destination, now)
        room = updated
        store.save(updated)
        Log.i(TAG, "DESTINATION_MARKED name=$name x=%+.3f y=%+.3f z=%+.3f yaw=%+.1f"
            .format(local.x, local.y, local.z, local.yawDeg))
        speak("$name saved here.")
        render()
    }

    // ---- natural language ---------------------------------------------------------------

    /**
     * The assistant only picks a name out of the destinations this room already has; if it
     * cannot, the user is pointed back at the buttons, which never stop working.
     */
    private fun askAssistant() {
        val request = binding.askInput.text.toString().trim()
        if (request.isEmpty()) return
        val destinations = room?.destinations.orEmpty()
        if (destinations.isEmpty()) {
            showAssistantStatus(getString(R.string.ai_no_match))
            return
        }
        binding.findDestinationButton.isEnabled = false
        showAssistantStatus(getString(R.string.ai_thinking))
        destinationService.resolve(request, destinations) { outcome ->
            binding.findDestinationButton.isEnabled = true
            when (outcome) {
                is DestinationResolver.Outcome.Matched -> {
                    binding.askInput.text.clear()
                    showAssistantStatus(null)
                    startNavigation(outcome.destination)
                }

                DestinationResolver.Outcome.NoMatch ->
                    showAssistantStatus(getString(R.string.ai_no_match))

                is DestinationResolver.Outcome.Failed ->
                    showAssistantStatus("${getString(R.string.ai_no_match)} (${outcome.reason})")
            }
        }
    }

    private fun showAssistantStatus(text: String?) {
        binding.aiStatus.text = text.orEmpty()
        binding.aiStatus.visibility = visibleIf(text != null)
    }

    // ---- navigation ---------------------------------------------------------------------

    private fun startNavigation(destination: Destination) {
        target = destination
        fix = null
        announcer.reset()
        obstacles.reset()
        obstacleState = DepthClearanceAnalyzer.State.UNKNOWN
        lastSpokenObstacle = null
        phase = Phase.NAVIGATING
        Log.i(NAV_TAG, "NAV_START destination=${destination.name} " +
            "target=%+.2f,%+.2f".format(destination.position.x, destination.position.z))
        speak("Navigating to ${destination.name}.")
        render()
    }

    private fun stopNavigation() {
        target?.let { Log.i(NAV_TAG, "NAV_STOP destination=${it.name}") }
        target = null
        fix = null
        phase = Phase.PICKING
        tts?.stop()
        render()
    }

    private fun updateNavigation(local: NavigationFrame.Local) {
        val destination = target ?: return
        val solved = NavigationEngine.solve(local, destination.position)
        fix = solved
        // The announcer still runs while an obstacle is being cleared so its milestones
        // stay current, but the obstacle layer owns the voice until the way is open.
        val phrase = announcer.next(solved, destination.name, System.currentTimeMillis())
        if (phrase != null && !obstacleState.isObstacle) speak(phrase)
        if (fps.totalFrames % POSE_LOG_INTERVAL == 0L) {
            Log.i(
                NAV_TAG,
                ("destination=%s current=%+.2f,%+.2f target=%+.2f,%+.2f distance=%.2f " +
                    "headingError=%+.1f state=%s").format(
                    destination.name, local.x, local.z,
                    destination.position.x, destination.position.z,
                    solved.distanceMeters, solved.headingErrorDeg, solved.state.name,
                )
            )
        }
    }

    // ---- obstacles ----------------------------------------------------------------------

    /**
     * Reactive safety layer: it only answers "is the way ahead blocked, and which side is
     * more open", and it overrides the destination guidance when it is. It is not a planner
     * and cannot route around an arbitrary obstacle.
     */
    private fun updateObstacles(frame: Frame) {
        val clearance = try {
            frame.acquireDepthImage16Bits().use { DepthSampler.sample(it) }
        } catch (e: NotYetAvailableException) {
            // Normal for the first frames and the odd frame after that; the analyzer
            // decides how long a gap may be ridden out before it reports UNKNOWN.
            DepthClearanceAnalyzer.Clearance(Float.NaN, Float.NaN, Float.NaN)
        }
        val previous = obstacleState
        val state = obstacles.update(clearance)
        obstacleState = state
        if (state != previous || fps.totalFrames % OBSTACLE_LOG_INTERVAL == 0L) {
            Log.i(
                OBSTACLE_TAG,
                "left=%s center=%s right=%s state=%s".format(
                    format(clearance.leftMeters),
                    format(clearance.centerMeters),
                    format(clearance.rightMeters),
                    state.name,
                )
            )
        }
        if (state != previous) runOnUiThread { announceObstacle(previous, state) }
    }

    private fun format(meters: Float) = if (meters.isNaN()) "-" else "%.2f".format(meters)

    private fun announceObstacle(
        previous: DepthClearanceAnalyzer.State,
        state: DepthClearanceAnalyzer.State,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastObstacleSpeechMs < OBSTACLE_SPEECH_COOLDOWN_MS && state == lastSpokenObstacle) return
        val phrase = when {
            state == DepthClearanceAnalyzer.State.MOVE_LEFT -> "Obstacle ahead. Move left."
            state == DepthClearanceAnalyzer.State.MOVE_RIGHT -> "Obstacle ahead. Move right."
            state == DepthClearanceAnalyzer.State.STOP -> "Stop. Obstacle ahead."
            state == DepthClearanceAnalyzer.State.CLEAR && previous.isObstacle -> "Path clear."
            else -> null
        } ?: return
        // Let the destination guidance speak again immediately once the way is open.
        if (state == DepthClearanceAnalyzer.State.CLEAR) announcer.reset()
        lastSpokenObstacle = state
        lastObstacleSpeechMs = now
        speak(phrase)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    // ---- rendering ----------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        renderer.createOnGlThread()
        glInitialized = true
        session?.setCameraTextureName(renderer.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val active = session ?: return
        if (viewportDirty && viewportWidth > 0) {
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            active.setDisplayGeometry(displayRotation(), viewportWidth, viewportHeight)
            viewportDirty = false
        }
        try {
            val frame = active.update()
            renderer.draw(frame)
            fps.record()
            onFrame(frame)
            while (true) {
                (glTasks.poll() ?: break).invoke(frame)
            }
        } catch (e: CameraNotAvailableException) {
            trackingText = "camera lost: ${e.message}"
        }
        runOnUiThread { render() }
    }

    private fun onFrame(frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        stabilizer.update(tracking, System.currentTimeMillis())
        trackingText = if (tracking) {
            "TRACKING"
        } else {
            "${camera.trackingState.name} (${camera.trackingFailureReason.name})"
        }

        // A cloud anchor is resolved by looking at the room, so it can start by itself once
        // tracking is trustworthy. A manual origin needs the person to confirm they are
        // standing on the marker, so it waits for the button.
        val existing = room
        if (existing != null &&
            relocalization == Relocalization.NOT_STARTED &&
            stabilizer.state == TrackingStabilizer.State.READY &&
            strategy.id == CloudAnchorStrategy.ID
        ) {
            runOnUiThread { startResolve(existing) }
        }

        if (phase == Phase.NAVIGATING && depthSupported &&
            fps.totalFrames % DEPTH_SAMPLE_INTERVAL == 0L
        ) {
            updateObstacles(frame)
        }

        val local = originPose()?.let { origin ->
            NavigationFrame(origin.translation, origin.rotationQuaternion)
                .localize(camera.pose.translation, camera.pose.rotationQuaternion)
        }
        localPose = local
        if (local != null && phase == Phase.NAVIGATING) runOnUiThread { updateNavigation(local) }
        logPose()
    }

    /** The room origin, only while ARCore is actually tracking it. */
    private fun originPose(): Pose? = strategy.originAnchor
        ?.takeIf { it.trackingState == TrackingState.TRACKING }
        ?.pose

    /**
     * One line per second with everything needed to measure relocalization error offline:
     * stand on a marked destination and the printed distance to it *is* the error.
     */
    private fun logPose() {
        if (fps.totalFrames % POSE_LOG_INTERVAL != 0L) return
        val local = localPose ?: return
        val current = room ?: return
        val nearest = current.destinations.minByOrNull { distanceTo(it, local) }
        Log.i(
            TAG,
            "POSE room=%s x=%+.3f y=%+.3f z=%+.3f yaw=%+.1f nearest=%s dist=%s fps=%.1f".format(
                current.roomId, local.x, local.y, local.z, local.yawDeg,
                nearest?.name ?: "-",
                nearest?.let { "%.3f".format(distanceTo(it, local)) } ?: "-",
                fps.fps(),
            )
        )
    }

    private fun distanceTo(destination: Destination, local: NavigationFrame.Local): Float {
        val dx = destination.position.x - local.x
        val dy = destination.position.y - local.y
        val dz = destination.position.z - local.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ---- screens ------------------------------------------------------------------------

    private fun render() {
        binding.setupPanel.visibility = visibleIf(phase == Phase.ALIGNING)
        binding.destinationPanel.visibility = visibleIf(phase == Phase.PICKING)
        binding.navPanel.visibility = visibleIf(phase == Phase.NAVIGATING)
        when (phase) {
            Phase.ALIGNING -> renderSetup()
            Phase.PICKING -> renderPicker()
            Phase.NAVIGATING -> renderNavigation()
        }
        if (binding.debugText.visibility == View.VISIBLE) binding.debugText.text = debugReport()
    }

    private fun renderSetup() {
        val current = room
        binding.setupTitle.text = current?.displayName ?: pendingRoomName
        binding.primaryButton.setText(
            if (current == null) R.string.set_room_origin else R.string.align_here
        )
        val ready = stabilizer.state == TrackingStabilizer.State.READY
        binding.primaryButton.isEnabled = ready && relocalization != Relocalization.SEARCHING
        binding.setupMessage.text = when {
            relocalization == Relocalization.SEARCHING -> "Looking for the space…"
            relocalization == Relocalization.FAILED ->
                "Could not recover the space: $relocalizationDetail\nTry again."

            !ready -> stabilizer.message()
            current == null ->
                "${stabilizer.message()}\n\nStand where the space should start from, then set " +
                    "the origin."

            else ->
                "${stabilizer.message()}\n\nStand on ${current.spatialReference.originDescription}" +
                    ", face the same way, then align."
        }
    }

    private fun renderPicker() {
        val current = room ?: return
        binding.roomTitle.text = current.displayName
        binding.roomStatus.text = if (trackingText == "TRACKING") {
            getString(R.string.tracking_ready)
        } else {
            trackingText
        }
        val container = binding.destinationContainer
        if (container.childCount != current.destinations.size) {
            container.removeAllViews()
            current.destinations.forEach { destination ->
                val button = layoutInflater
                    .inflate(R.layout.item_destination_button, container, false) as Button
                button.text = destination.name
                button.setOnClickListener { startNavigation(destination) }
                container.addView(button)
            }
        }
    }

    private fun renderNavigation() {
        val destination = target ?: return
        val solved = fix
        binding.destinationLabel.text = "to ${destination.name}"
        if (solved == null) {
            binding.arrowText.text = "…"
            binding.instructionText.text = ""
            binding.distanceText.text = ""
            return
        }
        if (obstacleState.isObstacle) {
            renderObstacleOverride()
            return
        }
        binding.pathStatus.setText(R.string.path_clear)
        binding.pathStatus.setTextColor(0xFF69F0AE.toInt())
        binding.arrowText.text = when (solved.state) {
            NavigationEngine.State.GO_FORWARD -> "\u2191"
            NavigationEngine.State.TURN_LEFT -> "\u21B0"
            NavigationEngine.State.TURN_RIGHT -> "\u21B1"
            NavigationEngine.State.ARRIVED -> "\u2713"
        }
        binding.instructionText.text = when (solved.state) {
            NavigationEngine.State.GO_FORWARD -> "GO FORWARD"
            NavigationEngine.State.TURN_LEFT -> "TURN LEFT"
            NavigationEngine.State.TURN_RIGHT -> "TURN RIGHT"
            NavigationEngine.State.ARRIVED -> getString(R.string.arrived)
        }
        binding.distanceText.text = if (solved.state == NavigationEngine.State.ARRIVED) {
            destination.name
        } else {
            "%.1f m".format(solved.distanceMeters)
        }
    }

    /** Safety wins the screen: the destination stays visible, the instruction does not. */
    private fun renderObstacleOverride() {
        binding.pathStatus.setText(R.string.obstacle_ahead)
        binding.pathStatus.setTextColor(0xFFFF5252.toInt())
        binding.arrowText.text = when (obstacleState) {
            DepthClearanceAnalyzer.State.MOVE_LEFT -> "\u2190"
            DepthClearanceAnalyzer.State.MOVE_RIGHT -> "\u2192"
            else -> "\u2715"
        }
        binding.instructionText.text = when (obstacleState) {
            DepthClearanceAnalyzer.State.MOVE_LEFT -> getString(R.string.move_left)
            DepthClearanceAnalyzer.State.MOVE_RIGHT -> getString(R.string.move_right)
            else -> getString(R.string.stop)
        }
    }

    private fun debugReport(): String = buildString {
        val current = room
        append("Room: ${current?.displayName ?: pendingRoomName} [${current?.roomId ?: "-"}]\n")
        append("Reference: ${strategy.displayName} — ${strategy.status()}\n")
        strategy.unavailableReason()?.let { append("  unavailable: $it\n") }
        append("Relocalization: ${relocalization.name}")
        if (relocalizationDetail.isNotEmpty()) append(" — $relocalizationDetail")
        append("\n")
        append("Tracking: $trackingText\n")
        append("Init: ${stabilizer.state.name}\n")
        append("Session: $sessionStatus   fps %.1f\n".format(fps.fps()))
        val local = localPose
        if (local == null) {
            append("Position: waiting for the room origin\n")
        } else {
            append("Position: x=%+.3f y=%+.3f z=%+.3f yaw=%+.1f\n"
                .format(local.x, local.y, local.z, local.yawDeg))
        }
        fix?.let {
            append("Nav: %s dist=%.2f headingError=%+.1f\n"
                .format(it.state.name, it.distanceMeters, it.headingErrorDeg))
        }
        append("Depth: ${if (depthSupported) obstacleState.name else "unsupported"}\n")
        current?.destinations?.forEach { destination ->
            append("  %-10s %+.2f %+.2f %+.2f".format(
                destination.name,
                destination.position.x,
                destination.position.y,
                destination.position.z,
            ))
            if (local != null) append("   %.2f m away".format(distanceTo(destination, local)))
            append("\n")
        }
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            windowManager.defaultDisplay.rotation
        }

    companion object {
        private const val TAG = "P2Check"
        private const val NAV_TAG = "NavCheck"
        private const val OBSTACLE_TAG = "ObstacleCheck"
        private const val POSE_LOG_INTERVAL = 30L

        /** ~6 depth reads per second at 60 fps: enough to react, cheap enough to ignore. */
        private const val DEPTH_SAMPLE_INTERVAL = 10L
        private const val OBSTACLE_LOG_INTERVAL = 150L
        private const val OBSTACLE_SPEECH_COOLDOWN_MS = 3_000L
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_ROOM_NAME = "room_name"

        fun createIntent(context: Context, roomName: String): Intent =
            Intent(context, RoomActivity::class.java).putExtra(EXTRA_ROOM_NAME, roomName)

        fun openIntent(context: Context, roomId: String): Intent =
            Intent(context, RoomActivity::class.java).putExtra(EXTRA_ROOM_ID, roomId)
    }
}
