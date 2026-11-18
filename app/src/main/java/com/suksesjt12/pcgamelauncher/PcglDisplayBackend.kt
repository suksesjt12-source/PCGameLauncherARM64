package com.suksesjt12.pcgamelauncher

import android.view.Surface

/**
 * Rendering boundary owned by PCGL. Future X11/Wine display integration will
 * feed window content into a backend rather than coupling Wine to an Activity.
 */
interface PcglDisplayBackend {
    data class AttachResult(val ready: Boolean, val details: String)

    fun attach(surface: Surface, width: Int, height: Int): AttachResult
    fun resize(width: Int, height: Int): Boolean
    fun renderFrame(frameIndex: Long): Boolean
    fun details(): String
    fun detach()
}

/**
 * Native Android Surface + EGL/OpenGL ES implementation.
 * This backend currently renders PCGL diagnostics; it does not implement X11.
 */
class PcglEglSurfaceBackend : PcglDisplayBackend {
    override fun attach(surface: Surface, width: Int, height: Int): PcglDisplayBackend.AttachResult {
        return try {
            val message = NativeBridge.createDisplaySurface(surface, width, height)
            PcglDisplayBackend.AttachResult(message.startsWith("READY:"), message)
        } catch (error: Throwable) {
            PcglDisplayBackend.AttachResult(
                false,
                "ERROR: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    override fun resize(width: Int, height: Int): Boolean =
        runCatching { NativeBridge.resizeDisplaySurface(width, height) }.getOrDefault(false)

    override fun renderFrame(frameIndex: Long): Boolean =
        runCatching { NativeBridge.renderDisplayFrame(frameIndex) }.getOrDefault(false)

    override fun details(): String =
        runCatching { NativeBridge.displayInfo() }.getOrDefault("Native display bridge unavailable.")

    override fun detach() {
        runCatching { NativeBridge.destroyDisplaySurface() }
    }
}
