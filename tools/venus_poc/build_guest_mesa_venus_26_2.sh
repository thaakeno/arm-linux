#!/bin/bash
set -euo pipefail

# Run this INSIDE the Debian UML guest.
# Mesa 26.2 contains the 2026 Venus sync rework that no longer requires a
# renderer VkSemaphore object for SYNC_FD WSI semaphores.  That matters for
# Android/Adreno hosts where VK_KHR_external_semaphore_fd is unavailable.

MESA_VER="${MESA_VER:-26.2.2}"
PREFIX_DIR="${PREFIX_DIR:-/opt/mesa-venus-${MESA_VER}}"
SRC_DIR="${SRC_DIR:-/root/mesa-${MESA_VER}}"
BUILD_DIR="${BUILD_DIR:-${SRC_DIR}/build-venus-x11}"
TARBALL="/root/mesa-${MESA_VER}.tar.xz"
URL="https://archive.mesa3d.org/mesa-${MESA_VER}.tar.xz"
ICD_JSON="/root/virtio-wsi-test.json"

echo "[guest-mesa] Mesa ${MESA_VER} -> ${PREFIX_DIR}"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y \
  ca-certificates curl xz-utils \
  build-essential pkg-config meson ninja-build python3 python3-mako python3-yaml python3-ply python3-packaging \
  bison flex \
  libdrm-dev libexpat1-dev \
  libx11-dev libx11-xcb-dev libxext-dev libxfixes-dev libxshmfence-dev \
  libxcb1-dev libxcb-dri3-dev libxcb-present-dev libxcb-randr0-dev libxcb-sync-dev libxcb-xfixes0-dev

if [ ! -s "$TARBALL" ]; then
  echo "[guest-mesa] downloading $URL"
  curl -fL --retry 3 --retry-delay 1 -o "$TARBALL.tmp" "$URL"
  mv "$TARBALL.tmp" "$TARBALL"
fi

rm -rf "$SRC_DIR"
mkdir -p "$SRC_DIR"
tar -xJf "$TARBALL" --strip-components=1 -C "$SRC_DIR"

# Keep this deliberately small: Venus + X11 WSI only.  No Gallium/OpenGL,
# Wayland, LLVM, EGL or GBM are needed for the guest-native Vulkan test.
meson setup "$BUILD_DIR" "$SRC_DIR" \
  --prefix="$PREFIX_DIR" \
  --buildtype=release \
  -Dvulkan-drivers=virtio \
  -Dgallium-drivers= \
  -Dplatforms=x11 \
  -Dglx=disabled \
  -Degl=disabled \
  -Dgbm=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dopengl=false \
  -Dllvm=disabled \
  -Dbuild-tests=false

ninja -C "$BUILD_DIR"
ninja -C "$BUILD_DIR" install

LIB="$(find "$PREFIX_DIR" -type f -name 'libvulkan_virtio.so' | head -1)"
if [ -z "$LIB" ] || [ ! -f "$LIB" ]; then
  echo "[guest-mesa] libvulkan_virtio.so not found under $PREFIX_DIR" >&2
  exit 1
fi

cat > "$ICD_JSON" <<EOF
{
  "file_format_version": "1.0.0",
  "ICD": {
    "library_path": "$LIB",
    "api_version": "1.4.0"
  }
}
EOF

cat > /root/venus-env.sh <<EOF
export DISPLAY=10.0.2.2:0
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
export VK_DRIVER_FILES=$ICD_JSON
export XDG_RUNTIME_DIR=/tmp
EOF

chmod 0644 "$ICD_JSON" /root/venus-env.sh

echo "[guest-mesa] installed: $LIB"
echo "[guest-mesa] ICD:       $ICD_JSON"
echo "[guest-mesa] env:       /root/venus-env.sh"
echo "[guest-mesa] next: source /root/venus-env.sh && vulkaninfo --summary && vkcube"
