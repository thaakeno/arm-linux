# Vessel Overhaul — Architecture, History, Current State, Failures, and Next-Agent Handoff

> **Status date:** 2026-09-15  
> **Repository:** `thaakeno/arm-linux`  
> **Authoritative working branch:** `arch/vessel-self-contained`  
> **Current branch HEAD observed while writing this document:** `5dca2e8aee89f9e723157de801de512cb52dafed` (`fix: disambiguate virtio input socket send`)  
> **Current protocol:** 39  
> **Current runtime revision in source:** `v39-self-contained-dmabuf-virtio-input-r1`

This file is the handoff document for the Vessel rewrite. It is intentionally detailed. A new agent should read this file before changing architecture, creating another branch, reintroducing a temporary bridge, or trying to “fix” a failure that was already understood.

---

## 1. What Vessel is supposed to become

Vessel is an Android application that runs a persistent ARM64 Debian desktop through User Mode Linux (UML) and presents the Linux desktop directly inside the Android app.

The goal is **not** “Linux in Termux with an Android viewer.” The goal is a **complete Android application** that owns its Linux runtime itself:

```text
Android Vessel app
  ├─ UI / machine controls / terminal / display
  ├─ UML ARM64 Linux kernel
  ├─ persistent Linux disk
  ├─ networking backend
  ├─ VirtIO GPU backend
  ├─ VirGL / virglrenderer / ANGLE host graphics stack
  ├─ native DMA-BUF presentation
  └─ native VirtIO input backend
       ↓
Debian 12 ARM64 guest
  ├─ /dev/dri/card0 + renderD128
  ├─ Mesa VirGL
  ├─ Linux evdev devices from VirtIO input
  ├─ Xorg + libinput
  └─ KDE Plasma
```

The user has repeatedly made these constraints explicit:

- Vessel must eventually be the whole product; **Termux must not be required at runtime**.
- No VNC.
- No screenshot or RGB frame streaming.
- No Termux:X11.
- No software renderer fallback.
- No guest TCP input bridge.
- No temporary Python input daemon as the production input architecture.
- No “fallback path” that silently switches back to old behavior.
- One authoritative working branch; do not spawn many experiment branches.
- The Linux distro image must **not** make the APK multiple gigabytes.
- Linux machine state must persist across app restarts.
- The Linux disk should live in a reusable user-visible location under `Download/LinuxPC` so it is downloaded/prepared once and reused.
- The runtime manager / multi-machine manager is deferred until the core machine is stable.
- Guest Vulkan is deferred for now. The current guest graphics target is OpenGL/VirGL. Android-side Vulkan may still be used internally for DMA-BUF presentation.

The final intended architecture for the current phase is:

```text
DISPLAY
Linux app / KDE Plasma
  → Mesa VirGL
  → virtio-gpu in UML
  → vhost-user-gpu
  → virglrenderer
  → ANGLE / Android GPU stack
  → DMA-BUF scanout FD
  → Vessel native presenter
  → Android Surface

INPUT
Android touch / trackpad / keyboard events
  → Vessel JNI/native sender
  → local same-UID control socket
  → Vessel vhost-user virtio-input backend
  → virtio-input device in UML
  → Linux evdev
  → libinput
  → Xorg / KWin / Plasma
```

Networking is a separate subsystem and must not be a prerequisite for mouse, keyboard, or touchscreen delivery.

---

## 2. Branch policy

The user became frustrated when multiple branches appeared during the rewrite. For this work, the authoritative branch is:

```text
arch/vessel-self-contained
```

Do not create another architecture branch unless the user explicitly asks for one. Historical branches can remain as references, especially `arch/vessel-wlroots-final`, because it contains the older working Plasma bring-up and is valuable for regression comparison. However, new production changes should continue on `arch/vessel-self-contained`.

The old branch is a reference implementation, not the runtime Vessel should return to.

---

## 3. Why the overhaul happened

The older implementation successfully proved a huge amount of the stack, but it was not the architecture we wanted to ship.

The older path used Termux as the runtime host. The broad pipeline was:

```text
Vessel Android UI
  → Termux runtime/controller
  → UML Debian
  → virtio-gpu / VirGL
  → host-side renderer in Termux UID
  → frame transfer / relay
  → Vessel Android presenter
```

This proved that:

- UML ARM64 boots correctly on the target Android device.
- A persistent ext4 Debian disk works.
- UML can expose a VirtIO GPU.
- Debian receives `/dev/dri/card0` and `/dev/dri/renderD128`.
- VirGL capsets are exposed correctly.
- Mesa VirGL works inside Debian.
- virglrenderer + ANGLE reaches the Qualcomm Adreno GPU.
- Xorg can directly own the VirtIO DRM/KMS device.
- KDE Plasma can visibly boot and render inside Vessel.
- touchscreen/uinput devices can be recognized by Linux and Xorg/libinput.

That was an important bring-up milestone. It was never supposed to be the final architecture.

The main problems were that Termux and Vessel were different Android UIDs, which made direct graphics-FD transfer awkward, and the final display path degraded into a CPU-heavy frame-copy transport.

---

## 4. The last known working old desktop architecture

The old, proven Plasma setup ended up using **Plasma X11**, not direct KWin Wayland.

### Why direct Plasma Wayland was abandoned for that phase

