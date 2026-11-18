# PCGL display backend integration plan

## Current implementation

PCGL owns a native Android `Surface` + EGL/OpenGL ES backend. It creates an EGL context, compiles a small GLES 2.0 shader program, and renders a rotating colored triangle to verify that the app can acquire a Surface and submit GPU frames. The display test is intentionally diagnostic only; the output is not a Windows game window.

The Kotlin boundary is `PcglDisplayBackend`, with `PcglEglSurfaceBackend` as its first implementation. The contract keeps the Android UI separate from the renderer so a future X11-backed implementation can reuse the same lifecycle.

## Wine display endpoint safety

Wine must not receive a fabricated `DISPLAY=:0`. `WineRuntimeEnvironment.apply` only publishes `DISPLAY` when its caller supplies a syntactically valid endpoint after a real X server has completed startup. The current launcher does not yet have such an X server, so the game process is not connected to a working graphical display.

## Upstream component audit

The MIT-licensed Winlator Mali source tree at [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2) contains separable X11/display components. The audited tree includes approximately:

- 102 Java files under the X server core package.
- 9 Java files for the X connector and stream layer.
- 7 native C transport/drawable helper files.
- 23 native renderer source/header files.

These are component counts from the inspected source tree, not code already imported into PCGL. The upstream `XServerDisplayActivity` also depends on Winlator app-specific container, menu, notification, and input-control classes. PCGL will not transplant that Activity or the Winlator launcher UI.

## Integration strategy

1. Port the X server protocol core and connector as a separately namespaced PCGL module, preserving required MIT and third-party notices.
2. Replace app-specific callbacks with PCGL interfaces for screen geometry, window events, process lifecycle, logging, and input.
3. Connect the X server's drawable/window notifications to the PCGL renderer backend.
4. Establish a private per-container Unix-domain X socket inside the runtime filesystem and publish `DISPLAY` only after the listener is accepting connections.
5. Route PCGL touch/keyboard/mouse events into the X server input model. SDL/XInput support remains a separate follow-on.
6. Verify a known small Windows GUI program on a real ARM64 Android device before claiming visible game support.

## Acceptance test

A successful EGL triangle test only validates the Android rendering surface. The X11 milestone is complete only when a Windows GUI test program connects to the PCGL-owned X server, creates a visible window, updates frames, receives pointer and keyboard input, and exits cleanly. CI compilation alone does not satisfy this test.
