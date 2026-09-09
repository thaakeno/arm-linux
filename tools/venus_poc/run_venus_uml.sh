#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
UML_DIR="${UML_DIR:-$HOME/uml-test}"
VENUS_SOCK="${VENUS_SOCK:-$PREFIX/tmp/venus.sock}"
UMSHM_SOCK="${UMSHM_SOCK:-$PREFIX/tmp/umshm.sock}"
HOST_LOG="${HOST_LOG:-$HOME/venus-direct.log}"
RELAY_LOG="${RELAY_LOG:-$HOME/venus-host-relay.log}"
PORT="${VENUS_RELAY_PORT:-5002}"

cleanup() {
  rc=$?
  trap - EXIT INT TERM
  echo
  echo "[venus-run] cleaning up..."
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

pkill -f '[h]ost_relay_direct.py' 2>/dev/null || true
pkill -f '[v]irgl_test_server_android' 2>/dev/null || true
rm -f "$VENUS_SOCK" "$UMSHM_SOCK"

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
