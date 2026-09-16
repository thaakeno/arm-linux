#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
CONTROLLER = ROOT / "app/src/main/java/com/example/dreamlinux/VesselRuntimeController.kt"
SERVICE = ROOT / "app/src/main/java/com/example/dreamlinux/VmSessionService.kt"
AGENT = ROOT / "app/src/main/java/com/example/dreamlinux/VesselGuestAgent.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}: {old[:180]!r}")
    return text.replace(old, new, 1)


# Physical v51 testing proved that the boot tty itself was healthy through the
# audio setup, but the next command stalled inside Python subprocess.Popen()
# before it could print either the child PID or the tty completion marker.
#
# Do not use Popen for this one-shot daemon bootstrap. Popen has a parent/child
# exec-error pipe and, depending on options, a fork_exec path that can leave the
# parent blocked until the child reaches exec. Instead call os.posix_spawn()
# directly with explicit file actions and POSIX_SPAWN_SETSID where glibc exposes
# it. The launcher therefore has no PIPE, no wait(), no communicate(), and no
# inherited tty stdio in the long-lived agent. If setsid is unavailable we retry
# without it; the agent still has /dev/null + file-backed stdio and reconnects.
text = CONTROLLER.read_text()
old_method = r'''    private fun startGuestControlAgentBlocking() {
        val port = VesselGuestAgent.port()
        check(port > 0) { "Android guest-control server did not start" }
        val helper = context.assets.open("vessel/guest_control_agent.py").use { it.readBytes() }
        val helperB64 = Base64.getEncoder().encodeToString(helper)
        val token = VesselGuestAgent.token()
        val command = """
            set -e
            install -d -m 755 /usr/local/lib/vessel
            printf '%s' '$helperB64' | base64 -d >/usr/local/lib/vessel/control_agent.py
            chmod 0755 /usr/local/lib/vessel/control_agent.py
            pkill -f '/usr/local/lib/vessel/control_agent.py' 2>/dev/null || true
            nohup /usr/bin/python3 /usr/local/lib/vessel/control_agent.py 10.0.2.2 $port '$token' >/tmp/vessel-control-agent.log 2>&1 </dev/null &
            echo VESSEL_CONTROL_AGENT_STARTED
        """.trimIndent()
        val result = guestBlocking(command, 20)
        check(result.first == 0) { "Could not start Debian control agent: ${result.second.takeLast(5000)}" }
        append("[control] persistent Debian control agent launched port=$port\\n")
    }
'''
new_method = r'''    private fun startGuestControlAgentBlocking() {
        val port = VesselGuestAgent.port()
        check(port > 0) { "Android guest-control server did not start" }
        val helper = context.assets.open("vessel/guest_control_agent.py").use { it.readBytes() }
        val helperB64 = Base64.getEncoder().encodeToString(helper)
        val token = VesselGuestAgent.token()
        val launcher = """
            #!/usr/bin/python3
            import os
            import signal
            import sys
            import time

            host = sys.argv[1]
            port = sys.argv[2]
            token = sys.argv[3]
            pidfile = "/run/vessel-control-agent.pid"
            log_path = "/tmp/vessel-control-agent.log"
            agent_path = "/usr/local/lib/vessel/control_agent.py"

            # Terminate only the PID we previously recorded. Avoid pkill -f on a
            # command that itself contains the agent path.
            try:
                old_pid = int(open(pidfile, "r", encoding="ascii").read().strip())
            except Exception:
                old_pid = -1
            if old_pid > 1:
                try:
                    cmdline = open(f"/proc/{old_pid}/cmdline", "rb").read().replace(b"\\x00", b" ")
                    if agent_path.encode() in cmdline:
                        os.kill(old_pid, signal.SIGTERM)
                        for _ in range(20):
                            try:
                                os.kill(old_pid, 0)
                            except ProcessLookupError:
                                break
                            time.sleep(0.05)
                except Exception:
                    pass

            env = dict(os.environ)
            env.update({"HOME": "/root", "TERM": "dumb", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"})
            flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND
            actions = [
                (os.POSIX_SPAWN_OPEN, 0, "/dev/null", os.O_RDONLY, 0o600),
                (os.POSIX_SPAWN_OPEN, 1, log_path, flags, 0o600),
                (os.POSIX_SPAWN_DUP2, 1, 2),
            ]
            argv = ["/usr/bin/python3", agent_path, host, port, token]

            print("VESSEL_CONTROL_AGENT_SPAWN_BEGIN", flush=True)
            try:
                pid = os.posix_spawn("/usr/bin/python3", argv, env, file_actions=actions, setsid=True)
            except (NotImplementedError, TypeError):
                pid = os.posix_spawn("/usr/bin/python3", argv, env, file_actions=actions)

            with open(pidfile, "w", encoding="ascii") as f:
                f.write(str(pid))
            print(f"VESSEL_CONTROL_AGENT_PID={pid}", flush=True)
        """.trimIndent()
        val launcherB64 = Base64.getEncoder().encodeToString(launcher.toByteArray(Charsets.UTF_8))
        val command = """
            set -e
            install -d -m 755 /usr/local/lib/vessel
            printf '%s' '$helperB64' | base64 -d >/usr/local/lib/vessel/control_agent.py
            printf '%s' '$launcherB64' | base64 -d >/usr/local/lib/vessel/launch_control_agent.py
            chmod 0755 /usr/local/lib/vessel/control_agent.py /usr/local/lib/vessel/launch_control_agent.py
            /usr/bin/python3 /usr/local/lib/vessel/launch_control_agent.py 10.0.2.2 $port '$token'
            echo VESSEL_CONTROL_AGENT_STARTED
        """.trimIndent()
        val result = guestBlocking(command, 15)
        check(result.first == 0 && result.second.contains("VESSEL_CONTROL_AGENT_STARTED")) {
            "Could not start Debian control agent: ${result.second.takeLast(5000)}"
        }
        check(VesselGuestAgent.waitUntilConnected(20_000)) {
            "Debian control agent spawned but did not authenticate back to Android; see /tmp/vessel-control-agent.log"
        }
        append("[control] persistent Debian control agent connected port=$port\\n")
    }
'''
text = replace_once(text, old_method, new_method, "posix-spawn control-agent bootstrap")
text = text.replace("v50-stable-control-audio-ui-r1", "v52-posix-spawn-control-agent-r1")
CONTROLLER.write_text(text)

agent = AGENT.read_text()
agent = replace_once(
    agent,
    '''    fun status(): String = lastStatus\n\n    fun resetConnection() {\n''',
    '''    fun status(): String = lastStatus\n    fun waitUntilConnected(waitMs: Long = 20_000): Boolean = awaitConnection(waitMs.coerceIn(1_000, 60_000))\n\n    fun resetConnection() {\n''',
    "public authenticated connection wait",
)
AGENT.write_text(agent)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v50-stable-control-audio-ui-r1",
        "v52-posix-spawn-control-agent-r1",
    ))

print("[alpha12] posix_spawn control-agent bootstrap + authenticated connection gate applied")