package com.suksesjt12.pcgamelauncher.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** On-device loopback check for the X11 setup request/reply exchange. */
object PcglX11ProtocolSelfTest {
    data class Result(val success: Boolean, val details: String)

    fun run(runtimeRoot: File): Result {
        val server = PcglX11ProtocolServer(runtimeRoot, 640, 480)
        return try {
            val endpoint = server.start()
            LocalSocket().use { socket ->
                socket.setSoTimeout(3000)
                socket.connect(
                    LocalSocketAddress(endpoint.socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                val setup = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                setup.put('l'.code.toByte())
                setup.put(0)
                setup.putShort(11)
                setup.putShort(0)
                setup.putShort(0)
                setup.putShort(0)
                socket.outputStream.write(setup.array())
                socket.outputStream.flush()

                val header = ByteArray(8)
                readFully(socket, header)
                val status = header[0].toInt() and 0xff
                val bodyLengthWords =
                    (header[6].toInt() and 0xff) or ((header[7].toInt() and 0xff) shl 8)
                if (bodyLengthWords > 4096) {
                    return Result(false, "X11 server returned an invalid setup reply length.")
                }
                val body = ByteArray(bodyLengthWords * 4)
                readFully(socket, body)

                if (status != 1) {
                    val reasonLength = header[1].toInt() and 0xff
                    val reason = String(body, 0, reasonLength.coerceAtMost(body.size), Charsets.ISO_8859_1)
                    return Result(false, "X11 setup rejected: " + reason)
                }
                if (body.size < 140) {
                    return Result(false, "X11 setup reply is shorter than the required root-screen record.")
                }

                val rootWindow = ByteBuffer.wrap(body, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val screenOffset = 32 + 20 + 16
                val width = ByteBuffer.wrap(body, screenOffset + 20, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                val height = ByteBuffer.wrap(body, screenOffset + 22, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                val valid = rootWindow == PcglX11SetupCodec.ROOT_WINDOW_ID && width == 640 && height == 480
                Result(
                    valid,
                    if (valid) "PASS: X11 11.0 setup succeeded; root window and 640x480 screen record verified."
                    else "FAIL: X11 setup reply fields did not match the expected root-screen record."
                )
            }
        } catch (error: Throwable) {
            Result(false, "FAIL: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            runCatching { server.close() }
        }
    }

    private fun readFully(socket: LocalSocket, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = socket.inputStream.read(target, offset, target.size - offset)
            if (count < 0) throw IllegalStateException("X11 server closed the connection before replying.")
            if (count > 0) offset += count
        }
    }
}
