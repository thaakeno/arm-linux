#!/usr/bin/env bash
set -euo pipefail

# Rebuild the exact UML/arm64 base used by Vessel with umshm/Venus, SMP,
# /dev/uinput, and the standard virtio-gpu DRM frontend carried over
# VIRTIO_UML/vhost-user.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${VESSEL_KERNEL_WORK:-$ROOT/.kernel-build}"
SRC="$WORK/linux-um-arm64"; OUT="$WORK/out"; FINAL_ART="$WORK/artifacts"; UPSTREAM_ART="$FINAL_ART/upstream"
UPSTREAM_REPO="${VESSEL_UML_UPSTREAM:-https://github.com/zalexdev/linux-um-arm64.git}"
UPSTREAM_COMMIT="${VESSEL_UML_COMMIT:-8897487c52233cd00cf2850008ca068892f1ae91}"
JOBS="${JOBS:-$(nproc)}"; NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
mkdir -p "$WORK" "$FINAL_ART" "$UPSTREAM_ART"
[ -d "$SRC/.git" ] || git clone "$UPSTREAM_REPO" "$SRC"
git -C "$SRC" fetch --tags origin; git -C "$SRC" reset --hard "$UPSTREAM_COMMIT"; git -C "$SRC" clean -ffd
SMP_UMSHM_PATCHER="$WORK/apply_umshm_smp.py"
python3 - "$ROOT/tools/venus_poc/apply_umshm.py" "$SMP_UMSHM_PATCHER" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text(); needle="old_phys_mapping = r'''"
if src.count(needle)!=1: raise SystemExit(f"expected exactly one raw phys_mapping matcher, found {src.count(needle)}")
Path(sys.argv[2]).write_text(src.replace(needle,"old_phys_mapping = '''",1))
PY
python3 "$SMP_UMSHM_PATCHER" "$SRC"
python3 "$ROOT/tools/venus_poc/fix_umshm_nonblock.py" "$SRC"
python3 "$ROOT/tools/venus_poc/fix_umshm_ptrace_fds.py" "$SRC"

# UML's pristine arm64 defconfig starts with UML_DMA_EMULATION=n.  That makes
# UML select NO_DMA during the harness' initial `make defconfig`.  Enabling DMA
# only later through EXTRA_CONFIG is too late on this tree: the stale NO_DMA=y
# state survives olddefconfig, HAS_DMA stays unavailable while DRM is resolved,
# and Kconfig silently drops CONFIG_DRM=y.  Seed the two UML emulation knobs in
# the source defconfig *before* the harness creates .config so DMA/IOMEM are
# available from the first Kconfig resolution.  This is a config-only change to
# the throw-away upstream checkout, not a kernel source patch.
ARM64_DEFCONFIG="$SRC/arch/um/configs/arm64_defconfig"
"$SRC/scripts/config" --file "$ARM64_DEFCONFIG" -e UML_DMA_EMULATION -e UML_IOMEM_EMULATION
grep -qx 'CONFIG_UML_DMA_EMULATION=y' "$ARM64_DEFCONFIG"
grep -qx 'CONFIG_UML_IOMEM_EMULATION=y' "$ARM64_DEFCONFIG"

CONFIG="$WORK/vessel-smp.config"
cat >"$CONFIG" <<'EOF'
CONFIG_SMP=y
CONFIG_NR_CPUS=8
CONFIG_INPUT=y
CONFIG_INPUT_EVDEV=y
CONFIG_INPUT_MISC=y
CONFIG_INPUT_UINPUT=y

