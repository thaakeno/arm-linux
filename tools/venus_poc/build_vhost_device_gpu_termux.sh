#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Build rust-vmm's vhost-user virtio-gpu backend natively in Termux and link it
# against the Android/ANGLE virglrenderer already used by Vessel.  This is a
# host tool: it never runs inside Debian/UML.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALL="${VESSEL_VHOST_GPU_PREFIX:-$PREFIX/opt/vessel-vhost-gpu}"
WORK="${VESSEL_VHOST_GPU_WORK:-$HOME/.cache/vessel-vhost-gpu}"
VHOST_REPO="${VESSEL_VHOST_DEVICE_REPO:-https://github.com/rust-vmm/vhost-device.git}"
VHOST_COMMIT="${VESSEL_VHOST_DEVICE_COMMIT:-20fa14c4c56e40a12104794a934dd70dc7642ff2}"
VIRGL_VERSION="1.3.0"
VIRGL_PREFIX="$PREFIX/opt/virglrenderer-android"
ANGLE_PREFIX="$PREFIX/opt/angle-android/vulkan"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[vhost-gpu-build] missing command: $1" >&2
    exit 1
  }
}

if [ "$(uname -o 2>/dev/null || true)" != "Android" ] && [ ! -d /data/data/com.termux ]; then
  echo "[vhost-gpu-build] this builder is meant to run natively in Termux/Android" >&2
  exit 1
fi

echo "[vhost-gpu-build] installing build prerequisites..."
pkg update -y
pkg install -y rust clang git curl tar pkg-config make

for cmd in cargo clang git curl pkg-config; do need "$cmd"; done

# Reuse Vessel's known-good Android virglrenderer build.  It is patched for
# ANGLE and Venus and already works on this phone.  Build it only if missing.
if ! find "$VIRGL_PREFIX/lib" -maxdepth 1 -type f -name 'libvirglrenderer.so*' -print -quit 2>/dev/null | grep -q .; then
  echo "[vhost-gpu-build] Android virglrenderer not installed; building it first..."
  bash "$ROOT/tools/venus_poc/build_virglrenderer_android_thread.sh"
fi

VIRGL_LIB="$(find "$VIRGL_PREFIX/lib" -maxdepth 1 -type f -name 'libvirglrenderer.so*' 2>/dev/null | sort | head -1 || true)"
[ -n "$VIRGL_LIB" ] || {
  echo "[vhost-gpu-build] no libvirglrenderer.so under $VIRGL_PREFIX/lib" >&2
  exit 1
}
[ -d "$ANGLE_PREFIX" ] || {
  echo "[vhost-gpu-build] ANGLE Vulkan runtime missing: $ANGLE_PREFIX" >&2
  exit 1
}

mkdir -p "$WORK" "$INSTALL/bin" "$INSTALL/lib/pkgconfig" "$INSTALL/angle-shim"

# Termux intentionally strips the development headers/pkg-config metadata from
# virglrenderer-android.  Fetch the *matching* 1.3.0 source only for public
# headers and point pkg-config at the already-installed Android library.
VIRGL_ARCHIVE="$WORK/virglrenderer-$VIRGL_VERSION.tar.gz"
VIRGL_SRC="$WORK/virglrenderer-virglrenderer-$VIRGL_VERSION"
if [ ! -f "$VIRGL_SRC/src/virglrenderer.h" ]; then
  rm -rf "$VIRGL_SRC"
  if [ ! -s "$VIRGL_ARCHIVE" ]; then
    curl -fL --retry 3 --retry-delay 2 \
      -o "$VIRGL_ARCHIVE.tmp" \
      "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz"
    mv "$VIRGL_ARCHIVE.tmp" "$VIRGL_ARCHIVE"
  fi
  tar -xzf "$VIRGL_ARCHIVE" -C "$WORK"
fi
[ -f "$VIRGL_SRC/src/virglrenderer.h" ] || {
  echo "[vhost-gpu-build] matching virglrenderer headers were not extracted" >&2
  exit 1
}

cat >"$INSTALL/lib/pkgconfig/virglrenderer.pc" <<EOF
prefix=$VIRGL_PREFIX
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=$VIRGL_SRC/src

Name: virglrenderer
Description: Vessel Android virglrenderer
Version: $VIRGL_VERSION
Libs: -L\${libdir} -lvirglrenderer
Cflags: -I\${includedir}
EOF

