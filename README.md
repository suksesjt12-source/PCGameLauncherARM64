# PCGameLauncherARM64

Android ARM64 launcher foundation for organizing legally owned Windows game files.

## Implemented
- Import a Windows executable (.exe) through Android's Storage Access Framework.
- Import a game data directory.
- Persist document access permissions and keep a local game library.
- Restrict the native build to ARM64 (arm64-v8a).
- Build a debug APK automatically with GitHub Actions.
- Show a native runtime-readiness status.

## Important runtime limitation
This project does not yet bundle Wine, Box64, DXVK, or a complete Windows graphics/audio/input stack. Importing an EXE only registers it in the library; it does not run the game. Launching Windows games requires a compatible Wine/Box64 runtime, native dependencies, graphics translation, audio and input integration, plus device-specific testing.

A Windows EXE cannot generally be recompiled into a native Android APK without the game's source code. This project is a compatibility-launcher foundation, not an EXE-to-APK converter.

## Build APK
1. Open the repository's Actions tab.
2. Select Android APK.
3. Run the workflow with Run workflow (or push a commit).
4. Download the PCGameLauncher-debug-apk artifact from the completed run.

APK output: app/build/outputs/apk/debug/app-debug.apk

## Target device
- Android ARM64
- Intended test target: MediaTek Helio G100/G99 and Mali-G57
- Performance depends on the game, runtime, driver and thermal limits. No fixed FPS is guaranteed.

## Project structure
- app/: Android application and native status bridge
- .github/workflows/android.yml: GitHub Actions build

## License
No license has been selected yet. Add one before distributing the project or bundling third-party runtime components.