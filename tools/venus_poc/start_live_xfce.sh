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
  sleep .4
  mkdir -p "$PREFIX/tmp/.X11-unix"; rm -f "$X11_UNIX"

  termux-x11 ":$X11_DISPLAY_NUM" -ac >"$X11_LOG" 2>&1 &
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
  console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null || { echo "[desktop] Debian boot failed" >&2; tail -120 "$SESSION"; exit 1; }
fi

echo "[desktop] Debian console is live"

python - "$CONSOLE" <<'PY'
import base64, select, socket, sys, time
sock_path=sys.argv[1]
guest=r'''#!/bin/bash
set -u
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=10.0.2.2:0
export NO_AT_BRIDGE=1
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"

export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export LIBGL_ALWAYS_INDIRECT=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
export GALLIUM_DRIVER=llvmpipe
export GDK_GL=disable
export GDK_DISABLE=vulkan
export GSK_RENDERER=cairo
export QT_XCB_GL_INTEGRATION=none

if ! command -v xdpyinfo >/dev/null 2>&1 || ! command -v xwininfo >/dev/null 2>&1 || ! command -v xrandr >/dev/null 2>&1; then
  apt-get update && apt-get install -y x11-utils x11-xserver-utils
fi
if ! command -v xfce4-panel >/dev/null 2>&1; then
  echo '[desktop] FIRST_INSTALL_BEGIN'
  apt-get update && apt-get install -y xfce4 xfce4-terminal dbus-x11
  echo '[desktop] FIRST_INSTALL_DONE'
fi

if ! xdpyinfo -display "$DISPLAY" >/tmp/desktop-xdpyinfo.log 2>&1; then
  echo '[desktop] X11_GUEST_FAIL'; cat /tmp/desktop-xdpyinfo.log; exit 1
fi

# Termux:X11 can initially expose a tiny placeholder framebuffer before its
# Android activity has finished attaching.  XFCE then happily maps 10x10
# windows, which is technically "success" but invisible on the phone.  Wait
# briefly for a real size, then force a sane framebuffer if it is still tiny.
read root_w root_h <<EOF
$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | head -1)
EOF
for i in $(seq 1 20); do
  if [ "${root_w:-0}" -ge 320 ] && [ "${root_h:-0}" -ge 240 ]; then break; fi
  sleep .25
  read root_w root_h <<EOF
$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | head -1)
EOF
done
if [ "${root_w:-0}" -lt 320 ] || [ "${root_h:-0}" -lt 240 ]; then
  echo "[desktop] tiny X11 framebuffer ${root_w:-0}x${root_h:-0}; forcing 1280x720"
  xrandr -display "$DISPLAY" --fb 1280x720 >/tmp/desktop-xrandr.log 2>&1 || true
  sleep .5
  read root_w root_h <<EOF
$(xdpyinfo -display "$DISPLAY" 2>/dev/null | sed -n 's/.*dimensions:[[:space:]]*\([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | head -1)
EOF
fi
echo "[desktop] X11_GUEST_OK size=${root_w:-unknown}x${root_h:-unknown}"

pkill -f '[v]kcube' 2>/dev/null || true
pkill -f '[x]fce4-session' 2>/dev/null || true
pkill -f '[x]fwm4' 2>/dev/null || true
pkill -f '[x]fce4-panel' 2>/dev/null || true
pkill -f '[x]fdesktop' 2>/dev/null || true
pkill -f '[x]fsettingsd' 2>/dev/null || true
pkill -f '[x]fce4-terminal' 2>/dev/null || true
sleep 1
rm -f /tmp/xfce-components.log /tmp/xfce-dbus.log /tmp/xfce-ready

cat >/tmp/xfce-env.sh <<'ENV'
export DISPLAY=10.0.2.2:0
export XDG_RUNTIME_DIR=/tmp/runtime-root
export VK_DRIVER_FILES=/nonexistent/disabled-vulkan-icd.json
export VK_ICD_FILENAMES=/nonexistent/disabled-vulkan-icd.json
unset VN_DEBUG VTEST_SOCKET_NAME VN_PERF LD_LIBRARY_PATH
export LIBGL_ALWAYS_SOFTWARE=1
export LIBGL_ALWAYS_INDIRECT=1
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
export GALLIUM_DRIVER=llvmpipe
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

for i in $(seq 1 60); do
  tree=$(xwininfo -root -tree -display "$DISPLAY" 2>/dev/null || true)
  # Require at least one actually visible-sized child window.  This prevents
  # the old 10x10 placeholder windows from being reported as desktop success.
  if printf '%s\n' "$tree" | grep -Eqi 'xfce|terminal|panel|desktop' && \
     printf '%s\n' "$tree" | grep -E ' [3-9][0-9][0-9]x[2-9][0-9][0-9]\+' >/dev/null 2>&1; then
    touch /tmp/xfce-ready
    echo '[desktop] XFCE_WINDOWS_READY'
    printf '%s\n' "$tree" | grep -Ei 'xfce|terminal|panel|desktop' | head -12
    exit 0
  fi
  sleep .5
done

echo '[desktop] XFCE_FAILED'
echo '--- root geometry ---'
xdpyinfo -display "$DISPLAY" 2>/dev/null | grep -E 'dimensions:|resolution:' | head -4 || true
echo '--- xrandr ---'
xrandr -display "$DISPLAY" 2>&1 | head -30 || true
echo '--- component log ---'
tail -120 /tmp/xfce-components.log 2>/dev/null || true
echo '--- dbus log ---'
tail -80 /tmp/xfce-dbus.log 2>/dev/null || true
echo '--- X tree ---'
xwininfo -root -tree -display "$DISPLAY" 2>&1 | tail -100 || true
exit 1
'''
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

echo "[desktop] launching XFCE inside Debian with Vulkan hard-disabled for the desktop"
start=$(date +%s); spin='|/-\\'; i=0
for _ in $(seq 1 2400); do
  if grep -q '\[desktop\] XFCE_WINDOWS_READY' "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] SUCCESS: Debian XFCE has visible-sized windows on Termux:X11\n'
    grep -A12 '\[desktop\] XFCE_WINDOWS_READY' "$SESSION" | head -13 || true
    echo "[desktop] switch to Termux:X11 now"
    exit 0
  fi
  if grep -q '\[desktop\] X11_GUEST_FAIL\|\[desktop\] XFCE_FAILED' "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] FAILED; exact Debian diagnostics:\n' >&2
    grep -A320 -E '\[desktop\] X11_GUEST_FAIL|\[desktop\] XFCE_FAILED' "$SESSION" | tail -320 >&2 || true
    exit 1
  fi
  e=$(($(date +%s)-start)); c=${spin:$((i%4)):1}; i=$((i+1))
  printf '\r\033[K[desktop] %s waiting for visible XFCE desktop  %ss' "$c" "$e"
  sleep .25
done
printf '\r\033[K[desktop] timed out\n' >&2
exit 1
