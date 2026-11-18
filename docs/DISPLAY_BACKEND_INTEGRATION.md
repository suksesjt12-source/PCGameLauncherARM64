# PCGL display backend integration

## Current implementation

PCGL owns a native Android `Surface` + EGL/OpenGL ES backend. It creates an EGL context, compiles a small GLES 2.0 shader program, and renders a rotating colored triangle. This is a diagnostic path only; it is not a Windows game window.

The Kotlin boundary is `PcglDisplayBackend`, with `PcglEglSurfaceBackend` as its first implementation.

## Stage 4.1 — X11 transport layer

The repository now contains a separately namespaced PCGL X11 transport module:

- AF_UNIX stream socket creation with a runtime-root constrained path.
- epoll-based client connection monitoring.
- direct-buffer client I/O and ancillary file-descriptor transfer.
- a Kotlin lifecycle adapter exposing connection and request-dispatch callbacks.
- JNI symbols and native-library loading namespaced to PCGL.

The transport is adapted from the MIT-licensed XConnector implementation in [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2). Its original attribution and license are retained in `THIRD_PARTY_NOTICES.md`. This is a reusable connector component, not the Winlator Android application or its display Activity.

**Important boundary:** the X11 protocol server core, window/drawable bridge, GLX/DRI3 frame path, and Android rendering integration are not yet connected. The transport alone is not an X server and does not make Wine GUI applications visible. It intentionally does not publish `DISPLAY`.

## Wine display endpoint safety

Wine must not receive a fabricated `DISPLAY=:0`. `WineRuntimeEnvironment.apply` only publishes `DISPLAY` when its caller supplies a syntactically valid endpoint after a real X server has completed its startup handshake. Until the PCGL protocol server is implemented and verified, the launcher must leave `DISPLAY` unset.

## Upstream component audit

The MIT-licensed Winlator Mali source tree contains separable X11/display components. The audited tree includes approximately 102 Java X server core files, 9 Java X connector/stream files, 7 native C transport/drawable files, and 23 native renderer source/header files. These counts describe the upstream tree, not code already integrated into PCGL.

The upstream `XServerDisplayActivity` depends on Winlator-specific container, menu, notification, HUD, and input-control classes. PCGL does not transplant that Activity or the Winlator launcher UI.

## Remaining integration sequence

1. Port/adapt the X11 protocol core under the PCGL namespace and replace Winlator-only dependencies with PCGL interfaces.
2. Add the X11 display/window event bridge and connect window buffers to the PCGL EGL renderer.
3. Complete per-container socket startup and X11 setup-handshake readiness before publishing `DISPLAY`.
4. Route keyboard, pointer, touch, and later SDL/gamepad events into the X server input model.
5. Verify a small Windows GUI test program on a physical ARM64 Android device.

## Acceptance test

The milestone is complete only when a Windows GUI test program connects to the PCGL-owned X server, creates a visible window, updates frames, receives pointer and keyboard input, and exits cleanly. CI compilation alone does not satisfy this acceptance test.
