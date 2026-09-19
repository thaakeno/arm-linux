#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-build/proroot-rootfs}"
ROOTFS="$OUT_DIR/rootfs"
ARCHIVE="$OUT_DIR/Vessel-Proroot-trixie-arm64.tar.zst"
MANIFEST="$OUT_DIR/Vessel-Proroot-trixie-arm64.json"
DESKTOP_RELEASE="$(python3 - <<'PY'
import json
print(json.load(open("tools/vessel_proroot/desktop_manifest.json", encoding="utf-8"))["release"])
PY
)"

rm -rf "$ROOTFS"
mkdir -p "$OUT_DIR"

sudo debootstrap   --arch=arm64   --variant=minbase   trixie   "$ROOTFS"   https://deb.debian.org/debian

sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
sudo install -d -m0755 "$ROOTFS/usr/sbin"
sudo tee "$ROOTFS/usr/sbin/policy-rc.d" >/dev/null <<'EOF'
#!/bin/sh
exit 101
EOF
sudo chmod 0755 "$ROOTFS/usr/sbin/policy-rc.d"

sudo chroot "$ROOTFS" /bin/bash <<'CHROOT'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
export UCF_FORCE_CONFFOLD=1
export NEEDRESTART_MODE=a

# /tmp is a normal Linux runtime invariant. APT, Qt and many desktop tools use
# mkstemp(3) there and must never depend on a later Android-side bind existing.
install -d -m1777 /tmp /var/tmp
install -d -m0755 /var/lib/apt/lists/partial /var/cache/apt/archives/partial

apt-get update
apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y   bash ca-certificates curl git locales sudo   dbus dbus-x11 python3 python3-dbus python3-gi   kde-plasma-desktop plasma-workspace plasma-desktop plasma-desktoptheme qml6-module-org-kde-ksvg libkf6svg6 libkirigamiplatform6 kwin-wayland   xwayland qt6-wayland wayland-utils systemsettings   libinput-tools mesa-utils   breeze breeze-icon-theme hicolor-icon-theme   desktop-file-utils xdg-user-dirs shared-mime-info menu   appstream packagekit packagekit-tools polkitd pkexec plasma-discover   xdg-desktop-portal xdg-desktop-portal-kde   pulseaudio pulseaudio-utils alsa-utils   fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation   firefox-esr konsole dolphin ark kcalc okular gwenview kate

dpkg --configure -a
id -u vessel >/dev/null 2>&1 || useradd -m -s /bin/bash vessel
install -d -m0700 -o vessel -g vessel /home/vessel
install -d -m0755 /usr/lib/vessel /var/cache/vessel
install -d -m0755 /etc/polkit-1/rules.d
cat >/etc/polkit-1/rules.d/49-vessel-packagekit.rules <<'POLKIT'
polkit.addRule(function(action, subject) {
    if (subject.user == "vessel" &&
        action.id.indexOf("org.freedesktop.packagekit.") == 0) {
        return polkit.Result.YES;
    }
});
POLKIT
chmod 0644 /etc/polkit-1/rules.d/49-vessel-packagekit.rules

# Runtime owns the user/session lifecycle; systemd is not used as PID 1.
install -d -m0755 /etc/xdg
cat >/etc/xdg/startkderc <<'STARTKDE'
[General]
systemdBoot=false
STARTKDE

# Android's app sandbox does not provide a user systemd manager or /dev/fuse.
# Do not leave D-Bus activation entries that can only spawn doomed services.
for service in \
  /usr/share/dbus-1/services/org.freedesktop.systemd1.service \
  /usr/share/dbus-1/services/org.freedesktop.portal.Documents.service
do
  if [ -f "$service" ]; then mv "$service" "$service.vessel-disabled"; fi
done

install -d -m0700 -o vessel -g vessel /home/vessel/.config/xdg-desktop-portal
cat >/home/vessel/.config/xdg-desktop-portal/portals.conf <<'PORTALS'
[preferred]
default=kde
org.freedesktop.impl.portal.ScreenCast=none
org.freedesktop.impl.portal.RemoteDesktop=none
org.freedesktop.impl.portal.Lockdown=none
PORTALS
chown vessel:vessel /home/vessel/.config/xdg-desktop-portal/portals.conf
chmod 0600 /home/vessel/.config/xdg-desktop-portal/portals.conf

