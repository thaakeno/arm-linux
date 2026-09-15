# Vessel / Dream Linux Research Handoff

**Status date:** 2026-09-15  
**Branch:** `arch/vessel-wlroots-final`  
**Known-good app build:** GitHub Actions run **#113** (`34928944664`)  
**Known-good branch head before this document:** `6d3cdc33a64a783fbc3afc7552965df5fee1844e` (`Expose Vulkan SurfaceView instead of painting over it`)  
**Purpose:** This is the handoff document future agents must read before changing Vessel. It records the current architecture, what is actually proven on-device, rejected/failed approaches, debugging traps, and the loops we already wasted time on.

---

## 0. Read this before touching anything

The project is no longer at the stage of asking whether rootless Linux, hardware GPU acceleration, or Android presentation are possible. Those pieces have now been proven together on the phone.

The current milestone is real and important:

```text
Debian ARM64 UML
  -> Linux virtio_gpu DRM
  -> VIRTIO_UML / vhost-user
  -> rust-vmm vhost-device-gpu
  -> virglrenderer
  -> ANGLE
  -> Vulkan
  -> physical Qualcomm Adreno 840
  -> vhost-user-gpu RGB scanout update
  -> loopback TCP across Android app sandbox
  -> Vessel native Vulkan presenter
  -> Android SurfaceView
  -> visible Linux kmscube on the phone
```

The user has now visibly seen the rotating Linux `kmscube` inside Vessel. The current runtime log also reaches:

```text
VIRTIO_GPU_DRM_READY
VIRTIO_GPU_FRAME_REACHED_VESSEL
VIRTIO_GPU_VALIDATED_FRAME_REACHED_VESSEL
```

Do **not** reset the architecture because a remaining UI bug, Plasma startup issue, orientation bug, or performance problem appears. The hard low-level path is working.

The next major functional milestone is **normal KDE Plasma Wayland on the same DRM/KMS device**, not another graphics architecture rewrite.

---

# 1. Non-negotiable project requirements

These constraints came directly from the user and should be treated as architecture requirements, not suggestions.

1. Final guest OS is real **Debian ARM64 Linux**, running through **User Mode Linux (UML)** rootlessly on Android.
2. No root requirement.
3. No `/dev/kvm` requirement.
4. No PRoot/chroot pretending to be the final VM architecture.
5. Persistent guest disk is `~/venus-wsi-local/debian-docker.ext4`. **Never clobber, replace, reformat, or silently recreate it.**
6. Target VM configuration is **6 vCPUs and 8 GB RAM**.
7. Final desktop is **KDE Plasma**.
8. Do not replace KDE with Weston, Sway, GNOME, Xfce, etc. Weston may only be used as a temporary diagnostic compositor if absolutely necessary.
9. Real phone GPU acceleration is required. Permanent `llvmpipe`, Lavapipe, software rasterization, VNC rendering, screenshots, or CPU-only desktop transport are not acceptable as the final graphics solution.
10. Vessel is the Android frontend. Do not replace Vessel with Termux:X11 as the final display system.
11. Do not restore the old custom KWin `vesseloutput.so` / frame-export-plugin architecture.
12. Do not make the Android app automatically `git clone`, `git fetch`, `git reset --hard`, or `git clean` the user's runtime repository. Runtime updates are explicit/manual.
13. Start Linux only when the user presses **Start Linux**. Opening Vessel must not silently boot or modify Linux.
14. Preserve the existing persistent environment whenever possible.
15. When testing the phone, distinguish **guest OpenGL/GLES**, **host Vulkan**, and **guest native Vulkan**. Do not call VirGL guest rendering “native guest Vulkan.”

---

# 2. Current architecture, accurately

## 2.1 Android app -> Termux control

`TermuxUmlController.kt` is protocol 38 and intentionally passive until the user requests an action.

Important constants/state:

```text
REQUIRED_PROTOCOL = 38
REQUIRED_RUNTIME_REVISION = v38-virtio-gpu-virgl-adreno
DISPLAY_TRANSPORT = virtio-gpu-rgb-loopback-android-vulkan-v1
CONTROL_PORT = 47631
VNC_PORT = -1
```

When the user presses Start, Vessel invokes Termux's `RUN_COMMAND` service and starts the already-installed:

```text
~/vessel-poc-runtime/tools/venus_poc/vessel_runtime_daemon_v38.py
```

The app deliberately does **not** update the repo. This was changed because the user explicitly hated Vessel fetching/resetting `~/vessel-poc-runtime` on its own.

The runtime uses:

```text
VESSEL_MEM_MB=8192
VESSEL_VCPUS=6
```

## 2.2 Debian guest

The real guest is Debian 12 ARM64 on a User Mode Linux kernel. The phone boot log has proven:

