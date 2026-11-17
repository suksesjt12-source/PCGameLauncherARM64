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
        val profile = try {
            if (profileFile.isFile) JSONObject(profileFile.readText(Charsets.UTF_8))
            else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        profile.put("schemaVersion", 1)
            .put("containerId", id)
            .put("gameName", gameName)
            .put("gameUri", gameUri)
            .put("architecture", "win64")
            .put("box64Preset", profile.optString("box64Preset", "compatibility"))
            .put("graphicsBackend", profile.optString("graphicsBackend", "auto"))
            .put("resolution", profile.optString("resolution", "960x540"))
        if (!profile.has("createdAt")) {
            profile.put("createdAt", System.currentTimeMillis())
        }
        if (!profile.has("state")) {
            profile.put("state", "workspace_only_not_a_wine_prefix")
        }

        profileFile.writeText(profile.toString(2), Charsets.UTF_8)
        return Profile(id, directory, profileFile)
    }

    fun setPrefixState(profile: Profile, state: String) {
        val json = JSONObject(profile.profileFile.readText(Charsets.UTF_8))
        json.put("state", state)
        json.put("lastUpdatedAt", System.currentTimeMillis())
        profile.profileFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    fun isPrefixInitialized(profile: Profile): Boolean {
        return try {
            JSONObject(profile.profileFile.readText(Charsets.UTF_8))
                .optString("state") == "wine_prefix_initialized"
        } catch (_: Exception) {
            false
        }
    }

    fun recordExecutable(profile: Profile, executable: File) {
        val json = JSONObject(profile.profileFile.readText(Charsets.UTF_8))
        val prefixRoot = profile.directory.canonicalFile
        val target = executable.canonicalFile
        require(target.path.startsWith(prefixRoot.path + File.separator)) {
            "Executable harus berada di dalam workspace game."
        }
        json.put("executablePath", target.absolutePath)
            .put("lastPreparedAt", System.currentTimeMillis())
        profile.profileFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    fun gameDataDirectory(profile: Profile): File {
        val driveC = File(profile.directory, "drive_c").canonicalFile
        val target = File(driveC, "PCGameLauncher/${profile.id}/game-data").canonicalFile
        require(target.path.startsWith(driveC.path + File.separator)) {
            "Direktori data game berada di luar drive_c."
        }
        return target
    }

    fun recordGameData(profile: Profile, directory: File, sourceUri: String, files: Int, directories: Int, bytes: Long) {
        val target = directory.canonicalFile
        val driveC = File(profile.directory, "drive_c").canonicalFile
        require(target.path.startsWith(driveC.path + File.separator) && target.isDirectory) {
            "Folder data game harus berada di dalam drive_c."
        }
        val json = JSONObject(profile.profileFile.readText(Charsets.UTF_8))
        json.put("gameDataPath", target.absolutePath)
            .put("gameDataUri", sourceUri)
            .put("gameDataFiles", files)
            .put("gameDataDirectories", directories)
            .put("gameDataBytes", bytes)
            .put("gameDataImportedAt", System.currentTimeMillis())
        profile.profileFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    fun hasGameData(profile: Profile): Boolean = gameDataDirectory(profile).isDirectory

    fun isDxvkEnabled(profile: Profile): Boolean {
        return try {
            JSONObject(profile.profileFile.readText(Charsets.UTF_8)).optBoolean("dxvkEnabled", false)
        } catch (_: Exception) {
            false
        }
    }

    fun setDxvkState(profile: Profile, enabled: Boolean, runtimeVersion: String? = null) {
        val json = JSONObject(profile.profileFile.readText(Charsets.UTF_8))
        json.put("dxvkInstalled", true).put("dxvkEnabled", enabled)
            .put("dxvkUpdatedAt", System.currentTimeMillis())
        if (runtimeVersion != null) json.put("dxvkRuntimeVersion", runtimeVersion)
        profile.profileFile.writeText(json.toString(2), Charsets.UTF_8)
    }

}
