#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="${CONSOLE:-$ROOT/console.sock}"
SESSION="${SESSION:-$ROOT/session.out}"
X11_DISPLAY_NUM="${X11_DISPLAY_NUM:-0}"
X11_TCP_PORT="${X11_TCP_PORT:-6000}"
X11_UNIX="$PREFIX/tmp/.X11-unix/X${X11_DISPLAY_NUM}"
X11_LOG="$ROOT/desktop-x11.log"
SOCAT_LOG="$ROOT/desktop-x11-socat.log"

console_alive() {
  [ -S "$CONSOLE" ] || return 1
  python - "$CONSOLE" <<'PY' >/dev/null 2>&1
import socket, sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.settimeout(0.5)
try:
    s.connect(sys.argv[1])
except OSError:
    raise SystemExit(1)
finally:
    s.close()
PY
}

ensure_x11() {
  command -v termux-x11 >/dev/null 2>&1 || { echo "[desktop] termux-x11 is missing" >&2; exit 1; }
  command -v socat >/dev/null 2>&1 || { echo "[desktop] socat is missing; run: pkg install socat" >&2; exit 1; }

  if [ ! -S "$X11_UNIX" ]; then
    echo "[desktop] starting Termux:X11"
    mkdir -p "$PREFIX/tmp/.X11-unix"
    rm -f "$X11_UNIX"
    termux-x11 ":$X11_DISPLAY_NUM" >"$X11_LOG" 2>&1 &
    for _ in $(seq 1 80); do
      [ -S "$X11_UNIX" ] && break
      sleep .1
    done
    [ -S "$X11_UNIX" ] || { echo "[desktop] Termux:X11 socket did not appear" >&2; tail -80 "$X11_LOG" >&2 || true; exit 1; }
  fi

  # Make the Android activity visible even when the X server was already alive.
  am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true

  # Debian reaches the host as 10.0.2.2:6000. Keep exactly one TCP->Unix bridge.
  if ! python - "$X11_TCP_PORT" <<'PY' >/dev/null 2>&1
import socket,sys
s=socket.socket(); s.settimeout(.2)
try: s.connect(('127.0.0.1',int(sys.argv[1])))
except OSError: raise SystemExit(1)
finally: s.close()
PY
  then
    pkill -f "socat TCP-LISTEN:${X11_TCP_PORT}.*X${X11_DISPLAY_NUM}" 2>/dev/null || true
    socat "TCP-LISTEN:${X11_TCP_PORT},bind=127.0.0.1,reuseaddr,fork" "UNIX-CONNECT:${X11_UNIX}" >"$SOCAT_LOG" 2>&1 &
    sleep .3
  fi

  python - "$X11_TCP_PORT" <<'PY' >/dev/null 2>&1 || { echo "[desktop] X11 TCP bridge did not come up" >&2; tail -80 "$SOCAT_LOG" >&2 || true; exit 1; }
import socket,sys
s=socket.socket(); s.settimeout(1); s.connect(('127.0.0.1',int(sys.argv[1]))); s.close()
PY
  echo "[desktop] X11 host path is live"
}

ensure_x11

if ! console_alive; then
  echo "[desktop] no live console behind $CONSOLE; recovering a fresh Debian session"
  pkill -TERM -f '[c]onsole.py' 2>/dev/null || true
  pkill -TERM -f '[u]mnet' 2>/dev/null || true
  pkill -TERM -f '[l]inux-umshm' 2>/dev/null || true
  sleep 2
  pkill -KILL -f '[l]inux-umshm' 2>/dev/null || true
  rm -f "$CONSOLE"
  [ -f "$ROOT/console.py" ] || { echo "[desktop] missing $ROOT/console.py" >&2; exit 1; }
  : > "$SESSION"
  (cd "$ROOT" && nohup python console.py >"$SESSION" 2>&1 </dev/null &)
  echo "[desktop] booting Debian"
  for _ in $(seq 1 360); do
    if console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null; then break; fi
    sleep .25
  done
  if ! console_alive || ! grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null; then
    echo "[desktop] Debian did not become ready" >&2
    tail -120 "$SESSION" 2>/dev/null || true
    exit 1
  fi