We initially tried a direct KWin/Wayland/DRM session. The guest did not run a normal systemd init. Attempts to bolt on `elogind` collided with Bookworm's existing systemd packages. Running `systemd-logind` standalone exposed `login1`/`seat0`, but PAM did not get a proper logind session. KWin 5.27's DRM session handling then fell through its session backends and could not open restricted DRM devices correctly.

The important lesson is that **direct KWin Wayland requires a real seat/session ownership solution**. It should not be resurrected casually by adding more init hacks.

For the working old build we instead used:

```text
KDE Plasma / KWin X11
  → Xorg modesetting driver
  → glamor
  → /dev/dri/card0
  → Mesa VirGL
  → virtio-gpu
```

That path visibly booted Plasma on the phone.

### Xorg details that were proven

The old and current controller use the same general model:

- Xorg `:0`
- `modesetting` driver
- `kmsdev=/dev/dri/card0`
- `AccelMethod=glamor`
- software renderer explicitly rejected
- `LIBGL_ALWAYS_SOFTWARE=0`
- `GALLIUM_DRIVER=virgl`
- libinput for Vessel input devices
- a custom `vessel` desktop user
- manually started udev/dbus because the guest's PID 1 is not systemd

This is still the desktop-session model in the current branch.

---

## 5. Old display path: what worked and what was wrong

The old path genuinely GPU-rendered the Linux desktop through VirtIO GPU/VirGL, but the **final handoff to Android** became the bottleneck.

The bad final transport looked effectively like:

```text
GPU-rendered Linux frame
  → read pixels back to CPU RGB
  → Python processing / copies
  → TCP relay
  → Android CPU buffer
  → upload/blit to Android Vulkan surface
```

This was not literally a screenshot API, but it had the same class of performance problem: every frame became a large CPU pixel blob.

At 1280×720×4 bytes, one full frame is ~3.5 MiB. At high refresh rates, raw frame bandwidth becomes enormous before counting Python copies, socket copies, renderer readback, Android staging, and GPU upload.

The visible consequences on device were:

- Plasma did appear.
- The image was horizontally distorted because the source 1280×720 frame was stretched to the entire portrait Android surface without preserving aspect ratio.
- Desktop interaction felt very slow.
- The cursor did not appear correctly.
- trackpad behavior was broken/unreliable.
- partial/frozen-looking regions could occur because damage updates and buffering/synchronization were not robust.
- the EDID path was effectively 60 Hz even though 120 Hz was desired.

That was the point where the project needed an architecture reset, not another optimization pass on the RGB relay.

---

## 6. Why DMA-BUF became the new display architecture

DMA-BUF allows a graphics buffer to be shared as a file descriptor rather than copying the entire pixel contents through userspace.

The desired model is:

```text
renderer-owned GPU buffer
  → DMA-BUF FD
  → native Android-side import
  → present to Android Surface
```

The key conceptual improvement is that the image remains a graphics buffer instead of becoming a multi-megabyte CPU byte array every frame.

The old cross-app architecture could not simply pass those FDs from Termux to Vessel because Android SELinux and app-UID boundaries made the direct Unix `SCM_RIGHTS` path unreliable/forbidden.

That led to the major design decision:

> Move the FD-critical runtime into Vessel's own Android UID.

That means UML, vhost-device-gpu, virglrenderer, the input backend, and the native presenter are all packaged/started by Vessel itself. Same-UID Unix sockets can then be used for the renderer/display protocol and FD passing.

This is what “self-contained Vessel” means in this repository.

---

## 7. Current self-contained runtime (Protocol 39)

The current branch packages the native runtime inside the APK under `lib/arm64-v8a`.

The runtime bundle includes:

- `libvessel_uml.so` — the ARM64 UML kernel executable packaged as an Android native library/executable.
- `libvessel_stub.so` — UML stub executable.
- `libvessel_umnet.so` — UML networking helper.
- `libvessel_passt.so` — passt networking backend.
- `libvessel_vhost_gpu.so` — Android-targeted rust-vmm `vhost-device-gpu`.
- `libvessel_vhost_input.so` — Vessel's custom Android-targeted vhost-user VirtIO input backend.
- `libvessel_virglrenderer.so` — virglrenderer runtime library.
- `libvessel_epoxy.so` — epoxy dependency repackaged with Vessel SONAME/RPATH.
- `libEGL_angle.so` and `libGLESv2_angle.so` — ANGLE graphics libraries.
- the Vessel native presenter JNI library built by Gradle/CMake.

The build intentionally verifies that the APK contains the runtime but **does not contain the Debian disk image**.

This keeps the APK relatively small compared with bundling a full desktop distro.

---

## 8. Persistent distro / machine storage plan

The user's intended long-term storage design is to keep Linux machines under a user-visible folder so distros do not need to be re-downloaded every app update and do not inflate the APK.

The current implementation hardcodes the first machine to:

```text
/storage/emulated/0/Download/LinuxPC/Vessel-Debian/
```

The persistent disk is:

```text
/storage/emulated/0/Download/LinuxPC/Vessel-Debian/debian-docker.ext4
```

The persistent runtime log is:

```text
/storage/emulated/0/Download/LinuxPC/Vessel-Debian/vessel-runtime.log
```

The controller downloads the compressed Debian image only if a valid large ext4 disk is not already present. It verifies the compressed image by SHA-256, extracts to a `.part` file, then atomically promotes the finished disk.

Current source values:

