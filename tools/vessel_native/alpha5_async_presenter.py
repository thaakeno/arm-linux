#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]


def replace_once(rel: str, old: str, new: str) -> None:
    path = ROOT / rel
    text = path.read_text()
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one alpha5 anchor, found {count}: {old[:140]!r}")
    path.write_text(text.replace(old, new, 1))


bridge = "tools/vessel_native/vessel_ahb_bridge.cpp"
replace_once(bridge, "constexpr size_t FRAME_SLOTS = 3;", "constexpr size_t FRAME_SLOTS = 5;")
replace_once(
    bridge,
    "uint64_t g_partial_copy_count = 0;\nauto g_rate_started",
    "uint64_t g_partial_copy_count = 0;\nuint64_t g_drop_count = 0;\nauto g_rate_started",
)
replace_once(
    bridge,
    'logi("connected presenter side channel name=" + name + " protocol=3 slots=3 syncfd=1 damage=1 resourceSync=1");',
    'logi("connected presenter side channel name=" + name + " protocol=3 slots=5 syncfd=1 damage=1 resourceSync=1 dropOnBackpressure=1");',
)
replace_once(
    bridge,
    '''int wait_for_free_slot(uint32_t scanout_id, ScanoutState& state) {
    for (;;) {
        drain_acks();
        for (size_t n = 0; n < FRAME_SLOTS; ++n) {
            const uint32_t idx = (state.next_slot + static_cast<uint32_t>(n)) % FRAME_SLOTS;
            if (!state.slots[idx].busy) { state.next_slot = (idx + 1) % FRAME_SLOTS; return static_cast<int>(idx); }
        }
        if (!connect_socket() || !receive_one_ack(true)) {
            if (g_socket < 0) continue;
            loge("failed waiting for free AHB slot scanout=" + std::to_string(scanout_id));
            return -1;
        }
    }
}
''',
    '''int find_free_slot(ScanoutState& state) {
    // Never block the vhost-user-gpu dispatch thread waiting for Android. ACKs
    // are opportunistically retired; if all five source buffers are still in
    // flight we drop this presentation update and carry its damage forward.
    drain_acks();
    for (size_t n = 0; n < FRAME_SLOTS; ++n) {
        const uint32_t idx = (state.next_slot + static_cast<uint32_t>(n)) % FRAME_SLOTS;
        if (!state.slots[idx].busy) {
            state.next_slot = (idx + 1) % FRAME_SLOTS;
            return static_cast<int>(idx);
        }
    }
    return -1;
}
''',
)
replace_once(
    bridge,
    '''    const int idx = wait_for_free_slot(scanout_id, state);
    if (idx < 0) return RC_SOCKET;
''',
    '''    const int idx = find_free_slot(state);
    if (idx < 0) {
        ++g_drop_count;
        return 0;
    }
''',
)
replace_once(
    bridge,
    '''        logi("producer=" + std::to_string(static_cast<int>(g_frame_count / elapsed)) +
             " fps in_flight<=3 syncfd=1 full=" + std::to_string(g_full_copy_count) +
             " partial=" + std::to_string(g_partial_copy_count));
        g_frame_count = 0;
        g_full_copy_count = 0;
        g_partial_copy_count = 0;
''',
    '''        logi("producer=" + std::to_string(static_cast<int>(g_frame_count / elapsed)) +
             " fps in_flight<=5 syncfd=1 fullCopies=" + std::to_string(g_full_copy_count) +
             " partialCopies=" + std::to_string(g_partial_copy_count) +
             " dropped=" + std::to_string(g_drop_count));
        g_frame_count = 0;
        g_full_copy_count = 0;
        g_partial_copy_count = 0;
        g_drop_count = 0;
''',
)

