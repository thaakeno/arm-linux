#!/usr/bin/env bash
set -euo pipefail

ROOTFS="${1:-}"
ARCHIVE="${2:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/direct_gpu_manifest.json"

if [[ -z "$ROOTFS" ]]; then
  echo "usage: $0 <debian-13-rootfs-directory> [mesa-archive]" >&2
  exit 2
fi
ROOTFS="$(realpath -m "$ROOTFS")"
[[ -d "$ROOTFS" ]] || { echo "rootfs does not exist: $ROOTFS" >&2; exit 2; }
[[ -f "$ROOTFS/etc/os-release" ]] || { echo "rootfs has no /etc/os-release" >&2; exit 2; }

readarray -t META < <(python3 - "$MANIFEST" <<'PY'
import json, sys
m=json.load(open(sys.argv[1], encoding="utf-8"))
for k in ("url","sha256","archive","version","marker"):
    print(m[k])
PY
)
URL="${META[0]}"
EXPECTED_SHA="${META[1]}"
ARCHIVE_NAME="${META[2]}"
VERSION="${META[3]}"
MARKER="${META[4]}"

# This package is built for Debian 13/Trixie. Refuse to overlay it on another
# distro instead of hoping ABI differences happen to work.
. "$ROOTFS/etc/os-release"
[[ "${ID:-}" == "debian" ]] || { echo "direct-GPU Mesa pin requires Debian, got ${ID:-unknown}" >&2; exit 3; }
case "${VERSION_ID:-}" in
  13|13.*) ;;
  *) echo "direct-GPU Mesa pin requires Debian 13, got ${VERSION_ID:-unknown}" >&2; exit 3 ;;
esac

if [[ -z "$ARCHIVE" ]]; then
  CACHE_DIR="${VESSEL_GPU_CACHE_DIR:-$SCRIPT_DIR/.cache}"
  mkdir -p "$CACHE_DIR"
  ARCHIVE="$CACHE_DIR/$ARCHIVE_NAME"
  if [[ ! -s "$ARCHIVE" ]]; then
    echo "Downloading pinned Mesa $VERSION..."
    curl --fail --location --retry 3 --connect-timeout 15 --output "$ARCHIVE.tmp" "$URL"
    mv -f "$ARCHIVE.tmp" "$ARCHIVE"
  fi
fi
ARCHIVE="$(realpath "$ARCHIVE")"
[[ -s "$ARCHIVE" ]] || { echo "Mesa archive missing: $ARCHIVE" >&2; exit 4; }

ACTUAL_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
[[ "$ACTUAL_SHA" == "$EXPECTED_SHA" ]] || {
  echo "Mesa archive SHA-256 mismatch" >&2
  echo "expected: $EXPECTED_SHA" >&2
  echo "actual:   $ACTUAL_SHA" >&2
  exit 5
}

# The upstream direct-extraction package is a DESTDIR archive rooted at ./.
# The checksum pins the exact artifact; still reject path traversal before
# writing into the rootfs.
python3 - "$ARCHIVE" <<'PY'
import sys, tarfile
archive=sys.argv[1]
with tarfile.open(archive, "r:gz") as tf:
    for member in tf.getmembers():
        name=member.name
        parts=[p for p in name.split("/") if p not in ("", ".")]
        if name.startswith("/") or ".." in parts:
            raise SystemExit(f"unsafe archive path: {name}")
PY

# The direct archive intentionally overlays distro Mesa. Do this only while
# constructing/updating Vessel's controlled rootfs, never per application.
tar -xzf "$ARCHIVE" -C "$ROOTFS"

KGSL="$ROOTFS/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
TURNIP="$ROOTFS/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
[[ -s "$KGSL" ]] || { echo "Mesa archive did not install kgsl_dri.so" >&2; exit 6; }
[[ -s "$TURNIP" ]] || { echo "Mesa archive did not install libvulkan_freedreno.so" >&2; exit 6; }

ICD=""
for candidate in   "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"   "$ROOTFS/usr/share/vulkan/icd.d/freedreno_icd.json"
do
  if [[ -s "$candidate" ]]; then ICD="${candidate#"$ROOTFS"}"; break; fi
done
[[ -n "$ICD" ]] || { echo "Mesa archive did not install a Freedreno Vulkan ICD" >&2; exit 6; }

# Refresh the target rootfs cache without executing any ARM64 binary.
if command -v ldconfig >/dev/null 2>&1; then
  ldconfig -r "$ROOTFS" || true
fi

MARKER_HOST="$ROOTFS$MARKER"
mkdir -p "$(dirname "$MARKER_HOST")"
cat > "$MARKER_HOST" <<EOF
version=$VERSION
sha256=$EXPECTED_SHA
source=$URL
vulkan_icd=$ICD
driver=freedreno
kernel_backend=kgsl
EOF
chmod 0644 "$MARKER_HOST"

echo "Vessel direct GPU rootfs ready:"
echo "  Mesa:    $VERSION"
echo "  OpenGL:  /usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
echo "  Vulkan:  /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
echo "  ICD:     $ICD"
