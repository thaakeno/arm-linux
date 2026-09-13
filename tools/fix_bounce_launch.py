#!/usr/bin/env python3
from pathlib import Path

kt = Path('app/src/main/java/com/example/dreamlinux/BounceQuestActivity.kt')
s = kt.read_text()
old = '''        root.post {
            buildHud(root)
            buildWinOverlay(root)
        }

        surface.holder.addCallback'''
new = '''        root.post {
            buildHud(root)
            buildWinOverlay(root)
            handler.post(tick)
        }

        surface.holder.addCallback'''
if old not in s:
    raise SystemExit('HUD init block not found')
s = s.replace(old, new, 1)
s = s.replace('''        handler.post(tick)\n    }''', '''    }''', 1)
kt.write_text(s)

cpp = Path('app/src/main/cpp/engine/renderer/BounceRenderer.cpp')
s = cpp.read_text()
old = 'void stop(){if(running_.exchange(false)&&thread_.joinable())thread_.join();}'
new = 'void stop(){running_.store(false);if(thread_.joinable())thread_.join();}'
if old not in s:
    raise SystemExit('renderer stop block not found')
s = s.replace(old, new, 1)
cpp.write_text(s)

print('Bounce Quest launch/lifecycle crash fixes applied')
