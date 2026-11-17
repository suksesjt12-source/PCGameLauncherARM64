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

The paths above are an example layout. A package may use other relative paths, provided
the manifest points to the actual files and library directories.

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

## Architecture checks

- Box64 is built for Android ARM64 (AArch64) and embedded into the APK by the GitHub Actions workflow.
- `wine64` and `wineserver` must be 64-bit little-endian Linux ELF files with machine type x86-64.
- `wineboot.exe`, `d3d11.dll`, and `dxgi.dll` must be PE32+ x86-64 files.
- Each component listed in `components` must have a matching SHA-256 entry.
- `libraryPaths` is optional. When present, each relative path must exist as a directory and is passed to Box64 as `BOX64_LD_LIBRARY_PATH`.

## Android execution model

Android 10 and newer restrict regular apps from executing binary files located in their
writable app home directory. This project therefore packages the ARM64 Box64 host engine
as a native APK library and enables native-library extraction. Wine's x86_64 ELF files and
Windows PE files remain data loaded by Box64/Wine, not direct Android process executables.

This packaging model still requires device testing. A successful APK build does not prove
that Box64 can start on every Android vendor build, that all Wine libraries are present,
or that Wine/DXVK can initialize.

## Scope and limitations

- The runtime importer checks structure, hashes, file formats, and processor architecture. It does not prove that a Wine build is compatible with the Android host.
- The runtime probe attempts to start Box64 with the supplied Wine binary and request its version.
- Prefix initialization invokes the supplied Wine `wineboot.exe` through Box64 and stores its output in the container log directory.
- DXVK files are validated as PE32+ x86-64. Vulkan translation, driver compatibility, audio, and gamepad support remain separate integration work.
- A matching SHA-256 detects changes relative to the manifest; it does not authenticate the package publisher. Use only files you trust and have permission to use.
- This project is not an EXE-to-APK converter. Running a Windows executable requires the complete compatible runtime, game data, and graphics/audio/input integration.
