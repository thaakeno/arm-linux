#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SRC_ROOT="${SRC_ROOT:-$HOME/.termux-build/virglrenderer-android/src}"
SRC="$SRC_ROOT/src/venus/vkr_queue.c"
BUILD_DIR="${BUILD_DIR:-$HOME/.termux-build/virglrenderer-android/host-build/virglrenderer-build}"
PREFIX_DIR="${PREFIX_DIR:-$PREFIX/opt/virglrenderer-android}"

[ -f "$SRC" ] || {
  echo "[venus-sync-fix] missing extracted source: $SRC" >&2
  echo "[venus-sync-fix] run build_virglrenderer_android_thread.sh once first" >&2
  exit 1
}
[ -d "$BUILD_DIR" ] || {
  echo "[venus-sync-fix] missing build dir: $BUILD_DIR" >&2
  exit 1
}

cp -f "$SRC" "$SRC.before-venus-sync-fix"

python - "$SRC" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

marker = "Android fallback: signal imported sync-fd semaphore through a queue submit"
if marker in s:
    print("[venus-sync-fix] source already patched")
    raise SystemExit(0)

old = '''   if (vk->ImportSemaphoreFdKHR(args->device, &import_info) != VK_SUCCESS)\n      vkr_cs_decoder_set_fatal(&ctx->decoder);'''
if old not in s:
    raise SystemExit("[venus-sync-fix] could not find ImportSemaphoreFdKHR call in vkr_queue.c")

member = "base.track_head" if "&dev->queues, base.track_head" in s else "head"
new = f'''   if (vk->ImportSemaphoreFdKHR) {{\n      if (vk->ImportSemaphoreFdKHR(args->device, &import_info) != VK_SUCCESS)\n         vkr_cs_decoder_set_fatal(&ctx->decoder);\n   }} else {{\n      /* Android fallback: signal imported sync-fd semaphore through a queue submit.\n       * The guest has already waited for the sync fd before issuing resourceId=0,\n       * so this recreates the required signaled temporary payload semantics when\n       * the Android Vulkan driver does not expose VK_KHR_external_semaphore_fd.\n       */\n      if (LIST_IS_EMPTY(&dev->queues)) {{\n         vkr_log("cannot import signaled semaphore: device has no queue");\n         vkr_cs_decoder_set_fatal(&ctx->decoder);\n         return;\n      }}\n\n      struct vkr_queue *queue =\n         LIST_ENTRY(struct vkr_queue, dev->queues.next, {member});\n      const VkSemaphore semaphore = res_info->semaphore;\n      const VkSubmitInfo signal_submit = {{\n         .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,\n         .signalSemaphoreCount = 1,\n         .pSignalSemaphores = &semaphore,\n      }};\n      VkResult result = vk->QueueSubmit(queue->base.handle.queue, 1,\n                                        &signal_submit, VK_NULL_HANDLE);\n      if (result != VK_SUCCESS) {{\n         vkr_log("fallback semaphore signal submit failed (%d)", result);\n         vkr_cs_decoder_set_fatal(&ctx->decoder);\n      }}\n   }}'''

s = s.replace(old, new, 1)
p.write_text(s)
print(f"[venus-sync-fix] patched {p}")
print(f"[venus-sync-fix] queue list member: {member}")
PY

echo "[venus-sync-fix] rebuilding virgl_render_server/libvirglrenderer..."
ninja -C "$BUILD_DIR"
ninja -C "$BUILD_DIR" install

touch "$PREFIX_DIR/.venus-thread-worker"
touch "$PREFIX_DIR/.venus-semaphore-fallback"

echo "[venus-sync-fix] installed patched renderer"
echo "[venus-sync-fix] next: cd ~/venus-poc && bash tools/venus_poc/run_venus_uml.sh"
