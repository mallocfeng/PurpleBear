#!/usr/bin/env bash
# Rebuilds app/libs/libXray.aar from upstream libXray / Xray-core v26.11.9
# plus the patches in this folder (per-app routing needs 0001; the
# connection view needs 0003 and libxray-0001).
#
# Needs: git, python3, Go (fetches go1.27.4 via GOTOOLCHAIN), the Android
# SDK with NDK 29 (ANDROID_HOME / ANDROID_NDK_HOME).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$ROOT/build/libxray-src}"
VERSION=v26.11.9

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.14206865}"
export LIBXRAY_ANDROID_API=26
export LIBXRAY_GOMOBILE_VERSION=v0.0.0-20261011034528-197770444867
export GOTOOLCHAIN=go1.27.4
export PATH="$(go env GOPATH)/bin:$PATH"

mkdir -p "$WORK"
cd "$WORK"
[ -d Xray-core ] || git clone --depth 1 --branch "$VERSION" https://github.com/XTLS/Xray-core.git
[ -d libXray ] || git clone --depth 1 --branch "$VERSION" https://github.com/XTLS/libXray.git

git -C Xray-core checkout -- .
git -C Xray-core clean -fdq
for patch in "$HERE"/0*.patch; do
  git -C Xray-core apply "$patch"
done
git -C libXray checkout -- .
git -C libXray clean -fdq -e libXray.aar -e libXray-sources.jar -e dat
for patch in "$HERE"/libxray-*.patch; do
  git -C libXray apply "$patch"
done

# Upstream builds every Android ABI; PurpleBear ships arm64-v8a only.
git -C libXray checkout -- build/app/android.py
sed -i.bak 's/                    "android",$/                    "android\/arm64",/' libXray/build/app/android.py
rm -f libXray/build/app/android.py.bak

(cd libXray && python3 build/main.py android local)
cp libXray/libXray.aar "$ROOT/app/libs/libXray.aar"
shasum -a 256 "$ROOT/app/libs/libXray.aar"