```text
ROOTFS_URL:
https://github.com/zalexdev/linux-um-arm64/releases/download/prebuilt-20260816/debian-docker.ext4.gz

ROOTFS_SHA256:
2807979f76021fadf1f76f0c827fbe1eab4df51e33bfeca0c840c5181c610be3
```

The long-term manager design is still deferred. Eventually the model should generalize to something like:

```text
Download/LinuxPC/
  Vessel-Debian/
  Ubuntu-24.04/
  Arch-ARM64/
  ...
```

with per-machine metadata. **Do not build the full manager yet unless the user asks.** The user explicitly said to do the runtime manager last.

---

## 9. Memory policy and the old 6 GiB crash concern

An earlier self-contained build allocated **6144 MiB** to UML on the test phone. Because the UML runtime, renderer, Android UI, presenter, and other native components all lived in Vessel's app process/UID, this raised a legitimate Android low-memory risk.

The current controller no longer hardcodes 6 GiB. It computes guest memory as roughly 30% of physical RAM and clamps it to:

```text
minimum: 2048 MiB
maximum: 4096 MiB
```

This is a safer current policy.

However, runtime crash isolation is still incomplete. `VmSessionService` currently has no `android:process=":runtime"` entry in the manifest, so the foreground service and UI are still in the same default Android process. A fatal native crash or process kill can still make the entire app disappear.

A previously discussed hardening step was:

- move the heavy runtime service to a private `:runtime` process;
- add `ApplicationExitInfo` reporting on next launch;
- keep the UI alive when the runtime dies.

**This is not implemented in the current manifest as of this document.** Do not claim otherwise.

---

## 10. Current graphics architecture

The current controller identifies the display transport as:

```text
vhost-user-gpu-dmabuf-same-uid-v1
```

The current renderer description is:

```text
KDE Plasma/Xorg
  → Mesa VirGL
  → vhost-device-gpu
  → virglrenderer
  → ANGLE
  → Adreno
```

### Guest graphics mode

The guest is intentionally **not using Venus/Vulkan yet**.

The native runtime build patches the pinned rust-vmm `vhost-device-gpu` source to:

- remove `VIRTIO_GPU_F_RESOURCE_BLOB` from the advertised feature set;
- set `.use_venus(false)`;
- set `.use_external_blob(false)`.

This keeps the current guest side focused on the already proven VirGL/OpenGL path.

The user explicitly asked not to tackle guest Vulkan during this overhaul phase.

### Important terminology

The Android presenter itself currently uses the Android Vulkan API to import DMA-BUF and blit/present to the Android surface. That does **not** mean the Debian guest is using Vulkan.

So the current distinction is:

```text
Guest application API: OpenGL via Mesa VirGL
Host presentation API: Vulkan on Android
```

When guest Vulkan is implemented later, the intended path is likely Mesa Venus → VirtIO GPU → virglrenderer Venus → Android Vulkan/Adreno. That is a later project.

---

## 11. Current native presenter

`app/src/main/cpp/vessel_native_presenter.cpp` implements the vhost-user-gpu display-side protocol inside Vessel.

It understands, among other messages:

- display info;
- EDID;
- cursor position/show/hide/update;
- DMA-BUF scanout;
- DMA-BUF scanout2/modifier path;
- DMA-BUF update notifications.

It receives DMA-BUF FDs with `SCM_RIGHTS` over the local same-UID Unix socket and imports them into Vulkan.

The presenter requires Android Vulkan support for external-memory FD/DMA-BUF import. It also probes DRM-format-modifier support where available.

A relevant risk discovered by looking at DroidVM's newer work is that some Qualcomm production Vulkan drivers do not expose every raw Linux DMA-BUF import extension in the way desktop Linux expects. DroidVM sometimes needs Turnip/AHardwareBuffer-oriented handling for its native path. Vessel therefore **must be tested on the actual phone** before declaring the DMA-BUF path finished.

Do not silently reintroduce RGB streaming if DMA-BUF import fails. The user explicitly requested no fallback architecture. Diagnose/fix the native import path instead.

---

## 12. Cursor handling

The old build had a missing/incorrect cursor. The new presenter has explicit cursor protocol handling and stores:

- cursor X/Y;
- hotspot X/Y;
- visibility;
- cursor pixels;
- cursor serial/update state.

The Android UI/presenter integration is expected to render that cursor appropriately over the guest display.

This code exists, but the new full self-contained stack has not yet been proven through a complete successful Plasma boot on the target device after the VirtIO-input rewrite. Therefore the correct status is:

> **Cursor support is implemented in the new path but still requires end-to-end on-device validation.**

Do not mark it “fixed” solely because the code compiles.

---

## 13. Input: the old proven bridge and why it was removed

The old Protocol 38 architecture used a host input bridge and a guest input agent.

Conceptually:

```text
Android input
  → Android-side socket
  → host_input_bridge.py
  → guest TCP connection (10.0.2.2:47633)
  → guest command/input agent
  → /dev/uinput
  → Linux evdev
  → libinput
```

That path did create devices named:

- `Vessel Touchscreen`
- `Vessel Trackpad`
- `Vessel Keyboard`

and old runtime logs proved Linux/libinput could see them.

However, it violated the final architecture because input depended on guest networking, Python, a guest uinput daemon, and a bespoke socket protocol.

When Vessel was made self-contained, that input bridge became the biggest source of repeated startup failures.

---

