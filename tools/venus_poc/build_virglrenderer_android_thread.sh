#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

TP_DIR="${TERMUX_PACKAGES_DIR:-$HOME/termux-packages-venus}"
PKG="virglrenderer-android"
MARKER="$PREFIX/opt/virglrenderer-android/.venus-thread-worker"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[venus-build] missing command: $1" >&2
    exit 1
  }
}

need git
need python

if [ ! -d "$TP_DIR/.git" ]; then
  echo "[venus-build] cloning termux-packages..."
  git clone --depth=1 https://github.com/termux/termux-packages.git "$TP_DIR"
else
  echo "[venus-build] refreshing termux-packages..."
  git -C "$TP_DIR" fetch --depth=1 origin master
  git -C "$TP_DIR" reset --hard origin/master
fi

BUILD_SH="$TP_DIR/packages/$PKG/build.sh"
[ -f "$BUILD_SH" ] || {
  echo "[venus-build] package recipe not found: $BUILD_SH" >&2
  exit 1
}

python - "$BUILD_SH" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = "\t\t-Dvenus=true \\\\\n\t\t-Dplatforms=egl"
replacement = "\t\t-Dvenus=true \\\\\n\t\t-Drender-server-worker=thread \\\\\n\t\t-Dplatforms=egl"
if "-Drender-server-worker=thread" not in s:
    if needle not in s:
        raise SystemExit("could not find virglrenderer meson option block")
    s = s.replace(needle, replacement, 1)
p.write_text(s)
PY

echo "[venus-build] patched recipe: render-server-worker=thread"

echo "[venus-build] preparing on-device Termux build environment..."
cd "$TP_DIR"
./scripts/setup-termux.sh

echo "[venus-build] building $PKG..."
TERMUX_ON_DEVICE_BUILD=true ./build-package.sh -f -I "$PKG"

DEB="$(find "$TP_DIR/output" "$TP_DIR/debs" -maxdepth 1 -type f -name 'virglrenderer-android_*_aarch64.deb' 2>/dev/null | sort | tail -1 || true)"
[ -n "$DEB" ] || {
  echo "[venus-build] build finished but no aarch64 .deb was found" >&2
  exit 1
}

echo "[venus-build] installing: $DEB"
apt install -y "$DEB"

touch "$MARKER"

echo "[venus-build] installed thread-worker virglrenderer"
echo "[venus-build] marker: $MARKER"
echo "[venus-build] next: cd ~/venus-poc && git pull && bash tools/venus_poc/run_venus_uml.sh"
