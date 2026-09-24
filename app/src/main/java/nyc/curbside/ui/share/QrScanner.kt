package nyc.curbside.ui.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * The camera half of pairing: points the back camera at the other phone and reports the first QR
 * payload it reads.
 *
 * [onScanned] fires at most once — the analyzer latches after the first hit, because the dialog
 * takes a moment to leave the composition and a second callback would try to redeem an invite that
 * the first one has already burned.
 */
@Composable
fun QrScannerDialog(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanned by rememberUpdatedState(onScanned)

    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val bound = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect

        val main = ContextCompat.getMainExecutor(context)
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (provider == null) {
            failed = true
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                // The analyzer runs off the main thread; the result has to come back onto it.
                it.setAnalyzer(executor, QrAnalyzer { payload -> main.execute { scanned(payload) } })
            }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            bound.value = provider
        }.onFailure { failed = true }
    }

    DisposableEffect(Unit) {
        onDispose {
            bound.value?.unbindAll()
            executor.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan the other phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    denied -> Text(
                        "Curbside needs the camera to read the pairing code. Nothing is recorded: " +
                            "the frames are searched for a code and thrown away.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    failed -> Text(
                        "This phone's camera could not be opened. Try pairing the other way " +
                            "round — show the code here and scan it there.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    granted -> {
                        AndroidView(
                            factory = { previewView },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                        Text(
                            "Hold it steady over the square on the other phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    else -> Text("Waiting for the camera permission…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Reads QR codes out of the analysis frames.
 *
 * The frame's rotation is ignored on purpose: the QR finder patterns are found at any orientation,
 * so rotating the buffer first would only cost a copy per frame.
 */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    @Volatile
    private var latched = false

    override fun analyze(image: ImageProxy) {
        try {
            if (latched) return
            val payload = decode(image) ?: return
            latched = true
            onFound(payload)
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val luminance = ByteArray(buffer.remaining()).also(buffer::get)

        // The Y plane is row-padded, so the stride — not the image width — is the data width, and
        // the visible pixels are the crop within it.
        val source = PlanarYUVLuminanceSource(
            luminance,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )

        // A code photographed off a screen can come through inverted depending on the other
        // phone's theme and glare, and zxing does not try that itself.
        return sequenceOf(source, source.invert()).firstNotNullOfOrNull { candidate ->
            runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))) }
                .getOrNull()
                ?.text
        }.also { reader.reset() }
    }
}
