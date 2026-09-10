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

# Match both virglrenderer variants seen in the wild:
#   vkr_cs_decoder_set_fatal(&ctx->decoder)
#   vkr_context_set_fatal(ctx)
pat = re.compile(
    r'(?P<indent>^[ \t]*)if\s*\(\s*vk->ImportSemaphoreFdKHR\s*\(\s*args->device\s*,\s*&import_info\s*\)\s*!=\s*VK_SUCCESS\s*\)\s*\n'
    r'(?P=indent)[ \t]+(?P<fatal>vkr_(?:cs_decoder_set_fatal\s*\(\s*&ctx->decoder\s*\)|context_set_fatal\s*\(\s*ctx\s*\)))\s*;',
    re.M,
)
m = pat.search(s)
if not m:
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
fatal = m.group("fatal") + ";"
new = f'''{indent}if (vk->ImportSemaphoreFdKHR) {{
{indent}   if (vk->ImportSemaphoreFdKHR(args->device, &import_info) != VK_SUCCESS)
{indent}      {fatal}
{indent}}} else {{
{indent}   /* Android fallback: signal resourceId=0 semaphore through a queue submit.
{indent}    * resourceId 0 means the guest wants an already-signaled temporary
{indent}    * sync-fd payload. Android/Adreno does not expose ImportSemaphoreFdKHR
{indent}    * in this configuration, so avoid the NULL call and produce the same
{indent}    * renderer-side signaled state with an empty queue submit.
{indent}    */
{indent}   if (LIST_IS_EMPTY(&dev->queues)) {{
{indent}      vkr_log("cannot signal imported semaphore: device has no queue");
{indent}      {fatal}
{indent}      return;
{indent}   }}

{indent}   struct vkr_queue *queue = NULL;
{indent}   LIST_FOR_EACH_ENTRY (queue, &dev->queues, base.track_head) {{
{indent}      break;
{indent}   }}
{indent}   if (!queue) {{
{indent}      vkr_log("cannot signal imported semaphore: queue lookup failed");
{indent}      {fatal}
{indent}      return;
{indent}   }}

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
{indent}      {fatal}
{indent}   }}
{indent}}}'''

s = s[:m.start()] + new + s[m.end():]
p.write_text(s)
print(f"[venus-sync-fix] patched {p}")
print(f"[venus-sync-fix] fatal helper: {m.group('fatal')}")
PY

echo "[venus-sync-fix] rebuilding virgl_render_server/libvirglrenderer..."
ninja -C "$BUILD_DIR"
ninja -C "$BUILD_DIR" install

touch "$PREFIX_DIR/.venus-thread-worker"
touch "$PREFIX_DIR/.venus-semaphore-fallback"

echo "[venus-sync-fix] installed patched renderer"
echo "[venus-sync-fix] next: cd ~/venus-poc && bash tools/venus_poc/run_venus_uml.sh"
