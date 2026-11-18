package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcglX11FramebufferTest {
    @Test
    fun putImageConvertsX11BgrxIntoMappedScreenRgba() {
        var rendered: PcglWindowFrame? = null
        val framebuffer = PcglX11Framebuffer(8, 6) { rendered = it }
        val window = 0x00200010
        val gc = 0x00200011

        assertTrue(framebuffer.createWindow(window, PcglX11SetupCodec.ROOT_WINDOW_ID, 2, 1, 2, 2, 24))
        assertTrue(framebuffer.createGraphicsContext(gc))
        assertTrue(framebuffer.mapWindow(window))

        val bgrx = byteArrayOf(
            0, 0, 0xff.toByte(), 0, 0, 0xff.toByte(), 0, 0,
            0xff.toByte(), 0, 0, 0, 0, 0, 0, 0
        )
        assertTrue(framebuffer.putImage(
            window, gc, 2, 2, 2, 0, 0, 24, bgrx, java.nio.ByteOrder.LITTLE_ENDIAN
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
        assertTrue(framebuffer.createWindow(window, PcglX11SetupCodec.ROOT_WINDOW_ID, 0, 0, 2, 2, 24))
        val pixels = ByteArray(16)
        assertTrue(!framebuffer.putImage(
            window, 0x00200012, 2, 2, 2, 0, 0, 24, pixels, java.nio.ByteOrder.LITTLE_ENDIAN
        ))
        assertTrue(framebuffer.createGraphicsContext(0x00200011))
        assertTrue(!framebuffer.putImage(
            window, 0x00200011, 2, 2, 2, 0, 0, 16, pixels, java.nio.ByteOrder.LITTLE_ENDIAN
        ))
    }

    @Test
    fun getImageReturnsClientOrderedZPixmapBytes() {
        val framebuffer = PcglX11Framebuffer(8, 6) {}
        val window = 0x00200030
        val gc = 0x00200031
        assertTrue(framebuffer.createWindow(window, PcglX11SetupCodec.ROOT_WINDOW_ID, 0, 0, 3, 2, 24))
        assertTrue(framebuffer.createGraphicsContext(gc, foreground = 0x00112233))
        assertTrue(framebuffer.mapWindow(window))
        assertTrue(framebuffer.fillRectangles(window, gc, listOf(intArrayOf(0, 0, 1, 1))))

        val littleEndian = framebuffer.getImage(
            window, 0, 0, 1, 1, java.nio.ByteOrder.LITTLE_ENDIAN
        )!!
        assertEquals(0x33, littleEndian[0].toInt() and 0xff)
        assertEquals(0x22, littleEndian[1].toInt() and 0xff)
        assertEquals(0x11, littleEndian[2].toInt() and 0xff)

        val bigEndian = framebuffer.getImage(
            window, 0, 0, 1, 1, java.nio.ByteOrder.BIG_ENDIAN
        )!!
        assertEquals(0, bigEndian[0].toInt() and 0xff)
        assertEquals(0x11, bigEndian[1].toInt() and 0xff)
        assertEquals(0x22, bigEndian[2].toInt() and 0xff)
        assertEquals(0x33, bigEndian[3].toInt() and 0xff)
    }

    @Test
    fun fillRectangleUsesGraphicsContextForegroundColor() {
        var rendered: PcglWindowFrame? = null
        val framebuffer = PcglX11Framebuffer(8, 6) { rendered = it }
        val window = 0x00200020
        val gc = 0x00200021

        assertTrue(framebuffer.createWindow(window, PcglX11SetupCodec.ROOT_WINDOW_ID, 1, 1, 4, 3, 24))
        assertTrue(framebuffer.createGraphicsContext(gc, foreground = 0x0033aa55))
        assertTrue(framebuffer.mapWindow(window))
        assertTrue(framebuffer.fillRectangles(window, gc, listOf(intArrayOf(1, 1, 2, 1))))

        val pixels = rendered!!.rgbaPixels
        val filled = (2 * 8 + 2) * 4
        val outside = (1 * 8 + 1) * 4
        assertEquals(0x33, pixels[filled].toInt() and 0xff)
        assertEquals(0xaa, pixels[filled + 1].toInt() and 0xff)
        assertEquals(0x55, pixels[filled + 2].toInt() and 0xff)
        assertEquals(0, pixels[outside].toInt() and 0xff)
    }
}
