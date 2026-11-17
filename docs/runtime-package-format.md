# Runtime package format (schema version 1)

PCGameLauncherARM64 can import a user-selected ZIP archive. The archive must contain
`runtime.json` at its root and all component files at the relative paths listed in the
manifest. The launcher extracts into app-private storage, rejects path traversal and
duplicate entries, limits extracted data to 10 GiB, validates SHA-256 hashes, and checks
that Box64 is a 64-bit little-endian AArch64 ELF binary.

## Required archive layout

```
runtime-package.zip
├── runtime.json
├── bin/
│   └── box64
├── wine/
│   └── bin/
│       ├── wine
│       └── wineserver
└── dxvk/
    └── x64/
        ├── d3d11.dll
        └── dxgi.dll
```

## Manifest

The SHA-256 values below are examples of the required format, not real checksums.
Replace every value with the actual lowercase SHA-256 digest of the corresponding file.

```json
{
  "schemaVersion": 1,
  "id": "community-wine-box64",
  "name": "Community Wine + Box64 + DXVK",
  "version": "1.0.0",
  "abi": "arm64-v8a",
  "components": {
    "box64": "bin/box64",
    "wine": "wine/bin/wine",
    "wineserver": "wine/bin/wineserver",
    "dxvkD3d11": "dxvk/x64/d3d11.dll",
    "dxvkDxgi": "dxvk/x64/dxgi.dll"
  },
  "sha256": {
    "bin/box64": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "wine/bin/wine": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "wine/bin/wineserver": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
    "dxvk/x64/d3d11.dll": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
    "dxvk/x64/dxgi.dll": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
  }
}
```

## Important limitations

- The Box64 file must be an Android-compatible AArch64 ELF binary. The check does not prove
  that its dynamic libraries, Android linker requirements, or SELinux execution policy are
  satisfied.
- Wine and wineserver must be built for a compatible Android/Linux environment. Merely
  placing Linux desktop binaries in this archive will not make them executable on Android.
- DXVK DLLs must match the Wine/graphics stack and target game architecture. This importer
  verifies file hashes and presence, not API compatibility or rendering.
- A matching SHA-256 only detects accidental or deliberate changes relative to the manifest.
  It does not establish who produced the package. Import only runtime files from sources you
  trust and have permission to use.
- Installing a package does not launch Windows executables. Process supervision, Wine prefix
  initialization, graphics translation, audio, input, and device-specific testing remain
  separate development tasks.
