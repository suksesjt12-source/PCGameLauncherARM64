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
- Native Android Surface/EGL lifecycle, OpenGL ES diagnostic rendering, and RGBA8888 texture upload/presentation bridge: implemented.
- PCGL-namespaced X11 AF_UNIX/epoll transport and X11 11.0 setup exchange: implemented.
- Initial X11 software window/drawable path: CreateWindow/MapWindow, pixmap/GC resource basics, ZPixmap PutImage, CopyArea, ClearArea, window geometry/tree queries and basic selected structure/exposure events are implemented.
- X11 CPU framebuffer composition can submit RGBA8888 frames to the PCGL EGL bridge via the diagnostic integration path.
- Wine's X11 endpoint is not yet enabled: Bionic Wine's access to the AF_UNIX socket and display compatibility have not been verified.
- XRender, MIT-SHM, GLX, DRI3, Vulkan WSI and multi-client window management: not implemented.
- SDL input/gamepad bridge: not yet implemented; current overlay maps to keyboard/mouse.
- End-to-end visible Windows GUI/game rendering: not yet verified.

## Target architecture

1. **PCGL Android shell** — native Android UI, library, import flow, permissions, profiles, and settings.
2. **Runtime supervisor** — isolated per-game process tree, environment construction, logs, lifecycle, and crash reporting.
3. **Compatibility runtime** — Box64 ARM64 host plus Wine/Proton guest binaries and optional DXVK.
4. **Display subsystem** — PCGL-managed display/window lifecycle, X11 core/software framebuffer, then a compatible accelerated Wine rendering path (GLX/DRI3 or validated Vulkan WSI).
5. **Input subsystem** — touch controller, keyboard/mouse mapping, pointer focus and eventually SDL joystick/XInput-compatible virtual gamepad support.
6. **Device profile engine** — detect Android ABI, SoC/GPU, Vulkan capabilities, memory and thermal state; apply conservative per-game defaults with user override.
7. **Diagnostics assistant** — summarize logs and explain settings; all changes remain reviewable and reversible.

## Implementation order

- **Milestone A — independent application boundary:** remove the companion Winlator APK handoff and its branding workflow; keep all user-facing launcher operations in PCGL.
- **Milestone B — runtime contract:** define stable interfaces for runtime packages, game profiles, process sessions, display sessions, and input providers.
- **Milestone C1 — Android EGL proof of concept:** create/destroy a native Android Surface/EGL session, submit diagnostic frames, and upload RGBA8888 frame textures.
- **Milestone C2 — X11 core display:** implement X11 drawable/window resources, software PutImage/CopyArea composition, event delivery and an EGL framebuffer handoff.
- **Milestone C3 — Wine display bridge:** validate socket reachability from Bionic Wine, then add software windowing and an accelerated renderer (GLX/DRI3 or a compatible Vulkan WSI).
- **Milestone D — input integration:** route touch, keyboard and pointer events into the focused X11 window; add and test virtual gamepad axes/buttons.
- **Milestone E — Mali device profiles:** benchmark workloads on real Mali-G57 devices before shipping automatic defaults.
- **Milestone F — AI diagnostics:** summarize logs and explain settings; all changes remain reviewable and reversible.

## Acceptance criteria

The EGL test validates only PCGL's Android surface and GPU submission path. The X11 framebuffer test validates only a bounded subset of X11 core window/image behavior. A build is not considered playable until a Windows test executable opens a visible window through Wine, receives input, updates frames and exits cleanly, followed by a Direct3D test game on real ARM64 Android hardware. CI compilation, protocol unit tests and diagnostic rendering alone do not satisfy this criterion.
