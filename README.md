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
- PCGL-owned Android Surface/EGL session, GLES shader pipeline, and a pluggable display-backend contract.
- Device temperature, memory, and process-memory monitor.
- Per-game virtual keyboard/mouse controls and overlay.
- Per-game graphics, audio, and controller preferences.

## Important runtime status

The launcher application is its own Android project. Some runtime files are sourced from third-party projects and retain their original licenses. In particular, the current Bionic Wine root filesystem package is based on the Winlator Mali runtime distribution. This is a runtime dependency, not a copy of the Winlator Mali Android application.

**The game display backend is not complete yet. See [the X11 display integration plan](docs/DISPLAY_BACKEND_INTEGRATION.md).** The current launcher can prepare a Wine prefix and start Box64/Wine processes, but it does not yet own a complete XServer/SDL/EGL window lifecycle. A successful process start or EGL diagnostic test is not proof that a game is visible or playable. No fixed FPS or game compatibility is promised.

## Architecture direction

See [docs/ENGINE_ARCHITECTURE.md](docs/ENGINE_ARCHITECTURE.md) for the independent PCGL architecture and implementation milestones. The target is one Android application with its own launcher, runtime orchestration, rendering/window integration, controller input, and automatic per-game profiles. A separately branded Winlator APK is not part of the target architecture.

## Build APK

1. Open the repository's **Actions** tab.
2. Select **Android APK**.
3. Run the workflow or push a change to the tracked source paths.
4. Download the `PCGameLauncher-debug-apk` artifact from a successful run.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Intended device

- Android ARM64.
- Primary performance target: MediaTek Helio G100/G99 with Mali-G57.
- Device compatibility and performance require real-device testing.

## Third-party licensing

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party runtime components remain subject to their own licenses and notices.
