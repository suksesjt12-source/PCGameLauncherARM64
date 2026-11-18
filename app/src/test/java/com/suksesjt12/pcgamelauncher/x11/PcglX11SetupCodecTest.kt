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
    fun authorizationIsRejectedWhenNoCookieIsConfigured() {
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

    @Test
    fun matchingMitMagicCookieIsAccepted() {
        val cookie = ByteArray(16) { (it * 7).toByte() }
        val request = authenticatedSetupRequest(cookie)
        assertTrue(PcglX11SetupCodec.parseRequest(request, cookie) is PcglX11SetupCodec.ParseResult.Accepted)
    }

    @Test
    fun wrongMitMagicCookieIsRejected() {
        val cookie = ByteArray(16) { (it * 7).toByte() }
        val wrongCookie = cookie.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        val request = authenticatedSetupRequest(cookie)
        assertTrue(PcglX11SetupCodec.parseRequest(request, wrongCookie) is PcglX11SetupCodec.ParseResult.Rejected)
    }

    private fun authenticatedSetupRequest(cookie: ByteArray): ByteArray {
        val authName = PcglX11SetupCodec.MIT_MAGIC_COOKIE_NAME.toByteArray(Charsets.ISO_8859_1)
        val namePadding = ByteArray(PcglX11SetupCodec.align4(authName.size) - authName.size)
        val cookiePadding = ByteArray(PcglX11SetupCodec.align4(cookie.size) - cookie.size)
        val request = ByteBuffer.allocate(12 + authName.size + namePadding.size + cookie.size + cookiePadding.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        request.put('l'.code.toByte())
        request.put(0)
        request.putShort(11)
        request.putShort(0)
        request.putShort(authName.size.toShort())
        request.putShort(cookie.size.toShort())
        request.put(authName)
        request.put(namePadding)
        request.put(cookie)
        request.put(cookiePadding)
        return request.array()
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
