#!/usr/bin/env python3
from pathlib import Path
import runpy
import sys

# Compatibility entry point kept because the native rebuild script already
# invokes this path. The hardened v2 patcher owns the actual alpha6 rewrite.
target = Path(__file__).with_name("alpha6_wayland_overhaul_v2.py")
sys.argv[0] = str(target)
runpy.run_path(str(target), run_name="__main__")
