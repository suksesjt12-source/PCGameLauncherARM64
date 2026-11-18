package com.suksesjt12.pcgamelauncher

import android.view.Surface

/** Rendering boundary owned by PCGL. X11 drawable buffers can be submitted as RGBA frames. */
interface PcglDisplayBackend {
    data class AttachResult(val ready: Boolean, val details: String)

    fun attach(surface: Surface, width: Int, height: Int): AttachResult
    fun resize(width: Int, height: Int): Boolean
    fun submitWindowFrame(frame: PcglWindowFrame): Boolean
    fun clearWindowFrame(): Boolean
    fun renderFrame(frameIndex: Long): Boolean
    fun details(): String
    fun detach()
}

/** Native Android Surface + EGL/OpenGL ES implementation owned by PCGL. */
class PcglEglSurfaceBackend : PcglDisplayBackend {
    override fun attach(surface: Surface, width: Int, height: Int): PcglDisplayBackend.AttachResult {
        return try {
            val message = NativeBridge.createDisplaySurface(surface, width, height)
            PcglDisplayBackend.AttachResult(message.startsWith("READY:"), message)
        } catch (error: Throwable) {
            PcglDisplayBackend.AttachResult(
                false,
                "ERROR: " + (error.message ?: error.javaClass.simpleName)
            )
        }
    }

    override fun resize(width: Int, height: Int): Boolean =
        runCatching { NativeBridge.resizeDisplaySurface(width, height) }.getOrDefault(false)

    override fun submitWindowFrame(frame: PcglWindowFrame): Boolean =
        runCatching {
            NativeBridge.submitWindowFrame(frame.rgbaPixels, frame.width, frame.height)
        }.getOrDefault(false)

    override fun clearWindowFrame(): Boolean =
        runCatching { NativeBridge.clearWindowFrame() }.getOrDefault(false)

    override fun renderFrame(frameIndex: Long): Boolean =
        runCatching { NativeBridge.renderDisplayFrame(frameIndex) }.getOrDefault(false)

    override fun details(): String =
        runCatching { NativeBridge.displayInfo() }.getOrDefault("Native display bridge unavailable.")

    override fun detach() {
        runCatching { NativeBridge.destroyDisplaySurface() }
    }
}
