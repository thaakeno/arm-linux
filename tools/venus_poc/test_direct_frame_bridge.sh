#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="$ROOT/console.sock"
SESSION="$ROOT/session.out"
HOST_SRC="$POC_DIR/tools/venus_poc/uml_frame_bridge_host.c"
HOST_BIN="$ROOT/uml-frame-bridge-host"
HOST_LOG="$ROOT/frame-bridge.log"
PATCH="$POC_DIR/tools/venus_poc/mesa-26.2.2-x11-present-stall.patch"
GUEST_SCRIPT="$POC_DIR/tools/venus_poc/apply_direct_frame_bridge_guest.sh"

for f in "$ROOT/console.py" "$HOST_SRC" "$PATCH" "$GUEST_SCRIPT"; do
  [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "[host] cleaning previous isolated session"
if [ -S "$CONSOLE" ]; then
  python - "$CONSOLE" <<'PY' || true
import socket, sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
try:
    s.connect(sys.argv[1]); s.sendall(b'sync\nexit\n')
except OSError:
    pass
finally:
    s.close()
PY
  for _ in $(seq 1 40); do [ ! -S "$CONSOLE" ] && break; sleep 0.25; done
fi
pkill -TERM -f '[c]onsole.py' 2>/dev/null || true
pkill -TERM -f '[u]mnet .*venus-wsi-local' 2>/dev/null || true
pkill -TERM -f '[l]inux-umshm .*venus-wsi-local' 2>/dev/null || true
pkill -TERM -f '[h]ost_relay_direct.py' 2>/dev/null || true
pkill -TERM -f '[v]irgl_test_server_android' 2>/dev/null || true
pkill -TERM -f '[u]ml-frame-bridge-host' 2>/dev/null || true
sleep 2
sync
rm -f "$CONSOLE"
: > "$SESSION"
: > "$HOST_LOG"

echo "[host] building local frame sink"
if ! command -v clang >/dev/null 2>&1 || ! pkg-config --exists xcb 2>/dev/null; then
  pkg install -y clang libxcb pkg-config
fi
clang -O2 "$HOST_SRC" -o "$HOST_BIN" $(pkg-config --cflags --libs xcb)

echo "[host] starting isolated UML + Venus + Termux:X11"
cd "$ROOT"
POC_DIR="$POC_DIR" python console.py >"$SESSION" 2>&1 &
CPID=$!

for _ in $(seq 1 120); do
  [ -S "$CONSOLE" ] && break
  kill -0 "$CPID" 2>/dev/null || { tail -120 "$SESSION"; exit 1; }
  sleep 0.25
done
[ -S "$CONSOLE" ] || { echo "console socket did not appear"; tail -120 "$SESSION"; exit 1; }

for _ in $(seq 1 240); do
  grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null && break
  sleep 0.25
done
grep -q 'root@umdebian:/#' "$SESSION" || { echo "Debian did not reach shell"; tail -180 "$SESSION"; exit 1; }

X11_UNIX="$PREFIX/tmp/.X11-unix/X0"
for _ in $(seq 1 100); do [ -S "$X11_UNIX" ] && break; sleep 0.1; done
[ -S "$X11_UNIX" ] || { echo "Termux:X11 local socket missing"; exit 1; }

echo "[host] starting local X11 frame sink on port 6010"
DISPLAY=:0 "$HOST_BIN" >"$HOST_LOG" 2>&1 &
HPID=$!
sleep 1
kill -0 "$HPID" 2>/dev/null || { cat "$HOST_LOG"; exit 1; }

echo "[host] injecting direct-frame Mesa backend"
python - "$CONSOLE" "$PATCH" "$GUEST_SCRIPT" <<'PY'
import base64, pathlib, socket, sys
sock_path, patch_path, script_path = sys.argv[1:4]
patch = base64.b64encode(pathlib.Path(patch_path).read_bytes()).decode('ascii')
script = base64.b64encode(pathlib.Path(script_path).read_bytes()).decode('ascii')
cmd = (
    "printf '%s' '" + patch + "' | base64 -d > /root/mesa-26.2.2-x11-present-stall.patch\n"
    "printf '%s' '" + script + "' | base64 -d > /root/apply_direct_frame_bridge_guest.sh\n"
    "chmod +x /root/apply_direct_frame_bridge_guest.sh\n"
    "bash /root/apply_direct_frame_bridge_guest.sh\n"
)
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(cmd.encode())
s.close()
PY

echo "[host] build + direct-frame vkcube test running"
for _ in $(seq 1 240); do
  if grep -q '\[frame-guest\] vkcube exit=' "$SESSION" 2>/dev/null; then break; fi
  kill -0 "$CPID" 2>/dev/null || break
  sleep 0.5
done

echo
echo "========== FRAME BRIDGE =========="
cat "$HOST_LOG" | tail -120 || true

echo
echo "========== GUEST RESULT =========="
grep -E '\[frame-guest\]|UML-FRAME|deviceName|driverName|driverInfo|MESA:|assert|ERROR|error' "$SESSION" | tail -160 || true

echo
echo "========== RELAY =========="
tail -100 "$ROOT/relay.log" 2>/dev/null || true

echo
if grep -q '\[frame-host\] frame=' "$HOST_LOG"; then
  echo "[host] SUCCESS: rendered Vulkan frames reached the local Termux:X11 frame sink"
else
  echo "[host] NO FRAMES reached the local frame sink"
fi

echo "[host] isolated guest and frame sink left running for visual inspection"
