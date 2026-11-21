#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
NDK_BIN="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android26-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi26-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android26-clang"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
for item in 'aarch64-linux-android arm64-v8a' 'x86_64-linux-android x86_64'; do
 read -r target abi <<< "$item"
 cargo build --locked --release --target "$target"
 mkdir -p "../app/src/main/jniLibs/$abi"
 cp "${CARGO_TARGET_DIR:-target}/$target/release/libvolna_core.so" "../app/src/main/jniLibs/$abi/"
done
