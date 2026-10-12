#!/usr/bin/env bash
# Build, install, launch, and tail the probe in one go.
#   ./run.sh          build + install + launch + follow logcat
#   ./run.sh build    build only
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# The console's address. Override with STRIDE_DEVICE, or leave it and the
# script asks adb what is attached — which is right far more often than a
# hardcoded IP, and is the difference between this working on one bench and
# working on anyone's.
# adb on Windows ends its lines in \r, which would hide every device.
DEV="${STRIDE_DEVICE:-$(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1; exit}')}"
if [ -z "$DEV" ]; then
  echo "No device. Plug in over USB, or: export STRIDE_DEVICE=<ip>:<port>" >&2
  exit 1
fi
PKG="dev.stride.usbprobe"

# Android Studio's default SDK location on macOS, Windows, then Linux.
if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Android/Sdk"; do
    [ -d "$d" ] && { ANDROID_HOME="$d"; break; }
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# java_home is macOS only. On Windows or Linux, set JAVA_HOME to a JDK 17.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$HERE"
echo ">>> building"
./gradlew --quiet --console=plain assembleDebug

APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "FAILED: no APK at $APK"; exit 1; }
echo ">>> built $(du -h "$APK" | cut -f1)"

[ "${1:-}" = "build" ] && exit 0

echo ">>> installing"
adb connect "$DEV" >/dev/null 2>&1 || true
adb -s "$DEV" install -r "$APK"

echo ">>> launching"
adb -s "$DEV" shell am start -n "$PKG/.MainActivity" >/dev/null

echo ">>> logcat (ctrl-c to stop)"
adb -s "$DEV" logcat -c
adb -s "$DEV" logcat -s StrideProbe:I
