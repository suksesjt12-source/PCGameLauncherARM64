# PCGL display backend integration

## Current implementation

PCGL owns a native Android Surface + EGL/OpenGL ES backend. It creates an EGL context and supports a diagnostic renderer plus a CPU RGBA8888 frame upload path. The texture path accepts tightly packed top-left-origin RGBA frames up to 4096x4096 and 64 MiB, copies them into a GLES texture, and displays them on the Android Surface.

The Kotlin boundary is PcglDisplayBackend. PcglWindowFrame validates frame dimensions, memory size and byte count before a frame is submitted. EGL calls must remain serialized on the renderer thread because an EGL context cannot be current on multiple threads at once. X11 socket callbacks only enqueue the latest complete frame; they do not use EGL directly.

## Stage 4.1 — X11 transports

The repository contains a separately namespaced PCGL transport layer:

- AF_UNIX stream socket creation with a runtime-root constrained path.
- A loopback TCP listener bound to 127.0.0.1 on port 6000 (X11 display :0).
- epoll-based client connection monitoring, direct-buffer I/O and ancillary file-descriptor transport for the AF_UNIX path.
- A Kotlin lifecycle adapter and disconnect path.
- JNI symbols and native-library loading namespaced to PCGL.

The connector is adapted from the MIT-licensed XConnector implementation in [GunaCharanTeja/WinlatorMali](https://github.com/GunaCharanTeja/WinlatorMali/tree/bionic-mali-1.2). Its original attribution and license are retained in THIRD_PARTY_NOTICES.md. This is a reusable connector component, not the Winlator Android application or its display Activity.

TCP mode is restricted to loopback and requires a 16-byte MIT-MAGIC-COOKIE-1 value. PcglXAuthority writes an owner-only Xauthority record into the selected per-game workspace. The server rejects empty, incorrect, or unexpected authorization records. The session manager returns the DISPLAY endpoint and Xauthority path only after the listener is active.

## Stage 4.2b — shared X11 window properties

Window properties are stored in one server-owned property table instead of per-client state. XGetProperty and ListProperties therefore read values written by another client on the same server. Property values use canonical big-endian storage and are converted to each client's byte order for 16- and 32-bit formats. Replace, Append and Prepend follow the core X11 type/format rules, mutations are memory-bounded, property data is returned through defensive copies, and deleting a window clears its property records. PropertyNotify is broadcast to every live client that selected PropertyChangeMask on the affected window. Selection ownership and full cross-client resource ownership remain incomplete.

## Stage 4.2a — shared X11 atom registry

The protocol server now uses one server-wide atom registry for every connected client. The 68 X11 core predefined atoms are exposed with their fixed protocol IDs (1–68); dynamically interned atoms start at 69. InternAtom and GetAtomName use the same bidirectional registry, so a client can resolve an atom created by another client. This improves core protocol compatibility but does not imply complete multi-client window/resource ownership.

## Stage 4.2 — X11 protocol bootstrap

PCGL parses X11 byte order and the X11 11.0 setup exchange, returns a 24-bit TrueColor root screen, and bounds input/request memory. TCP mode verifies MIT-MAGIC-COOKIE-1 using a constant-time byte comparison; AF_UNIX test mode accepts only empty authorization records.

The initial read/query set includes GetInputFocus, QueryExtension, ListExtensions, GetGeometry, QueryTree, GetWindowAttributes, InternAtom, GetAtomName and GetImage (ZPixmap). Unknown requests return protocol errors or a null extension result; this is not a complete implementation of the X11 protocol or its extensions.

## Stage 4.3 — software window/drawable path

The PCGL core server has a bounded software drawable store and handles a first subset of X11 core requests:

- CreateWindow, ChangeWindowAttributes (event-mask selection), DestroyWindow, MapWindow, UnmapWindow and ConfigureWindow;
- CreatePixmap / FreePixmap and CreateGC / ChangeGC / FreeGC, with foreground/background state;
- ZPixmap PutImage and GetImage for depth-24 TrueColor using 32 bits per pixel;
- CopyArea, ClearArea and PolyFillRectangle;
- GetGeometry, QueryTree and GetWindowAttributes for registered windows/drawables;
- basic MapNotify, UnmapNotify, DestroyNotify, ConfigureNotify and Expose delivery to the requesting client's selected event masks.

Mapped child windows are composed into a screen-sized RGBA8888 frame, with bounds on drawable dimensions and total allocation. Frames can be uploaded to the Android EGL texture renderer. Local JVM tests cover pixel conversion, drawing, and readback; the on-device protocol tests send actual X11 setup/request packets through AF_UNIX and authenticated TCP.

This provides a software core-image path for compatible X11 clients. It is not a complete X server: full event routing across clients, resource ID ownership, visuals/depth variants, most GC raster operations, background handling, XRender, MIT-SHM, RandR, XInput2, GLX, DRI3/Present and Vulkan WSI are not implemented.

## Stage 4.4 — EGL frame presentation and game launch wiring

PcglDisplayBackend.submitWindowFrame uploads software RGBA8888 frames into an OpenGL ES texture and presents them on the Android Surface. The display Activity keeps EGL operations on its renderer thread and consumes the newest queued X11 frame.

The normal launcher flow now hands a prepared game to the display Activity. After the Android Surface is ready, that Activity starts PcglX11DisplaySession, creates an Xauthority file in the game's container, and only then launches Box64/Wine with the authenticated DISPLAY/XAUTHORITY environment. When the process exits or the display Activity is closed, the session is stopped and its authority file is removed.

This wiring makes the transport reachable in principle, but it has not yet proven that the packaged Bionic Wine client completes the X11 handshake or that a Wine window is accepted by this partial server. A successful CI build does not prove that the X11 window appears on the phone.

## Graphics and input limits

The current core X11 software path is not an accelerated Windows game renderer. In particular:

- GLX, DRI3/Present and Vulkan WSI are not implemented.
- DXVK therefore has no verified X11/Vulkan surface path here.
- XRender, MIT-SHM and RandR are not implemented.
- Full keyboard/pointer event routing into X11 is not implemented.
- The current overlay/helper sends keyboard and mouse events to the Windows guest; it does not expose a tested SDL/XInput virtual gamepad.

WineRuntimeEnvironment deliberately does not force GLX variables for the current software-only server. DISPLAY is set only while an authenticated PCGL display session is active.

## Remaining integration sequence

1. Run the authenticated TCP test on a physical Android device and verify the Xauthority file and loopback connection.
2. Launch a minimal Wine GUI program (for example, a basic text editor) and verify CreateWindow/MapWindow/draw/update/close on the Android Surface.
3. Expand core X11 request/event coverage and add focus, pointer, keyboard, selection, property and multi-client resource ownership.
4. Add XRender/MIT-SHM or an equivalent efficient software path for GUI applications.
5. Implement and validate an accelerated graphics route (GLX, or the DRI3/Present/Vulkan WSI stack needed by the chosen Wine/DXVK route).
6. Route Android touch, keyboard and gamepad axes/buttons into the focused window and verify actual game controls.
7. Run a Direct3D game on a physical ARM64 Mali-G57 device, then profile performance, memory and thermals.

## Acceptance tests

Code-level X11 milestones require JVM unit tests and a successful Android APK build. The on-device diagnostic must verify the AF_UNIX and authenticated loopback TCP paths, including the Xauthority handshake and EGL presentation.

The visible Windows GUI milestone is complete only when a Wine GUI executable connects to PCGL, creates a visible window, updates its content, receives input, and exits cleanly on a physical Android device. The playable Direct3D milestone additionally requires a tested GLX or Vulkan WSI route and a Direct3D game test. CI compilation, protocol unit tests and diagnostic rendering alone do not satisfy either acceptance criterion.
