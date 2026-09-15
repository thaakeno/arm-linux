#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK_HOME is required" >&2; exit 2; }
WORK="${VESSEL_NATIVE_WORK:-$ROOT/.vessel-native-build}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
VHOST="$WORK/vhost-device"
INCLUDE_ROOT="$WORK/include"
INC="$INCLUDE_ROOT/virgl"
PINNED_VHOST="20fa14c4c56e40a12104794a934dd70dc7642ff2"
VIRGL_VERSION="1.3.0"
VIRGL_SHA="56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"

mkdir -p "$WORK" "$OUT" "$INC" "$WORK/pkgconfig"
[ -s "$OUT/libvessel_virglrenderer.so" ] || { echo "cached libvessel_virglrenderer.so is missing" >&2; exit 3; }
[ -s "$OUT/libvessel_epoxy.so" ] || { echo "cached libvessel_epoxy.so is missing" >&2; exit 3; }

if [ ! -d "$VHOST/.git" ]; then
  rm -rf "$VHOST"
  git clone --filter=blob:none https://github.com/rust-vmm/vhost-device.git "$VHOST"
fi
git -C "$VHOST" fetch --depth=1 origin "$PINNED_VHOST" || true
git -C "$VHOST" checkout --detach "$PINNED_VHOST"

if [ ! -s "$INC/virglrenderer.h" ] || [ ! -s "$INC/virgl-version.h" ]; then
  TAR="$WORK/virglrenderer-$VIRGL_VERSION.tar.gz"
  SRC="$WORK/virglrenderer-virglrenderer-$VIRGL_VERSION"
  if [ ! -s "$TAR" ]; then
    curl -fL --retry 4 --retry-delay 2 \
      -o "$TAR.tmp" \
      "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-$VIRGL_VERSION/virglrenderer-virglrenderer-$VIRGL_VERSION.tar.gz"
    echo "$VIRGL_SHA  $TAR.tmp" | sha256sum -c -
    mv "$TAR.tmp" "$TAR"
  else
    echo "$VIRGL_SHA  $TAR" | sha256sum -c -
  fi
  rm -rf "$SRC"
  tar -xzf "$TAR" -C "$WORK"
  install -m0644 "$SRC/src/virglrenderer.h" "$INC/virglrenderer.h"
  IFS=. read -r VMAJ VMIN VMIC <<< "$VIRGL_VERSION"
  sed -e "s/@VIRGL_MAJOR_VERSION@/$VMAJ/g" \
      -e "s/@VIRGL_MINOR_VERSION@/$VMIN/g" \
      -e "s/@VIRGL_MICRO_VERSION@/$VMIC/g" \
      "$SRC/src/virgl-version.h.meson" > "$INC/virgl-version.h"
fi

cat > "$WORK/pkgconfig/virglrenderer.pc" <<EOF
prefix=$WORK
libdir=$OUT
includedir=$INCLUDE_ROOT
Name: virglrenderer
Description: Vessel Android VirGL renderer
Version: $VIRGL_VERSION
Libs: -L\${libdir} -lvessel_virglrenderer
Cflags: -I\${includedir}
EOF

echo "[vessel-gpu-prep] lightweight GPU rebuild inputs ready"
