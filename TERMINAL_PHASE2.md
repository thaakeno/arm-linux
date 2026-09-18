# Vessel terminal phase 2

Phase 2 replaces the old command/output box with a real multi-session PTY architecture. It does not switch Vessel away from UML and it does not package proroot/rootfs yet.

## Runtime path

```
Compose terminal page
  -> VesselTerminalViewHost
  -> Termux terminal-view / terminal-emulator 0.118.0
  -> Vessel-maintained libtermux.so PTY JNI
  -> VesselProrootLaunchPlan
  -> official unmodified proroot runtime
  -> /bin/bash -l
```

The Termux Java terminal modules provide VT/ANSI emulation, selection, scrollback, IME handling and terminal rendering. Vessel does not maintain a partial terminal emulator.

The native PTY source is directly compiled from
`app/src/main/cpp/third_party/termux/vessel_termux_pty.c`. It implements the ABI expected by terminal-emulator and is derived from the Apache-2.0 Termux v0.118.0 PTY implementation. There is no build-time patch script, generated replacement, alpha transform or source rewrite.

## Sessions

- Up to 8 terminals.
- Internal IDs only increase and never repeat.
- Visible Terminal 1 / Terminal 2 numbers reuse gaps after a successful close.
- Switching pages or recreating the Activity does not restart shells.
- Closing one tab cannot close another tab.
- Close failures keep the tab registered and surface the error.
- Scrollback lives only in terminal-emulator. Vessel does not mirror every output byte into Compose state.
- Copy logs reads the transcript only when requested.
- Search polls the selected transcript only while the search UI is open.

## PTY ownership and cleanup

The native child calls `setsid()` before guest exec. Before it can execute proroot it reads its own `/proc/self/stat` and sends that record to the parent over a private `SOCK_SEQPACKET` socketpair.

The parent validates that:

- the reported PID is the forked child;
- PPID is the Vessel app process;
- process group equals PID;
- session ID equals PID.

Only then is exec acknowledged.

Java immediately claims that birth identity. Explicit close revalidates Android UID, session ID and Linux starttime before every leader signal. The verified leader is stopped first, only same-UID members of that exact session are killed, then the leader is killed last. Natural exit is checked for leftover session members and a cleanup warning is shown if anything survives.

This is intentionally stricter than just calling `TerminalSession.finishIfRunning()`, which only signals the launcher PID.

## UI

The Linux PTY mode includes:

- multiple persistent tabs and + New;
- close terminal;
- native soft/physical keyboard handling;
- long-press text selection from terminal-view;
- Copy logs;
- Paste;
- Clear history;
- on-demand scrollback search;
- Esc, Tab, arrows, Ctrl-C, Ctrl-D, Ctrl-Z and common shell keys;
- pinch font scaling;
- the existing Android Host Debug shell as a separate mode.

Arrow keys are generated with terminal-emulator's `KeyHandler`, so applications that enable cursor application mode receive the correct sequences.

## No app-specific compatibility layer

Every PTY starts through `VesselProrootLaunchPlan` and a normal `/bin/bash -l`. There are no launch branches for vim, htop, tmux, package managers, browsers or other individual Linux applications.

## Activation gate

The PTY UI exists in this phase, but New Terminal remains disabled until:

1. all five official proroot v1.2.8 libraries are packaged;
2. their pinned SHA-256 hashes match;
3. the directory rootfs is installed.

That prevents a second half-configured Linux environment from silently diverging from the future desktop runtime.

## Sources

- Termux v0.118.0 terminal-emulator / terminal-view: https://github.com/termux/termux-app/tree/v0.118.0
- Termux library coordinates: https://github.com/termux/termux-app/wiki/Termux-Libraries
- DSHA current multi-terminal implementation and process-reaping work: https://github.com/DSH-APP/DSHA
- coderredlab/proroot runtime contract: https://github.com/coderredlab/proroot
