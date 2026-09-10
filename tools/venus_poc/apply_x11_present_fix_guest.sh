#!/bin/bash
set -euo pipefail

MESA_VER=26.2.2
SRC="/root/mesa-${MESA_VER}"
TARBALL="/root/mesa-${MESA_VER}.tar.xz"
BUILD="$SRC/build-venus-x11"
PATCH=/root/mesa-26.2.2-x11-present-stall.patch

echo "[x11-fix] restoring pristine Mesa ${MESA_VER} WSI sources"
for rel in \
  src/vulkan/wsi/wsi_common.c \
  src/vulkan/wsi/wsi_common_private.h \
  src/vulkan/wsi/wsi_common_x11.c \
  src/virtio/vulkan/vn_wsi.c; do
  tar -xJOf "$TARBALL" "mesa-${MESA_VER}/$rel" > "$SRC/$rel"
done

echo "[x11-fix] applying pipelined software-present fix"
cd "$SRC"
patch -p1 --batch --forward < "$PATCH"

python3 - <<'PY'
from pathlib import Path

vn = Path('/root/mesa-26.2.2/src/virtio/vulkan/vn_wsi.c')
s = vn.read_text()
old = '.sw_device = use_sw_device,'
if s.count(old) != 1:
    raise SystemExit(f'expected exactly one {old!r}, found {s.count(old)}')
s = s.replace(old, '.sw_device = true, /* UML remote X11: CPU-backed WSI */', 1)
vn.write_text(s)

x11 = Path('/root/mesa-26.2.2/src/vulkan/wsi/wsi_common_x11.c')
s = x11.read_text()
old = 'wsi_conn->has_mit_shm = x11_xcb_display_supports_xshm(conn, NULL);'
if s.count(old) != 1:
    raise SystemExit(f'expected exactly one MIT-SHM assignment, found {s.count(old)}')
s = s.replace(old, 'wsi_conn->has_mit_shm = false; /* UML guest SHM ids cannot cross Android */', 1)
x11.write_text(s)

print('[x11-fix] forced software WSI and disabled MIT-SHM')
PY

echo "[x11-fix] rebuilding Mesa"
ninja -C "$BUILD" src/virtio/vulkan/libvulkan_virtio.so
ninja -C "$BUILD" install
sync

source /root/venus-env.sh
export DISPLAY=10.0.2.2:0
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
unset VN_PERF || true
export VK_DRIVER_FILES=/root/virtio-wsi-test.json
export XDG_RUNTIME_DIR=/tmp

echo "[x11-fix] device"
vulkaninfo --summary 2>&1 | grep -E 'deviceName|driverName|driverInfo' | head -12 || true

echo "[x11-fix] running vkcube for 20 seconds"
rm -f /tmp/vkcube-x11-fix.log
set +e
timeout 20s vkcube > /tmp/vkcube-x11-fix.log 2>&1
rc=$?
set -e
cat /tmp/vkcube-x11-fix.log

echo "[x11-fix] vkcube exit=$rc"
if [ "$rc" -eq 124 ]; then
  echo "[x11-fix] vkcube survived the full test window"
elif [ "$rc" -ne 0 ]; then
  echo "[x11-fix] vkcube exited early"
fi
