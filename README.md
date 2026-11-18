# PC Game Launcher ARM64

**PCGL** is an independently developed Android launcher for legally owned Windows game files. The launcher UI, game library, per-game workspaces, configuration, component manager, process control, and Android integration are maintained in this repository.

This is not a renamed Winlator APK. The project uses interoperable runtime components such as Box64 and Wine/Proton where needed; those dependencies do not define the launcher application. The previous companion-APK workflow that rebuilt and rebranded the Winlator Mali app has been retired.

## Current implementation

- Native Android launcher and local game library.
- Import Windows EXE files and game data through Android's Storage Access Framework.
- Per-game private Wine workspaces and prefix initialization.
- Box64 ARM64 host binary built from a pinned upstream source revision and packaged in the launcher APK.
- In-app installation of a checksum-pinned Bionic Wine/Proton runtime package.
- EXE staging, argument parsing, process tracking, logs, and stop control.
- Optional official DXVK download, checksum validation, installation, and per-game configuration.
- Vulkan device and queue capability probe.
- PCGL-owned Android Surface/EGL session and GLES diagnostic renderer.
- PCGL-namespaced X11 AF_UNIX and loopback TCP transports; TCP mode binds to 127.0.0.1 and requires MIT-MAGIC-COOKIE-1 authentication.
- X11 11.0 setup, Xauthority generation, and an initial software window/drawable implementation (CreateWindow/MapWindow, GC, ZPixmap PutImage/GetImage, CopyArea, PolyFillRectangle, geometry/tree queries, and selected core events).
- RGBA8888 software framebuffer composition routed to the Android EGL texture renderer.
- Launcher-to-display Activity handoff; the game launch path starts an authenticated X11 display session before passing DISPLAY/XAUTHORITY to Wine.
- Device temperature, memory, and process-memory monitor.
- Per-game virtual keyboard/mouse controls and overlay.
- Per-game graphics, audio, and controller preferences.

## Important runtime status

The launcher application is its own Android project. Some runtime files are sourced from third-party projects and retain their original licenses. In particular, the current Bionic Wine root filesystem package is based on the Winlator Mali runtime distribution. This is a runtime dependency, not a copy of the Winlator Mali Android application.

**The Windows game display is still an incomplete compatibility path.** PCGL now has an authenticated X11 software-window path and starts it as part of the game launch flow, but the server is only a subset of X11 core. XRender, MIT-SHM, RandR, XInput2, full multi-client/window-manager behavior, GLX, DRI3/Present, and Vulkan WSI are not implemented. Therefore many Wine applications may fail to open a visible window, and Direct3D games using DXVK are not yet supported by a verified accelerated display path. A successful CI build or process start is not proof that a game is visible or playable. No fixed FPS or game compatibility is promised.

The next required checks are the on-device authenticated X11 TCP diagnostic, a Wine GUI test program, then an accelerated Direct3D test on the target ARM64/Mali device. See [the display integration record](docs/DISPLAY_BACKEND_INTEGRATION.md).

## Architecture direction

See [docs/ENGINE_ARCHITECTURE.md](docs/ENGINE_ARCHITECTURE.md) for the independent PCGL architecture and implementation milestones. The target is one Android application with its own launcher, runtime orchestration, rendering/window integration, controller input, and automatic per-game profiles. A separately branded Winlator APK is not part of the target architecture.

## Build APK

1. Open the repository's **Actions** tab.
2. Select **Android APK**.
3. Run the workflow or push a change to the tracked source paths.
4. Download the PCGameLauncher-debug-apk artifact from a successful run.

APK output: app/build/outputs/apk/debug/app-debug.apk.

## Intended device

- Android ARM64.
- Primary performance target: MediaTek Helio G100/G99 with Mali-G57.
- Device compatibility and performance require real-device testing.

## Third-party licensing

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party runtime components remain subject to their own licenses and notices.