## 14. The 48% input failure ping-pong

Several Protocol 39 builds booted Debian and the GPU successfully, then stopped around 48% with:

```text
[error] Guest input channel did not connect
```

The important lesson is that these were **not GPU failures**.

The device logs showed all of the following working before the failure:

- UML launched.
- Linux kernel booted.
- ext4 root disk mounted and recovered.
- VirtIO GPU initialized.
- VirGL/virgl2 capsets were present.
- virglrenderer initialized EGL 1.5 through ANGLE.
- the renderer vendor was Google/Qualcomm.
- `/dev/dri/card0` and `/dev/dri/renderD128` passed the readiness check.
- Debian reached the interactive shell.

The failure happened because the self-contained rewrite still tried to make the guest input agent connect back through the guest network.

We then tried two incremental fixes:

1. configure guest networking using the `ip` command;
2. replace that dependency with direct Linux network ioctls.

That was still the wrong architectural layer. Input should never have depended on `vec0`, DHCP, `10.0.2.2`, or passt in the first place.

The user explicitly stopped the ping-pong and requested a real VirtIO input device.

---

## 15. Current input architecture: real VirtIO input

The current branch replaces the guest TCP/uinput bridge with a native vhost-user VirtIO input backend.

The current transport identifier is:

```text
virtio-input-vhost-user-same-uid-v1
```

There are three separate virtual input devices/backends:

```text
Vessel Touchscreen
Vessel Trackpad
Vessel Keyboard
```

Each backend has:

- a vhost-user socket consumed by the UML `virtio_uml.device=` transport;
- a same-UID local Unix datagram control socket consumed by the backend;
- Linux VirtIO-input configuration describing device name, bus/product IDs, properties, and supported event bits.

The current UML command attaches all three using VirtIO device ID 18:

```text
virtio_uml.device=<touch socket>:18
virtio_uml.device=<pointer socket>:18
virtio_uml.device=<keyboard socket>:18
```

The GPU remains on VirtIO device ID 16.

The UML kernel build now verifies:

```text
CONFIG_VIRTIO_INPUT=y
CONFIG_VIRTIO_UML=y
CONFIG_DRM_VIRTIO_GPU=y
```

### Event capabilities

The custom backend exposes roughly:

**Touchscreen**

- `INPUT_PROP_DIRECT`
- `BTN_TOUCH`
- `ABS_X`
- `ABS_Y`
- absolute range 0..32767

**Trackpad/pointer**

- `INPUT_PROP_POINTER`
- left/right/middle buttons
- `REL_X`
- `REL_Y`
- wheel/hwheel

**Keyboard**

- EV_KEY key range
- repeat support

The backend pushes standard VirtIO input events into the guest VirtIO queue. The guest should therefore see normal Linux evdev devices, and Xorg/libinput should consume them exactly like physical/virtual Linux input hardware.

This is the correct architectural direction.

---

## 16. Native Android input sender

The Android side now uses `VesselVirtioInput` plus native code in `app/src/main/cpp/vessel_virtio_input.cpp` to send events to the three local control sockets.

The controller no longer uses the production guest Python input agent.

The CI architecture check explicitly verifies that production Android files do not contain the old bridge identifiers such as:

```text
47633
47635
guest_input_agent
TermuxUmlController
com.termux.permission.RUN_COMMAND
VesselFrameTcpBridge
```

The old `guest_input_agent.py` asset is expected to be absent from the production app.

The current branch HEAD (`5dca2e8...`) specifically contains a fix titled:

```text
fix: disambiguate virtio input socket send
```

This is the current code line another agent should continue from.

---

## 17. Current boot sequence

`VesselRuntimeController.startDesktop()` currently performs the following sequence:

```text
1. Verify native runtime files are packaged.
2. Ensure persistent Debian disk exists; download/extract once if needed.
3. Reset/start persistent runtime log.
4. Start all three native VirtIO-input backends.
5. Start native vhost-device-gpu.
6. Start UML Debian with:
     - persistent ext4 disk
     - VirtIO GPU
     - 3× VirtIO input devices
     - umnet/passt networking wrapper
7. Wait for the Debian interactive shell banner.
8. Verify /dev/dri/card0 and /dev/dri/renderD128.
9. Verify all three VirtIO input devices appear in /proc/bus/input/devices.
10. Send a no-op relative event through the Android/native input sender.
11. Verify/prepare KDE Plasma packages.
12. Start udev + dbus manually.
13. Prepare the vessel desktop user and groups.
14. Write Xorg config for VirtIO GPU + libinput devices.
15. Start Xorg on :0 using direct DRM/KMS.
16. Verify glxinfo does not report llvmpipe/softpipe/swrast.
17. Verify xinput sees Vessel Trackpad/Touchscreen/Keyboard.
18. Start Plasma X11 via dbus-run-session.
19. Apply requested display mode/DPI/refresh through xrandr.
20. Wait for the native presenter to report presenting-dmabuf.
21. Mark desktop ready.
```

This sequence is intentionally strict. It should fail loudly rather than silently switch to VNC/software rendering/old input.

---

## 18. Guest shell command protocol bug that was already fixed

One of the early self-contained builds falsely reported that Debian/VirtIO GPU failed even though the guest had booted.

The controller sent a command like:

```text
test -c /dev/dri/card0 && test -c /dev/dri/renderD128
printf '__VESSEL_1__:%s\n' $?
```