```text
Debian GNU/Linux 12 (bookworm), arm64
running on a User Mode Linux kernel
smp: Brought up 1 node, 6 CPUs
```

The persistent root filesystem is the ext4 image:

```text
~/venus-wsi-local/debian-docker.ext4
```

Current guest init is `/umarm-init`. The current minimal environment says there is no systemd, so starting a full Plasma session will probably require explicit session/dbus/runtime-directory setup rather than assuming a normal systemd login manager exists.

## 2.3 VirtIO GPU in UML

The upstream UML kernel base is from `zalexdev/linux-um-arm64`, pinned around commit:

```text
8897487c52233cd00cf2850008ca068892f1ae91
```

Vessel enabled the necessary UML/DRM pieces, including:

```text
CONFIG_SMP=y
CONFIG_NR_CPUS=8
CONFIG_UML_DMA_EMULATION=y
CONFIG_UML_IOMEM_EMULATION=y
CONFIG_VIRTIO=y
CONFIG_VIRTIO_UML=y
CONFIG_DRM=y
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_DRM_VIRTIO_GPU_KMS=y
```

A crucial kernel-side patch added the vhost-user GPU secondary display socket handoff. Generic UML vhost-user handling did not support the QEMU-style GPU request needed to pass the display side channel. Vessel added support for the equivalent of:

```text
VHOST_USER_GPU_SET_SOCKET = 33
```

and connects the vhost-user GPU backend's `<gpu socket>.display` channel.

On-device proof inside the guest:

```text
/dev/dri/card0
/dev/dri/renderD128
```

and kernel logs:

```text
[drm] features: +virgl +edid -resource_blob -host_visible
[drm] features: +context_init -blob_alignment
[drm] number of scanouts: 16
[drm] number of cap sets: 2
[drm] Initialized virtio_gpu 0.1.0 for virtio-uml.0 on minor 0
```

That is a real Linux DRM device inside the UML guest.

## 2.4 Guest 3D API and renderer

Current guest 3D API is **OpenGL / OpenGL ES**, not native Vulkan.

The current chain is:

```text
Linux OpenGL / GLES application
  -> Mesa VirGL
  -> virtio_gpu
  -> VIRTIO_UML / vhost-user
  -> vhost-device-gpu
  -> virglrenderer
  -> ANGLE
  -> Vulkan
  -> Qualcomm Adreno 840
```

We proved this with a real guest draw + `glFinish()`. The renderer string was effectively:

```text
virgl (ANGLE (Qualcomm, Vulkan 1.4.295 (Adreno (TM) 840 ...
```

and the test returned:

```text
GPU DRAW + glFinish: SUCCESS
```

That is important: actual guest 3D commands traversed VirGL into ANGLE/Vulkan and completed on the physical Adreno GPU.

### Do not misstate this

Current guest API:

```text
OpenGL / OpenGL ES
```

Current host/backend API to the hardware:

```text
Vulkan through ANGLE
```

Current guest native Vulkan / Venus:

```text
NOT IMPLEMENTED on this path yet
```

## 2.5 Why Venus is currently disabled

The pinned rust-vmm `vhost-device-gpu` commit is:

```text
20fa14c4c56e40a12104794a934dd70dc7642ff2
```

It advertises/initializes pieces that imply resource-blob/Venus support, but the relevant blob command path is not truly implemented end-to-end for our use. Therefore Vessel deliberately patches the backend to:

```text
.use_venus(false)
.use_external_blob(false)
```

and removes the unsupported `VIRTIO_GPU_F_RESOURCE_BLOB` advertisement.

This is not a temporary random workaround. It is a correctness rule: **only advertise features the backend actually supports**.

Do not re-enable Venus just because `CAPSET_ID_VENUS` exists in upstream source. Guest native Vulkan should wait until resource-blob/external-blob/vhost-user transport is genuinely supported through the full chain.

## 2.6 ANGLE selection and the Bionic linker trap

We previously tried conventional aliases such as:

```text
libGLESv1_CM.so -> libGLESv1_CM_angle.so
```

This poisoned Android's dynamic-linker namespace. Bionic's version/SONAME handling does not make a filename symlink equivalent to the SONAME expected by dependencies. That caused startup/linker failures before EGL could even initialize.

The correct working solution is:

- keep fake Android system SONAME aliases out of `LD_LIBRARY_PATH`;
- use Termux libepoxy's `epoxy_set_library_path()` to explicitly select the real ANGLE libraries by path;
- keep virglrenderer library path separate and controlled.

Never reintroduce the ANGLE alias trick.

---

# 3. Current display architecture

## 3.1 Why direct dma-buf FD passing across apps did not work

The natural design was:

```text
Termux process -> abstract Unix socket -> Vessel Android process
```

