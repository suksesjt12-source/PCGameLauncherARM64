# PCGameLauncherARM64

Android ARM64 launcher project for organizing and testing legally owned Windows game files.

## Implemented

- Import Windows EXE files through Android's Storage Access Framework.
- Recursively copy selected game data directories into private per-game Wine workspaces with Windows-safe path handling, collision checks, and depth/entry/size limits.
- Keep a local game library.
- Build the Box64 Android ARM64 host engine from a pinned upstream source revision during GitHub Actions.
- Package Box64 inside the APK native library directory.
- Install a pinned Winlator Mali Bionic root filesystem and Proton x86_64 package directly in-app on first setup; both downloads are SHA-256 verified.
- Validate ZIP paths, extracted size, hashes, ELF architecture, and PE architecture.
- Create separate per-game workspaces and preserve Wine prefix state.
- Initialize a Wine prefix through Box64 and Wine's wineboot executable.
- Copy a selected Windows EXE into the same per-game working directory as imported game data.
- Launch the selected EXE through the APK-packaged Box64 engine and supplied Wine binary with optional command-line arguments.
- Track the launched process, record its log, and provide a stop action using wineserver.
- Detect Vulkan physical devices, graphics/compute queues, API/driver versions, and device-local heap size through the native C++ bridge.
- Download the latest official DXVK release directly from doitsujin/dxvk, verify the GitHub-published SHA-256 asset digest, and extract only the two x64 DLLs required by the current D3D11 installer.
- Install validated x64 DXVK DLLs into an initialized Wine prefix and enable per-game native DLL overrides.
- Configure per-game DXVK VSync, frame-rate limit, and HUD using an isolated config file.
- Pass optional PulseAudio latency hints to Wine game processes.
- Monitor Android battery temperature, battery percentage, system thermal status, available RAM, and launcher-process PSS.
- Save per-game controller layout, scale, opacity, movement preset, action-key preset, and mouse-look sensitivity.
- Show an Android floating controller with a left analog-style movement stick, face buttons, and right mouse-look pad after overlay permission is granted.
- Map movement to WASD or arrow keys and face buttons to J/K/U/I or numpad presets on a per-game basis.
- Translate right-pad drag into relative Windows mouse movement through the Wine helper.
- Declare Android INTERNET permission for the authenticated loopback input connection.
- Build a small x86_64 Windows helper that connects to an authenticated loopback TCP endpoint and translates virtual-button messages into Win32 SendInput keyboard/mouse events inside Wine.
- Package and verify both the ARM64 Box64 host engine and x86_64 Wine input helper in the debug APK through GitHub Actions.

## Automatic runtime setup

Box64 is built for Android ARM64 and bundled in the APK. On first setup, the app can download and install the Winlator Mali Bionic base filesystem plus the Proton 9.0 x86_64 Wine package directly into private app storage. The two release assets are pinned by exact byte size and SHA-256; damaged, truncated, or changed downloads are rejected. No user-supplied Wine ZIP is required. DXVK remains an optional in-app download and should only be enabled when the device graphics path is compatible.

The older schema-v2 ZIP importer remains in the source for backward compatibility, but it is no longer the normal setup path. Details and checksums are documented in [docs/runtime-package-format.md](docs/runtime-package-format.md).

## Current limitations

Game data directories can now be copied recursively into the private game workspace.
The selected EXE is staged into the same working directory, allowing programs that use
relative paths to locate sibling assets and DLLs. The importer enforces 8 GiB total,
4 GiB per file, 100,000 entries, and 48 levels. The source folder is not modified.

The in-app component manager downloads the latest official DXVK release only when GitHub publishes a SHA-256 digest for its release asset. It verifies the archive digest before extracting only x64 d3d11.dll and dxgi.dll. Older official releases without a published digest are deliberately rejected by the automatic downloader. DXVK installation and per-process Wine native DLL overrides are implemented. The Vulkan panel is only a device/queue capability probe; it does not prove that the device's driver is compatible with the selected DXVK build or that a Direct3D game will render.
The PulseAudio setting is a client latency hint, not an audio-server installer or backend
switch. The floating controller now sends mapped keyboard and mouse events through a small
Wine helper using Win32 SendInput. It requires Android's "display over other apps" permission
and a Wine desktop/window that is receiving keyboard or mouse input. The default mapping is
W/A/S/D for movement, J/K/U/I for face buttons, Enter for Start, and Escape for Back. Stage 9
adds an analog-style touch stick (quantized to eight-way keyboard directions) and a right
mouse-look surface that sends relative mouse movement. Movement keys, action keys, and mouse
sensitivity are stored per game. It does not expose true analog axes, SDL virtual joystick, or
XInput and will not control games that require gamepad-only input. The launcher still lacks a Wine/SDL display surface,
so a compatible display backend must be supplied by the imported runtime for the game itself
to be visible. Resolution remains a preferred profile setting until a Wine display/window
surface exists. DXVK HUD can show in-game FPS only when the game actually renders through a
compatible DXVK/Vulkan path.

The launcher configures the Bionic rootfs paths used by Winlator Mali, including host LD_LIBRARY_PATH, PATH, HOME, XDG/fontconfig/Vulkan-wrapper paths, and Mali Box64 flags. It still lacks the Winlator XServer/SDL window lifecycle and complete audio/display service startup path. A successful setup or Wine process start does not establish that a game will render visibly or be playable.

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
