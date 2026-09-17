#!/bin/bash
set -u

echo VESSEL_WAYLAND_BOOTSTRAP_BEGIN
uid=$(id -u vessel)
runtime_dir="/run/user/$uid"
plasma_log=/tmp/vessel-plasma.log
sycoca_log=/tmp/vessel-sycoca.log

stop_desktop() {
  pkill -u vessel -x plasmashell 2>/dev/null || true
  pkill -u vessel -x kwin_wayland 2>/dev/null || true
  pkill -u vessel -x kwin_x11 2>/dev/null || true
  pkill -u vessel -x startplasma-wayland 2>/dev/null || true
  pkill -u vessel -x kded5 2>/dev/null || true
  pkill -u vessel -x Xwayland 2>/dev/null || true
  pkill -u vessel -x dbus-daemon 2>/dev/null || true
  pkill -x Xorg 2>/dev/null || true
  sleep .15
  rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
  find /run/user -maxdepth 2 -type s -name 'wayland-*' -delete 2>/dev/null || true
}

rebuild_kservice_cache() {
  install -d -m 700 -o vessel -g vessel /home/vessel/.cache "$runtime_dir"
  chown vessel:vessel "$runtime_dir" 2>/dev/null || true
  chmod 700 "$runtime_dir" 2>/dev/null || true
  rm -f /home/vessel/.cache/ksycoca5* /home/vessel/.cache/ksycoca6* 2>/dev/null || true
  : >"$sycoca_log"
  su -l vessel -c "HOME=/home/vessel XDG_RUNTIME_DIR='$runtime_dir' LC_ALL=C.UTF-8 kbuildsycoca5 --noincremental" >"$sycoca_log" 2>&1 || true
  chown -R vessel:vessel /home/vessel/.cache 2>/dev/null || true
}

launch_desktop() {
  : >"$plasma_log"
  /usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py
}

wait_ready() {
  limit="$1"
  i=0
  while [ "$i" -lt "$limit" ]; do
    if pgrep -u vessel -x kwin_wayland >/dev/null 2>&1 &&
       pgrep -u vessel -x plasmashell >/dev/null 2>&1 &&
       find /run/user -maxdepth 2 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then
      return 0
    fi
    i=$((i + 1))
    sleep .1
  done
  return 1
}

# KF5Service consumes ksycoca during KWin startup. A desktop installed or
# repaired in-place can leave a stale cache from the old package graph, so
# rebuild it before the compositor ever maps it.
stop_desktop
rebuild_kservice_cache
launch_desktop

if wait_ready 150; then
  echo VESSEL_WAYLAND_READY
  exit 0
fi

# One bounded self-heal for a compositor that died during startup. Preserve the
# first crash log, rebuild the service cache from scratch, and launch a clean
# user D-Bus/Plasma session exactly once more.
cp "$plasma_log" /tmp/vessel-plasma-first-attempt.log 2>/dev/null || true
echo VESSEL_WAYLAND_RECOVERY=retry-after-kservice-cache-reset
stop_desktop
rebuild_kservice_cache
launch_desktop

if wait_ready 200; then
  echo VESSEL_WAYLAND_READY
  exit 0
fi

echo VESSEL_WAYLAND_DIAG
id vessel || true
dpkg-query -W kwin-wayland kwin-common plasma-workspace-wayland libkf5service5 libkf5service-data 2>/dev/null || true
ls -l /dev/dri 2>/dev/null || true
ls -la /run/user/* 2>/dev/null || true
dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null || true
echo '--- ksycoca ---'
tail -120 "$sycoca_log" 2>/dev/null || true
echo '--- first attempt ---'
tail -160 /tmp/vessel-plasma-first-attempt.log 2>/dev/null || true
echo '--- second attempt ---'
tail -240 "$plasma_log" 2>/dev/null || true
echo '--- kernel kwin faults ---'
dmesg 2>/dev/null | grep -Ei 'kwin_wayland|libKF5Service|segfault' | tail -80 || true
exit 44
