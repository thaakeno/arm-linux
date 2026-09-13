#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="${VESSEL_ROOT:-$HOME/venus-poc}"
PROP="$HOME/.termux/termux.properties"

echo "[vessel-setup] configuring Termux for the Vessel APK"
mkdir -p "$HOME/.termux"
touch "$PROP"
if grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=' "$PROP"; then
  sed -i -E 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps=true/' "$PROP"
else
  printf '\nallow-external-apps=true\n' >> "$PROP"
fi

echo "[vessel-setup] ensuring small runtime dependencies"
pkg install -y python git

if [ ! -d "$ROOT/.git" ]; then
  echo "[vessel-setup] expected repository missing: $ROOT" >&2
  echo "Clone https://github.com/thaakeno/arm-linux there first." >&2
  exit 2
fi

git -C "$ROOT" fetch origin app/vessel-final

missing=0
for p in \
  "$HOME/venus-wsi-local/debian-docker.ext4" \
  "$HOME/venus-wsi-local/linux-umshm" \
  "$HOME/venus-wsi-local/stub_exe-umshm" \
  "$HOME/venus-wsi-local/umnet" \
  "$HOME/venus-wsi-local/passt"; do
  if [ ! -e "$p" ]; then
    alt="$HOME/uml-test/${p##*/}"
    if [ ! -e "$alt" ]; then
      echo "[vessel-setup] missing runtime asset: ${p##*/}" >&2
      missing=1
    fi
  fi
done

if [ ! -e "$PREFIX/opt/virglrenderer-android/.venus-thread-worker" ]; then
  echo "[vessel-setup] WARNING: the proven thread-worker virglrenderer marker is missing."
  echo "[vessel-setup] Build it once with tools/venus_poc/build_virglrenderer_android_thread.sh."
  missing=1
fi

printf '\n[vessel-setup] allow-external-apps: '
grep -E '^allow-external-apps=' "$PROP" | tail -1
if [ "$missing" -eq 0 ]; then
  echo "[vessel-setup] PASS: proven UML/Venus runtime assets are present."
else
  echo "[vessel-setup] PARTIAL: Termux integration is configured, but runtime assets above still need preparation."
fi

echo "[vessel-setup] Android still requires the Vessel app's Additional Permission: Run commands in Termux environment."
