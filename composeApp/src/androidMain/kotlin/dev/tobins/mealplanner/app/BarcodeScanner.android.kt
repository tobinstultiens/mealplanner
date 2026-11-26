package dev.tobins.mealplanner.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The camera side of the barcode scan (issue 356): CameraX shows the preview and hands over frames, which BarcodeReader
 * reads. Neither the preview nor a frame is stored or sent; only the digits go on.
 */

/** A scanner on a phone that has a camera; null on one without (the app installs there too: its manifest does not require one). */
@Composable
actual fun rememberBarcodeScanner(): BarcodeScanner? {
    val context = LocalContext.current
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null
    var asker by remember { mutableStateOf<((ScanResult) -> Unit)?>(null) }
    var open by remember { mutableStateOf(false) }
    // The permission is asked at the first scan, not before; a refusal is said, and the next tap asks again where Android lets it.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            open = true
        } else {
            asker?.invoke(ScanResult.Refused)
            asker = null
        }
    }
    if (open) {
        ScanScreen(
            onResult = { result ->
                open = false
                asker?.invoke(result)
                asker = null
            },
            onClose = {
                open = false
                asker = null
            },
        )
    }
    return remember(context) {
        object : BarcodeScanner {
            override fun scan(onResult: (ScanResult) -> Unit) {
                asker = onResult
                if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open = true
                else permission.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

/**
 * The scanner: the camera's preview over the whole screen, a frame to aim at, a line of help, a way back and, where the
 * phone has one, the torch. It reports the first barcode read and then closes: [onResult] once; [onClose] for the way back.
 */
@Composable
private fun ScanScreen(onResult: (ScanResult) -> Unit, onClose: () -> Unit) {
    val t = LocalStrings.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val report = rememberUpdatedState(onResult)
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val done = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        val picture = Preview.Builder().build().apply { setSurfaceProvider(preview.surfaceProvider) }
        val frames = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(executor) { frame ->
                    // A frame is read and dropped; the first barcode ends the scan.
                    val digits = frame.use { BarcodeReader.read(it.luminance()) }
                    if (digits != null && done.compareAndSet(false, true)) main.execute { report.value(ScanResult.Read(digits)) }
                }
            }
        val ready = ProcessCameraProvider.getInstance(context)
        ready.addListener({
            try {
                provider = ready.get().also {
                    camera = it.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, picture, frames)
                }
            } catch (_: Exception) {
                if (done.compareAndSet(false, true)) report.value(ScanResult.Unavailable)
            }
        }, main)
        onDispose {
            done.set(true)
            frames.clearAnalyzer()
            provider?.unbind(picture, frames)
            executor.shutdown()
            camera = null
        }
    }
    DisposableEffect(camera, torch) {
        camera?.cameraControl?.enableTorch(torch)
        onDispose {}
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView({ preview }, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.8f).aspectRatio(1.6f)
                        .border(3.dp, Color.White, RoundedCornerShape(12.dp)),
                )
                Text(
                    t(Msg.SCAN_HELP),
                    Modifier.align(Alignment.BottomCenter).padding(32.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(12.dp),
                )
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarAction(AppIcons.Close, t(Msg.SCAN_CLOSE), onClose)
                    Box(Modifier.weight(1f))
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        TextButton(onClick = { torch = !torch }) { Text(t(Msg.SCAN_TORCH), color = if (torch) Color.Yellow else Color.White) }
                    }
                }
            }
        }
    }
}

/** The brightness of this frame, row by row without the padding the camera adds at the end of a row. */
private fun ImageProxy.luminance(): LuminanceImage {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixels = ByteArray(width * height)
    for (y in 0 until height) {
        buffer.position(y * rowStride)
        buffer.get(pixels, y * width, width)
    }
    return LuminanceImage(width, height, pixels)
}
