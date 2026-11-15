package dev.tobins.mealplanner.server

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory

/** Tiny pictures and cooking sites made up for tests. */
object PictureFixtures {
    /**
     * A JPEG, red in its top-left quarter and blue elsewhere, so a test can tell which way it was
     * turned; with an EXIF [orientation] as a phone camera writes it, when given.
     */
    fun jpeg(width: Int, height: Int, orientation: Int? = null, background: Color = Color.BLUE): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = background
            fillRect(0, 0, width, height)
            color = Color.RED
            fillRect(0, 0, width / 2, height / 2)
            dispose()
        }
        val plain = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        if (orientation == null) return plain
        // A big-endian TIFF header and one directory holding only the orientation tag.
        val tiff = bytes(
            'M'.code, 'M'.code, 0, 42, 0, 0, 0, 8,
            0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0,
            0, 0, 0, 0,
        )
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val length = payload.size + 2
        val segment = bytes(0xFF, 0xE1, length shr 8, length and 0xFF) + payload
        // After the JFIF segment ImageIO writes first, where cameras put it too.
        val afterJfif = 4 + ((plain[4].toInt() and 0xFF) shl 8 or (plain[5].toInt() and 0xFF))
        return plain.copyOfRange(0, afterJfif) + segment + plain.copyOfRange(afterJfif, plain.size)
    }

    /** A half-transparent PNG. */
    fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color(0, 128, 0, 128)
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A valid one-pixel PNG whose header claims [width] × [height] pixels: a decompression bomb's way in. */
    fun pngClaiming(width: Int, height: Int): ByteArray {
        val bytes = png(1, 1)
        // Signature (8), then the IHDR chunk: length (4), type (4), width (4), height (4), ..., CRC.
        for ((offset, value) in listOf(16 to width, 20 to height)) {
            for (i in 0..3) bytes[offset + i] = (value shr (24 - 8 * i)).toByte()
        }
        val crc = CRC32().apply { update(bytes, 12, 17) }.value
        for (i in 0..3) bytes[29 + i] = (crc shr (24 - 8 * i)).toByte()
        return bytes
    }

    /** A cooking-site checkout with [files] (text or bytes) at their paths. */
    fun site(vararg files: Pair<String, Any>): File = createTempDirectory("cooking").toFile().also { dir ->
        for ((path, content) in files) write(dir, path, content)
    }

    fun write(dir: File, path: String, content: Any) {
        val file = File(dir, path).apply { parentFile.mkdirs() }
        when (content) {
            is ByteArray -> file.writeBytes(content)
            else -> file.writeText(content.toString())
        }
    }

    /** A recipe page with [frontMatter] lines and [body]. */
    fun page(title: String, frontMatter: String = "", body: String = "") =
        "---\ntitle: $title\n$frontMatter\n---\n```ingredients\nservings: 2\n200 | g | rice\n```\n$body\n"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
