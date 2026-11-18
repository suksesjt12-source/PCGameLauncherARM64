package com.suksesjt12.pcgamelauncher

import java.io.File

/** In-process handoff from the launcher to the full-screen game display Activity. */
object PcglPendingGameLaunch {
    data class Request(
        val gameName: String,
        val runtime: RuntimePackageManager.RuntimeInfo,
        val profile: GameContainerManager.Profile,
        val executable: File,
        val arguments: List<String>
    )

    @Volatile
    private var pending: Request? = null

    @Synchronized
    fun publish(request: Request) {
        check(pending == null) { "A game launch request is already waiting for the display Activity." }
        pending = request.copy(arguments = request.arguments.toList())
    }

    @Synchronized
    fun take(): Request? {
        val request = pending
        pending = null
        return request
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
