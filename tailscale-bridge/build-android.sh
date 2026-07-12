#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk}"
if [[ -d "$NDK_ROOT/toolchains" ]]; then
  NDK_DIR="$NDK_ROOT"
else
  NDK_DIR="$(find "$NDK_ROOT" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
HOST_TAG="darwin-arm64"
if [[ ! -d "$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG" ]]; then
  HOST_TAG="darwin-x86_64"
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

if [[ ! -x "$TOOLCHAIN/bin/aarch64-linux-android26-clang" ]]; then
  echo "Android NDK not found at $NDK_ROOT" >&2
  exit 1
fi

mkdir -p "$OUT"
pushd "$(dirname "$0")" >/dev/null
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
  go build -buildmode=c-shared -o "$OUT/libtailscalebridge.so" .
# The generated C header is only needed while building a statically linked
# consumer. JNI resolves this bridge dynamically, so do not package it.
rm -f "$OUT/libtailscalebridge.h"
"$TOOLCHAIN/bin/aarch64-linux-android26-clang" -shared -fPIC \
  -I"$TOOLCHAIN/sysroot/usr/include" \
  -o "$OUT/libtailscalebridgejni.so" jni/tailscalebridge_jni.c -ldl
popd >/dev/null
