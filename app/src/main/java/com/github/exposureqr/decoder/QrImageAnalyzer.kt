package com.github.exposureqr.decoder

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import zxingcpp.BarcodeReader

class QrImageAnalyzer(
    private val onQrDetected: (text: String, engine: QrEngine) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var currentEngine: QrEngine = QrEngine.ZXING_CPP

    @Volatile
    var isPaused: Boolean = false

    private val zxingReader by lazy { BarcodeReader() }

    private val mlKitScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isPaused) {
            imageProxy.close()
            return
        }

        when (currentEngine) {
            QrEngine.ZXING_CPP -> {
                try {
                    val results = zxingReader.read(imageProxy)
                    val first = results.firstOrNull { !it.text.isNullOrEmpty() }
                    if (first != null && !isPaused) {
                        onQrDetected(first.text ?: "", QrEngine.ZXING_CPP)
                    }
                } catch (e: Exception) {
                    // Ignore decode failure on frames without barcodes
                } finally {
                    imageProxy.close()
                }
            }
            QrEngine.ML_KIT -> {
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    mlKitScanner.process(inputImage)
                        .addOnSuccessListener { barcodes: List<Barcode> ->
                            val first = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                            if (first != null && !isPaused) {
                                onQrDetected(first.rawValue ?: "", QrEngine.ML_KIT)
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }
        }
    }
}
