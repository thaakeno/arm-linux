#!/usr/bin/env python3
from pathlib import Path

source_path = Path(__file__).with_name("alpha3c.py")
source = source_path.read_text()
old_open = "optimization = r'''# VESSEL_SCANOUT_DIRTY_CONTEXT_SYNC"
new_open = 'optimization = r"""# VESSEL_SCANOUT_DIRTY_CONTEXT_SYNC'
old_close = "final = path.read_text()\n'''\nreplace_once(patch, anchor, optimization)"
new_close = 'final = path.read_text()\n"""\nreplace_once(patch, anchor, optimization)'
if source.count(old_open) != 1 or source.count(old_close) != 1:
    raise SystemExit("alpha3c quote repair anchors changed")
source = source.replace(old_open, new_open, 1).replace(old_close, new_close, 1)
exec(compile(source, str(source_path), "exec"), globals(), globals())
