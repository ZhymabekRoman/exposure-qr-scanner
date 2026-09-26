package com.github.exposureqr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.github.exposureqr.databinding.ActivityMainBinding
import com.github.exposureqr.decoder.QrEngine
import com.github.exposureqr.decoder.QrImageAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null
    private var isTorchOn = false
    private var lastScannedContent: String? = null

    private val qrAnalyzer by lazy {
        QrImageAnalyzer { text, engine ->
            runOnUiThread {
                handleScanResult(text, engine)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupEngineSwitcher()
        setupButtons()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun setupEngineSwitcher() {
        binding.engineToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnEngineZxing -> {
                        qrAnalyzer.currentEngine = QrEngine.ZXING_CPP
                        Toast.makeText(this, "Switched to ZXing-C++ (FOSS)", Toast.LENGTH_SHORT).show()
                    }
                    R.id.btnEngineMlkit -> {
                        qrAnalyzer.currentEngine = QrEngine.ML_KIT
                        Toast.makeText(this, "Switched to Google ML Kit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnCopy.setOnClickListener {
            lastScannedContent?.let { content ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Scanned QR", content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpen.setOnClickListener {
            lastScannedContent?.let { content ->
                try {
                    val url = if (!content.startsWith("http://") && !content.startsWith("https://")) {
                        "https://$content"
                    } else {
                        content
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open: $content", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnDismiss.setOnClickListener {
            binding.resultCard.visibility = View.GONE
            lastScannedContent = null
            qrAnalyzer.isPaused = false
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Flash unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        isTorchOn = !isTorchOn
        cam.cameraControl.enableTorch(isTorchOn)
        binding.btnTorch.text = if (isTorchOn) getString(R.string.torch_on) else getString(R.string.torch_off)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, qrAnalyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                camera = boundCamera
                setupExposureControls(boundCamera)
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera initialization failed: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupExposureControls(camera: Camera) {
        val exposureState = camera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) {
            binding.tvExposureLabel.text = "Exposure: Hardware unsupported"
            binding.sbExposure.isEnabled = false
            return
        }

        val range = exposureState.exposureCompensationRange
        val step = exposureState.exposureCompensationStep
        val stepValue = step.numerator.toFloat() / step.denominator.toFloat()

        val minIndex = range.lower
        val maxIndex = range.upper
        val totalSteps = maxIndex - minIndex

        binding.sbExposure.isEnabled = true
        binding.sbExposure.max = totalSteps
        val currentIndex = exposureState.exposureCompensationIndex
        binding.sbExposure.progress = currentIndex - minIndex

        updateExposureLabel(currentIndex, stepValue)

        binding.sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val targetIndex = progress + minIndex
                    camera.cameraControl.setExposureCompensationIndex(targetIndex)
                    updateExposureLabel(targetIndex, stepValue)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnResetExposure.setOnClickListener {
            val defaultIndex = 0
            camera.cameraControl.setExposureCompensationIndex(defaultIndex)
            binding.sbExposure.progress = defaultIndex - minIndex
            updateExposureLabel(defaultIndex, stepValue)
        }
    }

    private fun updateExposureLabel(index: Int, stepValue: Float) {
        val ev = index * stepValue
        binding.tvExposureLabel.text = String.format("Exposure: %+.1f EV (Index: %d)", ev, index)
    }

    private fun handleScanResult(text: String, engine: QrEngine) {
        if (qrAnalyzer.isPaused) return
        qrAnalyzer.isPaused = true
        lastScannedContent = text

        vibratePhone()

        binding.tvResultHeader.text = "Scanned via ${engine.displayName}"
        binding.tvResultContent.text = text
        binding.resultCard.visibility = View.VISIBLE

        val isUrl = text.startsWith("http://") || text.startsWith("https://") ||
                (text.contains(".") && !text.contains(" ") && text.length > 4)
        binding.btnOpen.visibility = if (isUrl) View.VISIBLE else View.GONE
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(100)
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
