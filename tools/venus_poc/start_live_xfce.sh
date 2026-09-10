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

  echo "[desktop] resetting Termux:X11"
  pkill -f '[t]ermux-x11' 2>/dev/null || true
  pkill -f "socat TCP-LISTEN:${X11_TCP_PORT}.*X${X11_DISPLAY_NUM}" 2>/dev/null || true
  am broadcast -a com.termux.x11.ACTION_STOP -p com.termux.x11 >/dev/null 2>&1 || true
  sleep .5

  # Termux:X11 has its own Android-side resolution preference.  Configure it
  # before creating a fresh server instead of waiting for Xwayland's bootstrap
  # root to magically resize.  That wait created a startup deadlock on some
  # devices because no real client was mapped yet.
  am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
  sleep .7
  if command -v termux-x11-preference >/dev/null 2>&1; then
    timeout 3s termux-x11-preference \
      "displayResolutionMode"="custom" \
      "displayResolutionCustom"="1280x720" \
      "displayStretch"="true" \
      "fullscreen"="true" >/dev/null 2>&1 || true
  fi

  mkdir -p "$PREFIX/tmp/.X11-unix"
  rm -f "$X11_UNIX"
  TERMUX_X11_DEBUG=1 termux-x11 ":$X11_DISPLAY_NUM" -ac -legacy-drawing >"$X11_LOG" 2>&1 &
  X11PID=$!
  for _ in $(seq 1 100); do
    [ -S "$X11_UNIX" ] && break
    kill -0 "$X11PID" 2>/dev/null || {
      echo "[desktop] Termux:X11 died" >&2
      tail -100 "$X11_LOG" >&2 || true
      exit 1
    }
    sleep .1
  done
  [ -S "$X11_UNIX" ] || { echo "[desktop] Termux:X11 socket missing" >&2; tail -100 "$X11_LOG" >&2 || true; exit 1; }

  socat "TCP-LISTEN:${X11_TCP_PORT},bind=127.0.0.1,reuseaddr,fork" "UNIX-CONNECT:${X11_UNIX}" >"$SOCAT_LOG" 2>&1 &
  SOCATPID=$!
  sleep .35
  kill -0 "$SOCATPID" 2>/dev/null || { echo "[desktop] X11 TCP bridge died" >&2; tail -80 "$SOCAT_LOG" >&2 || true; exit 1; }

  am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity >/dev/null 2>&1 || true
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
  console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null || { echo "[desktop] Debian boot failed" >&2; tail -120 "$SESSION" >&2; exit 1; }
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

# XFCE is ordinary software X11.  Keep the experimental Venus ICD opt-in for
# Vulkan applications only.
export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export LIBGL_ALWAYS_INDIRECT=1
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
  mark X11_GUEST_FAIL
  cat /tmp/desktop-xdpyinfo.log
  exit 1
fi

dims=$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1x\2/p' | head -1)
mark "X11_GUEST_OK size=${dims:-unknown}"

pkill -f '[v]kcube' 2>/dev/null || true
pkill -f '[x]fce4-session' 2>/dev/null || true
pkill -f '[x]fwm4' 2>/dev/null || true
pkill -f '[x]fce4-panel' 2>/dev/null || true
pkill -f '[x]fdesktop' 2>/dev/null || true
pkill -f '[x]fsettingsd' 2>/dev/null || true
pkill -f '[x]fce4-terminal' 2>/dev/null || true
sleep .5
rm -f /tmp/xfce-components.log /tmp/xfce-dbus.log

cat >/tmp/xfce-env.sh <<'ENV'
export DISPLAY=10.0.2.2:0
export XDG_RUNTIME_DIR=/tmp/runtime-root
export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export LIBGL_ALWAYS_INDIRECT=1
export GDK_GL=disable
export GDK_DISABLE=vulkan
export GSK_RENDERER=cairo
export QT_XCB_GL_INTEGRATION=none
ENV

# Launch clients immediately.  Do not wait for an Android "surface attached"
# condition first; Termux:X11's normal flow is server -> X clients, and the
# activity can update its surface/resolution while those clients are alive.
nohup dbus-run-session -- bash -lc '
  source /tmp/xfce-env.sh
  xfsettingsd >>/tmp/xfce-components.log 2>&1 &
  xfwm4 --replace --compositor=off >>/tmp/xfce-components.log 2>&1 &
  sleep .7
  xfdesktop >>/tmp/xfce-components.log 2>&1 &
  xfce4-panel >>/tmp/xfce-components.log 2>&1 &
  xfce4-terminal --disable-server >>/tmp/xfce-components.log 2>&1 &
  wait
' >/tmp/xfce-dbus.log 2>&1 &

for i in $(seq 1 80); do
  tree=$(xwininfo -root -tree -display "$DISPLAY" 2>/dev/null || true)
  if printf '%s\n' "$tree" | grep -Eqi 'xfdesktop|xfce4-panel|Xfce Terminal'; then
    dims=$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1x\2/p' | head -1)
    mark "XFCE_WINDOWS_READY root=${dims:-unknown}"
    printf '%s\n' "$tree" | grep -Ei 'xfce|terminal|panel|desktop' | head -16
    exit 0
  fi
  sleep .25
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
        pos += n
    except BlockingIOError:
        select.select([], [s], [], .2)
s.close()
PY

echo "[desktop] launching XFCE now (no surface-wait deadlock)"
start=$(date +%s); spin='|/-\\'; i=0
READY="[desktop][$RUN_ID] XFCE_WINDOWS_READY"
FAIL1="[desktop][$RUN_ID] X11_GUEST_FAIL"
FAIL2="[desktop][$RUN_ID] XFCE_FAILED"
for _ in $(seq 1 240); do
  if grep -Fq "$READY" "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] SUCCESS: current-run Debian XFCE windows are mapped\n'
    grep -F -A16 "$READY" "$SESSION" | head -17 || true
    echo "[desktop] switch to Termux:X11 now"
    exit 0
  fi
  if grep -Fq "$FAIL1" "$SESSION" 2>/dev/null || grep -Fq "$FAIL2" "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] FAILED; current-run diagnostics:\n' >&2
    grep -F -A180 "[desktop][$RUN_ID]" "$SESSION" | tail -220 >&2 || true
    exit 1
  fi
  e=$(($(date +%s)-start)); c=${spin:$((i%4)):1}; i=$((i+1))
  printf '\r\033[K[desktop] %s launching Debian XFCE  %ss' "$c" "$e"
  sleep .25
done
printf '\r\033[K[desktop] timed out waiting for XFCE\n' >&2
grep -F -A180 "[desktop][$RUN_ID]" "$SESSION" | tail -220 >&2 || true
exit 1
