package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.x11.transport.Client
import com.suksesjt12.pcgamelauncher.x11.transport.ConnectionHandler
import com.suksesjt12.pcgamelauncher.x11.transport.RequestHandler
import com.suksesjt12.pcgamelauncher.x11.transport.TcpSocketConfig
import com.suksesjt12.pcgamelauncher.x11.transport.UnixSocketConfig
import com.suksesjt12.pcgamelauncher.x11.transport.XConnectorEpoll
import java.io.File
import java.io.IOException

/** PCGL-owned AF_UNIX or authenticated loopback TCP X11 transport. */
internal class PcglX11Transport(
    runtimeRoot: File,
    private val dispatcher: ProtocolDispatcher,
    relativeSocketPath: String = UnixSocketConfig.XSERVER_RELATIVE_PATH,
    tcpPort: Int? = null
) : AutoCloseable {
    interface ProtocolDispatcher {
        fun onClientConnected(client: Client)
        fun onClientDisconnected(client: Client)
        @Throws(IOException::class)
        fun handleRequest(client: Client): Boolean
    }

    data class Endpoint(
        val socketPath: String,
        val displayEndpoint: String,
        val transportStarted: Boolean
    )

    private val tcpConfig = tcpPort?.let(::TcpSocketConfig)
    private val socketConfig = if (tcpConfig == null) {
        UnixSocketConfig.createSocket(runtimeRoot.canonicalPath, relativeSocketPath)
    } else {
        null
    }

    @Volatile
    private var connector: XConnectorEpoll? = null

    private fun endpoint() = Endpoint(
        socketPath = socketConfig?.path.orEmpty(),
        displayEndpoint = tcpConfig?.displayEndpoint.orEmpty(),
        transportStarted = true
    )

    @Synchronized
    @Throws(IOException::class)
    fun start(): Endpoint {
        connector?.let { return endpoint() }
        val next = try {
            val onConnection = object : ConnectionHandler {
                override fun handleNewConnection(client: Client) =
                    dispatcher.onClientConnected(client)

                override fun handleConnectionShutdown(client: Client) =
                    dispatcher.onClientDisconnected(client)
            }
            val onRequest = object : RequestHandler {
                override fun handleRequest(client: Client): Boolean =
                    dispatcher.handleRequest(client)
            }
            if (tcpConfig != null) {
                XConnectorEpoll(tcpConfig, onConnection, onRequest)
            } else {
                XConnectorEpoll(socketConfig!!, onConnection, onRequest)
            }
        } catch (error: RuntimeException) {
            throw IOException("Unable to bind the PCGL X11 transport listener.", error)
        }

        try {
            next.start()
            connector = next
        } catch (error: RuntimeException) {
            runCatching { next.stop() }
            throw IOException("Unable to start the PCGL X11 transport loop.", error)
        }
        return endpoint()
    }

    /** Snapshot of live X11 clients; called by the protocol dispatcher on the epoll thread. */
    fun snapshotClients(): List<Client> =
        connector?.snapshotConnectedClients()?.toList() ?: emptyList()

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
