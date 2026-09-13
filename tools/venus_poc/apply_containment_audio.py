#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
kt=ROOT/'app/src/main/java/com/example/dreamlinux/NativeCubeActivity.kt'
p1=ROOT/'app/src/main/cpp/native_vulkan_studio_v7_part1.inc'
s=kt.read_text()
def rep(a,b):
    global s
    if a not in s: raise SystemExit('audio Kotlin marker missing: '+a[:90])
    s=s.replace(a,b,1)
rep('import android.media.AudioAttributes\nimport android.media.SoundPool\n','import android.media.AudioAttributes\nimport android.media.MediaPlayer\nimport android.media.SoundPool\n')
rep('    private lateinit var soundPool: SoundPool\n','    private lateinit var soundPool: SoundPool\n    private var facilityAmbience: MediaPlayer? = null\n    private var stormAmbience: MediaPlayer? = null\n')
rep('        initAudio()\n','        initAudio()\n        initAmbience()\n')
rep('                line.startsWith("SFX:ALARM") -> play("alarm", .36f, .82f)\n','                line.startsWith("SFX:ALARM") -> play("alarm", .36f, .82f)\n                line.startsWith("SFX:RAIN_OUT") -> { stormAmbience?.setVolume(.58f, .58f); facilityAmbience?.setVolume(.08f, .08f) }\n')
needle='    private fun initAudio() {\n'
fn='''    private fun initAmbience() {\n        facilityAmbience = MediaPlayer.create(this, R.raw.factory_ambience)?.apply {\n            isLooping = true; setVolume(.20f, .20f); start()\n        }\n        stormAmbience = MediaPlayer.create(this, R.raw.rain_ambience)?.apply {\n            isLooping = true; setVolume(.035f, .035f); start()\n        }\n    }\n\n'''
rep(needle,fn+needle)
rep('        soundPool.release()\n','        soundPool.release()\n        facilityAmbience?.release(); facilityAmbience = null\n        stormAmbience?.release(); stormAmbience = null\n')
kt.write_text(s)

c=p1.read_text();old='gameEvent("SUB:Cold rain hits your face. The emergency lift is ahead. You made it out.");gameEvent("SFX:POWER");'
new='gameEvent("SUB:Cold rain hits your face. The emergency lift is ahead. You made it out.");gameEvent("SFX:RAIN_OUT");gameEvent("SFX:POWER");'
if old not in c: raise SystemExit('audio native marker missing')
p1.write_text(c.replace(old,new,1))
print('layered facility/rain ambience installed')
