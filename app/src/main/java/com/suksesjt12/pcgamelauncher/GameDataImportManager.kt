package com.suksesjt12.pcgamelauncher

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Copies a selected SAF tree into a private per-game directory.
 * Provider document IDs are opaque and never used as filesystem paths.
 */
object GameDataImportManager {
    data class ImportResult(val destination: File, val files: Int, val directories: Int, val bytes: Long)
    private data class ChildDocument(val id: String, val name: String, val mimeType: String)
    private class CopyState {
        var entries = 0
        var files = 0
        var directories = 0
        var bytes = 0L
        val visitedDirectoryIds = HashSet<String>()
    }

    private const val MAX_ENTRIES = 100_000
    private const val MAX_DEPTH = 48
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L

    fun importTree(resolver: ContentResolver, treeUri: Uri, profile: GameContainerManager.Profile): ImportResult {
        if (!GameContainerManager.isPrefixInitialized(profile)) {
            error("Wine prefix belum diinisialisasi. Inisialisasi prefix sebelum mengimpor folder game.")
        }
        val driveC = File(profile.directory, "drive_c").canonicalFile
        val base = File(driveC, "PCGameLauncher/${profile.id}").canonicalFile
        if (!base.path.startsWith(driveC.path + File.separator)) error("Direktori tujuan impor tidak valid.")
        if (!base.exists() && !base.mkdirs()) error("Tidak dapat membuat direktori workspace game.")

        val destination = File(base, "game-data").canonicalFile
        val staging = File(base, ".game-data.importing")
        val backup = File(base, ".game-data.previous")
        if (staging.exists() && !staging.deleteRecursively()) error("Tidak dapat membersihkan impor folder sebelumnya.")
        if (backup.exists() && !backup.deleteRecursively()) error("Tidak dapat membersihkan folder cadangan sebelumnya.")
        if (!staging.mkdirs()) error("Tidak dapat membuat direktori staging impor.")

        val state = CopyState()
        try {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            if (rootDocumentId.isBlank()) error("ID folder SAF kosong.")
            state.visitedDirectoryIds.add(rootDocumentId)
            copyDirectory(resolver, treeUri, rootDocumentId, staging, staging.canonicalFile, 0, state)

            if (destination.exists() && !destination.renameTo(backup)) {
                error("Tidak dapat mengganti folder data game lama.")
            }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                error("Tidak dapat memasang folder data game hasil impor.")
            }
            if (backup.exists()) backup.deleteRecursively()

            GameContainerManager.recordGameData(
                profile, destination, treeUri.toString(),
                state.files, state.directories, state.bytes
            )
            return ImportResult(destination, state.files, state.directories, state.bytes)
        } catch (error: Exception) {
            if (staging.exists()) staging.deleteRecursively()
            throw error
        }
    }

    private fun copyDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        outputDirectory: File,
        canonicalRoot: File,
        depth: Int,
        state: CopyState
    ) {
        if (depth > MAX_DEPTH) error("Struktur folder terlalu dalam (batas $MAX_DEPTH level).")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val children = mutableListOf<ChildDocument>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idCol < 0 || nameCol < 0 || mimeCol < 0) error("Metadata penyedia dokumen tidak lengkap.")
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                children.add(
                    ChildDocument(
                        id,
                        cursor.getString(nameCol) ?: "",
                        cursor.getString(mimeCol) ?: "application/octet-stream"
                    )
                )
            }
        } ?: error("Tidak dapat membaca isi folder melalui Storage Access Framework.")

        val siblingNames = HashSet<String>()
        for (child in children) {
            state.entries++
            if (state.entries > MAX_ENTRIES) error("Folder berisi lebih dari $MAX_ENTRIES item.")
            val safeName = sanitizeWindowsName(child.name)
            if (!siblingNames.add(safeName.lowercase(Locale.ROOT))) {
                error("Nama file/folder bertabrakan setelah penyesuaian Windows: $safeName")
            }
            val target = File(outputDirectory, safeName).canonicalFile
            if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                error("Path hasil impor keluar dari direktori tujuan.")
            }

            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!state.visitedDirectoryIds.add(child.id)) error("Siklus folder tidak valid dari penyedia dokumen.")
                if (!target.mkdirs() && !target.isDirectory) error("Tidak dapat membuat folder: $safeName")
                state.directories++
                copyDirectory(resolver, treeUri, child.id, target, canonicalRoot, depth + 1, state)
            } else {
                copyDocument(resolver, treeUri, child.id, target, state)
            }
        }
    }

    private fun copyDocument(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        destination: File,
        state: CopyState
    ) {
        val sourceUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val input = resolver.openInputStream(sourceUri) ?: error("Tidak dapat membuka file: ${destination.name}")
        var fileBytes = 0L
        input.use { source ->
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    val bytes = count.toLong()
                    if (fileBytes > MAX_FILE_BYTES - bytes) error("File melewati batas 4 GiB: ${destination.name}")
                    if (state.bytes > MAX_TOTAL_BYTES - bytes) error("Total folder melewati batas 8 GiB.")
                    fileBytes += bytes
                    state.bytes += bytes
                    output.write(buffer, 0, count)
                }
            }
        }
        state.files++
    }

    private fun sanitizeWindowsName(rawName: String): String {
        if (rawName.isBlank() || rawName == "." || rawName == "..") error("Nama file/folder sumber tidak valid.")
        val cleaned = buildString {
            rawName.forEach { c ->
                val invalid = c.code < 32 || c == '<' || c == '>' || c == ':' ||
                    c == '"' || c == '/' || c == '\\' || c == '|' || c == '?' || c == '*'
                append(if (invalid) '_' else c)
            }
        }.trim().trimEnd('.', ' ')
        if (cleaned.isBlank() || cleaned.length > 180) error("Nama file/folder terlalu panjang atau tidak valid: $rawName")
        if (Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE).matches(cleaned)) {
            return "_$cleaned"
        }
        return cleaned
    }
}
