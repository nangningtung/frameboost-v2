#!/bin/bash
# Build HUD in-process (Zygisk) untuk arm64-v8a.
# Output: out/arm64-v8a.so — disalin ke module payload sebagai
# zygisk/arm64-v8a.so saat integrasi.
set -euo pipefail

NDK="${ANDROID_NDK_HOME:-C:/Users/Administrator/AppData/Local/Android/Sdk/ndk/26.3.11579264}"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android24-clang++.cmd"
ROOT="$(cd "$(dirname "$0")" && pwd)"
IMGUI="$ROOT/third_party/imgui"
DOBBY="$ROOT/third_party/dobby"
OUT="$ROOT/out"
mkdir -p "$OUT"

SOURCES=(
    "$ROOT/src/main.cpp"
    "$IMGUI/imgui.cpp"
    "$IMGUI/imgui_draw.cpp"
    "$IMGUI/imgui_tables.cpp"
    "$IMGUI/imgui_widgets.cpp"
    "$IMGUI/backends/imgui_impl_opengl3.cpp"
)

INCLUDES=(
    -I"$ROOT/src"
    -I"$ROOT/third_party"
    -I"$IMGUI"
    -I"$IMGUI/backends"
    -I"$DOBBY"
)

"$CC" -std=c++20 -O2 -fPIC -shared -static-libstdc++ \
    -DIMGUI_IMPL_OPENGL_ES3 \
    -DZYGISK_API_VERSION=2 \
    "${INCLUDES[@]}" \
    "${SOURCES[@]}" \
    "$DOBBY/libs/arm64-v8a/libdobby.a" \
    -lEGL -lGLESv3 -llog \
    -o "$OUT/arm64-v8a.so"

echo "OK: $OUT/arm64-v8a.so"
ls -la "$OUT/arm64-v8a.so"
