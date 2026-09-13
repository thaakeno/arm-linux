#!/usr/bin/env bash
set -euo pipefail

# Rebuild the exact UML/arm64 base used by Vessel with umshm/Venus, SMP and
# /dev/uinput enabled for direct Android -> Linux evdev input.
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
CONFIG="$WORK/vessel-smp.config"
cat >"$CONFIG" <<'EOF'
CONFIG_SMP=y
CONFIG_NR_CPUS=8
CONFIG_INPUT=y
CONFIG_INPUT_EVDEV=y
CONFIG_INPUT_UINPUT=y
EOF
export TREE="$SRC" O="$OUT" ART="$UPSTREAM_ART" EXTRA_CONFIG="$CONFIG" JOBS
[ -n "$NDK" ] && export NDK
bash "$SRC/tools/um-arm64/harness/build-bionic.sh"
KERNEL="$UPSTREAM_ART/linux-bionic"; STUB="$UPSTREAM_ART/stub_exe_bionic"
[ -s "$KERNEL" ] || { echo "missing rebuilt UML kernel: $KERNEL" >&2; exit 1; }
[ -s "$STUB" ] || { echo "missing rebuilt UML stub: $STUB" >&2; exit 1; }
for cfg in 'CONFIG_SMP=y' 'CONFIG_NR_CPUS=8' 'CONFIG_INPUT=y' 'CONFIG_INPUT_EVDEV=y' 'CONFIG_INPUT_UINPUT=y'; do grep -qx "$cfg" "$OUT/.config" || { echo "$cfg did not stick" >&2; exit 1; }; done
install -m0755 "$KERNEL" "$FINAL_ART/linux-umshm"; install -m0755 "$STUB" "$FINAL_ART/stub_exe-umshm"; cp "$OUT/.config" "$FINAL_ART/vessel-uml-smp.config"
{ echo "upstream=$UPSTREAM_REPO"; echo "commit=$UPSTREAM_COMMIT"; echo 'CONFIG_SMP=y'; echo 'CONFIG_NR_CPUS=8'; echo 'CONFIG_INPUT_UINPUT=y'; echo 'default_vcpus=6'; sha256sum "$FINAL_ART/linux-umshm" "$FINAL_ART/stub_exe-umshm"; } | tee "$FINAL_ART/SHA256SUMS.txt"
strings "$FINAL_ART/linux-umshm" | grep -Fq 'ncpus=<# of desired CPUs>' || { echo 'rebuilt kernel is missing ncpus option' >&2; exit 1; }
echo "Vessel SMP + uinput kernel ready: $FINAL_ART/linux-umshm"
