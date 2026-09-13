#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SRC="$(dirname "$0")/build_virglrenderer_android_thread.sh"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

python3 - "$SRC" "$TMP" <<'PY'
from pathlib import Path
import sys
src = Path(sys.argv[1]).read_text()
s = src.replace('.venus-thread-worker', '.venus-process-worker')
s = s.replace('render-server-worker=thread', 'render-server-worker=process')
s = s.replace('thread worker', 'process worker')
s = s.replace('thread-worker', 'process-worker')
s = s.replace('selecting the thread worker', 'selecting the process worker')
Path(sys.argv[2]).write_text(s)
PY

chmod +x "$TMP"
exec "$TMP"
