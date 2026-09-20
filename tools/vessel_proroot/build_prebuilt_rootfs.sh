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
MESA_VERSION="$(python3 - <<'PY'
import json
print(json.load(open("tools/vessel_proroot/direct_gpu_manifest.json", encoding="utf-8"))["version"])
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
apt-get -o Dpkg::Use-Pty=0 -o APT::Color=0 install -y   bash ca-certificates curl git locales sudo   dbus dbus-x11 python3 python3-dbus python3-gi   kde-plasma-desktop plasma-workspace plasma-desktop plasma-desktoptheme qml6-module-org-kde-ksvg libkf6svg6 libkirigamiplatform6 qmlscene-qt6 kwin-wayland   xwayland qt6-wayland wayland-utils systemsettings   libinput-tools mesa-utils   breeze breeze-icon-theme hicolor-icon-theme   desktop-file-utils xdg-user-dirs shared-mime-info menu   appstream packagekit packagekit-tools polkitd pkexec plasma-discover   xdg-desktop-portal xdg-desktop-portal-kde   pulseaudio pulseaudio-utils alsa-utils   fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation   firefox-esr konsole dolphin ark kcalc okular gwenview kate

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
  /usr/share/dbus-1/services/org.freedesktop.portal.Documents.service \
  /usr/share/dbus-1/services/org.kde.KSplash.service
do
  if [ -f "$service" ]; then mv "$service" "$service.vessel-disabled"; fi
done

install -d -m0700 -o vessel -g vessel /home/vessel/.config
cat >/home/vessel/.config/ksplashrc <<'KSPLASH'
[KSplash]
Engine=None
KSPLASH
chown vessel:vessel /home/vessel/.config/ksplashrc
chmod 0600 /home/vessel/.config/ksplashrc

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

dpkg-query -W -f='${db:Status-Status}\n' plasma-desktoptheme | grep -Fxq installed
dpkg-query -W -f='${db:Status-Status}\n' qml6-module-org-kde-ksvg | grep -Fxq installed

apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /tmp/* /var/tmp/*
install -d -m1777 /tmp /var/tmp
rm -f /usr/sbin/policy-rc.d
CHROOT

# Overlay the exact direct-GPU and compositor builds pinned by Vessel.
sudo -E tools/vessel_proroot/install_direct_gpu_mesa.sh "$ROOTFS"
sudo -E tools/vessel_proroot/install_desktop_runtime.sh "$ROOTFS"

# Discover the final Qt6/QML layout from the built filesystem. Never encode
# Debian's current multiarch triplet in rootfs metadata or Android runtime code.
sudo python3 - "$ROOTFS" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
candidates = []
for base in (root / "usr/lib", root / "usr/local/lib"):
    if not base.is_dir():
        continue
    for qmldir in base.glob("**/qt6/qml/org/kde/plasma/core/qmldir"):
        qml = qmldir.parents[4]
        required = (
            qml / "org/kde/plasma/core/qmldir",
            qml / "org/kde/plasma/core/libcorebindingsplugin.so",
            qml / "org/kde/ksvg/qmldir",
            qml / "org/kde/ksvg/libcorebindingsplugin.so",
        )
        if all(path.is_file() and path.stat().st_size > 0 for path in required):
            candidates.append(qml)

candidates = sorted(set(candidates))
if not candidates:
    raise SystemExit("complete Plasma/KSvg Qt6 QML tree not found")

guest_qml = ["/" + path.relative_to(root).as_posix() for path in candidates]
plugin_roots = []
for qml in candidates:
    plugin = qml.parent / "plugins"
    if plugin.is_dir():
        plugin_roots.append("/" + plugin.relative_to(root).as_posix())

meta = root / "usr/lib/vessel/rootfs/runtime.env"
meta.parent.mkdir(parents=True, exist_ok=True)
meta.write_text(
    "QML_IMPORT_PATH=" + ":".join(guest_qml) + "\n"
    + "QT_PLUGIN_PATH=" + ":".join(plugin_roots) + "\n",
    encoding="utf-8",
)
PY
sudo chmod 0644 "$ROOTFS/usr/lib/vessel/rootfs/runtime.env"

# Prove the exact post-overlay rootfs can import the same discovered QML tree
# that Vessel will use on device.
sudo tee "$ROOTFS/var/cache/vessel/vessel-qml-probe.qml" >/dev/null <<'QML'
import QtQuick
import org.kde.ksvg as KSvg
import org.kde.plasma.core as PlasmaCore
Item {
    width: 8
    height: 8
    KSvg.SvgItem { width: 1; height: 1 }
    Component.onCompleted: console.log("VESSEL_QML_PROBE_OK")
}
QML
sudo chroot "$ROOTFS" /bin/bash -lc '
  set -e
  . /usr/lib/vessel/rootfs/runtime.env
  export QML_IMPORT_PATH
  export QML2_IMPORT_PATH="$QML_IMPORT_PATH"
  export QT_PLUGIN_PATH
  export QT_QPA_PLATFORM=offscreen
  export QT_QUICK_BACKEND=software
  rc=0
  timeout 5s /usr/bin/qmlscene6 /var/cache/vessel/vessel-qml-probe.qml \
    >/var/cache/vessel/plasma-qml-build-probe.log 2>&1 || rc=$?
  printf "VESSEL_QMLSCENE_RC=%s\\n" "$rc" >>/var/cache/vessel/plasma-qml-build-probe.log
