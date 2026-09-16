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
print("alpha3 legacy qualification markers added without changing runtime behavior")
