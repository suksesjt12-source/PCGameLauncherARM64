package com.suksesjt12.pcgamelauncher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Creates a per-game workspace and configuration record.
 * This is not a Wine prefix until wineboot initializes it.
 */
object GameContainerManager {
    data class Profile(val id: String, val directory: File, val profileFile: File)

    fun create(context: Context, gameName: String, gameUri: String): Profile {
        require(gameName.isNotBlank()) { "Nama game kosong." }
        require(gameUri.isNotBlank()) { "URI game kosong." }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(gameUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val id = digest.take(20)

        val directory = File(File(context.filesDir, "pcgl/containers"), id)
        val requiredDirectories = listOf(
            "drive_c",
            "dosdevices",
            "temp",
            "logs",
            "shader-cache"
        )

        if (!directory.exists() && !directory.mkdirs()) {
            error("Tidak dapat membuat direktori container.")
        }
        for (child in requiredDirectories) {
            val dir = File(directory, child)
            if (!dir.exists() && !dir.mkdirs()) {
                error("Tidak dapat membuat direktori $child.")
            }
        }

        val profileFile = File(directory, "profile.json")
        val profile = JSONObject()
            .put("schemaVersion", 1)
            .put("containerId", id)
            .put("gameName", gameName)
            .put("gameUri", gameUri)
            .put("architecture", "win64")
            .put("box64Preset", "compatibility")
            .put("graphicsBackend", "auto")
            .put("resolution", "960x540")
            .put("createdAt", System.currentTimeMillis())
            .put("state", "workspace_only_not_a_wine_prefix")

        profileFile.writeText(profile.toString(2), Charsets.UTF_8)
        return Profile(id, directory, profileFile)
    }
}
