package dev.tobins.mealplanner.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The camera side of the barcode scan (issue 356): CameraX shows the preview and hands over frames, which BarcodeReader
 * reads. Neither the preview nor a frame is stored or sent; only the digits go on.
 */

/** The aiming frame's size in dp, [width] by [height]. */
internal data class FrameSize(val width: Float, val height: Float)

/**
 * The aiming frame for a preview of [screenWidth] by [screenHeight] dp: wider than tall as a barcode is, sized from the
 * smaller side so that it keeps a margin on all four edges held upright or sideways (and leaves room for the help line below).
 */
internal fun scanFrameSize(screenWidth: Float, screenHeight: Float): FrameSize {
    val width = minOf(screenWidth, screenHeight) * 0.8f
    return FrameSize(width, width / 1.6f)
}

/** A scanner on a phone that has a camera; null on one without (the app installs there too: its manifest does not require one). */
@Composable
actual fun rememberBarcodeScanner(): BarcodeScanner? {
    val context = LocalContext.current
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return null
    // Saved, so that a scan open when the phone is turned stays open: the camera is bound again, and the permission, once given, is not asked again.
    var open by rememberSaveable { mutableStateOf(false) }
    val scanner = remember(context) { CameraScanner(context) }
    val lifecycle = LocalLifecycleOwner.current
    // Read again on every resume: the camera may have been allowed in the system settings meanwhile.
    DisposableEffect(lifecycle, scanner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scanner.readPermission() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // The permission is asked at the first scan, not before; a refusal is said, and the next tap asks again where Android lets it.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) open = true else scanner.onResult?.invoke(ScanResult.Refused)
    }
    SideEffect {
        scanner.start = {
            // Read now, not remembered: the person may have allowed it in the settings since the last tap.
            scanner.readPermission()
            if (scanner.cameraAllowed) open = true
            else permission.launch(Manifest.permission.CAMERA)
        }
    }
    if (open) {
        ScanScreen(
            onResult = { result ->
                open = false
                scanner.onResult?.invoke(result)
            },
            onClose = { open = false },
        )
    }
    return scanner
}

/** The scanner a screen holds: [start] is set by [rememberBarcodeScanner], which owns the state of the scan. */
private class CameraScanner(private val context: Context) : BarcodeScanner {
    override var onResult: ((ScanResult) -> Unit)? = null
    override var cameraAllowed by mutableStateOf(allowed())
        private set
    var start: () -> Unit = {}

    override fun scan() = start()

    private fun allowed() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun readPermission() {
        cameraAllowed = allowed()
    }

    override fun openSettings() {
        val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    var torch by rememberSaveable { mutableStateOf(false) }

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
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView({ preview }, Modifier.fillMaxSize())
            // A dark ring around a white one: the frame shows on a white pack as well as on a black one.
            val frame = scanFrameSize(maxWidth.value, maxHeight.value)
            Box(
                Modifier.align(Alignment.Center).size(frame.width.dp, frame.height.dp)
                    .border(7.dp, Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(2.dp).border(3.dp, Color.White, RoundedCornerShape(12.dp)),
            )
            Text(
                t(Msg.SCAN_HELP),
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 32.dp, vertical = 16.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(12.dp),
                color = Color.White,
            )
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ScanControl(t(Msg.SCAN_CLOSE), AppIcons.Close, lit = false, onClick = onClose)
                Box(Modifier.weight(1f))
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    // On is told from off by the bolt struck through when off, an amber disc with a black ring instead of a dark one, and by what a screen reader says.
                    val state = t(if (torch) Msg.SCAN_TORCH_ON else Msg.SCAN_TORCH_OFF)
                    ScanControl(
                        t(Msg.SCAN_TORCH), if (torch) AppIcons.Torch else AppIcons.TorchOff, lit = torch,
                        onClick = { torch = !torch },
                        modifier = Modifier.semantics { stateDescription = state },
                    )
                }
            }
        }
    }
}

/**
 * A round button of the scanner, 48 dp, on a dark translucent disc so that it shows on any picture. When [lit] the disc is
 * amber with a black ring, which holds on a white picture as on a black one; off has a thin pale ring instead.
 */
@Composable
private fun ScanControl(description: String, icon: ImageVector, lit: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
            .background(if (lit) TorchLit else Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(if (lit) 3.dp else 1.dp, if (lit) Color.Black else Color.White.copy(alpha = 0.5f), CircleShape),
    ) {
        Icon(icon, description, tint = if (lit) Color.Black else Color.White)
    }
}

private val TorchLit = Color(0xFFFFC107)

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
