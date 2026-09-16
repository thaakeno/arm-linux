#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK_HOME is required" >&2; exit 2; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK="${VESSEL_NATIVE_WORK:-$ROOT/.vessel-native-build}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
VHOST="$WORK/vhost-device"
INCLUDE_ROOT="$WORK/include"
CARGO_TARGET_DIR="$WORK/vhost-device-target"

[ -d "$VHOST/.git" ] || { echo "prepare/build the native runtime first" >&2; exit 2; }
[ -s "$OUT/libvessel_virglrenderer.so" ] || exit 2
[ -s "$OUT/libvessel_epoxy.so" ] || exit 2
[ -s "$OUT/libEGL_angle.so" ] || { echo "bundled ANGLE EGL is missing" >&2; exit 2; }
[ -s "$OUT/libGLESv2_angle.so" ] || { echo "bundled ANGLE GLES is missing" >&2; exit 2; }
[ -d "$INCLUDE_ROOT/virgl" ] || exit 2
mkdir -p "$CARGO_TARGET_DIR"

echo "[vessel-ahb] building synchronized GPU-only Android HardwareBuffer bridge"
CXX="$TOOLCHAIN/bin/aarch64-linux-android29-clang++"
NM="$TOOLCHAIN/bin/llvm-nm"
"$CXX" --sysroot="$TOOLCHAIN/sysroot" -std=c++17 -fPIC -shared \
  -Wall -Wextra -Werror \
  -I"$INCLUDE_ROOT" \
  "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp" \
  -L"$OUT" -Wl,-soname,libvessel_ahb_bridge.so -Wl,-rpath,'$ORIGIN' \
  -lvessel_virglrenderer -lEGL_angle -lGLESv2_angle -landroid -llog \
  -o "$OUT/libvessel_ahb_bridge.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_ahb_bridge.so"

# VirGL and the bridge must dispatch through the exact same bundled ANGLE
# implementation so shared GL contexts, textures and GLsync objects are valid.
BRIDGE_NEEDED="$(patchelf --print-needed "$OUT/libvessel_ahb_bridge.so")"
grep -Fx 'libEGL_angle.so' <<<"$BRIDGE_NEEDED" >/dev/null
grep -Fx 'libGLESv2_angle.so' <<<"$BRIDGE_NEEDED" >/dev/null
if grep -E '^(libEGL\.so|libGLESv2\.so)$' <<<"$BRIDGE_NEEDED" >/dev/null; then
  echo "AHardwareBuffer bridge accidentally depends on Android system EGL/GLES" >&2
  printf '%s\n' "$BRIDGE_NEEDED" >&2
  exit 5
fi
for sym in eglGetCurrentDisplay eglGetCurrentContext eglGetError eglGetProcAddress; do
  "$NM" -D "$OUT/libEGL_angle.so" | grep -E " [TW] ${sym}$" >/dev/null || {
    echo "bundled ANGLE EGL is missing $sym" >&2
    exit 5
  }
done
for sym in glBindFramebuffer glBlitFramebuffer glCheckFramebufferStatus glFenceSync glWaitSync glDeleteSync glFlush glGenFramebuffers glGetError; do
  "$NM" -D "$OUT/libGLESv2_angle.so" | grep -E " [TW] ${sym}$" >/dev/null || {
    echo "bundled ANGLE GLES is missing $sym" >&2
    exit 5
  }
done
if grep -Eq '\bglFinish[[:space:]]*\(' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"; then
  echo "synchronous glFinish survived in the frame hot path" >&2
  exit 5
fi
grep -Fq 'eglDupNativeFenceFDANDROID' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'glFenceSync' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'glWaitSync' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
echo "[vessel-ahb] bridge pinned to ANGLE with GL context sync + Android native fence FDs"

# Catch C/C++ ABI mismatches around virglrenderer.
"$NM" -D --undefined-only "$OUT/libvessel_ahb_bridge.so" | grep -F 'virgl_renderer_resource_get_info' >/dev/null
if "$NM" -D --undefined-only "$OUT/libvessel_ahb_bridge.so" | grep -F '_Z32virgl_renderer_resource_get_info' >/dev/null; then
  echo "AHardwareBuffer bridge references C++-mangled virgl_renderer_resource_get_info" >&2
  exit 5
fi
"$NM" -D "$OUT/libvessel_virglrenderer.so" | grep -F 'virgl_renderer_resource_get_info' >/dev/null

# Rebuild pinned rust-vmm vhost-device-gpu. Standard vhost-user display remains
# for EDID/cursor metadata; scanout pixels use the synchronized AHB transport.
git -C "$VHOST" reset --hard 20fa14c4c56e40a12104794a934dd70dc7642ff2
git -C "$VHOST" clean -ffd
python3 - "$VHOST/vhost-device-gpu/src/device.rs" "$VHOST/vhost-device-gpu/src/backend/virgl.rs" <<'PY'
from pathlib import Path
import sys