with SCM_RIGHTS carrying dma-buf file descriptors.

We tested direct connection to Vessel's abstract socket and Android returned:

```text
PermissionError(13, 'Permission denied')
```

Android sandbox/SELinux made the cross-app FD path unusable in this architecture.

Do not keep retrying the same abstract-socket trick without a fundamentally different Android IPC mechanism.

## 3.2 Current functional transport

Vessel now uses standard vhost-user-gpu **RGB update** messages for the final display hop:

```text
Linux DRM/KMS scanout
  -> vhost-device-gpu
  -> VHOST_USER_GPU_UPDATE RGB data
  -> vhost_gpu_display_frontend.py
  -> loopback TCP 127.0.0.1:47635
  -> VesselFrameTcpBridge inside Vessel UID
  -> native Vulkan presenter
  -> Android SurfaceView
```

This final cross-sandbox hop is copied. It is **not zero-copy**.

But it is also **not screenshot polling, VNC, or fake Android rendering**. The 3D frame is generated by the real accelerated Linux graphics path, then the final scanout update is transported as RGB damage data because Android prevents the direct cross-app dma-buf FD transfer.

The long-term optimization can replace this copied hop with a sanctioned FD-capable Android IPC if one is found, but that is no longer a correctness blocker.

## 3.3 Raw scanout v2

The first raw-scanout version still produced black buffers even though GPU rendering itself was working.

Root cause: VirGL readback was being requested without the explicit tightly packed stride expected by the backend.

The current patch uses:

```text
stride = width * 4
layer_stride = 0
ctx = 0
```

and suppresses genuinely black readbacks instead of forwarding them and declaring the display healthy.

This gave us a useful invariant:

> A frame should not advance the display-ready milestone unless the RGB payload actually contains visible content.

## 3.4 PIXMAN format bug

We made a bad debugging mistake here: an early version hardcoded a guessed DRM fourcc (`0x34325258`) onto the raw GPU update stream.

That was wrong because QEMU's `VHOST_USER_GPU_UPDATE` protocol defines the update payload as **PIXMAN_x8r8g8b8**. It does not provide an arbitrary DRM fourcc for that message.

The correct current boundary conversion is:

```text
PIXMAN_x8r8g8b8
  -> preserve RGB bytes
  -> force the unused X byte to 0xff (opaque)
  -> describe canonical output as DRM_FORMAT_ARGB8888
```

The fourcc is generated from the string `AR24`, not a magic integer.

Rule for future work: **never guess pixel format semantics from what “looks right.” Read the transport spec.**

## 3.5 The final black-screen bug was Android UI layering

After fixing the GPU buffer and pixel-format path, Vessel still reported Vulkan present success while the user saw black.

The actual final blocker was embarrassingly high-level: the `SurfaceView` itself had:

```kotlin
setBackgroundColor(Color.BLACK)
```

`SurfaceView` owns a separate Surface layer behind the normal app window. Painting the View placeholder opaque black can cover the hole through which the separately composited Surface is supposed to be visible.

The successful current fix is:

```kotlin
surfaceView.setBackgroundColor(Color.TRANSPARENT)
parent.setBackgroundColor(Color.BLACK)
```

so the parent remains a black placeholder before frames exist, but the SurfaceView does not cover the Vulkan surface.

This is the commit that finally made the real rotating `kmscube` visible:

```text
6d3cdc33a64a783fbc3afc7552965df5fee1844e
Expose Vulkan SurfaceView instead of painting over it
```

This is an important debugging lesson: if `vkQueuePresentKHR()` succeeds and frame content is known-good but the screen is still black, **inspect Android composition/layering before rewriting the GPU pipeline again**.

---

# 4. What is proven on the physical phone

The following are not theoretical anymore.

## Proven: real Linux guest

- Debian 12 ARM64 boots.
- UML kernel boots as an ordinary Android-hosted process.
- persistent ext4 root mounts read/write.
- 6 CPUs come online.
- 8 GB memory target boots.

## Proven: guest GPU device

- `/dev/dri/card0` exists.
- `/dev/dri/renderD128` exists.
- Linux `virtio_gpu` DRM initializes.
- VirGL capsets are exposed.

## Proven: real hardware 3D

- Mesa guest selects `virtio_gpu` / VirGL rather than llvmpipe for the tested path.
- actual GLES drawing + `glFinish()` succeeds.
- renderer identifies VirGL backed by ANGLE/Vulkan on Adreno 840.

## Proven: real Linux DRM/KMS frame

- `kmscube` runs directly against `/dev/dri/card0`.
- it generates a standard Linux DRM/KMS scanout.
- the vhost-user GPU display side sees/updates that scanout.

## Proven: frame crosses into Vessel

Runtime reaches:

