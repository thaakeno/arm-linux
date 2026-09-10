#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

POC_DIR="${POC_DIR:-$HOME/venus-poc}"
ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="$ROOT/console.sock"
SESSION="$ROOT/session.out"
PATCH="$POC_DIR/tools/venus_poc/mesa-26.2.2-x11-present-stall.patch"
GUEST_SCRIPT="$POC_DIR/tools/venus_poc/apply_x11_present_fix_guest.sh"

for f in "$ROOT/console.py" "$PATCH" "$GUEST_SCRIPT"; do
  [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "[host] cleaning previous isolated session"
if [ -S "$CONSOLE" ]; then
  python - "$CONSOLE" <<'PY' || true
import socket, sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
try:
    s.connect(sys.argv[1]); s.sendall(b'sync\nexit\n')
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
sleep 2
sync
rm -f "$CONSOLE"
: > "$SESSION"

echo "[host] starting isolated UML + Venus + Termux:X11"
cd "$ROOT"
python console.py >"$SESSION" 2>&1 &
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

echo "[host] injecting exact Mesa patch and guest runner"
python - "$CONSOLE" "$PATCH" "$GUEST_SCRIPT" <<'PY'
import base64, pathlib, socket, sys
sock_path, patch_path, script_path = sys.argv[1:4]
patch = base64.b64encode(pathlib.Path(patch_path).read_bytes()).decode('ascii')
script = base64.b64encode(pathlib.Path(script_path).read_bytes()).decode('ascii')
cmd = (
    "printf '%s' '" + patch + "' | base64 -d > /root/mesa-26.2.2-x11-present-stall.patch\n"
    "printf '%s' '" + script + "' | base64 -d > /root/apply_x11_present_fix_guest.sh\n"
    "chmod +x /root/apply_x11_present_fix_guest.sh\n"
    "bash /root/apply_x11_present_fix_guest.sh\n"
)
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(cmd.encode())
s.close()
PY

echo "[host] build + vkcube test running; Termux:X11 should open automatically"
for _ in $(seq 1 180); do
  if grep -q '\[x11-fix\] vkcube exit=' "$SESSION" 2>/dev/null; then break; fi
  kill -0 "$CPID" 2>/dev/null || break
  sleep 0.5
done

echo
echo "========== RESULT =========="
grep -E '\[x11-fix\]|deviceName|driverName|driverInfo|MESA:|assert|ERROR|error' "$SESSION" | tail -120 || true

echo
echo "========== LAST GUEST OUTPUT =========="
tail -120 "$SESSION"

echo
echo "========== RELAY =========="
tail -80 "$ROOT/relay.log" 2>/dev/null || true

echo
echo "[host] done; isolated guest left running so you can inspect Termux:X11"
