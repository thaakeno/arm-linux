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
RUN_ID="$(date +%s)-$$"

console_alive() {
  [ -S "$CONSOLE" ] || return 1
  python - "$CONSOLE" <<'PY' >/dev/null 2>&1
import socket, sys
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); s.settimeout(.5)
try: s.connect(sys.argv[1])
except OSError: raise SystemExit(1)
finally: s.close()
PY
}

ensure_x11() {
  command -v termux-x11 >/dev/null 2>&1 || { echo "[desktop] termux-x11 missing" >&2; exit 1; }
  command -v socat >/dev/null 2>&1 || { echo "[desktop] socat missing" >&2; exit 1; }

  echo "[desktop] resetting Termux:X11 host display"
  pkill -f '[t]ermux-x11' 2>/dev/null || true
  pkill -f "socat TCP-LISTEN:${X11_TCP_PORT}.*X${X11_DISPLAY_NUM}" 2>/dev/null || true
  sleep .5
  mkdir -p "$PREFIX/tmp/.X11-unix"; rm -f "$X11_UNIX"

  # Official Termux:X11 docs recommend -legacy-drawing on devices where the
  # activity stays black even though X clients are connected.
  termux-x11 ":$X11_DISPLAY_NUM" -ac -legacy-drawing >"$X11_LOG" 2>&1 &
  X11PID=$!
  for _ in $(seq 1 100); do
    [ -S "$X11_UNIX" ] && break
    kill -0 "$X11PID" 2>/dev/null || { echo "[desktop] Termux:X11 died" >&2; tail -80 "$X11_LOG" >&2; exit 1; }
    sleep .1
  done
  [ -S "$X11_UNIX" ] || { echo "[desktop] Termux:X11 socket missing" >&2; exit 1; }

  socat "TCP-LISTEN:${X11_TCP_PORT},bind=127.0.0.1,reuseaddr,fork" "UNIX-CONNECT:${X11_UNIX}" >"$SOCAT_LOG" 2>&1 &
  SOCATPID=$!
  sleep .35
  kill -0 "$SOCATPID" 2>/dev/null || { echo "[desktop] X11 TCP bridge died" >&2; tail -80 "$SOCAT_LOG" >&2; exit 1; }

  # Bring the Android surface to the foreground before the guest measures the
  # root window. Xwayland can otherwise remain at its tiny placeholder size.
  am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
  sleep 1
  echo "[desktop] X11 host path is live"
}

ensure_x11

if ! console_alive; then
  echo "[desktop] recovering a fresh Debian session"
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
    console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null && break
    sleep .25
  done
  console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null || { echo "[desktop] Debian boot failed" >&2; tail -120 "$SESSION"; exit 1; }
fi

echo "[desktop] Debian console is live"

