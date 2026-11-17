# PCGameLauncherARM64

Android ARM64 launcher foundation for organizing legally owned Windows game files.

## Implemented

- Import a Windows executable (.exe) through Android's Storage Access Framework.
- Import a game data directory and persist document access permission.
- Keep a local game library.
- Import a user-provided Wine + Box64 + DXVK runtime ZIP into app-private storage.
- Reject unsafe archive paths, duplicate paths, oversized extraction, wrong ABI, missing component files, and invalid SHA-256 hashes.
- Check that the Box64 binary is a 64-bit little-endian AArch64 ELF file.
- Create a separate per-game workspace and JSON configuration profile.
- Restrict the native build to ARM64 (arm64-v8a).
- Detect Vulkan devices through the native bridge.
- Build a debug APK automatically with GitHub Actions.

## Runtime package requirements

See docs/runtime-package-format.md for the exact ZIP layout and runtime.json schema.

The importer expects Box64, Wine, wineserver, and the x64 DXVK d3d11.dll and dxgi.dll files. The archive must include correct SHA-256 values for every component. The launcher does not download third-party runtime binaries automatically.

**Importing a runtime is not the same as running it.** Hash and ELF checks confirm package structure and some file properties only. They do not prove dynamic linker compatibility, permission to execute the binary, Wine functionality, DXVK compatibility, or game performance on a particular phone.

## Current execution status

- EXE import: implemented as a library registration only.
- Runtime ZIP import and validation: implemented.
- Per-game workspace creation: implemented, but it is not yet a valid Wine prefix.
- Wine prefix initialization with wineboot: not implemented.
- Box64 process supervision and Wine invocation: not implemented.
- DXVK/Vulkan rendering integration: not implemented.
- Audio and gamepad integration: not implemented.

A Windows EXE cannot generally be recompiled into a native Android APK without the game's source code. This project is a compatibility-launcher foundation, not an EXE-to-APK converter.

## Build APK

1. Open the repository's Actions tab.
2. Select Android APK.
3. Run the workflow with Run workflow (or push a commit).
4. Download the PCGameLauncher-debug-apk artifact from the completed run.

APK output: app/build/outputs/apk/debug/app-debug.apk.

## Target device

- Android ARM64.
- Intended test target: MediaTek Helio G100/G99 and Mali-G57.
- Performance depends on the game, runtime, graphics driver, and thermal limits. No fixed FPS is guaranteed.

## Project structure

- app/: Android application and native status bridge.
- docs/runtime-package-format.md: runtime ZIP format.
- .github/workflows/android.yml: GitHub Actions build.

## Licensing

No license has been selected yet. Check the licenses of Wine, Box64, DXVK, and every runtime dependency before packaging or redistributing them. Add a project license before distributing this launcher.
