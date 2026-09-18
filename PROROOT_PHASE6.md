# Vessel proroot Phase 6 — production cutover

Phase 6 makes the shared-kernel runtime the production backend. UML is preserved as an explicit recovery option; it is never selected automatically after a proroot error.

## Default and recovery

```
production: VesselRuntimeBackend -> VesselProrootRuntimeBackend
recovery:   VesselRuntimeBackend -> VesselUmlRuntimeBackend
```

The selected backend is persisted in Vessel settings. Existing installations receive a one-time migration to `proroot`. The System page can switch backends only while Linux is stopped.

A runtime failure never changes this preference. Silent fallback would hide compatibility/performance regressions and is prohibited.

## Production rootfs

The old launcher downloaded a Bookworm ext4 image for UML. Phase 6 introduces a separate Debian 13/Trixie directory-rootfs pipeline:

```
native ARM64 GitHub runner
  -> debootstrap Trixie
  -> normal Plasma/DBus/Pulse/apps
  -> pinned direct-KGSL Mesa
  -> pinned Anland-compatible KWin/XWayland
  -> Phase-6 production marker
  -> tar.zst
  -> 64 MiB verified release chunks
```

The live rootfs is under `filesDir/vessel-proroot/rootfs/debian-arm64`. Existing UML disks are not deleted and remain usable by recovery mode.

The public manifest is the atomic release pointer. It is uploaded only after all chunks exist and can be fetched from GitHub's public CDN.

## Android bootstrap safety

`VesselProrootBootstrapActivity` is the launcher activity.

It verifies:

- manifest schema/runtime/distribution/architecture;
- chunk index, size and SHA-256;
- full compressed archive SHA-256;
- expected tar entry count;
- required production paths;
- official proroot DSO integrity through `desktopReadiness(true)`;
- direct KGSL Mesa and compositor markers.

Extraction never modifies the live rootfs. It uses a sibling staging directory, rejects absolute/traversal paths, defers symlink/hardlink creation until ordinary files are finished, and activates through a same-filesystem rename with rollback.

## Official proroot binaries

The APK build fetches the five unmodified v1.2.8 release assets from coderredlab's official GitHub release and verifies their published digests.

They are included through `nativeLibraryDir`. Gradle keeps their debug symbols so Android packaging does not strip or rewrite the upstream binaries.

## Networking

The directory rootfs uses `/etc/resolv.conf -> /run/resolv.conf`. Before a shell/terminal/desktop launch, Vessel reads DNS servers from Android `ConnectivityManager/LinkProperties` and writes the active servers into the bound runtime directory. VPN/Wi-Fi changes therefore do not require rebuilding the rootfs.

## Android service integration

Proroot does not depend on the UML guest-agent TCP service. App discovery, package operations, system stats and diagnostics execute through `VesselRuntimeBackend.guest()` directly.

The guest agent remains only for UML recovery.

Storage/status/UI are backend-aware:

- proroot: directory rootfs, shared kernel CPU/memory, direct KGSL;
- UML recovery: sparse ext4, vCPU/memory/host-GL recovery controls.

## Acceptance

Phase 6 source CI must prove:

- production factory default is `proroot`;
- no silent fallback branch exists;
- new launcher is the proroot bootstrap;
- official proroot binaries match upstream SHA-256;
- production rootfs/bootstrap scripts are syntax-valid;
- Phase-6 production marker is mandatory;
- Android network-state/DNS integration is present;
- no browser sandbox disabling flags or per-app compatibility patches appear;
- Kotlin/unit/native checks from Phases 2–5 remain green.

A manual APK build additionally verifies that the five proroot binaries inside both debug and release APKs are byte-identical to the official upstream release files.

Phase 6 does not remove UML. Removal, if ever desired, is a separate post-validation decision after real-device production testing.
