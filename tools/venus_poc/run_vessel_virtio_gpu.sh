#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Experimental Vessel path:
#   Debian UML -> Linux virtio_gpu -> VIRTIO_UML/vhost-user
#   -> rust-vmm vhost-device-gpu -> virglrenderer -> ANGLE/Vulkan -> Adreno
#   -> vhost-user-gpu display side channel -> Vessel Android presenter.
#
# No PRoot, no nested Weston and no Termux:X11 are involved.

POC_DIR="${POC_DIR:-$HOME/vessel-poc-runtime}"
UML_DIR="${UML_DIR:-$HOME/venus-wsi-local}"
GPU_PREFIX="${VESSEL_VHOST_GPU_PREFIX:-$PREFIX/opt/vessel-vhost-gpu}"
GPU_BIN="${VESSEL_VHOST_GPU_BIN:-$GPU_PREFIX/bin/vhost-device-gpu}"
GPU_SOCK="${VESSEL_GPU_SOCK:-$PREFIX/tmp/vessel-vugpu.sock}"
GPU_DISPLAY_SOCK="${GPU_SOCK}.display"
GPU_LOG="${VESSEL_GPU_LOG:-$UML_DIR/vessel-vhost-gpu.log}"
DISPLAY_LOG="${VESSEL_GPU_DISPLAY_LOG:-$UML_DIR/vessel-vhost-gpu-display.log}"
INPUT_LOG="${VESSEL_INPUT_LOG:-$UML_DIR/vessel-input-bridge.log}"
VESSEL_VCPUS="${VESSEL_VCPUS:-6}"
VESSEL_MEM_MB="${VESSEL_MEM_MB:-8192}"
VESSEL_WIDTH="${VESSEL_WIDTH:-1280}"
VESSEL_HEIGHT="${VESSEL_HEIGHT:-720}"

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  [ -n "${WATCHDOG_PID:-}" ] && kill "$WATCHDOG_PID" 2>/dev/null || true
  [ -n "${INPUT_PID:-}" ] && kill "$INPUT_PID" 2>/dev/null || true
  [ -n "${DISPLAY_PID:-}" ] && kill "$DISPLAY_PID" 2>/dev/null || true
  [ -n "${GPU_PID:-}" ] && kill "$GPU_PID" 2>/dev/null || true
  rm -f "$GPU_SOCK" "$GPU_DISPLAY_SOCK"
  exit "$rc"
}
trap cleanup EXIT INT TERM

need_file() {
  [ -e "$1" ] || { echo "[vessel-vugpu] missing: $1" >&2; exit 1; }
}

for f in \
  "$POC_DIR/tools/venus_poc/vhost_gpu_display_frontend.py" \
  "$POC_DIR/tools/venus_poc/build_vhost_device_gpu_termux.sh" \
  "$UML_DIR/linux-umshm" \
  "$UML_DIR/stub_exe-umshm" \
  "$UML_DIR/umnet" \
  "$UML_DIR/passt" \
  "$UML_DIR/debian-docker.ext4"; do
  need_file "$f"
done

# Refuse to boot an older kernel that has virtio_gpu but lacks the GPU display
# socket handoff. Search the binary directly: `strings | grep -q` is unsafe with
# pipefail because a successful early grep exit can SIGPIPE strings.
if ! grep -aFq 'Vessel vhost-user-gpu display relay attached' "$UML_DIR/linux-umshm"; then
  echo "[vessel-vugpu] linux-umshm is older than the vhost-user-gpu handoff patch." >&2
  echo "[vessel-vugpu] install the newest 'Vessel UML SMP Kernel' artifact first." >&2
  exit 2
fi

# First run builds the host daemon natively in Termux. Later boots reuse it.
if [ ! -x "$GPU_BIN" ]; then
  echo "[vessel-vugpu] host GPU backend not installed; building it once..."
  bash "$POC_DIR/tools/venus_poc/build_vhost_device_gpu_termux.sh"
fi
need_file "$GPU_BIN"

