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

configure_runtime_linker() {
    export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    if [ "$(id -u)" -eq 0 ] && command -v ldconfig >/dev/null 2>&1; then
        printf '%s\n' "$PREFIX/lib" > /etc/ld.so.conf.d/vessel-weston16.conf
        ldconfig
    fi
}

if [ -x "$PREFIX/bin/weston" ] && [ -f "$MARKER" ]; then
    configure_runtime_linker
    "$PREFIX/bin/weston" --version
    echo VESSEL_WESTON16_VULKAN_CACHE_HIT
    exit 0
fi

if [ "$(id -u)" -eq 0 ] && command -v apt-get >/dev/null 2>&1; then
    if [ ! -f /etc/apt/sources.list.d/bookworm-backports.list ]; then
        printf '%s\n' 'deb http://deb.debian.org/debian bookworm-backports main' > /etc/apt/sources.list.d/bookworm-backports.list
    fi
    # Weston 16 requires wayland-protocols >= 1.46. Bookworm-backports only has
    # 1.43, while sid currently carries the architecture-independent 1.49 data
    # package. Keep sid low-priority and explicitly select only that package.
    if [ ! -f /etc/apt/sources.list.d/vessel-wayland-protocols-sid.list ]; then
        printf '%s\n' 'deb http://deb.debian.org/debian sid main' > /etc/apt/sources.list.d/vessel-wayland-protocols-sid.list
    fi
    cat >/etc/apt/preferences.d/vessel-sid-wayland-protocols <<'EOF'
Package: *
Pin: release a=unstable
Pin-Priority: 50

Package: wayland-protocols
Pin: release a=unstable
Pin-Priority: 990
EOF

    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends \
        ca-certificates curl xz-utils meson ninja-build pkg-config python3 \
        build-essential glslang-tools libvulkan-dev libgbm-dev \
        libpixman-1-dev libxkbcommon-dev libcairo2-dev libpango1.0-dev \
        libinput-dev libevdev-dev libudev-dev libdbus-1-dev libpam0g-dev \
        libxcb1-dev libxcb-composite0-dev libxcb-xfixes0-dev libxcb-shape0-dev \
        libxcb-xkb-dev libx11-dev libx11-xcb-dev libxcursor-dev \
        libjpeg-dev libwebp-dev xwayland dbus-x11 foot fonts-dejavu-core
    apt-get install -y --no-install-recommends -t bookworm-backports \
        libwayland-dev libwayland-bin libdrm-dev libdisplay-info-dev
    apt-get install -y --no-install-recommends -t sid wayland-protocols
fi

for cmd in meson ninja pkg-config glslangValidator curl sha256sum; do
    command -v "$cmd" >/dev/null || { echo "missing Weston 16 build dependency: $cmd" >&2; exit 20; }
done
pkg-config --atleast-version=1.22 wayland-server || { pkg-config --modversion wayland-server; exit 21; }
pkg-config --atleast-version=2.4.108 libdrm || { pkg-config --modversion libdrm; exit 22; }
pkg-config --atleast-version=1.46 wayland-protocols || { pkg-config --modversion wayland-protocols; exit 23; }
pkg-config --exists vulkan gbm pixman-1 xkbcommon cairo || exit 24

echo "[weston16-build] wayland-server=$(pkg-config --modversion wayland-server)"
echo "[weston16-build] wayland-protocols=$(pkg-config --modversion wayland-protocols)"
echo "[weston16-build] libdrm=$(pkg-config --modversion libdrm)"

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
    -Dshell-desktop=true \
    -Dshell-ivi=false \
    -Dshell-kiosk=false \
    -Dshell-lua=false \
    -Dcolor-management-lcms=false \
    -Dsystemd=false \
    -Ddemo-clients=false \
    -Dsimple-clients=[] \
    -Dresize-pool=false \
    -Dtests=false \
    -Ddoc=false

ninja -C "$BUILD" -j"${VESSEL_BUILD_JOBS:-2}"
ninja -C "$BUILD" install
configure_runtime_linker

"$PREFIX/bin/weston" --version
find "$PREFIX" -name 'vulkan-renderer.so' -type f -print -quit | grep -q .
find "$PREFIX" -name 'wayland-backend.so' -type f -print -quit | grep -q .
if find "$PREFIX" -name 'gl-renderer.so' -type f -print -quit | grep -q .; then
    echo 'forbidden gl-renderer.so was built' >&2
    exit 25
fi

printf '%s\n' "$VERSION" > "$MARKER"
echo VESSEL_WESTON16_VULKAN_BUILT
