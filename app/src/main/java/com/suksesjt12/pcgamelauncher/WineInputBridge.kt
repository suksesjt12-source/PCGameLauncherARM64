package com.suksesjt12.pcgamelauncher

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/** Authenticated loopback transport from Android controls to the helper running in Wine. */
object WineInputBridge {
    data class Endpoint(val host: String, val port: Int, val token: String)

    private val lock = Any()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val pending = ArrayDeque<String>()

    fun start(): Endpoint {
        stop()
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        listener.soTimeout = 90_000
        val tokenBytes = ByteArray(24)
        SecureRandom().nextBytes(tokenBytes)
        val token = tokenBytes.joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            server = listener
            pending.clear()
        }
        Thread({
            var accepted: Socket? = null
            try {
                accepted = listener.accept()
                accepted.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(accepted.getInputStream(), Charsets.UTF_8))
                if (reader.readLine() != "AUTH $token") {
                    accepted.close()
                    return@Thread
                }
                val output = BufferedWriter(OutputStreamWriter(accepted.getOutputStream(), Charsets.UTF_8))
                synchronized(lock) {
                    if (server !== listener) {
                        accepted.close()
                        return@Thread
                    }
                    client = accepted
                    writer = output
                    output.write("OK\n")
                    output.flush()
                    while (pending.isNotEmpty()) {
                        output.write(pending.removeFirst())
                        output.write("\n")
                    }
                    output.flush()
                }
                accepted.soTimeout = 0
                while (reader.readLine() != null) {
                    // READY / keepalive records are informational only.
                }
            } catch (_: Exception) {
                // A dropped helper connection is handled as a disconnected controller.
            } finally {
                synchronized(lock) {
                    if (client === accepted) {
                        client = null
                        writer = null
                    }
                }
                try { accepted?.close() } catch (_: Exception) {}
            }
        }, "PCGL-Input-Accept").apply {
            isDaemon = true
            start()
        }
        return Endpoint("127.0.0.1", listener.localPort, token)
    }

    fun isConnected(): Boolean =
        client?.let { it.isConnected && !it.isClosed } == true && writer != null

    fun sendKey(virtualKey: Int, down: Boolean): Boolean {
        require(virtualKey in 1..255) { "Virtual-key code must be 1..255." }
        return send("K $virtualKey ${if (down) 1 else 0}")
    }

    fun sendMouseButton(button: Int, down: Boolean): Boolean {
        require(button in 1..3) { "Mouse button must be 1..3." }
        return send("B $button ${if (down) 1 else 0}")
    }

    fun sendMouseMove(deltaX: Int, deltaY: Int): Boolean =
        send("M ${deltaX.coerceIn(-4000, 4000)} ${deltaY.coerceIn(-4000, 4000)}")

    fun sendMouseWheel(delta: Int): Boolean = send("W ${delta.coerceIn(-1200, 1200)}")

    private fun send(line: String): Boolean = synchronized(lock) {
        val output = writer
        if (output == null) {
            if (pending.size >= 128) pending.removeFirst()
            pending.addLast(line)
            return@synchronized false
        }
        return@synchronized try {
            output.write(line)
            output.write("\n")
            output.flush()
            true
        } catch (_: Exception) {
            writer = null
            client = null
            false
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                writer?.write("QUIT\n")
                writer?.flush()
            } catch (_: Exception) {}
            try { client?.close() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
            writer = null
            client = null
            server = null
            pending.clear()
        }
    }
}
