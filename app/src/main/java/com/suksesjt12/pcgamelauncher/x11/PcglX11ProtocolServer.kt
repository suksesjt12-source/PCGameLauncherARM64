package com.suksesjt12.pcgamelauncher.x11

import android.os.SystemClock
import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import com.suksesjt12.pcgamelauncher.x11.transport.Client
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * PCGL-owned X11 core server.
 *
 * Implemented here: setup, resource/drawable basics, map/configure, GC, ZPixmap
 * PutImage, CopyArea, clear, atoms and selected structure/exposure notifications.
 * XRender, MIT-SHM, GLX, DRI3, Vulkan WSI and a window manager are not implemented.
 */
class PcglX11ProtocolServer(
    runtimeRoot: File,
    private val screenWidthPx: Int = 1280,
    private val screenHeightPx: Int = 720,
    private val tcpPort: Int? = null,
    frameSink: (PcglWindowFrame) -> Unit = {}
) : AutoCloseable {
    data class Endpoint(
        val socketPath: String,
        val protocolVersion: String = "11.0",
        val displayEndpoint: String? = null,
        val authCookie: ByteArray? = null
    )

    private data class ClientState(
        var byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        var setupComplete: Boolean = false,
        var sequence: Int = 0,
        var pending: ByteArray = ByteArray(0),
        val eventMasks: MutableMap<Int, Int> = HashMap(),
        var focusedWindow: Int = PcglX11SetupCodec.ROOT_WINDOW_ID,
        var revertTo: Int = 1
    )

    private val framebuffer = PcglX11Framebuffer(screenWidthPx, screenHeightPx, frameSink)
    private val propertyStore = PcglX11PropertyStore()
    private val atomRegistry = PcglX11AtomRegistry()
    private val authCookie: ByteArray? = if (tcpPort != null) {
        ByteArray(16).also { SecureRandom().nextBytes(it) }
    } else null
    private lateinit var transport: PcglX11Transport

    fun copyAuthCookie(): ByteArray? = authCookie?.copyOf()

    private val dispatcher = object : PcglX11Transport.ProtocolDispatcher {
        override fun onClientConnected(client: Client) {
            try {
                client.setTag(ClientState())
                client.createIOStreams()
            } catch (_: Throwable) {
                transport.disconnect(client)
            }
        }

        override fun onClientDisconnected(client: Client) {
            client.setTag(null)
        }

        override fun handleRequest(client: Client): Boolean = handleClientRequest(client)
    }

    init {
        require(screenWidthPx > 0 && screenHeightPx > 0) { "X11 screen dimensions must be positive." }
        transport = PcglX11Transport(runtimeRoot, dispatcher, tcpPort = tcpPort)
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(): Endpoint {
        val endpoint = transport.start()
        return Endpoint(
            socketPath = endpoint.socketPath,
            displayEndpoint = endpoint.displayEndpoint.takeIf { it.isNotBlank() },
            authCookie = authCookie?.copyOf()
        )
    }

    @Synchronized
    fun stop() = transport.stop()

    override fun close() = stop()

    private fun handleClientRequest(client: Client): Boolean {
        val state = client.getTag() as? ClientState ?: return false
        val input = client.getInputStream() ?: return false
        val available = input.available()
        var progressed = false
        if (available > 0) {
            if (state.pending.size + available > MAX_BUFFERED_BYTES) {
                rejectClient(client, state, "X11 client sent an oversized request buffer.")
                return false
            }
            val incoming = ByteArray(available)
            for (index in incoming.indices) incoming[index] = input.readByte()
            state.pending = state.pending + incoming
            progressed = true
        }

        if (!state.setupComplete) {
            when (val parsed = PcglX11SetupCodec.parseRequest(state.pending, authCookie)) {
                PcglX11SetupCodec.ParseResult.NeedMore -> return progressed
                is PcglX11SetupCodec.ParseResult.Rejected -> {
                    writeResponse(client, PcglX11SetupCodec.buildFailure(parsed.byteOrder, parsed.reason))
                    transport.disconnect(client)
                    return false
                }
                is PcglX11SetupCodec.ParseResult.Accepted -> {
                    state.byteOrder = parsed.byteOrder
                    consumePending(state, parsed.consumedBytes)
                    writeResponse(client, PcglX11SetupCodec.buildSuccess(state.byteOrder, screenWidthPx, screenHeightPx))
                    state.setupComplete = true
                    progressed = true
                }
            }
        }

        while (state.pending.size >= 4) {
            val requestLengthWords = PcglX11SetupCodec.readU16(state.pending, 2, state.byteOrder)
            if (requestLengthWords < 1) {
                writeResponse(client, buildError(state, 16, 0, 0))
                transport.disconnect(client)
                return false
            }
            val requestLengthBytes = requestLengthWords * 4
            if (requestLengthBytes > MAX_REQUEST_BYTES) {
                rejectClient(client, state, "X11 request exceeds the protocol size limit.")
                return false
            }
            if (state.pending.size < requestLengthBytes) break
            val request = state.pending.copyOfRange(0, requestLengthBytes)
            consumePending(state, requestLengthBytes)
            state.sequence = (state.sequence + 1) and 0xffff
            try {
                val response = dispatchRequest(client, state, request)
                if (response != null) writeResponse(client, response)
            } catch (_: Throwable) {
                writeResponse(client, buildError(state, BAD_IMPLEMENTATION, 0, request[0].toInt() and 0xff))
                transport.disconnect(client)
                return false
            }
            progressed = true
        }
        return progressed
    }

    private fun dispatchRequest(client: Client, state: ClientState, request: ByteArray): ByteArray? {
        val opcode = request[0].toInt() and 0xff
        return when (opcode) {
            1 -> createWindow(state, request)
            2 -> changeWindowAttributes(state, request)
            3 -> getWindowAttributes(state, request)
            4 -> destroyWindow(client, state, request)
            8 -> mapWindow(client, state, request)
            10 -> unmapWindow(client, state, request)
            12 -> configureWindow(client, state, request)
            14 -> getGeometry(state, request)
            15 -> queryTree(state, request)
            16 -> internAtom(state, request)
            17 -> getAtomName(state, request)
            18 -> changeProperty(state, request)
            19 -> deleteProperty(state, request)
            20 -> getProperty(state, request)
            21 -> listProperties(state, request)
            42 -> setInputFocus(state, request)
            43 -> getInputFocus(state, request)
            53 -> createPixmap(state, request)
            54 -> freePixmap(state, request)
            55 -> createGraphicsContext(state, request)
            56 -> changeGraphicsContext(state, request)
            60 -> freeGraphicsContext(state, request)
            61 -> clearArea(state, request)
            62 -> copyArea(state, request)
            70 -> polyFillRectangle(state, request)
            72 -> putImage(state, request)
            73 -> getImage(state, request)
            98 -> queryExtension(state, request)
            99 -> listExtensions(state, request)
            127 -> null
            else -> buildError(state, if (opcode <= 127) BAD_REQUEST else BAD_IMPLEMENTATION, 0, opcode)
        }
    }

    private fun createWindow(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 32) return buildError(state, BAD_LENGTH, 0, 1)
        val id = readU32(request, 4, state.byteOrder)
        val parent = readU32(request, 8, state.byteOrder)
        val width = readU16(request, 16, state.byteOrder)
        val height = readU16(request, 18, state.byteOrder)
        val valueMask = readU32(request, 28, state.byteOrder)
        if (request.size != 32 + Integer.bitCount(valueMask) * 4) return buildError(state, BAD_LENGTH, 0, 1)
        if (!framebuffer.hasWindow(parent)) return buildError(state, BAD_WINDOW, parent, 1)
        if (width == 0 || height == 0) return buildError(state, BAD_VALUE, 0, 1)
        val x = readI16(request, 12, state.byteOrder)
        val y = readI16(request, 14, state.byteOrder)
        val depth = request[1].toInt() and 0xff
        if (!framebuffer.createWindow(id, parent, x, y, width, height, depth)) {
            return buildError(state, BAD_ID_CHOICE, id, 1)
        }
        return null
    }

    private fun changeWindowAttributes(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 12) return buildError(state, BAD_LENGTH, 0, 2)
        val window = readU32(request, 4, state.byteOrder)
        val mask = readU32(request, 8, state.byteOrder)
        if (request.size != 12 + Integer.bitCount(mask) * 4) return buildError(state, BAD_LENGTH, 0, 2)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 2)
        var offset = 12
        for (bit in 0 until 32) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = readU32(request, offset, state.byteOrder)
            if (bit == 11) state.eventMasks[window] = value
            offset += 4
        }
        return null
    }

    private fun destroyWindow(client: Client, state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 4)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window) || window == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            return buildError(state, BAD_WINDOW, window, 4)
        }
        notifySelected(client, state, window, STRUCTURE_NOTIFY, event(state, 17).apply {
            putInt(4, window); putInt(8, window)
        })
        removeWindowPropertiesRecursively(window)
        if (!framebuffer.destroyWindow(window)) return buildError(state, BAD_WINDOW, window, 4)
        return null
    }

    private fun removeWindowPropertiesRecursively(window: Int) {
        framebuffer.children(window).forEach(::removeWindowPropertiesRecursively)
        propertyStore.removeWindow(window)
    }

    private fun mapWindow(client: Client, state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 8)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window) || window == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            return buildError(state, BAD_WINDOW, window, 8)
        }
        val parent = framebuffer.parentOf(window) ?: PcglX11SetupCodec.ROOT_WINDOW_ID
        if (!framebuffer.mapWindow(window)) return buildError(state, BAD_WINDOW, window, 8)
        notifySelected(client, state, window, STRUCTURE_NOTIFY, event(state, 19).apply {
            putInt(4, window); putInt(8, window)
        })
        notifySelected(client, state, parent, SUBSTRUCTURE_NOTIFY, event(state, 19).apply {
            putInt(4, parent); putInt(8, window)
        })
        if ((state.eventMasks[window] ?: 0) and EXPOSURE != 0) {
            val geometry = framebuffer.geometry(window)
            if (geometry != null) {
                val expose = event(state, 12)
                expose.putInt(4, window)
                expose.putShort(8, 0); expose.putShort(10, 0)
                expose.putShort(12, geometry.width.toShort())
                expose.putShort(14, geometry.height.toShort())
                expose.putShort(16, 0)
                writeResponse(client, expose.array())
            }
        }
        return null
    }

    private fun unmapWindow(client: Client, state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 10)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window) || window == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            return buildError(state, BAD_WINDOW, window, 10)
        }
        val parent = framebuffer.parentOf(window) ?: PcglX11SetupCodec.ROOT_WINDOW_ID
        if (!framebuffer.unmapWindow(window)) return buildError(state, BAD_WINDOW, window, 10)
        notifySelected(client, state, window, STRUCTURE_NOTIFY, event(state, 18).apply {
            putInt(4, window); putInt(8, window); put(12, 0)
        })
        notifySelected(client, state, parent, SUBSTRUCTURE_NOTIFY, event(state, 18).apply {
            putInt(4, parent); putInt(8, window); put(12, 0)
        })
        return null
    }

    private fun configureWindow(client: Client, state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 12) return buildError(state, BAD_LENGTH, 0, 12)
        val window = readU32(request, 4, state.byteOrder)
        val mask = readU16(request, 8, state.byteOrder)
        if (request.size != 12 + Integer.bitCount(mask) * 4) return buildError(state, BAD_LENGTH, 0, 12)
        if (!framebuffer.hasWindow(window) || window == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            return buildError(state, BAD_WINDOW, window, 12)
        }
        var x: Int? = null
        var y: Int? = null
        var width: Int? = null
        var height: Int? = null
        var offset = 12
        for (bit in 0 until 7) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = readU32(request, offset, state.byteOrder)
            when (bit) {
                0 -> x = value.toShort().toInt()
                1 -> y = value.toShort().toInt()
                2 -> width = value.coerceIn(1, 4096)
                3 -> height = value.coerceIn(1, 4096)
            }
            offset += 4
        }
        if (!framebuffer.configureWindow(window, x, y, width, height)) {
            return buildError(state, BAD_VALUE, window, 12)
        }
        val geometry = framebuffer.geometry(window) ?: return null
        val notify = event(state, 22)
        notify.putInt(4, window)
        notify.putInt(8, window)
        notify.putInt(12, 0) // above-sibling: None
        notify.putShort(16, geometry.x.toShort())
        notify.putShort(18, geometry.y.toShort())
        notify.putShort(20, geometry.width.toShort())
        notify.putShort(22, geometry.height.toShort())
        notify.putShort(24, 0) // border width
        notify.put(26, 0) // override-redirect
        notify.put(27, 0)
        notify.put(28, 0)
        notify.put(29, 0)
        notify.put(30, 0)
        notify.put(31, 0)
        notifySelected(client, state, window, STRUCTURE_NOTIFY, notify)
        return null
    }

    private fun createPixmap(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 16) return buildError(state, BAD_LENGTH, 0, 53)
        val id = readU32(request, 4, state.byteOrder)
        val drawable = readU32(request, 8, state.byteOrder)
        val width = readU16(request, 12, state.byteOrder)
        val height = readU16(request, 14, state.byteOrder)
        if (!framebuffer.hasDrawable(drawable)) return buildError(state, BAD_DRAWABLE, drawable, 53)
        if (!framebuffer.createPixmap(id, width, height, request[1].toInt() and 0xff)) {
            return buildError(state, BAD_ALLOC, id, 53)
        }
        return null
    }

    private fun freePixmap(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 54)
        val id = readU32(request, 4, state.byteOrder)
        if (!framebuffer.freePixmap(id)) return buildError(state, BAD_PIXMAP, id, 54)
        return null
    }

    private fun createGraphicsContext(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 16) return buildError(state, BAD_LENGTH, 0, 55)
        val id = readU32(request, 4, state.byteOrder)
        val drawable = readU32(request, 8, state.byteOrder)
        val mask = readU32(request, 12, state.byteOrder)
        if (request.size != 16 + Integer.bitCount(mask) * 4) return buildError(state, BAD_LENGTH, 0, 55)
        if (!framebuffer.hasDrawable(drawable)) return buildError(state, BAD_DRAWABLE, drawable, 55)
        var foreground = 0
        var background = 0x00ffffff
        var offset = 16
        for (bit in 0 until 32) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = readU32(request, offset, state.byteOrder)
            when (bit) {
                2 -> foreground = value
                3 -> background = value
            }
            offset += 4
        }
        if (!framebuffer.createGraphicsContext(id, foreground, background)) {
            return buildError(state, BAD_ID_CHOICE, id, 55)
        }
        return null
    }

    private fun changeGraphicsContext(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 12) return buildError(state, BAD_LENGTH, 0, 56)
        val id = readU32(request, 4, state.byteOrder)
        val mask = readU32(request, 8, state.byteOrder)
        if (request.size != 12 + Integer.bitCount(mask) * 4) return buildError(state, BAD_LENGTH, 0, 56)
        var foreground: Int? = null
        var background: Int? = null
        var offset = 12
        for (bit in 0 until 23) {
            if ((mask and (1 shl bit)) == 0) continue
            val value = readU32(request, offset, state.byteOrder)
            when (bit) {
                2 -> foreground = value
                3 -> background = value
            }
            offset += 4
        }
        if (!framebuffer.configureGraphicsContext(id, foreground, background)) {
            return buildError(state, BAD_GC, id, 56)
        }
        return null
    }

    private fun polyFillRectangle(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 12 || (request.size - 12) % 8 != 0) {
            return buildError(state, BAD_LENGTH, 0, 70)
        }
        val drawable = readU32(request, 4, state.byteOrder)
        val gc = readU32(request, 8, state.byteOrder)
        if (!framebuffer.hasDrawable(drawable)) return buildError(state, BAD_DRAWABLE, drawable, 70)
        val rectangles = ArrayList<IntArray>((request.size - 12) / 8)
        var offset = 12
        while (offset < request.size) {
            rectangles.add(
                intArrayOf(
                    readI16(request, offset, state.byteOrder),
                    readI16(request, offset + 2, state.byteOrder),
                    readU16(request, offset + 4, state.byteOrder),
                    readU16(request, offset + 6, state.byteOrder)
                )
            )
            offset += 8
        }
        if (!framebuffer.fillRectangles(drawable, gc, rectangles)) {
            return buildError(state, BAD_GC, gc, 70)
        }
        return null
    }

    private fun freeGraphicsContext(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 60)
        val id = readU32(request, 4, state.byteOrder)
        if (!framebuffer.freeGraphicsContext(id)) return buildError(state, BAD_GC, id, 60)
        return null
    }

    private fun clearArea(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 16) return buildError(state, BAD_LENGTH, 0, 61)
        val window = readU32(request, 4, state.byteOrder)
        val x = readI16(request, 8, state.byteOrder)
        val y = readI16(request, 10, state.byteOrder)
        val width = readU16(request, 12, state.byteOrder)
        val height = readU16(request, 14, state.byteOrder)
        if (!framebuffer.clearArea(window, x, y, width, height)) return buildError(state, BAD_WINDOW, window, 61)
        return null
    }

    private fun copyArea(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 28) return buildError(state, BAD_LENGTH, 0, 62)
        val source = readU32(request, 4, state.byteOrder)
        val destination = readU32(request, 8, state.byteOrder)
        val gc = readU32(request, 12, state.byteOrder)
        if (!framebuffer.hasDrawable(source)) return buildError(state, BAD_DRAWABLE, source, 62)
        if (!framebuffer.hasDrawable(destination)) return buildError(state, BAD_DRAWABLE, destination, 62)
        if (!framebuffer.copyArea(
                source, destination, gc,
                readI16(request, 16, state.byteOrder), readI16(request, 18, state.byteOrder),
                readI16(request, 20, state.byteOrder), readI16(request, 22, state.byteOrder),
                readU16(request, 24, state.byteOrder), readU16(request, 26, state.byteOrder)
            )
        ) return buildError(state, BAD_GC, gc, 62)
        return null
    }

    private fun putImage(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 24) return buildError(state, BAD_LENGTH, 0, 72)
        val format = request[1].toInt() and 0xff
        val drawable = readU32(request, 4, state.byteOrder)
        val gc = readU32(request, 8, state.byteOrder)
        val width = readU16(request, 12, state.byteOrder)
        val height = readU16(request, 14, state.byteOrder)
        val destinationX = readI16(request, 16, state.byteOrder)
        val destinationY = readI16(request, 18, state.byteOrder)
        val leftPad = request[20].toInt() and 0xff
        val depth = request[21].toInt() and 0xff
        if (leftPad != 0) return buildError(state, BAD_MATCH, leftPad, 72)
        if (!framebuffer.hasDrawable(drawable)) return buildError(state, BAD_DRAWABLE, drawable, 72)
        if (!framebuffer.putImage(
                drawable, gc, format, width, height, destinationX, destinationY,
                depth, request.copyOfRange(24, request.size), state.byteOrder
            )
        ) return buildError(state, BAD_MATCH, drawable, 72)
        return null
    }

    private fun getImage(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 20) return buildError(state, BAD_LENGTH, 0, 73)
        val format = request[1].toInt() and 0xff
        val drawable = readU32(request, 4, state.byteOrder)
        if (format != Z_PIXMAP) return buildError(state, BAD_MATCH, format, 73)
        if (!framebuffer.hasDrawable(drawable)) return buildError(state, BAD_DRAWABLE, drawable, 73)
        val width = readU16(request, 12, state.byteOrder)
        val height = readU16(request, 14, state.byteOrder)
        val image = framebuffer.getImage(
            drawable,
            readI16(request, 8, state.byteOrder),
            readI16(request, 10, state.byteOrder),
            width,
            height,
            state.byteOrder
        ) ?: return buildError(state, BAD_MATCH, drawable, 73)
        val output = replyBuffer(
            state.byteOrder,
            32 + image.size,
            1,
            24,
            state.sequence,
            image.size / 4
        )
        output.putInt(PcglX11SetupCodec.ROOT_VISUAL_ID)
        repeat(20) { output.put(0) }
        output.put(image)
        return output.array()
    }

    private fun changeProperty(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size < 24) return buildError(state, BAD_LENGTH, 0, 18)
        val mode = request[1].toInt() and 0xff
        val window = readU32(request, 4, state.byteOrder)
        val propertyAtom = readU32(request, 8, state.byteOrder)
        val typeAtom = readU32(request, 12, state.byteOrder)
        val format = request[16].toInt() and 0xff
        val itemCount = readU32(request, 20, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 18)
        if (propertyAtom == 0 || typeAtom == 0) {
            return buildError(state, BAD_ATOM, if (propertyAtom == 0) propertyAtom else typeAtom, 18)
        }
        if (mode !in 0..2 || format !in setOf(8, 16, 32) || itemCount < 0) {
            return buildError(state, BAD_VALUE, itemCount, 18)
        }
        val bytesPerItem = format / 8
        val dataLength = itemCount.toLong() * bytesPerItem
        if (dataLength > MAX_PROPERTY_BYTES ||
            24L + PcglX11SetupCodec.align4(dataLength.toInt()) != request.size.toLong()
        ) {
            return buildError(state, BAD_LENGTH, 0, 18)
        }
        val newValue = request.copyOfRange(24, 24 + dataLength.toInt())
        when (propertyStore.change(window, propertyAtom, typeAtom, format, mode, newValue, MAX_PROPERTY_BYTES, state.byteOrder)) {
            PcglX11PropertyStore.ChangeResult.SUCCESS -> {
                notifyPropertyChange(window, propertyAtom, PROPERTY_NEW_VALUE)
                return null
            }
            PcglX11PropertyStore.ChangeResult.TYPE_MISMATCH,
            PcglX11PropertyStore.ChangeResult.FORMAT_MISMATCH ->
                return buildError(state, BAD_MATCH, propertyAtom, 18)
            PcglX11PropertyStore.ChangeResult.TOO_LARGE ->
                return buildError(state, BAD_ALLOC, propertyAtom, 18)
        }
    }

    private fun deleteProperty(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 12) return buildError(state, BAD_LENGTH, 0, 19)
        val window = readU32(request, 4, state.byteOrder)
        val propertyAtom = readU32(request, 8, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 19)
        if (propertyAtom == 0) return buildError(state, BAD_ATOM, propertyAtom, 19)
        if (propertyStore.remove(window, propertyAtom)) {
            notifyPropertyChange(window, propertyAtom, PROPERTY_DELETE)
        }
        return null
    }

    private fun getProperty(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 24) return buildError(state, BAD_LENGTH, 0, 20)
        val delete = request[1].toInt() != 0
        val window = readU32(request, 4, state.byteOrder)
        val propertyAtom = readU32(request, 8, state.byteOrder)
        val requestedType = readU32(request, 12, state.byteOrder)
        val longOffset = readU32(request, 16, state.byteOrder)
        val longLength = readU32(request, 20, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 20)
        if (propertyAtom == 0) return buildError(state, BAD_ATOM, propertyAtom, 20)
        if (longOffset < 0 || longLength < 0) return buildError(state, BAD_VALUE, longOffset, 20)

        val property = propertyStore.get(window, propertyAtom, state.byteOrder)
        if (property == null || (requestedType != 0 && requestedType != property.type)) {
            val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
            output.putInt(0)
            repeat(24) { output.put(0) }
            return output.array()
        }

        val offsetBytes = longOffset.toLong() * 4L
        if (offsetBytes > property.value.size) return buildError(state, BAD_VALUE, longOffset, 20)
        val start = offsetBytes.toInt()
        val requestedBytes = longLength.toLong() * 4L
        val end = (start.toLong() + requestedBytes).coerceAtMost(property.value.size.toLong()).toInt()
        val value = property.value.copyOfRange(start, end)
        val bytesAfter = property.value.size - end
        val paddedLength = PcglX11SetupCodec.align4(value.size)
        val output = replyBuffer(state.byteOrder, 32 + paddedLength, 1, property.format, state.sequence, paddedLength / 4)
        output.putInt(property.type)
        output.putInt(bytesAfter)
        val itemCount = value.size / (property.format / 8)
        output.putInt(itemCount)
        repeat(12) { output.put(0) }
        output.put(value)
        repeat(paddedLength - value.size) { output.put(0) }
        if (delete && bytesAfter == 0 && propertyStore.remove(window, propertyAtom)) {
            notifyPropertyChange(client, state, window, propertyAtom, PROPERTY_DELETE)
        }
        return output.array()
    }

    private fun listProperties(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 21)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 21)
        val atoms = propertyStore.atomIds(window)
        val output = replyBuffer(state.byteOrder, 32 + atoms.size * 4, 1, 0, state.sequence, atoms.size)
        output.putShort(atoms.size.toShort())
        repeat(22) { output.put(0) }
        atoms.forEach { output.putInt(it) }
        return output.array()
    }

    private fun notifyPropertyChange(window: Int, atom: Int, propertyState: Int) {
        val recipients = transport.snapshotClients()
        recipients.forEach { recipient ->
            val recipientState = recipient.getTag() as? ClientState ?: return@forEach
            if (((recipientState.eventMasks[window] ?: 0) and PROPERTY_CHANGE) == 0) {
                return@forEach
            }
            val notification = event(recipientState, 28)
            notification.putInt(4, window)
            notification.putInt(8, atom)
            notification.putInt(12, SystemClock.uptimeMillis().toInt())
            notification.put(16, propertyState.toByte())
            try {
                writeResponse(recipient, notification.array())
            } catch (_: IOException) {
                transport.disconnect(recipient)
            }
        }
    }

    private fun setInputFocus(state: ClientState, request: ByteArray): ByteArray? {
        if (request.size != 12) return buildError(state, BAD_LENGTH, 0, 42)
        val revertTo = request[1].toInt() and 0xff
        val focus = readU32(request, 4, state.byteOrder)
        if (revertTo !in 0..2) return buildError(state, BAD_VALUE, revertTo, 42)
        if (focus != 0 && focus != 1 && !framebuffer.hasWindow(focus)) {
            return buildError(state, BAD_WINDOW, focus, 42)
        }
        state.focusedWindow = if (focus == 0 || focus == 1) PcglX11SetupCodec.ROOT_WINDOW_ID else focus
        state.revertTo = revertTo
        return null
    }

    private fun getInputFocus(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 4) return buildError(state, BAD_LENGTH, 0, 43)
        val output = replyBuffer(state.byteOrder, 32, 1, 1, state.sequence, 0)
        output.putInt(state.focusedWindow)
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun queryExtension(state: ClientState, request: ByteArray): ByteArray {
        if (request.size < 8) return buildError(state, BAD_LENGTH, 0, 98)
        val nameLength = readU16(request, 4, state.byteOrder)
        val expectedLength = 8 + PcglX11SetupCodec.align4(nameLength)
        if (request.size != expectedLength) return buildError(state, BAD_LENGTH, nameLength, 98)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        repeat(4) { output.put(0) }
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun listExtensions(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 4) return buildError(state, BAD_LENGTH, 0, 99)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.put(0)
        repeat(23) { output.put(0) }
        return output.array()
    }

    private fun getGeometry(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 14)
        val drawable = readU32(request, 4, state.byteOrder)
        val geometry = framebuffer.geometry(drawable) ?: return buildError(state, BAD_DRAWABLE, drawable, 14)
        val output = replyBuffer(state.byteOrder, 32, 1, geometry.depth, state.sequence, 0)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        output.putShort(geometry.x.toShort())
        output.putShort(geometry.y.toShort())
        output.putShort(geometry.width.toShort())
        output.putShort(geometry.height.toShort())
        output.putShort(0)
        repeat(10) { output.put(0) }
        return output.array()
    }

    private fun queryTree(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 15)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 15)
        val children = framebuffer.children(window)
        val parent = if (window == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            0
        } else {
            framebuffer.parentOf(window) ?: PcglX11SetupCodec.ROOT_WINDOW_ID
        }
        val output = replyBuffer(state.byteOrder, 32 + children.size * 4, 1, 0, state.sequence, children.size)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        output.putInt(parent)
        output.putShort(children.size.toShort())
        repeat(14) { output.put(0) }
        children.forEach { output.putInt(it) }
        return output.array()
    }

    private fun getWindowAttributes(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 3)
        val window = readU32(request, 4, state.byteOrder)
        if (!framebuffer.hasWindow(window)) return buildError(state, BAD_WINDOW, window, 3)
        val output = replyBuffer(state.byteOrder, 44, 1, 0, state.sequence, 3)
        output.putInt(PcglX11SetupCodec.ROOT_VISUAL_ID)
        output.putShort(1)
        output.put(0)
        output.put(0)
        output.putInt(-1)
        output.putInt(0)
        output.put(0)
        output.put(0)
        output.put(if (framebuffer.isMapped(window)) 2 else 0)
        output.put(0)
        output.putInt(PcglX11SetupCodec.ROOT_COLORMAP_ID)
        output.putInt(0)
        output.putInt(state.eventMasks[window] ?: 0)
        output.putShort(0)
        output.putShort(0)
        return output.array()
    }

    private fun internAtom(state: ClientState, request: ByteArray): ByteArray {
        if (request.size < 8) return buildError(state, BAD_LENGTH, 0, 16)
        val nameLength = readU16(request, 4, state.byteOrder)
        val expectedLength = 8 + PcglX11SetupCodec.align4(nameLength)
        if (request.size != expectedLength) return buildError(state, BAD_LENGTH, nameLength, 16)
        val name = String(request, 8, nameLength, Charsets.ISO_8859_1)
        val onlyIfExists = request[1].toInt() != 0
        val atom = atomRegistry.intern(name, onlyIfExists)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.putInt(atom)
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun getAtomName(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, BAD_LENGTH, 0, 17)
        val atom = readU32(request, 4, state.byteOrder)
        val name = atomRegistry.name(atom) ?: return buildError(state, BAD_ATOM, atom, 17)
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        val paddedLength = PcglX11SetupCodec.align4(nameBytes.size)
        val output = replyBuffer(state.byteOrder, 32 + paddedLength, 1, 0, state.sequence, paddedLength / 4)
        output.putShort(nameBytes.size.toShort())
        repeat(22) { output.put(0) }
        output.put(nameBytes)
        repeat(paddedLength - nameBytes.size) { output.put(0) }
        return output.array()
    }

    private fun notifySelected(
        client: Client,
        state: ClientState,
        selectedWindow: Int,
        mask: Int,
        event: ByteBuffer
    ) {
        if (((state.eventMasks[selectedWindow] ?: 0) and mask) != 0) {
            writeResponse(client, event.array())
        }
    }

    private fun event(state: ClientState, type: Int): ByteBuffer =
        ByteBuffer.allocate(32).order(state.byteOrder).apply {
            put(type.toByte())
            put(0)
            putShort(state.sequence.toShort())
        }

    private fun buildError(state: ClientState, errorCode: Int, badValue: Int, majorOpcode: Int): ByteArray {
        val output = ByteBuffer.allocate(32).order(state.byteOrder)
        output.put(0)
        output.put(errorCode.toByte())
        output.putShort(state.sequence.toShort())
        output.putInt(badValue)
        output.putShort(0)
        output.put(majorOpcode.toByte())
        repeat(21) { output.put(0) }
        return output.array()
    }

    private fun replyBuffer(
        byteOrder: ByteOrder,
        size: Int,
        responseType: Int,
        detail: Int,
        sequence: Int,
        lengthWords: Int
    ): ByteBuffer {
        val output = ByteBuffer.allocate(size).order(byteOrder)
        output.put(responseType.toByte())
        output.put(detail.toByte())
        output.putShort(sequence.toShort())
        output.putInt(lengthWords)
        return output
    }

    private fun writeResponse(client: Client, response: ByteArray) {
        val output = client.getOutputStream() ?: throw IOException("X11 client output stream is unavailable.")
        output.write(response)
        output.lock().close()
    }

    private fun rejectClient(client: Client, state: ClientState, reason: String) {
        runCatching {
            if (state.setupComplete) writeResponse(client, buildError(state, BAD_LENGTH, 0, 0))
            else writeResponse(client, PcglX11SetupCodec.buildFailure(state.byteOrder, reason))
        }
        transport.disconnect(client)
    }

    private fun consumePending(state: ClientState, count: Int) {
        state.pending = if (count >= state.pending.size) ByteArray(0) else state.pending.copyOfRange(count, state.pending.size)
    }

    private fun readU16(bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Int =
        PcglX11SetupCodec.readU16(bytes, offset, byteOrder)

    private fun readI16(bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Int =
        readU16(bytes, offset, byteOrder).toShort().toInt()

    private fun readU32(bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(byteOrder).int

    private companion object {
        const val Z_PIXMAP = 2
        const val MAX_BUFFERED_BYTES = 1024 * 1024
        const val MAX_REQUEST_BYTES = 262140
        const val MAX_PROPERTY_BYTES = 1024 * 1024
        const val BAD_REQUEST = 1
        const val BAD_VALUE = 2
        const val BAD_WINDOW = 3
        const val BAD_PIXMAP = 4
        const val BAD_ATOM = 5
        const val BAD_MATCH = 8
        const val BAD_DRAWABLE = 9
        const val BAD_ALLOC = 11
        const val BAD_GC = 13
        const val BAD_ID_CHOICE = 14
        const val BAD_LENGTH = 16
        const val BAD_IMPLEMENTATION = 17
        const val STRUCTURE_NOTIFY = 1 shl 17
        const val SUBSTRUCTURE_NOTIFY = 1 shl 19
        const val EXPOSURE = 1 shl 15
        const val PROPERTY_CHANGE = 1 shl 22
        const val PROPERTY_NEW_VALUE = 0
        const val PROPERTY_DELETE = 1
    }
}