'
if ! sudo grep -Fq 'VESSEL_QML_PROBE_OK' "$ROOTFS/var/cache/vessel/plasma-qml-build-probe.log" || \
   sudo grep -Eqi 'is not installed|is not a type|plugin cannot be loaded' "$ROOTFS/var/cache/vessel/plasma-qml-build-probe.log"; then
  echo "post-overlay Plasma QML runtime probe failed" >&2
  sudo cat "$ROOTFS/var/cache/vessel/plasma-qml-build-probe.log" >&2 || true
  sudo cat "$ROOTFS/usr/lib/vessel/rootfs/runtime.env" >&2 || true
  exit 1
fi

# /tmp stays inside the rootfs. Prove the final image retains normal Linux
# sticky-directory semantics after every overlay; Android must not replace it.
test "$(sudo stat -c '%a' "$ROOTFS/tmp")" = "1777"
sudo chroot "$ROOTFS" /bin/bash -lc '
  set -e
  f="$(mktemp /tmp/vessel-final.XXXXXX)"
  test -f "$f"
  rm -f "$f"
'

sudo test -s "$ROOTFS/usr/lib/aarch64-linux-gnu/dri/kgsl_dri.so"
sudo test -s "$ROOTFS/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
sudo test -s "$ROOTFS/usr/local/libexec/vessel-start-plasma"
sudo test -s "$ROOTFS/usr/local/libexec/vessel-compat-probe"
sudo test -s "$ROOTFS/usr/lib/vessel/direct-gpu/mesa.env"
sudo test -s "$ROOTFS/usr/lib/vessel/desktop/session.env"
sudo test -s "$ROOTFS/usr/lib/vessel/rootfs/runtime.env"

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

python3 - "$MANIFEST" "$DESKTOP_RELEASE" "$MESA_VERSION" "$ROOTFS/usr/lib/vessel/rootfs/runtime.env" <<PY
import json, os, sys
out, desktop_release, mesa_version, runtime_env = sys.argv[1:5]
env = {}
with open(runtime_env, encoding="utf-8") as source:
    for line in source:
        key, sep, value = line.rstrip("\n").partition("=")
        if sep:
            env[key] = value

qml_roots = [path.lstrip("/") for path in env.get("QML_IMPORT_PATH", "").split(":") if path]
if not qml_roots:
    raise SystemExit("runtime.env has no QML_IMPORT_PATH")

required = [
    "bin/sh",
    "usr/bin/kwin_wayland",
    "usr/bin/startplasma-wayland",
    "usr/local/libexec/vessel-start-plasma",
    "usr/local/libexec/vessel-compat-probe",
    "usr/lib/vessel/direct-gpu/mesa.env",
    "usr/lib/vessel/desktop/session.env",
    "usr/lib/vessel/desktop/direct-kwin-build.txt",
    "usr/lib/vessel/rootfs/runtime.env",
    "usr/bin/qmlscene6",
]
for root in qml_roots:
    required.extend([
        root + "/org/kde/plasma/core/qmldir",
        root + "/org/kde/plasma/core/libcorebindingsplugin.so",
        root + "/org/kde/ksvg/qmldir",
        root + "/org/kde/ksvg/libcorebindingsplugin.so",
    ])

data = {
  "schema": 1,
  "revision": os.environ.get("GITHUB_SHA", "local"),
  "runtime": "proroot",
  "debian": "trixie",
  "arch": "arm64",
  "compression": "zstd",
  "hardLinksFlattened": True,
  "archiveSha256": "$ARCHIVE_SHA",
  "archiveBytes": int("$ARCHIVE_BYTES"),
  "extractedBytes": int("$EXTRACTED_BYTES"),
  "entryCount": int("$ENTRY_COUNT"),
  "mesaVersion": mesa_version,
  "desktopRelease": desktop_release,
  "capabilities": [
    "rootless-proroot",
    "plasma-wayland",
    "plasma-qml-core",
    "ksvg-qml",
    "direct-kgsl",
    "native-surfacecontrol",
  ],
  "qmlImportPaths": ["/" + path for path in qml_roots],
  "requiredPaths": sorted(set(required)),
}
with open(out, "w", encoding="utf-8") as target:
  json.dump(data, target, indent=2, sort_keys=True)
  target.write("\n")
PY

echo "proroot rootfs archive: $ARCHIVE_BYTES bytes"
cat "$MANIFEST"
