# PCGL independent engine architecture

## Product boundary

PCGL is the Android application and orchestration layer developed in this repository. It owns the launcher UI, game library, per-game profiles, storage permissions, runtime installation, process lifecycle, settings, diagnostics, and controller overlay.

PCGL is not a rebranded Winlator application. It may consume compatible open-source components (for example Box64, Wine/Proton, DXVK, Vulkan drivers, SDL, or an X11-compatible server) under their respective licenses. Using a runtime component does not mean embedding or relabeling its original Android application.

## Current state

- Android launcher and library: implemented in this repository.
- ARM64 Box64 packaging: built by the Android APK workflow.
- Wine/Bionic runtime installation: implemented using a pinned third-party runtime distribution.
- Per-game Wine prefixes and EXE staging: implemented.
- DXVK component manager: implemented.
- Device diagnostics and keyboard/mouse overlay: implemented with limitations documented in source.
- Integrated display server + window lifecycle: not yet implemented.
- SDL input/gamepad bridge: not yet implemented; current overlay maps to keyboard/mouse.
- End-to-end visible game rendering: not yet verified.

## Target architecture

1. **PCGL Android shell** — native Android UI, library, import flow, permissions, profiles, and settings.
2. **Runtime supervisor** — isolated per-game process tree, environment construction, logs, lifecycle, and crash reporting.
3. **Compatibility runtime** — Box64 ARM64 host plus Wine/Proton guest binaries and optional DXVK.
4. **Display subsystem** — PCGL-managed display/window lifecycle and an explicitly licensed X11/Wayland-compatible or native Wine display bridge; SDL/EGL/Vulkan integration selected after a technical compatibility audit.
5. **Input subsystem** — touch controller, keyboard/mouse mapping, and eventually SDL joystick/XInput-compatible virtual gamepad support.
6. **Device profile engine** — detect Android ABI, SoC/GPU, Vulkan capabilities, memory and thermal state; apply conservative per-game defaults with user override.
7. **Diagnostics assistant** — explain launch logs and recommend reversible settings. No privileged/root-only tuning and no unsupported claims of automatic optimization.

## Implementation order

- **Milestone A — independent application boundary:** remove the companion Winlator APK handoff and its branding workflow; keep all user-facing launcher operations in PCGL.
- **Milestone B — runtime contract:** define stable interfaces for runtime packages, game profiles, process sessions, display sessions, and input providers.
- **Milestone C — display proof of concept:** create and destroy one native display surface, connect Wine's supported display path, and verify a simple Windows test application is visibly rendered.
- **Milestone D — input proof of concept:** route touch events to the active window, then add a tested virtual gamepad backend.
- **Milestone E — Mali device profiles:** benchmark a small set of workloads on real Mali-G57 devices before shipping automatic defaults.
- **Milestone F — AI diagnostics:** summarize logs and explain settings; all changes remain reviewable and reversible.

## Acceptance criteria

A build is not considered a playable Windows-game launcher until a test executable opens a visible window, receives input, renders frames, exits cleanly, and produces useful logs on a real ARM64 Android device. CI compilation and APK packaging alone do not satisfy this criterion.
