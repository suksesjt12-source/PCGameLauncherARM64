package com.suksesjt12.pcgamelauncher

import android.view.Surface

object NativeBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("launcher_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun runtimeStatus(): String
    external fun deviceInfo(): String
    external fun createDisplaySurface(surface: Surface, width: Int, height: Int): String
    external fun resizeDisplaySurface(width: Int, height: Int): Boolean
    external fun renderDisplayFrame(frameIndex: Long): Boolean
    external fun destroyDisplaySurface()
    external fun displayInfo(): String

    fun status(): String =
        if (loaded) runtimeStatus() else "Native status bridge unavailable"

    fun device(): String =
        if (loaded) deviceInfo() else "GPU probe unavailable: native bridge not loaded"

    fun isLoaded(): Boolean = loaded
}
