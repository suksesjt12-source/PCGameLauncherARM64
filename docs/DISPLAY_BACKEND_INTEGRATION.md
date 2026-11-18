# PCGL display backend integration

## Current implementation

PCGL owns a native Android Surface + EGL/OpenGL ES backend. It creates an EGL context, compiles a small GLES 2.0 shader program, and renders a rotating colored triangle. This is a diagnostic path only; it is not a Windows game window.

The Kotlin boundary is PcglDisplayBackend, with PcglEglSurfaceBackend as its first implementation.

## Stage 4.1 — X11 transport layer

The repository contains a separately namespaced PCGL X11 transport module:

- AF_UNIX stream socket creation with a runtime-root constrained path.
- epoll-based client connection monitoring.
- direct-buffer client I/O and ancillary file-descriptor transfer.
- a Kotlin lifecycle adapter and disconnect path.
- JNI symbols and native-library loading namespaced to PCGL.

The transport is adapted from the MIT-licensed XConnector implementation in [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2). Its original attribution and license are retained in THIRD_PARTY_NOTICES.md. This is a reusable connector component, not the Winlator Android application or its display Activity.

## Stage 4.2 — X11 protocol bootstrap

PCGL now has an independent protocol bootstrap above the AF_UNIX transport:

- parses the X11 client byte-order marker and setup request;
- validates X11 protocol 11.0 and setup packet bounds;
- returns a SetupSuccess record with a 24-bit TrueColor root screen;
- rejects authorization records until cookie authentication is implemented;
- answers a small set of read-only core requests: GetInputFocus, QueryExtension, ListExtensions, GetGeometry, QueryTree, GetWindowAttributes, InternAtom and GetAtomName;
- returns explicit X11 errors for unsupported requests;
- bounds pending request memory and closes malformed clients.

A local self-test is available from the native display test screen. It connects through an Android filesystem AF_UNIX socket, sends an X11 setup request and validates the server reply. JVM tests cover little-endian and big-endian setup packets, partial input, authorization rejection and root-screen fields.

This is a protocol bootstrap, not a complete X server. It does not implement CreateWindow/MapWindow rendering, drawable storage, GLX, DRI3, shared-memory image transfer, keyboard/pointer events or XInput. It cannot yet display Wine or Windows game windows, and it does not publish DISPLAY.

## Wine display endpoint safety

Wine must not receive a fabricated DISPLAY=:0. WineRuntimeEnvironment.apply only publishes DISPLAY when its caller supplies a syntactically valid endpoint after a real X server has completed its startup handshake. The current protocol bootstrap is not yet a game-capable display server, so the launcher must leave DISPLAY unset.

## Upstream component audit

The MIT-licensed Winlator Mali source tree contains separable X11/display components. The audited tree includes approximately 102 Java X server core files, 9 Java X connector/stream files, 7 native C transport/drawable files, and 23 native renderer source/header files. These counts describe the upstream tree, not code already integrated into PCGL.

The upstream XServerDisplayActivity depends on Winlator-specific container, menu, notification, HUD, and input-control classes. PCGL does not transplant that Activity or the Winlator launcher UI.

## Remaining integration sequence

1. Port/adapt the remaining X11 core requests and X resource model under the PCGL namespace.
2. Add window/drawable lifecycle events and connect window buffers to the PCGL EGL renderer.
3. Implement MIT-MAGIC-COOKIE-1 authorization and publish DISPLAY only after a real server handshake is ready.
4. Add GLX/DRI3 or another supported Wine rendering path, including image transfer and synchronization.
5. Route keyboard, pointer, touch and gamepad events into the X server input model.
6. Verify a Windows GUI test program on a physical ARM64 Android device.

## Acceptance tests

The protocol-bootstrap code milestone is covered by JVM codec tests. The on-device AF_UNIX self-test is available in DisplayTestActivity and must still be run on a physical Android device.

The game-display milestone is separate and is complete only when a Windows GUI test program connects to PCGL's server, creates a visible window, updates frames, receives pointer and keyboard input, and exits cleanly. CI compilation and setup-handshake tests alone do not satisfy that acceptance test.
