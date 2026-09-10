#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="$ROOT/console.sock"
SESSION="$ROOT/session.out"
FRAME_PATH="$PREFIX/tmp/uml-frame-shm.bin"
HOST_SRC="$POC_DIR/tools/venus_poc/uml_frame_bridge_shm_host.c"
HOST_BIN="$ROOT/uml-frame-bridge-shm-host"
HOST_LOG="$ROOT/frame-bridge-shm.log"
PATCH="$POC_DIR/tools/venus_poc/mesa-26.2.2-x11-present-stall.patch"
GUEST_SCRIPT="$POC_DIR/tools/venus_poc/apply_shared_frame_bridge_guest.sh"
TOOLS="$POC_DIR/tools/venus_poc"

for f in "$ROOT/console.py" "$HOST_SRC" "$PATCH" "$GUEST_SCRIPT" "$TOOLS/host_relay_direct.py" "$TOOLS/host_relay_frame.py"; do
  [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "[host] preparing shared-memory relay in isolated worktree"
cp "$TOOLS/host_relay_direct.py" "$TOOLS/host_relay_direct_base.py"
sed -i 's/import host_relay_direct as base/import host_relay_direct_base as base/' "$TOOLS/host_relay_frame.py"
cat > "$TOOLS/host_relay_direct.py" <<PY
#!/usr/bin/env python3
import sys
from host_relay_frame import main
sys.argv += ['--frame-path', '$FRAME_PATH']
main()
PY
chmod +x "$TOOLS/host_relay_direct.py"

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
pkill -TERM -f '[h]ost_relay_frame.py' 2>/dev/null || true
pkill -TERM -f '[v]irgl_test_server_android' 2>/dev/null || true
pkill -TERM -f '[u]ml-frame-bridge-shm-host' 2>/dev/null || true
sleep 2
sync
rm -f "$CONSOLE" "$FRAME_PATH"
: > "$SESSION"
: > "$HOST_LOG"

echo "[host] building local shared-frame sink"
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
  kill -0 "$CPID" 2>/dev/null || { tail -140 "$SESSION"; exit 1; }
  sleep 0.25
done
[ -S "$CONSOLE" ] || { echo "console socket did not appear"; tail -140 "$SESSION"; exit 1; }

for _ in $(seq 1 240); do
  grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null && [ -f "$FRAME_PATH" ] && break
  sleep 0.25
done
grep -q 'root@umdebian:/#' "$SESSION" || { echo "Debian did not reach shell"; tail -180 "$SESSION"; exit 1; }
[ -f "$FRAME_PATH" ] || { echo "shared frame file was not registered"; tail -120 "$ROOT/relay.log" 2>/dev/null || true; exit 1; }

echo "[host] starting local Termux:X11 shared-frame sink"
DISPLAY=:0 "$HOST_BIN" "$FRAME_PATH" >"$HOST_LOG" 2>&1 &
HPID=$!
sleep 1
kill -0 "$HPID" 2>/dev/null || { cat "$HOST_LOG"; exit 1; }

echo "[host] injecting shared-memory Mesa presentation backend"
python - "$CONSOLE" "$PATCH" "$GUEST_SCRIPT" <<'PY'
import base64, pathlib, socket, sys
sock_path, patch_path, script_path = sys.argv[1:4]
patch = base64.b64encode(pathlib.Path(patch_path).read_bytes()).decode('ascii')
script = base64.b64encode(pathlib.Path(script_path).read_bytes()).decode('ascii')
cmd = (
    "printf '%s' '" + patch + "' | base64 -d > /root/mesa-26.2.2-x11-present-stall.patch\n"
    "printf '%s' '" + script + "' | base64 -d > /root/apply_shared_frame_bridge_guest.sh\n"
    "chmod +x /root/apply_shared_frame_bridge_guest.sh\n"
    "bash /root/apply_shared_frame_bridge_guest.sh\n"
)
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(cmd.encode())
s.close()
PY

echo "[host] build + shared-memory vkcube test running"
for _ in $(seq 1 240); do
  if grep -q '\[frame-guest\] vkcube exit=' "$SESSION" 2>/dev/null; then break; fi
  kill -0 "$CPID" 2>/dev/null || break
  sleep 0.5
done

echo
echo "========== FRAME BRIDGE =========="
tail -140 "$HOST_LOG" 2>/dev/null || true

echo
echo "========== GUEST RESULT =========="
grep -E '\[frame-guest\]|UML-FRAME|deviceName|driverName|driverInfo|MESA:|assert|ERROR|error' "$SESSION" | tail -200 || true

echo
echo "========== RELAY =========="
tail -120 "$ROOT/relay.log" 2>/dev/null || true

echo
if grep -q '\[frame-host\] frame=' "$HOST_LOG"; then
  echo "[host] SUCCESS: Vulkan frames crossed through shared memory and were drawn locally"
elif grep -q 'UML-FRAME: copy-begin' "$SESSION" && ! grep -q 'UML-FRAME: copy-done' "$SESSION"; then
  echo "[host] CPU MAP STALL: presentation reached the WSI image read but stalled while reading image->cpu_map"
elif grep -q 'UML-FRAME: copy-done' "$SESSION"; then
  echo "[host] COPY WORKS: CPU WSI image is readable; remaining fault is only notification/display"
else
  echo "[host] NO PRESENT: WSI backend was not reached"
fi

echo "[host] isolated guest and sink left running for visual inspection"
