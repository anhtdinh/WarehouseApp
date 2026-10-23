package com.example.warehouse

import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Camera preview.
 *  - [onBarcode] != null  -> scans barcodes/QR codes and reports the text.
 *  - [capture]   != null  -> exposes an ImageCapture so the caller can take a photo.
 */
@Composable
fun CameraView(
    modifier: Modifier,
    onBarcode: ((String) -> Unit)?,
    capture: MutableState<ImageCapture?>?
) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onBarcode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }

    AndroidView(modifier = modifier, factory = { ctx ->
        val view = PreviewView(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val useCases = mutableListOf<UseCase>(preview)

            if (onBarcode != null) {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(img)
                        .addOnSuccessListener { codes ->
                            val text = codes.firstNotNullOfOrNull { it.rawValue }
                            if (text != null) {
                                ContextCompat.getMainExecutor(ctx).execute { latest?.invoke(text) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                useCases += analysis
            }
            if (capture != null) {
                val ic = ImageCapture.Builder().build()
                capture.value = ic
                useCases += ic
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        }, ContextCompat.getMainExecutor(ctx))
        view
    })
}