```text
VIRTIO_GPU_FRAME_REACHED_VESSEL
VIRTIO_GPU_VALIDATED_FRAME_REACHED_VESSEL
```

## Proven: Android Vulkan presenter

- RGB frame reaches Vessel UID.
- native Vulkan staging/upload/blit path runs.
- `vkQueuePresentKHR()` succeeds.
- Android `SurfaceView` displays the Linux image.

## Proven visually

The user saw the actual rotating, colored `kmscube` inside the Vessel Display page. That closes the old “display ready but permanently black” blocker.

---

# 5. What the project is NOT yet

## Not yet KDE Plasma

`kmscube` is a validation producer. It is not the final desktop.

Current daemon metadata intentionally calls this:

```text
compositor = direct DRM/KMS validation
desktopName = VirtIO GPU direct display
```

The next producer should be a normal **KDE Plasma Wayland session**, where normal KWin owns the guest DRM/KMS display.

Important wording distinction:

- **Rejected:** custom KWin/Vessel output plugin, `vesseloutput.so`, frame-export monkey patches, special KWin ABI hacks.
- **Required/normal:** KWin as KDE Plasma Wayland's standard compositor.

Final desired graphics ownership:

```text
KDE Plasma + normal KWin
  -> standard Linux DRM/KMS /dev/dri/card0
  -> VirtIO GPU
  -> existing Vessel display frontend
```

KWin should not know Vessel exists.

## Not yet guest-native Vulkan

`vulkaninfo`/`vkcube` in Debian would not currently represent a supported Venus path. Native guest Vulkan is a later feature.

## Not zero-copy across the Android app boundary

Current Termux -> Vessel frame hop is RGB over loopback TCP. This is a performance optimization target, not a proof-of-concept blocker anymore.

## Not fully polished lifecycle

Switching Machine <-> Display currently destroys/recreates the Android `SurfaceView`. That causes a short state regression from `presenting-*` to `surface-detached` / `surface-attached`, so the UI briefly flashes the 96% startup progress overlay again.

This is a UI/lifecycle bug, not a graphics failure.

---

# 6. Immediate next work, in order

## Priority 1: fix Surface/display-state flicker

Current bug:

- `VesselActivity` renders pages with a `when(page)` branch.
- Leaving the Display tab removes `DesktopPage` from composition.
- its AndroidView/`VncFramebufferView` is destroyed.
- `surfaceDestroyed()` / detach destroys Vulkan surface state and reports `surface-detached:frames-retained`.
- returning to Display creates a new Surface.
- `VmSessionService` only calls the display “VISIBLE” when both:
  - the frame has been validated, and
  - presenter status currently starts with `presenting-`.
- during the ~350 ms status-polling gap after reattach, the app falls back to 96% and re-shows the startup overlay.

Correct product behavior:

1. Latch a durable state such as `displayPipelineValidated` / `everPresentedFrame` once the first real visible frame has been proven.
2. Do **not** downgrade the whole machine to startup progress merely because the Display tab is hidden.
3. Track transient `surfaceAttached` separately from durable `displayPipelineValidated`.
4. Ideally keep the Surface/presenter alive across tab changes; alternatively, allow detach/replay but hide the startup overlay after first success.
5. Reuse the native presenter's retained software frame so reopening Display shows the last frame immediately.

Do not solve this by changing the GPU backend.

## Priority 2: replace kmscube with KDE Plasma Wayland

Requirements:

- normal Plasma Wayland;
- normal KWin DRM backend;
- no custom Vessel/KWin plugin;
- no Weston nesting;
- no Termux:X11 display;
- same `/dev/dri/card0` VirtIO GPU;
- same VirGL acceleration;
- same Vessel display frontend underneath.

Because this Debian image currently says “no systemd,” inspect the actual installed packages and binaries before designing startup. Likely requirements include a user/session D-Bus and correct runtime environment, but do not assume package names or installed versions without checking.

The migration strategy should be simple:

```text
stop kmscube
start Plasma Wayland/KWin against DRM card0
wait for first validated frame
mark desktop ready
```

Do not rewrite the lower GPU/display stack during this step.

## Priority 3: lifecycle/resolution/input polish

After Plasma is visible:

- orientation changes;
- dynamic resolution / DPI;
- correct EDID/mode update;
- app background/foreground;
- Surface recreation without progress flash;
- sleep/resume;
- stress test frame pacing and corruption;
- Plasma touch/trackpad/keyboard behavior;
- pointer scaling;
- Android IME/text entry;
- fullscreen behavior.

## Priority 4: cleanup/productization

- Rename `VncFramebufferView`; it is not VNC anymore.
- Rename old `WaylandPresenter` identifiers where useful; current protocol 38 path is broader than old Wayland bridge naming.
- Remove dead protocol-37/Weston compatibility code only after proving no required component depends on it.
- Keep bounded copyable logs.
- Keep real percent progress only for actual startup, not every Surface reattach.