fi

echo "[desktop] Debian console is live"

python - "$CONSOLE" <<'PY'
import base64, socket, sys
sock_path=sys.argv[1]
guest=r'''#!/bin/bash
set -u
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=10.0.2.2:0
export NO_AT_BRIDGE=1
mkdir -p /tmp/runtime-root
chmod 700 /tmp/runtime-root
export XDG_RUNTIME_DIR=/tmp/runtime-root

# Prove the guest can actually reach the Android X server before claiming success.
if ! command -v xdpyinfo >/dev/null 2>&1; then
  apt-get update
  apt-get install -y x11-utils
fi
if ! xdpyinfo -display "$DISPLAY" >/tmp/xdpyinfo-desktop.log 2>&1; then
  echo "[desktop] X11_GUEST_FAIL"
  cat /tmp/xdpyinfo-desktop.log
  exit 1
fi
echo "[desktop] X11_GUEST_OK"

pkill -f '[v]kcube' 2>/dev/null || true
pkill -f '[x]fce4-session' 2>/dev/null || true
pkill -f '[x]fwm4' 2>/dev/null || true
sleep 1

if ! command -v xfce4-session >/dev/null 2>&1; then
  echo "[desktop] FIRST_INSTALL_BEGIN"
  apt-get update
  apt-get install -y xfce4 xfce4-terminal dbus-x11
  echo "[desktop] FIRST_INSTALL_DONE"
fi

rm -f /tmp/xfce4.log /tmp/xfce-ready
nohup dbus-run-session -- xfce4-session >/tmp/xfce4.log 2>&1 &
launcher_pid=$!
sleep 4
if pgrep -f '[x]fce4-session' >/dev/null 2>&1 || pgrep -f '[x]fwm4' >/dev/null 2>&1; then
  touch /tmp/xfce-ready
  echo "[desktop] XFCE_READY display=$DISPLAY launcher_pid=$launcher_pid"
else
  echo "[desktop] XFCE_FAILED"
  tail -80 /tmp/xfce4.log 2>/dev/null || true
  exit 1
fi
'''
enc=base64.b64encode(guest.encode()).decode()
cmd="printf '%s' '"+enc+"' | base64 -d > /root/start-xfce-live.sh\nchmod +x /root/start-xfce-live.sh\nbash /root/start-xfce-live.sh\n"
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); s.settimeout(2); s.connect(sock_path); s.sendall(cmd.encode()); s.close()
PY

echo "[desktop] starting XFCE inside Debian"
start_ts=$(date +%s)
spin='|/-\\'; idx=0
for _ in $(seq 1 2400); do
  if grep -q '\[desktop\] XFCE_READY' "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] XFCE process verified and connected to X11\n'
    echo "[desktop] switch to Termux:X11 now"
    exit 0
  fi
  if grep -q '\[desktop\] X11_GUEST_FAIL\|\[desktop\] XFCE_FAILED' "$SESSION" 2>/dev/null; then
    printf '\r\033[K'
    echo "[desktop] startup failed; exact guest output:" >&2
    grep -A80 -E '\[desktop\] X11_GUEST_FAIL|\[desktop\] XFCE_FAILED' "$SESSION" | tail -100 >&2 || true
    exit 1
  fi
  now=$(date +%s); elapsed=$((now-start_ts)); ch=${spin:$((idx%4)):1}; idx=$((idx+1))
  if grep -q '\[desktop\] FIRST_INSTALL_BEGIN' "$SESSION" 2>/dev/null && ! grep -q '\[desktop\] FIRST_INSTALL_DONE' "$SESSION" 2>/dev/null; then stage='installing XFCE'; else stage='connecting/launching XFCE'; fi
  printf '\r\033[K[desktop] %s  %s  %ss' "$ch" "$stage" "$elapsed"
  sleep .25
done
printf '\r\033[K'
echo "[desktop] timed out waiting for verified XFCE" >&2
tail -120 "$SESSION" 2>/dev/null || true
exit 1
