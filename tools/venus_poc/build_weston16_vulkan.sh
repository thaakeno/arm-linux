#!/bin/bash
set -euo pipefail

VERSION=16.0.0
SHA256=dfb32e2bccabda957b94a8d0ec6075acd18c71c87ebc543ee3e618d294ca0f7f
PREFIX="${VESSEL_WESTON_PREFIX:-/opt/vessel-weston16}"
MARKER="$PREFIX/.vessel-native-vulkan-$VERSION"
SRC_ROOT="${VESSEL_WESTON_BUILD_DIR:-/root/vessel-weston16-build}"
TARBALL="$SRC_ROOT/weston-$VERSION.tar.xz"
SOURCE="$SRC_ROOT/weston-$VERSION"
BUILD="$SRC_ROOT/build"
URL="https://deb.debian.org/debian/pool/main/w/weston/weston_${VERSION}.orig.tar.xz"

if [ -x "$PREFIX/bin/weston" ] && [ -f "$MARKER" ]; then
    "$PREFIX/bin/weston" --version
    echo VESSEL_WESTON16_VULKAN_CACHE_HIT
    exit 0
fi

if [ "$(id -u)" -eq 0 ] && command -v apt-get >/dev/null 2>&1; then
    if [ ! -f /etc/apt/sources.list.d/bookworm-backports.list ]; then
        printf '%s\n' 'deb http://deb.debian.org/debian bookworm-backports main' > /etc/apt/sources.list.d/bookworm-backports.list
    fi
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    # Weston 16 needs newer Wayland/libdrm/display-info than stock Bookworm.
    apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils meson ninja-build pkg-config python3 \
        build-essential glslang-tools libvulkan-dev libgbm-dev \
        libpixman-1-dev libxkbcommon-dev libcairo2-dev libpango1.0-dev \
        libinput-dev libevdev-dev libudev-dev libdbus-1-dev libpam0g-dev \
        libxcb1-dev libxcb-composite0-dev libxcb-xfixes0-dev libxcb-shape0-dev \
        libxcb-xkb-dev libx11-dev libx11-xcb-dev libxcursor-dev \
        libjpeg-dev libwebp-dev xwayland dbus-x11 foot fonts-dejavu-core
    apt-get install -y --no-install-recommends -t bookworm-backports \
        libwayland-dev libwayland-bin wayland-protocols libdrm-dev libdisplay-info-dev
fi

for cmd in meson ninja pkg-config glslangValidator curl sha256sum; do
    command -v "$cmd" >/dev/null || { echo "missing Weston 16 build dependency: $cmd" >&2; exit 20; }
done
pkg-config --atleast-version=1.22 wayland-server || { pkg-config --modversion wayland-server; exit 21; }
pkg-config --atleast-version=2.4.108 libdrm || { pkg-config --modversion libdrm; exit 22; }
pkg-config --exists vulkan gbm pixman-1 xkbcommon cairo || exit 23

rm -rf "$SRC_ROOT"
mkdir -p "$SRC_ROOT"
curl -fL --retry 4 --retry-delay 2 "$URL" -o "$TARBALL"
printf '%s  %s\n' "$SHA256" "$TARBALL" | sha256sum -c -
tar -xJf "$TARBALL" -C "$SRC_ROOT"

meson setup "$BUILD" "$SOURCE" \
    --prefix="$PREFIX" \
    --libdir=lib \
    --libexecdir=libexec \
    --buildtype=release \
    --strip \
    --auto-features=disabled \
    -Dbackend-drm=false \
    -Dbackend-headless=false \
    -Dbackend-pipewire=false \
    -Dbackend-rdp=false \
    -Dbackend-vnc=false \
    -Dbackend-wayland=true \
    -Dbackend-x11=false \
    -Dbackend-default=wayland \
    -Drenderer-gl=false \
    -Drenderer-vulkan=true \
    -Dxwayland=true \
    -Dsystemd=false \
    -Ddemo-clients=false \
    -Dtests=false \
    -Ddoc=false

ninja -C "$BUILD" -j"${VESSEL_BUILD_JOBS:-2}"
ninja -C "$BUILD" install
mkdir -p "$PREFIX"
printf '%s\n' "$VERSION" > "$MARKER"

"$PREFIX/bin/weston" --version
find "$PREFIX" -name 'vulkan-renderer.so' -type f -print -quit | grep -q .
find "$PREFIX" -name 'wayland-backend.so' -type f -print -quit | grep -q .
if find "$PREFIX" -name 'gl-renderer.so' -type f -print -quit | grep -q .; then
    echo 'forbidden gl-renderer.so was built' >&2
    exit 24
fi

echo VESSEL_WESTON16_VULKAN_BUILT