## Later / optional

- native guest Vulkan via Venus after resource-blob support exists;
- more efficient FD-capable Android IPC/zero-copy frame transport;
- audio;
- clipboard;
- file sharing;
- Android notifications/integration.

---

# 7. Historical path and failures

This section exists specifically so future agents do not repeat old ideas that already consumed time.

## 7.1 Old custom KWin output/plugin path

Earlier architecture tried to make KWin export frames through a Vessel-specific plugin (`vesseloutput.so` / frame-export style integration).

Problems included plugin discovery/loading/ABI/metadata uncertainty, especially around KWin 5.27/Qt5 versus newer plugin examples/metadata conventions. The system often looked “ready” because KWin and relays were alive while **zero desktop frames were actually reaching Android**.

The user explicitly rejected continuing the KWin plugin monkey-patching loop.

Do not return to this design.

## 7.2 “Display ready” did not mean display worked

Old builds repeatedly reached a “display ready” or equivalent state while the Vessel surface stayed black.

That state meant only that processes/sockets/compositor infrastructure had started.

We learned to split display readiness into separate proof boundaries:

```text
1. guest booted
2. DRM GPU initialized
3. hardware renderer initialized
4. producer rendered a frame
5. scanout update was generated
6. frame reached Vessel app
7. pixel contents were validated non-black
8. native Vulkan present succeeded
9. user-visible pixels appeared
```

A future agent must never collapse these into one boolean too early.

## 7.3 Screenshot/CPU framebuffer fallback

At one stage the only reliably visible Linux image inside Vessel came from a screenshot/CPU-framebuffer-style path.

That proved the Android UI could display pixels, but it did **not** prove the desired GPU scanout path. It was useful diagnostically but rejected as the final architecture.

Do not call screenshot visibility proof of GPU display.

## 7.4 Weston detour

The project drifted into a Weston 16 runtime while trying to get a compositor/display path working.

That was architecture drift. The user wants KDE Plasma.

Weston-related work may still exist in history or compatibility code, but it is not the target.

CI also caused confusion here: GitHub runners used software Vulkan/Lavapipe and could not reproduce the phone's real dma-buf/GPU behavior. Strict “GPU-only” display assertions on CI therefore failed for reasons unrelated to the phone hardware path.

Lesson: CI can validate build/contracts/protocol syntax, but phone hardware proof requires phone logs and visual testing.

## 7.5 Termux:X11 confusion

Termux:X11 was used as a temporary viewer/sanity check.

We successfully bridged X11 over TCP/socat and `xdpyinfo` connected, but `glxinfo -B` hung.

Why: direct GLX/DRI3 relies on Unix-domain FD passing for DRM/dma-buf objects. TCP cannot carry those file descriptors.

Therefore:

- X11 TCP connectivity does not prove direct GPU GLX;
- Termux:X11 is not the final Vessel monitor;
- do not waste time trying to validate DRI3 over the TCP bridge.

## 7.6 Direct Termux -> Vessel Unix socket failure

We tested the old abstract Unix socket directly:

```text
\0vessel-wayland-v1
```

Termux received permission denied.

That is why protocol38 uses loopback TCP into a bridge running in the Vessel UID rather than pretending SCM_RIGHTS will work directly cross-app.

## 7.7 kmscube immediately exiting

A first protocol38 test launched `kmscube` daemonized with stdin redirected from `/dev/null`.

`kmscube` polls stdin. EOF/HUP became immediately readable and it interpreted that as interruption, logging:

```text
user interrupted!
```

The fix was to give it a private pipe whose writer remains open across exec, leaving stdin quiet but non-EOF.

Lesson: before redirecting an interactive/polling program to `/dev/null`, inspect its stdin behavior.

## 7.8 False VISIBLE state

Another bug: the app reported `VISIBLE` when:

- a frame reached Vessel; and
- `vkQueuePresentKHR()` succeeded;

but the user still saw black.

Two separate problems existed:

1. raw readback could be black due missing stride;
2. Android UI could cover a correctly presented Surface.

We added non-black content validation and then fixed the SurfaceView layer.

Lesson: a successful Vulkan present call proves queue/swapchain acceptance, **not necessarily human-visible pixels**.

## 7.9 Guessed pixel format

We initially hardcoded XRGB/DRM-format assumptions onto a protocol-defined Pixman RGB update.

That was wrong and caused unnecessary debugging loops.

The final correct fix came only after checking the actual vhost-user-gpu spec.

Rule: no magic format constants without protocol evidence.

## 7.10 Android SurfaceView black background

This was the final major black-screen blocker.

