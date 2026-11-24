package com.arynis.wavetune.desktop.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.oned.Code128Reader
import com.arynis.wavetune.shared.models.Track
import com.arynis.wavetune.shared.share.WaveTuneCode
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSongCodeCardTest {

    @Test
    fun `full exported desktop card scans as the same WaveTune Code on Android`() {
        val videoId = "dQw4w9WgXcQ"
        val code = requireNotNull(WaveTuneCode.encode(videoId))
        val card = renderDesktopSongCodeCard(
            Track(
                id = videoId,
                title = "Never Gonna Give You Up",
                artist = "Rick Astley",
                source = videoId,
                isLocal = false,
                videoId = videoId,
            ),
            code,
        )

        val bitmap = BinaryBitmap(HybridBinarizer(AwtLuminanceSource(card)))
        val scannedText = Code128Reader().decode(bitmap).text

        assertEquals(videoId, WaveTuneCode.decode(scannedText))
    }
}

private class AwtLuminanceSource(image: java.awt.image.BufferedImage) :
    LuminanceSource(image.width, image.height) {
    private val pixels = IntArray(image.width * image.height).also {
        image.getRGB(0, 0, image.width, image.height, it, 0, image.width)
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height)
        val result = row?.takeIf { it.size >= width } ?: ByteArray(width)
        val offset = y * width
        for (x in 0 until width) {
            val color = pixels[offset + x]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            result[x] = ((red + green + green + blue) shr 2).toByte()
        }
        return result
    }

    override fun getMatrix(): ByteArray = ByteArray(pixels.size) { index ->
        val color = pixels[index]
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        ((red + green + green + blue) shr 2).toByte()
    }
}