# Only stop Vessel processes that can collide with this exact disk/runtime.
stale_uml_pids=()
for proc in /proc/[0-9]*; do
  pid="${proc##*/}"
  [ "$pid" = "$$" ] && continue
  [ -r "$proc/cmdline" ] || continue
  cmd="$(tr '\0' ' ' <"$proc/cmdline" 2>/dev/null || true)"
  case "$cmd" in
    *linux-umshm*ubd0=debian-docker.ext4*)
      cwd="$(readlink "$proc/cwd" 2>/dev/null || true)"
      if [ "$cwd" = "$UML_DIR" ] || [[ "$cmd" == *"$UML_DIR/linux-umshm"* ]]; then
        echo "[vessel-vugpu] stopping stale UML pid=$pid"
        kill -TERM "$pid" 2>/dev/null || true
        stale_uml_pids+=("$pid")
      fi
      ;;
  esac
done
for pid in "${stale_uml_pids[@]}"; do
  for _ in $(seq 1 40); do
    kill -0 "$pid" 2>/dev/null || break
    sleep .1
  done
  kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
done

pkill -f '[v]host-device-gpu' 2>/dev/null || true
pkill -f '[v]host_gpu_display_frontend.py' 2>/dev/null || true
pkill -f '[h]ost_input_bridge.py' 2>/dev/null || true
pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
pkill -f '[h]ost_relay_wayland.py' 2>/dev/null || true
rm -f "$GPU_SOCK" "$GPU_DISPLAY_SOCK"
mkdir -p "$UML_DIR" "$(dirname "$GPU_SOCK")"
: >"$GPU_LOG"
: >"$DISPLAY_LOG"
: >"$INPUT_LOG"

# The display side channel must already be listening when UML sends
# VHOST_USER_GPU_SET_SOCKET to the daemon.
python3 "$POC_DIR/tools/venus_poc/vhost_gpu_display_frontend.py" \
  --socket "$GPU_DISPLAY_SOCK" \
  --width "$VESSEL_WIDTH" \
  --height "$VESSEL_HEIGHT" \
  >"$DISPLAY_LOG" 2>&1 &
DISPLAY_PID=$!
for _ in $(seq 1 100); do
  [ -S "$GPU_DISPLAY_SOCK" ] && break
  kill -0 "$DISPLAY_PID" 2>/dev/null || {
    echo "[vessel-vugpu] display frontend died during startup" >&2
    cat "$DISPLAY_LOG" >&2 || true
    exit 1
  }
  sleep .05
done
[ -S "$GPU_DISPLAY_SOCK" ] || {
  echo "[vessel-vugpu] display sidecar socket did not appear" >&2
  exit 1
}

ANGLE_PREFIX="$PREFIX/opt/angle-android/vulkan"
ANGLE_SHIM="$GPU_PREFIX/angle-shim"
VIRGL_LIB="$PREFIX/opt/virglrenderer-android/lib"
export LD_LIBRARY_PATH="$ANGLE_SHIM:$VIRGL_LIB:${LD_LIBRARY_PATH:-}"
export EGL_PLATFORM="${EGL_PLATFORM:-surfaceless}"
export RUST_LOG="${RUST_LOG:-debug}"
export RUST_BACKTRACE="${RUST_BACKTRACE:-1}"

# virglrenderer-android's bundled libepoxy does not select ANGLE merely because
# ANGLE is installed. Its vtest --angle-vulkan path calls the Termux-specific
# epoxy_set_library_path() hook explicitly. vhost-device-gpu bypasses vtest, so
# do the same before VirGL lazily initializes EGL. Use dlsym in a tiny preload
# helper so this remains compatible if the symbol ever moves between DSOs.
ANGLE_SELECT_SO="$GPU_PREFIX/lib/libvessel-angle-select.so"
ANGLE_SELECT_C="$GPU_PREFIX/lib/vessel-angle-select.c"
mkdir -p "$GPU_PREFIX/lib"
if [ ! -s "$ANGLE_SELECT_SO" ]; then
  command -v clang >/dev/null 2>&1 || {
    echo "[vessel-vugpu] clang is required to build the ANGLE selector helper" >&2
    exit 1
  }
  cat >"$ANGLE_SELECT_C" <<'EOF'
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef void (*epoxy_set_library_path_fn)(const char *);

