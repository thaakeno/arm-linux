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
import re
import sys

p = Path(sys.argv[1])
s = p.read_text()

marker = "Android fallback: signal resourceId=0 semaphore through a queue submit"
if marker in s:
    print("[venus-sync-fix] source already patched")
    raise SystemExit(0)

# virglrenderer 1.3.0 names this command ImportSemaphoreResource100000MESA,
# while some generated symbol output shortens the experimental suffix.  Match
# the actual ImportSemaphoreFdKHR call rather than brittle surrounding text.
pat = re.compile(
    r'(?P<indent>^[ \t]*)if\s*\(\s*vk->ImportSemaphoreFdKHR\s*\(\s*args->device\s*,\s*&import_info\s*\)\s*!=\s*VK_SUCCESS\s*\)\s*\n'
    r'(?P=indent)[ \t]+vkr_cs_decoder_set_fatal\s*\(\s*&ctx->decoder\s*\)\s*;',
    re.M,
)
m = pat.search(s)
if not m:
    # Print the real nearby source on failure so the next diagnosis is useful.
    lines = s.splitlines()
    hits = [i for i, line in enumerate(lines) if "ImportSemaphoreFdKHR" in line]
    if hits:
        i = hits[0]
        lo, hi = max(0, i - 8), min(len(lines), i + 10)
        print("[venus-sync-fix] actual source around ImportSemaphoreFdKHR:", file=sys.stderr)
        for n in range(lo, hi):
            print(f"{n+1:5}: {lines[n]}", file=sys.stderr)
    raise SystemExit("[venus-sync-fix] could not match ImportSemaphoreFdKHR failure block")

indent = m.group("indent")
new = f'''{indent}if (vk->ImportSemaphoreFdKHR) {{
{indent}   if (vk->ImportSemaphoreFdKHR(args->device, &import_info) != VK_SUCCESS)
{indent}      vkr_cs_decoder_set_fatal(&ctx->decoder);
{indent}}} else {{
{indent}   /* Android fallback: signal resourceId=0 semaphore through a queue submit.
{indent}    * Mesa uses resourceId 0 to request an already-signaled temporary
{indent}    * sync-fd payload. Android/Adreno does not expose ImportSemaphoreFdKHR
{indent}    * here, so submitting an empty batch that signals the binary semaphore
{indent}    * provides the same renderer-side state without dereferencing NULL.
{indent}    */
{indent}   if (LIST_IS_EMPTY(&dev->queues)) {{
{indent}      vkr_log("cannot signal imported semaphore: device has no queue");
{indent}      vkr_cs_decoder_set_fatal(&ctx->decoder);
{indent}      return;
{indent}   }}

{indent}   struct vkr_queue *queue =
{indent}      LIST_ENTRY(struct vkr_queue, dev->queues.next, base.track_head);
{indent}   const VkSemaphore semaphore = res_info->semaphore;
{indent}   const VkSubmitInfo signal_submit = {{
{indent}      .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
{indent}      .signalSemaphoreCount = 1,
{indent}      .pSignalSemaphores = &semaphore,
{indent}   }};
{indent}   VkResult result = vk->QueueSubmit(queue->base.handle.queue, 1,
{indent}                                     &signal_submit, VK_NULL_HANDLE);
{indent}   if (result != VK_SUCCESS) {{
{indent}      vkr_log("fallback semaphore signal submit failed (%d)", result);
{indent}      vkr_cs_decoder_set_fatal(&ctx->decoder);
{indent}   }}
{indent}}}'''

s = s[:m.start()] + new + s[m.end():]
p.write_text(s)
print(f"[venus-sync-fix] patched {p}")
PY

echo "[venus-sync-fix] rebuilding virgl_render_server/libvirglrenderer..."
ninja -C "$BUILD_DIR"
ninja -C "$BUILD_DIR" install

touch "$PREFIX_DIR/.venus-thread-worker"
touch "$PREFIX_DIR/.venus-semaphore-fallback"

echo "[venus-sync-fix] installed patched renderer"
echo "[venus-sync-fix] next: cd ~/venus-poc && bash tools/venus_poc/run_venus_uml.sh"
