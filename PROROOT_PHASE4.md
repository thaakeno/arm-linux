# Vessel proroot Phase 4 — desktop and generic Linux compatibility

Phase 4 activates the shared-kernel desktop without adding per-application launch patches.

## Runtime identity

Administrative commands still use proroot fake-root when they explicitly need it. Plasma and normal applications do not. The desktop is launched without `-0`, with the real Android app UID represented in an `/etc/passwd` overlay as user `vessel`. This gives D-Bus and Unix-domain sockets real kernel credentials instead of pretending every desktop process is root.

## Generic compatibility boundary

Before Plasma starts, `vessel-compat-probe` checks the primitives that Firefox, Chromium, desktop services and ordinary Linux utilities expect: readable procfs, `/proc/self/exe`, writable shared memory, memfd, mmap, SCM_RIGHTS, SCM_CREDENTIALS, SO_PEERCRED, fork/exec and a private session D-Bus.

The runtime provides a tiny procfs overlay only for global files Android may hide. Dynamic per-process procfs remains the real host kernel view.

No browser-specific flags or application-name dispatch is part of the backend.

## Desktop session

A pinned Anland-compatible KWin/XWayland build is installed into the Debian 13 rootfs. `vessel-start-plasma` creates one private `dbus-run-session` and launches Plasma Wayland. A separate runtime-owned system D-Bus provides the system bus needed by desktop services.

KWin is wrapped once so only the compositor receives the Anland/surfaceless variables. Ordinary applications inherit normal Wayland/KGSL Mesa variables and are not forced to use a surfaceless EGL platform.

## Display and input

The Android side implements the compositor protocol independently. It allocates three AHardwareBuffer slots, exports their DMA-BUFs to KWin, drives buffer selection through shared memory/eventfd, receives the native render fence, and forwards the selected AHB/fence to Vessel's existing native Surface presenter.

There is no CPU framebuffer transport, VNC, Termux:X11 or per-app display patch. The final AHB-to-Android-Surface GPU blit remains in the current presenter and is explicitly a Phase-5 performance/battery target.

Android pointer, touch, keyboard, text and clipboard events use the same compositor connection. `LinuxDesktopView` stays backend-neutral; UML continues to use VirtIO input unchanged.

## Process lifetime

Desktop/system services use a native non-PTY launcher: fork, setsid, verified /proc birth identity, then exec. Stop/timeout cleanup reuses Vessel's UID + PID start-time + session validation, so stopping Plasma cannot accidentally kill an unrelated reused PID.

## Audio

Vessel keeps its existing Pulse/ALSA -> Android AudioTrack path. The optional KWin/Anland audio socket is deliberately disconnected in this phase to avoid running two audio transports.

## Acceptance gate

Phase 4 is source-only. CI compiles Kotlin and unit tests, syntax-checks the PTY C plus the new native C++ runtime, validates shell scripts/manifests, and rejects browser sandbox-disabling flags. APK assembly remains explicit and is not run by a phase commit.
