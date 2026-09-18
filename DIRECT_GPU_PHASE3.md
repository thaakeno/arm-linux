# Vessel Phase 3 — direct Adreno GPU runtime

Phase 3 establishes one direct graphics contract for the proroot backend:

```
normal ARM64 Linux app
  -> normal Mesa/EGL/GL/Vulkan ABI
  -> Freedreno OpenGL/OpenGL ES or Turnip Vulkan
  -> KGSL
  -> /dev/kgsl-3d0
  -> Adreno
```

VirGL is not part of the proroot rendering path. Zink is not the default and is not used as an application compatibility shim. UML keeps its existing VirGL/AHardwareBuffer path unchanged until the final backend switch.

## Pinned Mesa

As of 2026-09-18 the current `lfdevs/mesa-for-android-container` direct-extraction release is:

- Mesa `26.3.0-devel-20260824`
- Debian 13 / Trixie ARM64 package
- SHA-256 `c014cf66bdbff96417ee30d34f006cf51df64ae04893d599711b0b6b73b52ccf`

The package supports Freedreno + Turnip on Adreno 840 and upstream documents direct PRoot results on Snapdragon 8 Elite Gen 5 / Adreno 840 around `glmark2 3574` and `glmark2-es2 3621`.

The rootfs installer is `tools/vessel_proroot/install_direct_gpu_mesa.sh`. It verifies Debian 13, verifies the exact archive digest, rejects path traversal, overlays the package into normal `/usr` paths, refreshes the rootfs linker cache when possible, verifies the KGSL DRI + Turnip files and writes a version marker.

No Mesa archive is stored in the APK or repository in this phase.

## Global environment, not per-app wrappers

Every future proroot process receives the same variables from `VesselDirectGpuProfile`:

```
MESA_LOADER_DRIVER_OVERRIDE=kgsl
GALLIUM_DRIVER=freedreno
FD_FORCE_KGSL=1
TURNIP_KMD=kgsl
XWAYLAND_FORCE_KGSL_SURFACELESS=1
LIBGL_DRIVERS_PATH=/usr/lib/aarch64-linux-gnu/dri
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
```

The Vulkan ICD path is selected from the actual rootfs and supports the unsuffixed Mesa manifest name as a compatibility candidate.

Production deliberately does **not** set:

- `LD_LIBRARY_PATH`: Mesa lives in normal distro locations.
- `EGL_PLATFORM=surfaceless`: that would break ordinary Wayland window-system selection.
- `MESA_LOADER_DRIVER_OVERRIDE=zink`: OpenGL goes directly through Freedreno.
- forced benchmark/vsync variables: display pacing belongs to the later battery/performance phase.

Pinning `VK_DRIVER_FILES` to Freedreno is intentional: a broken Turnip/KGSL path should fail visibly rather than silently falling back to Lavapipe.

## Activation gate

The proroot PTY now requires:

1. official proroot runtime files;
2. directory rootfs;
3. pinned Mesa marker and direct-GPU files;
4. host `/dev/kgsl-3d0` present and readable+writable by Vessel.

When those conditions are met, terminal shells inherit the exact graphics environment the future KDE session will inherit.

## Android presentation

This phase does not replace or delete Vessel's current Android Surface/AHardwareBuffer code. The existing UML display remains untouched.

Direct KGSL solves Linux rendering. The next desktop/session integration phase will connect the compositor's scanout to Vessel's native Android presentation boundary; it will not introduce VNC, Termux:X11 or per-application presentation patches.

## Sources checked

- Mesa for Android Container: https://github.com/lfdevs/mesa-for-android-container
- release `mesa-26.3.0-devel-20260824`
- Mesa KGSL build configuration uses `-Dfreedreno-kmds=kgsl`, Freedreno Gallium and Freedreno Vulkan/Turnip.
- July 2026 KGSL work fixed surfaceless/Wayland initialization and linear dma-buf sharing for Anland/XWayland.
- Anland Termux 5.13.3 demonstrates the compositor-to-Android model using shared DMA-BUF resources and fences instead of VNC/screenshot transport.
