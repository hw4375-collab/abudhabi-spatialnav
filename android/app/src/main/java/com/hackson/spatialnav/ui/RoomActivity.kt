package com.hackson.spatialnav.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
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
import com.google.ar.core.exceptions.UnavailableException
import com.hackson.spatialnav.BuildConfig
import com.hackson.spatialnav.R
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
import com.hackson.spatialnav.navigation.NavigationFrame
import com.hackson.spatialnav.persistence.RoomStore
import com.hackson.spatialnav.util.RollingFps
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/**
 * P2: map a room once, mark semantic destinations in it, and find both again after the app
 * has been closed.
 *
 * The room's coordinate frame is defined by the spatial reference anchor, *not* by wherever
 * the session happened to start — that is what makes the saved destinations mean the same
 * physical place tomorrow. Every frame the camera pose is re-expressed relative to that
 * anchor, so ARCore's continuous corrections to the anchor pose are picked up for free.
 */
class RoomActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private enum class Relocalization { NOT_STARTED, SEARCHING, SUCCESS, FAILED }

    private lateinit var binding: ActivityRoomBinding
    private lateinit var store: RoomStore
    private val renderer = BackgroundRenderer()
    private val fps = RollingFps()
    private val stabilizer = TrackingStabilizer()

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
                Toast.makeText(this, "room not found", Toast.LENGTH_LONG).show()
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
        binding.markDoorButton.setOnClickListener { markDestination("Door") }
        binding.markBathroomButton.setOnClickListener { markDestination("Bathroom") }
        binding.markDeskButton.setOnClickListener { markDestination("Desk") }
        binding.markCustomButton.setOnClickListener { askCustomDestination() }

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
        if (session != null) {
            binding.surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
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
        toast("Room saved. Walk to a destination and mark it.")
        render()
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
                            render()
                        },
                        onFailure = { error -> onReferenceFailed(error) },
                    )
                }
            }
        }
    }

    private fun onReferenceFailed(error: Throwable) {
        relocalization = Relocalization.FAILED
        relocalizationDetail = error.message ?: error.javaClass.simpleName
        Log.w(TAG, "RELOCALIZATION=FAILED ${relocalizationDetail}")
        render()
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
            toast("Anchor the room first.")
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
        toast("$name saved at %.2f, %.2f, %.2f".format(local.x, local.y, local.z))
        render()
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

        localPose = originPose()?.let { origin ->
            NavigationFrame(origin.translation, origin.rotationQuaternion)
                .localize(camera.pose.translation, camera.pose.rotationQuaternion)
        }
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

    private fun render() {
        val current = room
        binding.primaryButton.isEnabled = stabilizer.state == TrackingStabilizer.State.READY &&
            relocalization != Relocalization.SUCCESS &&
            relocalization != Relocalization.SEARCHING
        binding.primaryButton.setText(
            if (current == null) R.string.anchor_room_here else R.string.align_here
        )
        val canMark = relocalization == Relocalization.SUCCESS && localPose != null
        binding.markDoorButton.isEnabled = canMark
        binding.markBathroomButton.isEnabled = canMark
        binding.markDeskButton.isEnabled = canMark
        binding.markCustomButton.isEnabled = canMark

        binding.statusText.text = buildString {
            if (current == null) {
                append("Room: $pendingRoomName (not created yet)\n")
            } else {
                append("Room: ${current.displayName} [${current.roomId}]\n")
            }
            append("Reference: ${strategy.displayName} — ${strategy.status()}\n")
            strategy.unavailableReason()?.let { append("  unavailable: $it\n") }
            append("Relocalization: ${relocalization.name}")
            if (relocalizationDetail.isNotEmpty()) append(" — $relocalizationDetail")
            append("\n")
            append("Tracking: $trackingText\n")
            append("Init: ${stabilizer.state.name} — ${stabilizer.message()}\n")
            append("Session: $sessionStatus   fps %.1f\n\n".format(fps.fps()))

            val local = localPose
            if (local == null) {
                append("Position: waiting for the room origin\n")
            } else {
                append("Position in room\n")
                append("X right   : %+.3f m\n".format(local.x))
                append("Y up      : %+.3f m\n".format(local.y))
                append("Z forward : %+.3f m\n".format(local.z))
                append("Yaw       : %+.1f deg\n".format(local.yawDeg))
            }
            append("\nDestinations (${current?.destinations?.size ?: 0})\n")
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
    }

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
        private const val POSE_LOG_INTERVAL = 30L
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_ROOM_NAME = "room_name"

        fun createIntent(context: Context, roomName: String): Intent =
            Intent(context, RoomActivity::class.java).putExtra(EXTRA_ROOM_NAME, roomName)

        fun openIntent(context: Context, roomId: String): Intent =
            Intent(context, RoomActivity::class.java).putExtra(EXTRA_ROOM_ID, roomId)
    }
}
