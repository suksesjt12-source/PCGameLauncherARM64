# Runtime package and automatic bootstrap

## Recommended setup: automatic in-app installation

The normal setup no longer requires the user to import a Wine ZIP. The app downloads
and installs two pinned Winlator Mali Bionic release assets into its private
`filesDir/imagefs` directory:

| Component | Release asset | Size | SHA-256 |
|---|---|---:|---|
| Base filesystem | `imagefs.tzst` | 199,788,880 bytes | `0c8c6c1c7653766323378f3294be6618392075bd354d50b2416a534a6e231620` |
| Wine runtime | `proton-9.0-x86_64.tzst` | 52,247,947 bytes | `9c60d605b68865a6c9861bdda54c2e4d845183b0b4ee3e04a8677eb1a74207a6` |

Source: the published `Sd` release at
[Winlator-Extras](https://github.com/GunaCharanTeja/Winlator-Extras/releases/tag/Sd).
The installer checks HTTPS source host, exact archive size, and the pinned SHA-256
before extracting. Extraction rejects absolute/traversal paths, checks canonical
parents against the install root, caps archive entries and expanded data, and
does not create device nodes, sockets, or FIFOs. Failed installations are removed
from the staging directory; an existing non-PCGL `imagefs` directory is never
overwritten.

Box64 is built for Android ARM64 and bundled in the APK at
`lib/arm64-v8a/libbox64.so`. Proton is installed under
`imagefs/opt/proton-9.0-x86_64`. The app validates Wine/wineserver ELF x86_64
binaries and `wineboot.exe` PE32+ x86_64 before marking the runtime installed.
This bootstrap declares x86-only support as disabled; it does not claim that
32-bit Windows programs will run.

DXVK remains an optional in-app component download. Vulkan device detection is
not a rendering compatibility test, and a graphics wrapper must be compatible
with the device's GPU driver.

## Legacy schema-v2 ZIP format

The schema-v2 ZIP importer remains in the source for backward compatibility.
It is no longer required for the normal first-run setup. Its expected structure
is documented below.

## Required archive layout

```
wine-runtime.zip
├── runtime.json
├── wine/
│   ├── bin/
│   │   ├── wine64
│   │   └── wineserver
│   └── lib/
│       ├── x86_64-linux-gnu/
│       │   └── ... x86_64 Wine support libraries ...
│       └── wine/
│           └── x86_64-windows/
│               └── wineboot.exe
└── dxvk/
    └── x64/
        ├── d3d11.dll
        └── dxgi.dll
```

The paths are an example layout. A package may use other relative paths, provided the
manifest points to the actual files and library directories. The `dxvk/` directory and
the `dxvkD3d11` / `dxvkDxgi` manifest fields are optional. If either DXVK field is
present, both DLLs and both SHA-256 entries are required. The launcher can download
official DXVK releases independently from its component manager.

## Manifest

All SHA-256 values below are placeholders and must be replaced with the real lowercase
SHA-256 digest of each listed file. They are not valid package checksums.

```json
{
  "schemaVersion": 2,
  "id": "community-wine-wow64",
  "name": "Community Wine x86_64",
  "version": "1.0.0",
  "hostAbi": "arm64-v8a",
  "supportsX86": false,
  "components": {
    "wine64": "wine/bin/wine64",
    "wineserver": "wine/bin/wineserver",
    "winebootExe": "wine/lib/wine/x86_64-windows/wineboot.exe",
    "dxvkD3d11": "dxvk/x64/d3d11.dll",
    "dxvkDxgi": "dxvk/x64/dxgi.dll"
  },
  "libraryPaths": [
    "wine/lib/x86_64-linux-gnu",
    "wine/lib"
  ],
  "sha256": {
    "wine/bin/wine64": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "wine/bin/wineserver": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "wine/lib/wine/x86_64-windows/wineboot.exe": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "dxvk/x64/d3d11.dll": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "dxvk/x64/dxgi.dll": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  }
}
```

Set `supportsX86` to true only when the supplied Wine build has been confirmed to support
32-bit x86 Windows applications through its WOW64 configuration. The default is false.

## Architecture checks

- Box64 is built for Android ARM64 (AArch64) and embedded into the APK by GitHub Actions.
- `wine64` and `wineserver` must be 64-bit little-endian Linux ELF files with machine type x86-64.
- `wineboot.exe`, `d3d11.dll`, and `dxgi.dll` must be PE32+ x86-64 files.
- Required Wine components listed in `components` must have a matching SHA-256 entry.
- DXVK is optional, but `dxvkD3d11` and `dxvkDxgi` must either both be present or both be omitted. If present, both require SHA-256 entries and must be PE32+ x86-64.
- `libraryPaths` is optional. Each declared relative path must exist as a directory.
- Game EXEs selected by the launcher are checked as PE32+ x86-64 or PE32 x86.
- PE32 x86 games are accepted only when `supportsX86` is true.

## Android execution model

Android 10 and newer restrict regular apps from executing binary files in their writable
app home directory. This project packages the ARM64 Box64 host engine as a native APK
library and enables native-library extraction. Wine's x86_64 ELF files and Windows PE
files remain guest data loaded by Box64/Wine, not direct Android process executables.

This packaging model still requires device testing. A successful APK build does not prove
that Box64 can start on every Android vendor build, that all Wine libraries are present,
or that Wine can initialize.

## Game launch workflow

1. Import the game EXE in the library.
2. Import a compatible Wine runtime ZIP.
3. Use **TEST BOX64 + WINE** to run the Wine version probe.
4. Press **Initialize Wine prefix** on the game card and wait for a successful result.
5. Press **Launch EXE through Wine**. Optional command-line arguments are passed as
   individual process arguments; quoted values may contain spaces.
6. On the game's card, choose **Import game folder** and select its complete installation
   directory. Its contents are copied to
   `drive_c/PCGameLauncher/<id>/game-data` with Windows-safe names and bounded recursion,
   entry count, and expanded size.
7. Choose **DOWNLOAD / UPDATE OFFICIAL DXVK** in the component manager. The launcher reads the latest release metadata from the official GitHub repository, verifies the asset SHA-256 digest published by GitHub, and extracts only the two x64 DLLs. Alternatively, a runtime ZIP may bundle both DLLs.
8. Choose **Install DXVK (Vulkan)** to copy the validated x64 `d3d11.dll` and `dxgi.dll`
   into `drive_c/windows/system32`. The launcher enables
   `WINEDLLOVERRIDES=d3d11,dxgi=n,b` for that game's process.
8. Press **Launch EXE through Wine**. The selected EXE is staged in the same `game-data`
   directory, which is also used as the process working directory.
9. Use **STOP RUNNING GAME** to ask wineserver to stop the current prefix and terminate the
   launcher process.

The native Vulkan panel reports physical devices and queue-family information. Device
detection does not prove DXVK compatibility, successful Vulkan initialization by Wine,
or a functioning Direct3D game. Testing with a compatible runtime and owned game data on
physical hardware is still required.

## Scope and limitations

- Runtime import checks structure, hashes, file formats, and architecture, not runtime compatibility.
- Box64 ARM64 is built and bundled inside the APK; it does not need a separate user download.
- The in-app DXVK downloader requires an official GitHub release asset SHA-256 digest. Older official releases without that published digest are deliberately rejected by automatic installation.
- The bootstrap downloads Winlator Mali's Bionic filesystem and its Proton 9.0 x86_64 package using pinned SHA-256 values. This is a known source package, not a general Wine-package discovery engine.
- Wine execution depends on the contents and build configuration of the supplied runtime ZIP.
- Wine prefix initialization invokes `wineboot.exe` through Box64.
- Game launch reports that the process started; it does not prove the game reached its main menu.
- DXVK x64 DLL installation into Wine system32 and per-process native overrides are implemented. Actual DXVK/Vulkan rendering compatibility still requires device testing.
- Vulkan device enumeration is not a DXVK rendering test or compatibility guarantee.
- The launcher configures the Bionic rootfs environment paths used by Winlator Mali, including host `LD_LIBRARY_PATH`, `PATH`, `HOME`, XDG/fontconfig/Vulkan-wrapper paths, and Mali Box64 flags. The Android-side XServer/SDL display lifecycle and complete PulseAudio service startup are not yet integrated. Runtime installation and a Wine process start do not prove visible game rendering or gameplay.
- SHA-256 detects changes relative to the manifest; it does not authenticate the publisher.
- This project is not an EXE-to-APK converter.


## Per-game graphics, audio and monitoring (launcher v0.6.0)

Per-game settings are stored under `graphicsSettings`, `audioSettings`, and
`controllerSettings` in each container's `profile.json`. When DXVK is installed, the
launcher writes a per-container `pcgl-config/dxvk.conf` with `dxgi.syncInterval` and
`dxvk.maxFrameRate`, then passes `DXVK_CONFIG_FILE` and the selected `DXVK_HUD` to
the game process. The HUD can report FPS and frame times within the game, but only if
DXVK/Vulkan rendering is actually active.

The resolution choice is a saved preference only; no Wine window/display surface exists
yet to force a game resolution. The PulseAudio option passes `PULSE_LATENCY_MSEC`; it
does not install or start PulseAudio, and requires a compatible runtime and reachable
audio service.

The device monitor reads Android battery temperature and percentage, system thermal
status, available system RAM, and the launcher's own process PSS. It does not measure
the Box64 child process's memory or SoC junction temperature. The controller page stores
layout/scale/opacity preferences and includes a button-response preview. It does not
forward touch/controller events into Wine; that requires a dedicated Wine/SDL input bridge.