Because the `SurfaceView` is separately composed, the ordinary View's opaque black background covered the surface hole. Making that View transparent exposed the already-working Vulkan output.

This should be one of the first checks next time an Android native Surface is presenting successfully but appears black.

## 7.11 App UI regressions during protocol38 migration

We broke useful old app behavior while replacing the Weston-oriented UI:

- Copy Logs disappeared.
- runtime log card was unbounded and visually overflowed.
- progress percentages disappeared.
- error feedback was delayed/poor when the daemon was absent.

These were later fixed:

```text
c20b0577... Restore bounded copyable runtime logs
cddd7b70... Restore accurate percentage progress UI
```

Future rule: architecture migrations must preserve existing UX features unless intentionally redesigned.

## 7.12 Compose CI failure

An explicit import of Compose `weight` resolved to an internal implementation in the current Compose version and broke compilation.

Fix commit:

```text
f041019de5400a7223b54ebd5e1900b145670f2d
Fix Compose scoped weight import
```

The scoped `Modifier.weight()` API should be used from the proper Row/Column scope without importing the internal symbol.

## 7.13 Runtime missing after disabling auto-update

Once the app correctly stopped mutating `~/vessel-poc-runtime`, the first new APK failed with “protocol 38 runtime missing or outdated” on a phone whose Termux checkout had not yet pulled the new files.

That was expected architecture behavior but bad UX.

Correct update model:

```bash
cd ~/vessel-poc-runtime && git pull --ff-only origin arch/vessel-wlroots-final
```

performed explicitly by the user when required.

The app should make stale-runtime errors obvious, but must not silently reset the repo.

---

# 8. Current important files

## Android

### `app/src/main/java/com/example/dreamlinux/TermuxUmlController.kt`

- protocol 38 controller;
- explicit Start only;
- no git mutation;
- control port 47631;
- validates native protocol/revision/transport;
- 6 vCPU / 8GB runtime.

### `app/src/main/java/com/example/dreamlinux/VmSessionService.kt`

- runtime state polling;
- progress mapping;
- current source of tab-switch “96%” regression because `displayReady` is tied to current `presenting-*` status instead of a latched successful-display milestone.

### `app/src/main/java/com/example/dreamlinux/VesselActivity.kt`

- Compose UI;
- Machine / Display / Terminal / System tabs;
- `when(page)` destroys the Display composition when another tab is selected;
- needs lifecycle cleanup so display does not visually restart when navigating tabs.

### `app/src/main/java/com/example/dreamlinux/VncFramebufferView.kt`

Misleading legacy class name. It is **not VNC**.

It owns the real Android `SurfaceView`, forwards input, and attaches/detaches the native presenter.

Critical current fix:

```text
SurfaceView background = transparent
parent background = black
```

### `app/src/main/java/com/example/dreamlinux/VesselWaylandPresenter.kt`

Loads native presenter and starts the protocol38 TCP bridge.

### `app/src/main/cpp/vessel_wayland_presenter_v3.cpp`

- Android Vulkan instance/surface/swapchain;
- FIFO present;
- triple-flight synchronization;
- software/RGB staging path;
- dma-buf import code still exists for FD-capable paths;
- retains RGB frame while Surface is detached;
- replays retained frame on reattach.

## Termux/runtime

### `tools/venus_poc/run_vessel_virtio_gpu.sh`

Current real runtime entrypoint.

Explicitly documents the working chain and does not use PRoot, Weston, VNC, screenshots, or Termux:X11.

### `tools/venus_poc/build_vhost_device_gpu_termux.sh`

Builds the rust-vmm backend, stages virgl headers, disables unsupported blob/Venus features, and avoids the broken ANGLE alias approach.

### `tools/venus_poc/patch_vhost_device_gpu_raw_scanout.py`

Raw scanout v2 patch:

- standard vhost-user-gpu scanout/update messages;
- explicit readback stride;
- visible-RGB check;
- sends updates only after meaningful frame content exists.

### `tools/venus_poc/vhost_gpu_display_frontend.py`

- vhost-user-gpu display sidecar;
- advertises display info / EDID;
- receives raw GPU updates;
- applies spec-defined Pixman -> opaque ARGB conversion;
- forwards frame messages to Vessel over TCP 47635.

### `tools/venus_poc/vessel_runtime_daemon_v38.py`

- boots/manages current runtime;
- probes DRM/VirGL;
- runs guest command agent;
- currently launches kmscube as the direct DRM/KMS validation producer;
- sets progress milestones.

---

# 9. Current CI/build state

Workflow:

```text
.github/workflows/vessel-native-wayland.yml
```

Current name:

```text
Vessel VirtIO GPU protocol 38 APK
```

It verifies:

