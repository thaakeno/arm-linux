#!/usr/bin/env python3
from pathlib import Path
p=Path(__file__).resolve().parents[2]/'app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt'
s=p.read_text()
def rep(a,b):
    global s
    if a not in s: raise SystemExit('controls marker missing: '+a[:100])
    s=s.replace(a,b,1)
rep('    private var subtitleToken = 0\n', '    private var subtitleToken = 0\n    private var graphicsMode = 1 // 0 raster, 1 hybrid RT, 2 cinematic path\n')
rep('    private fun onTouch(v: View, e: MotionEvent): Boolean {\n        val w = max(v.width, 1)\n', '''    private fun onTouch(v: View, e: MotionEvent): Boolean {\n        val w = max(v.width, 1)\n        // No HUD: a deliberate three-finger tap cycles benchmark render modes.\n        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount >= 3) {\n            cycleGraphicsMode()\n            return true\n        }\n''')
needle='    private fun initAudio() {\n'
fn='''    private fun cycleGraphicsMode() {\n        val h = rendererHandle\n        if (h == 0L) return\n        graphicsMode = (graphicsMode + 1) % 3\n        when (graphicsMode) {\n            0 -> { nativeSetPathTracing(h, false); nativeSetRt(h, false); showSubtitle("RASTER", 1400) }\n            1 -> { nativeSetPathTracing(h, false); nativeSetRt(h, true); showSubtitle("HYBRID RAY TRACING", 1400) }\n            else -> { nativeSetRt(h, true); nativeSetPathTracing(h, true); showSubtitle("CINEMATIC PATH TRACING", 1600) }\n        }\n    }\n\n'''
rep(needle,fn+needle)
p.write_text(s)
print('hidden three-finger graphics selector installed')
