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

echo "[host] preparing forensic shared-memory relay"
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

echo "[host] hard-cleaning every stale isolated process"
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
: > "$SESSION"
: > "$HOST_LOG"

echo "[host] building forensic local X11 sink"
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
  kill -0 "$CPID" 2>/dev/null || { tail -160 "$SESSION"; exit 1; }
  sleep 0.25
done
[ -S "$CONSOLE" ] || { echo "console socket did not appear"; tail -160 "$SESSION"; exit 1; }

for _ in $(seq 1 280); do
  grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null && [ -f "$FRAME_PATH" ] && break
  sleep 0.25
done
grep -q 'root@umdebian:/#' "$SESSION" || { echo "Debian did not reach shell"; tail -200 "$SESSION"; exit 1; }
[ -f "$FRAME_PATH" ] || { echo "shared frame file was not registered"; tail -160 "$ROOT/relay.log" 2>/dev/null || true; exit 1; }

echo "[host] starting forensic local Termux:X11 sink"
DISPLAY=:0 "$HOST_BIN" "$FRAME_PATH" >"$HOST_LOG" 2>&1 &
HPID=$!
sleep 1
kill -0 "$HPID" 2>/dev/null || { cat "$HOST_LOG"; exit 1; }

echo "[host] injecting forensic Mesa presentation backend"
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

echo "[host] forensic vkcube test running; no input needed"
for _ in $(seq 1 360); do
  if grep -q '\[frame-guest\] vkcube exit=' "$SESSION" 2>/dev/null; then break; fi
  kill -0 "$CPID" 2>/dev/null || break
  sleep 0.25
done

# Give host readback/summary a moment to flush after vkcube closes.
sleep 2

echo
echo "========== EXACT FORENSICS =========="
grep -E '\[forensic\]|\[frame-host\]' "$HOST_LOG" | tail -220 || true

echo
echo "========== GUEST PIXEL PROOF =========="
grep -E 'UML-FRAME: PIXELS|\[frame-guest\]|deviceName|driverName|driverInfo|MESA:|assert|ERROR|error' "$SESSION" | tail -220 || true

echo
echo "========== SYNC SUMMARY =========="
grep -E 'proxied pollable sync fd|sync fd signalled|session ended with error|guest->Venus closed' "$ROOT/relay.log" 2>/dev/null | tail -80 || true

echo
echo "========== FINAL DIAGNOSIS =========="
DIAG=$(grep -o 'DIAG=[A-Z0-9_]*' "$HOST_LOG" | tail -1 | cut -d= -f2 || true)
case "$DIAG" in
  PIXELS_DYNAMIC_AND_X11_READBACK_MATCHES)
    echo "EXACT ISSUE: Vulkan produces changing pixels, the UML shared-memory copy is intact, XCB PutImage succeeds, and X11 GetImage reads back the same RGB pixels. The graphics path is correct. If you still see no cube, the remaining problem is Termux:X11 app/window visibility/compositor presentation, not Venus/Mesa/UML pixel transport."
    ;;
  VENUS_WSI_IMAGE_STATIC_OR_STALE)
    echo "EXACT ISSUE: presentation runs, but Mesa's CPU-visible WSI image does not change across frames. The fault is the Venus -> software-WSI blit/image synchronization path before the host display."
    ;;
  LOCAL_X11_UPLOAD_OR_PIXEL_FORMAT_MISMATCH)
    echo "EXACT ISSUE: Vulkan frame bytes are changing and reach Android, but local X11 does not read back the same RGB data. The remaining bug is the local X11 PutImage pixel format/upload path."
    ;;
  LOCAL_TERMUX_X11_PIXEL_PATH_BROKEN)
    echo "EXACT ISSUE: even the host-generated red/green/blue/white test pattern fails checked PutImage/GetImage. The problem is local Termux:X11 presentation itself."
    ;;
  NO_WSI_FRAMES)
    echo "EXACT ISSUE: vkcube never reaches the software WSI present backend. The fault is before x11_present_to_x11_sw."
    ;;
  *)
    echo "No final host diagnosis marker appeared. Full forensic lines are above; do not rerun anything yet."
    ;;
esac

echo
if grep -q 'UML-FRAME: PIXELS' "$SESSION"; then
  if grep 'UML-FRAME: PIXELS' "$SESSION" | grep -q 'match=NO'; then
    echo "COPY CHECK: FAIL - source and shared framebuffer hashes differed."
  else
    echo "COPY CHECK: PASS - every logged source/shared framebuffer copy matched."
  fi
fi

echo "[host] done; isolated session left up for visual inspection"
