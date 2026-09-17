#!/usr/bin/env bash
set -euo pipefail

BASE_URL="https://github.com/zalexdev/linux-um-arm64/releases/download/prebuilt-20260816/debian-docker.ext4.gz"
BASE_SHA256="2807979f76021fadf1f76f0c827fbe1eab4df51e33bfeca0c840c5181c610be3"
OUT_DIR="${1:-build/workstation}"
IMAGE="$OUT_DIR/Vessel-Workstation-bookworm-arm64.ext4"
ARCHIVE="$IMAGE.zst"
MANIFEST="$OUT_DIR/Vessel-Workstation-bookworm-arm64.json"
MOUNT="$OUT_DIR/root"
RELEASE_TAG="vessel-workstation-edge"
ASSET_URL="https://github.com/${GITHUB_REPOSITORY:-thaakeno/arm-linux}/releases/download/$RELEASE_TAG/$(basename "$ARCHIVE")"

mkdir -p "$OUT_DIR" "$MOUNT"
BASE_GZ="$OUT_DIR/base.ext4.gz"
LOOP=""

cleanup() {
  set +e
  for p in "$MOUNT/run" "$MOUNT/sys" "$MOUNT/dev" "$MOUNT/proc"; do
    mountpoint -q "$p" && sudo umount -R "$p"
  done
  mountpoint -q "$MOUNT" && sudo umount "$MOUNT"
  if [ -n "$LOOP" ]; then sudo losetup -d "$LOOP" 2>/dev/null || true; fi
}
trap cleanup EXIT

curl -fL --retry 4 --retry-delay 2 -o "$BASE_GZ" "$BASE_URL"
echo "$BASE_SHA256  $BASE_GZ" | sha256sum -c -
gzip -dc "$BASE_GZ" > "$IMAGE"
rm -f "$BASE_GZ"

# Give dpkg plenty of room during CI. The final filesystem is shrunk again before
# compression; Android later grows the sparse backing file to Vessel's 10 GiB cap.
truncate -s 10G "$IMAGE"
sudo e2fsck -fy "$IMAGE"
sudo resize2fs "$IMAGE"

LOOP="$(sudo losetup --find --show "$IMAGE")"
sudo mount "$LOOP" "$MOUNT"
sudo mount -t proc proc "$MOUNT/proc"
sudo mount --rbind /dev "$MOUNT/dev"
sudo mount --make-rslave "$MOUNT/dev"
sudo mount --rbind /sys "$MOUNT/sys"
sudo mount --make-rslave "$MOUNT/sys"
sudo mount --rbind /run "$MOUNT/run"
sudo mount --make-rslave "$MOUNT/run"

# A normal resolv.conf is required inside the chroot. The original image may use
# a symlink intended for a running init system, so replace it only while building.
sudo rm -f "$MOUNT/etc/resolv.conf"
sudo cp /etc/resolv.conf "$MOUNT/etc/resolv.conf"

sudo chroot "$MOUNT" /bin/bash <<'CHROOT'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export SYSTEMD_OFFLINE=1
export UCF_FORCE_CONFFOLD=1
export NEEDRESTART_MODE=a

install -d -m 755 /usr/sbin /etc/initramfs-tools /var/cache/vessel
cat >/usr/sbin/policy-rc.d <<'EOF'
#!/bin/sh
# Vessel UML has its own userspace lifecycle. Never start package services while
# the workstation image is assembled in CI.
exit 101
EOF
chmod 0755 /usr/sbin/policy-rc.d
cat >/etc/initramfs-tools/update-initramfs.conf <<'EOF'
# Vessel boots the APK-provided UML kernel directly.
update_initramfs=no
backup_initramfs=no
EOF

apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 update
# Keep Debian Recommends enabled for the core desktop. This intentionally builds
# a normal Plasma workstation rather than a fragile hand-trimmed dependency set.
apt-get \
  -o Dpkg::Use-Pty=0 \
  -o APT::Color=0 \
  -o APT::Install-Recommends=true \
  -o Dpkg::Options::=--force-confdef \
  -o Dpkg::Options::=--force-confold \
  install -y \
  kde-plasma-desktop plasma-workspace plasma-desktop plasma-framework \
  kwin-x11 kwin-wayland plasma-workspace-wayland xwayland qtwayland5 wayland-utils systemsettings \
  python3-dbus python3-gi xserver-xorg-core xserver-xorg-input-libinput dbus dbus-x11 udev \
  libinput-tools mesa-utils x11-xserver-utils xinput xcvt e2fsprogs \
  breeze breeze-icon-theme hicolor-icon-theme desktop-file-utils xdg-user-dirs shared-mime-info menu \
  appstream apt-config-icons apt-config-icons-large apt-config-icons-hidpi packagekit packagekit-tools \
  policykit-1 plasma-discover librsvg2-bin python3-yaml \
  qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels \
  qml-module-org-kde-kquickcontrolsaddons qml-module-qtquick-controls qml-module-qtquick-controls2 \
  qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 \
  qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform \
  plasma-integration plasma-pa pulseaudio pulseaudio-utils alsa-utils kactivitymanagerd libkf5service-data \
  fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation plasma-workspace-wallpapers \
  firefox-esr konsole dolphin ark kcalc okular gwenview kate

dpkg --force-confdef --force-confold --configure -a

id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel
for g in video render input; do getent group "$g" >/dev/null || groupadd "$g"; done
usermod -a -G video,render,input vessel
install -d -m 755 /var/cache/vessel
install -d -m 755 -o vessel -g vessel /home/vessel/Desktop /home/vessel/.config /home/vessel/.mozilla/firefox/vessel.default

