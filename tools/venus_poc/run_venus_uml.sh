#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
UML_DIR="${UML_DIR:-$HOME/uml-test}"
VENUS_SOCK="${VENUS_SOCK:-$PREFIX/tmp/venus.sock}"
UMSHM_SOCK="${UMSHM_SOCK:-$PREFIX/tmp/umshm.sock}"
HOST_LOG="${HOST_LOG:-$HOME/venus-direct.log}"
RELAY_LOG="${RELAY_LOG:-$HOME/venus-host-relay.log}"
X11_LOG="${X11_LOG:-$HOME/venus-x11-proxy.log}"
PORT="${VENUS_RELAY_PORT:-5002}"
X11_DISPLAY_NUM="${X11_DISPLAY_NUM:-0}"
X11_TCP_PORT="${X11_TCP_PORT:-6000}"
ENABLE_X11="${ENABLE_X11:-1}"
REQUIRE_THREAD_WORKER="${REQUIRE_THREAD_WORKER:-1}"
THREAD_WORKER_MARKER="${THREAD_WORKER_MARKER:-$PREFIX/opt/virglrenderer-android/.venus-thread-worker}"

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  echo
  echo "[venus-run] cleaning up..."
  [ -n "${X11_PROXY_PID:-}" ] && kill "$X11_PROXY_PID" 2>/dev/null || true
  [ -n "${RELAY_PID:-}" ] && kill "$RELAY_PID" 2>/dev/null || true
  [ -n "${VIRGL_PID:-}" ] && kill "$VIRGL_PID" 2>/dev/null || true
  pkill -f '[h]ost_relay_direct.py' 2>/dev/null || true
  pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
  rm -f "$VENUS_SOCK" "$UMSHM_SOCK"
  exit "$rc"
}
trap cleanup EXIT INT TERM

for f in \
  "$POC_DIR/tools/venus_poc/host_relay_direct.py" \
  "$UML_DIR/linux-umshm" \
  "$UML_DIR/stub_exe-umshm" \
  "$UML_DIR/umnet" \
  "$UML_DIR/passt" \
  "$UML_DIR/debian-docker.ext4"; do
  if [ ! -e "$f" ]; then
    echo "[venus-run] missing: $f" >&2
    exit 1
  fi
done

if [ "$REQUIRE_THREAD_WORKER" = "1" ] && [ ! -e "$THREAD_WORKER_MARKER" ]; then
  echo "[venus-run] custom thread-worker virglrenderer is not installed." >&2
  echo "[venus-run] run: bash $POC_DIR/tools/venus_poc/build_virglrenderer_android_thread.sh" >&2
  exit 1
fi

pkill -f '[h]ost_relay_direct.py' 2>/dev/null || true
pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
rm -f "$VENUS_SOCK" "$UMSHM_SOCK"

if [ "$ENABLE_X11" = "1" ]; then
  command -v termux-x11 >/dev/null 2>&1 || {
    echo "[venus-run] termux-x11 missing; install x11-repo + termux-x11-nightly" >&2
    exit 1
  }
  command -v socat >/dev/null 2>&1 || {
    echo "[venus-run] socat missing; run: pkg install socat" >&2
    exit 1
  }

  X11_UNIX="$PREFIX/tmp/.X11-unix/X${X11_DISPLAY_NUM}"
  if [ ! -S "$X11_UNIX" ]; then
    termux-x11 ":$X11_DISPLAY_NUM" >/dev/null 2>&1 &
    for _ in $(seq 1 50); do
      [ -S "$X11_UNIX" ] && break
      sleep 0.1
    done
  fi
  [ -S "$X11_UNIX" ] || {
    echo "[venus-run] Termux:X11 socket did not appear: $X11_UNIX" >&2
    exit 1
  }

  am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
  pkill -f "socat TCP-LISTEN:${X11_TCP_PORT}.*X${X11_DISPLAY_NUM}" 2>/dev/null || true
  socat \
    "TCP-LISTEN:${X11_TCP_PORT},bind=127.0.0.1,reuseaddr,fork" \
    "UNIX-CONNECT:${X11_UNIX}" \
    >"$X11_LOG" 2>&1 &
  X11_PROXY_PID=$!
  sleep 0.2
  kill -0 "$X11_PROXY_PID" 2>/dev/null || {
    echo "[venus-run] X11 TCP proxy died; see $X11_LOG" >&2
    exit 1
  }
fi

cd "$POC_DIR"
virgl_test_server_android \
  --angle-vulkan \
  --venus \
  --multi-clients \
  --socket-path "$VENUS_SOCK" \
  >"$HOST_LOG" 2>&1 &
VIRGL_PID=$!

for _ in $(seq 1 50); do
  [ -S "$VENUS_SOCK" ] && break
  kill -0 "$VIRGL_PID" 2>/dev/null || {
    echo "[venus-run] virgl_test_server_android died; see $HOST_LOG" >&2
    exit 1
  }
  sleep 0.1
done
[ -S "$VENUS_SOCK" ] || {
  echo "[venus-run] Venus socket did not appear; see $HOST_LOG" >&2
  exit 1
}

python "$POC_DIR/tools/venus_poc/host_relay_direct.py" \
  --venus-unix "$VENUS_SOCK" \
  --uml-control "$UMSHM_SOCK" \
  --listen 127.0.0.1 \
  --port "$PORT" \
  >"$RELAY_LOG" 2>&1 &
RELAY_PID=$!

for _ in $(seq 1 50); do
  [ -S "$UMSHM_SOCK" ] && break
  kill -0 "$RELAY_PID" 2>/dev/null || {
    echo "[venus-run] host relay died; see $RELAY_LOG" >&2
    exit 1
  }
  sleep 0.1
done
[ -S "$UMSHM_SOCK" ] || {
  echo "[venus-run] umshm control socket did not appear; see $RELAY_LOG" >&2
  exit 1
}

echo "[venus-run] Venus host ready"
echo "[venus-run] host log:  $HOST_LOG"
echo "[venus-run] relay log: $RELAY_LOG"
if [ "$ENABLE_X11" = "1" ]; then
  echo "[venus-run] X11 guest display: 10.0.2.2:${X11_DISPLAY_NUM}"
  echo "[venus-run] X11 proxy log: $X11_LOG"
fi
echo "[venus-run] booting Debian UML..."

cd "$UML_DIR"
./umnet --passt ./passt --dns 1.1.1.1 -- \
  ./linux-umshm \
    mem=2048M \
    ubd0=debian-docker.ext4 \
    root=/dev/ubda \
    rw \
    init=/umarm-init \
    stub_exe="$UML_DIR/stub_exe-umshm" \
    umshm_sock="$UMSHM_SOCK" \
    panic=-1 \
    con=null \
    con0=fd:0,fd:1
