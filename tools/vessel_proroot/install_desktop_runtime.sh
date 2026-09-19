#!/usr/bin/env bash
set -euo pipefail

ROOTFS="${1:-}"
CACHE="${VESSEL_DESKTOP_CACHE_DIR:-}"
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$HERE/desktop_manifest.json"
ROOTFS_FILES="$HERE/rootfs"

if [[ -z "$ROOTFS" ]]; then
  echo "usage: $0 <debian-13-rootfs-directory>" >&2
  exit 2
fi
ROOTFS="$(realpath -m "$ROOTFS")"
[[ -d "$ROOTFS" && -f "$ROOTFS/etc/os-release" ]] || {
  echo "invalid rootfs: $ROOTFS" >&2
  exit 2
}

. "$ROOTFS/etc/os-release"
[[ "${ID:-}" == debian ]] || { echo "Vessel desktop package requires Debian" >&2; exit 3; }
case "${VERSION_ID:-}" in
  13|13.*) ;;
  *) echo "Vessel desktop package requires Debian 13, got ${VERSION_ID:-unknown}" >&2; exit 3 ;;
esac

readarray -t META < <(python3 - "$MANIFEST" <<'PY'
import json, sys
m=json.load(open(sys.argv[1], encoding="utf-8"))
print(m["release"])
for key in ("kwin","xwayland"):
    print(m[key]["archive"])
    print(m[key]["url"])
    print(m[key]["sha256"])
print(m["marker"])
PY
)
RELEASE="${META[0]}"
KWIN_NAME="${META[1]}"
KWIN_URL="${META[2]}"
KWIN_SHA="${META[3]}"
XWAYLAND_NAME="${META[4]}"
XWAYLAND_URL="${META[5]}"
XWAYLAND_SHA="${META[6]}"
MARKER="${META[7]}"

CACHE="${CACHE:-$HERE/.cache}"
mkdir -p "$CACHE"

download_verified() {
  local name="$1" url="$2" expected="$3"
  local path="$CACHE/$name"
  if [[ ! -s "$path" ]]; then
    curl --fail --location --retry 3 --connect-timeout 15 -o "$path.tmp" "$url"
    mv -f "$path.tmp" "$path"
  fi
  local actual
  actual="$(sha256sum "$path" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    echo "SHA-256 mismatch for $name" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    exit 4
  }
  printf '%s\n' "$path"
}

KWIN_ZIP="$(download_verified "$KWIN_NAME" "$KWIN_URL" "$KWIN_SHA")"
XWAYLAND_DEB="$(download_verified "$XWAYLAND_NAME" "$XWAYLAND_URL" "$XWAYLAND_SHA")"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$KWIN_ZIP" "$WORK/kwin" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1:3]
os.makedirs(dst, exist_ok=True)
with zipfile.ZipFile(src) as zf:
    for info in zf.infolist():
        p=info.filename.replace("\\","/")
        parts=[x for x in p.split("/") if x not in ("",".")]
        if p.startswith("/") or ".." in parts:
            raise SystemExit(f"unsafe zip path: {p}")
        zf.extract(info, dst)
PY

mapfile -t KWIN_DEBS < <(find "$WORK/kwin" -type f -name '*.deb' -print | sort)
[[ "${#KWIN_DEBS[@]}" -gt 0 ]] || { echo "KWin archive contains no .deb packages" >&2; exit 5; }

declare -a HELD_PACKAGES=()
for deb in "${KWIN_DEBS[@]}"; do
  package_name="$(dpkg-deb -f "$deb" Package)"
  [[ -n "$package_name" ]] && HELD_PACKAGES+=("$package_name")
  dpkg-deb -x "$deb" "$ROOTFS"
done
HELD_PACKAGES+=("$(dpkg-deb -f "$XWAYLAND_DEB" Package)")
dpkg-deb -x "$XWAYLAND_DEB" "$ROOTFS"

