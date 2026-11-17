# Runtime package format (schema version 2)

The Android APK contains the native ARM64 Box64 host engine at
`lib/arm64-v8a/libbox64.so`. The Android package installer extracts this native library
to the application's native library directory. The application launches Box64 only from
that APK-managed directory.

A user-supplied runtime ZIP contains the x86_64 Wine userspace and x64 Windows DLLs. It
must contain `runtime.json` at its root. The importer rejects path traversal, duplicate
archive entries, invalid hashes, incorrect ELF machine types, and incorrect PE machine
types. The extracted guest executables are loaded by Box64; Android does not directly
execute them from the writable app data directory.

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
manifest points to the actual files and library directories.

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
- Each component listed in `components` must have a matching SHA-256 entry.
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
6. The selected EXE is copied into the game's private `drive_c/PCGameLauncher/<id>`
   directory and started through the APK-packaged Box64 engine and the supplied Wine binary.
7. Use **STOP RUNNING GAME** to ask wineserver to stop the current prefix and terminate the
   launcher process.

The current launch stage copies only the selected EXE file. It does not recursively import
the rest of a game's installation directory, so games that need sibling DLLs, archives,
asset files, or installers will not be complete yet. The launcher does not yet activate
the imported DXVK DLLs or translate Direct3D calls to Vulkan.

## Scope and limitations

- Runtime import checks structure, hashes, file formats, and architecture, not runtime compatibility.
- Wine execution depends on the contents and build configuration of the supplied runtime ZIP.
- Wine prefix initialization invokes `wineboot.exe` through Box64.
- Game launch reports that the process started; it does not prove the game reached its main menu.
- DXVK/Vulkan rendering integration, audio, and gamepad support remain separate work.
- SHA-256 detects changes relative to the manifest; it does not authenticate the publisher.
- This project is not an EXE-to-APK converter.