The parser was too loose and could mistake the echoed command text for the actual marker result. Also, commands could be sent before the interactive shell was truly ready.

The fix was to:

- wait for the real Debian shell-ready banner;
- use a unique numeric command marker;
- accept only a correctly parsed marker response;
- stop using broken `poweroff` semantics under the custom UML init and use `exit` to shut the UML guest down.

Do not regress this protocol.

---

## 19. Xorg self-kill bug that was already found

The old r3 runtime used a command similar to:

```text
pkill -f '[X]org :0'
```

inside a long shell command that also contained the future `Xorg :0` invocation. The pattern could match and kill the shell running the command itself.

The safe direction is PID-file based cleanup (`/tmp/vessel-xorg.pid`) and exact process handling, not broad `pkill -f` matching.

The current controller already uses `/tmp/vessel-xorg.pid` and explicit startup logic. Avoid reintroducing broad command-line matching.

---

## 20. UI regression that was caused during the rewrite

During the first self-contained rewrite, the proper Vessel UI was accidentally replaced with a stripped-down debug/runtime screen.

The user did **not** request a UI redesign.

The correct UI is the existing Vessel application design with:

- Machine
- Display
- Terminal
- System
- desktop/fullscreen display controls
- touch/trackpad interaction
- runtime status/log information

That UI was restored while retaining the self-contained runtime.

Future runtime work must not replace the UI just because a simpler debug Activity is easier to wire.

---

## 21. Runtime logs / diagnostics

The main persistent runtime log is:

```text
Download/LinuxPC/Vessel-Debian/vessel-runtime.log
```

The controller keeps an in-memory log tail and appends to this file.

Guest-side diagnostics generated during desktop bring-up include:

```text
/tmp/vessel-udev.log
/tmp/vessel-xorg.log
/tmp/vessel-glx.log
/tmp/vessel-xinput.log
/tmp/vessel-plasma.log
```

The UI should surface runtime logs while startup is busy as well as when fully running. An earlier UI bug only refreshed console/log information while `running == true`, so startup failures produced an empty-looking log panel. That behavior was corrected in later builds.

### Android-process crash diagnostics still missing

The Linux runtime log cannot explain an Android LMKD kill, native SIGSEGV, or fatal Java exception if the whole app process disappears.

The next robust diagnostic improvement should include `ApplicationExitInfo` and/or persistent Android crash reporting so the next launch can show why the previous app/runtime process died.

Do not infer “LMKD” or “native crash” solely from the Linux runtime log.

---

## 22. Networking

Networking currently uses `umnet` plus `passt`.

Important architectural rule:

> Networking is for guest internet/connectivity. It must not be used as the input transport or display transport.

The old guest banner says “There is no network” because the minimal/custom guest init does not behave like a normal systemd distro boot. The current controller launches `umnet/passt`, but fresh-machine networking still needs complete on-device validation, especially because `ensurePlasma()` may call `apt-get update` if Plasma is absent.

This creates an important fresh-install risk:

- existing persistent disks that already contain Plasma can proceed without downloading Plasma again;
- a truly fresh disk may need working guest network configuration before the one-time Plasma installation step can succeed.

Do not hide this behind a fallback. Either make guest networking deterministic or use a distro image that already contains the required desktop packages.

---

## 23. Plasma persistence model

The ext4 guest disk is writable and persistent. Plasma packages, user files, configuration, and Linux-side state live on that disk and survive app restarts.

The current runtime may install Plasma once if the required binaries are missing. Because the disk is persistent, this should not happen every boot.

The app update itself should not overwrite the user's ext4 machine disk.

The long-term machine manager should treat the ext4 image as user machine state, not an APK asset.

---

## 24. Current Android service lifecycle limitation

`VmSessionService` is a foreground `specialUse` service, but as of the current manifest it does not declare a separate Android process.

Therefore:

```text
VesselActivity + VmSessionService + JNI/native presenter/runtime
```

can still share the same app process.

This matters because UML, virglrenderer, vhost-device-gpu, and the presenter are heavy native components. A native fatal signal can terminate the whole app UI.

Recommended hardening after core boot works:

```xml
android:process=":runtime"
```

for the runtime service, plus an IPC surface/state interface between UI and runtime.

This should be designed carefully because Surface/native-window ownership, JNI presenter lifecycle, and same-UID FD passing must remain valid. A private process is still the same Android app UID, so it is compatible with the same-UID architectural goal, but it is not a zero-work change.

Do not pretend this is already implemented.

---

## 25. Current build system

The production workflow is:

```text
.github/workflows/vessel-self-contained.yml
```

It runs on pushes to `arch/vessel-self-contained` and can also be manually dispatched.

The workflow:

1. installs Android NDK 29 / Android 36 build tools;
2. installs native host build dependencies;
3. builds the self-contained runtime bundle;
4. checks architecture invariants and rejects legacy bridges;
5. builds APK + unit tests + Android lint;
6. verifies required native runtime files are inside the APK;
7. verifies no distro/rootfs image is bundled;
8. uploads `Vessel-protocol39-self-contained-arm64.apk`.

The workflow artifact is intentionally only the app/runtime, not the Linux machine disk.

---

## 26. Native runtime build details

`tools/vessel_native/build_runtime_bundle_ci.sh` is the main CI native bundle builder.

It does the following:

### UML kernel

