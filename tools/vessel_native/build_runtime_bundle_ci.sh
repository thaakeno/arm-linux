#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK_HOME is required" >&2; exit 2; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$TOOLCHAIN/bin/aarch64-linux-android29-clang" ] || { echo "Android NDK clang missing" >&2; exit 2; }
WORK="${VESSEL_NATIVE_WORK:-$ROOT/.vessel-native-build}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
rm -rf "$WORK" "$OUT"; mkdir -p "$WORK" "$OUT"
need(){ command -v "$1" >/dev/null 2>&1 || { echo "missing tool: $1" >&2; exit 2; }; }
for x in curl unzip gzip dpkg-deb patchelf git python3 cargo rustup pkg-config sha256sum strings file; do need "$x"; done

download_verified(){
  local url="$1" sha="$2" out="$3"
  echo "[vessel-native] GET $url"
  curl -fL --retry 4 --retry-delay 2 -o "$out.tmp" "$url"
  echo "$sha  $out.tmp" | sha256sum -c -
  mv "$out.tmp" "$out"
}

# Build the exact UML kernel used by this APK instead of downloading a rolling
# release. This prevents the app and kernel capabilities from drifting apart.
KERNEL_WORK="$WORK/kernel-build"
VESSEL_KERNEL_WORK="$KERNEL_WORK" NDK="$NDK" JOBS="${JOBS:-4}" bash "$ROOT/tools/venus_poc/build_uml_smp_android.sh"
KERNEL="$KERNEL_WORK/artifacts/linux-umshm"
STUB="$KERNEL_WORK/artifacts/stub_exe-umshm"
KCONFIG="$KERNEL_WORK/artifacts/vessel-uml-smp.config"
[ -s "$KERNEL" ] && [ -s "$STUB" ] || { echo "rebuilt UML kernel is incomplete" >&2; exit 3; }
grep -qx 'CONFIG_VIRTIO_INPUT=y' "$KCONFIG"
grep -qx 'CONFIG_VIRTIO_UML=y' "$KCONFIG"
grep -qx 'CONFIG_DRM_VIRTIO_GPU=y' "$KCONFIG"
install -m0755 "$KERNEL" "$OUT/libvessel_uml.so"
install -m0755 "$STUB" "$OUT/libvessel_stub.so"
strings "$OUT/libvessel_uml.so" | grep -Fq 'Vessel vhost-user-gpu display relay attached'

download_verified "https://github.com/zalexdev/linux-um-arm64/releases/download/prebuilt-20260816/umnet" "a6e1af3986d354aa9268a6dce665d5c08ca761da2c79ca7dc5709b12121a5694" "$OUT/libvessel_umnet.so"
download_verified "https://github.com/zalexdev/linux-um-arm64/releases/download/prebuilt-20260816/passt" "122efb3aa39e2069e7bddd5791b5ab04b54f06a69d89a22dd70c50834d18e75f" "$OUT/libvessel_passt.so"
chmod 0755 "$OUT/libvessel_umnet.so" "$OUT/libvessel_passt.so"

# Build-time source only. Vessel has no Termux package/runtime dependency.
TERMUX_BASE="https://packages.termux.dev/apt/termux-main"
curl -fL --retry 4 -o "$WORK/Packages.gz" "$TERMUX_BASE/dists/stable/main/binary-aarch64/Packages.gz"
gzip -dc "$WORK/Packages.gz" > "$WORK/Packages"
python3 - "$WORK/Packages" "$WORK/pkg-meta" <<'PY'
from pathlib import Path
import sys
text=Path(sys.argv[1]).read_text(errors='replace'); out=Path(sys.argv[2]); out.mkdir(parents=True,exist_ok=True)
wanted={'angle-android','virglrenderer-android'}; found={}
for stanza in text.split('\n\n'):
    f={}
    for line in stanza.splitlines():
        if ': ' in line:
            k,v=line.split(': ',1); f[k]=v
    if f.get('Package') in wanted: found[f['Package']]=(f.get('Filename'),f.get('SHA256'),f.get('Version','unknown'))
