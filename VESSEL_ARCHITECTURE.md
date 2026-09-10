# Vessel 2 — rootless ARM64 Linux on Android

Vessel's primary backend is the ARM64 User Mode Linux path that was proven on the target phone. It does **not** use KVM, Gunyah, GenieZone or Android root. The old AVF/Shizuku implementation remains in-tree as a compatibility experiment, but it is not the normal startup path anymore.

## Architecture

```text
Android Vessel APK
  ├─ Compose machine/desktop/terminal/storage UI
  ├─ embedded RFB 3.8 client (touch + keyboard + zoom/pan)
  └─ Termux RUN_COMMAND control
             │
             ▼
Termux vessel_runtime_daemon.py
  ├─ UML console lifecycle + safe shutdown
  ├─ virgl_test_server_android (thread worker)
  ├─ host_relay_direct.py + /dev/umshm control
  ├─ umnet + passt rootless networking
  └─ loopback VNC reverse proxy
             │
             ▼
ARM64 UML Debian
  ├─ persistent ext4 root disk
  ├─ guest_relay_direct.py
  ├─ Mesa 26.2.2 Venus ICD
  ├─ TigerVNC X server :1
  └─ KDE Plasma X11 session
             │
             ▼
Android Qualcomm Vulkan / Adreno GPU
```

### GPU path

The working GPU path is:

```text
Debian Vulkan application
→ Mesa Venus 26.2.2
→ vtest protocol
→ guest relay
→ umshm mapped host allocations + sync proxy
→ host relay
→ virglrenderer Venus
→ Android Vulkan / Turnip / Qualcomm GPU
```

This is not a software Vulkan renderer. On-device enumeration identified the guest device as `Virtio-GPU Venus (Adreno (TM) 840)`. The debugging campaign separately verified queue submission, a bounded submitted-fence wait, GPU clear/copy readback, changing vkcube WSI pixels, shared-memory transport and byte-for-byte host X11 readback.

The last manually verified vkcube presentation is preserved by commit `3230daeaaf3da5f6022f3045c6971b8a0c96cca5` on `fix/venus-local-window-present`. That test was deliberately *not* made the KDE architecture: its local proxy proved the rendered Vulkan frames, while the final desktop uses a guest-local X server to avoid remote X11 round-trip and window-stacking problems.

### Desktop path

KDE Plasma runs against TigerVNC's X server **inside Debian**. That matters. The early prototype made guest X11 calls travel through UML networking to Termux:X11; large `xcb_put_image` traffic, geometry round trips and window ownership made that path fragile. With Xvnc, X11 remains local to the guest and Vessel only transports the final RFB framebuffer.

`umnet` intentionally starts passt with inbound TCP/UDP forwarding disabled. Therefore the APK cannot simply connect to `10.0.2.15:5901`. Vessel uses a guest-initiated reverse tunnel instead: for each APK VNC client, Debian connects to `10.0.2.2:5902`; passt maps the guest gateway to the Android host loopback; the Termux daemon joins that socket to `127.0.0.1:5901` for the APK. The VNC server itself is bound to guest localhost and the Android listener is bound to host localhost.

## Why UML

DroidVM is an excellent reference for product design: integrated display, terminal, VM lifecycle, storage tools, networking, external-display support, guest packaging and a coherent machine manager. Its acceleration path, however, relies on privileged hypervisors/devices such as KVM/Gunyah/GenieZone. Vessel's goal is different: keep the useful VM-manager experience while running the Linux kernel as an ordinary Android process.

ARM64 UML gives Vessel a real Linux guest kernel without asking Android for `/dev/kvm` or root. The project then supplies the pieces UML does not normally have on Android: unprivileged networking through `umnet`/passt and GPU resource/synchronization transport through `umshm` + Venus.

## Startup

The APK requires Termux and Android's `com.termux.permission.RUN_COMMAND` permission. It does not mutate the user's active `~/venus-poc` checkout. On first connection it creates/refreshed a detached runtime worktree at `~/vessel-poc-runtime` from `origin/app/vessel-final`, then starts `tools/venus_poc/vessel_runtime_daemon.py`.

For the current development device the runtime daemon reuses the already-proven binaries and disk under `~/venus-wsi-local`, falling back to `~/uml-test`. It does not rebuild Mesa every start. KDE/TigerVNC packages are installed only when missing.

## Verified vs. still requiring device acceptance

CI must pass Android compilation, unit tests, lint, Python syntax validation and shell syntax validation before this branch is merged. The underlying UML/Venus renderer has already been proven on the target phone, including a visible persistent vkcube.

The new **APK → Termux daemon → guest-local TigerVNC → KDE Plasma** orchestration is new integration code and still requires one end-to-end device acceptance run before it can be described as device-verified. Do not confuse a green APK build with that runtime acceptance test.

## Product direction borrowed from DroidVM

Vessel 2 adopts the good product ideas without copying the root-only backend: machine dashboard, persistent guest lifecycle, built-in graphical console, keyboard/touch controls, terminal, storage visibility, diagnostics, networking status and a single place to manage the Linux machine. Future safe additions include offline disk clone/resize/import/export, shared folders, clipboard sync, port-forwarding controls and Android external-display presentation.

Those future features should only appear as working controls after their backend exists. The UI intentionally does not pretend raw ext4 has qcow2 snapshots or that shared folders already work.
