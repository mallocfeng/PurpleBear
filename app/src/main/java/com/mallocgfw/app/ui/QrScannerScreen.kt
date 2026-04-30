package com.mallocgfw.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.mallocgfw.app.ui.theme.TextSecondary
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun QrScannerScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    var scanLocked by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { barcodeScanner.close() }
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy ->
                    processQrFrame(
                        scanner = barcodeScanner,
                        imageProxy = imageProxy,
                        onDetected = { rawValue ->
                            if (scanLocked) return@processQrFrame
                            scanLocked = true
                            onDetected(rawValue)
                        },
                    )
                }
            }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val bindCamera = Runnable {
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analysis,
                )
            }
        }
        cameraProviderFuture.addListener(bindCamera, mainExecutor)
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        state = rememberRetainedLazyListState(),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppTopBar(
                title = "扫码导入",
                subtitle = "相机扫码",
                onBack = onBack,
            )
        }
        item {
            ScreenHeader(
                title = "扫描订阅二维码",
                subtitle = "识别后直接导入。",
            )
        }
        item {
            SurfaceCard {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = uiText("本地识别。"),
                    color = TextSecondary,
                )
            }
        }
    }
}
