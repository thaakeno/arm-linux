#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="${CONSOLE:-$ROOT/console.sock}"

if [ ! -S "$CONSOLE" ]; then
  echo "[desktop] no live UML console socket at $CONSOLE" >&2
  echo "[desktop] start the Venus UML session first" >&2
  exit 1
fi

python - "$CONSOLE" <<'PY'
import base64, socket, sys
sock_path = sys.argv[1]
guest = r'''#!/bin/bash
set -e
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=10.0.2.2:0
export NO_AT_BRIDGE=1
mkdir -p /tmp/runtime-root
chmod 700 /tmp/runtime-root
export XDG_RUNTIME_DIR=/tmp/runtime-root

# Clear the persistent vkcube test window before starting the actual desktop.
pkill -f '[v]kcube' 2>/dev/null || true
sleep 1

if ! command -v xfce4-session >/dev/null 2>&1; then
  echo "[desktop] installing XFCE (one-time; this is the slow part)"
  apt-get update
  apt-get install -y xfce4 xfce4-terminal dbus-x11
fi

pkill -f '[x]fce4-session' 2>/dev/null || true
pkill -f '[x]fwm4' 2>/dev/null || true
sleep 1
rm -f /tmp/xfce4.log
nohup dbus-run-session -- xfce4-session >/tmp/xfce4.log 2>&1 &
echo "[desktop] XFCE launched inside Debian on DISPLAY=$DISPLAY"
echo "[desktop] log: /tmp/xfce4.log"
'''
enc = base64.b64encode(guest.encode()).decode()
cmd = "printf '%s' '" + enc + "' | base64 -d > /root/start-xfce-live.sh\nchmod +x /root/start-xfce-live.sh\nbash /root/start-xfce-live.sh\n"
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sock_path)
s.sendall(cmd.encode())
s.close()
PY

echo "[desktop] command injected into the live Debian guest"
echo "[desktop] switch to Termux:X11. First install can take a few minutes; later starts should be fast."
