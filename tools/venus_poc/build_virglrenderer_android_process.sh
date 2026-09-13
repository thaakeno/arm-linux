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
"$TMP"

# The rebuilt package intentionally keeps the upstream package version. `apt install`
# therefore reports "already the newest version" and does not unpack the freshly
# built process-worker binary. Force the local .deb over the installed copy.
TP_DIR="${TERMUX_PACKAGES_DIR:-$HOME/termux-packages-venus}"
DEB="$(find "$TP_DIR/output" "$TP_DIR/debs" -maxdepth 1 -type f -name 'virglrenderer-android_*_aarch64.deb' 2>/dev/null | sort | tail -1 || true)"
[ -n "$DEB" ] || {
  echo "[venus-build] no rebuilt virglrenderer-android .deb found for force install" >&2
  exit 1
}

echo "[venus-build] force-installing rebuilt process-worker package: $DEB"
dpkg -i "$DEB"

MARKER="$PREFIX/opt/virglrenderer-android/.venus-process-worker"
touch "$MARKER"

test -x "$PREFIX/opt/virglrenderer-android/libexec/virgl_render_server"
test -x "$(command -v virgl_test_server_android)"
echo "[venus-build] process-worker package is now actually installed"
