package com.suksesjt12.pcgamelauncher.x11

import android.system.Os
import com.suksesjt12.pcgamelauncher.x11.PcglX11ProtocolServer.Endpoint
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/** Writes a private Xauthority file for an authenticated PCGL loopback X11 server. */
object PcglXAuthority {
    fun write(prefix: File, endpoint: Endpoint): File {
        val cookie = endpoint.authCookie ?: error("X11 endpoint does not provide a MIT-MAGIC-COOKIE-1 secret.")
        require(cookie.size == 16) { "MIT-MAGIC-COOKIE-1 cookies must contain exactly 16 bytes." }
        val display = endpoint.displayEndpoint ?: error("X11 TCP display endpoint is missing.")
        val match = Regex("^127\\.0\\.0\\.1:([0-9]+)(?:\\.[0-9]+)?$").matchEntire(display)
            ?: error("Only authenticated loopback X11 endpoints are supported.")
        val displayNumber = match.groupValues[1].toByteArray(Charsets.ISO_8859_1)
        val authName = PcglX11SetupCodec.MIT_MAGIC_COOKIE_NAME.toByteArray(Charsets.ISO_8859_1)
        val destination = File(prefix.canonicalFile, ".pcgl-Xauthority")
        val temporary = File(prefix.canonicalFile, ".pcgl-Xauthority.tmp")
        require(destination.canonicalFile.parentFile == prefix.canonicalFile) {
            "Xauthority path escaped the selected Wine prefix."
        }

        try {
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                // Xauthority fields are big-endian, regardless of the X11 client byte order.
                output.writeShort(FAMILY_WILD)
                writeField(output, ByteArray(0)) // address
                writeField(output, displayNumber)
                writeField(output, authName)
                writeField(output, cookie)
                output.flush()
            }
            Os.chmod(temporary.absolutePath, OWNER_READ or OWNER_WRITE)
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace the previous Xauthority file.")
            }
            if (!temporary.renameTo(destination)) {
                error("Unable to activate the new Xauthority file.")
            }
            Os.chmod(destination.absolutePath, OWNER_READ or OWNER_WRITE)
            return destination
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun writeField(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size <= 65535) { "Xauthority field is too long." }
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private const val FAMILY_WILD = 0xffff
    private const val OWNER_READ = 0x100
    private const val OWNER_WRITE = 0x080
}
