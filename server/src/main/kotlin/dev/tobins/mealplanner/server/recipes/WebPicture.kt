package dev.tobins.mealplanner.server.recipes

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Why a picture is not shown; logged once for each version of the file. */
class UnusablePicture(message: String) : Exception(message)

/**
 * The copy of a recipe picture the apps show: at most [LONGEST_SIDE] pixels on its longest side
 * (smaller pictures keep their size), upright as its EXIF orientation says (phone photos are stored
 * sideways), transparency on white, as a JPEG.
 *
 * Only the JDK's `javax.imageio` is used: it reads JPEG, PNG, GIF and BMP, which covers what cooking
 * sites hold; WebP, HEIC and CMYK JPEGs are unusable and skipped. It writes no WebP, hence JPEG.
 */
object WebPicture {
    const val LONGEST_SIDE = 800

    /** Larger files are not a photo meant for a web page. */
    const val MAX_BYTES = 20L * 1024 * 1024

    /** Checked before decoding, so a small file claiming huge dimensions cannot exhaust the memory. */
    const val MAX_PIXELS = 60_000_000L

    private const val QUALITY = 0.82f

    /** The web-sized copy of [file], as JPEG bytes. */
    fun of(file: File): ByteArray {
        if (file.length() > MAX_BYTES) throw UnusablePicture("${file.length() / (1024 * 1024)} MB is implausibly large for a picture")
        val bytes = file.readBytes()
        return encodeJpeg(orient(scale(decode(bytes)), exifOrientation(bytes)))
    }

    /**
     * Decodes the picture, skipping rows and columns of large ones while reading: a 12 megapixel photo
     * then takes a quarter of the memory, and is still at least twice the size it is scaled to.
     */
    private fun decode(bytes: ByteArray): BufferedImage = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
            ?: throw UnusablePicture("not a picture format the server can read (JPEG, PNG, GIF or BMP)")
        try {
            reader.setInput(input, true, true)
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width.toLong() * height > MAX_PIXELS) throw UnusablePicture("$width × $height pixels is implausibly large for a picture")
            val step = max(1, max(width, height) / (LONGEST_SIDE * 2))
            val param = reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) }
            reader.read(0, param)
        } catch (e: UnusablePicture) {
            throw e
        } catch (e: Exception) {
            throw UnusablePicture("cannot be read (${e.message ?: e::class.simpleName})")
        } finally {
            reader.dispose()
        }
    }

    /** Halves the picture until the last step is less than half, so every pixel counts (one bilinear step would skip most). */
    private fun scale(image: BufferedImage): BufferedImage {
        val factor = min(1.0, LONGEST_SIDE.toDouble() / max(image.width, image.height))
        val width = max(1, (image.width * factor).roundToInt())
        val height = max(1, (image.height * factor).roundToInt())
        var current = image
        do {
            current = draw(current, max(width, current.width / 2), max(height, current.height / 2))
        } while (current.width != width || current.height != height)
        return current
    }

    private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /** Turns or mirrors the picture as EXIF [orientation] (1 to 8) says it is meant to be seen. */
    private fun orient(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return image
        val w = image.width
        val h = image.height
        val turned = orientation >= 5
        val target = BufferedImage(if (turned) h else w, if (turned) w else h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (tx, ty) = when (orientation) {
                    2 -> w - 1 - x to y
                    3 -> w - 1 - x to h - 1 - y
                    4 -> x to h - 1 - y
                    5 -> y to x
                    6 -> h - 1 - y to x
                    7 -> h - 1 - y to w - 1 - x
                    else -> y to w - 1 - x
                }
                target.setRGB(tx, ty, image.getRGB(x, y))
            }
        }
        return target
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }
}

/**
 * The orientation tag (1 to 8) of a JPEG's EXIF data, or 1 when there is none. `javax.imageio`
 * ignores it, so this reads the APP1 segment itself: a TIFF header and the first directory of tags.
 */
internal fun exifOrientation(jpeg: ByteArray): Int = runCatching {
    fun byte(at: Int) = jpeg[at].toInt() and 0xFF
    if (byte(0) != 0xFF || byte(1) != 0xD8) return@runCatching 1
    var at = 2
    while (byte(at) == 0xFF) {
        val marker = byte(at + 1)
        // The picture's data starts: no metadata after it.
        if (marker == 0xDA || marker == 0xD9) break
        val length = byte(at + 2) shl 8 or byte(at + 3)
        val start = at + 4
        if (marker == 0xE1 && String(jpeg, start, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
            val tiff = start + 6
            val bigEndian = byte(tiff) == 'M'.code
            fun u16(at: Int) = if (bigEndian) byte(at) shl 8 or byte(at + 1) else byte(at + 1) shl 8 or byte(at)
            fun u32(at: Int) = if (bigEndian) u16(at) shl 16 or u16(at + 2) else u16(at + 2) shl 16 or u16(at)
            val directory = tiff + u32(tiff + 4)
            val entry = (0 until u16(directory)).map { directory + 2 + 12 * it }.firstOrNull { u16(it) == ORIENTATION_TAG }
            return@runCatching entry?.let { u16(it + 8) }?.takeIf { it in 1..8 } ?: 1
        }
        at += 2 + length
    }
    1
}.getOrDefault(1)

private const val ORIENTATION_TAG = 0x0112
