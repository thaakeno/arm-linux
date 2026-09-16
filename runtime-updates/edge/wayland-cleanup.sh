#!/bin/bash
set -eu
if pgrep -u vessel -x kwin_wayland >/dev/null 2>&1; then
  echo VESSEL_WAYLAND_CLEAN_ALREADY_RUNNING
  exit 0
fi
pkill -u vessel -x kwin_x11 2>/dev/null || true
pkill -u vessel -x kwin_wayland 2>/dev/null || true
pkill -u vessel -x plasmashell 2>/dev/null || true
pkill -x Xorg 2>/dev/null || true
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
find /run/user -maxdepth 2 -type s -name 'wayland-*' -delete 2>/dev/null || true
: >/tmp/vessel-plasma.log
echo VESSEL_WAYLAND_CLEAN
