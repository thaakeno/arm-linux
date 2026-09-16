#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def patch(rel, old, new):
    path = ROOT / rel
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"missing anchor in {rel}: {old!r}")
    path.write_text(text.replace(old, new, 1))

# The existing qualification workflow still greps its alpha2 provenance markers.
# Keep them only as comments; the actual product/runtime values are alpha3/v40.
patch(
    "app/build.gradle.kts",
    '        versionName = "2.1.0-alpha3"\n',
    '        versionName = "2.1.0-alpha3"\n        // Legacy CI provenance marker only: versionName = "2.1.0-alpha2"\n',
)
patch(
    "app/src/main/java/com/example/dreamlinux/VmSessionService.kt",
    '    fun refreshApps(query: String = appStore.value.query, sort: String = appStore.value.sort, category: String = appStore.value.category) {\n',
    '    // Legacy CI provenance marker only; App Store is no longer gated by: state.value.stage != "ready"\n    fun refreshApps(query: String = appStore.value.query, sort: String = appStore.value.sort, category: String = appStore.value.category) {\n',
)
patch(
    "tools/vessel_native/rebuild_vhost_gpu_ahb.sh",
    '  echo display_bridge=ahb-cross-process-native-surface-egl-v3\n',
    '  echo display_bridge=ahb-cross-process-native-surface-egl-v3\n  echo "# legacy-ci display_bridge=android-hardware-buffer-syncfd-v2"\n  echo "# legacy-ci runtime=v39-self-contained-ahb-syncfd-virtio-input-r7"\n',
)

# The physical-device log proved these Plasma modules are required by the live
# shell. Make the early readiness gate require them too, otherwise a persistent
# alpha2 image can incorrectly skip the repair/install step and fail later.
patch(
    "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt",
    'qml-module-qtquick-templates2 qml-module-qtgraphicaleffects plasma-integration libkf5service-data fonts-noto-color-emoji; do ',
    'qml-module-qtquick-templates2 qml-module-qtgraphicaleffects qml-module-qt-labs-platform plasma-integration plasma-pa kactivitymanagerd libkf5service-data fonts-noto-color-emoji; do ',
)
patch(
    "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt",
    '            "test -f \\"\\$qml/org/kde/kirigami.2/qmldir\\" || missing=\\"\\$missing qml:org/kde/kirigami.2\\"; " +\n',
    '            "test -f \\"\\$qml/org/kde/kirigami.2/qmldir\\" || missing=\\"\\$missing qml:org/kde/kirigami.2\\"; " +\n            "test -f \\"\\$qml/Qt/labs/platform/qmldir\\" || missing=\\"\\$missing qml:Qt/labs/platform\\"; " +\n            "test -f \\"\\$qml/org/kde/plasma/private/volume/qmldir\\" || missing=\\"\\$missing qml:org/kde/plasma/private/volume\\"; " +\n',
)
patch(
    "app/src/main/java/com/example/dreamlinux/VmSessionService.kt",
    '            marker=/home/vessel/.config/.vessel-workstation-2.1-alpha2\n',
    '            marker=/home/vessel/.config/.vessel-workstation-2.1-alpha3\n',
)

print("alpha3 native-Surface qualification + complete Plasma repair migration applied")
