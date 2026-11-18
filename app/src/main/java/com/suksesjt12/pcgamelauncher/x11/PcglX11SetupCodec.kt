package com.suksesjt12.pcgamelauncher.x11

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Wire-format helpers for the initial X11 connection setup exchange. */
internal object PcglX11SetupCodec {
    private const val PROTOCOL_MAJOR = 11
    private const val PROTOCOL_MINOR = 0
    private const val MAX_SETUP_BYTES = 64 * 1024
    const val ROOT_WINDOW_ID = 0x00200000
    const val ROOT_COLORMAP_ID = 0x00200001
    const val ROOT_VISUAL_ID = 0x00200002

    sealed class ParseResult {
        object NeedMore : ParseResult()
        data class Accepted(val byteOrder: ByteOrder, val consumedBytes: Int) : ParseResult()
        data class Rejected(val byteOrder: ByteOrder, val reason: String, val consumedBytes: Int) : ParseResult()
    }

    fun parseRequest(bytes: ByteArray, expectedAuthCookie: ByteArray? = null): ParseResult {
        if (bytes.isEmpty()) return ParseResult.NeedMore
        val byteOrder = when (bytes[0].toInt() and 0xff) {
            0x6c -> ByteOrder.LITTLE_ENDIAN
            0x42 -> ByteOrder.BIG_ENDIAN
            else -> return ParseResult.Rejected(ByteOrder.LITTLE_ENDIAN, "Invalid X11 client byte-order marker.", 1)
        }
        if (bytes.size < 12) return ParseResult.NeedMore
        val major = readU16(bytes, 2, byteOrder)
        val minor = readU16(bytes, 4, byteOrder)
        val authNameLength = readU16(bytes, 6, byteOrder)
        val authDataLength = readU16(bytes, 8, byteOrder)
        val totalLength = 12 + align4(authNameLength) + align4(authDataLength)
        if (totalLength > MAX_SETUP_BYTES) {
            return ParseResult.Rejected(byteOrder, "X11 setup request exceeds the supported size.", 12)
        }
        if (bytes.size < totalLength) return ParseResult.NeedMore
        if (major != PROTOCOL_MAJOR || minor > PROTOCOL_MINOR) {
            return ParseResult.Rejected(byteOrder, "Unsupported X11 protocol version " + major + "." + minor + ".", totalLength)
        }
        val authNameOffset = 12
        val authDataOffset = authNameOffset + align4(authNameLength)
        if (expectedAuthCookie == null) {
            if (authNameLength != 0 || authDataLength != 0) {
                return ParseResult.Rejected(byteOrder, "This X11 endpoint accepts unauthenticated local socket clients only.", totalLength)
            }
        } else {
            val authName = String(bytes, authNameOffset, authNameLength, Charsets.ISO_8859_1)
            val authData = bytes.copyOfRange(authDataOffset, authDataOffset + authDataLength)
            val cookieMatches = authName == MIT_MAGIC_COOKIE_NAME &&
                authData.size == expectedAuthCookie.size &&
                MessageDigest.isEqual(authData, expectedAuthCookie)
            if (!cookieMatches) {
                return ParseResult.Rejected(byteOrder, "X11 MIT-MAGIC-COOKIE-1 authentication failed.", totalLength)
            }
        }
        return ParseResult.Accepted(byteOrder, totalLength)
    }

    fun buildSuccess(byteOrder: ByteOrder, widthPx: Int, heightPx: Int): ByteArray {
        val vendor = "PCGameLauncherARM64".toByteArray(Charsets.ISO_8859_1)
        val vendorPaddedLength = align4(vendor.size)
        val payloadLength = 32 + vendorPaddedLength + 16 + 40 + 8 + 24
        val output = ByteBuffer.allocate(8 + payloadLength).order(byteOrder)
        output.put(1)
        output.put(0)
        output.putShort(PROTOCOL_MAJOR.toShort())
        output.putShort(PROTOCOL_MINOR.toShort())
        output.putShort((payloadLength / 4).toShort())

        output.putInt(1)
        output.putInt(ROOT_WINDOW_ID)
        output.putInt(0x001fffff)
        output.putInt(256)
        output.putShort(vendor.size.toShort())
        output.putShort(65535.toShort())
        output.put(1)
        output.put(2)
        output.put(if (byteOrder == ByteOrder.LITTLE_ENDIAN) 0 else 1)
        output.put(0)
        output.put(32)
        output.put(32)
        output.put(8)
        output.put(255.toByte())
        output.putInt(0)

        output.put(vendor)
        repeat(vendorPaddedLength - vendor.size) { output.put(0) }
        output.put(1)
        output.put(1)
        output.put(32)
        repeat(5) { output.put(0) }
        output.put(24)
        output.put(32)
        output.put(32)
        repeat(5) { output.put(0) }

        val safeWidth = widthPx.coerceIn(1, 65535)
        val safeHeight = heightPx.coerceIn(1, 65535)
        output.putInt(ROOT_WINDOW_ID)
        output.putInt(ROOT_COLORMAP_ID)
        output.putInt(0x00ffffff)
        output.putInt(0)
        output.putInt(0)
        output.putShort(safeWidth.toShort())
        output.putShort(safeHeight.toShort())
        output.putShort((safeWidth / 3).coerceIn(1, 65535).toShort())
        output.putShort((safeHeight / 3).coerceIn(1, 65535).toShort())
        output.putShort(1)
        output.putShort(1)
        output.putInt(ROOT_VISUAL_ID)
        output.put(0)
        output.put(0)
        output.put(24)
        output.put(1)

        output.put(24)
        output.put(0)
        output.putShort(1)
        output.putInt(0)
        output.putInt(ROOT_VISUAL_ID)
        output.put(4)
        output.put(8)
        output.putShort(256)
        output.putInt(0x00ff0000)
        output.putInt(0x0000ff00)
        output.putInt(0x000000ff)
        output.putInt(0)

        check(!output.hasRemaining()) { "X11 setup success packet size mismatch." }
        return output.array()
    }

    fun buildFailure(byteOrder: ByteOrder, reason: String): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
        val paddedLength = align4(reasonBytes.size)
        val output = ByteBuffer.allocate(8 + paddedLength).order(byteOrder)
        output.put(0)
        output.put(reasonBytes.size.toByte())
        output.putShort(PROTOCOL_MAJOR.toShort())
        output.putShort(PROTOCOL_MINOR.toShort())
        output.putShort((paddedLength / 4).toShort())
        output.put(reasonBytes)
        repeat(paddedLength - reasonBytes.size) { output.put(0) }
        return output.array()
    }

    fun readU16(bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Int {
        require(offset >= 0 && offset + 1 < bytes.size)
        val first = bytes[offset].toInt() and 0xff
        val second = bytes[offset + 1].toInt() and 0xff
        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) first or (second shl 8) else (first shl 8) or second
    }

    fun align4(value: Int): Int = (value + 3) and -4

    const val MIT_MAGIC_COOKIE_NAME = "MIT-MAGIC-COOKIE-1"
}
