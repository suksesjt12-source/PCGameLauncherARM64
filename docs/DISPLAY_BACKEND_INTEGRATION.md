# PCGL display backend integration

## Current implementation

PCGL owns a native Android Surface + EGL/OpenGL ES backend. It creates an EGL context and supports a diagnostic renderer plus a CPU RGBA8888 frame upload path. The texture path accepts tightly packed top-left-origin RGBA frames up to 4096x4096 and 64 MiB, copies them into a GLES texture, and displays the texture on the Android Surface.

The Kotlin boundary is PcglDisplayBackend. PcglWindowFrame validates frame dimensions, memory size and byte count before a frame is submitted. EGL calls must remain serialized on the renderer thread because an EGL context cannot be current on multiple threads at once.

## Stage 4.1 — X11 transport layer

The repository contains a separately namespaced PCGL X11 transport module:

- AF_UNIX stream socket creation with a runtime-root constrained path.
- epoll-based client connection monitoring.
- direct-buffer client I/O and ancillary file-descriptor transfer.
- a Kotlin lifecycle adapter and disconnect path.
- JNI symbols and native-library loading namespaced to PCGL.

The transport is adapted from the MIT-licensed XConnector implementation in [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2). Its original attribution and license are retained in THIRD_PARTY_NOTICES.md. This is a reusable connector component, not the Winlator Android application or its display Activity.

## Stage 4.2 — X11 protocol bootstrap

PCGL parses X11 byte order and the X11 11.0 setup exchange, returns a 24-bit TrueColor root screen, rejects unimplemented authorization records, and bounds input/request memory. A limited core-query set is implemented: GetInputFocus, QueryExtension, ListExtensions, GetGeometry, QueryTree, GetWindowAttributes, InternAtom and GetAtomName.

## Stage 4.3 — software window/drawable path

The PCGL core server now has a bounded software drawable store and handles a first subset of X11 core requests:

- CreateWindow, ChangeWindowAttributes (event-mask selection), DestroyWindow, MapWindow, UnmapWindow and ConfigureWindow;
- CreatePixmap / FreePixmap and CreateGC / FreeGC;
- ZPixmap PutImage for depth-24 TrueColor using 32 bits per pixel;
- CopyArea and ClearArea;
- GetGeometry, QueryTree and GetWindowAttributes for registered windows/drawables;
- basic MapNotify, UnmapNotify, DestroyNotify, ConfigureNotify and Expose delivery to the requesting client's selected event masks.

Mapped child windows are composed into a screen-sized RGBA8888 frame, with bounds on drawable dimensions and total allocation. A local on-device protocol test sends a real X11 setup packet, creates/maps a small window, creates a GC, submits a ZPixmap image and checks that the expected pixel reached the frame sink. The EGL display test queues those frames onto the renderer thread rather than making the EGL context current from the socket thread.

This provides a working core software-image path for compatible X11 clients. It is not a complete X server: full event routing across clients, visual/depth variants, GC raster functions, XRender, MIT-SHM, RandR, XInput2, GLX, DRI3 and Vulkan WSI are not implemented.

## Stage 4.4 — EGL frame presentation

PcglDisplayBackend.submitWindowFrame uploads software RGBA8888 frames into an OpenGL ES texture and presents them on the Android Surface. The display diagnostic screen now links the X11 window/image protocol self-test to this bridge. This verifies the X11-to-CPU-frame-to-EGL code path when the diagnostic is run on a device; CI unit tests only verify the framebuffer conversion and APK compilation.

## Wine display endpoint safety

Wine must not receive a fabricated DISPLAY=:0. WineRuntimeEnvironment.apply only publishes DISPLAY when its caller supplies a syntactically valid endpoint after a real server has completed its startup handshake. The current AF_UNIX endpoint has not yet been proven reachable from the packaged Bionic Wine process on Android, and the X11 core subset is not sufficient for most accelerated game renderers. The launcher must therefore keep DISPLAY unset until the client transport and Wine graphics path pass an end-to-end test.

## Remaining integration sequence

1. Extend the X11 core implementation for the request/event patterns used by Wine's X11 driver and the target GUI toolkit.
2. Prove the app-private AF_UNIX socket path is reachable from the Bionic Wine process, or implement a loopback TCP transport with authenticated setup.
3. Implement a window manager policy, focus, pointer/keyboard event delivery, and robust multi-client resource ownership.
4. Add XRender/SHM acceleration for software windows and GLX/DRI3 or a separately supported Wine/Vulkan WSI path for accelerated games.
5. Connect Android touch, keyboard and gamepad input to X11 and/or SDL/XInput with tested axis/button semantics.
6. Verify a Windows GUI test program, then a Direct3D game, on a physical ARM64 Android device.

## Acceptance tests

The current core-framebuffer milestone is complete at code level only when X11 framebuffer unit tests and APK compilation pass. The on-device diagnostic must then be run to verify AF_UNIX request handling and EGL presentation.

The game-display milestone is separate and is complete only when a Windows GUI test program connects to PCGL's server, creates a visible window, updates frames, receives pointer and keyboard input, and exits cleanly. The accelerated-game milestone additionally requires a tested graphics API path. CI compilation and frame-upload tests alone do not satisfy either acceptance test.
