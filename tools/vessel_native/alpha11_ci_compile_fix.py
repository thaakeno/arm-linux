#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONFIG = ROOT / "app/src/main/java/com/example/dreamlinux/VesselExperimentConfig.kt"
AGENT = ROOT / "app/src/main/java/com/example/dreamlinux/VesselGuestAgent.kt"

config = CONFIG.read_text()
if "fun desktopBackend(context: Context)" not in config:
    anchor = '''    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)\n    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }\n\n    fun reset(context: Context) {\n'''
    replacement = '''    fun invertPointerY(context: Context): Boolean = prefs(context).getBoolean("invert_pointer_y", false)\n    fun setInvertPointerY(context: Context, value: Boolean) { prefs(context).edit().putBoolean("invert_pointer_y", value).apply() }\n\n    fun desktopBackend(context: Context): String = prefs(context).getString("desktop_backend", "wayland").let { if (it == "x11") "x11" else "wayland" }\n    fun setDesktopBackend(context: Context, value: String) { prefs(context).edit().putString("desktop_backend", if (value == "x11") "x11" else "wayland").apply() }\n    fun hostGl(context: Context): String = prefs(context).getString("host_gl", "system").let { if (it == "angle") "angle" else "system" }\n    fun setHostGl(context: Context, value: String) { prefs(context).edit().putString("host_gl", if (value == "angle") "angle" else "system").apply() }\n    fun firefoxDmabuf(context: Context): Boolean = prefs(context).getBoolean("firefox_dmabuf", false)\n    fun setFirefoxDmabuf(context: Context, value: Boolean) { prefs(context).edit().putBoolean("firefox_dmabuf", value).apply() }\n\n    fun reset(context: Context) {\n'''
    if config.count(anchor) != 1:
        raise SystemExit("alpha11: VesselExperimentConfig anchor missing")
    config = config.replace(anchor, replacement, 1)
    CONFIG.write_text(config)

agent = AGENT.read_text()
old = '''        } finally {\n            runCatching { s.soTimeout = 0 }\n        }\n    }\n\n    private fun closeConnectionLocked() {\n'''
new = '''        } finally {\n            runCatching { s.soTimeout = 0 }\n        }\n        // The response loop exits only via return@synchronized or exception,\n        // but keeping an explicit Pair expression makes Kotlin's generic\n        // synchronized() return type unambiguous across compiler versions.\n        @Suppress("UNREACHABLE_CODE")\n        -1 to out.toString()\n    }\n\n    private fun closeConnectionLocked() {\n'''
if new not in agent:
    if agent.count(old) != 1:
        raise SystemExit("alpha11: VesselGuestAgent return anchor missing")
    agent = agent.replace(old, new, 1)
    AGENT.write_text(agent)

print("[alpha11] restored Wayland experiment API after stable-profile rewrite + fixed guest-agent return typing")