It builds the exact UML kernel from source rather than downloading a rolling kernel artifact. This avoids kernel/userspace capability drift.

The produced kernel config is checked for:

```text
CONFIG_VIRTIO_INPUT=y
CONFIG_VIRTIO_UML=y
CONFIG_DRM_VIRTIO_GPU=y
```

### Networking helpers

Pinned/prebuilt `umnet` and `passt` are downloaded with exact SHA-256 verification.

### ANGLE / virglrenderer Android libraries

The build fetches current package metadata from the Termux package repository **at build time** to extract Android-targeted ANGLE and virglrenderer binaries.

This does **not** create a Termux runtime dependency in Vessel.

The resulting shared objects are copied/relabelled into Vessel's native library directory and RPATH/needed entries are rewritten so they resolve inside the APK.

CI checks that no `/data/data/com.termux` RUNPATH leaks into production binaries.

### vhost-device-gpu

The build pins rust-vmm `vhost-device` to:

```text
20fa14c4c56e40a12104794a934dd70dc7642ff2
```

It patches the GPU backend to disable guest Venus/resource blobs for the current VirGL-only phase, then cross-compiles `vhost-device-gpu` for `aarch64-linux-android`.

### Vessel VirtIO input backend

The custom Rust project:

```text
tools/vessel_native/vhost-device-vessel-input/
```

is cross-compiled for Android and packaged as:

```text
libvessel_vhost_input.so
```

This is the production input backend.

---

## 27. CI failures encountered during the overhaul

There were several classes of CI failure. A future agent should distinguish them instead of treating every red run as a new runtime bug.

### 27.1 Missing build dependencies

Earlier native graphics builds hit missing Python build dependencies such as `mako` and `yaml`/PyYAML while building Mesa-related components. These were ordinary CI dependency issues, not Android runtime problems.

### 27.2 Android lint / API-level mismatch

The self-contained storage implementation used APIs requiring API 30 while the app still declared minSdk 29. Lint failed. The app was aligned to minSdk 30 and the display API mismatch was corrected.

### 27.3 Native VirtIO input Rust compile/API errors

While introducing the new vhost-user VirtIO input backend, Rust/vhost-user API details required follow-up fixes, including mapping backend/virtqueue errors into `io::Error` and later disambiguating the input socket send path.

Relevant recent commits on the branch include messages such as:

```text
fix: map vhost and virtqueue errors in native input backend
feat: wire native virtio-input end to end
ci: make architecture verification revision-safe
fix: disambiguate virtio input socket send
```

### 27.4 Stale hard-coded revision check

The uploaded Actions log (`logs_94699106723.zip`) is especially important because it looks like a native build failure at first glance, but the native bundle actually finished successfully.

The log shows:

```text
[vessel-native] runtime bundle ready
```

and lists all expected binaries, including:

```text
libvessel_vhost_input.so
libvessel_vhost_gpu.so
libvessel_uml.so
libvessel_virglrenderer.so
ANGLE libraries
```

The job then failed at the architecture validation line:

```text
grep -F v39-self-contained-dmabuf-virtio-input-r1 \
  app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt
```

with exit code 1.

That was a **CI validation bug**, not a compiler failure. The workflow was later changed to accept runtime revision bumps with a regex such as:

```text
v39-self-contained-dmabuf-virtio-input-r[0-9]+
```

Do not revert to a hard-coded transient revision in architecture validation.

---

## 28. Runtime failures encountered on device

### 28.1 Crash/disappearance at ~36%

An early Protocol 39 build disappeared around the UML-launch stage. At that time the guest was being given 6144 MiB. Because the app/runtime were not isolated, memory pressure/native fatal behavior was difficult to distinguish.

Later logs proved another separate issue: the command parser could report a false guest readiness failure.

Current memory policy is safer (2–4 GiB), but Android exit-reason instrumentation is still needed.

### 28.2 False GPU failure after a successful guest boot

The command marker parser misread echoed shell commands. Fixed as described earlier.

### 28.3 Guest input channel did not connect

This was the repeated 48% failure in r4/r5/r6-style builds. It happened after Debian, VirtIO GPU, VirGL, and the shell were all working.

Do not spend more time fixing that guest TCP bridge. The architecture has now moved to real VirtIO input.

### 28.4 UI log panel appeared empty

The service/UI only refreshed runtime state when fully running, not while busy. Startup failures therefore looked like “no logs.” Later code was changed to expose logs during startup/failure.

### 28.5 Old display distortion and slowness

Caused by frame-sized RGB copy/streaming and stretching the source image to the whole Android surface. The self-contained DMA-BUF presenter is the replacement, not a patch to the old stream.

---

## 29. No-fallback policy

This project now has an explicit architectural policy: if the native path fails, diagnose the native path.

Production code should not silently fall back to:

- VNC;
- RGB/TCP frame transport;
- screenshot copying;
- Termux:X11;
- software GL;
- guest Python input socket bridge;
- old Termux runtime controller.

The controller reports:

```text
softwareFallback = false
```

and CI searches for legacy identifiers.

This is intentional. The user asked for the correct architecture, not a demo that “works somehow.”

---

## 30. What is actually proven vs. what is only implemented

A new agent must distinguish these carefully.

### Proven on real device in older architecture

