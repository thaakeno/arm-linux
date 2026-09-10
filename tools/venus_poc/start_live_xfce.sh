#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="${ROOT:-$HOME/venus-wsi-local}"
CONSOLE="${CONSOLE:-$ROOT/console.sock}"
SESSION="${SESSION:-$ROOT/session.out}"

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

if ! console_alive; then
  echo "[desktop] no live console behind $CONSOLE; recovering a fresh Debian session"
  pkill -TERM -f '[c]onsole.py' 2>/dev/null || true
  pkill -TERM -f '[u]mnet' 2>/dev/null || true
  pkill -TERM -f '[l]inux-umshm' 2>/dev/null || true
  pkill -TERM -f '[h]ost_relay_direct.py' 2>/dev/null || true
  pkill -TERM -f '[v]irgl_test_server_android' 2>/dev/null || true
  sleep 2
  pkill -KILL -f '[l]inux-umshm' 2>/dev/null || true
  rm -f "$CONSOLE"

  [ -f "$ROOT/console.py" ] || { echo "[desktop] missing $ROOT/console.py" >&2; exit 1; }
  : > "$SESSION"
  (cd "$ROOT" && nohup python console.py >"$SESSION" 2>&1 </dev/null &)

  echo "[desktop] booting Debian..."
  for _ in $(seq 1 360); do
    if console_alive && grep -q 'root@umdebian:/#' "$SESSION" 2>/dev/null; then
      break
    fi
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
sock_path = sys.argv[1]
guest = r'''#!/bin/bash
set -e
export DEBIAN_FRONTEND=noninteractive
export DISPLAY=10.0.2.2:0
export NO_AT_BRIDGE=1
mkdir -p /tmp/runtime-root
chmod 700 /tmp/runtime-root
export XDG_RUNTIME_DIR=/tmp/runtime-root

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

rm -f /tmp/xfce4.log
nohup dbus-run-session -- xfce4-session >/tmp/xfce4.log 2>&1 &
echo "[desktop] XFCE_READY display=$DISPLAY pid=$!"
'''
enc = base64.b64encode(guest.encode()).decode()
cmd = "printf '%s' '" + enc + "' | base64 -d > /root/start-xfce-live.sh\nchmod +x /root/start-xfce-live.sh\nbash /root/start-xfce-live.sh\n"
s = socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
s.settimeout(2)
s.connect(sock_path)
s.sendall(cmd.encode())
s.close()
PY

echo "[desktop] starting XFCE inside Debian"

echo "[desktop] live progress below (first install is the slow part)"
start_ts=$(date +%s)
spin='|/-\\'
idx=0
last_detail=''

for _ in $(seq 1 2400); do
  if grep -q '\[desktop\] XFCE_READY' "$SESSION" 2>/dev/null; then
    printf '\r\033[K[desktop] [########################] 100%%  XFCE ready\n'
    echo "[desktop] XFCE is running inside Debian. Switch to Termux:X11 and interact with it."
    exit 0
  fi

  if grep -qE 'E: |dpkg: error|Temporary failure resolving|Could not resolve|Unable to fetch' "$SESSION" 2>/dev/null; then
    printf '\r\033[K'
    echo "[desktop] install/start hit an error:" >&2
    grep -E 'E: |dpkg: error|Temporary failure resolving|Could not resolve|Unable to fetch' "$SESSION" | tail -20 >&2 || true
    exit 1
  fi

  now=$(date +%s)
  elapsed=$((now-start_ts))
  ch=${spin:$((idx%4)):1}
  idx=$((idx+1))

  if grep -q '\[desktop\] FIRST_INSTALL_BEGIN' "$SESSION" 2>/dev/null && ! grep -q '\[desktop\] FIRST_INSTALL_DONE' "$SESSION" 2>/dev/null; then
    stage="installing XFCE"
    # Show the newest meaningful apt/dpkg action so it is obvious the install is moving.
    detail=$(grep -E '^(Get:|Fetched |Selecting previously unselected package|Unpacking |Setting up |Processing triggers for )' "$SESSION" 2>/dev/null | tail -1 | sed 's/[[:space:]]\+/ /g' || true)
    [ -n "$detail" ] && last_detail="$detail"
  elif grep -q '\[desktop\] FIRST_INSTALL_DONE' "$SESSION" 2>/dev/null; then
    stage="launching XFCE"
    last_detail="starting xfce4-session"
  else
    stage="checking Debian packages"
  fi

  # Indeterminate bar: this is intentionally not a fake percentage because apt
  # does not expose a reliable total package-completion percentage here.
  pos=$((idx%24))
  bar='........................'
  bar="${bar:0:$pos}#${bar:$((pos+1))}"
  printf '\r\033[K[desktop] [%s] %s  %s  %ss' "$bar" "$ch" "$stage" "$elapsed"
  if [ -n "$last_detail" ]; then
    short=$(printf '%s' "$last_detail" | cut -c1-70)
    printf '  | %s' "$short"
  fi

  sleep .25
done

printf '\r\033[K'
echo "[desktop] timed out waiting for XFCE" >&2
tail -100 "$SESSION" 2>/dev/null || true
exit 1
