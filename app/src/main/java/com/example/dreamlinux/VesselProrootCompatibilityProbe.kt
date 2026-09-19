package com.example.dreamlinux

/**
 * APK-owned compatibility probe for the shared-kernel proroot runtime.
 *
 * This intentionally does not use the copy baked into the large rootfs. Runtime
 * semantics evolve faster than the distro image and must be updated with the APK.
 *
 * Android 16 may reject access(R_OK) against /proc/self/exe even though proroot's
 * documented PROROOT_GUEST_EXE readlink emulation works. Linux applications
 * overwhelmingly use readlink(2) here to discover their executable path, so we
 * validate that exact contract instead of inventing a stronger Android-host
 * readability requirement.
 */
internal object VesselProrootCompatibilityProbe {
    const val PYTHON = "/usr/bin/python3"
    const val SESSION_DBUS = "/usr/bin/dbus-run-session"

    fun kernelArgv(): List<String> = listOf(PYTHON, "-c", kernelPython)

    fun sessionBusArgv(): List<String> = listOf(
        SESSION_DBUS,
        "--",
        "/bin/sh",
        "-c",
        sessionBusShell,
    )

    internal val kernelPython = """
import array
import fcntl
import mmap
import os
import socket
import stat
import struct
import subprocess
import sys
import tempfile

def fail(name, exc=None):
    msg = "VESSEL_COMPAT_FAIL=" + name
    if exc is not None:
        msg += ":" + str(exc)
    print(msg, file=sys.stderr)
    raise SystemExit(1)

# This is the actual proroot contract. Do not replace it with os.access(...):
# Android's procfs permissions can reject access(R_OK) while readlink emulation
# is correct and usable by normal Linux software.
try:
    exe = os.readlink("/proc/self/exe")
    if not exe.startswith("/usr/bin/python3"):
        fail("proc-self-exe-target", exe)
    print("VESSEL_COMPAT_OK=proc-self-exe:" + exe)
except Exception as exc:
    fail("proc-self-exe-readlink", exc)

try:
    with open("/proc/stat", "rb") as f:
        if not f.read(1):
            fail("proc-stat-empty")
except Exception as exc:
    fail("proc-stat", exc)

try:
    xdg = os.environ.get("XDG_RUNTIME_DIR", "")
    if not xdg or not os.path.isdir(xdg):
        fail("xdg-runtime-dir")
    if stat.S_IMODE(os.stat(xdg).st_mode) != 0o700:
        fail("xdg-runtime-mode")
except Exception as exc:
    fail("xdg-runtime", exc)

if not os.path.isdir("/dev/shm") or not os.access("/dev/shm", os.W_OK):
    fail("dev-shm")

try:
    fd = os.memfd_create(
        "vessel-compat",
        os.MFD_CLOEXEC | getattr(os, "MFD_ALLOW_SEALING", 0),
    )
    os.write(fd, b"vessel")
    if hasattr(fcntl, "F_ADD_SEALS") and hasattr(fcntl, "F_SEAL_GROW"):
        fcntl.fcntl(fd, fcntl.F_ADD_SEALS, fcntl.F_SEAL_GROW)
    os.close(fd)
except Exception as exc:
    fail("memfd", exc)

try:
    with tempfile.TemporaryFile(dir="/dev/shm") as f:
        f.truncate(4096)
        with mmap.mmap(f.fileno(), 4096) as m:
            m[:6] = b"vessel"
except Exception as exc:
    fail("shm-mmap", exc)

try:
    a, b = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
    r, w = os.pipe()
    fds = array.array("i", [w])
    a.sendmsg([b"x"], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, fds)])
    _, anc, _, _ = b.recvmsg(1, socket.CMSG_SPACE(fds.itemsize))
    got = []
    for level, typ, data in anc:
        if level == socket.SOL_SOCKET and typ == socket.SCM_RIGHTS:
            got.extend(array.array("i", data[:len(data) - (len(data) % fds.itemsize)]))
    if not got:
        fail("scm-rights")
    for inherited in got:
        os.close(inherited)
    os.close(r)
    os.close(w)
    a.close()
    b.close()
except Exception as exc:
    fail("scm-rights", exc)

try:
    a, b = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    b.setsockopt(socket.SOL_SOCKET, socket.SO_PASSCRED, 1)
    a.send(b"x")
    _, anc, _, _ = b.recvmsg(1, socket.CMSG_SPACE(struct.calcsize("3i")))
    creds = None
    for level, typ, data in anc:
        if level == socket.SOL_SOCKET and typ == socket.SCM_CREDENTIALS:
            creds = struct.unpack("3i", data[:struct.calcsize("3i")])
    if not creds or creds[1] != os.getuid():
        fail("scm-credentials")
    a.close()
    b.close()
except Exception as exc:
    fail("scm-credentials", exc)

try:
    a, b = socket.socketpair()
    peer = struct.unpack("3i", a.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    if peer[1] != os.getuid():
        fail("so-peercred")
    a.close()
    b.close()
except Exception as exc:
    fail("so-peercred", exc)

try:
    subprocess.run(["/bin/true"], check=True)
except Exception as exc:
    fail("fork-exec", exc)

print("VESSEL_COMPAT_OK=kernel-ipc")
""".trimIndent()

    internal val sessionBusShell = """
        set -e
        test -n "${'$'}{DBUS_SESSION_BUS_ADDRESS:-}"
        reply=${'$'}(dbus-send --session --print-reply           --dest=org.freedesktop.DBus           /org/freedesktop/DBus           org.freedesktop.DBus.RequestName           string:org.vessel.SessionBusProbe uint32:0)
        printf '%s\n' "${'$'}reply"
        printf '%s\n' "${'$'}reply" | grep -Eq 'uint32[[:space:]]+1'
        echo VESSEL_COMPAT_OK=dbus-session
    """.trimIndent()
}
