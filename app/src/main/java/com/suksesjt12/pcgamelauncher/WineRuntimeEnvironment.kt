package com.suksesjt12.pcgamelauncher

import android.content.Context
import java.io.File

/**
 * Builds the Android/Bionic environment expected by the Winlator Mali rootfs.
 *
 * This does not start XServer, PulseAudio, or a Vulkan wrapper. Those are
 * separate environment components and DISPLAY is only a target endpoint.
 */
object WineRuntimeEnvironment {
    data class Validation(
        val imageFs: File,
        val missingPaths: List<String>
    ) {
        val isReady: Boolean get() = missingPaths.isEmpty()
    }

    fun validate(context: Context, runtime: RuntimePackageManager.RuntimeInfo): Validation {
        val imageFs = runtime.root.parentFile?.parentFile?.canonicalFile
            ?: error("Lokasi root filesystem runtime tidak valid.")
        val expected = File(context.filesDir, "imagefs").canonicalFile
        if (imageFs != expected) {
            error("Runtime Wine tidak berada di dalam root filesystem aplikasi yang terverifikasi.")
        }

        val required = listOf(
            "usr",
            "usr/bin",
            "usr/lib",
            "usr/share",
            "usr/etc",
            "usr/etc/fonts",
            "usr/etc/xdg",
            "home"
        )
        val missing = required
            .filter { !File(imageFs, it).isDirectory }
            .map { "imagefs/$it" }
        return Validation(imageFs, missing)
    }

    fun apply(
        context: Context,
        runtime: RuntimePackageManager.RuntimeInfo,
        prefix: File,
        environment: MutableMap<String, String>,
        audioMode: String = "default",
        pulseLatencyMs: Int = 60,
        displayEndpoint: String? = null,
        xAuthorityFile: File? = null
    ) {
        val validation = validate(context, runtime)
        if (!validation.isReady) {
            error("Root filesystem belum lengkap: ${validation.missingPaths.joinToString()}")
        }

        val imageFs = validation.imageFs
        val usr = File(imageFs, "usr")
        val usrLib = File(usr, "lib")
        val usrBin = File(usr, "bin")
        val wineBin = File(runtime.root, "bin")
        val home = File(imageFs, "home/xuser")
        val tmp = File(usr, "tmp")
        val cache = File(usr, "var/cache")
        listOf(home, tmp, cache).forEach {
            if (!it.exists() && !it.mkdirs()) {
                error("Tidak dapat membuat direktori runtime: ${it.absolutePath}")
            }
        }

        val systemLibDir = if (File("/system/lib64").isDirectory) "/system/lib64" else "/system/lib"
        val existingPath = environment["PATH"].orEmpty()
        val existingLdPath = environment["LD_LIBRARY_PATH"].orEmpty()

        environment["HOME"] = home.absolutePath
        environment["USER"] = "xuser"
        environment["TMPDIR"] = tmp.absolutePath
        environment["XDG_DATA_DIRS"] = File(usr, "share").absolutePath
        environment["XDG_CONFIG_DIRS"] = File(usr, "etc/xdg").absolutePath
        environment["FONTCONFIG_PATH"] = File(usr, "etc/fonts").absolutePath
        environment["GST_PLUGIN_PATH"] = File(usrLib, "gstreamer-1.0").absolutePath
        environment["PREFIX"] = usr.absolutePath
        environment["PATH"] = listOf(wineBin.absolutePath, usrBin.absolutePath, existingPath)
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)

        val ldPaths = listOf(usrLib.absolutePath, systemLibDir, existingLdPath)
            .filter { it.isNotBlank() }
            .distinct()
        environment["LD_LIBRARY_PATH"] = ldPaths.joinToString(File.pathSeparator)

        environment["VK_LAYER_PATH"] = listOf(
            File(usr, "share/vulkan/implicit_layer.d").absolutePath,
            File(usr, "share/vulkan/explicit_layer.d").absolutePath
        ).joinToString(File.pathSeparator)
        environment["WRAPPER_LAYER_PATH"] = usrLib.absolutePath
        environment["WRAPPER_CACHE_PATH"] = cache.absolutePath
        environment["ALSA_CONFIG_PATH"] = listOf(
            File(usr, "share/alsa/alsa.conf").absolutePath,
            File(usr, "etc/alsa/conf.d/android_aserver.conf").absolutePath
        ).joinToString(File.pathSeparator)
        environment["ALSA_PLUGIN_DIR"] = File(usrLib, "alsa-lib").absolutePath
        environment["OPENSSL_CONF"] = File(usr, "etc/tls/openssl.cnf").absolutePath
        environment["SSL_CERT_FILE"] = File(usr, "etc/tls/cert.pem").absolutePath
        environment["SSL_CERT_DIR"] = File(usr, "etc/tls/certs").absolutePath

        // Never advertise a dummy X display. The caller may set DISPLAY only
        // after a real PCGL-owned X11 server has completed its startup handshake.
        val validatedDisplay = displayEndpoint?.trim()?.takeIf {
            it.matches(Regex("^(?::[0-9]+|localhost:[0-9]+|127\\.0\\.0\\.1:[0-9]+)(\\.[0-9]+)?$"))
        }
        if (validatedDisplay != null) {
            val isLoopbackTcp = validatedDisplay.startsWith("127.0.0.1:")
            val authority = xAuthorityFile?.canonicalFile
            if (isLoopbackTcp) {
                require(authority != null && authority.isFile) {
                    "Authenticated X11 TCP display requires a valid XAUTHORITY file."
                }
            }
            environment["DISPLAY"] = validatedDisplay
            if (authority != null && authority.isFile) {
                environment["XAUTHORITY"] = authority.absolutePath
            } else {
                environment.remove("XAUTHORITY")
            }
            // Do not force GLX: the current PCGL server is core X11/software only.
            environment.remove("WINE_X11FORCEGLX")
            environment.remove("BOX64_X11GLX")
        } else {
            environment.remove("DISPLAY")
            environment.remove("XAUTHORITY")
            environment.remove("WINE_X11FORCEGLX")
            environment.remove("BOX64_X11GLX")
        }
        environment["WINE_NO_DUPLICATE_EXPLORER"] = "1"
        environment["WINE_DISABLE_FULLSCREEN_HACK"] = "1"
        environment["WINE_GST_NO_GL"] = "1"
        environment["GST_PLUGIN_FEATURE_RANK"] = "ximagesink:3000"
        environment["PROTON_AUDIO_CONVERT"] = "0"
        environment["PROTON_VIDEO_CONVERT"] = "0"
        environment["PROTON_DEMUX"] = "0"

        environment["BOX64_NOBANNER"] = "1"
        environment["BOX64_DYNAREC"] = "1"
        environment["BOX64_NORCFILES"] = "1"
        if (NativeBridge.device().contains("Mali", ignoreCase = true)) {
            environment["BOX64_MMAP32"] = "0"
        }

        if (runtime.libraryPaths.isNotEmpty()) {
            environment["BOX64_LD_LIBRARY_PATH"] =
                runtime.libraryPaths.joinToString(File.pathSeparator) { it.absolutePath }
        }

        if (audioMode == "pulseaudio") {
            environment["PULSE_LATENCY_MSEC"] = pulseLatencyMs.coerceIn(20, 250).toString()
        } else {
            environment.remove("PULSE_LATENCY_MSEC")
        }

        // WINEPREFIX is intentionally set by the caller to the selected game container.
        environment["WINEPREFIX"] = prefix.absolutePath
    }
}
