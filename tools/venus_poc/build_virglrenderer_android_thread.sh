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

if "-Drender-server-worker=thread" not in s:
    lines = s.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if "-Dvenus=true" not in line:
            continue

        indent = line[: len(line) - len(line.lstrip())]
        newline = "\r\n" if line.endswith("\r\n") else "\n"
        lines.insert(i + 1, f"{indent}-Drender-server-worker=thread \\\\{newline}")
        s = "".join(lines)
        break
    else:
        raise SystemExit("could not find -Dvenus=true in virglrenderer meson options")

p.write_text(s)

# Fail here with a useful message rather than much later in Meson.
patched = p.read_text()
if "-Dvenus=true" not in patched or "-Drender-server-worker=thread" not in patched:
    raise SystemExit("virglrenderer recipe patch verification failed")
PY

echo "[venus-build] patched recipe: render-server-worker=thread"
grep -n -A2 -- '-Dvenus=true' "$BUILD_SH" | head -3

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
