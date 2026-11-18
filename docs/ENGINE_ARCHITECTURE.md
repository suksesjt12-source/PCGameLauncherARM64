# PCGL independent engine architecture

## Product boundary

PCGL is the Android application and orchestration layer developed in this repository. It owns the launcher UI, game library, per-game profiles, storage permissions, runtime installation, process lifecycle, settings, diagnostics, and controller overlay.

PCGL is not a rebranded Winlator application. It may consume compatible open-source components (for example Box64, Wine/Proton, DXVK, Vulkan drivers, SDL, or an X11-compatible server) under their respective licenses. Using a runtime component does not mean embedding or relabeling their original Android application.

## Current state

- Android launcher and library: implemented in this repository.
- ARM64 Box64 packaging: built by the Android APK workflow.
- Wine/Bionic runtime installation: implemented using a pinned third-party runtime distribution.
- Per-game Wine prefixes and EXE staging: implemented.
- DXVK component manager: implemented.
- Device diagnostics and keyboard/mouse overlay: implemented with limitations documented in source.
- Native Android Surface/EGL lifecycle, OpenGL ES diagnostic rendering, and RGBA8888 texture upload/presentation bridge: implemented.
- PCGL-namespaced AF_UNIX transport and authenticated loopback TCP transport (127.0.0.1:6000): implemented.
- X11 11.0 setup and MIT-MAGIC-COOKIE-1 authorization for TCP sessions; owner-only per-game Xauthority file generation: implemented.
- Initial X11 software window/drawable path: CreateWindow/MapWindow, pixmap/GC resource basics, ZPixmap PutImage/GetImage, CopyArea, PolyFillRectangle, ClearArea, window geometry/tree queries and selected core events are implemented.
- X11 CPU framebuffer composition can submit RGBA8888 frames to the PCGL EGL bridge via the renderer-thread frame queue.
- The launcher hands prepared game sessions to a dedicated display Activity, which starts the X11 server and Xauthority before passing DISPLAY/XAUTHORITY to Box64/Wine.
- Wine's connection to this endpoint, actual visible Wine GUI output, and stable window lifecycle have not been verified on a physical device.
- XRender, MIT-SHM, RandR, XInput2, complete multi-client/window-manager behavior, GLX, DRI3/Present and Vulkan WSI are not implemented.
- The current input overlay/helper maps buttons to keyboard/mouse; no tested SDL/XInput virtual gamepad bridge exists.
- End-to-end visible Windows game rendering and playable Direct3D output are not yet verified.

## Target architecture

1. **PCGL Android shell** — native Android UI, library, import flow, permissions, profiles, and settings.
2. **Runtime supervisor** — isolated per-game process tree, environment construction, logs, lifecycle, and crash reporting.
3. **Compatibility runtime** — Box64 ARM64 host plus Wine/Proton guest binaries and optional DXVK.
4. **Display subsystem** — PCGL-managed Android Surface/EGL, authenticated X11 core/software windows, then a compatible accelerated Wine rendering path (GLX or validated DRI3/Present/Vulkan WSI).
5. **Input subsystem** — touch controller, keyboard/mouse mapping, pointer focus and eventually SDL joystick/XInput-compatible virtual gamepad support.
6. **Device profile engine** — detect Android ABI, SoC/GPU, Vulkan capabilities, memory and thermal state; apply conservative per-game defaults with user override.
7. **Diagnostics assistant** — summarize logs and explain settings; all changes remain reviewable and reversible.

## Implementation order

- **Milestone A — independent application boundary:** remove the companion Winlator APK handoff and its branding workflow; keep all user-facing launcher operations in PCGL.
- **Milestone B — runtime contract:** define stable interfaces for runtime packages, game profiles, process sessions, display sessions, and input providers.
- **Milestone C1 — Android EGL proof of concept:** create/destroy a native Android Surface/EGL session, submit diagnostic frames, and upload RGBA8888 frame textures.
- **Milestone C2 — X11 core display:** implement bounded X11 drawable/window resources, software drawing/readback, composition and EGL framebuffer handoff.
- **Milestone C3 — authenticated Wine endpoint:** start an X11 listener on loopback, generate a private MIT-MAGIC-COOKIE-1 Xauthority file, and pass DISPLAY/XAUTHORITY only after server startup.
- **Milestone C4 — Wine compatibility:** validate the Wine X11 handshake and GUI lifecycle; add required core requests and events.
- **Milestone C5 — accelerated graphics:** implement and verify GLX or a Vulkan WSI path suitable for Wine/DXVK.
- **Milestone D — input integration:** route touch, keyboard, pointer and gamepad axes/buttons into the focused X11 window and Windows guest.
- **Milestone E — Mali device profiles:** benchmark workloads on real Mali-G57 devices before shipping automatic defaults.
- **Milestone F — AI diagnostics:** summarize logs and explain settings; all changes remain reviewable and reversible.

## Acceptance criteria

The EGL test validates only PCGL's Android surface and GPU submission path. The X11 framebuffer test validates only a bounded subset of X11 core window/image behavior. The authenticated TCP self-test validates only a loopback setup handshake and software frame path.

A build is not considered playable until a Windows GUI test executable opens a visible window through Wine, receives input, updates frames and exits cleanly, followed by a Direct3D test game on real ARM64 Android hardware. CI compilation, protocol unit tests and diagnostic rendering alone do not satisfy this criterion.