- Python runtime scripts compile;
- shell scripts parse;
- protocol/revision markers exist;
- raw scanout v2 stride + non-black checks exist;
- Android controller requires protocol38;
- TCP frame bridge exists;
- visible state is gated on validated content + presenter;
- app no longer git fetches/resets/cleans runtime repo;
- old `Weston 16` UI strings are gone;
- Android app builds, unit tests run, lint runs;
- debug APK artifact uploads.

Known-good run:

```text
#113
run id: 34928944664
conclusion: success
artifact: vessel-protocol38-virtio-gpu-debug
```

The user installed the resulting APK and visually proved kmscube output.

---

# 10. Key commits / chronology

This is not every commit, but it gives future agents the important progression.

```text
41762e44  gpu: add rootless UML virtio-gpu runtime
b3fef64f  gpu: add one-command Termux installer and launcher
01d687fa  ci: validate rootless UML virtio-gpu runtime
6e1d136a  ci: fix display frontend smoke import
7c99fd1b  ci: stop Weston APK rebuilds for unrelated GPU experiments
500e9b9a  ci: publish rolling Vessel virtio-gpu kernel
7d6e5b12  fix: avoid pipefail SIGPIPE in virtio GPU installer
fcbc1c53  fix: make runtime kernel marker check pipefail-safe
22a6ed64  fix: stage virgl public headers for Termux bindgen
74b90428  fix: force classic VirGL flags in Termux vhost GPU backend
d1faf697  fix: select ANGLE explicitly and surface vhost GPU crashes
64f95c13  fix: keep ANGLE aliases out of Android linker namespace
8d4b19c4  fix: stop aliasing ANGLE libraries to Android system SONAMEs

d21b0221  Add raw scanout patch for cross-app Vessel display
7a80cf2c  Build Vessel vhost GPU with raw scanout bridge
94cace36  Route raw GPU scanout to Vessel over loopback TCP
0a0ad465  Use Vessel raw scanout GPU backend for Android bridge
1f6e31ef  Add Vessel TCP to native presenter bridge
b116faa2  Start protocol 38 frame bridge with native presenter
4d7be039  Add protocol 38 virtio GPU runtime daemon
c87e33f1  Wire Vessel app to protocol 38 without automatic repo mutation
828f42f5  Replace Weston service state with protocol 38 VirtIO GPU state
643a3a93  Replace Weston UI with protocol 38 VirtIO GPU surface flow
a69f5100  Build protocol 38 VirtIO GPU Vessel APK
f041019d  Fix Compose scoped weight import
f6df7d97  Keep kmscube alive for direct DRM scanout
c20b0577  Restore bounded copyable runtime logs

eace7bf2  Fix VirGL raw scanout readback and suppress black frames
9ab96d0d  Bump Vessel raw scanout backend to v2
8f25ebb4  Force protocol 38 raw scanout v2 rebuild
9ceb14f3  Validate non-black GPU frames before display ready
f8f792f1  Gate visible state on validated GPU content
cddd7b70  Restore accurate percentage progress UI
006f4ced  Verify raw scanout v2 and validated display progress

b1d89d62  Use spec-defined PIXMAN format conversion for raw scanout
6d3cdc33  Expose Vulkan SurfaceView instead of painting over it
```

The last two commits are what finally turned the black Surface into a visibly correct kmscube image.

---

# 11. Anti-loop rules for future agents

This section is the most important part of this document.

## Rule 1: read history before proposing architecture

Before suggesting a new compositor, KWin plugin, X11 bridge, framebuffer, or display transport:

1. read this file;
2. inspect current branch HEAD;
3. search recent commits;
4. inspect the current runtime/app files;
5. if the user says “we already tried this,” retrieve prior context before arguing.

We repeatedly wasted time because the assistant proposed something that had already failed in an older thread.

## Rule 2: do not re-propose rejected final architectures

Without genuinely new evidence, do not propose as the final desktop/display path:

- custom KWin `vesseloutput.so` or KWin frame-export plugin;
- Weston as the desktop;
- VNC;
- screenshot polling;
- Termux:X11;
- software renderer;
- direct cross-app abstract Unix socket/SCM_RIGHTS from Termux to Vessel;
- PRoot as the VM architecture.

## Rule 3: do not confuse normal KWin with the rejected custom KWin path

Plasma Wayland normally uses KWin. That is fine and expected.

What is rejected is **making KWin Vessel-aware**.

KWin should see a normal DRM/KMS monitor and GPU. Vessel sits below it.

## Rule 4: separate proof boundaries

Never say “display works” because a socket opened or a daemon launched.

Track at least:

```text
guest boot
gpu drm ready
hardware renderer ready
producer alive
scanout exists
frame reached host sidecar
frame reached Android app
content validated
Vulkan present success
human-visible frame
```