presenter = "app/src/main/cpp/vessel_ahb_presenter.cpp"
replace_once(presenter, "constexpr uint32_t FRAME_SLOTS = 3;", "constexpr uint32_t FRAME_SLOTS = 5;")
replace_once(presenter, "constexpr uint32_t FRAMES_IN_FLIGHT = 3;", "constexpr uint32_t FRAMES_IN_FLIGHT = 5;")
replace_once(
    presenter,
    '" presentMode=" + (present_mode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO") + " frames=3 producerSyncFd=1");',
    '" presentMode=" + (present_mode == VK_PRESENT_MODE_MAILBOX_KHR ? "MAILBOX" : "FIFO") + " frames=5 producerSyncFd=1 nonBlockingAcquire=1");',
)
replace_once(
    presenter,
    '''        if (!window_) {
            bool ok = true;
            if (producer_fence_fd >= 0) {
                ok = wait_sync_fd(producer_fence_fd);
                close(producer_fence_fd);
            }
            status_ = "frame-ready-waiting-for-surface";
            return send_ack(fd, scanout, slot_index, serial, ok);
        }
''',
    '''        if (!window_) {
            // There is no Android consumer while Display is detached. Do not
            // stall the GPU backend on the producer fence; the same GL queue
            // orders subsequent writes and the next visible frame will catch up.
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            status_ = "frame-ready-waiting-for-surface";
            return send_ack(fd, scanout, slot_index, serial, true);
        }
''',
)
replace_once(
    presenter,
    '''        Frame& frame = frames_[frame_number_++ % FRAMES_IN_FLIGHT];
        if (frame.pending && !finish_frame_locked(fd, frame, true)) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            return false;
        }
        vkWaitForFences(device_, 1, &frame.fence, VK_TRUE, UINT64_MAX);
        vkResetFences(device_, 1, &frame.fence);

        uint32_t image_index = 0;
        VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, frame.acquire, VK_NULL_HANDLE, &image_index);
''',
    '''        // Reap completed submissions without sleeping. If Android/Vulkan
        // still owns all five frame contexts, drop this presentation update
        // instead of blocking the vhost-user-gpu event loop.
        Frame* selected = nullptr;
        for (uint32_t n = 0; n < FRAMES_IN_FLIGHT; ++n) {
            const uint32_t idx = (frame_number_ + n) % FRAMES_IN_FLIGHT;
            Frame& candidate = frames_[idx];
            if (candidate.pending) {
                const VkResult fence_state = vkGetFenceStatus(device_, candidate.fence);
                if (fence_state == VK_SUCCESS) {
                    if (!finish_frame_locked(fd, candidate, false)) {
                        if (producer_fence_fd >= 0) close(producer_fence_fd);
                        return false;
                    }
                } else if (fence_state != VK_NOT_READY) {
                    if (producer_fence_fd >= 0) close(producer_fence_fd);
                    status_ = vk_error("vkGetFenceStatus", fence_state);
                    return false;
                }
            }
            if (!candidate.pending) {
                selected = &candidate;
                frame_number_ = (idx + 1) % FRAMES_IN_FLIGHT;
                break;
            }
        }
        if (!selected) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            status_ = "presenting-ahardwarebuffer-backpressure-drop";
            return send_ack(fd, scanout, slot_index, serial, true);
        }
        Frame& frame = *selected;

        uint32_t image_index = 0;
        VkResult result = vkAcquireNextImageKHR(device_, swapchain_, 0, frame.acquire, VK_NULL_HANDLE, &image_index);
        if (result == VK_NOT_READY || result == VK_TIMEOUT) {
            if (producer_fence_fd >= 0) close(producer_fence_fd);
            status_ = "presenting-ahardwarebuffer-swapchain-drop";
            return send_ack(fd, scanout, slot_index, serial, true);
        }
        vkResetFences(device_, 1, &frame.fence);
''',
)

# Firefox's forced X11/EGL path is a bad fit for this legacy virgl guest. Keep
# hardware WebRender requested, but let Firefox choose the X11 GL path instead
# of forcing the EGL/dmabuf route that has been white-screening on the phone.
controller = "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
replace_once(
    controller,
    "KDE_SESSION_VERSION=5 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl MOZ_X11_EGL=1\\n",
    "KDE_SESSION_VERSION=5 LIBGL_ALWAYS_SOFTWARE=0 GALLIUM_DRIVER=virgl MOZ_WEBRENDER=1\\n",
)
replace_once(
    controller,
    "MOZ_X11_EGL=1 dbus-run-session -- /usr/local/bin/vessel-plasma-session",
    "MOZ_WEBRENDER=1 dbus-run-session -- /usr/local/bin/vessel-plasma-session",
)

service = "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
replace_once(
    service,
    "printf '%s\\n' 'export MOZ_X11_EGL=1' >/etc/profile.d/vessel-gpu.sh",
    "printf '%s\\n' 'unset MOZ_X11_EGL' 'export MOZ_WEBRENDER=1' >/etc/profile.d/vessel-gpu.sh",
)

print("[alpha5] penta-buffered nonblocking AHB presentation + Firefox X11 EGL escape hatch applied")
