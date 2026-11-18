package com.suksesjt12.pcgamelauncher.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcglX11SetupCodecTest {
    @Test
    fun littleEndianSetupProducesCompleteRootScreenRecord() {
        val parsed = PcglX11SetupCodec.parseRequest(setupRequest(ByteOrder.LITTLE_ENDIAN))
        assertTrue(parsed is PcglX11SetupCodec.ParseResult.Accepted)
        val response = PcglX11SetupCodec.buildSuccess(ByteOrder.LITTLE_ENDIAN, 640, 480)
        assertEquals(148, response.size)
        assertEquals(1, response[0].toInt())
        assertEquals(35, PcglX11SetupCodec.readU16(response, 6, ByteOrder.LITTLE_ENDIAN))

        val body = response.copyOfRange(8, response.size)
        val root = ByteBuffer.wrap(body, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(PcglX11SetupCodec.ROOT_WINDOW_ID, root)
        val screenOffset = 32 + 20 + 16
        val width = ByteBuffer.wrap(body, screenOffset + 20, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val height = ByteBuffer.wrap(body, screenOffset + 22, 2)
            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        assertEquals(640, width)
        assertEquals(480, height)
    }

    @Test
    fun bigEndianSetupProducesBigEndianReply() {
        val parsed = PcglX11SetupCodec.parseRequest(setupRequest(ByteOrder.BIG_ENDIAN))
        assertTrue(parsed is PcglX11SetupCodec.ParseResult.Accepted)
        val response = PcglX11SetupCodec.buildSuccess(ByteOrder.BIG_ENDIAN, 800, 600)
        assertEquals(1, response[0].toInt())
        assertEquals(0, response[6].toInt())
        assertEquals(35, response[7].toInt() and 0xff)
    }

    @Test
    fun partialSetupRequestWaitsForMoreBytes() {
        val request = setupRequest(ByteOrder.LITTLE_ENDIAN)
        assertTrue(
            PcglX11SetupCodec.parseRequest(request.copyOfRange(0, 7)) is
                PcglX11SetupCodec.ParseResult.NeedMore
        )
    }

    @Test
    fun authorizationIsRejectedUntilCookieSupportExists() {
        val request = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        request.put('l'.code.toByte())
        request.put(0)
        request.putShort(11)
        request.putShort(0)
        request.putShort(4)
        request.putShort(0)
        request.putShort(0)
        request.putInt(0)
        val parsed = PcglX11SetupCodec.parseRequest(request.array())
        assertTrue(parsed is PcglX11SetupCodec.ParseResult.Rejected)
    }

    private fun setupRequest(order: ByteOrder): ByteArray {
        val request = ByteBuffer.allocate(12).order(order)
        request.put(if (order == ByteOrder.LITTLE_ENDIAN) 'l'.code.toByte() else 'B'.code.toByte())
        request.put(0)
        request.putShort(11)
        request.putShort(0)
        request.putShort(0)
        request.putShort(0)
        return request.array()
    }
}
