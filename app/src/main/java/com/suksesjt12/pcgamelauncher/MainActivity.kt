package com.suksesjt12.pcgamelauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.text.InputType
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("game_library", MODE_PRIVATE) }
    private val games = mutableListOf<GameEntry>()
    private var installedRuntime: RuntimePackageManager.RuntimeInfo? = null
    private var pendingGameFolder: GameEntry? = null
    private var performancePanel: TextView? = null
    private val performanceHandler = Handler(Looper.getMainLooper())
    private val performanceRefresh = object : Runnable {
        override fun run() {
            performancePanel?.text = PerformanceMonitor.snapshot(this@MainActivity).toDisplayText()
            performanceHandler.postDelayed(this, 3_000L)
        }
    }

    data class GameEntry(val name: String, val uri: String, val kind: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 15, 23)
        window.navigationBarColor = Color.rgb(12, 15, 23)
        loadLibrary()
        installedRuntime = RuntimePackageManager.findInstalled(filesDir)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        performanceHandler.removeCallbacks(performanceRefresh)
        performanceRefresh.run()
        if (RuntimeExecutionManager.isGameRunning()) {
            if (android.provider.Settings.canDrawOverlays(this)) ControllerOverlayManager.show(this, RuntimeExecutionManager.controllerSettings())
        } else {
            ControllerOverlayManager.hide()
        }
    }

    override fun onPause() {
        performanceHandler.removeCallbacks(performanceRefresh)
        super.onPause()
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
            "WINE RUNTIME PACKAGE IMPORTED\n" +
                "${it.name} (${it.version}) • ${it.hostAbi}\n" +
                "wine64, wineserver, wineboot.exe, and DXVK passed architecture/hash checks."
        } ?: (
            "NO WINE RUNTIME PACKAGE INSTALLED\n" +
                "Import a schema v2 ZIP containing x86_64 Wine, wineserver, wineboot.exe, and DXVK."
            )

        val runtimeCard = TextView(this).apply {
            text = RuntimeExecutionManager.engineStatus(this@MainActivity) +
                "\n\n" + runtimeText + "\n\n" + NativeBridge.status() +
                "\n\nDEVICE / VULKAN\n" + NativeBridge.device()
            textSize = 13f
            setTextColor(Color.rgb(255, 220, 150))
            setBackgroundColor(Color.rgb(43, 36, 25))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        content.addView(runtimeCard, matchWidth())

        val performanceTitle = TextView(this).apply {
            text = "DEVICE PERFORMANCE MONITOR"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(8))
        }
        content.addView(performanceTitle)
        performancePanel = TextView(this).apply {
            text = PerformanceMonitor.snapshot(this@MainActivity).toDisplayText()
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 224))
            setBackgroundColor(Color.rgb(24, 29, 41))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(performancePanel, matchWidth())

        content.addView(actionButton("IMPORT WINDOWS EXE") { chooseExe() }, buttonParams())
        content.addView(actionButton("ADD GAME DATA FOLDER") { chooseFolder() }, buttonParams())
        content.addView(actionButton("IMPORT WINE + DXVK RUNTIME (.ZIP)") { chooseRuntimePackage() }, buttonParams())
        content.addView(actionButton("TEST BOX64 + WINE") { probeRuntime() }, buttonParams())
        content.addView(actionButton("SHOW / HIDE FLOATING CONTROLS") { toggleControllerOverlay() }, buttonParams())
        content.addView(actionButton("STOP RUNNING GAME") { stopRunningGame() }, buttonParams())

        val processStatus = TextView(this).apply {
            text = if (RuntimeExecutionManager.isGameRunning()) {
                "GAME PROCESS: RUNNING"
            } else {
                RuntimeExecutionManager.lastGameExitCode()?.let { "LAST GAME EXIT CODE: $it" }
                    ?: "GAME PROCESS: IDLE"
            }
            textSize = 12f
            setTextColor(Color.rgb(155, 166, 187))
            setPadding(0, dp(10), 0, dp(4))
        }
        content.addView(processStatus)

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
            text = "PCGameLauncherARM64 • v0.8.0\nAnalog-style movement, mouse-look and per-game input presets."
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
        pendingGameFolder = null
        openFolderPicker()
    }

    private fun chooseGameFolder(game: GameEntry) {
        pendingGameFolder = game
        openFolderPicker()
    }

    private fun openFolderPicker() {
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
        if (resultCode != RESULT_OK) {
            if (requestCode == REQUEST_FOLDER) pendingGameFolder = null
            return
        }
        val uri = data?.data ?: run {
            if (requestCode == REQUEST_FOLDER) pendingGameFolder = null
            return
        }

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
                val linkedGame = pendingGameFolder
                pendingGameFolder = null
                if (linkedGame != null) {
                    importGameFolder(linkedGame, uri)
                } else {
                    val name = queryDisplayName(uri) ?: "Game data folder"
                    addGame(GameEntry(name, uri.toString(), "Game data folder"))
                }
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
                            "${info.name} ${info.version} passed the package checks.\n\n" +
                                "Use TEST BOX64 + WINE first, then initialize a Wine prefix for each game before launching it."
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

    private fun importGameFolder(game: GameEntry, treeUri: Uri) {
        val profile = try {
            GameContainerManager.create(this, game.name, game.uri)
        } catch (error: Exception) {
            showMessage("Workspace gagal dibuat", error.message ?: "Kesalahan direktori.")
            return
        }
        if (!GameContainerManager.isPrefixInitialized(profile)) {
            showMessage("Wine prefix belum siap", "Jalankan Initialize Wine prefix sebelum mengimpor folder game.")
            return
        }

        val progress = ProgressDialog.show(
            this, "Import game folder",
            "Menyalin isi folder ke penyimpanan privat aplikasi. Jangan tutup aplikasi.",
            true, false
        )
        Thread {
            val result = try {
                GameDataImportManager.importTree(contentResolver, treeUri, profile) to null as String?
            } catch (error: Exception) {
                null to (error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                progress.dismiss()
                val imported = result.first
                if (imported == null) {
                    showMessage("Folder game gagal diimpor", result.second ?: "Kesalahan impor tidak diketahui.")
                    return@runOnUiThread
                }
                buildScreen()
                showMessage(
                    "Folder game berhasil diimpor",
                    "File: ${imported.files}\nFolder: ${imported.directories}\nUkuran: ${imported.bytes / (1024L * 1024L)} MiB\n\nLokasi: ${imported.destination.absolutePath}\n\nEXE dari library ditempatkan di folder kerja yang sama saat diluncurkan."
                )
            }
        }.start()
    }

    private fun installDxvk(game: GameEntry) {
        val runtime = installedRuntime
        if (runtime == null) {
            showMessage("Runtime belum tersedia", "Impor paket Wine + DXVK schema v2 terlebih dahulu.")
            return
        }
        val profile = try {
            GameContainerManager.create(this, game.name, game.uri)
        } catch (error: Exception) {
            showMessage("Workspace gagal dibuat", error.message ?: "Kesalahan direktori.")
            return
        }
        if (!GameContainerManager.isPrefixInitialized(profile)) {
            showMessage("Wine prefix belum siap", "Jalankan Initialize Wine prefix dahulu.")
            return
        }
        runRuntimeTask("Install DXVK") { DxvkInstaller.install(runtime, profile) }
    }

    private fun showGameSettings(game: GameEntry) {
        val profile = try {
            GameContainerManager.create(this, game.name, game.uri)
        } catch (error: Exception) {
            showMessage("Workspace gagal dibuat", error.message ?: "Kesalahan direktori.")
            return
        }
        val current = GameProfileSettings.load(profile)
        val scroll = ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(form)

        val resolutionValues = listOf("640x480", "800x600", "960x540", "1280x720")
        val resolutionLabels = listOf("640 × 480", "800 × 600", "960 × 540", "1280 × 720")
        val fpsValues = listOf(0, 30, 45, 60)
        val fpsLabels = listOf("Uncapped", "30 FPS", "45 FPS", "60 FPS")
        val hudValues = listOf("off", "fps", "fps,frametimes", "fps,frametimes,devinfo")
        val hudLabels = listOf("Off", "FPS", "FPS + frametimes", "FPS + GPU info")
        val audioValues = listOf("default", "pulseaudio")
        val audioLabels = listOf("System default", "PulseAudio latency hint")
        val latencyValues = listOf(40, 60, 100, 150)
        val latencyLabels = latencyValues.map { "$it ms" }
        val layoutValues = listOf("classic", "compact")
        val layoutLabels = listOf("Classic", "Compact")
        val movementValues = listOf("wasd", "arrows")
        val movementLabels = listOf("WASD", "Arrow keys")
        val actionValues = listOf("jiku", "numpad")
        val actionLabels = listOf("J / K / U / I", "Numpad 1 / 2 / 4 / 5")

        fun addSpinner(label: String, options: List<String>, selected: Int): Spinner {
            val title = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.rgb(205, 215, 230))
                setPadding(0, dp(10), 0, dp(2))
            }
            form.addView(title)
            val spinner = Spinner(this)
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                options
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.adapter = adapter
            spinner.setSelection(selected.coerceIn(options.indices))
            form.addView(spinner, matchWidth())
            return spinner
        }

        val heading = TextView(this).apply {
            text = "GRAPHICS"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }
        form.addView(heading)
        val resolutionSpinner = addSpinner(
            "Preferred resolution (saved as profile preference)",
            resolutionLabels,
            resolutionValues.indexOf(current.resolution)
        )
        val fpsSpinner = addSpinner(
            "DXVK frame limiter",
            fpsLabels,
            fpsValues.indexOf(current.frameLimit)
        )
        val vsyncSwitch = Switch(this).apply {
            text = "VSync (DXVK)"
            setTextColor(Color.WHITE)
            isChecked = current.vsync
            setPadding(0, dp(10), 0, dp(6))
        }
        form.addView(vsyncSwitch)
        val hudSpinner = addSpinner(
            "DXVK in-game HUD",
            hudLabels,
            hudValues.indexOf(current.dxvkHud)
        )
        val resolutionNote = TextView(this).apply {
            text = "Resolution is currently stored as a preference only. The launcher does not yet provide a Wine display/window surface to force the game's actual resolution. FPS cap, VSync and HUD settings are applied only when DXVK is installed and active."
            textSize = 11f
            setTextColor(Color.rgb(155, 166, 187))
            setPadding(0, dp(6), 0, dp(12))
        }
        form.addView(resolutionNote)

        val audioHeading = TextView(this).apply {
            text = "AUDIO"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }
        form.addView(audioHeading)
        val audioSpinner = addSpinner(
            "Audio environment",
            audioLabels,
            audioValues.indexOf(current.audioMode)
        )
        val latencySpinner = addSpinner(
            "PulseAudio latency hint",
            latencyLabels,
            latencyValues.indexOf(current.pulseLatencyMs)
        )
        form.addView(TextView(this).apply {
            text = "PulseAudio option only passes PULSE_LATENCY_MSEC to the process. It does not install or start an audio server, and it requires a compatible Wine runtime and accessible PulseAudio service."
            textSize = 11f
            setTextColor(Color.rgb(155, 166, 187))
            setPadding(0, dp(6), 0, dp(12))
        })

        val controlHeading = TextView(this).apply {
            text = "VIRTUAL CONTROLLER PROFILE"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }
        form.addView(controlHeading)
        val layoutSpinner = addSpinner(
            "Layout preset",
            layoutLabels,
            layoutValues.indexOf(current.controllerLayout)
        )
        val movementSpinner = addSpinner(
            "Movement keys",
            movementLabels,
            movementValues.indexOf(current.movementPreset)
        )
        val actionSpinner = addSpinner(
            "Action buttons",
            actionLabels,
            actionValues.indexOf(current.actionPreset)
        )
        val sensitivityLabel = TextView(this).apply {
            text = "Mouse-look sensitivity: ${current.mouseSensitivity}%"
            textSize = 12f
            setTextColor(Color.rgb(205, 215, 230))
            setPadding(0, dp(10), 0, dp(2))
        }
        form.addView(sensitivityLabel)
        val sensitivitySeek = SeekBar(this).apply {
            max = 200
            progress = current.mouseSensitivity - 50
        }
        sensitivitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 50 + (progress / 25) * 25
                sensitivityLabel.text = "Mouse-look sensitivity: ${value}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        val scaleLabel = TextView(this).apply {
            text = "Controller scale: ${current.controllerScale}%"
            textSize = 12f
            setTextColor(Color.rgb(205, 215, 230))
            setPadding(0, dp(10), 0, dp(2))
        }
        form.addView(scaleLabel)
        val scaleSeek = SeekBar(this).apply {
            max = 50
            progress = current.controllerScale - 75
        }
        scaleSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                scaleLabel.text = "Controller scale: ${progress + 75}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        form.addView(scaleSeek)

        val opacityLabel = TextView(this).apply {
            text = "Controller opacity: ${current.controllerOpacity}%"
            textSize = 12f
            setTextColor(Color.rgb(205, 215, 230))
            setPadding(0, dp(10), 0, dp(2))
        }
        form.addView(opacityLabel)
        val opacitySeek = SeekBar(this).apply {
            max = 70
            progress = current.controllerOpacity - 30
        }
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacityLabel.text = "Controller opacity: ${progress + 30}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        form.addView(opacitySeek)

        val previewTitle = TextView(this).apply {
            text = "LAYOUT PREVIEW"
            textSize = 12f
            setTextColor(Color.rgb(205, 215, 230))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
        form.addView(previewTitle)
        val previewStatus = TextView(this).apply {
            text = "Tap a preview button to test the layout."
            textSize = 11f
            setTextColor(Color.rgb(155, 166, 187))
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val dpadMiddle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun previewButton(label: String): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minWidth = dp(44)
                setOnClickListener {
                    previewStatus.text = "Preview input: $label (not sent to the game)"
                }
            }
        dpad.addView(previewButton("UP"), LinearLayout.LayoutParams(dp(52), dp(42)))
        dpadMiddle.addView(previewButton("LEFT"), LinearLayout.LayoutParams(dp(58), dp(42)))
        dpadMiddle.addView(previewButton("DOWN"), LinearLayout.LayoutParams(dp(58), dp(42)))
        dpadMiddle.addView(previewButton("RIGHT"), LinearLayout.LayoutParams(dp(58), dp(42)))
        dpad.addView(dpadMiddle)
        val faceButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val faceTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val faceBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        faceTop.addView(previewButton("Y"), LinearLayout.LayoutParams(dp(48), dp(44)))
        faceTop.addView(previewButton("X"), LinearLayout.LayoutParams(dp(48), dp(44)))
        faceBottom.addView(previewButton("B"), LinearLayout.LayoutParams(dp(48), dp(44)))
        faceBottom.addView(previewButton("A"), LinearLayout.LayoutParams(dp(48), dp(44)))
        faceButtons.addView(faceTop)
        faceButtons.addView(faceBottom)
        previewRow.addView(dpad, LinearLayout.LayoutParams(0, -2, 1f))
        previewRow.addView(faceButtons, LinearLayout.LayoutParams(0, -2, 1f))
        form.addView(previewRow)
        form.addView(previewStatus)
        form.addView(TextView(this).apply {
            text = "The floating overlay maps D-pad and face buttons to keyboard keys through the Wine input helper. It does not emulate XInput or SDL gamepad devices."
            textSize = 11f
            setTextColor(Color.rgb(210, 180, 130))
            setPadding(0, dp(6), 0, dp(12))
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings • ${game.name}")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val settings = GameProfileSettings.Settings(
                        resolution = resolutionValues[resolutionSpinner.selectedItemPosition],
                        frameLimit = fpsValues[fpsSpinner.selectedItemPosition],
                        vsync = vsyncSwitch.isChecked,
                        dxvkHud = hudValues[hudSpinner.selectedItemPosition],
                        audioMode = audioValues[audioSpinner.selectedItemPosition],
                        pulseLatencyMs = latencyValues[latencySpinner.selectedItemPosition],
                        controllerLayout = layoutValues[layoutSpinner.selectedItemPosition],
                        controllerOpacity = opacitySeek.progress + 30,
                        controllerScale = scaleSeek.progress + 75,
                        movementPreset = movementValues[movementSpinner.selectedItemPosition],
                        actionPreset = actionValues[actionSpinner.selectedItemPosition],
                        mouseSensitivity = 50 + (sensitivitySeek.progress / 25) * 25
                    )
                    GameProfileSettings.save(profile, settings)
                    dialog.dismiss()
                    showMessage(
                        "Settings saved",
                        "Game profile: ${game.name}\nResolution preference: ${settings.resolution}\nDXVK FPS cap: ${if (settings.frameLimit == 0) "Uncapped" else settings.frameLimit}\nVSync: ${if (settings.vsync) "On" else "Off"}\nAudio: ${settings.audioMode}\nController: ${settings.movementPreset} + ${settings.actionPreset}\nMouse sensitivity: ${settings.mouseSensitivity}%\n\nThe left analog-style stick maps to keyboard directions. The right look pad sends mouse movement. These are not SDL/XInput axes."
                    )
                } catch (error: Exception) {
                    showMessage("Settings gagal disimpan", error.message ?: "Kesalahan konfigurasi.")
                }
            }
        }
        dialog.show()
    }

    private fun toggleControllerOverlay() {
        if (!RuntimeExecutionManager.isGameRunning()) {
            showMessage("Game belum berjalan", "Mulai game melalui Wine terlebih dahulu untuk mengaktifkan kontrol mengambang.")
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (ControllerOverlayManager.isShowing()) ControllerOverlayManager.hide()
        else if (!ControllerOverlayManager.show(this, RuntimeExecutionManager.controllerSettings())) {
            showMessage("Overlay gagal ditampilkan", "Android tidak dapat membuat jendela kontrol mengambang.")
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (error: Exception) {
            showMessage("Pengaturan overlay tidak tersedia", error.message ?: "Buka pengaturan izin tampil di atas aplikasi secara manual.")
        }
    }

    private fun probeRuntime() {
        val runtime = installedRuntime
        if (runtime == null) {
            showMessage("Runtime belum tersedia", "Impor paket Wine + DXVK schema v2 terlebih dahulu.")
            return
        }
        runRuntimeTask("Testing Box64 + Wine") {
            RuntimeExecutionManager.probeWine(this, runtime)
        }
    }

    private fun initializeWinePrefix(game: GameEntry) {
        val runtime = installedRuntime
        if (runtime == null) {
            showMessage("Runtime belum tersedia", "Impor paket Wine + DXVK schema v2 terlebih dahulu.")
            return
        }

        val profile = try {
            GameContainerManager.create(this, game.name, game.uri)
        } catch (error: Exception) {
            showMessage("Workspace gagal dibuat", error.message ?: "Kesalahan direktori.")
            return
        }

        runRuntimeTask("Initializing Wine prefix") {
            val result = RuntimeExecutionManager.initializeWinePrefix(this, runtime, profile.directory)
            GameContainerManager.setPrefixState(
                profile,
                if (result.success) "wine_prefix_initialized" else "wine_prefix_initialization_failed"
            )
            result
        }
    }

    private fun requestGameLaunch(game: GameEntry) {
        if (installedRuntime == null) {
            showMessage("Runtime belum tersedia", "Impor paket Wine + DXVK schema v2 terlebih dahulu.")
            return
        }

        val argumentsInput = EditText(this).apply {
            hint = "Contoh: -windowed -novid"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 2
        }

        AlertDialog.Builder(this)
            .setTitle("Launch ${game.name}")
            .setMessage("Argumen tambahan opsional. Gunakan tanda kutip untuk argumen yang berisi spasi.")
            .setView(argumentsInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Launch") { _, _ ->
                val arguments = try {
                    parseArguments(argumentsInput.text.toString())
                } catch (error: Exception) {
                    showMessage("Argumen tidak valid", error.message ?: "Format argumen tidak valid.")
                    return@setPositiveButton
                }
                launchGame(game, arguments)
            }
            .show()
    }

    private fun parseArguments(raw: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var tokenStarted = false

        for (character in raw) {
            when {
                character == '"' -> {
                    quoted = !quoted
                    tokenStarted = true
                }
                character.isWhitespace() && !quoted -> {
                    if (tokenStarted) {
                        result.add(token.toString())
                        token.setLength(0)
                        tokenStarted = false
                    }
                }
                else -> {
                    token.append(character)
                    tokenStarted = true
                }
            }
        }

        if (quoted) error("Tanda kutip ganda argumen belum ditutup.")
        if (tokenStarted) result.add(token.toString())
        if (result.size > 128 || result.any { it.length > 8192 } ||
            result.sumOf { it.length } > 32768
        ) {
            error("Argumen peluncuran terlalu panjang.")
        }
        return result
    }

    private fun launchGame(game: GameEntry, arguments: List<String>) {
        val runtime = installedRuntime ?: return
        val profile = try {
            GameContainerManager.create(this, game.name, game.uri)
        } catch (error: Exception) {
            showMessage("Workspace gagal dibuat", error.message ?: "Kesalahan direktori.")
            return
        }

        if (!GameContainerManager.isPrefixInitialized(profile)) {
            showMessage(
                "Wine prefix belum siap",
                "Tekan Initialize Wine prefix pada kartu game ini, tunggu sampai berhasil, lalu coba Launch EXE lagi."
            )
            return
        }

        val progress = ProgressDialog.show(
            this,
            "Preparing game",
            "Menyalin EXE ke folder kerja game privat dan memulai Box64/Wine.",
            true,
            false
        )

        Thread {
            val result = try {
                val prepared = GameLaunchManager.prepareExecutable(
                    resolver = contentResolver,
                    runtime = runtime,
                    profile = profile,
                    sourceUri = Uri.parse(game.uri),
                    displayName = game.name
                )
                val launch = RuntimeExecutionManager.launchWindowsGame(
                    context = this,
                    runtime = runtime,
                    profile = prepared.profile,
                    executable = prepared.executable,
                    arguments = arguments
                )
                Triple(prepared, launch, null as String?)
            } catch (error: Exception) {
                Triple(null, null, error.message ?: error.javaClass.simpleName)
            }

            runOnUiThread {
                progress.dismiss()
                val prepared = result.first
                val launch = result.second
                val error = result.third

                if (prepared == null || launch == null || !launch.success) {
                    showMessage(
                        "Game tidak berhasil dimulai",
                        error ?: launch?.message ?: "Kesalahan runtime yang tidak diketahui."
                    )
                    return@runOnUiThread
                }

                val details =
                    "Launch session: ${launch.sessionId}\n" +
                    "Executable: ${prepared.executable.absolutePath}\n" +
                    "Guest architecture: ${prepared.architecture}\n" +
                    "Log: ${launch.logFile?.absolutePath}\n\n" +
                    launch.message

                if (android.provider.Settings.canDrawOverlays(this)) {
                    ControllerOverlayManager.show(this, RuntimeExecutionManager.controllerSettings())
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Aktifkan kontrol mengambang")
                        .setMessage("Untuk memakai tombol virtual di atas jendela game, izinkan PC Game Launcher tampil di atas aplikasi lain.")
                        .setNegativeButton("Nanti", null)
                        .setPositiveButton("Buka pengaturan") { _, _ -> requestOverlayPermission() }
                        .show()
                }
                AlertDialog.Builder(this)
                    .setTitle("Launch request dikirim")
                    .setMessage(details)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Stop game") { _, _ -> stopRunningGame() }
                    .show()
                buildScreen()
            }
        }.start()
    }

    private fun stopRunningGame() {
        val runtime = installedRuntime
        if (runtime == null) {
            showMessage("Runtime belum tersedia", "Runtime Wine perlu diimpor sebelum menghentikan proses.")
            return
        }
        if (!RuntimeExecutionManager.isGameRunning()) {
            showMessage("Tidak ada game aktif", "Saat ini tidak ada proses game yang tercatat berjalan.")
            return
        }
        runRuntimeTask("Stop running game") {
            RuntimeExecutionManager.stopWindowsGame(this, runtime)
        }
    }

    private fun runRuntimeTask(
        title: String,
        operation: () -> RuntimeExecutionManager.Result
    ) {
        val progress = ProgressDialog.show(
            this,
            title,
            "Runtime sedang diproses. Jangan tutup aplikasi.",
            true,
            false
        )

        Thread {
            val result = try {
                operation()
            } catch (error: Exception) {
                RuntimeExecutionManager.Result(
                    false,
                    "Application",
                    null,
                    error.message ?: error.javaClass.simpleName
                )
            }

            runOnUiThread {
                progress.dismiss()
                val summary = buildString {
                    append(if (result.success) "SUCCESS" else "FAILED")
                    append("\nStage: ")
                    append(result.stage)
                    result.exitCode?.let {
                        append("\nExit code: ")
                        append(it)
                    }
                    append("\n\n")
                    append(result.output)
                }
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(summary)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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

                val initializeButton = Button(this).apply {
                    text = "Initialize Wine prefix"
                    isAllCaps = false
                    setOnClickListener { initializeWinePrefix(game) }
                }
                card.addView(initializeButton)

                val folderButton = Button(this).apply {
                    text = try {
                        val profile = GameContainerManager.create(this@MainActivity, game.name, game.uri)
                        if (GameContainerManager.hasGameData(profile)) "Replace imported game folder" else "Import game folder"
                    } catch (_: Exception) { "Import game folder" }
                    isAllCaps = false
                    setOnClickListener { chooseGameFolder(game) }
                }
                card.addView(folderButton)

                val dxvkButton = Button(this).apply {
                    val enabled = try {
                        val profile = GameContainerManager.create(this@MainActivity, game.name, game.uri)
                        GameContainerManager.isDxvkEnabled(profile)
                    } catch (_: Exception) { false }
                    text = if (enabled) "Reinstall / update DXVK" else "Install DXVK (Vulkan)"
                    isAllCaps = false
                    setOnClickListener { installDxvk(game) }
                }
                card.addView(dxvkButton)

                val settingsButton = Button(this).apply {
                    text = "Game settings + controller preview"
                    isAllCaps = false
                    setOnClickListener { showGameSettings(game) }
                }
                card.addView(settingsButton)

                val launchButton = Button(this).apply {
                    text = "Launch EXE through Wine"
                    isAllCaps = false
                    setOnClickListener { requestGameLaunch(game) }
                }
                card.addView(launchButton)
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
