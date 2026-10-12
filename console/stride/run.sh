#!/usr/bin/env bash
# Build, install, launch, and tail the probe in one go.
#   ./run.sh            build + install + launch + follow logcat
#   ./run.sh build      build only
#   ./run.sh release    force a release build
#   ./run.sh debug      force a debug build
#
# Which one you get by default depends on whether you have set up a signing
# key. A debug build is `debuggable`, which lets anything else on the console
# read this app's store — broker password included — and it turns on the
# WebView DevTools socket. Fine on a bench, not what you want left in a
# hallway. So: if local.properties names a keystore, this builds release; if
# it does not, it builds debug and says so rather than asking you to set up
# signing before you can see your treadmill work. See app/build.gradle.kts for
# the four properties, and the keytool line that makes the key.
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
PKG="dev.stride.hud"

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

# A keystore named in local.properties is the signal that release builds are
# set up. Anything else — no line, or a line pointing at a file that is not
# there — means debug, because a treadmill you cannot install onto is worse
# than one running a debug build.
# `|| true`: with no local.properties at all — a fresh clone — sed fails, and
# under pipefail that would end the script here without a word.
KEYSTORE="$(sed -n 's/^stride\.keystore=//p' local.properties 2>/dev/null | head -1 || true)"
case "${1:-}" in
  release) VARIANT=release ;;
  debug)   VARIANT=debug ;;
  *)       if [ -n "$KEYSTORE" ] && [ -f "$KEYSTORE" ]; then VARIANT=release; else VARIANT=debug; fi ;;
esac

if [ "$VARIANT" = "release" ]; then
  echo ">>> building (release, signed)"
  ./gradlew --quiet --console=plain assembleRelease
  APK="$HERE/app/build/outputs/apk/release/app-release.apk"
  if [ ! -f "$APK" ]; then
    # An unsigned APK is what you get when the keystore properties are missing
    # or wrong. adb will refuse it, so say why here rather than there.
    UNSIGNED="$HERE/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ -f "$UNSIGNED" ]; then
      echo "FAILED: release build is unsigned — check the stride.keystore.* lines" >&2
      echo "        in local.properties. See app/build.gradle.kts." >&2
      exit 1
    fi
  fi
else
  echo ">>> building (debug)"
  ./gradlew --quiet --console=plain assembleDebug
  APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
fi

[ -f "$APK" ] || { echo "FAILED: no APK at $APK"; exit 1; }
echo ">>> built $(du -h "$APK" | cut -f1)"
if [ "$VARIANT" = "debug" ]; then
  echo "    debug build: debuggable, DevTools on. Fine for the bench."
  echo "    For the machine you actually walk on, set up signing and run: ./run.sh release"
fi

[ "${1:-}" = "build" ] && exit 0

echo ">>> installing"
adb connect "$DEV" >/dev/null 2>&1 || true
adb -s "$DEV" install -r "$APK"

echo ">>> launching"
adb -s "$DEV" shell am start -n "$PKG/.MainActivity" >/dev/null

echo ">>> logcat (ctrl-c to stop)"
adb -s "$DEV" logcat -c
adb -s "$DEV" logcat -s Stride:I
