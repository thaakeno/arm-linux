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
FRAME_RELAY_LOG="${FRAME_RELAY_LOG:-$UML_DIR/vessel-frame-relay.log}"
INPUT_LOG="${INPUT_LOG:-$UML_DIR/vessel-input-bridge.log}"
PORT="${VENUS_RELAY_PORT:-5002}"
FRAME_PORT="${VESSEL_FRAME_RELAY_PORT:-5003}"
VESSEL_VCPUS="${VESSEL_VCPUS:-6}"
VESSEL_MEM_MB="${VESSEL_MEM_MB:-8192}"
PROCESS_WORKER_MARKER="${PROCESS_WORKER_MARKER:-$PREFIX/opt/virglrenderer-android/.venus-process-worker}"

start_virgl() {
  rm -f "$VENUS_SOCK"
  cd "$POC_DIR"
  virgl_test_server_android \
    --angle-vulkan \
    --venus \
    --no-fork \
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
  local pid="$1"
  while :; do
    sleep .25
    if ! kill -0 "$pid" 2>/dev/null || [ ! -S "$VENUS_SOCK" ]; then
      echo "[venus-wayland] Venus listener disappeared; recycling virglrenderer" >>"$HOST_LOG"
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      start_virgl || exit 1
      pid="$VIRGL_PID"
      echo "[venus-wayland] Venus listener restored pid=$pid" >>"$HOST_LOG"
    fi
  done
}

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  [ -n "${WATCHDOG_PID:-}" ] && kill "$WATCHDOG_PID" 2>/dev/null || true
  [ -n "${FRAME_RELAY_PID:-}" ] && kill "$FRAME_RELAY_PID" 2>/dev/null || true
  [ -n "${RELAY_PID:-}" ] && kill "$RELAY_PID" 2>/dev/null || true
  [ -n "${INPUT_PID:-}" ] && kill "$INPUT_PID" 2>/dev/null || true
  [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null || true
  pkill -f '[h]ost_frame_damage_relay.py' 2>/dev/null || true
  pkill -f '[h]ost_relay_wayland.py' 2>/dev/null || true
  pkill -f '[h]ost_input_bridge.py' 2>/dev/null || true
  pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
  rm -f "$VENUS_SOCK" "$UMSHM_SOCK"
  exit "$rc"
}
trap cleanup EXIT INT TERM

for f in \
  "$POC_DIR/tools/venus_poc/host_relay_wayland.py" \
  "$POC_DIR/tools/venus_poc/host_frame_damage_relay.py" \
  "$POC_DIR/tools/venus_poc/host_input_bridge.py" \
  "$UML_DIR/linux-umshm" \
  "$UML_DIR/stub_exe-umshm" \
  "$UML_DIR/umnet" \
  "$UML_DIR/passt" \
  "$UML_DIR/debian-docker.ext4"; do
  [ -e "$f" ] || { echo "[venus-wayland] missing: $f" >&2; exit 1; }
done

[ -e "$PROCESS_WORKER_MARKER" ] || {
  echo "[venus-wayland] process-isolated Venus virglrenderer is not installed" >&2
  echo "[venus-wayland] run tools/venus_poc/build_virglrenderer_android_process.sh once" >&2
  exit 1
}

# Kill only Vessel UML instances using this exact persistent disk, then wait
# for them to actually release it before starting the replacement. A fixed
# sleep was racy and could leave debian-docker.ext4 locked during fast restarts.
stale_uml_pids=()
for proc in /proc/[0-9]*; do
  pid="${proc##*/}"
  [ "$pid" = "$$" ] && continue
  [ -r "$proc/cmdline" ] || continue
  cmd="$(cat "$proc/cmdline" 2>/dev/null | tr '\0' ' ' || true)"
  case "$cmd" in
    *linux-umshm*ubd0=debian-docker.ext4*)
      cwd="$(readlink "$proc/cwd" 2>/dev/null || true)"
      if [ "$cwd" = "$UML_DIR" ] || [[ "$cmd" == *"$UML_DIR/linux-umshm"* ]]; then
        echo "[venus-wayland] stopping stale UML pid=$pid"
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
  if kill -0 "$pid" 2>/dev/null; then
    echo "[venus-wayland] stale UML pid=$pid did not exit; forcing stop"
    kill -KILL "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep .05
    done
  fi
done

pkill -f '[h]ost_frame_damage_relay.py' 2>/dev/null || true
pkill -f '[h]ost_relay_wayland.py' 2>/dev/null || true
pkill -f '[h]ost_input_bridge.py' 2>/dev/null || true
pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
rm -f "$VENUS_SOCK" "$UMSHM_SOCK"
: >"$HOST_LOG"
: >"$RELAY_LOG"
: >"$FRAME_RELAY_LOG"
: >"$INPUT_LOG"

start_virgl
venus_watchdog "$VIRGL_PID" &
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
  kill -0 "$RELAY_PID" 2>/dev/null || {
    echo "[venus-wayland] host relay died" >&2
    tail -120 "$RELAY_LOG" >&2 || true
    exit 1
  }
  sleep .1
done
[ -S "$UMSHM_SOCK" ] || {
  echo "[venus-wayland] umshm socket missing" >&2
  exit 1
}

python3 "$POC_DIR/tools/venus_poc/host_frame_damage_relay.py" \
  --listen 127.0.0.1 \
  --port "$FRAME_PORT" \
  >"$FRAME_RELAY_LOG" 2>&1 &
FRAME_RELAY_PID=$!
sleep .1
kill -0 "$FRAME_RELAY_PID" 2>/dev/null || {
  echo "[venus-wayland] persistent frame relay died" >&2
  cat "$FRAME_RELAY_LOG" >&2 || true
  exit 1
}

python3 "$POC_DIR/tools/venus_poc/host_input_bridge.py" \
  --android-port 47634 --guest-port 47633 \
  >"$INPUT_LOG" 2>&1 &
INPUT_PID=$!
sleep .1
kill -0 "$INPUT_PID" 2>/dev/null || {
  echo "[venus-wayland] native input bridge died" >&2
  cat "$INPUT_LOG" >&2 || true
  exit 1
}

echo "[venus-wayland] Venus host + dma-buf/AHardwareBuffer relay ready"
echo "[venus-wayland] persistent SHM damage relay ready"
echo "[venus-wayland] native evdev input bridge ready"
echo "[venus-wayland] host log:  $HOST_LOG"
echo "[venus-wayland] relay log: $RELAY_LOG"
echo "[venus-wayland] frame log: $FRAME_RELAY_LOG"
echo "[venus-wayland] input log: $INPUT_LOG"
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
