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

[ -d "$VHOST/.git" ] || { echo "run build_runtime_bundle_ci.sh first" >&2; exit 2; }
[ -s "$OUT/libvessel_virglrenderer.so" ] || exit 2
[ -s "$OUT/libvessel_epoxy.so" ] || exit 2
[ -d "$INCLUDE_ROOT/virgl" ] || exit 2

echo "[vessel-ahb] building GPU-only Android HardwareBuffer bridge"
CXX="$TOOLCHAIN/bin/aarch64-linux-android29-clang++"
"$CXX" --sysroot="$TOOLCHAIN/sysroot" -std=c++17 -fPIC -shared \
  -Wall -Wextra -Werror \
  -I"$INCLUDE_ROOT" \
  "$ROOT/tools/vessel_native/vessel_ahb_bridge.cpp" \
  -L"$OUT" -Wl,-soname,libvessel_ahb_bridge.so -Wl,-rpath,'$ORIGIN' \
  -lvessel_virglrenderer -lvessel_epoxy -landroid -llog \
  -o "$OUT/libvessel_ahb_bridge.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_ahb_bridge.so"

# Rebuild vhost-device-gpu from its pinned source with the Android-native
# scanout path. The normal vhost-user-gpu side channel stays active for EDID
# and cursor messages; only the scanout payload transport changes.
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
grep -Fq 'VESSEL_ANDROID_AHB_SCANOUT_V1' "$VHOST/vhost-device-gpu/src/backend/virgl.rs"
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

(cd "$VHOST"; cargo build --locked --release --target aarch64-linux-android -p vhost-device-gpu --no-default-features --features backend-virgl)
VHOST_BIN="$VHOST/target/aarch64-linux-android/release/vhost-device-gpu"
[ -x "$VHOST_BIN" ] || exit 5
install -m0755 "$VHOST_BIN" "$OUT/libvessel_vhost_gpu.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_gpu.so"
while read -r dep; do
  case "$dep" in
    libvirglrenderer.so*) patchelf --replace-needed "$dep" libvessel_virglrenderer.so "$OUT/libvessel_vhost_gpu.so";;
  esac
done < <(patchelf --print-needed "$OUT/libvessel_vhost_gpu.so")

patchelf --print-needed "$OUT/libvessel_vhost_gpu.so" | grep -Fq 'libvessel_ahb_bridge.so'
strings "$OUT/libvessel_vhost_gpu.so" | grep -Fq 'Vessel Android HardwareBuffer scanout'

# Keep the runtime manifest truthful for the APK produced by this workflow.
MANIFEST="$ROOT/app/src/main/assets/vessel/runtime-build.txt"
sed -i 's/^runtime=.*/runtime=v39-self-contained-ahb-virtio-input-r4/' "$MANIFEST"
sed -i '/^display_bridge=/d' "$MANIFEST"
echo 'display_bridge=android-hardware-buffer-zero-copy-v1' >> "$MANIFEST"
sed -i "s/^vhost_gpu_sha256=.*/vhost_gpu_sha256=$(sha256sum "$OUT/libvessel_vhost_gpu.so" | awk '{print $1}')/" "$MANIFEST"

echo "[vessel-ahb] Android HardwareBuffer runtime ready"
file "$OUT/libvessel_ahb_bridge.so" "$OUT/libvessel_vhost_gpu.so"
patchelf --print-needed "$OUT/libvessel_ahb_bridge.so"
patchelf --print-needed "$OUT/libvessel_vhost_gpu.so"
cat "$MANIFEST"