- UML ARM64 boot.
- persistent Debian ext4 disk.
- six UML CPUs.
- VirtIO GPU enumeration.
- `/dev/dri/card0` and `/dev/dri/renderD128`.
- VirGL / VirGL2 capsets.
- virglrenderer + ANGLE reaching Qualcomm/Adreno.
- Xorg direct DRM/KMS.
- Plasma visible in Vessel.
- old Linux input/uinput devices visible to libinput.

### Proven in newer self-contained builds before the VirtIO-input rewrite

- Vessel can package and execute the UML runtime itself.
- Vessel can start `vhost-device-gpu` itself.
- Debian boots from the persistent disk without Termux hosting it.
- the self-contained GPU stack reaches ANGLE/Qualcomm.
- the self-contained guest sees VirtIO DRM.
- Debian reaches the interactive shell.

### Implemented in current branch but still requiring complete on-device validation

- same-UID DMA-BUF display path all the way into Vessel's native presenter;
- three real vhost-user VirtIO input devices;
- Android-native input sender to the VirtIO input backend;
- Xorg/libinput attachment to those new devices;
- complete Plasma startup after the new input stage;
- cursor correctness on the new presenter;
- stable touchpad semantics;
- aspect-correct display presentation on the actual device;
- actual high-refresh behavior;
- full reboot/reopen persistence with the new runtime;
- fresh-machine Plasma installation/networking.

Do **not** write “everything is fixed” until those items are observed on the phone.

---

## 31. High refresh rate / 120 Hz expectations

The controller currently stores a preferred refresh rate of 120 Hz and can request modes through `xrandr`.

However, real 120 Hz requires the whole chain to cooperate:

- guest mode/EDID;
- Xorg mode;
- renderer cadence;
- DMA-BUF update cadence;
- Android Surface refresh/mode selection;
- device compositor/display panel.

A configured `120f` value is not proof of 120 fps or 120 Hz presentation.

Measure it on-device before claiming success.

---

## 32. Aspect ratio / sizing

The old presenter visibly stretched 1280×720 across the full portrait Android surface.

The new architecture should preserve content geometry and use dynamic guest resolution where possible rather than blindly stretching pixels.

The controller already supports runtime display configuration and `xrandr` mode creation. The app should map touch coordinates to the guest content rectangle correctly if letterboxing/pillarboxing is used.

Trackpad relative motion is naturally independent of aspect ratio, while direct touchscreen absolute coordinates are not.

This must be part of final display/input validation.

---

## 33. Terminal and UI behavior

The app's Terminal page should use the controller's guest command channel, not spawn a second Linux runtime and not depend on Termux.

The runtime command protocol is serialized (`commandLock`) and has explicit command markers. Avoid sending concurrent raw commands around it.

The UI should remain responsive while the runtime boots. Long-running guest operations (e.g. one-time Plasma install) should be reflected through progress/status rather than freezing the main thread.

---

## 34. Security / ownership model

A major reason to move the runtime inside Vessel was ownership and FD locality.

The desired model is:

```text
one Android application UID owns:
  UML host process
  vhost-device-gpu
  vhost-device-input
  virglrenderer
  local Unix sockets
  native presenter
```

This eliminates the old Termux↔Vessel cross-app FD boundary.

A future `:runtime` Android process would still use the same app UID, so it can preserve this model while improving crash isolation.

---

## 35. Why the current architecture is “native” and what is still virtualized

Vessel is native in the practical Android integration sense:

- Android app owns the runtime;
- Android app owns input;
- Android app owns display presentation;
- graphics ultimately target the phone's GPU;
- Linux graphics and input are exposed through VirtIO devices rather than remote-desktop emulation.

But Debian is still running on a **User Mode Linux kernel as a userspace process**. This is not dual-boot Linux and not KVM hardware virtualization.

That is expected for this project.

---

## 36. Deferred work

The following are intentionally **not** part of the immediate “make current machine boot correctly” task:

### Guest Vulkan / Venus

Deferred. Do not enable `RESOURCE_BLOB`, host-visible blob memory, or Venus until the current VirGL/DMA-BUF path is stable.

### Full runtime / distro manager

Deferred. The current machine path is hardcoded to `Vessel-Debian`. The generic manager for multiple distros/machines comes later.

### Direct KWin Wayland

Deferred. The current desktop path is Plasma X11 through Xorg direct DRM. A proper Wayland return requires a real seat/session solution, not a logind hack.

### Broad UI redesign

Not requested. Keep the existing Vessel UI unless the user asks for design work.

---

## 37. Known risks / unresolved issues to address next

These are the highest-value next checks after the current CI build is green.

### A. Prove VirtIO input enumeration on device

After boot, verify `/proc/bus/input/devices` contains all three:

```text
Vessel Touchscreen
Vessel Trackpad
Vessel Keyboard
```

Then verify `xinput list --name-only` sees the same devices after Xorg starts.

If enumeration fails, inspect vhost-user protocol/config/queue behavior. Do not re-add guest TCP input.

### B. Prove native sender events reach Linux

Check that relative motion, buttons, absolute touch, wheel, and keyboard events produce evdev/libinput activity.

If the backend starts but events do not flow, inspect the local Unix datagram sender/backend event queue and vhost-user event-queue notification logic.

### C. Prove DMA-BUF import on the actual Qualcomm driver

The presenter requires Vulkan external-memory DMA-BUF support. If the stock driver rejects a format/modifier/import mode, adapt the native zero-copy path. Do not switch to CPU RGB frames.

