#!/usr/bin/python3
import subprocess

uid = subprocess.check_output(['/usr/bin/id', '-u', 'vessel'], text=True).strip()
env_command = (
    f'XDG_RUNTIME_DIR=/run/user/{uid} XDG_SEAT=seat0 XDG_VTNR=1 '
    'exec dbus-run-session -- /usr/local/bin/vessel-plasma-session'
)
log = open('/tmp/vessel-plasma.log', 'ab', buffering=0)
try:
    proc = subprocess.Popen(
        ['/usr/bin/su', '-l', 'vessel', '-c', env_command],
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=subprocess.STDOUT,
        cwd='/home/vessel',
        close_fds=True,
        start_new_session=True,
    )
except Exception:
    log.close()
    raise
print(f'VESSEL_WAYLAND_PID={proc.pid}', flush=True)
