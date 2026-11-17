package com.suksesjt12.pcgamelauncher

object NativeBridge {
    private val loaded: Boolean = try {
        System.loadLibrary("launcher_native")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun runtimeStatus(): String

    fun status(): String = if (loaded) runtimeStatus() else "Native status bridge unavailable"
}
