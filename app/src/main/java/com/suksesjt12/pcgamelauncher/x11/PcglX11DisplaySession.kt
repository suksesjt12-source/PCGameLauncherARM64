package com.suksesjt12.pcgamelauncher.x11

import android.content.Context
import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import java.io.File

/** Owns the authenticated X11 server lifecycle while an Android game Surface is attached. */
object PcglX11DisplaySession {
    data class Session(
        val displayEndpoint: String,
        val xAuthorityFile: File
    )

    private val lock = Any()
    @Volatile private var server: PcglX11ProtocolServer? = null
    @Volatile private var active: Session? = null

    fun currentSession(): Session? = active
    fun isRunning(): Boolean = server != null && active != null

    fun start(
        context: Context,
        prefix: File,
        width: Int,
        height: Int,
        frameSink: (PcglWindowFrame) -> Unit
    ): Session = synchronized(lock) {
        active?.let { return@synchronized it }

        val containersRoot = File(context.filesDir, "pcgl/containers").canonicalFile
        val safePrefix = prefix.canonicalFile
        require(safePrefix.path.startsWith(containersRoot.path + File.separator)) {
            "Xauthority workspace must remain inside PCGL game containers."
        }
        if (!safePrefix.exists() && !safePrefix.mkdirs()) {
            error("Unable to create the selected game Xauthority workspace.")
        }

        val imageFs = File(context.filesDir, "imagefs").canonicalFile
        // TCP display mode does not need an imagefs socket directory. Use the Wine root
        // when present, or the already validated per-game workspace for imported runtimes.
        val x11RuntimeRoot = if (imageFs.isDirectory) imageFs else safePrefix

        val nextServer = PcglX11ProtocolServer(
            runtimeRoot = x11RuntimeRoot,
            screenWidthPx = width.coerceIn(320, 1920),
            screenHeightPx = height.coerceIn(240, 1200),
            tcpPort = X11_TCP_PORT,
            frameSink = frameSink
        )
        try {
            val endpoint = nextServer.start()
            val display = endpoint.displayEndpoint
                ?: error("X11 server started without a TCP DISPLAY endpoint.")
            val authority = PcglXAuthority.write(safePrefix, endpoint)
            val session = Session(display, authority)
            server = nextServer
            active = session
            session
        } catch (error: Throwable) {
            runCatching { nextServer.close() }
            throw error
        }
    }

    fun stop() = synchronized(lock) {
        val oldServer = server
        val oldSession = active
        server = null
        active = null
        runCatching { oldServer?.close() }
        runCatching { oldSession?.xAuthorityFile?.delete() }
    }

    private const val X11_TCP_PORT = 6000
}
