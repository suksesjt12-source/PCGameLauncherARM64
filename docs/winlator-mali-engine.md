# Winlator Mali engine integration

This repository now has a separate GitHub Actions workflow that builds a complete Mali-capable Windows compatibility engine from the pinned Winlator Mali source revision.

- Upstream: https://github.com/GunaCharanTeja/WinlatorMali
- Ref: `0411a4b602ed00d71bef52e6a7ccb82a8e709b1a` (`bionic-mali-1.2`)
- Upstream license: MIT, with separate licenses for bundled dependencies and runtime content.
- Build workflow: `.github/workflows/pcgl-mali-engine.yml`
- Output artifact: `PCGameLauncher-Mali-Engine-debug`
- Android package for the debug engine: `com.suksesjt12.pcgamelauncher.engine`

The engine build retains the upstream XServer, SDL/window integration, EGL renderer, native Winlator bridge, audio components, and Mali runtime setup. The workflow checks out the pinned source with its native submodule, builds the standard ARM64 debug flavor, and checks that the native renderer and root filesystem asset are packaged.

## Why this is a separate APK at this stage

The existing lightweight launcher and the full Winlator runtime have different process, container, graphics, and audio lifecycles. Replacing the current launcher in-place before the engine is proven would risk losing the existing app's saved data and would create an unverified hybrid. Therefore, the full engine is built as a separately installable companion APK first. The eventual in-app merger is still pending; this stage does not claim that the two apps share a container or that the lightweight launcher's imported EXE entries are automatically transferred.

The full engine still requires first-launch runtime installation and device-side validation. A successful CI build proves compilation and APK packaging only, not compatibility with every Windows game or a guaranteed frame rate on a Helio G100/Mali-G57.
