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

# Do not run a full pkg update on every retry. Install only tools that are
# actually missing; Cargo's target directory is persistent so failed builds
# resume from the already-compiled crates.
missing_pkgs=()
command -v cargo >/dev/null 2>&1 || missing_pkgs+=(rust)
command -v clang >/dev/null 2>&1 || missing_pkgs+=(clang)
command -v git >/dev/null 2>&1 || missing_pkgs+=(git)
command -v curl >/dev/null 2>&1 || missing_pkgs+=(curl)
command -v tar >/dev/null 2>&1 || missing_pkgs+=(tar)
command -v pkg-config >/dev/null 2>&1 || missing_pkgs+=(pkg-config)
command -v make >/dev/null 2>&1 || missing_pkgs+=(make)
if [ "${#missing_pkgs[@]}" -gt 0 ]; then
  echo "[vhost-gpu-build] installing missing prerequisites: ${missing_pkgs[*]}"
  pkg install -y "${missing_pkgs[@]}"
else
  echo "[vhost-gpu-build] build prerequisites already installed"
fi

for cmd in cargo clang git curl tar pkg-config make; do need "$cmd"; done

# Reuse Vessel's known-good Android virglrenderer build. It is patched for
# ANGLE and already works on this phone. Build it only if missing.
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

VIRGL_INCLUDE="$INSTALL/include"
VIRGL_PUBLIC="$VIRGL_INCLUDE/virgl"
mkdir -p "$WORK" "$INSTALL/bin" "$INSTALL/lib/pkgconfig" "$VIRGL_PUBLIC"

# Termux's runtime-only virglrenderer install has the .so but not the public
# development headers/pkg-config metadata. Fetch the matching 1.3.0 source and
# recreate the normal installed header layout expected by virglrenderer-sys:
#   <includedir>/virgl/virglrenderer.h
#   <includedir>/virgl/virgl-version.h
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
  echo "[vhost-gpu-build] matching virglrenderer header was not extracted" >&2
  exit 1
}
[ -f "$VIRGL_SRC/src/virgl-version.h.meson" ] || {
  echo "[vhost-gpu-build] matching virgl-version.h template was not extracted" >&2
  exit 1
}

install -m0644 "$VIRGL_SRC/src/virglrenderer.h" "$VIRGL_PUBLIC/virglrenderer.h"
IFS=. read -r VIRGL_MAJOR VIRGL_MINOR VIRGL_MICRO <<<"$VIRGL_VERSION"
sed \
  -e "s/@VIRGL_MAJOR_VERSION@/$VIRGL_MAJOR/g" \
  -e "s/@VIRGL_MINOR_VERSION@/$VIRGL_MINOR/g" \
  -e "s/@VIRGL_MICRO_VERSION@/$VIRGL_MICRO/g" \
  "$VIRGL_SRC/src/virgl-version.h.meson" >"$VIRGL_PUBLIC/virgl-version.h"

grep -Fq '#define VIRGL_MAJOR_VERSION' "$VIRGL_PUBLIC/virgl-version.h"
grep -Fq '#define VIRGL_MINOR_VERSION' "$VIRGL_PUBLIC/virgl-version.h"
grep -Fq '#define VIRGL_MICRO_VERSION' "$VIRGL_PUBLIC/virgl-version.h"

cat >"$INSTALL/lib/pkgconfig/virglrenderer.pc" <<EOF
prefix=$VIRGL_PREFIX
exec_prefix=\${prefix}
libdir=\${prefix}/lib
includedir=$VIRGL_INCLUDE

Name: virglrenderer
Description: Vessel Android virglrenderer
Version: $VIRGL_VERSION
Libs: -L\${libdir} -lvirglrenderer
Cflags: -I\${includedir}
EOF

# Fail here with a small, readable diagnostic instead of 80 crates into Cargo if
# the public-header layout ever changes again.
printf '#include <virgl/virglrenderer.h>\n' | \
  clang -E -x c -I"$VIRGL_INCLUDE" - >/dev/null