# The Termux libepoxy patch normally gets its ANGLE path from
# virgl_test_server_android --angle-vulkan.  vhost-device-gpu embeds the
# library instead, so provide the conventional SONAMEs through a tiny private
# runtime shim and put it first in LD_LIBRARY_PATH.
link_angle() {
  local conventional="$1" angle_name="$2"
  local target="$ANGLE_PREFIX/$angle_name"
  [ -f "$target" ] || {
    echo "[vhost-gpu-build] ANGLE library missing: $target" >&2
    exit 1
  }
  ln -sfn "$target" "$INSTALL/angle-shim/$conventional"
}
link_angle libEGL.so libEGL_angle.so
link_angle libGLESv2.so libGLESv2_angle.so
if [ -f "$ANGLE_PREFIX/libGLESv1_CM_angle.so" ]; then
  link_angle libGLESv1_CM.so libGLESv1_CM_angle.so
fi

SRC="$WORK/vhost-device"
if [ ! -d "$SRC/.git" ]; then
  git clone "$VHOST_REPO" "$SRC"
fi
git -C "$SRC" fetch origin
git -C "$SRC" reset --hard "$VHOST_COMMIT"
git -C "$SRC" clean -ffd

# vhost-device-gpu 0.2.0 advertises RESOURCE_BLOB even though the command is
# currently an explicit panic in device.rs.  Modern Mesa may then select that
# unsupported path.  For Vessel's first VirGL backend use the classic 3D
# resource path and advertise only features the daemon actually implements.
python - "$SRC/vhost-device-gpu/src/device.rs" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = "            | (1 << VIRTIO_GPU_F_RESOURCE_BLOB)\n"
if needle in s:
    s = s.replace(needle, "", 1)
elif "| (1 << VIRTIO_GPU_F_RESOURCE_BLOB)" in s:
    raise SystemExit("unexpected RESOURCE_BLOB feature formatting")
p.write_text(s)
if "            | (1 << VIRTIO_GPU_F_RESOURCE_BLOB)" in p.read_text():
    raise SystemExit("RESOURCE_BLOB advertisement patch failed")
print("[vhost-gpu-build] disabled unsupported RESOURCE_BLOB advertisement")
PY

export PKG_CONFIG_PATH="$INSTALL/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export BINDGEN_EXTRA_CLANG_ARGS="-I$VIRGL_SRC/src ${BINDGEN_EXTRA_CLANG_ARGS:-}"
export LIBRARY_PATH="$VIRGL_PREFIX/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,$VIRGL_PREFIX/lib ${RUSTFLAGS:-}"

# Bindgen needs libclang.  Clang's Termux package normally provides it; locate
# it explicitly when possible so the build does not depend on shell defaults.
LIBCLANG_SO="$(find "$PREFIX/lib" -maxdepth 3 -type f -name 'libclang.so*' -print -quit 2>/dev/null || true)"
if [ -n "$LIBCLANG_SO" ]; then
  export LIBCLANG_PATH="$(dirname "$LIBCLANG_SO")"
fi

pkg-config --modversion virglrenderer
pkg-config --libs --cflags virglrenderer

echo "[vhost-gpu-build] building rust-vmm vhost-device-gpu $VHOST_COMMIT..."
cd "$SRC"
CARGO_TARGET_DIR="$WORK/target" \
  cargo build --locked --release \
    -p vhost-device-gpu \
    --no-default-features \
    --features backend-virgl

BIN="$WORK/target/release/vhost-device-gpu"
[ -x "$BIN" ] || {
  echo "[vhost-gpu-build] cargo finished without vhost-device-gpu binary" >&2
  exit 1
}
install -m0755 "$BIN" "$INSTALL/bin/vhost-device-gpu"

cat >"$INSTALL/env.sh" <<EOF
export VESSEL_VHOST_GPU_PREFIX="$INSTALL"
export LD_LIBRARY_PATH="$INSTALL/angle-shim:$VIRGL_PREFIX/lib:\${LD_LIBRARY_PATH:-}"
EOF

# Smoke-test dynamic loading and CLI parsing without opening a GPU socket.
set +e
LD_LIBRARY_PATH="$INSTALL/angle-shim:$VIRGL_PREFIX/lib:${LD_LIBRARY_PATH:-}" \
  "$INSTALL/bin/vhost-device-gpu" --version
rc=$?
set -e
[ "$rc" -eq 0 ] || {
  echo "[vhost-gpu-build] built binary could not start (rc=$rc)" >&2
  exit "$rc"
}

echo "[vhost-gpu-build] READY"
echo "[vhost-gpu-build] binary: $INSTALL/bin/vhost-device-gpu"
echo "[vhost-gpu-build] ANGLE:  $ANGLE_PREFIX"
echo "[vhost-gpu-build] VirGL:  $VIRGL_LIB"
