#!/usr/bin/env bash
set -euo pipefail

# Rebuild the exact UML/arm64 base used by Vessel with the local umshm/Venus
# bridge applied and SMP enabled. The output is an Android/bionic AArch64 UML
# kernel suitable for Termux/app-sandbox execution.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${VESSEL_KERNEL_WORK:-$ROOT/.kernel-build}"
SRC="$WORK/linux-um-arm64"
OUT="$WORK/out"
ART="$WORK/artifacts"
UPSTREAM_REPO="${VESSEL_UML_UPSTREAM:-https://github.com/zalexdev/linux-um-arm64.git}"
UPSTREAM_COMMIT="${VESSEL_UML_COMMIT:-8897487c52233cd00cf2850008ca068892f1ae91}"
JOBS="${JOBS:-$(nproc)}"
NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"

mkdir -p "$WORK" "$ART"

if [ ! -d "$SRC/.git" ]; then
  git clone "$UPSTREAM_REPO" "$SRC"
fi

git -C "$SRC" fetch --tags origin
git -C "$SRC" reset --hard "$UPSTREAM_COMMIT"
git -C "$SRC" clean -ffd

# Keep all of Vessel's current Venus shared-memory transport changes.
python3 "$ROOT/tools/venus_poc/apply_umshm.py" "$SRC"
python3 "$ROOT/tools/venus_poc/fix_umshm_nonblock.py" "$SRC"
python3 "$ROOT/tools/venus_poc/fix_umshm_ptrace_fds.py" "$SRC"

CONFIG="$WORK/vessel-smp.config"
cat > "$CONFIG" <<'EOF'
CONFIG_SMP=y
CONFIG_NR_CPUS=8
EOF

# The upstream arm64 UML port already contains the arm64 SMP subarch support;
# its bionic harness handles Android's executable/ABI constraints. We only add
# our config fragment and our umshm source transform.
export TREE="$SRC"
export O="$OUT"
export ART="$ART/upstream"
export EXTRA_CONFIG="$CONFIG"
export JOBS
if [ -n "$NDK" ]; then
  export NDK
fi

bash "$SRC/tools/um-arm64/harness/build-bionic.sh"

KERNEL="$ART/upstream/linux-bionic"
STUB="$ART/upstream/stub_exe_bionic"
[ -s "$KERNEL" ] || { echo "missing rebuilt UML kernel: $KERNEL" >&2; exit 1; }
[ -s "$STUB" ] || { echo "missing rebuilt UML stub: $STUB" >&2; exit 1; }

grep -qx 'CONFIG_SMP=y' "$OUT/.config" || { echo 'CONFIG_SMP did not stick' >&2; exit 1; }
grep -qx 'CONFIG_NR_CPUS=8' "$OUT/.config" || { echo 'CONFIG_NR_CPUS=8 did not stick' >&2; exit 1; }

install -m 0755 "$KERNEL" "$ART/linux-umshm"
install -m 0755 "$STUB" "$ART/stub_exe-umshm"
cp "$OUT/.config" "$ART/vessel-uml-smp.config"

{
  echo "upstream=$UPSTREAM_REPO"
  echo "commit=$UPSTREAM_COMMIT"
  echo "CONFIG_SMP=y"
  echo "CONFIG_NR_CPUS=8"
  echo "default_vcpus=6"
  sha256sum "$ART/linux-umshm" "$ART/stub_exe-umshm"
} | tee "$ART/SHA256SUMS.txt"

# Prove that this is an SMP-capable UML binary before publishing it.
if ! "$ART/linux-umshm" --help 2>&1 | grep -q 'ncpus='; then
  echo 'rebuilt kernel does not advertise ncpus=; refusing artifact' >&2
  exit 1
fi

echo
echo "Vessel SMP kernel ready:"
echo "  $ART/linux-umshm"
echo "  $ART/stub_exe-umshm"
echo "  CONFIG_SMP=y CONFIG_NR_CPUS=8; Vessel default ncpus=6"
