#!/usr/bin/env bash
# NordicUnchained — fire the moment ADB comes up, before eru re-locks.
# Order matters: eru dies first, everything else is cleanup.

set -u
# The console's address. Override with STRIDE_DEVICE, or leave it and the
# script asks adb what is attached — which is right far more often than a
# hardcoded IP, and is the difference between this working on one bench and
# working on anyone's.
DEV="${STRIDE_DEVICE:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}"
if [ -z "$DEV" ]; then
  echo "No device. Plug in over USB, or: export STRIDE_DEVICE=<ip>:5555" >&2
  exit 1
fi
HERE="$(cd "$(dirname "$0")" && pwd)"

say() { printf '\n>>> %s\n' "$*"; }
sh_() { adb -s "$DEV" shell "$@"; }

say "Connecting to $DEV"
for i in $(seq 1 30); do
    adb connect "$DEV" 2>&1 | grep -q connected && break
    sleep 1
done
adb -s "$DEV" wait-for-device || { echo "FAILED: no device"; exit 1; }

# ---- 1. Kill eru FIRST. This is the thing that re-locks us on boot. ----
say "Disabling com.ifit.eru (the re-locker)"
sh_ pm disable-user --user 0 com.ifit.eru
sh_ appops set com.ifit.eru SYSTEM_ALERT_WINDOW deny
sh_ appops set com.ifit.eru WRITE_SETTINGS deny

# ---- 2. Snapshot state while we still have a shell, for diagnosis. ----
say "Capturing device state"
{
    echo "=== date ==="; date
    echo "=== owners ==="; sh_ dpm list-owners
    echo "=== adb props ==="; sh_ getprop | grep -i adb
    echo "=== selinux ==="; sh_ getenforce
    echo "=== android ==="; sh_ getprop ro.build.version.release
    echo "=== ifit packages ==="; sh_ pm list packages | grep -i ifit
    echo "=== eru state ==="; sh_ dumpsys package com.ifit.eru | head -60
} > "$HERE/device-state.txt" 2>&1
echo "  -> saved to device-state.txt"

# ---- 3. Make ADB survive reboot so we stop losing access. ----
say "Persisting ADB"
sh_ settings put global adb_enabled 1
sh_ settings put global development_settings_enabled 1
# keep the screen alive while we work
sh_ settings put global stay_on_while_plugged_in 7

# ADB over Wi-Fi is the part whose persistence varies by console.
#
# `adb tcpip 5555` sets service.adb.tcp.port, a runtime property that init
# clears at every boot. persist.adb.tcp.port is the one that survives, and
# writing it usually needs root. So we try, and then check — but a failed
# setprop is NOT proof that Wi-Fi ADB is about to disappear: on the console
# this was developed against it comes back after a power cycle regardless,
# without the persist property ever being set. Some builds restore it
# themselves. Rather than assert which yours is, this says what is true and
# tells you the one command that fixes it if yours forgets.
sh_ setprop persist.adb.tcp.port 5555 2>/dev/null || true
if [ "$(sh_ getprop persist.adb.tcp.port | tr -d '\r')" = "5555" ]; then
    say "ADB over Wi-Fi pinned by property — it will survive a reboot"
else
    cat <<'EOF'

  NOTE: could not pin ADB over Wi-Fi with a property (this usually needs root).
  Many consoles bring it back after a power cycle anyway — check that yours
  does, because on a console built into a treadmill there is usually no USB
  port to fall back on, and Wi-Fi ADB is the only way in.

  Leave USB debugging enabled in Developer Options. That is the switch that
  would lock you out.

  The treadmill itself is fine either way: STRIDE comes back on its own. This
  is only about your remote access to it.

EOF
fi

# ---- 4. Launcher replacement. ----
say "Disabling com.ifit.launcher"
sh_ pm disable-user --user 0 com.ifit.launcher

if [ -f "$HERE/NovaLauncher.apk" ]; then
    say "Installing Nova Launcher"
    adb -s "$DEV" install -r "$HERE/NovaLauncher.apk"
fi

for apk in SmartTube_stable_31.94_universal.apk fullscreenhide.apk; do
    if [ -f "$HERE/$apk" ]; then
        say "Installing $apk"
        adb -s "$DEV" install -r "$HERE/$apk"
    fi
done

# ---- 5. STRIDE itself, and make the console come back to it. ----
#
# The app carries a boot receiver, so a power cycle brings STRIDE up on its own
# wherever it is installed. Making it the *home* app as well is what covers the
# other ways a console ends up staring at a launcher: the app being dismissed,
# or killed, or crashing. On a machine you step onto and walk, "tap the STRIDE
# icon first" is one instruction too many.
#
# Set STRIDE_KIOSK=0 to install the app and leave the home app alone.
#
# STRIDE_ENGINE picks the build flavor: `classic` (default, System WebView) or
# `gecko` (bundled GeckoView, for old consoles like the Elite 900). Prefer a
# release APK, fall back to debug.
ENGINE="${STRIDE_ENGINE:-classic}"
OUT="$HERE/../console/stride/app/build/outputs/apk/$ENGINE"
APK="$OUT/release/app-$ENGINE-release.apk"
[ -f "$APK" ] || APK="$OUT/debug/app-$ENGINE-debug.apk"
if [ -f "$APK" ]; then
    say "Installing STRIDE"
    adb -s "$DEV" install -r "$APK"
    # And launch it, which is not decoration.
    #
    # Installing force-stops the app, and Android puts a force-stopped app into
    # the "stopped state", where it receives no broadcasts at all — including
    # BOOT_COMPLETED. Install and walk away and the boot receiver never fires,
    # for ever, and the console comes up to a launcher every time with no clue
    # why. Launching once clears the flag. Found on 2026-08-10 by installing a
    # build and not restarting it.
    say "Launching STRIDE (clears the stopped state, so it will start at boot)"
    adb -s "$DEV" shell am start -n dev.stride.hud/.MainActivity >/dev/null
else
    say "No STRIDE APK yet — build it with console/stride/run.sh, then re-run this"
fi

if [ "${STRIDE_KIOSK:-1}" = "1" ] && sh_ pm list packages | grep -q dev.stride.hud; then
    say "Making STRIDE the home app"
    # The alias ships disabled: two home apps and no preference between them
    # means Android asks "which one?" at every boot. Enabling it and setting
    # the preference has to happen together, in that order.
    sh_ pm enable dev.stride.hud/.HomeAlias
    sh_ cmd package set-home-activity dev.stride.hud/.HomeAlias
    echo "  to hand the console back to its launcher:"
    echo "      adb -s $DEV shell pm disable-user --user 0 dev.stride.hud/.HomeAlias"
fi

say "Current launchers"
sh_ cmd package query-activities \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    2>/dev/null | grep -i packageName | sort -u

say "DONE — eru disabled, STRIDE installed and set as home. Reboot to verify it sticks."
