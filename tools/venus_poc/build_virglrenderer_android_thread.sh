#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="virglrenderer-android"
MARKER="$PREFIX/opt/virglrenderer-android/.venus-thread-worker"

APK_RELEASE="${TERMUX_APK_RELEASE:-UNKNOWN}"
if [[ "$APK_RELEASE" == "GOOGLE_PLAY_STORE" || "${TERMUX_VERSION:-}" == googleplay.* ]]; then
  TP_REPO="https://github.com/termux-play-store/termux-packages.git"
  TP_BRANCH="main"
  MAIN_APT_LINE="deb https://termux.net stable main"
  TERMUX_FLAVOR="google-play"
else
  TP_REPO="https://github.com/termux/termux-packages.git"
  TP_BRANCH="master"
  MAIN_APT_LINE="deb https://packages.termux.dev/apt/termux-main stable main"
  TERMUX_FLAVOR="upstream"
fi

TP_DIR="${TERMUX_PACKAGES_DIR:-$HOME/termux-packages-venus}"

# Pin graphics API headers so this build does not depend on whatever happens
# to be installed globally in Termux. The on-device ndk-sysroot package strips
# EGL/GLES/KHR/Vulkan headers even though virglrenderer-android's host build
# needs them while compiling bundled libepoxy and Venus.
EGL_REGISTRY_COMMIT="5961a7fe64cf8a126890ced6f13d69e0a1e1b83e"
GL_REGISTRY_COMMIT="1cdd228e34966dd6b95bd203e9f84faba0f371a1"
VULKAN_HEADERS_COMMIT="ee2ec5fd83dafce291024683b50dc89219333076"
KHRONOS_ROOT="$TP_DIR/.venus-khronos-headers"
KHRONOS_INCLUDE="$KHRONOS_ROOT/include"

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[venus-build] missing command: $1" >&2
    exit 1
  }
}

need git
need python
need apt

echo "[venus-build] Termux flavor: $TERMUX_FLAVOR"
echo "[venus-build] package tree: $TP_REPO ($TP_BRANCH)"

