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

echo "[host] preparing persistent verified local presentation proxy"
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

if [ -S "$CONSOLE" ]; then
python - "$CONSOLE" <<'PY' || true
import socket,sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
try: s.connect(sys.argv[1]); s.sendall(b'sync\nexit\n')
except OSError: pass
finally: s.close()
PY
fi
pkill -TERM -f '[c]onsole.py' 2>/dev/null || true
pkill -TERM -f '[u]mnet' 2>/dev/null || true
pkill -TERM -f '[l]inux-umshm' 2>/dev/null || true
pkill -TERM -f '[h]ost_relay_direct.py' 2>/dev/null || true
pkill -TERM -f '[h]ost_relay_frame.py' 2>/dev/null || true
pkill -TERM -f '[v]irgl_test_server_android' 2>/dev/null || true
pkill -TERM -f '[u]ml-frame-bridge-host' 2>/dev/null || true
pkill -TERM -f '[u]ml-frame-bridge-shm-host' 2>/dev/null || true
sleep 3
pkill -KILL -f '[l]inux-umshm' 2>/dev/null || true
pkill -KILL -f '[u]ml-frame-bridge-host' 2>/dev/null || true
pkill -KILL -f '[u]ml-frame-bridge-shm-host' 2>/dev/null || true
sync
rm -f "$CONSOLE" "$FRAME_PATH"
: > "$SESSION"; : > "$HOST_LOG"

if ! command -v clang >/dev/null 2>&1 || ! pkg-config --exists xcb 2>/dev/null; then
  pkg install -y clang libxcb pkg-config
fi
clang -O2 "$HOST_SRC" -o "$HOST_BIN" $(pkg-config --cflags --libs xcb)

echo "[host] booting isolated UML + Venus + Termux:X11"
cd "$ROOT"
POC_DIR="$POC_DIR" python console.py >"$SESSION" 2>&1 &
CPID=$!
for _ in $(seq 1 120); do
  [ -S "$CONSOLE" ] && break
  kill -0 "$CPID" 2>/dev/null || { tail -160 "$SESSION"; exit 1; }
  sleep .25
done
for _ in $(seq 1 280); do
  grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null && [ -f "$FRAME_PATH" ] && break
  sleep .25
done
grep -q 'root@umdebian:/#' "$SESSION" || { echo "Debian did not reach shell"; tail -200 "$SESSION"; exit 1; }
[ -f "$FRAME_PATH" ] || { echo "shared frame file missing"; exit 1; }

echo "[host] starting persistent local Vulkan presenter"
DISPLAY=:0 "$HOST_BIN" "$FRAME_PATH" >"$HOST_LOG" 2>&1 &
HPID=$!
sleep 1
kill -0 "$HPID" 2>/dev/null || { cat "$HOST_LOG"; exit 1; }

python - "$CONSOLE" "$PATCH" "$GUEST_SCRIPT" <<'PY'
import base64,pathlib,socket,sys
sock_path,patch_path,script_path=sys.argv[1:4]
patch=base64.b64encode(pathlib.Path(patch_path).read_bytes()).decode()
script=base64.b64encode(pathlib.Path(script_path).read_bytes()).decode()
cmd=("printf '%s' '"+patch+"' | base64 -d > /root/mesa-26.2.2-x11-present-stall.patch\n"
     "printf '%s' '"+script+"' | base64 -d > /root/apply_shared_frame_bridge_guest.sh\n"
     "chmod +x /root/apply_shared_frame_bridge_guest.sh\n"
     "bash /root/apply_shared_frame_bridge_guest.sh\n")
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.connect(sock_path); s.sendall(cmd.encode()); s.close()
PY

echo "[host] waiting for first verified Vulkan frame; switch to Termux:X11 now"
for _ in $(seq 1 480); do
  if grep -q 'VISIBLE_VERIFY=PASS' "$HOST_LOG" 2>/dev/null && grep -q '\[local-present\] frame=1' "$HOST_LOG" 2>/dev/null; then
    break
  fi
  kill -0 "$CPID" 2>/dev/null || break
  kill -0 "$HPID" 2>/dev/null || break
  sleep .25
done

echo
echo "========== VERIFIED LOCAL PRESENT =========="
grep -E '\[local-present\]' "$HOST_LOG" | tail -80 || true
echo
echo "========== GUEST =========="
grep -E 'UML-FRAME: DIRECT|\[frame-guest\]|ERROR|error|assert' "$SESSION" | tail -80 || true
echo
echo "========== RESULT =========="
if grep -q 'VISIBLE_VERIFY=PASS' "$HOST_LOG" && grep -q '\[local-present\] frame=1' "$HOST_LOG"; then
  echo "SUCCESS: the cube is live and persistent. Vulkan pixels reached the host-owned Termux:X11 proxy and were read back byte-for-byte correctly."
  echo "The session is intentionally left running. The cube will stay visible until you close vkcube or stop this isolated session."
else
  echo "FAIL: no verified live frame appeared. Relevant logs are above; do not rerun older branches."
fi