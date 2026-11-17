package com.suksesjt12.pcgamelauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("game_library", MODE_PRIVATE) }
    private val games = mutableListOf<GameEntry>()
    private var installedRuntime: RuntimePackageManager.RuntimeInfo? = null

    data class GameEntry(val name: String, val uri: String, val kind: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 23)
        window.navigationBarColor = Color.rgb(12, 15, 23)
        loadLibrary()
        installedRuntime = RuntimePackageManager.findInstalled(filesDir)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 15, 23))
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "PC GAME LAUNCHER"
            textSize = 23f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(title)

        val subtitle = TextView(this).apply {
            text = "ARM64 • Windows game library"
            textSize = 13f
            setTextColor(Color.rgb(155, 166, 187))
            setPadding(0, dp(5), 0, dp(18))
        }
        content.addView(subtitle)

        val runtimeText = installedRuntime?.let {
            "RUNTIME PACKAGE IMPORTED\n" +
                "${it.name} (${it.version}) • ${it.abi}\n" +
                "Box64, Wine, wineserver, and DXVK files passed package checks. " +
                "This does not prove Android can execute them; EXE launching is not implemented yet."
        } ?: (
            "NO RUNTIME PACKAGE INSTALLED\n" +
                "Import a compatible ARM64 runtime ZIP. It must include Box64, Wine, wineserver, " +
                "DXVK DLLs, runtime.json, and matching SHA-256 hashes."
            )

        val runtimeCard = TextView(this).apply {
            text = runtimeText + "\n\n" + NativeBridge.status() +
                "\n\nDEVICE / VULKAN\n" + NativeBridge.device()
            textSize = 13f
            setTextColor(Color.rgb(255, 220, 150))
            setBackgroundColor(Color.rgb(43, 36, 25))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(runtimeCard, matchWidth())

        content.addView(actionButton("IMPORT WINDOWS EXE") { chooseExe() }, buttonParams())
        content.addView(actionButton("ADD GAME DATA FOLDER") { chooseFolder() }, buttonParams())
        content.addView(actionButton("IMPORT WINE + BOX64 RUNTIME (.ZIP)") { chooseRuntimePackage() }, buttonParams())

        val section = TextView(this).apply {
            text = "GAME LIBRARY"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(10))
        }
        content.addView(section)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, matchWidth())
        renderLibrary(list)

        val footer = TextView(this).apply {
            text = "PCGameLauncherARM64 • v0.1.0\nDesigned for Android ARM64 devices. Game compatibility and performance are not guaranteed."
            textSize = 11f
            setTextColor(Color.rgb(120, 132, 153))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        content.addView(footer)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(36, 91, 174))
            setOnClickListener { action() }
        }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, dp(52)).apply {
        topMargin = dp(10)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)

    private fun chooseExe() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_EXE)
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    private fun chooseRuntimePackage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_RUNTIME)
    }

    @Deprecated("Platform document picker is used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable URI permissions.
        }

        when (requestCode) {
            REQUEST_EXE -> {
                val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.exe"
                if (!name.endsWith(".exe", ignoreCase = true)) {
                    AlertDialog.Builder(this)
                        .setTitle("File bukan EXE")
                        .setMessage("Nama file tidak berakhiran .exe. Pilih executable Windows yang benar.")
                        .setPositiveButton("OK", null)
                        .show()
                    return
                }
                addGame(GameEntry(name, uri.toString(), "Windows executable"))
            }
            REQUEST_FOLDER -> {
                val name = queryDisplayName(uri) ?: "Game data folder"
                addGame(GameEntry(name, uri.toString(), "Game data folder"))
            }
            REQUEST_RUNTIME -> importRuntimePackage(uri)
        }
    }

    private fun importRuntimePackage(uri: Uri) {
        val progress = ProgressDialog.show(
            this,
            "Importing runtime package",
            "Extracting files and checking SHA-256. Keep the app open.",
            true,
            false
        )

        Thread {
            try {
                val info = RuntimePackageManager.install(contentResolver, uri, filesDir)
                runOnUiThread {
                    progress.dismiss()
                    installedRuntime = info
                    buildScreen()
                    AlertDialog.Builder(this)
                        .setTitle("Runtime package imported")
                        .setMessage(
                            "${info.name} ${info.version} has passed the package checks.\n\n" +
                                "This is not an execution test. Starting EXE files is still unavailable."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Runtime import failed")
                        .setMessage(error.message ?: "The archive could not be installed.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun addGame(entry: GameEntry) {
        if (games.any { it.uri == entry.uri }) return
        games.add(entry)
        saveLibrary()
        buildScreen()
    }

    private fun removeGame(entry: GameEntry) {
        games.removeAll { it.uri == entry.uri }
        saveLibrary()
        buildScreen()
    }

    private fun createGameProfile(game: GameEntry) {
        try {
            val profile = GameContainerManager.create(this, game.name, game.uri)
            AlertDialog.Builder(this)
                .setTitle("Game workspace created")
                .setMessage(
                    "Profile ID: ${profile.id}\n" +
                        "Directory: ${profile.directory.absolutePath}\n\n" +
                        "This is only a per-game workspace. Wine has not initialized it as a valid prefix yet."
                )
                .setPositiveButton("OK", null)
                .show()
        } catch (error: Exception) {
            Toast.makeText(this, error.message ?: "Could not create game profile.", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadLibrary() {
        games.clear()
        try {
            val json = JSONArray(prefs.getString(KEY_GAMES, "[]") ?: "[]")
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                games.add(
                    GameEntry(
                        item.optString("name", "Unknown game"),
                        item.optString("uri", ""),
                        item.optString("kind", "Game file")
                    )
                )
            }
        } catch (_: Exception) {
            games.clear()
        }
    }

    private fun saveLibrary() {
        val json = JSONArray()
        games.forEach { game ->
            json.put(JSONObject().apply {
                put("name", game.name)
                put("uri", game.uri)
                put("kind", game.kind)
            })
        }
        prefs.edit().putString(KEY_GAMES, json.toString()).apply()
    }

    private fun renderLibrary(list: LinearLayout) {
        list.removeAllViews()
        if (games.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Belum ada game. Impor file EXE atau folder data game."
                textSize = 13f
                setTextColor(Color.rgb(155, 166, 187))
                setPadding(dp(14), dp(18), dp(14), dp(18))
                setBackgroundColor(Color.rgb(24, 29, 41))
            }
            list.addView(empty, matchWidth())
            return
        }

        games.forEach { game ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.rgb(24, 29, 41))
            }
            val name = TextView(this).apply {
                text = game.name
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val kind = TextView(this).apply {
                text = game.kind
                textSize = 12f
                setTextColor(Color.rgb(145, 160, 184))
                setPadding(0, dp(4), 0, dp(4))
            }
            val uri = TextView(this).apply {
                text = game.uri
                textSize = 10f
                setTextColor(Color.rgb(110, 126, 151))
                maxLines = 2
            }
            val remove = Button(this).apply {
                text = "Remove from library"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove " + game.name + "?")
                        .setMessage("This only removes the entry from this launcher. It does not delete the original file.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ -> removeGame(game) }
                        .show()
                }
            }
            card.addView(name)
            card.addView(kind)
            card.addView(uri)
            if (game.kind == "Windows executable") {
                val profileButton = Button(this).apply {
                    text = "Create game workspace"
                    isAllCaps = false
                    setOnClickListener { createGameProfile(game) }
                }
                card.addView(profileButton)
            }
            card.addView(remove)
            val params = matchWidth().apply { bottomMargin = dp(10) }
            list.addView(card, params)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_EXE = 1001
        private const val REQUEST_FOLDER = 1002
        private const val REQUEST_RUNTIME = 1003
        private const val KEY_GAMES = "games"
    }
}