python - "$CONSOLE" "$RUN_ID" <<'PY'
import base64, select, socket, sys, time
sock_path, run_id = sys.argv[1:3]
guest = r'''#!/bin/bash
set -u
RUN_ID="__RUN_ID__"
mark(){ echo "[desktop][$RUN_ID] $*"; }
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=10.0.2.2:0
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"

# Keep the desktop away from the experimental Venus ICD. Vulkan apps can opt
# back into Venus explicitly via /root/venus-env.sh.
export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export GDK_GL=disable
export GDK_DISABLE=vulkan
export GSK_RENDERER=cairo
export QT_XCB_GL_INTEGRATION=none

if ! command -v xdpyinfo >/dev/null 2>&1 || ! command -v xwininfo >/dev/null 2>&1; then
  apt-get update && apt-get install -y x11-utils
fi
if ! command -v xfce4-panel >/dev/null 2>&1; then
  mark FIRST_INSTALL_BEGIN
  apt-get update && apt-get install -y xfce4 xfce4-terminal dbus-x11
  mark FIRST_INSTALL_DONE
fi

if ! xdpyinfo -display "$DISPLAY" >/tmp/desktop-xdpyinfo.log 2>&1; then
  mark X11_GUEST_FAIL; cat /tmp/desktop-xdpyinfo.log; exit 1
fi

# Do not launch XFCE into Xwayland's 10x10 placeholder root. Wait until the
# Android Termux:X11 activity has attached a real surface and the root window
# reflects the phone display.
root_w=0; root_h=0
for i in $(seq 1 80); do
  dims=$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | head -1)
  root_w=${dims%% *}; root_h=${dims##* }
  [ "${root_w:-0}" -ge 320 ] && [ "${root_h:-0}" -ge 240 ] && break
  sleep .25
done
if [ "${root_w:-0}" -lt 320 ] || [ "${root_h:-0}" -lt 240 ]; then
  mark "X11_SURFACE_NOT_ATTACHED size=${root_w:-0}x${root_h:-0}"
  exit 1
fi
mark "X11_GUEST_OK size=${root_w}x${root_h}"

pkill -f '[v]kcube' 2>/dev/null || true
pkill -f '[x]fce4-session' 2>/dev/null || true
pkill -f '[x]fwm4' 2>/dev/null || true
pkill -f '[x]fce4-panel' 2>/dev/null || true
pkill -f '[x]fdesktop' 2>/dev/null || true
pkill -f '[x]fsettingsd' 2>/dev/null || true
pkill -f '[x]fce4-terminal' 2>/dev/null || true
sleep 1
rm -f /tmp/xfce-components.log /tmp/xfce-dbus.log

cat >/tmp/xfce-env.sh <<'ENV'
export DISPLAY=10.0.2.2:0
export XDG_RUNTIME_DIR=/tmp/runtime-root
export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export GDK_GL=disable
export GDK_DISABLE=vulkan
export GSK_RENDERER=cairo
export QT_XCB_GL_INTEGRATION=none
ENV

nohup dbus-run-session -- bash -lc '
  source /tmp/xfce-env.sh
  xfsettingsd >>/tmp/xfce-components.log 2>&1 &
  xfwm4 --replace --compositor=off >>/tmp/xfce-components.log 2>&1 &
  sleep 1
  xfdesktop >>/tmp/xfce-components.log 2>&1 &
  xfce4-panel >>/tmp/xfce-components.log 2>&1 &
  xfce4-terminal --disable-server >>/tmp/xfce-components.log 2>&1 &
  wait
' >/tmp/xfce-dbus.log 2>&1 &

for i in $(seq 1 80); do
  tree=$(xwininfo -root -tree -display "$DISPLAY" 2>/dev/null || true)
  # The desktop background itself should be approximately screen-sized. Do not
  # accept stale 10x10 placeholder windows from an earlier run.
  if printf '%s\n' "$tree" | grep -Eqi 'xfdesktop|xfce4-panel|Xfce Terminal' && \
     printf '%s\n' "$tree" | grep -E '[0-9]{3,5}x[0-9]{2,5}\+' >/dev/null 2>&1; then
    mark XFCE_WINDOWS_READY
    printf '%s\n' "$tree" | grep -Ei 'xfce|terminal|panel|desktop' | head -16
    exit 0
  fi
  sleep .5
done

mark XFCE_FAILED
echo '--- root geometry ---'
xdpyinfo -display "$DISPLAY" 2>/dev/null | grep -E 'dimensions:|resolution:' | head -4 || true
echo '--- component log ---'
tail -120 /tmp/xfce-components.log 2>/dev/null || true
echo '--- dbus log ---'
tail -80 /tmp/xfce-dbus.log 2>/dev/null || true
echo '--- X tree ---'
xwininfo -root -tree -display "$DISPLAY" 2>&1 | tail -100 || true
exit 1
'''.replace('__RUN_ID__', run_id)
enc=base64.b64encode(guest.encode()).decode()
cmd=("stty -echo 2>/dev/null || true\nprintf '%s' '"+enc+"' | base64 -d > /root/start-xfce-live.sh\nstty echo 2>/dev/null || true\nchmod +x /root/start-xfce-live.sh\nbash /root/start-xfce-live.sh\n").encode()
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); s.connect(sock_path)
pos=0; deadline=time.monotonic()+30
while pos<len(cmd):
  if time.monotonic()>deadline: raise SystemExit('[desktop] console injection timed out')
  try:
    n=s.send(cmd[pos:pos+2048])
    if n <= 0: raise SystemExit('[desktop] console closed during injection')
    pos+=n
  except BlockingIOError:
    select.select([], [s], [], .2)
s.close()
PY

echo "[desktop] waiting for a real Termux:X11 surface, then launching XFCE"
start=$(date +%s); spin='|/-\\'; i=0
READY="[desktop][$RUN_ID] XFCE_WINDOWS_READY"
FAIL1="[desktop][$RUN_ID] X11_GUEST_FAIL"
FAIL2="[desktop][$RUN_ID] X11_SURFACE_NOT_ATTACHED"
FAIL3="[desktop][$RUN_ID] XFCE_FAILED"
for _ in $(seq 1 2400); do
  if grep -Fq "$READY" "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] SUCCESS: this run has visible Debian XFCE windows\n'
    grep -F -A16 "$READY" "$SESSION" | head -17 || true
    echo "[desktop] switch to Termux:X11 now"
    exit 0
  fi
  if grep -Fq "$FAIL1" "$SESSION" 2>/dev/null || grep -Fq "$FAIL2" "$SESSION" 2>/dev/null || grep -Fq "$FAIL3" "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] FAILED; current-run diagnostics:\n' >&2
    grep -F -A180 "[desktop][$RUN_ID]" "$SESSION" | tail -220 >&2 || true
    exit 1
  fi
  e=$(($(date +%s)-start)); c=${spin:$((i%4)):1}; i=$((i+1))
  printf '\r\033[K[desktop] %s waiting for attached X11 surface / XFCE  %ss' "$c" "$e"
  sleep .25
done
printf '\r\033[K[desktop] timed out\n' >&2
exit 1