# Standard Linux virtio-gpu frontend over UML's existing vhost-user transport.
# These are also repeated here so the final generated config is self-describing;
# the important part is that DMA/IOMEM were already enabled for the first
# defconfig pass above.
CONFIG_UML_DMA_EMULATION=y
CONFIG_UML_IOMEM_EMULATION=y
CONFIG_VIRTIO_MENU=y
CONFIG_VIRTIO=y
CONFIG_VIRTIO_UML=y
CONFIG_DRM=y
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_DRM_VIRTIO_GPU_KMS=y
EOF
export TREE="$SRC" O="$OUT" ART="$UPSTREAM_ART" EXTRA_CONFIG="$CONFIG" JOBS
[ -n "$NDK" ] && export NDK
bash "$SRC/tools/um-arm64/harness/build-bionic.sh"
KERNEL="$UPSTREAM_ART/linux-bionic"; STUB="$UPSTREAM_ART/stub_exe_bionic"
[ -s "$KERNEL" ] || { echo "missing rebuilt UML kernel: $KERNEL" >&2; exit 1; }
[ -s "$STUB" ] || { echo "missing rebuilt UML stub: $STUB" >&2; exit 1; }

echo 'Resolved UML/virtio/DRM Kconfig:'
grep -E '^(CONFIG_(UML_DMA_EMULATION|UML_IOMEM_EMULATION|NO_DMA|HAS_DMA|NO_IOMEM|HAS_IOMEM|VIRTIO|VIRTIO_UML|DRM|DRM_VIRTIO_GPU|DRM_VIRTIO_GPU_KMS)=|# CONFIG_(UML_DMA_EMULATION|UML_IOMEM_EMULATION|NO_DMA|HAS_DMA|NO_IOMEM|HAS_IOMEM|VIRTIO|VIRTIO_UML|DRM|DRM_VIRTIO_GPU|DRM_VIRTIO_GPU_KMS) is not set)' "$OUT/.config" || true

for cfg in \
  'CONFIG_SMP=y' \
  'CONFIG_NR_CPUS=8' \
  'CONFIG_INPUT=y' \
  'CONFIG_INPUT_EVDEV=y' \
  'CONFIG_INPUT_MISC=y' \
  'CONFIG_INPUT_UINPUT=y' \
  'CONFIG_UML_DMA_EMULATION=y' \
  'CONFIG_UML_IOMEM_EMULATION=y' \
  'CONFIG_HAS_DMA=y' \
  'CONFIG_VIRTIO=y' \
  'CONFIG_VIRTIO_UML=y' \
  'CONFIG_DRM=y' \
  'CONFIG_DRM_VIRTIO_GPU=y' \
  'CONFIG_DRM_VIRTIO_GPU_KMS=y'; do
  grep -qx "$cfg" "$OUT/.config" || { echo "$cfg did not stick" >&2; exit 1; }
done
install -m0755 "$KERNEL" "$FINAL_ART/linux-umshm"; install -m0755 "$STUB" "$FINAL_ART/stub_exe-umshm"; cp "$OUT/.config" "$FINAL_ART/vessel-uml-smp.config"
{
  echo "upstream=$UPSTREAM_REPO"
  echo "commit=$UPSTREAM_COMMIT"
  echo 'CONFIG_SMP=y'
  echo 'CONFIG_NR_CPUS=8'
  echo 'CONFIG_INPUT_MISC=y'
  echo 'CONFIG_INPUT_UINPUT=y'
  echo 'CONFIG_UML_DMA_EMULATION=y'
  echo 'CONFIG_UML_IOMEM_EMULATION=y'
  echo 'CONFIG_HAS_DMA=y'
  echo 'CONFIG_VIRTIO_UML=y'
  echo 'CONFIG_DRM=y'
  echo 'CONFIG_DRM_VIRTIO_GPU=y'
  echo 'CONFIG_DRM_VIRTIO_GPU_KMS=y'
  echo 'default_vcpus=6'
  sha256sum "$FINAL_ART/linux-umshm" "$FINAL_ART/stub_exe-umshm"
} | tee "$FINAL_ART/SHA256SUMS.txt"
strings "$FINAL_ART/linux-umshm" | grep -Fq 'ncpus=<# of desired CPUs>' || { echo 'rebuilt kernel is missing ncpus option' >&2; exit 1; }
echo "Vessel SMP + uinput + VIRTIO_UML virtio-gpu kernel ready: $FINAL_ART/linux-umshm"