# Keep the pinned Anland XWayland binary intact, but launch it through a very
# small Vessel wrapper. The preload shim preserves Xorg's own SIGSYS handling
# while logging seccomp siginfo (si_syscall/si_arch/si_code), so Android-16
# failures become actionable instead of another opaque "Bad system call".
command -v cc >/dev/null 2>&1 || {
  echo "A C compiler is required to build the Vessel XWayland SIGSYS tracer" >&2
  exit 5
}
install -d -m0755 "$ROOTFS/usr/lib/vessel/xwayland"
[[ -x "$ROOTFS/usr/bin/Xwayland" ]] || {
  echo "pinned XWayland package did not install /usr/bin/Xwayland" >&2
  exit 5
}
mv "$ROOTFS/usr/bin/Xwayland" "$ROOTFS/usr/lib/vessel/xwayland/Xwayland.real"
cc -shared -fPIC -O2 -Wall -Wextra   "$ROOTFS_FILES/usr/local/lib/vessel/xwayland/sigsys_trace.c"   -ldl   -o "$ROOTFS/usr/lib/vessel/xwayland/libvessel-sigsys-trace.so"
chmod 0755 "$ROOTFS/usr/lib/vessel/xwayland/Xwayland.real"
chmod 0644 "$ROOTFS/usr/lib/vessel/xwayland/libvessel-sigsys-trace.so"
install -D -m0755 "$ROOTFS_FILES/usr/local/lib/vessel/xwayland/Xwayland"   "$ROOTFS/usr/bin/Xwayland"

install -D -m0755 "$ROOTFS_FILES/usr/local/libexec/vessel-start-plasma"   "$ROOTFS/usr/local/libexec/vessel-start-plasma"
install -D -m0755 "$ROOTFS_FILES/usr/local/libexec/vessel-compat-probe"   "$ROOTFS/usr/local/libexec/vessel-compat-probe"
install -D -m0755 "$ROOTFS_FILES/usr/local/lib/vessel/kwin-wrapper/kwin_wayland"   "$ROOTFS/usr/local/lib/vessel/kwin-wrapper/kwin_wayland"
install -D -m0755 "$ROOTFS_FILES/usr/local/lib/vessel/kwin-wrapper/kwin_wayland_wrapper"   "$ROOTFS/usr/local/lib/vessel/kwin-wrapper/kwin_wayland_wrapper"

{
  printf '# Vessel compositor/XWayland build; updated only by the pinned rootfs installer.\n'
  for package_name in "${HELD_PACKAGES[@]}"; do
    [[ -n "$package_name" ]] || continue
    printf 'Package: %s\nPin: version *\nPin-Priority: -1\n\n' "$package_name"
  done
} > "$ROOTFS/etc/apt/preferences.d/vessel-proroot-desktop"

MARKER_HOST="$ROOTFS$MARKER"
mkdir -p "$(dirname "$MARKER_HOST")"
cat > "$MARKER_HOST" <<EOF
release=$RELEASE
kwin_sha256=$KWIN_SHA
xwayland_sha256=$XWAYLAND_SHA
session=vessel-proroot-wayland-v1
EOF
chmod 0644 "$MARKER_HOST"

for required in   "$ROOTFS/usr/bin/kwin_wayland"   "$ROOTFS/usr/bin/startplasma-wayland"   "$ROOTFS/usr/bin/Xwayland"   "$ROOTFS/usr/lib/vessel/xwayland/Xwayland.real"   "$ROOTFS/usr/lib/vessel/xwayland/libvessel-sigsys-trace.so"   "$ROOTFS/usr/local/libexec/vessel-start-plasma"   "$ROOTFS/usr/local/libexec/vessel-compat-probe"
do
  [[ -e "$required" ]] || { echo "desktop install missing: ${required#$ROOTFS}" >&2; exit 6; }
done

echo "Vessel Phase 4 desktop rootfs ready: Anland-compatible KWin + XWayland + session layer"
