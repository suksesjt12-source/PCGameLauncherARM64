package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.x11.transport.Client
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PCGL-owned X11 protocol bootstrap. It implements connection setup
 * and a small set of read-only core requests. It is not a complete X server.
 */
class PcglX11ProtocolServer(
    runtimeRoot: File,
    private val screenWidthPx: Int = 1280,
    private val screenHeightPx: Int = 720
) : AutoCloseable {
    data class Endpoint(val socketPath: String, val protocolVersion: String = "11.0")

    private data class ClientState(
        var byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        var setupComplete: Boolean = false,
        var sequence: Int = 0,
        var pending: ByteArray = ByteArray(0),
        val atomsByName: MutableMap<String, Int> = HashMap(),
        val namesByAtom: MutableMap<Int, String> = HashMap(),
        var nextAtom: Int = 1
    )

    private lateinit var transport: PcglX11Transport

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
        transport = PcglX11Transport(runtimeRoot, dispatcher)
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(): Endpoint = Endpoint(transport.start().socketPath)

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
            when (val parsed = PcglX11SetupCodec.parseRequest(state.pending)) {
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
            val response = dispatchRequest(state, request)
            if (response != null) writeResponse(client, response)
            progressed = true
        }
        return progressed
    }

    private fun dispatchRequest(state: ClientState, request: ByteArray): ByteArray? {
        val opcode = request[0].toInt() and 0xff
        return when (opcode) {
            3 -> getWindowAttributes(state, request)
            14 -> getGeometry(state, request)
            15 -> queryTree(state, request)
            16 -> internAtom(state, request)
            17 -> getAtomName(state, request)
            43 -> getInputFocus(state, request)
            98 -> queryExtension(state, request)
            99 -> listExtensions(state, request)
            127 -> null
            else -> buildError(state, if (opcode <= 127) 17 else 1, 0, opcode)
        }
    }

    private fun getInputFocus(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 4) return buildError(state, 16, 0, 43)
        val output = replyBuffer(state.byteOrder, 32, 1, 1, state.sequence, 0)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun queryExtension(state: ClientState, request: ByteArray): ByteArray {
        if (request.size < 8) return buildError(state, 16, 0, 98)
        val nameLength = PcglX11SetupCodec.readU16(request, 4, state.byteOrder)
        val expectedLength = 8 + PcglX11SetupCodec.align4(nameLength)
        if (request.size != expectedLength) return buildError(state, 16, nameLength, 98)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.put(0)
        output.put(0)
        output.put(0)
        output.put(0)
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun listExtensions(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 4) return buildError(state, 16, 0, 99)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.put(0)
        repeat(23) { output.put(0) }
        return output.array()
    }

    private fun getGeometry(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, 16, 0, 14)
        val drawable = readU32(request, 4, state.byteOrder)
        if (drawable != PcglX11SetupCodec.ROOT_WINDOW_ID) return buildError(state, 9, drawable, 14)
        val output = replyBuffer(state.byteOrder, 32, 1, 24, state.sequence, 0)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        output.putShort(0)
        output.putShort(0)
        output.putShort(screenWidthPx.coerceIn(1, 65535).toShort())
        output.putShort(screenHeightPx.coerceIn(1, 65535).toShort())
        output.putShort(0)
        repeat(10) { output.put(0) }
        return output.array()
    }

    private fun queryTree(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, 16, 0, 15)
        val window = readU32(request, 4, state.byteOrder)
        if (window != PcglX11SetupCodec.ROOT_WINDOW_ID) return buildError(state, 3, window, 15)
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        output.putInt(PcglX11SetupCodec.ROOT_WINDOW_ID)
        output.putShort(0)
        repeat(14) { output.put(0) }
        return output.array()
    }

    private fun getWindowAttributes(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, 16, 0, 3)
        val window = readU32(request, 4, state.byteOrder)
        if (window != PcglX11SetupCodec.ROOT_WINDOW_ID) return buildError(state, 3, window, 3)
        val output = replyBuffer(state.byteOrder, 44, 1, 0, state.sequence, 3)
        output.putInt(PcglX11SetupCodec.ROOT_VISUAL_ID)
        output.putShort(1)
        output.put(0)
        output.put(0)
        output.putInt(-1)
        output.putInt(0)
        output.put(0)
        output.put(0)
        output.put(2)
        output.put(0)
        output.putInt(PcglX11SetupCodec.ROOT_COLORMAP_ID)
        output.putInt(0)
        output.putInt(0)
        output.putShort(0)
        output.putShort(0)
        return output.array()
    }

    private fun internAtom(state: ClientState, request: ByteArray): ByteArray {
        if (request.size < 8) return buildError(state, 16, 0, 16)
        val nameLength = PcglX11SetupCodec.readU16(request, 4, state.byteOrder)
        val expectedLength = 8 + PcglX11SetupCodec.align4(nameLength)
        if (request.size != expectedLength) return buildError(state, 16, nameLength, 16)
        val name = String(request, 8, nameLength, Charsets.ISO_8859_1)
        val onlyIfExists = request[1].toInt() != 0
        var atom = state.atomsByName[name]
        if (atom == null && !onlyIfExists) {
            atom = state.nextAtom++
            state.atomsByName[name] = atom
            state.namesByAtom[atom] = name
        }
        val output = replyBuffer(state.byteOrder, 32, 1, 0, state.sequence, 0)
        output.putInt(atom ?: 0)
        repeat(20) { output.put(0) }
        return output.array()
    }

    private fun getAtomName(state: ClientState, request: ByteArray): ByteArray {
        if (request.size != 8) return buildError(state, 16, 0, 17)
        val atom = readU32(request, 4, state.byteOrder)
        val name = state.namesByAtom[atom] ?: return buildError(state, 5, atom, 17)
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        val paddedLength = PcglX11SetupCodec.align4(nameBytes.size)
        val output = replyBuffer(state.byteOrder, 32 + paddedLength, 1, 0, state.sequence, paddedLength / 4)
        output.putShort(nameBytes.size.toShort())
        repeat(22) { output.put(0) }
        output.put(nameBytes)
        repeat(paddedLength - nameBytes.size) { output.put(0) }
        return output.array()
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
            if (state.setupComplete) writeResponse(client, buildError(state, 16, 0, 0))
            else writeResponse(client, PcglX11SetupCodec.buildFailure(state.byteOrder, reason))
        }
        transport.disconnect(client)
    }

    private fun consumePending(state: ClientState, count: Int) {
        state.pending = if (count >= state.pending.size) ByteArray(0) else state.pending.copyOfRange(count, state.pending.size)
    }

    private fun readU32(bytes: ByteArray, offset: Int, byteOrder: ByteOrder): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(byteOrder).int

    private companion object {
        const val MAX_BUFFERED_BYTES = 1024 * 1024
        const val MAX_REQUEST_BYTES = 262140
    }
}
