package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.x11.transport.Client
import com.suksesjt12.pcgamelauncher.x11.transport.ConnectionHandler
import com.suksesjt12.pcgamelauncher.x11.transport.RequestHandler
import com.suksesjt12.pcgamelauncher.x11.transport.UnixSocketConfig
import com.suksesjt12.pcgamelauncher.x11.transport.XConnectorEpoll
import java.io.File
import java.io.IOException

/** PCGL-owned AF_UNIX/epoll transport; it does not advertise DISPLAY by itself. */
internal class PcglX11Transport(
    runtimeRoot: File,
    private val dispatcher: ProtocolDispatcher,
    relativeSocketPath: String = UnixSocketConfig.XSERVER_RELATIVE_PATH
) : AutoCloseable {
    interface ProtocolDispatcher {
        fun onClientConnected(client: Client)
        fun onClientDisconnected(client: Client)
        @Throws(IOException::class)
        fun handleRequest(client: Client): Boolean
    }

    data class Endpoint(val socketPath: String, val transportStarted: Boolean)

    private val socketConfig = UnixSocketConfig.createSocket(
        runtimeRoot.canonicalPath,
        relativeSocketPath
    )

    @Volatile
    private var connector: XConnectorEpoll? = null

    @Synchronized
    @Throws(IOException::class)
    fun start(): Endpoint {
        connector?.let { return Endpoint(socketConfig.path, true) }
        val next = try {
            XConnectorEpoll(
                socketConfig,
                object : ConnectionHandler {
                    override fun handleNewConnection(client: Client) =
                        dispatcher.onClientConnected(client)

                    override fun handleConnectionShutdown(client: Client) =
                        dispatcher.onClientDisconnected(client)
                },
                object : RequestHandler {
                    override fun handleRequest(client: Client): Boolean =
                        dispatcher.handleRequest(client)
                }
            )
        } catch (error: RuntimeException) {
            throw IOException("Unable to bind the PCGL X11 transport socket.", error)
        }

        try {
            next.start()
            connector = next
        } catch (error: RuntimeException) {
            runCatching { next.stop() }
            throw IOException("Unable to start the PCGL X11 transport loop.", error)
        }
        return Endpoint(socketConfig.path, true)
    }

    /** Called on the epoll thread; deliberately does not take the lifecycle monitor. */
    fun disconnect(client: Client) {
        connector?.disconnectClient(client)
    }

    fun stop() {
        val active = synchronized(this) {
            val current = connector
            connector = null
            current
        }
        active?.stop()
    }

    override fun close() = stop()
}
