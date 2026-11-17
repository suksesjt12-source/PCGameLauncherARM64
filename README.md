# PCGameLauncherARM64

Android ARM64 launcher project for organizing and testing legally owned Windows game files.

## Implemented in the project

- Import Windows EXE files and game data directories through Android's Storage Access Framework.
- Keep a local game library.
- Build the Box64 Android ARM64 host engine from upstream source during GitHub Actions.
- Package Box64 inside the APK native library directory instead of attempting to execute a writable app-data binary.
- Import a user-provided Wine + DXVK runtime ZIP using schema version 2.
- Validate safe ZIP paths, archive size, duplicate entries, SHA-256 hashes, ELF architecture, and PE architecture.
- Create per-game workspaces and configuration profiles.
- Attempt a Box64 + Wine version probe and Wine prefix initialization, with logs saved in the selected workspace.
- Detect Vulkan devices through the native C++ bridge.
- Build an ARM64 debug APK through GitHub Actions.

## Runtime package

See [docs/runtime-package-format.md](docs/runtime-package-format.md) for the required ZIP layout and manifest.

The APK workflow builds Box64 from the pinned upstream source revision and embeds it as
`libbox64.so` in the APK's ARM64 native library directory. The user runtime ZIP contains
x86_64 Wine, wineserver, wineboot.exe, and x64 DXVK DLLs. The launcher does not bundle a
Wine distribution or silently download a third-party Wine package.

## Important Android limitation

Android 10 and newer restrict regular apps from executing binary files in their writable
app home directory. The Box64 host engine is therefore packaged as a native APK library.
Wine's x86_64 executables are guest files loaded by Box64. This is the intended design,
but device-level execution still needs to be verified on actual Android hardware and
vendor firmware.

## Current execution status

- EXE import: stores the document URI in the library.
- Runtime ZIP import and file-format validation: implemented.
- Box64 source build and APK packaging: configured in GitHub Actions; the latest workflow must complete successfully before the new APK is considered build-verified.
- Box64 + Wine version probe: implemented, requires a compatible runtime ZIP and a successful native Box64 build.
- Wine prefix initialization through wineboot.exe: implemented, requires a complete compatible runtime.
- Launching a selected game EXE: not implemented yet.
- DXVK/Vulkan rendering integration: not implemented yet.
- Audio and gamepad integration: not implemented yet.

A Windows EXE cannot generally be recompiled into a native Android APK without the game's source code. This project is a compatibility launcher, not an EXE-to-APK converter.

## Build APK

1. Open the repository's Actions tab.
2. Select **Android APK**.
3. Run the workflow with **Run workflow** (or push a commit).
4. Download the `PCGameLauncher-debug-apk` artifact from the completed run.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Intended device

- Android ARM64.
- Intended test target: MediaTek Helio G100/G99 and Mali-G57.
- Actual performance and game compatibility need to be measured on the device. No fixed FPS is guaranteed.

## Project structure

- `app/`: Android application, native bridge, runtime and container managers.
- `docs/runtime-package-format.md`: Wine/DXVK runtime ZIP schema.
- `.github/workflows/android.yml`: Box64 source build and Android APK workflow.

## Third-party licensing

Box64 is built from the upstream project under the MIT License. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Check Wine, DXVK, and all dependencies' licenses before redistribution.
