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


# v50 physical testing reached the new control-agent bootstrap and then the boot
# tty RPC timed out. The only long-lived process in that RPC was started with a
# shell background job (`nohup ... &`). Even with stdio redirected, keeping a
# daemon launch inside the line-oriented tty transaction is needlessly fragile.
# Use the same proven strategy as the Wayland launcher: a short Python parent
# spawns the agent with DEVNULL/close_fds/start_new_session, records its PID,
# prints a deterministic launch marker, and exits immediately.
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
            import subprocess
            import sys
            import time

            host = sys.argv[1]
            port = sys.argv[2]
            token = sys.argv[3]
            pidfile = "/run/vessel-control-agent.pid"
            log_path = "/tmp/vessel-control-agent.log"

            try:
                old_pid = int(open(pidfile, "r", encoding="ascii").read().strip())
            except Exception:
                old_pid = -1
            if old_pid > 1:
                try:
                    cmdline = open(f"/proc/{old_pid}/cmdline", "rb").read().replace(b"\\x00", b" ")
                    if b"/usr/local/lib/vessel/control_agent.py" in cmdline:
                        os.kill(old_pid, signal.SIGTERM)
                        for _ in range(20):
                            try:
                                os.kill(old_pid, 0)
                            except ProcessLookupError:
                                break
                            time.sleep(0.05)
                except Exception:
                    pass

            log = open(log_path, "ab", buffering=0)
            proc = subprocess.Popen(
                ["/usr/bin/python3", "/usr/local/lib/vessel/control_agent.py", host, port, token],
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                cwd="/root",
                close_fds=True,
                start_new_session=True,
            )
            with open(pidfile, "w", encoding="ascii") as f:
                f.write(str(proc.pid))
            print(f"VESSEL_CONTROL_AGENT_PID={proc.pid}", flush=True)
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
        val result = guestBlocking(command, 30)
        check(result.first == 0 && result.second.contains("VESSEL_CONTROL_AGENT_STARTED")) {
            "Could not start Debian control agent: ${result.second.takeLast(5000)}"
        }
        check(VesselGuestAgent.waitUntilConnected(15_000)) {
            "Debian control agent process launched but did not authenticate back to Android; see /tmp/vessel-control-agent.log"
        }
        append("[control] persistent Debian control agent connected port=$port\\n")
    }
'''
text = replace_once(text, old_method, new_method, "detached control-agent bootstrap")
text = text.replace("v50-stable-control-audio-ui-r1", "v51-detached-control-agent-r1")
CONTROLLER.write_text(text)

agent = AGENT.read_text()
agent = replace_once(
    agent,
    '''    fun status(): String = lastStatus\n\n    fun resetConnection() {\n''',
    '''    fun status(): String = lastStatus\n    fun waitUntilConnected(waitMs: Long = 15_000): Boolean = awaitConnection(waitMs.coerceIn(1_000, 60_000))\n\n    fun resetConnection() {\n''',
    "public authenticated connection wait",
)
AGENT.write_text(agent)

if SERVICE.exists():
    SERVICE.write_text(SERVICE.read_text().replace(
        "v50-stable-control-audio-ui-r1",
        "v51-detached-control-agent-r1",
    ))

print("[alpha12] detached control-agent bootstrap + authenticated connection gate applied")
