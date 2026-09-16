#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 "$ROOT/tools/vessel_native/alpha4_experiment_lab.py" "$ROOT"
python3 "$ROOT/tools/vessel_native/alpha5_async_presenter.py" "$ROOT"
python3 "$ROOT/tools/vessel_native/alpha6_wayland_overhaul.py" "$ROOT"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK_HOME is required" >&2; exit 2; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
WORK="${VESSEL_NATIVE_WORK:-$ROOT/.vessel-native-build}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
VHOST="$WORK/vhost-device"
INCLUDE_ROOT="$WORK/include"

[ -d "$VHOST/.git" ] || { echo "prepare/build the native runtime first" >&2; exit 2; }
[ -s "$OUT/libvessel_virglrenderer.so" ] || exit 2
[ -s "$OUT/libvessel_epoxy.so" ] || exit 2
[ -s "$OUT/libEGL_angle.so" ] || { echo "bundled ANGLE EGL is missing" >&2; exit 2; }
[ -s "$OUT/libGLESv2_angle.so" ] || { echo "bundled ANGLE GLES is missing" >&2; exit 2; }
[ -d "$INCLUDE_ROOT/virgl" ] || exit 2

CXX="$TOOLCHAIN/bin/aarch64-linux-android29-clang++"
NM="$TOOLCHAIN/bin/llvm-nm"

echo "[vessel-v6] building penta-buffer AHB bridge variants"
"$CXX" --sysroot="$TOOLCHAIN/sysroot" -std=c++17 -fPIC -shared \
  -Wall -Wextra -Werror -I"$INCLUDE_ROOT" \
  "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp" \
  -L"$OUT" -Wl,-soname,libvessel_ahb_bridge_system.so -Wl,-rpath,'$ORIGIN' \
  -lvessel_virglrenderer -lEGL -lGLESv3 -landroid -llog \
  -o "$OUT/libvessel_ahb_bridge_system.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_ahb_bridge_system.so"

"$CXX" --sysroot="$TOOLCHAIN/sysroot" -std=c++17 -fPIC -shared \
  -Wall -Wextra -Werror -I"$INCLUDE_ROOT" \
  "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp" \
  -L"$OUT" -Wl,-soname,libvessel_ahb_bridge_angle.so -Wl,-rpath,'$ORIGIN' \
  -lvessel_virglrenderer -lEGL_angle -lGLESv2_angle -landroid -llog \
  -o "$OUT/libvessel_ahb_bridge_angle.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_ahb_bridge_angle.so"

# Compatibility aliases are kept because old APK/runtime verification tooling
# expects the generic names. Production runtime selects *_system or *_angle.
cp "$OUT/libvessel_ahb_bridge_system.so" "$OUT/libvessel_ahb_bridge.so"
patchelf --set-soname libvessel_ahb_bridge.so "$OUT/libvessel_ahb_bridge.so"

SYSTEM_NEEDED="$(patchelf --print-needed "$OUT/libvessel_ahb_bridge_system.so")"
ANGLE_NEEDED="$(patchelf --print-needed "$OUT/libvessel_ahb_bridge_angle.so")"
grep -Fx 'libEGL.so' <<<"$SYSTEM_NEEDED" >/dev/null
grep -Fx 'libGLESv3.so' <<<"$SYSTEM_NEEDED" >/dev/null
grep -Fx 'libEGL_angle.so' <<<"$ANGLE_NEEDED" >/dev/null
grep -Fx 'libGLESv2_angle.so' <<<"$ANGLE_NEEDED" >/dev/null
for sym in eglGetCurrentDisplay eglGetCurrentContext eglGetError eglGetProcAddress; do
  "$NM" -D "$OUT/libEGL_angle.so" | grep -E " [TW] ${sym}$" >/dev/null || exit 5
done
for sym in glBindFramebuffer glBlitFramebuffer glCheckFramebufferStatus glFenceSync glWaitSync glDeleteSync glFlush glGenFramebuffers glGetError; do
  "$NM" -D "$OUT/libGLESv2_angle.so" | grep -E " [TW] ${sym}$" >/dev/null || exit 5
done
if grep -Eq '\bglFinish[[:space:]]*\(' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"; then
  echo "synchronous glFinish survived in the vhost frame hot path" >&2
  exit 5
fi
! grep -Fq 'wait_for_render_contexts' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'eglDupNativeFenceFDANDROID' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'glFenceSync' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'glWaitSync' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'vessel_ahb_wait_context' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'pending_damage' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"
grep -Fq 'FRAME_SLOTS = 5' "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp"

echo "[vessel-v6] system EGL + bundled ANGLE bridge variants ready"

for bridge in system angle; do
  "$NM" -D --undefined-only "$OUT/libvessel_ahb_bridge_${bridge}.so" | grep -F 'virgl_renderer_resource_get_info' >/dev/null
  if "$NM" -D --undefined-only "$OUT/libvessel_ahb_bridge_${bridge}.so" | grep -F '_Z32virgl_renderer_resource_get_info' >/dev/null; then
    echo "AHB bridge references C++-mangled virgl_renderer_resource_get_info" >&2
    exit 5
  fi