dev=Path(sys.argv[1]); s=dev.read_text(); needle='            | (1 << VIRTIO_GPU_F_RESOURCE_BLOB)\n'
if s.count(needle)!=1: raise SystemExit('unexpected RESOURCE_BLOB feature layout')
dev.write_text(s.replace(needle,'',1))

vir=Path(sys.argv[2]); s=vir.read_text()
old='''            .use_virgl(true)\n            .use_venus(true)\n            .use_egl(config.flags().use_egl)\n            .use_gles(config.flags().use_gles)\n            .use_glx(config.flags().use_glx)\n            .use_surfaceless(config.flags().use_surfaceless)\n            .use_external_blob(true)\n'''
new='''            .use_virgl(true)\n            .use_venus(false)\n            .use_egl(config.flags().use_egl)\n            .use_gles(config.flags().use_gles)\n            .use_glx(config.flags().use_glx)\n            .use_surfaceless(config.flags().use_surfaceless)\n            .use_external_blob(false)\n'''
if s.count(old)!=1: raise SystemExit('unexpected VirGL flags layout')
vir.write_text(s.replace(old,new,1))
PY
python3 "$ROOT/tools/vessel_native/patch_vhost_gpu_android_ahb.py" "$VHOST"
grep -Fq 'VESSEL_ANDROID_AHB_SCANOUT_V2' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_note_submit' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_set_scanout' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
! grep -A100 'fn set_scanout' "$VHOST/vhost-device-gpu/src/backend/virgl.rs" | head -100 | grep -q 'export_resource_dmabuf'

rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android29-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export PKG_CONFIG_ALLOW_CROSS=1
export PKG_CONFIG_PATH="$WORK/pkgconfig"
export BINDGEN_EXTRA_CLANG_ARGS="--target=aarch64-linux-android29 --sysroot=$TOOLCHAIN/sysroot -I$INCLUDE_ROOT"
CLANG_SO="$(find /usr/lib -name 'libclang.so*' -print -quit 2>/dev/null || true)"
[ -n "$CLANG_SO" ] || { echo "libclang not found" >&2; exit 5; }
export LIBCLANG_PATH="$(dirname "$CLANG_SO")"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN -L native=$OUT -l dylib=vessel_ahb_bridge"
export CARGO_TARGET_DIR

(cd "$VHOST"; cargo build --locked --release --target aarch64-linux-android -p vhost-device-gpu --no-default-features --features backend-virgl)
VHOST_BIN="$CARGO_TARGET_DIR/aarch64-linux-android/release/vhost-device-gpu"
[ -x "$VHOST_BIN" ] || exit 5
install -m0755 "$VHOST_BIN" "$OUT/libvessel_vhost_gpu.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_gpu.so"
while read -r dep; do
  case "$dep" in
    libvirglrenderer.so*) patchelf --replace-needed "$dep" libvessel_virglrenderer.so "$OUT/libvessel_vhost_gpu.so";;
  esac
done < <(patchelf --print-needed "$OUT/libvessel_vhost_gpu.so")

VHOST_NEEDED="$(patchelf --print-needed "$OUT/libvessel_vhost_gpu.so")"
grep -F 'libvessel_ahb_bridge.so' <<<"$VHOST_NEEDED" >/dev/null
strings "$OUT/libvessel_vhost_gpu.so" | grep -F 'Vessel Android HardwareBuffer scanout' >/dev/null

MANIFEST="$ROOT/app/src/main/assets/vessel/runtime-build.txt"
mkdir -p "$(dirname "$MANIFEST")"
ANGLE_PACKAGE="$(tail -1 "$WORK/pkg-meta/angle-android.txt" 2>/dev/null || echo cached)"
VIRGL_PACKAGE="$(tail -1 "$WORK/pkg-meta/virglrenderer-android.txt" 2>/dev/null || echo cached)"
{
  echo protocol=39
  echo runtime=v39-self-contained-ahb-syncfd-virtio-input-r5
  echo "kernel_sha256=$(sha256sum "$OUT/libvessel_uml.so" | awk '{print $1}')"
  echo "vhost_gpu_sha256=$(sha256sum "$OUT/libvessel_vhost_gpu.so" | awk '{print $1}')"
  echo "vhost_input_sha256=$(sha256sum "$OUT/libvessel_vhost_input.so" | awk '{print $1}')"
  echo "angle_package=$ANGLE_PACKAGE"
  echo "virgl_package=$VIRGL_PACKAGE"
  echo rootfs=external:Download/LinuxPC/Vessel-Debian/debian-docker.ext4
  echo display_bridge=android-hardware-buffer-syncfd-v1
} > "$MANIFEST"

echo "[vessel-ahb] synchronized Android HardwareBuffer runtime ready"
file "$OUT/libvessel_ahb_bridge.so" "$OUT/libvessel_vhost_gpu.so"
patchelf --print-needed "$OUT/libvessel_ahb_bridge.so"
patchelf --print-needed "$OUT/libvessel_vhost_gpu.so"
cat "$MANIFEST"
