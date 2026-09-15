#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# One-shot Termux entry point for Vessel's rootless UML virtio-gpu path.
# Expected usage after pulling the branch:
#   bash tools/venus_poc/install_and_run_vessel_virtio_gpu.sh
#
# It installs the latest CI-built UML kernel from a stable rolling release,
# preserves the user's persistent Debian disk, builds the host GPU daemon only
# on first use, and then boots the new runtime.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UML_DIR="${UML_DIR:-$HOME/venus-wsi-local}"
RELEASE_TAG="${VESSEL_GPU_KERNEL_TAG:-vessel-virtio-gpu-kernel-latest}"
REPO_SLUG="${VESSEL_REPO_SLUG:-thaakeno/arm-linux}"
ZIP_URL="https://github.com/$REPO_SLUG/releases/download/$RELEASE_TAG/vessel-uml-smp-arm64.zip"
TMP="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/vessel-vugpu-install.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fail() {
  echo "[vessel-vugpu-install] ERROR: $*" >&2
  exit 1
}

if [ ! -d "$UML_DIR" ]; then
  echo "[vessel-vugpu-install] existing Vessel UML directory missing: $UML_DIR" >&2
  echo "[vessel-vugpu-install] this installer upgrades the existing real-Linux runtime; it does not create a new PRoot/chroot." >&2
  exit 1
fi

for f in "$UML_DIR/umnet" "$UML_DIR/passt" "$UML_DIR/debian-docker.ext4"; do
  [ -e "$f" ] || fail "missing existing runtime file: $f"
done

command -v curl >/dev/null 2>&1 || pkg install -y curl
command -v unzip >/dev/null 2>&1 || pkg install -y unzip

ARCHIVE="$TMP/vessel-uml-smp-arm64.zip"
echo "[vessel-vugpu-install] downloading CI kernel: $RELEASE_TAG"
curl -fL --retry 4 --retry-delay 2 -o "$ARCHIVE" "$ZIP_URL"

echo "[vessel-vugpu-install] extracting + verifying kernel artifact"
unzip -q "$ARCHIVE" -d "$TMP/kernel"

KERNEL="$(find "$TMP/kernel" -type f -name linux-umshm -print -quit)"
STUB="$(find "$TMP/kernel" -type f -name stub_exe-umshm -print -quit)"
CONFIG="$(find "$TMP/kernel" -type f -name vessel-uml-smp.config -print -quit)"
CHECKSUMS="$(find "$TMP/kernel" -type f -name SHA256SUMS.txt -print -quit)"
for f in "$KERNEL" "$STUB" "$CONFIG" "$CHECKSUMS"; do
  [ -n "$f" ] && [ -s "$f" ] || {
    echo "[vessel-vugpu-install] rolling kernel release is incomplete" >&2
    find "$TMP/kernel" -maxdepth 3 -type f -print >&2 || true
    exit 1
  }
done

grep -qx 'CONFIG_VIRTIO_UML=y' "$CONFIG" || fail 'kernel config lacks CONFIG_VIRTIO_UML=y'
grep -qx 'CONFIG_DRM_VIRTIO_GPU=y' "$CONFIG" || fail 'kernel config lacks CONFIG_DRM_VIRTIO_GPU=y'
grep -qx 'CONFIG_DRM_VIRTIO_GPU_KMS=y' "$CONFIG" || fail 'kernel config lacks CONFIG_DRM_VIRTIO_GPU_KMS=y'
grep -Fq 'VHOST_USER_GPU_SET_SOCKET=33' "$CHECKSUMS" || fail 'artifact metadata lacks VHOST_USER_GPU_SET_SOCKET=33'

# Do not use `strings | grep -q` here with `set -o pipefail`: grep exits as soon
# as it finds the marker, strings receives SIGPIPE, and the otherwise-successful
# verification becomes exit 141.  Search the binary directly instead.
grep -aFq 'Vessel vhost-user-gpu display relay attached' "$KERNEL" || \
  fail 'kernel binary lacks Vessel vhost-user-gpu display handoff marker'

# Verify the artifact hashes before replacing the local executable.  The file
# names in CI are absolute/relative to its artifact folder, so compare hashes by
# basename instead of depending on the original path prefix.
want_kernel="$(awk '$2 ~ /linux-umshm$/ {print $1; exit}' "$CHECKSUMS")"
want_stub="$(awk '$2 ~ /stub_exe-umshm$/ {print $1; exit}' "$CHECKSUMS")"
[ -n "$want_kernel" ] || fail 'missing linux-umshm checksum'
[ -n "$want_stub" ] || fail 'missing stub_exe-umshm checksum'
[ "$(sha256sum "$KERNEL" | awk '{print $1}')" = "$want_kernel" ] || fail 'linux-umshm SHA256 mismatch'
[ "$(sha256sum "$STUB" | awk '{print $1}')" = "$want_stub" ] || fail 'stub_exe-umshm SHA256 mismatch'

echo "[vessel-vugpu-install] artifact verified"

# Keep one rollback copy.  Never touch the persistent ext4 disk.
if [ -e "$UML_DIR/linux-umshm" ] && [ ! -e "$UML_DIR/linux-umshm.pre-vugpu" ]; then
  cp -p "$UML_DIR/linux-umshm" "$UML_DIR/linux-umshm.pre-vugpu"
fi
if [ -e "$UML_DIR/stub_exe-umshm" ] && [ ! -e "$UML_DIR/stub_exe-umshm.pre-vugpu" ]; then
  cp -p "$UML_DIR/stub_exe-umshm" "$UML_DIR/stub_exe-umshm.pre-vugpu"
fi

install -m0755 "$KERNEL" "$UML_DIR/linux-umshm.new"
install -m0755 "$STUB" "$UML_DIR/stub_exe-umshm.new"
mv -f "$UML_DIR/linux-umshm.new" "$UML_DIR/linux-umshm"
mv -f "$UML_DIR/stub_exe-umshm.new" "$UML_DIR/stub_exe-umshm"
cp "$CONFIG" "$UML_DIR/vessel-uml-smp.config"
cp "$CHECKSUMS" "$UML_DIR/vessel-uml-smp.SHA256SUMS.txt"

echo "[vessel-vugpu-install] new UML kernel installed; persistent Debian disk unchanged"
echo "[vessel-vugpu-install] launching 6-vCPU / 8192-MiB rootless virtio-gpu runtime"

export POC_DIR="$ROOT"
export UML_DIR
export VESSEL_VCPUS="${VESSEL_VCPUS:-6}"
export VESSEL_MEM_MB="${VESSEL_MEM_MB:-8192}"
exec bash "$ROOT/tools/venus_poc/run_vessel_virtio_gpu.sh"
