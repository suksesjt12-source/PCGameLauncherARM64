# PCGL display backend integration

## Current implementation

PCGL owns a native Android Surface + EGL/OpenGL ES backend. It creates an EGL context and supports a diagnostic renderer plus a CPU RGBA8888 frame upload path. The texture path accepts tightly packed top-left-origin RGBA frames up to 4096x4096 and 64 MiB, copies them into a GLES texture, and displays the texture on the Android Surface.

The Kotlin boundary is PcglDisplayBackend. PcglWindowFrame validates the frame dimensions, memory size and byte count before a frame is submitted.

## Stage 4.1 — X11 transport layer

The repository contains a separately namespaced PCGL X11 transport module:

- AF_UNIX stream socket creation with a runtime-root constrained path.
- epoll-based client connection monitoring.
- direct-buffer client I/O and ancillary file-descriptor transfer.
- a Kotlin lifecycle adapter and disconnect path.
- JNI symbols and native-library loading namespaced to PCGL.

The transport is adapted from the MIT-licensed XConnector implementation in [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2). Its original attribution and license are retained in THIRD_PARTY_NOTICES.md. This is a reusable connector component, not the Winlator Android application or its display Activity.

## Stage 4.2 — X11 protocol bootstrap

PCGL has an independent protocol bootstrap above the AF_UNIX transport:

- parses the X11 client byte-order marker and setup request;
- validates X11 protocol 11.0 and setup packet bounds;
- returns a SetupSuccess record with a 24-bit TrueColor root screen;
- rejects authorization records until cookie authentication is implemented;
- answers a small set of read-only core requests: GetInputFocus, QueryExtension, ListExtensions, GetGeometry, QueryTree, GetWindowAttributes, InternAtom and GetAtomName;
- returns explicit X11 errors for unsupported requests;
- bounds pending request memory and closes malformed clients.

A local self-test is available from the native display test screen. It connects through an Android filesystem AF_UNIX socket, sends an X11 setup request and validates the server reply. JVM tests cover little-endian and big-endian setup packets, partial input, authorization rejection and root-screen fields.

## Stage 4.3 — EGL frame texture bridge

PCGL now exposes a PcglDisplayBackend.submitWindowFrame API and a native JNI path that uploads RGBA8888 frame buffers into an OpenGL ES texture. The display test screen can generate a small diagnostic frame and submit it, then clear the texture to return to the diagnostic triangle renderer. JVM tests verify the frame model dimension and byte-count validation.

This stage proves only the Android-side frame upload and texture presentation contract. It does not connect X11 windows or Wine drawable buffers to that contract. A real X11 drawable adapter must still supply valid frames, handle damage/resize synchronization, and preserve window dimensions and z-order. GLX, DRI3 and shared-memory transport are still not connected.

## Wine display endpoint safety

Wine must not receive a fabricated DISPLAY=:0. WineRuntimeEnvironment.apply only publishes DISPLAY when its caller supplies a syntactically valid endpoint after a real X server has completed its startup handshake. The current protocol bootstrap and frame upload bridge are not yet a game-capable display server, so the launcher must leave DISPLAY unset.

## Remaining integration sequence

1. Port/adapt the remaining X11 core requests and X resource model under the PCGL namespace.
2. Connect X11 window create/map/configure/destroy events and drawable pixels to PcglDisplayBackend.
3. Implement MIT-MAGIC-COOKIE-1 authorization and publish DISPLAY only after a real server handshake is ready.
4. Add GLX/DRI3 or another supported Wine rendering path, including image transfer and synchronization.
5. Route keyboard, pointer, touch and gamepad events into the X server input model.
6. Verify a Windows GUI test program on a physical ARM64 Android device.

## Acceptance tests

The RGBA frame bridge milestone is complete at code level when CI unit tests and APK compilation pass. The frame upload button must still be exercised on a physical Android device.

The game-display milestone is separate and is complete only when a Windows GUI test program connects to PCGL's server, creates a visible window, updates frames, receives pointer and keyboard input, and exits cleanly. CI compilation and frame-upload tests alone do not satisfy that acceptance test.
