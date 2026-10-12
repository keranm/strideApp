#!/usr/bin/env bash
# NordicUnchained — fire the moment ADB comes up, before eru re-locks.
# Order matters: eru dies first, everything else is cleanup.

set -u
say() { printf '\n>>> %s\n' "$*"; }

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools and put it on your PATH." >&2
  exit 1
fi

# Gen 7 / newer consoles: Wireless debugging wants a one-off pairing first, on
# a different port from the one you connect to, with a code that changes every
# time the pairing dialog opens. Pairing needs platform-tools 30 or later, and
# an older adb is half of why the original Unchained script stopped working.
#   STRIDE_PAIR=<ip>:<pairing port>  STRIDE_PAIR_CODE=<six digits>
if [ -n "${STRIDE_PAIR:-}" ]; then
  TOOLS="$(adb version | tr -d '\r' | sed -n 's/^Version \([0-9]*\).*/\1/p')"
  if [ -n "$TOOLS" ] && [ "$TOOLS" -lt 30 ]; then
    echo "adb is platform-tools $TOOLS. Pairing needs 30 or later; update it." >&2
    exit 1
  fi
  say "Pairing with $STRIDE_PAIR"
  adb pair "$STRIDE_PAIR" "${STRIDE_PAIR_CODE:?set STRIDE_PAIR_CODE to the six-digit code on the console}" \
    || { echo "FAILED: pairing. Reopen the pairing dialog; the port and code change each time." >&2; exit 1; }
fi

# The console's address. Override with STRIDE_DEVICE, or leave it and the
# script asks adb what is attached — which is right far more often than a
# hardcoded IP, and is the difference between this working on one bench and
# working on anyone's. adb on Windows ends its lines in \r, hence the tr.
DEV="${STRIDE_DEVICE:-$(adb devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1; exit}')}"
if [ -z "$DEV" ]; then
  echo "No device. Plug in over USB, or: export STRIDE_DEVICE=<ip>:<port>" >&2
  echo "On Gen 7 the port is the one under Wireless debugging, not 5555." >&2
  exit 1
fi
HERE="$(cd "$(dirname "$0")" && pwd)"

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
case "$DEV" in
  *:5555) ;;
  *:*) cat <<EOF

  NOTE: $DEV looks like Wireless debugging rather than classic ADB over Wi-Fi.
  Its port changes when the console reboots or Wireless debugging is toggled.
  This computer stays paired, so next time read the new port under Developer
  options → Wireless debugging and run: adb connect <ip>:<new port>

EOF
  ;;
esac

# ---- 3b. Gen 7: free the motor board. ----
# On Gen 7 the board (USB 0x213C/0x0003, FitPro 2) is held by iFit's motor
# service, which restarts itself after a force-stop. Disabling it is the only
# way STRIDE gets the board. Gen 6 never needed this, so it stays Gen 6-shaped
# there: only when the Gen 7 board is actually on the bus.
if sh_ dumpsys usb 2>/dev/null | tr -d '\r' | grep -A1 'vendor_id=8508' | grep -q 'product_id=3$'; then
    say "Gen 7 board found — disabling com.ifit.glassos_service so STRIDE can claim it"
    sh_ pm disable-user --user 0 com.ifit.glassos_service
    echo "  to give the board back to iFit:  adb -s $DEV shell pm enable com.ifit.glassos_service"
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
APK="$HERE/../console/stride/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || APK="$HERE/../console/stride/app/build/outputs/apk/debug/app-debug.apk"
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

# Android 10+ blocks an app opening itself from a boot receiver unless it may
# display over other apps — so without this a Gen 7 (Android 13) powers on to
# the Android launcher and STRIDE waits to be found. Same permission iFit's
# eru held. The launcher stays the home app, and everything else on the
# console stays reachable.
if [ "$(sh_ getprop ro.build.version.sdk | tr -d '\r')" -ge 29 ] 2>/dev/null &&
   sh_ pm list packages | grep -q dev.stride.hud; then
    say "Letting STRIDE open itself at power-on (display over other apps)"
    sh_ appops set dev.stride.hud SYSTEM_ALERT_WINDOW allow
fi

if [ "${STRIDE_KIOSK:-1}" = "1" ] && sh_ pm list packages | grep -q dev.stride.hud; then
    say "Making STRIDE the home app"
    # The alias ships disabled: two home apps and no preference between them
    # means Android asks "which one?" at every boot. Enabling it and setting
    # the preference has to happen together, in that order.
    #
    # Android 13 (Gen 7) refuses `pm enable` on another app's component, so
    # there the app enables its own alias at our request — see KioskReceiver.
    if sh_ pm enable dev.stride.hud/.HomeAlias 2>&1 | grep -q "Exception"; then
        echo "  pm enable refused (Android 12+) — asking STRIDE to enable it itself"
        sh_ am broadcast -a dev.stride.hud.KIOSK --ez on true -n dev.stride.hud/.KioskReceiver >/dev/null
        OFF="adb -s $DEV shell am broadcast -a dev.stride.hud.KIOSK --ez on false -n dev.stride.hud/.KioskReceiver"
    else
        OFF="adb -s $DEV shell pm disable-user --user 0 dev.stride.hud/.HomeAlias"
    fi
    sh_ cmd package set-home-activity dev.stride.hud/.HomeAlias
    echo "  to hand the console back to its launcher:"
    echo "      $OFF"
fi

say "Current launchers"
sh_ cmd package query-activities \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    2>/dev/null | grep -i packageName | sort -u

if sh_ pm list packages | grep -q dev.stride.hud; then
    if [ "${STRIDE_KIOSK:-1}" = "1" ]; then
        say "DONE — eru disabled, STRIDE installed and set as home. Reboot to verify it sticks."
    else
        say "DONE — eru disabled, STRIDE installed (home app left alone). Reboot to verify it sticks."
    fi
else
    say "DONE — eru disabled. STRIDE is not installed yet. Reboot to verify eru stays off."
fi