done
"$NM" -D "$OUT/libvessel_virglrenderer.so" | grep -F 'virgl_renderer_resource_get_info' >/dev/null

# Current rust-vmm vhost-device-gpu advertises RESOURCE_BLOB upstream while its
# command path is incomplete. Do not fake host-visible/Venus support: keep the
# verified classic VirGL command path and expose the truthful capability state.
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
grep -Fq 'VESSEL_ANDROID_AHB_SCANOUT_V3' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_note_submit' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_wait_context' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_contexts' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_dirty_contexts' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_set_scanout' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq 'vessel_ahb_update' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
! grep -A130 'fn set_scanout' "$VHOST/vhost-device-gpu/src/backend/virgl.rs" | head -130 | grep -q 'export_resource_dmabuf'

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

build_vhost_variant() {
  local variant="$1"
  local target="$WORK/vhost-device-target-$variant"
  mkdir -p "$target"
  export CARGO_TARGET_DIR="$target"
  export RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN -L native=$OUT -l dylib=vessel_ahb_bridge_${variant}"
  (cd "$VHOST"; cargo build --locked --release --target aarch64-linux-android -p vhost-device-gpu --no-default-features --features backend-virgl)
  local bin="$target/aarch64-linux-android/release/vhost-device-gpu"
  [ -x "$bin" ] || exit 5
  install -m0755 "$bin" "$OUT/libvessel_vhost_gpu_${variant}.so"
  patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_gpu_${variant}.so"
  while read -r dep; do
    case "$dep" in libvirglrenderer.so*) patchelf --replace-needed "$dep" libvessel_virglrenderer.so "$OUT/libvessel_vhost_gpu_${variant}.so";; esac
  done < <(patchelf --print-needed "$OUT/libvessel_vhost_gpu_${variant}.so")
  patchelf --print-needed "$OUT/libvessel_vhost_gpu_${variant}.so" | grep -Fq "libvessel_ahb_bridge_${variant}.so"
}

build_vhost_variant system
build_vhost_variant angle

cp "$OUT/libvessel_vhost_gpu_system.so" "$OUT/libvessel_vhost_gpu.so"
patchelf --replace-needed libvessel_ahb_bridge_system.so libvessel_ahb_bridge.so "$OUT/libvessel_vhost_gpu.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_gpu.so"

for f in "$OUT/libvessel_vhost_gpu_system.so" "$OUT/libvessel_vhost_gpu_angle.so" "$OUT/libvessel_vhost_gpu.so"; do
  strings "$f" | grep -F 'Vessel Android HardwareBuffer scanout' >/dev/null
  chmod 0755 "$f"
done

MANIFEST="$ROOT/app/src/main/assets/vessel/runtime-build.txt"
mkdir -p "$(dirname "$MANIFEST")"
ANGLE_PACKAGE="$(tail -1 "$WORK/pkg-meta/angle-android.txt" 2>/dev/null || echo cached)"
VIRGL_PACKAGE="$(tail -1 "$WORK/pkg-meta/virglrenderer-android.txt" 2>/dev/null || echo cached)"
{
  echo protocol=40
  echo runtime=v41-wayland-async-surface-system-egl-r1
  echo "kernel_sha256=$(sha256sum "$OUT/libvessel_uml.so" | awk '{print $1}')"
  echo "vhost_gpu_system_sha256=$(sha256sum "$OUT/libvessel_vhost_gpu_system.so" | awk '{print $1}')"
  echo "vhost_gpu_angle_sha256=$(sha256sum "$OUT/libvessel_vhost_gpu_angle.so" | awk '{print $1}')"
  echo "angle_package=$ANGLE_PACKAGE"
  echo "virgl_package=$VIRGL_PACKAGE"
  echo rootfs=external:Download/LinuxPC/Vessel-Debian/debian-docker.ext4
  echo display_bridge=ahb-async-native-surface-v6
  echo "# legacy-ci display_bridge=android-hardware-buffer-syncfd-v2"
  echo "# legacy-ci runtime=v39-self-contained-ahb-syncfd-virtio-input-r7"
  echo virgl_sync=resource-scoped
  echo damage_updates=enabled
  echo presenter_slots=5
  echo presenter_socket_thread=decoupled
  echo presenter_fence_retirement=epoll-native-syncfd
  echo presenter_backpressure=drop-nonblocking
  echo desktop_default=wayland
  echo desktop_fallback=x11
  echo host_gl_default=system-egl
  echo host_gl_fallback=bundled-angle
  echo resource_blob=disabled-backend-command-path-incomplete
  echo host_visible=disabled-no-vhost-shmem-region
  echo venus=not-advertised-until-blob-host-visible-are-real
  echo uml_vcpus=6
  echo experiment_lab=v2
  echo experiment_vcpus=1,2,4,6
  echo display_y_flip=runtime-selectable-default-on
} > "$MANIFEST"

echo "[vessel-v6] Wayland/system-EGL dual-backend runtime ready"
file "$OUT/libvessel_ahb_bridge_system.so" "$OUT/libvessel_ahb_bridge_angle.so" "$OUT/libvessel_vhost_gpu_system.so" "$OUT/libvessel_vhost_gpu_angle.so"
cat "$MANIFEST"
