package com.hackson.spatialnav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.hackson.spatialnav.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else binding.statusText.text = "camera permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        binding.speakButton.setOnClickListener { speak("Walk forward for two meters.") }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        } else {
            Log.w(TAG, "tts init failed: $status")
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                frameCount++
                if (frameCount % 30 == 0L) {
                    val info = "frames=$frameCount ${image.width}x${image.height}"
                    runOnUiThread { binding.statusText.text = info }
                }
                image.close()
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            binding.statusText.text = "camera running"
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        analysisExecutor.shutdown()
    }

    private var frameCount = 0L

    private companion object {
        const val TAG = "SpatialNav"
    }
}
