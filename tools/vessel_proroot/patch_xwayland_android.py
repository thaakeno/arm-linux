#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = Path(__import__("sys").argv[1]).resolve() if len(__import__("sys").argv) > 1 else root

utils = source / "os" / "utils.c"
text = utils.read_text()

old = """    case 0:                    /* child */
        if (setgid(getgid()) == -1)
            _exit(127);
        if (setuid(getuid()) == -1)
            _exit(127);
"""
new = """    case 0:                    /* child */
        /*
         * Android app processes inherit zygote seccomp. On current Android,
         * setgid()/setuid() can be trapped even when they are semantic no-ops
         * (real == effective IDs). Xwayland's XKB helper uses Popen(), so the
         * historical unconditional privilege drop can kill the helper with
         * SIGSYS before xkbcomp starts. Only perform the calls when an actual
         * credential transition is required.
         */
        if (getgid() != getegid() && setgid(getgid()) == -1)
            _exit(127);
        if (getuid() != geteuid() && setuid(getuid()) == -1)
            _exit(127);
"""
if old not in text:
    raise SystemExit("Xwayland Popen credential block did not match pinned source")
utils.write_text(text.replace(old, new, 1))

# XF86Bigfont's local-client optimization uses SysV shmget() on Linux.
# Android application seccomp does not provide a normal unrestricted SysV IPC
# surface. The optimization is optional, so disable that path while retaining
# the extension's non-SysV behavior. MIT-SHM fd passing remains available.
bigfont = source / "Xext" / "xf86bigfont.c"
text = bigfont.read_text()
old = "static Bool badSysCall = FALSE;"
if old not in text:
    raise SystemExit("XF86Bigfont badSysCall marker did not match pinned source")
text = text.replace(
    old,
    """/*
 * Vessel runs inside an Android app sandbox. Avoid optional SysV shared-memory
 * optimization so Xwayland never probes shmget() from this path.
 */
static Bool badSysCall = TRUE;""",
    1,
)
bigfont.write_text(text)

# Preserve exact SIGSYS evidence for any remaining Android seccomp mismatch.
osinit = source / "os" / "osinit.c"
text = osinit.read_text()
old = """    if (sip->si_code == SI_USER) {
        ErrorFSigSafe("Received signal %u sent by process %u, uid %u\\n", signo,
                     sip->si_pid, sip->si_uid);
    }
    else {
"""
new = """    if (signo == SIGSYS) {
#if defined(__linux__)
        ErrorFSigSafe("XWAYLAND_SIGSYS syscall=%d arch=%u code=%d\\n",
                      sip->si_syscall, sip->si_arch, sip->si_code);
#else
        ErrorFSigSafe("XWAYLAND_SIGSYS code=%d\\n", sip->si_code);
#endif
    }
    if (sip->si_code == SI_USER) {
        ErrorFSigSafe("Received signal %u sent by process %u, uid %u\\n", signo,
                     sip->si_pid, sip->si_uid);
    }
    else {
"""
if old not in text:
    raise SystemExit("Xwayland SIGSYS handler insertion point did not match pinned source")
osinit.write_text(text.replace(old, new, 1))

print("VESSEL_XWAYLAND_ANDROID_PATCH_OK")
