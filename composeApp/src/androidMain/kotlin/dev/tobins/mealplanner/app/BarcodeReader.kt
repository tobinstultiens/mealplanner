package dev.tobins.mealplanner.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/*
 * Reading a barcode from a picture (issue 356), kept apart from the camera: a grey picture in, the digits out, so that it
 * is tested with pictures made in the test. ZXing's decoder is pure Java and ships inside the app; nothing here keeps a
 * picture.
 */

/** A grey picture: one byte per pixel, 0 for black to 255 for white, row after row; what a camera frame's luminance plane is. */
class LuminanceImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "A $width x $height picture has ${width * height} pixels, not ${pixels.size}" }
    }

    /** This picture turned a quarter clockwise: a phone held upright sees the bars of a barcode running the other way. */
    fun turnedAQuarter(): LuminanceImage {
        val turned = ByteArray(pixels.size)
        for (y in 0 until height) for (x in 0 until width) turned[x * height + (height - 1 - y)] = pixels[y * width + x]
        return LuminanceImage(height, width, turned)
    }
}

/** Reads EAN-13, EAN-8 and UPC-A; no other code, so that a QR code or a stray pattern is not taken for a product. */
object BarcodeReader {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The digits of the barcode in [image], also when it is turned a quarter; null when there is none or its check digit
     * is wrong. An EAN-13 gives 13 digits, an EAN-8 8 and a UPC-A its 12, which the server's lookup takes as it takes a
     * typed one.
     */
    fun read(image: LuminanceImage): String? = digitsIn(image) ?: digitsIn(image.turnedAQuarter())

    /** Whether the last digit of [digits] is the check digit of the others (EAN-13, EAN-8 and UPC-A weigh them 3, 1, 3, … from the right). */
    fun checkDigitRight(digits: String): Boolean {
        if (digits.length !in setOf(8, 12, 13) || !digits.all { it in '0'..'9' }) return false
        val sum = digits.dropLast(1).reversed().withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 3 else 1 }
        return (10 - sum % 10) % 10 == digits.last() - '0'
    }

    private fun digitsIn(image: LuminanceImage): String? {
        val source = PlanarYUVLuminanceSource(image.pixels, image.width, image.height, 0, 0, image.width, image.height, false)
        val reader = MultiFormatReader()
        val text = try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            return null
        } finally {
            reader.reset()
        }
        return text.takeIf(::checkDigitRight)
    }
}
