package nyc.curbside.ui.share

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor
import kotlin.math.min

/**
 * Draws [payload] as a QR code.
 *
 * Deliberately black on white in both themes. A QR code is read by a camera, not by the person
 * holding the phone, and inverting it for dark mode costs scans on readers that do not try the
 * inverted image.
 */
@Composable
fun QrCode(
    payload: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val matrix = remember(payload) { encodeQr(payload) }

    Canvas(modifier) {
        drawRect(background)
        val modules = matrix ?: return@Canvas

        // A whole number of pixels per module, then centre what is left over. Fractional modules
        // leave hairline seams that some readers see as a broken timing pattern.
        val scale = floor(min(size.width, size.height) / modules.width)
        if (scale < 1f) return@Canvas
        val drawn = scale * modules.width
        val originX = (size.width - drawn) / 2f
        val originY = (size.height - drawn) / 2f

        for (y in 0 until modules.height) {
            // Horizontal runs rather than one rect per module: fewer draw calls, and no seam
            // between neighbours in a run.
            var x = 0
            while (x < modules.width) {
                if (!modules.get(x, y)) {
                    x++
                    continue
                }
                var end = x
                while (end < modules.width && modules.get(end, y)) end++
                drawRect(
                    color = foreground,
                    topLeft = Offset(originX + x * scale, originY + y * scale),
                    size = Size((end - x) * scale, scale),
                )
                x = end
            }
        }
    }
}

/**
 * Encodes at module resolution: passing zero for the pixel dimensions makes zxing fall back to one
 * pixel per module, which is what [QrCode] wants so it can pick its own scale. Returns null rather
 * than throwing on a payload too long to encode — an invite that cannot be drawn is a missing
 * square on screen, not a crash.
 */
private fun encodeQr(payload: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            // The code is read off a bright screen from 20cm away, so the lowest correction level
            // that keeps it comfortably scannable also keeps the modules large.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}.getOrNull()

/** The quiet zone the spec asks for. It is part of the matrix, so [QrCode] never has to pad. */
private const val QUIET_ZONE_MODULES = 4
