package com.suksesjt12.pcgamelauncher.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device AF_UNIX test covering X11 setup, window creation, mapping and a ZPixmap frame. */
object PcglX11ProtocolSelfTest {
    data class Result(val success: Boolean, val details: String)

    fun run(
        runtimeRoot: File,
        frameSink: (PcglWindowFrame) -> Unit = {}
    ): Result {
        val redFrameArrived = CountDownLatch(1)
        val windowX = 8
        val windowY = 8
        val server = PcglX11ProtocolServer(runtimeRoot, 640, 480) { frame ->
            frameSink(frame)
            val offset = (windowY * frame.width + windowX) * 4
            val pixels = frame.rgbaPixels
            if ((pixels[offset].toInt() and 0xff) == 255 &&
                (pixels[offset + 1].toInt() and 0xff) == 0 &&
                (pixels[offset + 2].toInt() and 0xff) == 0
            ) {
                redFrameArrived.countDown()
            }
        }
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
                val setupValid = rootWindow == PcglX11SetupCodec.ROOT_WINDOW_ID && width == 640 && height == 480
                if (!setupValid) {
                    return Result(false, "X11 setup reply fields did not match the expected root-screen record.")
                }

                val window = 0x00200010
                val gc = 0x00200011
                val requests = ByteBuffer.allocate(32 + 16 + 8 + 40).order(ByteOrder.LITTLE_ENDIAN)
                requests.put(1).put(24).putShort(8)
                    .putInt(window).putInt(rootWindow)
                    .putShort(windowX.toShort()).putShort(windowY.toShort())
                    .putShort(2).putShort(2).putShort(0).putShort(1)
                    .putInt(PcglX11SetupCodec.ROOT_VISUAL_ID).putInt(0)
                requests.put(55).put(0).putShort(4).putInt(gc).putInt(window).putInt(0)
                requests.put(8).put(0).putShort(2).putInt(window)
                requests.put(72).put(2).putShort(10).putInt(window).putInt(gc)
                    .putShort(2).putShort(2).putShort(0).putShort(0).put(0).put(24).putShort(0)
                    .put(byteArrayOf(
                        0, 0, 0xff.toByte(), 0,
                        0, 0xff.toByte(), 0, 0,
                        0xff.toByte(), 0, 0, 0,
                        0, 0, 0, 0
                    ))
                socket.outputStream.write(requests.array())
                socket.outputStream.flush()

                val frameArrived = redFrameArrived.await(3, TimeUnit.SECONDS)
                Result(
                    frameArrived,
                    if (frameArrived) {
                        "PASS: X11 setup, CreateWindow, CreateGC, MapWindow and 2x2 ZPixmap PutImage produced the expected RGBA screen pixel."
                    } else {
                        "FAIL: X11 setup succeeded, but the mapped window image did not reach the framebuffer within 3 seconds."
                    }
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
