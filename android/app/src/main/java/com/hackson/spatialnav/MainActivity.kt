package com.hackson.spatialnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.hackson.spatialnav.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors

/**
 * P0 hardware validation screen.
 *
 * Every capability the navigation stack depends on is reported as an explicit
 * PASS / FAIL / PENDING line, on screen and in logcat under the tag [TAG], so a tester
 * with the phone can decide the outcome without reading the code.
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private enum class Result { PENDING, PASS, FAIL }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val results = linkedMapOf(
        CHECK_PERMISSION to Result.PENDING,
        CHECK_PREVIEW to Result.PENDING,
        CHECK_ANALYSIS to Result.PENDING,
        CHECK_TTS_INIT to Result.PENDING,
        CHECK_TTS_SPEAK to Result.PENDING,
    )
    private val details = mutableMapOf<String, String>()

    private var frameCount = 0L
    private var firstFrameNanos = 0L
    private var lastFrameNanos = 0L

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                report(CHECK_PERMISSION, Result.PASS, "granted by user")
                startCamera()
            } else {
                report(CHECK_PERMISSION, Result.FAIL, "denied by user")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        render()

        binding.speakButton.isEnabled = false
        binding.speakButton.setOnClickListener {
            report(CHECK_TTS_SPEAK, Result.PENDING, "speaking")
            val code = tts.speak(SPOKEN_TEST_PHRASE, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            if (code != TextToSpeech.SUCCESS) {
                report(CHECK_TTS_SPEAK, Result.FAIL, "speak() returned $code")
            }
        }

        tts = TextToSpeech(this, this)

        observePreviewStream()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            report(CHECK_PERMISSION, Result.PASS, "already granted")
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            report(CHECK_TTS_INIT, Result.FAIL, "engine init status=$status")
            return
        }
        when (val lang = tts.setLanguage(Locale.US)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                report(CHECK_TTS_INIT, Result.FAIL, "en-US unavailable (code $lang) — install a TTS voice")
            else -> {
                report(CHECK_TTS_INIT, Result.PASS, "engine ready, en-US available")
                runOnUiThread { binding.speakButton.isEnabled = true }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                report(CHECK_TTS_SPEAK, Result.PASS, "utterance completed — did you hear it?")
            }

            @Deprecated("required by the framework")
            override fun onError(utteranceId: String?) {
                report(CHECK_TTS_SPEAK, Result.FAIL, "utterance error")
            }
        })
    }

    private fun observePreviewStream() {
        binding.previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) {
                report(CHECK_PREVIEW, Result.PASS, "surface streaming")
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image ->
                    onAnalyzerFrame(image.width, image.height)
                    image.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                report(CHECK_PREVIEW, Result.FAIL, "bind failed: ${e.message}")
                report(CHECK_ANALYSIS, Result.FAIL, "camera never bound")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onAnalyzerFrame(width: Int, height: Int) {
        val now = System.nanoTime()
        frameCount++
        if (frameCount == 1L) {
            firstFrameNanos = now
        }
        lastFrameNanos = now
        if (frameCount == FRAMES_REQUIRED) {
            report(CHECK_ANALYSIS, Result.PASS, "received $FRAMES_REQUIRED frames at ${width}x$height")
        }
        if (frameCount % FRAME_LOG_INTERVAL == 0L) {
            runOnUiThread { render() }
        }
    }

    private fun analyzerFps(): Double {
        val elapsed = lastFrameNanos - firstFrameNanos
        if (frameCount < 2 || elapsed <= 0) return 0.0
        return (frameCount - 1) * 1_000_000_000.0 / elapsed
    }

    private fun report(check: String, result: Result, detail: String) {
        results[check] = result
        details[check] = detail
        Log.i(TAG, "$check=$result ($detail)")
        runOnUiThread { render() }
    }

    private fun render() {
        val body = results.entries.joinToString("\n") { (check, result) ->
            val detail = details[check]?.let { " — $it" } ?: ""
            "[${result.name.padEnd(7)}] $check$detail"
        }
        val frames = "frames=$frameCount fps=%.1f".format(analyzerFps())
        binding.statusText.text =
            "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n$body\n$frames"
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "P0Check"
        const val CHECK_PERMISSION = "CAMERA_PERMISSION"
        const val CHECK_PREVIEW = "CAMERA_PREVIEW"
        const val CHECK_ANALYSIS = "IMAGE_ANALYSIS"
        const val CHECK_TTS_INIT = "TTS_INIT"
        const val CHECK_TTS_SPEAK = "TTS_SPEAK"
        const val UTTERANCE_ID = "p0-tts-check"
        const val SPOKEN_TEST_PHRASE = "Walk forward for two meters, then turn slightly left."
        const val FRAMES_REQUIRED = 30L
        const val FRAME_LOG_INTERVAL = 30L
    }
}