repair_package_index_if_needed() {
  echo "[venus-build] refreshing apt package index..."
  apt update || true

  if apt-cache show jq >/dev/null 2>&1; then
    return
  fi

  echo "[venus-build] core packages are missing from the current apt index."
  echo "[venus-build] repairing the main Termux repository..."

  local sources="$PREFIX/etc/apt/sources.list"
  mkdir -p "$(dirname "$sources")"
  if [ -f "$sources" ]; then
    cp "$sources" "$sources.venus-backup"
    echo "[venus-build] backed up sources.list to $sources.venus-backup"
  fi

  printf '%s\n' "$MAIN_APT_LINE" > "$sources"
  apt clean || true
  rm -rf "$PREFIX/var/lib/apt/lists"/*
  apt update

  if ! apt-cache show jq >/dev/null 2>&1; then
    echo "[venus-build] jq is still unavailable after repository repair." >&2
    echo "[venus-build] active sources:" >&2
    cat "$sources" >&2 || true
    for f in "$PREFIX/etc/apt/sources.list.d"/*.list; do
      [ -f "$f" ] || continue
      echo "--- $f" >&2
      cat "$f" >&2 || true
    done
    exit 1
  fi
}

repair_package_index_if_needed

# Only install what this build actually needs. The generic setup-termux.sh
# installs a huge toolchain (Rust, Go, protobuf, etc.) that is irrelevant here.
BUILD_PKGS=(
  clang
  file
  gnupg
  lzip
  patch
  python
  python-pip
  unzip
  jq
  git
  curl
  tar
  make
  ninja
  pkg-config
  cmake
  bison
  flex
)

echo "[venus-build] installing minimal build prerequisites..."
apt install -y "${BUILD_PKGS[@]}"

if [ ! -d "$TP_DIR/.git" ]; then
  echo "[venus-build] cloning matching termux-packages tree..."
  rm -rf "$TP_DIR"
  git clone --depth=1 --branch "$TP_BRANCH" "$TP_REPO" "$TP_DIR"
else
  current_origin="$(git -C "$TP_DIR" remote get-url origin 2>/dev/null || true)"
  if [ "$current_origin" != "$TP_REPO" ]; then
    echo "[venus-build] switching package tree origin to match this Termux build..."
    git -C "$TP_DIR" remote set-url origin "$TP_REPO"
  fi
  echo "[venus-build] refreshing termux-packages..."
  git -C "$TP_DIR" fetch --depth=1 origin "$TP_BRANCH"
  git -C "$TP_DIR" reset --hard "origin/$TP_BRANCH"
fi

fetch_header() {
  local url="$1"
  local out="$2"
  mkdir -p "$(dirname "$out")"
  if [ ! -s "$out" ]; then
    echo "[venus-build] fetching Khronos header: ${out#$KHRONOS_INCLUDE/}"
    curl -fL --retry 3 --retry-delay 1 -o "$out.tmp" "$url"
    mv "$out.tmp" "$out"
  fi
}

prepare_vulkan_headers() {
  if [ -s "$KHRONOS_INCLUDE/vulkan/vulkan.h" ] && \
     [ -s "$KHRONOS_INCLUDE/vulkan/vulkan_core.h" ] && \
     [ -s "$KHRONOS_INCLUDE/vulkan/vulkan_android.h" ]; then
    return
  fi

  echo "[venus-build] fetching pinned Vulkan-Headers..."
  local archive="$KHRONOS_ROOT/Vulkan-Headers-$VULKAN_HEADERS_COMMIT.tar.gz"
  local extract="$KHRONOS_ROOT/vulkan-extract"
  local src="$extract/Vulkan-Headers-$VULKAN_HEADERS_COMMIT/include"

  mkdir -p "$KHRONOS_ROOT"
  if [ ! -s "$archive" ]; then
    curl -fL --retry 3 --retry-delay 1 \
      -o "$archive.tmp" \
      "https://github.com/KhronosGroup/Vulkan-Headers/archive/$VULKAN_HEADERS_COMMIT.tar.gz"
    mv "$archive.tmp" "$archive"
  fi

  rm -rf "$extract"
  mkdir -p "$extract"
  tar -xzf "$archive" -C "$extract"

  [ -d "$src/vulkan" ] || {
    echo "[venus-build] Vulkan-Headers archive did not contain include/vulkan" >&2
    exit 1
  }

  rm -rf "$KHRONOS_INCLUDE/vulkan" "$KHRONOS_INCLUDE/vk_video"
  cp -R "$src/vulkan" "$KHRONOS_INCLUDE/"
  if [ -d "$src/vk_video" ]; then
    cp -R "$src/vk_video" "$KHRONOS_INCLUDE/"
  fi
  rm -rf "$extract"
}

echo "[venus-build] preparing private Khronos EGL/GLES/Vulkan headers..."
mkdir -p "$KHRONOS_INCLUDE"
EGL_RAW="https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/$EGL_REGISTRY_COMMIT/api"
GL_RAW="https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/$GL_REGISTRY_COMMIT/api"

fetch_header "$EGL_RAW/EGL/egl.h" "$KHRONOS_INCLUDE/EGL/egl.h"
fetch_header "$EGL_RAW/EGL/eglext.h" "$KHRONOS_INCLUDE/EGL/eglext.h"
fetch_header "$EGL_RAW/EGL/eglplatform.h" "$KHRONOS_INCLUDE/EGL/eglplatform.h"
fetch_header "$EGL_RAW/KHR/khrplatform.h" "$KHRONOS_INCLUDE/KHR/khrplatform.h"

for f in gl.h glext.h glplatform.h egl.h; do
  fetch_header "$GL_RAW/GLES/$f" "$KHRONOS_INCLUDE/GLES/$f"
done
for f in gl2.h gl2ext.h gl2platform.h; do
  fetch_header "$GL_RAW/GLES2/$f" "$KHRONOS_INCLUDE/GLES2/$f"
done
for f in gl3.h gl31.h gl32.h gl3platform.h; do
  fetch_header "$GL_RAW/GLES3/$f" "$KHRONOS_INCLUDE/GLES3/$f"
done
prepare_vulkan_headers

for required_header in \
  "$KHRONOS_INCLUDE/EGL/eglplatform.h" \
  "$KHRONOS_INCLUDE/KHR/khrplatform.h" \
  "$KHRONOS_INCLUDE/vulkan/vulkan.h" \
  "$KHRONOS_INCLUDE/vulkan/vulkan_android.h"; do
  [ -s "$required_header" ] || {
    echo "[venus-build] required private header missing: $required_header" >&2
    exit 1
  }
done

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

# The upstream recipe already enables Venus. The Google Play recipe currently
# does not, so enable it there before selecting the thread worker.
if "-Dvenus=true" not in s:
    lines = s.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if "-Dplatforms=egl" not in line:
            continue
        indent = line[: len(line) - len(line.lstrip())]
        newline = "\r\n" if line.endswith("\r\n") else "\n"
        lines.insert(i, f"{indent}-Dvenus=true \\{newline}")
        s = "".join(lines)
        break
    else:
        raise SystemExit("could not find virglrenderer Meson platform option")

if "-Drender-server-worker=thread" not in s:
    lines = s.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if "-Dvenus=true" not in line:
            continue
        indent = line[: len(line) - len(line.lstrip())]
        newline = "\r\n" if line.endswith("\r\n") else "\n"
        lines.insert(i + 1, f"{indent}-Drender-server-worker=thread \\{newline}")
        s = "".join(lines)
        break
    else:
        raise SystemExit("could not find -Dvenus=true in virglrenderer Meson options")

# On-device Termux's installed NDK sysroot intentionally omits EGL/GLES/KHR
# and Vulkan headers. The regular cross-builder has them in its full NDK.
# Inject our private, pinned graphics headers only into this host build.
if "VENUS_KHRONOS_HEADERS" not in s:
    needle = 'CPPFLAGS=""'
    replacement = 'CPPFLAGS="-I${VENUS_KHRONOS_HEADERS:?}"'
    if needle not in s:
        raise SystemExit("could not find virglrenderer host-build CPPFLAGS assignment")
    s = s.replace(needle, replacement, 1)

p.write_text(s)

patched = p.read_text()
for required in (
    "-Dvenus=true",
    "-Drender-server-worker=thread",
    "-Dplatforms=egl",
    "VENUS_KHRONOS_HEADERS",
):
    if required not in patched:
        raise SystemExit(f"virglrenderer recipe patch verification failed: {required}")
PY

echo "[venus-build] patched recipe: Venus + thread worker + private graphics headers"
grep -n -A2 -- '-Dvenus=true' "$BUILD_SH" | head -3
grep -n -- 'VENUS_KHRONOS_HEADERS' "$BUILD_SH" | head -1

echo "[venus-build] building $PKG..."
cd "$TP_DIR"
VENUS_KHRONOS_HEADERS="$KHRONOS_INCLUDE" \
  TERMUX_ON_DEVICE_BUILD=true \
  ./build-package.sh -f -I "$PKG"

DEB="$(find "$TP_DIR/output" "$TP_DIR/debs" -maxdepth 1 -type f -name 'virglrenderer-android_*_aarch64.deb' 2>/dev/null | sort | tail -1 || true)"
[ -n "$DEB" ] || {
  echo "[venus-build] build finished but no aarch64 .deb was found" >&2
  exit 1
}

echo "[venus-build] installing: $DEB"
apt install -y "$DEB"

mkdir -p "$(dirname "$MARKER")"
touch "$MARKER"

echo "[venus-build] installed thread-worker virglrenderer"
echo "[venus-build] marker: $MARKER"
echo "[venus-build] next: cd ~/venus-poc && git pull && bash tools/venus_poc/run_venus_uml.sh"
