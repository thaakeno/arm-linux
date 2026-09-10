#!/bin/bash
set -euo pipefail

BASE="${VENUS_TEST_DIR:-/root/venus-tests}"
SRC_BASE="${VENUS_SRC_BASE:-/root}"
mkdir -p "$BASE"

export VTEST_SOCKET_NAME="${VTEST_SOCKET_NAME:-/tmp/.venus_test}"
export VN_DEBUG="${VN_DEBUG:-vtest}"
export VK_DRIVER_FILES="${VK_DRIVER_FILES:-/usr/share/vulkan/icd.d/virtio_icd.json}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

echo "=== 1. Venus enumeration ==="
vulkaninfo --summary

echo
echo "=== 2. Headless vkcube ==="
if command -v vkcube >/dev/null 2>&1 && vkcube --help 2>&1 | grep -qi headless; then
    echo "[tests] running headless vkcube for 10 seconds"
    set +e
    timeout 10 vkcube --wsi headless
    rc=$?
    set -e
    if [ "$rc" -ne 0 ] && [ "$rc" -ne 124 ]; then
        echo "[tests] vkcube returned $rc"
        exit "$rc"
    fi
    echo "[tests] headless vkcube survived the test window"
else
    echo "[tests] this vkcube build has no headless WSI; skipping until display testing"
fi

echo
echo "=== 3. Vulkan compute smoke/stress ==="
for cmd in gcc glslangValidator; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "[tests] missing $cmd. Install: apt update && apt install -y gcc libvulkan-dev glslang-tools" >&2
        exit 1
    }
done

COMP_SRC="$SRC_BASE/venus_compute_test.c"
SHADER_SRC="$SRC_BASE/venus_compute.comp"
[ -f "$COMP_SRC" ] || { echo "[tests] missing $COMP_SRC" >&2; exit 1; }
[ -f "$SHADER_SRC" ] || { echo "[tests] missing $SHADER_SRC" >&2; exit 1; }

glslangValidator -V "$SHADER_SRC" -o "$BASE/venus_compute.spv"
gcc -O2 "$COMP_SRC" -lvulkan -o "$BASE/venus_compute_test"

echo "[tests] quick compute: 100 dispatches"
"$BASE/venus_compute_test" "$BASE/venus_compute.spv" 100

echo
echo "[tests] sustained compute: 5000 dispatches"
"$BASE/venus_compute_test" "$BASE/venus_compute.spv" 5000

echo
echo "=== PASS: enumeration + available rendering test + compute completed ==="