for p in sorted(wanted):
    if p not in found or not all(found[p][:2]): raise SystemExit(f'missing {p} in Termux package index')
    fn,sha,ver=found[p]; (out/f'{p}.txt').write_text(f'{fn}\n{sha}\n{ver}\n')
PY
fetch_termux_deb(){
  local pkg="$1"; mapfile -t meta < "$WORK/pkg-meta/$pkg.txt"; local file="${meta[0]#./}" sha="${meta[1]}"
  download_verified "$TERMUX_BASE/$file" "$sha" "$WORK/$pkg.deb"
  mkdir -p "$WORK/$pkg"; dpkg-deb -x "$WORK/$pkg.deb" "$WORK/$pkg"
}
fetch_termux_deb angle-android
fetch_termux_deb virglrenderer-android
ANGLE_DIR="$(find "$WORK/angle-android" -type d -path '*/opt/angle-android/vulkan' -print -quit)"
[ -n "$ANGLE_DIR" ] || { echo "ANGLE Vulkan directory missing" >&2; exit 4; }
for lib in libEGL_angle.so libGLESv2_angle.so; do [ -f "$ANGLE_DIR/$lib" ] || exit 4; install -m0644 "$ANGLE_DIR/$lib" "$OUT/$lib"; done
[ ! -f "$ANGLE_DIR/libGLESv1_CM_angle.so" ] || install -m0644 "$ANGLE_DIR/libGLESv1_CM_angle.so" "$OUT/libGLESv1_CM_angle.so"

VIRGL_REAL="$(find "$WORK/virglrenderer-android" -type f -name 'libvirglrenderer.so*' -print | sort | tail -1)"
EPOXY_REAL="$(find "$WORK/virglrenderer-android" -type f -name 'libepoxy.so*' -print | sort | tail -1)"
[ -n "$VIRGL_REAL" ] && [ -n "$EPOXY_REAL" ] || { echo "virglrenderer package libraries missing" >&2; exit 4; }
cp -L "$VIRGL_REAL" "$OUT/libvessel_virglrenderer.so"; cp -L "$EPOXY_REAL" "$OUT/libvessel_epoxy.so"
chmod 0644 "$OUT/libvessel_virglrenderer.so" "$OUT/libvessel_epoxy.so"
patchelf --set-soname libvessel_epoxy.so --set-rpath '$ORIGIN' "$OUT/libvessel_epoxy.so"
patchelf --set-soname libvessel_virglrenderer.so --set-rpath '$ORIGIN' "$OUT/libvessel_virglrenderer.so"
while read -r dep; do case "$dep" in libepoxy.so*) patchelf --replace-needed "$dep" libvessel_epoxy.so "$OUT/libvessel_virglrenderer.so";; esac; done < <(patchelf --print-needed "$OUT/libvessel_virglrenderer.so")

VIRGL_VERSION=1.3.0; VIRGL_TAR="$WORK/virglrenderer.tar.gz"
download_verified "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz" "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce" "$VIRGL_TAR"
tar -xzf "$VIRGL_TAR" -C "$WORK"
VIRGL_SRC="$WORK/virglrenderer-virglrenderer-$VIRGL_VERSION"
INCLUDE_ROOT="$WORK/include"
INC="$INCLUDE_ROOT/virgl"
mkdir -p "$INC" "$WORK/pkgconfig"
install -m0644 "$VIRGL_SRC/src/virglrenderer.h" "$INC/virglrenderer.h"
IFS=. read -r VMAJ VMIN VMIC <<< "$VIRGL_VERSION"
sed -e "s/@VIRGL_MAJOR_VERSION@/$VMAJ/g" -e "s/@VIRGL_MINOR_VERSION@/$VMIN/g" -e "s/@VIRGL_MICRO_VERSION@/$VMIC/g" "$VIRGL_SRC/src/virgl-version.h.meson" > "$INC/virgl-version.h"
cat > "$WORK/pkgconfig/virglrenderer.pc" <<EOF
prefix=$WORK
libdir=$OUT
includedir=$INCLUDE_ROOT
Name: virglrenderer
Description: Vessel Android VirGL renderer
Version: $VIRGL_VERSION
Libs: -L\${libdir} -lvessel_virglrenderer
Cflags: -I\${includedir}
EOF

