#!/bin/bash
for i in $(seq 1 600); do
  if pgrep -u vessel -x kwin_wayland >/dev/null && pgrep -u vessel -x plasmashell >/dev/null && find /run/user -maxdepth 2 -type s -name 'wayland-*' -print -quit 2>/dev/null | grep -q .; then
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
