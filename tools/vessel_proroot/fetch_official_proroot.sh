#!/usr/bin/env bash
set -euo pipefail

DEST="${1:-app/src/main/jniLibs/arm64-v8a}"
BASE="https://github.com/coderredlab/proroot/releases/download/v1.2.8"
mkdir -p "$DEST"

declare -A SHA=(
  [libproroot.so]=a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01
  [libproroot-runtime.so]=8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34
  [libproroot-bridge.so]=1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f
  [libproroot-linker.so]=51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee
  [libproroot-stub-loader.so]=06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0
)

for name in "${!SHA[@]}"; do
  target="$DEST/$name"
  expected="${SHA[$name]}"
  if [[ -s "$target" ]] && [[ "$(sha256sum "$target" | awk '{print $1}')" == "$expected" ]]; then
    echo "verified cached $name"
    chmod 0755 "$target"
    continue
  fi
  rm -f "$target.tmp"
  curl --fail --location --retry 4 --retry-all-errors --connect-timeout 15     -o "$target.tmp" "$BASE/$name"
  actual="$(sha256sum "$target.tmp" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    echo "SHA-256 mismatch for $name" >&2
    echo "expected: $expected" >&2
    echo "actual:   $actual" >&2
    rm -f "$target.tmp"
    exit 2
  }
  mv -f "$target.tmp" "$target"
  chmod 0755 "$target"
done

echo "official unmodified proroot v1.2.8 runtime staged in $DEST"