rustup target add aarch64-linux-android
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android29-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android29-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,\$ORIGIN"

# Android-owned vhost-user virtio-input backend. This consumes local Vessel
# input packets and exposes real Linux virtio-input devices; guest networking is
# not involved in input delivery.
INPUT_TARGET="$WORK/vessel-input-target"
cargo build --release --target aarch64-linux-android --target-dir "$INPUT_TARGET" --manifest-path "$ROOT/tools/vessel_native/vhost-device-vessel-input/Cargo.toml"
INPUT_BIN="$INPUT_TARGET/aarch64-linux-android/release/vhost-device-vessel-input"
[ -x "$INPUT_BIN" ] || { echo "Vessel virtio-input backend missing" >&2; exit 5; }
install -m0755 "$INPUT_BIN" "$OUT/libvessel_vhost_input.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_input.so"

VHOST="$WORK/vhost-device"
git clone --filter=blob:none https://github.com/rust-vmm/vhost-device.git "$VHOST"
git -C "$VHOST" checkout 20fa14c4c56e40a12104794a934dd70dc7642ff2
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
export PKG_CONFIG_ALLOW_CROSS=1 PKG_CONFIG_PATH="$WORK/pkgconfig"
export BINDGEN_EXTRA_CLANG_ARGS="--target=aarch64-linux-android29 --sysroot=$TOOLCHAIN/sysroot -I$INCLUDE_ROOT"
CLANG_SO="$(find /usr/lib -name 'libclang.so*' -print -quit 2>/dev/null || true)"; [ -n "$CLANG_SO" ] || { echo "libclang not found" >&2; exit 5; }
export LIBCLANG_PATH="$(dirname "$CLANG_SO")"
(cd "$VHOST"; cargo build --locked --release --target aarch64-linux-android -p vhost-device-gpu --no-default-features --features backend-virgl)
VHOST_BIN="$VHOST/target/aarch64-linux-android/release/vhost-device-gpu"; [ -x "$VHOST_BIN" ] || exit 5
install -m0755 "$VHOST_BIN" "$OUT/libvessel_vhost_gpu.so"; patchelf --set-rpath '$ORIGIN' "$OUT/libvessel_vhost_gpu.so"
while read -r dep; do case "$dep" in libvirglrenderer.so*) patchelf --replace-needed "$dep" libvessel_virglrenderer.so "$OUT/libvessel_vhost_gpu.so";; esac; done < <(patchelf --print-needed "$OUT/libvessel_vhost_gpu.so")

for f in "$OUT"/*.so; do
  if file "$f" | grep -q ELF; then
    rpath="$(patchelf --print-rpath "$f" 2>/dev/null || true)"
    case "$rpath" in *'/data/data/com.termux'*) echo "Termux RUNPATH leaked into $(basename "$f"): $rpath" >&2; exit 6;; esac
  fi
done
mkdir -p "$ROOT/app/src/main/assets/vessel"
{
  echo protocol=39; echo runtime=v39-self-contained-dmabuf-virtio-input-r1
  echo "kernel_sha256=$(sha256sum "$OUT/libvessel_uml.so" | awk '{print $1}')"
  echo "vhost_gpu_sha256=$(sha256sum "$OUT/libvessel_vhost_gpu.so" | awk '{print $1}')"
  echo "vhost_input_sha256=$(sha256sum "$OUT/libvessel_vhost_input.so" | awk '{print $1}')"
  echo "angle_package=$(tail -1 "$WORK/pkg-meta/angle-android.txt")"
  echo "virgl_package=$(tail -1 "$WORK/pkg-meta/virglrenderer-android.txt")"
  echo rootfs=external:Download/LinuxPC/Vessel-Debian/debian-docker.ext4
} > "$ROOT/app/src/main/assets/vessel/runtime-build.txt"
echo "[vessel-native] runtime bundle ready"; ls -lh "$OUT"; file "$OUT"/*.so
