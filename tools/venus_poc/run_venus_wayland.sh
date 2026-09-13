#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

HARD_NOFILE="$(ulimit -Hn 2>/dev/null || echo 8192)"
ulimit -Sn "$HARD_NOFILE" 2>/dev/null || ulimit -Sn 8192 2>/dev/null || true

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
UML_DIR="${UML_DIR:-$HOME/venus-wsi-local}"
VENUS_SOCK="${VENUS_SOCK:-$PREFIX/tmp/venus.sock}"
UMSHM_SOCK="${UMSHM_SOCK:-$PREFIX/tmp/umshm.sock}"
HOST_LOG="${HOST_LOG:-$UML_DIR/vessel-renderer.log}"
RELAY_LOG="${RELAY_LOG:-$UML_DIR/vessel-relay.log}"
PORT="${VENUS_RELAY_PORT:-5002}"
VESSEL_VCPUS="${VESSEL_VCPUS:-6}"
VESSEL_MEM_MB="${VESSEL_MEM_MB:-8192}"
THREAD_WORKER_MARKER="${THREAD_WORKER_MARKER:-$PREFIX/opt/virglrenderer-android/.venus-thread-worker}"

start_virgl() {
  rm -f "$VENUS_SOCK"
  cd "$POC_DIR"
  virgl_test_server_android \
    --angle-vulkan \
    --venus \
    --multi-clients \
    --socket-path "$VENUS_SOCK" \
    >>"$HOST_LOG" 2>&1 &
  VIRGL_PID=$!
  export VIRGL_PID
  for _ in $(seq 1 80); do
    [ -S "$VENUS_SOCK" ] && return 0
    kill -0 "$VIRGL_PID" 2>/dev/null || {
      echo "[venus-wayland] virglrenderer died during startup" >&2
      tail -120 "$HOST_LOG" >&2 || true
      return 1
    }
    sleep .1
  done
  echo "[venus-wayland] Venus socket missing after virglrenderer startup" >&2
  return 1
}

venus_watchdog() {
  while :; do
    sleep .25
    [ -n "${VIRGL_PID:-}" ] || continue
    if ! kill -0 "$VIRGL_PID" 2>/dev/null || [ ! -S "$VENUS_SOCK" ]; then
      echo "[venus-wayland] Venus listener disappeared; recycling virglrenderer" >>"$HOST_LOG"
      kill "$VIRGL_PID" 2>/dev/null || true
      wait "$VIRGL_PID" 2>/dev/null || true
      start_virgl || exit 1
      echo "[venus-wayland] Venus listener restored pid=$VIRGL_PID" >>"$HOST_LOG"
    fi
  done
}

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  [ -n "${WATCHDOG_PID:-}" ] && kill "$WATCHDOG_PID" 2>/dev/null || true
  [ -n "${RELAY_PID:-}" ] && kill "$RELAY_PID" 2>/dev/null || true
  [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null || true
  pkill -f '[h]ost_relay_wayland.py' 2>/dev/null || true
  pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
  rm -f "$VENUS_SOCK" "$UMSHM_SOCK"
  exit "$rc"
}
trap cleanup EXIT INT TERM

for f in \
  "$POC_DIR/tools/venus_poc/host_relay_wayland.py" \
  "$UML_DIR/linux-umshm" \
  "$UML_DIR/stub_exe-umshm" \
  "$UML_DIR/umnet" \
  "$UML_DIR/passt" \
  "$UML_DIR/debian-docker.ext4"; do
  [ -e "$f" ] || { echo "[venus-wayland] missing: $f" >&2; exit 1; }
done

[ -e "$THREAD_WORKER_MARKER" ] || {
  echo "[venus-wayland] custom thread-worker virglrenderer is not installed" >&2
  exit 1
}

for proc in /proc/[0-9]*; do
  pid="${proc##*/}"
  [ "$pid" = "$$" ] && continue
  cmd="$(tr '\0' ' ' < "$proc/cmdline" 2>/dev/null || true)"
  case "$cmd" in
    *linux-umshm*ubd0=debian-docker.ext4*)
      cwd="$(readlink "$proc/cwd" 2>/dev/null || true)"
      if [ "$cwd" = "$UML_DIR" ] || [[ "$cmd" == *"$UML_DIR/linux-umshm"* ]]; then
        echo "[venus-wayland] stopping stale UML pid=$pid"
        kill "$pid" 2>/dev/null || true
      fi
      ;;
  esac
done
sleep .4
pkill -f '[h]ost_relay_wayland.py' 2>/dev/null || true
pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
rm -f "$VENUS_SOCK" "$UMSHM_SOCK"
: >"$HOST_LOG"

start_virgl
venus_watchdog &
WATCHDOG_PID=$!

python3 "$POC_DIR/tools/venus_poc/host_relay_wayland.py" \
  --venus-unix "$VENUS_SOCK" \
  --uml-control "$UMSHM_SOCK" \
  --listen 127.0.0.1 \
  --port "$PORT" \
  >"$RELAY_LOG" 2>&1 &
RELAY_PID=$!
for _ in $(seq 1 80); do
  [ -S "$UMSHM_SOCK" ] && break
  kill -0 "$RELAY_PID" 2>/dev/null || { echo "[venus-wayland] host relay died" >&2; tail -120 "$RELAY_LOG"; exit 1; }
  sleep .1
done
[ -S "$UMSHM_SOCK" ] || { echo "[venus-wayland] umshm socket missing" >&2; exit 1; }

echo "[venus-wayland] Venus host + dma-buf/AHardwareBuffer relay ready"
echo "[venus-wayland] host log:  $HOST_LOG"
echo "[venus-wayland] relay log: $RELAY_LOG"
echo "[venus-wayland] booting Debian UML with $VESSEL_VCPUS vCPUs and ${VESSEL_MEM_MB} MiB RAM..."

cd "$UML_DIR"
exec ./umnet --passt ./passt --dns 1.1.1.1 -- \
  ./linux-umshm \
    mem="${VESSEL_MEM_MB}M" \
    ncpus="$VESSEL_VCPUS" \
    seccomp=on \
    ubd0=debian-docker.ext4 \
    root=/dev/ubda \
    rw \
    init=/umarm-init \
    stub_exe="$UML_DIR/stub_exe-umshm" \
    umshm_sock="$UMSHM_SOCK" \
    panic=-1 \
    con=null \
    con0=fd:0,fd:1 \
    console=tty0