echo "[vhost-gpu-build] virgl headers staged: $VIRGL_PUBLIC"

# ANGLE is selected at runtime through Termux libepoxy's
# epoxy_set_library_path() hook, which opens the real *_angle.so files by
# absolute path. Do not alias those DSOs to Android framework SONAMEs such as
# libEGL.so or libGLESv1_CM.so: Bionic's VERNEED resolver checks the child's
# DT_SONAME, so a filename alias to libGLESv1_CM_angle.so can break
# libandroid_runtime.so before EGL even initializes.
for angle_lib in libEGL_angle.so libGLESv2_angle.so; do
  [ -f "$ANGLE_PREFIX/$angle_lib" ] || {
    echo "[vhost-gpu-build] ANGLE library missing: $ANGLE_PREFIX/$angle_lib" >&2
    exit 1
  }
done

SRC="$WORK/vhost-device"
if [ ! -d "$SRC/.git" ]; then
  git clone "$VHOST_REPO" "$SRC"
fi
git -C "$SRC" fetch origin
git -C "$SRC" reset --hard "$VHOST_COMMIT"
git -C "$SRC" clean -ffd

# vhost-device-gpu 0.2.0 advertises RESOURCE_BLOB even though the command is
# currently an explicit panic in device.rs. Modern Mesa may then select that
# unsupported path. For Vessel's first VirGL backend use the classic 3D
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

# Upstream vhost-device-gpu currently initializes virglrenderer with Venus and
# external-blob support unconditionally, even when only the virgl/virgl2
# capsets are selected. That contradicts its own no-RESOURCE_BLOB limitation
# and can make the renderer worker die on the very first control-queue command.
# Vessel's first backend is intentionally classic VirGL only: guest OpenGL ->
# VirGL -> host GLES/ANGLE -> Vulkan/Adreno. Keep Venus disabled until the
# vhost-user blob path exists end-to-end.
python - "$SRC/vhost-device-gpu/src/backend/virgl.rs" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = """            .use_virgl(true)\n            .use_venus(true)\n            .use_egl(config.flags().use_egl)\n            .use_gles(config.flags().use_gles)\n            .use_glx(config.flags().use_glx)\n            .use_surfaceless(config.flags().use_surfaceless)\n            .use_external_blob(true)\n"""
new = """            .use_virgl(true)\n            .use_venus(false)\n            .use_egl(config.flags().use_egl)\n            .use_gles(config.flags().use_gles)\n            .use_glx(config.flags().use_glx)\n            .use_surfaceless(config.flags().use_surfaceless)\n            .use_external_blob(false)\n"""
if old not in s:
    raise SystemExit("unexpected VirglRendererFlags formatting")
s = s.replace(old, new, 1)
p.write_text(s)
final = p.read_text()
if ".use_venus(false)" not in final or ".use_external_blob(false)" not in final:
    raise SystemExit("classic VirGL renderer patch failed")
print("[vhost-gpu-build] forced classic VirGL renderer (Venus/external blobs disabled)")
PY

export PKG_CONFIG_PATH="$INSTALL/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export BINDGEN_EXTRA_CLANG_ARGS="-I$VIRGL_INCLUDE ${BINDGEN_EXTRA_CLANG_ARGS:-}"
export LIBRARY_PATH="$VIRGL_PREFIX/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,$VIRGL_PREFIX/lib ${RUSTFLAGS:-}"

# Bindgen needs libclang. Clang's Termux package normally provides it; locate
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
export LD_LIBRARY_PATH="$VIRGL_PREFIX/lib:\${LD_LIBRARY_PATH:-}"
EOF

# Smoke-test dynamic loading and CLI parsing without injecting fake Android GL
# SONAMEs. The runtime selects ANGLE explicitly through epoxy_set_library_path.
set +e
LD_LIBRARY_PATH="$VIRGL_PREFIX/lib:${LD_LIBRARY_PATH:-}" \
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