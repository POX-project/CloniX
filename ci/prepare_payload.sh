#!/usr/bin/env bash
# Packs the compiled runtime payload (dex) and native hook libraries into
# app/src/main/assets/cloneforge/ so the patcher can inject them into clone APKs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AAR_PAYLOAD="$ROOT/runtime-payload/build/outputs/aar/runtime-payload-release.aar"
AAR_NATIVE="$ROOT/native-bridge/build/outputs/aar/native-bridge-release.aar"
[ -f "$AAR_PAYLOAD" ] || { echo "runtime-payload AAR missing: $AAR_PAYLOAD" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ASSETS="$ROOT/app/src/main/assets/cloneforge"
rm -rf "$ASSETS"
mkdir -p "$ASSETS/native"

# 1. Extract payload classes.jar (+ lsplant java classes if present)
unzip -o -q "$AAR_PAYLOAD" classes.jar -d "$WORK/payload"
JARS=("$WORK/payload/classes.jar")
if [ -f "$AAR_NATIVE" ]; then
  unzip -o -q "$AAR_NATIVE" classes.jar -d "$WORK/native" 2>/dev/null || true
  [ -f "$WORK/native/classes.jar" ] && JARS+=("$WORK/native/classes.jar")
fi

# 2. Dexify with d8 from the newest installed build-tools
ANDROID_JAR="$(ls -v "$ANDROID_HOME"/platforms/*/android.jar | tail -n1)"
D8="$(ls -v "$ANDROID_HOME"/build-tools/*/d8 | tail -n1)"
mkdir -p "$WORK/dexout"
"$D8" --release --lib "$ANDROID_JAR" --output "$WORK/dexout" "${JARS[@]}"
mv "$WORK/dexout/classes.dex" "$ASSETS/runtime-payload.dex"

# 3. Copy native libs
if [ -f "$AAR_NATIVE" ]; then
  unzip -o -q "$AAR_NATIVE" 'jni/*' -d "$WORK/jniroot"
  for abi_dir in "$WORK/jniroot"/jni/*/; do
    abi="$(basename "$abi_dir")"
    mkdir -p "$ASSETS/native/$abi"
    cp "$abi_dir"/*.so "$ASSETS/native/$abi/"
  done
fi

# Ensure any local jniLibs (e.g. libshadowhook_nothing.so) are copied
if [ -d "$ROOT/native-bridge/src/main/jniLibs" ]; then
  for abi_dir in "$ROOT/native-bridge/src/main/jniLibs"/*/; do
    [ -d "$abi_dir" ] || continue
    abi="$(basename "$abi_dir")"
    mkdir -p "$ASSETS/native/$abi"
    cp "$abi_dir"/*.so "$ASSETS/native/$abi/" 2>/dev/null || true
  done
fi

echo "payload assets:"
find "$ASSETS" -type f -exec ls -l {} \;
