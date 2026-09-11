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
# apply_umshm.py historically used a raw triple-quoted string for the
# phys_mapping() match. On a clean checkout that leaves the \t sequences
# literal, so the transform aborts even though the upstream function is
# present with real tab indentation. Build a tiny compatibility copy for this
# clean-kernel CI path and make that one string a normal Python string.
SMP_UMSHM_PATCHER="$WORK/apply_umshm_smp.py"
python3 - "$ROOT/tools/venus_poc/apply_umshm.py" "$SMP_UMSHM_PATCHER" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()
needle = "old_phys_mapping = r'''"
if src.count(needle) != 1:
    raise SystemExit(f"expected exactly one raw phys_mapping matcher, found {src.count(needle)}")
Path(sys.argv[2]).write_text(src.replace(needle, "old_phys_mapping = '''", 1))
PY

python3 "$SMP_UMSHM_PATCHER" "$SRC"
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
UPSTREAM_ART="$ART/upstream"
export ART="$UPSTREAM_ART"
export EXTRA_CONFIG="$CONFIG"
export JOBS
if [ -n "$NDK" ]; then
  export NDK
fi

bash "$SRC/tools/um-arm64/harness/build-bionic.sh"

# build-bionic.sh writes directly into the ART directory supplied above.
# Keep that location separate from Vessel's final renamed artifact files.
KERNEL="$UPSTREAM_ART/linux-bionic"
STUB="$UPSTREAM_ART/stub_exe_bionic"
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

# The hosted Actions runner is x86_64 while linux-umshm is an Android/bionic
# AArch64 executable. Executing it here would fail with ENOEXEC even when the
# kernel is perfectly valid. Verify the compiled-in UML setup/help string in
# the binary instead; CONFIG_SMP/NR_CPUS were already verified above.
if ! strings "$ART/linux-umshm" | grep -Fq 'ncpus=<# of desired CPUs>'; then
  echo 'rebuilt kernel is missing the compiled-in ncpus= UML option' >&2
  exit 1
fi

echo
echo "Vessel SMP kernel ready:"
echo "  $ART/linux-umshm"
echo "  $ART/stub_exe-umshm"
echo "  CONFIG_SMP=y CONFIG_NR_CPUS=8; Vessel default ncpus=6"
