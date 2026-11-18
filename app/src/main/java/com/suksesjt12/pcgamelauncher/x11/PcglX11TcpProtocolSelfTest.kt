package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-device loopback test for the authenticated TCP X11 path used by Wine DISPLAY. */
object PcglX11TcpProtocolSelfTest {
    data class Result(val success: Boolean, val details: String)

    fun run(runtimeRoot: File, frameSink: (PcglWindowFrame) -> Unit = {}): Result {
        val redFrameArrived = CountDownLatch(1)
        val server = PcglX11ProtocolServer(
            runtimeRoot = runtimeRoot,
            screenWidthPx = 640,
            screenHeightPx = 480,
            tcpPort = 6000
        ) { frame ->
            frameSink(frame)
            val offset = (8 * frame.width + 8) * 4
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
            val cookie = endpoint.authCookie ?: return Result(false, "TCP X11 endpoint did not generate an authentication cookie.")
            val prefix = File(runtimeRoot, "tcp-x11-self-test-prefix")
            if (!prefix.exists() && !prefix.mkdirs()) {
                return Result(false, "Unable to create a temporary Xauthority test directory.")
            }
            val authorityFile = PcglXAuthority.write(prefix, endpoint)

            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = 3000
                socket.connect(InetSocketAddress("127.0.0.1", 6000), 3000)

                val setup = authenticatedSetupRequest(cookie)
                socket.getOutputStream().write(setup)
                socket.getOutputStream().flush()

                val header = ByteArray(8)
                readFully(socket.getInputStream(), header)
                val status = header[0].toInt() and 0xff
                val bodyLengthWords = (header[6].toInt() and 0xff) or
                    ((header[7].toInt() and 0xff) shl 8)
                if (bodyLengthWords > 4096) return Result(false, "TCP X11 returned an invalid setup length.")
                val body = ByteArray(bodyLengthWords * 4)
                readFully(socket.getInputStream(), body)
                if (status != 1) {
                    val reasonLength = header[1].toInt() and 0xff
                    val reason = String(body, 0, reasonLength.coerceAtMost(body.size), Charsets.ISO_8859_1)
                    return Result(false, "TCP X11 cookie handshake rejected: $reason")
                }

                val root = ByteBuffer.wrap(body, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (root != PcglX11SetupCodec.ROOT_WINDOW_ID) {
                    return Result(false, "TCP X11 root-window identifier was invalid.")
                }

                val window = 0x00200010
                val gc = 0x00200011
                val requests = ByteBuffer.allocate(32 + 16 + 8 + 40).order(ByteOrder.LITTLE_ENDIAN)
                requests.put(1).put(24).putShort(8)
                    .putInt(window).putInt(root)
                    .putShort(8).putShort(8)
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
                socket.getOutputStream().write(requests.array())
                socket.getOutputStream().flush()

                val frameArrived = redFrameArrived.await(3, TimeUnit.SECONDS)
                Result(
                    frameArrived && authorityFile.isFile && authorityFile.length() > 32L,
                    if (frameArrived && authorityFile.isFile) {
                        "PASS: loopback TCP :0, MIT-MAGIC-COOKIE-1, private Xauthority file, CreateWindow, MapWindow and PutImage reached the framebuffer."
                    } else {
                        "FAIL: TCP handshake or X11 window-image transfer did not complete."
                    }
                )
            }
        } catch (error: Throwable) {
            Result(false, "FAIL: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            runCatching { server.close() }
        }
    }

    private fun authenticatedSetupRequest(cookie: ByteArray): ByteArray {
        val authName = PcglX11SetupCodec.MIT_MAGIC_COOKIE_NAME.toByteArray(Charsets.ISO_8859_1)
        val namePadded = PcglX11SetupCodec.align4(authName.size)
        val cookiePadded = PcglX11SetupCodec.align4(cookie.size)
        val request = ByteBuffer.allocate(12 + namePadded + cookiePadded).order(ByteOrder.LITTLE_ENDIAN)
        request.put('l'.code.toByte())
        request.put(0)
        request.putShort(11)
        request.putShort(0)
        request.putShort(authName.size.toShort())
        request.putShort(cookie.size.toShort())
        request.put(authName)
        repeat(namePadded - authName.size) { request.put(0) }
        request.put(cookie)
        repeat(cookiePadded - cookie.size) { request.put(0) }
        return request.array()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IllegalStateException("X11 server closed the connection before replying.")
            if (count > 0) offset += count
        }
    }
}
