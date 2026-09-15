# Vessel architecture

## Current rewrite branch

All self-contained runtime work from this point forward lives on **arch/vessel-self-contained** only.

The goal is to remove the Termux dependency and turn Vessel into the complete host application while preserving the proven UML + VirtIO GPU + VirGL stack. Runtime assets stay outside the APK and persistent distro disks remain reusable between app updates.

## Immediate implementation scope

- Vessel owns and launches the UML host runtime directly under its own Android UID.
- No Termux RUN_COMMAND, no cross-app TCP frame bridge, no screenshot/VNC/software-renderer fallback.
- Keep the proven ARM64 UML guest, VirtIO GPU, VirGL and Android/ANGLE host acceleration path.
- Replace copied RGB scanout transport with same-UID native dma-buf/SCM_RIGHTS presentation.
- Fix cursor transport and Android touch/trackpad/keyboard delivery end-to-end.
- Add dynamic display sizing/DPI/refresh handling rather than stretching a fixed 1280x720 desktop.
- Preserve machine disks separately from the APK so app updates do not redownload Linux.
- Keep persistent machine state and graceful shutdown/restart semantics.
- Machine/distro manager UI is intentionally deferred until the runtime is complete.

## Branch policy

Do not create additional rewrite branches for this effort. Experimental work must stay on `arch/vessel-self-contained` until the user explicitly requests otherwise.
