#!/bin/bash
set -euo pipefail

SRC="${1:-/root/guest_relay_direct.py}"
DST="/usr/local/bin/guest_relay_direct.py"
MARK_BEGIN="# >>> dreamlinux venus >>>"
MARK_END="# <<< dreamlinux venus <<<"

if [ ! -e /dev/umshm ]; then
  echo "[guest-setup] /dev/umshm is missing; boot the patched UML kernel first" >&2
  exit 1
fi

if [ ! -f "$SRC" ]; then
  echo "[guest-setup] relay not found at $SRC" >&2
  exit 1
fi

install -m 0755 "$SRC" "$DST"

cat >/etc/profile.d/venus.sh <<'EOF'
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.json
export XDG_RUNTIME_DIR=/tmp
EOF
chmod 0644 /etc/profile.d/venus.sh

python3 - <<'PY'
from pathlib import Path
p = Path('/root/.bashrc')
text = p.read_text() if p.exists() else ''
begin = '# >>> dreamlinux venus >>>'
end = '# <<< dreamlinux venus <<<'
block = r'''# >>> dreamlinux venus >>>
export VTEST_SOCKET_NAME=/tmp/.venus_test
export VN_DEBUG=vtest
export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.json
export XDG_RUNTIME_DIR=/tmp

if [ -e /dev/umshm ] && ! pgrep -f '[g]uest_relay_direct.py.*--host 10.0.2.2.*--port 5002' >/dev/null 2>&1; then
    rm -f /tmp/.venus_test
    nohup python3 /usr/local/bin/guest_relay_direct.py \
        --host 10.0.2.2 \
        --port 5002 \
        --unix /tmp/.venus_test \
        >/tmp/venus-guest-direct.log 2>&1 &
fi
# <<< dreamlinux venus <<<'''
if begin in text and end in text:
    before = text.split(begin, 1)[0]
    after = text.split(end, 1)[1]
    text = before + block + after
else:
    if text and not text.endswith('\n'):
        text += '\n'
    text += '\n' + block + '\n'
p.write_text(text)
PY

# Apply variables immediately for this shell when sourced manually later.
echo "[guest-setup] installed relay: $DST"
echo "[guest-setup] installed Vulkan env: /etc/profile.d/venus.sh"
echo "[guest-setup] added relay autostart to /root/.bashrc"
echo "[guest-setup] next UML boot should start the reusable relay automatically"