test -s /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/plasma/core/qmldir
test -s /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/qmldir
test -s /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/libcorebindingsplugin.so
! ldd -r /usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/libcorebindingsplugin.so 2>&1 | grep -Eqi 'not found|undefined symbol'
touch /var/cache/vessel/plasma-qml-proroot-production-v15

apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /tmp/* /var/tmp/*
install -d -m1777 /tmp /var/tmp
rm -f /usr/sbin/policy-rc.d
CHROOT

# Overlay the exact direct-GPU and compositor builds pinned by Vessel.
sudo -E tools/vessel_proroot/install_direct_gpu_mesa.sh "$ROOTFS"
sudo -E tools/vessel_proroot/install_desktop_runtime.sh "$ROOTFS"

sudo test -s "$ROOTFS/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
sudo test -s "$ROOTFS/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
sudo test -s "$ROOTFS/usr/local/libexec/vessel-start-plasma"
sudo test -s "$ROOTFS/usr/local/libexec/vessel-compat-probe"
sudo test -s "$ROOTFS/usr/lib/vessel/direct-gpu/mesa.env"
sudo test -s "$ROOTFS/usr/lib/vessel/desktop/session.env"
sudo touch "$ROOTFS/var/cache/vessel/proroot-production-v1"

sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo ln -s /run/resolv.conf "$ROOTFS/etc/resolv.conf" || true

# Keep /dev, /proc, /sys and /run as ordinary directories; Android binds them.
sudo rm -rf "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/run"
sudo install -d -m0755 "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/run"

# Tar paths are relative and deterministic enough for integrity verification.
# Android SELinux denies hard-link creation to ordinary app domains. Store
# every multiply-linked inode as an ordinary file in the archive so extraction
# never needs link(2). Symlinks remain symlinks.
sudo tar   --hard-dereference   --numeric-owner   --xattrs   --acls   --sort=name   --mtime='UTC 2026-01-01'   -C "$ROOTFS"   -cf - .   | zstd -T0 -10 --no-progress -f -o "$ARCHIVE"

ARCHIVE_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
ARCHIVE_BYTES="$(stat -c '%s' "$ARCHIVE")"
EXTRACTED_BYTES="$(sudo du -sb "$ROOTFS" | awk '{print $1}')"
ENTRY_COUNT="$(sudo tar -C "$ROOTFS" -cf - . | tar -tf - | wc -l)"

python3 - "$MANIFEST" "$DESKTOP_RELEASE" <<PY
import json, os, sys
out=sys.argv[1]
desktop_release=sys.argv[2]
data={
  "schema": 1,
  "revision": os.environ.get("GITHUB_SHA","local"),
  "runtime": "proroot",
  "debian": "trixie",
  "arch": "arm64",
  "compression": "zstd",
  "hardLinksFlattened": True,
  "archiveSha256": "$ARCHIVE_SHA",
  "archiveBytes": int("$ARCHIVE_BYTES"),
  "extractedBytes": int("$EXTRACTED_BYTES"),
  "entryCount": int("$ENTRY_COUNT"),
  "mesaVersion": "26.3.0-devel-20260824",
  "desktopRelease": desktop_release,
  "requiredPaths": [
    "bin/sh",
    "usr/bin/kwin_wayland",
    "usr/bin/startplasma-wayland",
    "usr/local/libexec/vessel-start-plasma",
    "usr/local/libexec/vessel-compat-probe",
    "usr/lib/vessel/direct-gpu/mesa.env",
    "usr/lib/vessel/desktop/session.env",
    "usr/lib/vessel/desktop/direct-kwin-build.txt",
    "usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/qmldir",
    "usr/lib/aarch64-linux-gnu/qt6/qml/org/kde/ksvg/libcorebindingsplugin.so",
    "var/cache/vessel/proroot-production-v1"
  ]
}
with open(out,"w",encoding="utf-8") as f:
  json.dump(data,f,indent=2,sort_keys=True)
  f.write("\n")
PY

echo "proroot rootfs archive: $ARCHIVE_BYTES bytes"
cat "$MANIFEST"
