package dev.tobins.mealplanner.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a barcode from a picture (issue 356). The pictures are made here with ZXing's writer, on a white sheet like a
 * photograph of a pack would have, so that no camera is needed and nothing is downloaded.
 */
class BarcodeReaderTest {
    /** [text] as a picture of [format]: the bars on a larger white sheet. */
    private fun picture(text: String, format: BarcodeFormat, width: Int = 320, height: Int = 160): LuminanceImage {
        val bars = MultiFormatWriter().encode(text, format, width, height)
        val sheetWidth = width + 120
        val sheetHeight = height + 80
        val pixels = ByteArray(sheetWidth * sheetHeight) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            if (bars[x, y]) pixels[(y + 40) * sheetWidth + x + 60] = 0
        }
        return LuminanceImage(sheetWidth, sheetHeight, pixels)
    }

    @Test
    fun anEan13IsRead() = assertEquals("8712800035626", BarcodeReader.read(picture("8712800035626", BarcodeFormat.EAN_13)))

    @Test
    fun anEan8IsRead() = assertEquals("96385074", BarcodeReader.read(picture("96385074", BarcodeFormat.EAN_8)))

    @Test
    fun aUpcAIsReadAsItsTwelveDigits() = assertEquals("036000291452", BarcodeReader.read(picture("036000291452", BarcodeFormat.UPC_A)))

    @Test
    fun aBarcodeTurnedAQuarterIsRead() {
        val turned = picture("8712800035626", BarcodeFormat.EAN_13).turnedAQuarter()
        assertEquals("8712800035626", BarcodeReader.read(turned))
        // The other way round: turned three quarters.
        assertEquals("8712800035626", BarcodeReader.read(turned.turnedAQuarter().turnedAQuarter()))
        assertEquals("96385074", BarcodeReader.read(picture("96385074", BarcodeFormat.EAN_8).turnedAQuarter()))
    }

    @Test
    fun aSmallBarcodeInALargeFrameIsRead() {
        val bars = picture("8712800035626", BarcodeFormat.EAN_13, width = 190, height = 100)
        val frame = LuminanceImage(1280, 720, ByteArray(1280 * 720) { -1 })
        val pixels = frame.pixels
        for (y in 0 until bars.height) System.arraycopy(bars.pixels, y * bars.width, pixels, (y + 300) * 1280 + 500, bars.width)
        assertEquals("8712800035626", BarcodeReader.read(frame))
    }

    @Test
    fun aPictureWithoutABarcodeGivesNothing() {
        val sheet = LuminanceImage(300, 200, ByteArray(300 * 200) { -1 })
        assertNull(BarcodeReader.read(sheet))
        val noise = LuminanceImage(300, 200, Random(7).nextBytes(300 * 200))
        assertNull(BarcodeReader.read(noise))
    }

    @Test
    fun aCodeOfAnotherKindGivesNothing() {
        val bars = MultiFormatWriter().encode("8712800035626", BarcodeFormat.QR_CODE, 200, 200)
        val pixels = ByteArray(200 * 200) { if (bars[it % 200, it / 200]) 0 else -1 }
        assertNull(BarcodeReader.read(LuminanceImage(200, 200, pixels)))
        assertNull(BarcodeReader.read(picture("8712800035626", BarcodeFormat.CODE_128)))
    }

    @Test
    fun theCheckDigitIsChecked() {
        assertTrue(BarcodeReader.checkDigitRight("8712800035626"))
        assertTrue(BarcodeReader.checkDigitRight("96385074"))
        assertTrue(BarcodeReader.checkDigitRight("036000291452"))
        assertFalse(BarcodeReader.checkDigitRight("8712800035627"))
        assertFalse(BarcodeReader.checkDigitRight("96385075"))
        assertFalse(BarcodeReader.checkDigitRight("036000291453"))
        // Digits only, and only the lengths of the three codes.
        assertFalse(BarcodeReader.checkDigitRight("87128x0035626"))
        assertFalse(BarcodeReader.checkDigitRight("871280003562"))
    }
}
