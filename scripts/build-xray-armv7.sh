#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
ndk=$(cd "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}" && pwd)
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang"
commit=a4a3dff5b20c2faae567b742c172c7b95e75c083
archive_hash=7dba1dd055bdc854a2c5311610872c9bf6315ddb0a741fb8c2206212cec51b53
dest="$root/app/src/main/jniLibs/armeabi-v7a"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

export GOTOOLCHAIN=local
if [[ $(go env GOVERSION) != go1.27.3 ]]; then
  echo 'Xray ARMv7 requires Go 1.27.3.' >&2
  exit 1
fi

curl -fLsS --retry 3 "https://codeload.github.com/XTLS/Xray-core/tar.gz/$commit" -o "$work/xray.tar.gz"
printf '%s  %s\n' "$archive_hash" "$work/xray.tar.gz" | sha256sum -c -
tar -xzf "$work/xray.tar.gz" -C "$work"
cd "$work/Xray-core-$commit"
GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 CC="$compiler" go build \
  -mod=readonly -trimpath -buildmode=pie -buildvcs=false \
  -ldflags '-s -w -buildid= -checklinkname=0 -linkmode external -extldflags -Wl,-z,max-page-size=16384' \
  -o "$work/xray" ./main

install -Dm755 "$work/xray" "$dest/libxray_exec.so"
