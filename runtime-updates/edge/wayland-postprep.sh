#!/bin/bash
set -eu

echo VESSEL_WAYLAND_BOOTSTRAP_BEGIN
if ! pgrep -u vessel -x kwin_wayland >/dev/null 2>&1; then
  pkill -u vessel -x kwin_x11 2>/dev/null || true
  pkill -u vessel -x kwin_wayland 2>/dev/null || true
  pkill -u vessel -x plasmashell 2>/dev/null || true
  pkill -x Xorg 2>/dev/null || true
  rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
  find /run/user -maxdepth 2 -type s -name 'wayland-*' -delete 2>/dev/null || true
  : >/tmp/vessel-plasma.log
  /usr/bin/python3 /usr/local/lib/vessel/launch_wayland.py
fi

for i in $(seq 1 600); do
  if pgrep -u vessel -x kwin_wayland >/dev/null 2>&1 &&
     pgrep -u vessel -x plasmashell >/dev/null 2>&1 &&
     find /run/user -maxdepth 2 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then
    echo VESSEL_WAYLAND_READY
    exit 0
  fi
  sleep .1
done

echo VESSEL_WAYLAND_DIAG
id vessel || true
dpkg-query -W kwin-wayland kwin-common plasma-workspace-wayland 2>/dev/null || true
ls -l /dev/dri 2>/dev/null || true
ls -la /run/user/* 2>/dev/null || true
ls -ld /tmp/.X11-unix 2>/dev/null || true
dbus-send --system --print-reply --dest=org.freedesktop.DBus / org.freedesktop.DBus.NameHasOwner string:org.freedesktop.ConsoleKit 2>/dev/null || true
cat /tmp/vessel-consolekit.log 2>/dev/null || true
tail -400 /tmp/vessel-plasma.log 2>/dev/null || true
exit 44
