# Vessel proroot Phase 5 — zero-copy presentation and battery pacing

Phase 5 removes avoidable work from the Phase-4 shared-kernel desktop. It does not change the active default backend; UML remains the default until Phase 6.

## API 36+ zero-copy presentation

The fast path is now:

```
Linux app
  -> KWin Wayland compositor
  -> Mesa Freedreno / KGSL
  -> Vessel-owned AHardwareBuffer
  -> native render fence
  -> ASurfaceTransaction_setBufferWithRelease
  -> SurfaceFlinger
```

There is no intermediate EGL texture, framebuffer blit, CPU readback, VNC frame, Termux:X11 frame, or screenshot transport.

Vessel creates a child `ASurfaceControl` from the Android SurfaceView's native window using the public NDK API. KWin imports three Vessel AHardwareBuffers as DMA-BUFs and renders directly into them. The KWin render fence becomes SurfaceFlinger's acquire fence.

The API-36 `ASurfaceTransaction_setBufferWithRelease` function is resolved with `dlsym` rather than becoming a hard ELF import. This keeps the same native library loadable on Android 30–35. The older devices use the existing Phase-4 GPU-only AHB -> EGL -> Android Surface fallback.

The direct buffers request:

```
GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY
```

and never request CPU access.

## Correct buffer ownership

A zero-copy slot moves through:

```
FREE -> RENDERING -> PRESENTED -> release fence -> FREE
```

KWin is never allowed to render into a buffer while SurfaceFlinger may still read it. The API-36 buffer-release callback can run on any thread, so it only queues the release fence and wakes the bridge. Fence waiting remains asynchronous.

The bridge uses generation IDs so a delayed release callback from an old compositor connection can never free a slot from a new connection.

SurfaceControl backpressure is enabled on the API-36 child layer. Vessel already has a bounded triple-buffer producer, so this avoids spending GPU time on an intermediate frame that SurfaceFlinger would immediately drop.

## Adaptive refresh and idle power

User interaction immediately selects the configured desktop ceiling (for example 120 Hz). After 1.8 seconds with no pointer, touch, key, scroll or text input, the desktop drops to at most 60 Hz.

The same target is sent to:

- KWin's Anland RenderLoop through `INPUT_TYPE_DISPLAY_REFRESH`;
- the API-36 SurfaceControl through `ASurfaceTransaction_setFrameRate`;
- the Android Surface on the Android 30–35 fallback.

A single delayed Android Handler check performs the active -> idle transition. Mouse motion only updates a timestamp; it does not cancel/re-post a timer for every event.

When the Android Surface disappears, the API-36 child SurfaceControl is detached and the KWin control connection is closed. KWin enters its fallback/inhibited state instead of continuing to composite an invisible desktop.

## Removed idle work

The Android guest-cursor Choreographer loop now runs only for UML. Proroot/KWin already composites its cursor, so Phase 5 no longer invalidates an Android overlay once per display VSYNC for no reason.

The procfs compatibility layer now binds only global files Android actually hides from Vessel's UID. Readable host procfs files stay direct and require no Java refresher. If only static `/proc/cpuinfo` needs emulation, no refresher thread is started.

The AudioTrack server already blocks in accept/read while idle and creates an AudioTrack only for an active PCM stream, so Phase 5 does not add another audio polling loop.

## Public Android APIs

The zero-copy path uses public NDK SurfaceControl/AHardwareBuffer APIs only. It does not use hidden BufferQueue symbols or Anland's private Android consumer implementation.

References:

- https://developer.android.com/ndk/reference/group/native-activity
- https://developer.android.com/ndk/reference/group/a-hardware-buffer
- https://developer.android.com/media/optimize/performance/frame-rate
- https://github.com/lfdevs/anland-termux
- https://github.com/lfdevs/kwin

## CI acceptance

Phase 5 remains source-only. Push CI:

- compiles Kotlin and unit tests;
- syntax-checks the new SurfaceControl/bridge C++;
- compiles the SurfaceControl source as an Android-30 object and verifies the API-36 function is not a hard undefined import;
- rejects CPU framebuffer read/copy operations in the proroot zero-copy files;
- requires compositor-overlay AHB usage and the adaptive idle policy.

APK assembly remains manual-only.
