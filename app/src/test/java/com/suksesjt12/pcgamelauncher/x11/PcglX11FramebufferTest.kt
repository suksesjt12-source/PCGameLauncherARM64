package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

class PcglX11FramebufferTest {
    @Test
    fun putImageConvertsX11BgrxIntoMappedScreenRgba() {
        var rendered: PcglWindowFrame? = null
        val framebuffer = PcglX11Framebuffer(8, 6) { rendered = it }
        val window = 0x00200010
        val gc = 0x00200011

        assertTrue(framebuffer.createWindow(
            window, PcglX11SetupCodec.ROOT_WINDOW_ID, 2, 1, 2, 2, 24
        ))
        assertTrue(framebuffer.createGraphicsContext(gc))
        assertTrue(framebuffer.mapWindow(window))

        // Two rows of 32-bpp little-endian X11 TrueColor pixels (B, G, R, unused).
        val bgrx = byteArrayOf(
            0, 0, 0xff.toByte(), 0, 0, 0xff.toByte(), 0, 0,
            0xff.toByte(), 0, 0, 0, 0, 0, 0, 0
        )
        assertTrue(framebuffer.putImage(
            window, gc, 2, 2, 2, 0, 0, 24, bgrx, ByteOrder.LITTLE_ENDIAN
        ))

        val frame = rendered
        assertNotNull(frame)
        val pixels = frame!!.rgbaPixels
        val red = (1 * 8 + 2) * 4
        val green = (1 * 8 + 3) * 4
        val blue = (2 * 8 + 2) * 4
        assertEquals(255, pixels[red].toInt() and 0xff)
        assertEquals(0, pixels[red + 1].toInt() and 0xff)
        assertEquals(0, pixels[red + 2].toInt() and 0xff)
        assertEquals(0, pixels[green].toInt() and 0xff)
        assertEquals(255, pixels[green + 1].toInt() and 0xff)
        assertEquals(0, pixels[blue].toInt() and 0xff)
        assertEquals(255, pixels[blue + 2].toInt() and 0xff)
    }

    @Test
    fun invalidDepthAndUnknownGraphicsContextAreRejected() {
        val framebuffer = PcglX11Framebuffer(8, 8) {}
        val window = 0x00200010
        assertTrue(framebuffer.createWindow(
            window, PcglX11SetupCodec.ROOT_WINDOW_ID, 0, 0, 2, 2, 24
        ))
        val pixels = ByteArray(16)
        assertTrue(!framebuffer.putImage(
            window, 0x00200012, 2, 2, 2, 0, 0, 24, pixels, ByteOrder.LITTLE_ENDIAN
        ))
        assertTrue(framebuffer.createGraphicsContext(0x00200011))
        assertTrue(!framebuffer.putImage(
            window, 0x00200011, 2, 2, 2, 0, 0, 16, pixels, ByteOrder.LITTLE_ENDIAN
        ))
    }
}