cat >/etc/profile.d/vessel-gpu.sh <<'EOF'
unset MOZ_X11_EGL
export MOZ_ENABLE_WAYLAND=1
export MOZ_WEBRENDER=1
export MOZ_DISABLE_CONTENT_SANDBOX=1
export XCURSOR_THEME=Breeze
export XCURSOR_SIZE=18
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM=wayland
EOF
chmod 0644 /etc/profile.d/vessel-gpu.sh

update-desktop-database /usr/share/applications 2>/dev/null || true
update-mime-database /usr/share/mime 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/breeze 2>/dev/null || true
appstreamcli refresh-cache --force >/tmp/vessel-appstream-refresh.log 2>&1 || true

for f in firefox-esr org.kde.konsole org.kde.dolphin systemsettings; do
  src="/usr/share/applications/$f.desktop"
  [ -f "$src" ] && install -m 755 -o vessel -g vessel "$src" /home/vessel/Desktop/ || true
done

# Validate the exact components Vessel's runtime requires before this image is
# ever published. If any of these are absent, CI fails rather than the phone.
for c in kwin_wayland startplasma-wayland Xwayland startplasma-x11 Xorg xrandr xinput systemsettings konsole firefox-esr; do
  command -v "$c" >/dev/null
 done
for p in kwin-wayland plasma-workspace-wayland xwayland qtwayland5 python3-dbus python3-gi \
  qml-module-org-kde-qqc2desktopstyle qml-module-org-kde-kirigami2 qml-module-org-kde-kitemmodels \
  qml-module-org-kde-kquickcontrolsaddons qml-module-qtquick-controls qml-module-qtquick-controls2 \
  qml-module-qtquick-layouts qml-module-qtquick-window2 qml-module-qtquick2 qml-module-qtquick-templates2 \
  qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa pulseaudio \
  pulseaudio-utils alsa-utils kactivitymanagerd libkf5service-data fonts-noto-color-emoji packagekit \
  packagekit-tools policykit-1 plasma-discover apt-config-icons apt-config-icons-large apt-config-icons-hidpi librsvg2-bin; do
  dpkg-query -W -f='${Status}' "$p" 2>/dev/null | grep -q 'install ok installed'
done
qml=/usr/lib/aarch64-linux-gnu/qt5/qml
test -f /etc/xdg/menus/kf5-applications.menu
test -d /usr/share/icons/breeze
test -e /usr/lib/aarch64-linux-gnu/dri/virtio_gpu_dri.so
test -f "$qml/QtQuick/Templates.2/qmldir"
test -f "$qml/QtGraphicalEffects/qmldir"
test -f "$qml/org/kde/kirigami.2/qmldir"
test -f "$qml/Qt/labs/platform/qmldir"
test -f "$qml/org/kde/plasma/private/volume/qmldir"

# These markers make first boot take the fast validation path. Runtime launch
# still checks KWin/Plasma itself and can rebuild KDE service caches if needed.
touch /var/cache/vessel/plasma-ready-v58 /var/cache/vessel/workstation-v58

apt-get clean
rm -rf /var/cache/apt/archives/*.deb /var/lib/apt/lists/* /tmp/* /var/tmp/*
rm -f /usr/sbin/policy-rc.d
sync
CHROOT

cleanup
trap - EXIT
LOOP=""

sudo e2fsck -fy "$IMAGE"
# Shrink the downloadable filesystem to its real payload. The app sparse-grows
# it to 10 GiB before UML boots, so unused capacity is never downloaded.
sudo resize2fs -M "$IMAGE"
BLOCK_COUNT="$(sudo dumpe2fs -h "$IMAGE" 2>/dev/null | awk -F: '/Block count:/{gsub(/[[:space:]]/,"",$2); print $2; exit}')"
BLOCK_SIZE="$(sudo dumpe2fs -h "$IMAGE" 2>/dev/null | awk -F: '/Block size:/{gsub(/[[:space:]]/,"",$2); print $2; exit}')"
test -n "$BLOCK_COUNT" && test -n "$BLOCK_SIZE"
truncate -s "$((BLOCK_COUNT * BLOCK_SIZE))" "$IMAGE"
sudo e2fsck -fy "$IMAGE"

IMAGE_SHA="$(sha256sum "$IMAGE" | awk '{print $1}')"
IMAGE_BYTES="$(stat -c '%s' "$IMAGE")"
zstd -T0 -8 --no-progress -f "$IMAGE" -o "$ARCHIVE"
ARCHIVE_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
ARCHIVE_BYTES="$(stat -c '%s' "$ARCHIVE")"

# A single GitHub release asset must remain below 2 GiB. Fail loudly instead of
# publishing something the Android downloader cannot reliably consume.
test "$ARCHIVE_BYTES" -lt 2000000000

python3 - "$MANIFEST" <<PY
import json, os, sys
out = sys.argv[1]
data = {
    "schema": 1,
    "revision": os.environ.get("GITHUB_SHA", "local"),
    "debian": "bookworm",
    "arch": "arm64",
    "compression": "zstd",
    "url": "$ASSET_URL",
    "compressedSha256": "$ARCHIVE_SHA",
    "compressedBytes": int("$ARCHIVE_BYTES"),
    "imageSha256": "$IMAGE_SHA",
    "imageBytes": int("$IMAGE_BYTES"),
    "logicalTargetBytes": 10737418240,
    "plasmaReadyMarker": "plasma-ready-v58",
    "workstationMarker": "workstation-v58",
    "recommends": True,
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, sort_keys=True)
    f.write("\n")
PY

cat "$MANIFEST"
echo "workstation archive: $ARCHIVE_BYTES bytes"
echo "workstation ext4:   $IMAGE_BYTES bytes"