This project lost hours because “ready” meant the wrong thing.

## Rule 5: inspect protocol specs before inventing formats

No guessed DRM fourcc, channel order, alpha semantics, stride, modifier, or layout.

If a message comes from QEMU/vhost-user-gpu, read that protocol. If it comes from DRM, use the DRM definitions. If it is Pixman, use Pixman semantics.

## Rule 6: successful GPU APIs can still be hidden by UI

If Vulkan presents successfully but Android shows black:

- inspect SurfaceView transparency;
- z-order/layering;
- Surface lifecycle;
- view backgrounds;
- clipping/Compose container behavior;

before rewriting the renderer.

## Rule 7: do not daemonize blindly with `/dev/null`

Some graphics tools poll stdin. Check source/behavior first.

## Rule 8: do not expect FD-based direct rendering over TCP

X11/DRI3, dma-buf and SCM_RIGHTS require Unix FD passing. A TCP/socat bridge is not equivalent.

## Rule 9: do not reintroduce fake ANGLE SONAMEs

Use epoxy's explicit ANGLE path selection.

## Rule 10: do not enable Venus before blob support exists

The existence of Venus code/capset IDs is not proof that this pinned vhost backend supports the complete path.

## Rule 11: CI is not the phone GPU

GitHub CI can catch compile/lint/protocol regressions. It cannot prove:

- Adreno behavior;
- Android SELinux interaction;
- real dma-buf import;
- SurfaceFlinger layering;
- phone driver frame pacing.

On-device logs + visible result remain authoritative for those parts.

## Rule 12: preserve UX while replacing architecture

Do not accidentally remove:

- Copy Logs;
- bounded log scrolling;
- meaningful percentage progress;
- useful errors;
- explicit Start/Stop semantics.

## Rule 13: never mutate the persistent disk casually

The ext4 image is user state, not a disposable test artifact.

## Rule 14: fetch fresh SHA before GitHub file mutation

The branch moves quickly. Always fetch the current blob SHA before `update_file` to avoid overwriting newer work.

## Rule 15: change one boundary at a time

When lower layers are proven, freeze them.

Example now:

- GPU renderer proven;
- DRM/KMS scanout proven;
- Vessel presentation proven.

Therefore a future Plasma startup failure should be debugged in the Plasma/session layer first, not by replacing VirtIO GPU again.

---

# 12. How far the project has progressed

A useful rough split:

## Core low-level platform: ~90%

Already working/proven:

- rootless UML;
- persistent Debian ARM64;
- 6 vCPU / 8 GB;
- command/control channel;
- NAT networking path;
- Linux input devices;
- real VirtIO GPU DRM;
- real VirGL acceleration;
- host Vulkan/Adreno backend;
- real Linux KMS producer;
- frame transport into Android app;
- native Android Vulkan display;
- visible frame inside Vessel;
- Start/Stop UX;
- copyable bounded logs;
- progress state;
- no automatic repo reset.

## “Daily-usable Dream Linux KDE phone PC”: ~65–70%

The remaining visible gap is large in product terms even though the hard plumbing exists:

- Plasma Wayland startup instead of kmscube;
- stable tab/surface lifecycle;
- orientation/resolution/DPI;
- input polish in a real desktop;
- resume/background behavior;
- artifact/frame pacing stress tests;
- audio/clipboard/file integration;
- cleanup of compatibility naming/code.

The biggest conceptual blocker is solved. The project is no longer “can this architecture display accelerated Linux?” It is now “turn the proven virtual Linux computer into a polished Plasma product.”

---

# 13. Definition of the next success milestone

Do not call the next phase done just because Plasma/KWin starts as a process.

The next success criterion should be:

```text
1. press Start Linux in Vessel
2. Debian boots normally
3. VirtIO GPU reaches ready state
4. normal Plasma Wayland/KWin takes DRM master on /dev/dri/card0
5. a validated non-black Plasma frame crosses the existing display transport
6. Vessel displays the real Plasma desktop
7. mouse/touch/keyboard work
8. switching app tabs does not show startup progress again
9. no VNC / screenshots / Termux:X11 / custom KWin plugin involved
10. stop/start preserves the ext4 installation
```

Once that works, then optimize and polish.

---

# 14. Final mental model

The clean way to think about Vessel now is:

```text
Android/Vessel is the host machine + monitor shell.
Debian UML is the Linux computer.
virtio_gpu is the Linux GPU/display device.
VirGL is the guest 3D command transport.
virglrenderer + ANGLE/Vulkan drive the physical Adreno.
Vessel's display frontend is the virtual monitor cable.
Android SurfaceView is the physical screen endpoint.
```

The rotating cube proved that this model works.

The job from here is **not to reinvent the computer**. It is to boot Plasma on it and make the app behave like a polished product.
