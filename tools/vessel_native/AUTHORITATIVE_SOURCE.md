# Vessel native source

The tracked Kotlin/C++/asset files are the authoritative Vessel runtime source.
CI must compile them directly and must not rewrite first-party application source with `alpha*.py` transforms.

The rust-vmm integration patch in `patch_vhost_gpu_android_ahb.py` remains an explicit third-party source integration step for the vendored/upstream vhost-device-gpu build. It is not an application-source monkey patch.

Current latency/correctness invariants:

- Android input delivery is asynchronous and motion-coalesced; UI input callbacks do not block on the vhost-user input socket.
- Pointer rendering may use short host-side prediction while the guest cursor state catches up.
- Repeated unchanged `SET_SCANOUT` calls do not trigger full-frame AHardwareBuffer copies; content changes flow through `FLUSH_RESOURCE` damage rectangles.
- Firefox keeps hardware WebRender enabled without forcibly enabling the WebRender compositor path.
- Debian networking is explicitly configured and probed because Android app sandboxes do not expose the netlink/user-namespace features passt normally prefers.
- PackageKit authorization is scoped to the local Vessel desktop user so Discover does not request a nonexistent guest password.

Runtime-backend invariant:

- Android runtime/session code depends on `VesselRuntimeBackend`; the current active factory remains UML until the proroot acceptance phases are complete.
- `VesselRuntimeController` remains the authoritative UML implementation and is not rewritten by the proroot foundation.
- The proroot scaffold keeps mutable rootfs/runtime state under app-private storage but requires executable runtime DSOs to come from Android `nativeLibraryDir`.
- Linux application compatibility belongs in the shared runtime/session layer. Do not add per-application launch patches as the primary compatibility strategy.

Terminal invariants:

- `app/src/main/cpp/third_party/termux/vessel_termux_pty.c` is authoritative PTY source and is compiled directly; CI must not patch or regenerate it.
- Termux `terminal-view` / `terminal-emulator` stay pinned to 0.118.0 until a deliberate reviewed upgrade.
- Vessel owns terminal tabs and process lifetime. A tab may be removed only after verified cleanup succeeds.
- PTY teardown is scoped by captured PID birth identity + kernel session + Android UID; never kill processes by package name or app UID alone.
- Terminal scrollback belongs to terminal-emulator; do not mirror every output byte into Compose/StateFlow.
- Linux application compatibility belongs to the shared proroot/runtime layer, never per-terminal app launch hacks.

Direct-GPU invariants:

- Proroot graphics uses direct Freedreno OpenGL/ES + Turnip Vulkan over Android KGSL; VirGL remains UML-only.
- `VesselDirectGpuProfile` is the single application-side driver environment. Do not add app-specific Mesa/Vulkan launch wrappers.
- Production Mesa lives in normal Debian `/usr` paths. Do not revive the temporary `/tmp/mesa-*` + global `LD_LIBRARY_PATH` test setup.
- Zink is not the default proroot renderer. It may be used only as an explicit diagnostic/fallback experiment.
- Vulkan is pinned to the Freedreno ICD so Lavapipe cannot silently mask a broken KGSL path.
- `tools/vessel_proroot/direct_gpu_manifest.json` pins the exact Debian-13 Mesa archive and digest; rootfs assembly must verify it before extraction.
- Do not globally force `EGL_PLATFORM=surfaceless`; ordinary Wayland applications must be allowed to choose their native window-system platform.
- Frame pacing, adaptive refresh and idle throttling belong to the performance/battery phase, not benchmark-oriented Mesa environment variables.

Phase-4 proroot desktop/session invariants:

- Plasma and ordinary Linux applications run as a normal `vessel` identity mapped to Vessel's real Android app UID. Fake-root (`-0`) remains for explicit administrative shell operations only.
- The compositor is the single display integration boundary. Applications remain ordinary Wayland/XWayland clients; do not add per-application graphics wrappers.
- KWin's Anland-compatible backend renders through direct Freedreno/KGSL into Vessel-owned DMA-BUF/AHardwareBuffer slots and returns native render fences. No CPU framebuffer, VNC, Termux:X11, or screenshot transport is permitted.
- `VesselProrootDisplayBridge` owns compositor transport/input/clipboard interoperability. `LinuxDesktopView` remains backend-neutral.
- The existing Android native Surface presenter is reused for Phase 4. The final AHB-to-Surface GPU blit is intentionally left for the performance/battery phase to measure and remove or optimize; do not replace it with CPU copying.
- Global application compatibility is validated before Plasma starts: normal-user credentials, `/proc`, `/dev/shm`, memfd, mmap, SCM_RIGHTS, SCM_CREDENTIALS, SO_PEERCRED, fork/exec and session D-Bus.
- Never make Firefox/Chromium work by adding `--no-sandbox`, disabling the Firefox content sandbox, or matching application names in the runtime.
- `/proc` overlays are limited to global files Android may hide. Per-process `/proc/<pid>` entries stay backed by the real Android kernel.
- System D-Bus is a runtime service; the user session bus is created by `dbus-run-session`. Do not require systemd/logind for the basic desktop session.
- Non-PTY background sessions use the native isolated-session launcher and the same PID birth-identity/session cleanup rules as terminal tabs.
