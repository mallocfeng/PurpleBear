package com.mallocgfw.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.mallocgfw.app.ui.theme.Error
import com.mallocgfw.app.ui.theme.Primary
import com.mallocgfw.app.ui.theme.Secondary
import com.mallocgfw.app.ui.theme.SurfaceLow
import com.mallocgfw.app.ui.theme.TextPrimary
import com.mallocgfw.app.ui.theme.TextSecondary
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
internal fun QrScannerScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onDetected: (String) -> Unit,
    onScanError: (String) -> Unit,
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
    var cameraScanLocked by remember { mutableStateOf(false) }
    var galleryScanInFlight by remember { mutableStateOf(false) }
    var gallerySelection by remember { mutableStateOf<GalleryQrSelectionState?>(null) }
    var selectedGalleryIndex by remember { mutableIntStateOf(0) }
    var scanStatusMessage by remember { mutableStateOf<String?>(null) }
    var scanStatusIsError by remember { mutableStateOf(false) }
    val currentGallerySelection = gallerySelection
    val albumPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { imageUri ->
        if (imageUri == null) {
            galleryScanInFlight = false
            scanStatusMessage = "已取消图片选择。"
            scanStatusIsError = false
            return@rememberLauncherForActivityResult
        }
        galleryScanInFlight = true
        gallerySelection = null
        scanStatusMessage = "正在识别图片中的二维码，请稍候。"
        scanStatusIsError = false
        processQrImage(
            context = context,
            scanner = barcodeScanner,
            imageUri = imageUri,
            onDetected = { detections ->
                galleryScanInFlight = false
                if (detections.size == 1) {
                    scanStatusMessage = "已识别二维码，正在导入。"
                    scanStatusIsError = false
                    onDetected(detections.first().rawValue)
                } else {
                    val imageBitmap = runCatching {
                        loadBitmapFromUri(context, imageUri)
                    }.getOrElse {
                        scanStatusMessage = "读取图片失败，无法识别二维码。"
                        scanStatusIsError = true
                        onScanError("读取图片失败，无法识别二维码。")
                        return@processQrImage
                    }
                    gallerySelection = GalleryQrSelectionState(
                        imageBitmap = imageBitmap,
                        detections = detections,
                    )
                    selectedGalleryIndex = 0
                    scanStatusMessage = "已识别 ${detections.size} 个二维码，请点选一个继续导入。"
                    scanStatusIsError = false
                }
            },
            onFailure = { error ->
                galleryScanInFlight = false
                val message = if (error == null) {
                    "这张图片里没有识别到二维码，请换一张更清晰的图片再试。"
                } else {
                    "读取图片失败，无法识别二维码。"
                }
                scanStatusMessage = message
                scanStatusIsError = true
                onScanError(message)
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { barcodeScanner.close() }
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(previewView, lifecycleOwner, gallerySelection, galleryScanInFlight) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        if (gallerySelection != null || galleryScanInFlight) {
            onDispose {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
        } else {
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
                                if (!cameraScanLocked) {
                                    cameraScanLocked = true
                                    onDetected(rawValue)
                                }
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
        if (!scanStatusMessage.isNullOrBlank()) {
            item {
                ScanStatusCard(
                    message = scanStatusMessage.orEmpty(),
                    isError = scanStatusIsError,
                )
            }
        }
        item {
            SurfaceCard {
                if (currentGallerySelection == null) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedActionButton(
                        text = if (galleryScanInFlight) "正在识别图片…" else "打开相册",
                        enabled = !galleryScanInFlight,
                        onClick = {
                            galleryScanInFlight = true
                            scanStatusMessage = "请选择一张包含二维码的图片。"
                            scanStatusIsError = false
                            albumPickerLauncher.launch("image/*")
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiText("从本地图片识别二维码。"),
                        color = TextSecondary,
                    )
                } else {
                    GalleryQrSelectionPreview(
                        imageBitmap = currentGallerySelection.imageBitmap,
                        detections = currentGallerySelection.detections,
                        selectedIndex = selectedGalleryIndex,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = uiText(
                            "已识别 ${currentGallerySelection.detections.size} 个二维码，请选择一个导入。",
                            "Found ${currentGallerySelection.detections.size} QR codes. Choose one to import.",
                        ),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = uiText(
                            "图片上的编号与下方选项一一对应。",
                            "The numbers on the image match the options below.",
                        ),
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        currentGallerySelection.detections.forEachIndexed { index, _ ->
                            ModeChip(
                                text = "二维码 ${index + 1}",
                                selected = selectedGalleryIndex == index,
                                onClick = {
                                    selectedGalleryIndex = index
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentGallerySelection.detections[selectedGalleryIndex].rawValue,
                        color = TextSecondary,
                        maxLines = 2,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    PrimaryActionButton(
                        text = "识别所选二维码",
                        onClick = {
                            scanStatusMessage = "已选择二维码，正在导入。"
                            scanStatusIsError = false
                            onDetected(currentGallerySelection.detections[selectedGalleryIndex].rawValue)
                        },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ButtonRow(
                        primaryText = "重新选择图片",
                        onPrimary = {
                            galleryScanInFlight = true
                            scanStatusMessage = "请选择另一张图片。"
                            scanStatusIsError = false
                            albumPickerLauncher.launch("image/*")
                        },
                        secondaryText = "返回相机扫码",
                        onSecondary = {
                            gallerySelection = null
                            selectedGalleryIndex = 0
                            scanStatusMessage = "已返回相机扫码。"
                            scanStatusIsError = false
                        },
                    )
                }
            }
        }
    }
}

private data class GalleryQrSelectionState(
    val imageBitmap: Bitmap,
    val detections: List<QrImageCandidate>,
)

@Composable
private fun ScanStatusCard(
    message: String,
    isError: Boolean,
) {
    val accent = if (isError) Error else Primary
    SurfaceCard(
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Text(
            text = uiText(if (isError) "识别结果" else "当前状态"),
            color = accent,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiText(message),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GalleryQrSelectionPreview(
    imageBitmap: Bitmap,
    detections: List<QrImageCandidate>,
    selectedIndex: Int,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceLow),
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val imageWidthPx = imageBitmap.width.toFloat().coerceAtLeast(1f)
        val imageHeightPx = imageBitmap.height.toFloat().coerceAtLeast(1f)
        val scale = minOf(containerWidthPx / imageWidthPx, containerHeightPx / imageHeightPx)
        val displayedWidthPx = imageWidthPx * scale
        val displayedHeightPx = imageHeightPx * scale
        val horizontalInsetPx = (containerWidthPx - displayedWidthPx) / 2f
        val verticalInsetPx = (containerHeightPx - displayedHeightPx) / 2f

        Image(
            bitmap = imageBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        detections.forEachIndexed { index, detection ->
            val bounds = detection.boundingBox ?: return@forEachIndexed
            QrBoundingBoxOverlay(
                bounds = bounds,
                index = index,
                selected = index == selectedIndex,
                scale = scale,
                horizontalInsetPx = horizontalInsetPx,
                verticalInsetPx = verticalInsetPx,
            )
        }
    }
}

@Composable
private fun QrBoundingBoxOverlay(
    bounds: Rect,
    index: Int,
    selected: Boolean,
    scale: Float,
    horizontalInsetPx: Float,
    verticalInsetPx: Float,
) {
    val density = LocalDensity.current
    val overlayColor = if (selected) Secondary else Primary
    val left = with(density) { (horizontalInsetPx + bounds.left * scale).toDp() }
    val top = with(density) { (verticalInsetPx + bounds.top * scale).toDp() }
    val width = with(density) { (bounds.width().coerceAtLeast(1) * scale).toDp() }
    val height = with(density) { (bounds.height().coerceAtLeast(1) * scale).toDp() }

    Box(
        modifier = Modifier
            .offset(x = left, y = top)
            .size(width = width, height = height)
            .border(
                width = if (selected) 3.dp else 2.dp,
                color = overlayColor,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(8.dp)
                .clip(CircleShape)
                .background(overlayColor)
                .align(Alignment.TopStart)
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${index + 1}",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}
