#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALL="${VESSEL_VHOST_GPU_PREFIX:-$PREFIX/opt/vessel-vhost-gpu}"
WORK="${VESSEL_VHOST_GPU_WORK:-$HOME/.cache/vessel-vhost-gpu}"
VIRGL_PREFIX="$PREFIX/opt/virglrenderer-android"
VIRGL_INCLUDE="$INSTALL/include"
MARKER="$INSTALL/.vessel-raw-scanout-v1"

# Reuse the normal builder for all dependencies, pinned source and ANGLE/VirGL
# integration. Its Cargo target is persistent, so the second build below is
# incremental and only recompiles the patched GPU crate.
bash "$ROOT/tools/venus_poc/build_vhost_device_gpu_termux.sh"

SRC="$WORK/vhost-device"
python3 "$ROOT/tools/venus_poc/patch_vhost_device_gpu_raw_scanout.py" "$SRC"

grep -Fq 'VESSEL_RAW_SCANOUT_V1' "$SRC/vhost-device-gpu/src/backend/virgl.rs"
grep -Fq '.use_venus(false)' "$SRC/vhost-device-gpu/src/backend/virgl.rs"

export PKG_CONFIG_PATH="$INSTALL/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export BINDGEN_EXTRA_CLANG_ARGS="-I$VIRGL_INCLUDE ${BINDGEN_EXTRA_CLANG_ARGS:-}"
export LIBRARY_PATH="$VIRGL_PREFIX/lib${LIBRARY_PATH:+:$LIBRARY_PATH}"
export RUSTFLAGS="-C link-arg=-Wl,-rpath,$VIRGL_PREFIX/lib ${RUSTFLAGS:-}"
LIBCLANG_SO="$(find "$PREFIX/lib" -maxdepth 3 -type f -name 'libclang.so*' -print -quit 2>/dev/null || true)"
[ -z "$LIBCLANG_SO" ] || export LIBCLANG_PATH="$(dirname "$LIBCLANG_SO")"

cd "$SRC"
CARGO_TARGET_DIR="$WORK/target" \
  cargo build --locked --release \
    -p vhost-device-gpu \
    --no-default-features \
    --features backend-virgl

BIN="$WORK/target/release/vhost-device-gpu"
[ -x "$BIN" ] || { echo "[vhost-gpu-raw] missing rebuilt binary" >&2; exit 1; }
install -m0755 "$BIN" "$INSTALL/bin/vhost-device-gpu"
printf 'raw-scanout-v1\n' >"$MARKER"

echo "[vhost-gpu-raw] READY"
echo "[vhost-gpu-raw] rendering: guest GL -> VirGL -> ANGLE/Vulkan -> Adreno"
echo "[vhost-gpu-raw] display: vhost-user-gpu UPDATE -> Vessel Android Vulkan presenter"
