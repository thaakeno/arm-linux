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
