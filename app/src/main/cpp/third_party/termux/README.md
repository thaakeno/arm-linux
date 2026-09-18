# Vessel PTY JNI provenance

`vessel_termux_pty.c` implements the JNI ABI expected by Termux
`terminal-emulator` 0.118.0 and is maintained as authoritative Vessel source.

The basic PTY flow is derived from
`termux/termux-app@v0.118.0/terminal-emulator/src/main/jni/termux.c`.
Termux documents the `terminal-emulator` and `terminal-view` modules as
Apache License 2.0 exceptions to the repository's GPLv3 license.

Vessel does **not** patch upstream C at build time. The additional pre-exec
session-identity handshake is part of this directly compiled source so cleanup
can verify PID birth time/session ownership before signaling processes.

Source reference:
https://github.com/termux/termux-app/tree/v0.118.0/terminal-emulator
