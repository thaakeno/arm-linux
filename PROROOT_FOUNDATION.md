# Vessel proroot runtime foundation

Phase 1 introduces a second runtime seam without changing what Vessel boots today.

## Active behavior

- `VesselRuntimeBackend` is now the Android-facing runtime contract.
- `VesselUmlRuntimeBackend` delegates to the existing `VesselRuntimeController`; the controller itself is intentionally unchanged.
- `VesselRuntimeFactory.createActive()` is hard-locked to UML in this phase.
- `VesselProrootRuntimeBackend` is a non-selectable scaffold. It prepares layout and deterministic launch plans only. It does **not** start a desktop or Linux command yet.

This separation is deliberate: terminal/PTTY and process lifetime are Phase 2, direct GPU/presentation is Phase 3, and desktop/session compatibility comes after those are measurable.

## Why the rootfs is a directory

Android 10+ removes direct execute permission from writable app home directories for apps targeting API 29+. The proroot launcher therefore belongs in the APK's extracted `nativeLibraryDir`, while the mutable glibc rootfs remains ordinary app-private data.

Current phase layout:

```
filesDir/
  vessel-proroot/
    rootfs/
      debian-arm64/          # future directory rootfs
    runtime/
      proroot-tmp/           # PROROOT_TMP_DIR, mode 0700
    volatile/
      tmp/                   # guest /tmp, mode 01777
      run/
        user/0/              # guest XDG_RUNTIME_DIR, mode 0700
      shm/                   # guest /dev/shm, mode 01777
    diagnostics/
      proroot.log            # opt-in only; no continuous logging by default
```

The existing UML sparse ext4 image is not converted or reused. Both backends can therefore coexist safely while the migration is being proven.

## Launch contract

The planned executable is the unmodified upstream `libproroot.so` from `nativeLibraryDir`. The launch plan uses:

- `-r <rootfs>`
- fake root with `-0`
- `--link2symlink`
- `-w /root`
- baseline host binds for `/dev`, `/proc`, `/sys`, `/system`, `/apex`, `/dev/fd`
- Vessel-owned app-private binds for `/tmp`, `/run`, and `/dev/shm`
- shared storage binds only when Android actually grants readable access

The environment explicitly sets `PROROOT_TMP_DIR` and the runtime/linker/stub paths. Host `LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`, `LD_DEBUG`, and `LD_CONFIG_FILE` are stripped before a future launch so Android linker configuration cannot accidentally poison glibc processes.

Normal Linux applications must not get application-specific wrappers. Compatibility fixes belong at this runtime/session boundary.

## proroot binary policy

Phase 1 does **not** add the proprietary binaries to the repository or APK.

When packaging is enabled later, Vessel must ship all five official v1.2.8 files together in `jniLibs/arm64-v8a`, unmodified, and verify the upstream SHA-256 values already recorded in `VesselProrootContract`. Modified proroot binaries may not be redistributed.

## References used for this phase

- coderredlab/proroot README and v1.2.8 contract: https://github.com/coderredlab/proroot
- DSHA runtime implementation and third-party policy: https://github.com/DSH-APP/DSHA
- Android 10 W^X / app-home execution restriction: https://developer.android.com/about/versions/10/behavior-changes-10
- Android-on-Linux Runtime execution architecture: https://github.com/Meapri/android-on-linux
- Vessel's existing direct GPU/proroot device experiments supplied during development

## Phase 1 acceptance boundary

This commit is successful if:

1. Current UML behavior is unchanged.
2. The Android service depends on `VesselRuntimeBackend`, not directly on the UML controller.
3. The proroot rootfs/runtime/volatile layout is deterministic and app-private.
4. `/tmp`, `/run`, and `/dev/shm` have explicit Linux-appropriate semantics.
5. Launch argv/environment can be tested without starting a process.
6. No proroot binary is bundled and no backend preference is switched yet.
