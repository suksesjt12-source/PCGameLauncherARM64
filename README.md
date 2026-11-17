# PCGameLauncherARM64

Android ARM64 launcher project for organizing and testing legally owned Windows game files.

## Implemented

- Import Windows EXE files and game data directories through Android's Storage Access Framework.
- Keep a local game library.
- Build the Box64 Android ARM64 host engine from a pinned upstream source revision during GitHub Actions.
- Package Box64 inside the APK native library directory.
- Import a user-provided Wine + DXVK runtime ZIP using schema version 2.
- Validate ZIP paths, extracted size, hashes, ELF architecture, and PE architecture.
- Create separate per-game workspaces and preserve Wine prefix state.
- Initialize a Wine prefix through Box64 and Wine's wineboot executable.
- Copy a selected Windows EXE into its per-game drive_c workspace.
- Launch the selected EXE through the APK-packaged Box64 engine and supplied Wine binary with optional command-line arguments.
- Track the launched process, record its log, and provide a stop action using wineserver.
- Detect Vulkan devices through the native C++ bridge.
- Build and verify an ARM64 debug APK through GitHub Actions.

## Runtime package

See [docs/runtime-package-format.md](docs/runtime-package-format.md) for the required ZIP layout and schema.

The APK workflow builds Box64 for Android ARM64 and embeds it as `libbox64.so`. The user
runtime ZIP contains x86_64 Wine, wineserver, wineboot.exe, and x64 DXVK DLLs. Set
`supportsX86` to true only for a Wine build tested with 32-bit Windows support.

## Current limitations

The launcher currently copies only the selected EXE, not its entire game installation
directory or accompanying assets. Many games need additional files and will not work with
only the EXE. DXVK has not yet been installed into the Wine prefix or connected to Vulkan.
Audio and gamepad integration are also not implemented.

The launch screen can confirm that the Box64 process started, but only device testing with
a compatible Wine package and actual game content can establish whether the game runs.

A Windows EXE cannot generally be recompiled into a native Android APK without the game's
source code. This project is a compatibility launcher, not an EXE-to-APK converter.

## Build APK

1. Open the repository's Actions tab.
2. Select **Android APK**.
3. Run the workflow with **Run workflow** (or push a commit).
4. Download the `PCGameLauncher-debug-apk` artifact from a completed run.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Intended device

- Android ARM64.
- Intended test target: MediaTek Helio G100/G99 and Mali-G57.
- Actual compatibility and performance need to be measured on hardware. No fixed FPS is guaranteed.

## Third-party licensing

Box64 is built from the upstream project under the MIT License. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Check Wine, DXVK, and all dependencies' licenses before redistribution.
