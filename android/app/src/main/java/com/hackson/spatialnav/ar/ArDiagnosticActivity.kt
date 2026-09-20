package com.hackson.spatialnav.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.hackson.spatialnav.databinding.ActivityArBinding
import com.hackson.spatialnav.nav.NavigationFrame
import com.hackson.spatialnav.util.RollingFps
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * P1 diagnostic: does this phone actually support ARCore, and what pose does it produce?
 *
 * Everything on screen is read back from a live [Session]; nothing is simulated. Camera
 * ownership is exclusive — see `docs/05-arcore-camera-ownership.md`. CameraX must already be
 * unbound before this activity resumes, which the launcher activity guarantees by stopping
 * its own binding in `onPause`.
 */
class ArDiagnosticActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var binding: ActivityArBinding
    private val renderer = BackgroundRenderer()
    private val fps = RollingFps()

    private var session: Session? = null
    private var sessionStatus = "not created yet"
    private var depthStatus = "unknown"
    private var installRequested = false
    private var glInitialized = false
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportDirty = true

    /** Established on the first TRACKING frame so the readout starts at the user, not at 0,0,0. */
    @Volatile
    private var navigationFrame: NavigationFrame? = null

    @Volatile
    private var latest: String = "waiting for the first ARCore frame"

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                sessionStatus = "camera permission denied"
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.resetOriginButton.setOnClickListener {
            navigationFrame = null
            Log.i(TAG, "ORIGIN=reset")
        }

        render()
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
        session?.close()
        session = null
        super.onDestroy()
    }

    /**
     * Creates the ARCore session, reporting every way it can legitimately fail on a phone
     * that is simply not an AR device.
     */
    private fun ensureSession(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        log("ARCORE_AVAILABILITY", availability.name)
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
                    depthMode = when {
                        created.isDepthModeSupported(Config.DepthMode.AUTOMATIC) ->
                            Config.DepthMode.AUTOMATIC

                        else -> Config.DepthMode.DISABLED
                    }
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                }
            )
            depthStatus = describeDepth(created)
            session = created
            sessionStatus = "created"
            log("ARCORE_SESSION", "created")
            log("DEPTH_SUPPORT", depthStatus)
            log("ARCORE_APK_VERSION", playServicesForArVersion())
            if (glInitialized) {
                created.setCameraTextureName(renderer.textureId)
                viewportDirty = true
            }
            return true
        } catch (e: UnavailableException) {
            sessionStatus = "session creation failed: ${e.javaClass.simpleName}: ${e.message}"
            log("ARCORE_SESSION", sessionStatus)
            return false
        }
    }

    /** Both depth modes are reported: AUTOMATIC drives obstacles, RAW_DEPTH_ONLY is the fallback. */
    private fun describeDepth(session: Session): String {
        val automatic = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val raw = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        return when {
            automatic && raw -> "SUPPORTED (AUTOMATIC=yes, RAW_DEPTH_ONLY=yes)"
            automatic -> "SUPPORTED (AUTOMATIC=yes, RAW_DEPTH_ONLY=no)"
            raw -> "SUPPORTED (AUTOMATIC=no, RAW_DEPTH_ONLY=yes)"
            else -> "UNSUPPORTED (no depth mode)"
        }
    }

    private fun playServicesForArVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(AR_PACKAGE, 0).versionName ?: "installed, version unknown"
    } catch (e: PackageManager.NameNotFoundException) {
        "not installed / not visible"
    }

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
            latest = describe(frame.camera)
        } catch (e: CameraNotAvailableException) {
            latest = "camera lost: ${e.message}"
        }
        runOnUiThread { render() }
    }

    private fun describe(camera: Camera): String {
        val state = camera.trackingState
        if (state != TrackingState.TRACKING) {
            return "Tracking: ${state.name} (${camera.trackingFailureReason.name})\n" +
                "Move the phone slowly; point it at a textured, well-lit surface."
        }
        val pose = camera.pose
        val translation = pose.translation
        val quaternion = pose.rotationQuaternion
        val frame = navigationFrame ?: NavigationFrame(translation, quaternion).also {
            navigationFrame = it
            log("NAV_ORIGIN", "established at world ${translation.joinToString()}")
        }
        val local = frame.localize(translation, quaternion)
        val (pitch, roll) = NavigationFrame.pitchRollDegrees(quaternion)
        logPose(local, pitch, roll)
        return buildString {
            append("Tracking: TRACKING\n\n")
            append("Position (local origin)\n")
            append("X right   : %+.3f m\n".format(local.x))
            append("Y up      : %+.3f m\n".format(local.y))
            append("Z forward : %+.3f m\n\n".format(local.z))
            append("Orientation\n")
            append("Yaw   : %+.1f deg\n".format(local.yawDeg))
            append("Pitch : %+.1f deg   Roll: %+.1f deg\n".format(pitch, roll))
            append("quat  : %+.3f %+.3f %+.3f %+.3f\n".format(
                quaternion[0], quaternion[1], quaternion[2], quaternion[3]
            ))
            append("world : %+.3f %+.3f %+.3f".format(
                translation[0], translation[1], translation[2]
            ))
        }
    }

    private fun logPose(local: NavigationFrame.Local, pitch: Float, roll: Float) {
        if (fps.totalFrames % POSE_LOG_INTERVAL != 0L) return
        Log.i(
            TAG,
            "POSE x=%+.3f y=%+.3f z=%+.3f yaw=%+.1f pitch=%+.1f roll=%+.1f fps=%.1f".format(
                local.x, local.y, local.z, local.yawDeg, pitch, roll, fps.fps()
            )
        )
    }

    private fun render() {
        binding.statusText.text = buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n")
            append("ARCore  : ${if (session != null) "SUPPORTED" else "NOT AVAILABLE"}\n")
            append("Session : $sessionStatus\n")
            append("AR APK  : ${playServicesForArVersion()}\n")
            append("Depth   : $depthStatus\n")
            append("AR fps  : %.1f (3 s window, %d frames)\n\n".format(fps.fps(), fps.totalFrames))
            append(latest)
        }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: 0
        } else {
            windowManager.defaultDisplay.rotation
        }

    private fun log(key: String, value: String) = Log.i(TAG, "$key=$value")

    private companion object {
        const val TAG = "P1Check"
        const val AR_PACKAGE = "com.google.ar.core"
        const val POSE_LOG_INTERVAL = 30L
    }
}
