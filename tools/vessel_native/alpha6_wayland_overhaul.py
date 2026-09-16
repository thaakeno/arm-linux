#!/usr/bin/env python3
from pathlib import Path
import runpy
import sys

# Compatibility entry point kept because the native rebuild script already
# invokes this path. The hardened v2 patcher owns the alpha6 rewrite, then the
# device-tested DRM session repair layers on top of the generated runtime.
base = Path(__file__).with_name("alpha6_wayland_overhaul_v2.py")
sys.argv = [str(base), *sys.argv[1:]]
runpy.run_path(str(base), run_name="__main__")

fix = Path(__file__).with_name("alpha6_wayland_drm_session_fix.py")
sys.argv = [str(fix), *sys.argv[1:]]
runpy.run_path(str(fix), run_name="__main__")