### D. Prove Plasma boot after the new input stage

The old environment already proved Xorg/Plasma itself. The current blocker should be treated as a regression in the rewritten control/input/display plumbing until proven otherwise.

### E. Add runtime-process crash isolation and exit diagnostics

Once core boot is working, isolate the runtime in `:runtime` and add `ApplicationExitInfo` so future app deaths have a concrete reason.

### F. Fresh-machine network/install path

Make sure a brand-new downloaded Debian disk can reach package repositories if Plasma packages need one-time installation, or ship/use a prepared distro image with Plasma already present.

---

## 38. Recommended debugging order (avoid ping-pong)

When a new on-device test fails, debug the exact stage and do not make speculative changes to multiple layers at once.

Use this order:

```text
1. Did Vessel process survive?
2. Did native input backend processes start?
3. Did vhost-device-gpu start?
4. Did UML boot?
5. Did ext4 mount?
6. Did /dev/dri appear?
7. Did the three virtio-input devices enumerate?
8. Did Xorg start?
9. Did glxinfo prove VirGL rather than llvmpipe?
10. Did xinput see all Vessel devices?
11. Did Plasma start?
12. Did a DMA-BUF scanout reach the presenter?
13. Did the Android surface display it correctly?
14. Do cursor/touch/trackpad/keyboard work?
15. Does state survive stop/start and app restart?
```

Change only the failing layer unless evidence shows a dependency problem.

---

## 39. Acceptance criteria for calling the overhaul “done”

Do not call the project finished merely because GitHub Actions is green.

For this phase, “done” should mean a real on-device run demonstrates all of the following:

- app launches with the proper existing Vessel UI;
- no Termux installation or Termux runtime is needed;
- persistent Debian disk under `Download/LinuxPC/Vessel-Debian` is reused;
- UML boots reliably;
- VirtIO GPU initializes;
- Mesa uses VirGL, not a software rasterizer;
- all three real VirtIO-input devices enumerate;
- Xorg/libinput attaches them;
- Plasma reaches the visible desktop;
- display arrives via DMA-BUF, not RGB/TCP copying;
- desktop geometry is correct, not stretched;
- mouse cursor is visible and tracks correctly;
- trackpad works;
- direct touch works with correct coordinates;
- keyboard works;
- no legacy bridge/fallback is active;
- stop/start works without corrupting the disk;
- app restart reuses the same machine state;
- a runtime failure produces useful diagnostics instead of an unexplained app disappearance.

High-refresh behavior should be measured separately rather than assumed.

---

## 40. Important files for the next agent

Start with these files:

```text
app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt
app/src/main/java/com/example/dreamlinux/VmSessionService.kt
app/src/main/java/com/example/dreamlinux/VesselActivity.kt
app/src/main/java/com/example/dreamlinux/VesselWaylandPresenter.kt
app/src/main/java/com/example/dreamlinux/VesselVirtioInput.kt

app/src/main/cpp/vessel_native_presenter.cpp
app/src/main/cpp/vessel_virtio_input.cpp
app/src/main/cpp/CMakeLists.txt

tools/vessel_native/vhost-device-vessel-input/src/main.rs
tools/vessel_native/vhost-device-vessel-input/Cargo.toml
tools/vessel_native/build_runtime_bundle_ci.sh

tools/venus_poc/build_uml_smp_android.sh
tools/venus_poc/patch_virtio_uml_vhost_gpu.py

.github/workflows/vessel-self-contained.yml
```

Historical reference files/branches are useful to compare the old proven Plasma/Xorg behavior, but should not be copied wholesale back into production if they reintroduce Termux or bridge transports.

---

## 41. Current branch snapshot for handoff

At the time this document was written:

```text
branch: arch/vessel-self-contained
HEAD:   5dca2e8aee89f9e723157de801de512cb52dafed
commit: fix: disambiguate virtio input socket send
protocol: 39
runtime revision in controller: v39-self-contained-dmabuf-virtio-input-r1
GPU transport: vhost-user-gpu-dmabuf-same-uid-v1
input transport: virtio-input-vhost-user-same-uid-v1
```

The current controller uses:

```text
VirtIO GPU device ID:   16
VirtIO input device ID: 18
```

and currently limits UML guest RAM to 2–4 GiB based on physical RAM.

The production workflow's architecture check is revision-safe and explicitly rejects the old bridge paths.

---

## 42. Final architectural rule of thumb

When deciding whether a proposed fix belongs in Vessel, ask:

> “Does this make Linux look more like normal virtualized hardware, or does it add another custom transport?”

Prefer normal hardware abstractions:

- VirtIO GPU for graphics;
- DMA-BUF for buffer sharing;
- VirtIO input for input;
- normal Linux evdev/libinput;
- persistent block image for machine state;
- passt/umnet only for networking.

Avoid bespoke per-feature bridges unless there is no viable kernel/device abstraction.

The project already spent significant time proving that temporary bridges can make demos work while creating the next bottleneck. The overhaul exists to stop that cycle.

---

## 43. One-sentence handoff

**Continue on `arch/vessel-self-contained`; keep the restored Vessel UI and persistent `Download/LinuxPC` machine model; finish and validate the self-contained UML + VirtIO GPU + DMA-BUF + real VirtIO-input path on device; do not reintroduce Termux, TCP/RGB display, guest TCP/uinput input, software fallbacks, or guest Vulkan yet.**