__attribute__((constructor))
static void vessel_select_angle(void)
{
    const char *path = getenv("VESSEL_ANGLE_PATH");
    if (!path || !*path)
        return;

    dlerror();
    void *sym = dlsym(RTLD_DEFAULT, "epoxy_set_library_path");
    const char *err = dlerror();
    if (!sym || err) {
        fprintf(stderr,
                "[vessel-angle] epoxy_set_library_path unavailable: %s\n",
                err ? err : "symbol not found");
        return;
    }

    ((epoxy_set_library_path_fn)sym)(path);
    fprintf(stderr, "[vessel-angle] selected ANGLE libraries: %s\n", path);
}
EOF
  clang -shared -fPIC -O2 "$ANGLE_SELECT_C" -o "$ANGLE_SELECT_SO" -ldl
fi

GPU_LD_PRELOAD="$ANGLE_SELECT_SO${LD_PRELOAD:+:$LD_PRELOAD}"
VESSEL_ANGLE_PATH="$ANGLE_PREFIX" \
EPOXY_USE_ANGLE=1 \
LD_PRELOAD="$GPU_LD_PRELOAD" \
"$GPU_BIN" \
  --socket-path "$GPU_SOCK" \
  --gpu-mode virglrenderer \
  --capset virgl,virgl2 \
  --use-egl true \
  --use-glx false \
  --use-gles true \
  --use-surfaceless true \
  >"$GPU_LOG" 2>&1 &
GPU_PID=$!

for _ in $(seq 1 120); do
  [ -S "$GPU_SOCK" ] && break
  kill -0 "$GPU_PID" 2>/dev/null || {
    echo "[vessel-vugpu] vhost-device-gpu died during startup" >&2
    tail -200 "$GPU_LOG" >&2 || true
    exit 1
  }
  sleep .05
done
[ -S "$GPU_SOCK" ] || {
  echo "[vessel-vugpu] vhost-user GPU socket did not appear" >&2
  tail -200 "$GPU_LOG" >&2 || true
  exit 1
}

# If the daemon dies after the guest connects (the failure mode during capset
# negotiation), surface its host log immediately in the same terminal. This
# avoids another opaque UML 'read returned 0' round-trip.
gpu_watchdog() {
  while kill -0 "$GPU_PID" 2>/dev/null; do
    sleep .10
  done
  echo >&2
  echo "[vessel-vugpu] HOST GPU BACKEND EXITED; last log follows:" >&2
  tail -240 "$GPU_LOG" >&2 || true
  echo "[vessel-vugpu] END HOST GPU LOG" >&2
}
gpu_watchdog &
WATCHDOG_PID=$!

# Keep Vessel's existing native input bridge. It is independent of the old
# Venus display transport and lets the Android UI continue to feed input once
# the visible scanout is working.
if [ -f "$POC_DIR/tools/venus_poc/host_input_bridge.py" ]; then
  python3 "$POC_DIR/tools/venus_poc/host_input_bridge.py" \
    --android-port 47634 --guest-port 47633 \
    >"$INPUT_LOG" 2>&1 &
  INPUT_PID=$!
fi

echo "[vessel-vugpu] vhost-user virtio-gpu backend ready: $GPU_SOCK"
echo "[vessel-vugpu] GPU display relay ready: $GPU_DISPLAY_SOCK"
echo "[vessel-vugpu] GPU log:     $GPU_LOG"
echo "[vessel-vugpu] display log: $DISPLAY_LOG"
echo "[vessel-vugpu] ANGLE path:  $ANGLE_PREFIX"
echo "[vessel-vugpu] booting REAL Debian UML with $VESSEL_VCPUS vCPUs / ${VESSEL_MEM_MB} MiB"
echo "[vessel-vugpu] guest should expose /dev/dri/card0 and /dev/dri/renderD128"

cd "$UML_DIR"
set +e
./umnet --passt ./passt --dns 1.1.1.1 -- \
  ./linux-umshm \
    mem="${VESSEL_MEM_MB}M" \
    ncpus="$VESSEL_VCPUS" \
    seccomp=on \
    ubd0=debian-docker.ext4 \
    root=/dev/ubda \
    rw \
    init=/umarm-init \
    stub_exe="$UML_DIR/stub_exe-umshm" \
    virtio_uml.device="$GPU_SOCK:16" \
    panic=-1 \
    con=null \
    con0=fd:0,fd:1 \
    console=tty0
UML_RC=$?
set -e

if ! kill -0 "$GPU_PID" 2>/dev/null; then
  echo "[vessel-vugpu] UML exited after GPU backend failure; full GPU log: $GPU_LOG" >&2
fi
exit "$UML_RC"
