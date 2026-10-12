# Installing STRIDE

> 🚧 **DRAFT.** Nobody has followed this page end to end on a factory-reset
> machine. [Report anything that does not match](https://github.com/keranm/strideApp/issues).

**Read [SAFETY.md](../SAFETY.md) before you start.** This software drives a
motorised treadmill.

> **Gen 7.** Part 1 **works on Gen 7**. It was tested on 11/10/2026 on a Gen 7
> incline trainer with iFit builds dated 25/08/2026. Privileged mode, ADB over
> Wireless debugging and disabling iFit all worked. STRIDE drove the belt and
> deck, first with the belt empty and since then with its owner walking on it.
> Its motor board speaks a different protocol,
> [FitPro 2](../protocol/FITPRO2_PROTOCOL.md), which STRIDE now speaks too. Gen 7
> differences are marked **Gen 7** below. One machine so far: treat it as new.

To undo everything: factory reset the console.

---

## What you need

| | |
|---|---|
| A treadmill | NordicTrack or ProForm, FitPro motor board, Android console. Check [Step 0](#step-0-check-your-treadmill). |
| A computer | macOS, Linux or Windows, with `adb` and Python 3.9+. On Windows, run the `.sh` scripts in Git Bash. See [Windows](#windows). |
| JDK 17 + Android SDK | Only if you build it yourself. There is a [signed APK](https://github.com/keranm/strideApp/releases/latest) if you would rather not — see [1.7](#17-install-stride). |
| Home Assistant | Optional. Needs an MQTT broker. |
| An iPhone | Optional. Apple Health only. |

Allow about an hour for Part 1, ten minutes for Part 2.

Parts 2 and 3 are optional. Stop after Part 1 and the treadmill works with no
subscription.

---

## Step 0. Check your treadmill

STRIDE is confirmed on one machine:

| | |
|---|---|
| Model | NordicTrack C 1750 |
| Generation | Gen 6, the "CLASSIC" embedded console |
| GlassOS | 8.51.7.1070 |
| System version | EKA2_20221110 |
| Firmware | 84.121 |
| Motor board | FitPro, device id `0x04` |
| Ranges reported | incline -3 to +12 %, speed 1.6 to 20 km/h |

- **Likely to work:** other Gen 6 NordicTrack and ProForm consoles on a FitPro board.
- **New:** Gen 7, on the FitPro 2 board. One machine, walked on.
- **Required:** an Android console you can reach with `adb`.

One Gen 7 machine, a NordicTrack X22i, has been through Part 1 and is walked
on under STRIDE. Every machine and generation known so far is in
[treadmills/](../treadmills/README.md).

| | |
|---|---|
| Machine type | Incline trainer (iFit reports `INCLINE_TRAINER`) |
| Generation | Gen 7, "CLASSIC" embedded console |
| Android | 13 (Gen 6 is Android 9) |
| System version | `VKX1_20240619` |
| iFit / GlassOS | 2026.08.25 builds, firmware 1.19 |
| Console hardware | MediaTek MT8390 ("Xenon") |
| Model string | `ETNT39225-INT`, as the board reports it |
| Motor board | USB `0x213C`/`0x0003` "iFIT-LargeX", **FitPro 2**, software 1.19 |
| Ranges reported | incline −6 to +40 %, speed 1.5 to 20 km/h |

What you can check right now, on the console's own screen:

1. Go to **Settings → About tablet**.
2. Read **Build number**. This machine reports `EKA2_20221110`.
3. Read **Android version**. This machine is on Android 9.

You cannot identify the motor board yet. That needs ADB and the iFit software
out of the way, so it happens in [step 1.6](#16-identify-the-motor-board).

[Open an issue](https://github.com/keranm/strideApp/issues) with what you find,
working or not.

---

## Part 1. The console

### 1.1 Factory reset

Privileged mode in 1.2 is entered from the iFit welcome screen, before anyone
signs in. The reset is what gets you there with no iFit account on the
console and nothing half-configured. It is also what you fall back on if
anything later goes wrong. If your console has never been signed in and is
showing the welcome screen, you can try 1.2 first and come back here if it
fails. Either way, keep the console off the internet.

**Gen 7:** iFit cannot sign out while it is offline. It only shows a "no
internet connection" screen. Rather than going back online to sign out, reset.

1. Find the pinhole reset button. It is a small recessed hole and needs a
   paperclip or SIM tool.
   - **Gen 6:** a single small hole on the side panel or the top of the console.
   - **Gen 7 / 2026:** reported on the left side of the tablet enclosure.
2. Hold the pinhole in while you switch the treadmill on at the power switch.
3. Wait for *System recovery* in blue text.
4. Let it finish. Takes five to ten minutes.
5. Do not join Wi-Fi yet. Stay offline until iFit is disabled in step 1.3.

### 1.2 Enable privileged mode

On the iFit welcome screen:

1. Tap a blank area of the screen ten times.
2. Wait seven seconds. Nothing appears during the pause.
3. Tap the same spot ten more times.
4. The console shows a challenge code. Get the matching response code, either
   by ringing NordicTrack support and asking for one, or from a third-party
   calculator such as <https://getresponsecode.com> (no connection to this
   project).
5. Enter the response code.
6. The console opens the default Android apps screen. Tap **Settings**.

**Gen 7:** privileged mode opens iFit's own maintenance menu instead, with
*clear update files*, *clear admin cache*, *sleep*, *screen saver*, *check for
updates* and *CS help*. **Do not tap "check for updates".** Android's Settings can
be reached from there. If you can't see a way in, try swiping down from the top
edge for the gear icon.

### 1.3 Stop iFit locking you out again

Do this before anything else. Skipping it undoes every later step at the next
reboot.

Open **Settings → Apps**. Deal with two apps.

**iFit Admin** (`com.ifit.eru`):

1. Tap **Force stop**.
2. Go to **Settings → Apps → Special access → Display over other apps**.
3. Set iFit Admin to **Not allowed**.
4. Go to **Settings → Apps → Special access → Modify system settings**.
5. Set iFit Admin to off.

![iFit Admin app info](screenshots/install-ifit-admin.png)

![Display over other apps](screenshots/install-display-over-other-apps.png)

iFit Admin is a system app and cannot be uninstalled. Force stop plus those two
permissions is as far as it goes.

**iFit** (`com.ifit.standalone`):

1. Tap **Force stop**.
2. Tap **Uninstall**.

![iFit app info](screenshots/install-ifit-app.png)

**Gen 7:**

- **Force stop** on iFit Admin does not grey out. It restarts itself at once.
  That's expected. `unchain.sh` disables it properly in 1.5.
- After a reset there is **no iFit app** (`com.ifit.standalone`) to uninstall.
  Gen 7 downloads it during setup, which you skipped by staying offline.
  Skip that part.
- Gen 7's iFit is split across several system packages (`com.ifit.gandalf`,
  `arda`, `rivendell`, `mithlond`, `eriador`, `glassos_service`). They don't
  show in the app list. Leave them for now.

### 1.4 Enable ADB over Wi-Fi

1. Go to **Settings → About tablet**.
2. Tap **Build number** seven times.

   ![Build number](screenshots/install-build-number.png)

   Tapping Build number seven times is what turns on Developer Mode. After
   about four taps the console starts counting down how many taps are left,
   and confirms that Developer Mode is enabled on the seventh.

3. Go to **Settings → Developer options**.

   ![Developer options](screenshots/install-developer-options.png)

4. Turn on **USB debugging**.

   ![USB debugging](screenshots/install-usb-debugging.png)

5. Pull down the notification panel, long-press **Wi-Fi**, and join your network.
6. Go to **Settings → About tablet** and read the **IP address**.

   ![IP address](screenshots/install-ip-address.png)

On your computer:

```sh
adb connect <console-ip>:5555
adb devices
```

✅ The console lists as `device`, not `unauthorized` or `offline`.

**Gen 7:** if Developer options has **Wireless debugging** and port 5555 is
refused, pair instead. You need platform-tools 30 or later (`adb version`). An
old `adb` is one reason the original Unchained script fails on these consoles.

1. Turn on **Wireless debugging**, tap it, then tap **Pair device with pairing code**.
2. The dialog shows an IP, a *pairing* port and a six-digit code. The main
   Wireless debugging screen shows a different *connect* port. The pairing port
   and code change every time the dialog opens.
3. On your computer:

   ```sh
   adb pair <console-ip>:<pairing-port> <code>
   adb connect <console-ip>:<connect-port>
   adb devices
   ```

Pairing is once per computer. The connect port changes on reboot, so read it
again from the Wireless debugging screen each time.

If the pairing port is hard to read, `adb mdns services` lists both ports
(`_adb-tls-pairing` and `_adb-tls-connect`), so all you need off the screen is
the six-digit code. Gen 7 (Android 13) has Wireless debugging and needs this
pairing. Port 5555 is refused until you pair.

Do not turn USB debugging off afterwards.

### 1.5 Read unchain.sh before running it

[`tools/unchain.sh`](../tools/unchain.sh) does five things in order:

1. Disables `com.ifit.eru` and denies it `SYSTEM_ALERT_WINDOW` and `WRITE_SETTINGS`.
2. Disables the remaining iFit packages.
3. Turns on developer settings and keeps the screen awake.
4. Installs a launcher.
5. Installs STRIDE if you have built it, and sets it as the home app.

Run it with `STRIDE_KIOSK=0` to skip step 5. The script prints the command that
undoes the home-app change.

```sh
./tools/unchain.sh
```

**Gen 7:** pass the connect address, and the script can do the pairing too:

```sh
STRIDE_PAIR=<console-ip>:<pairing-port> STRIDE_PAIR_CODE=<code> \
STRIDE_DEVICE=<console-ip>:<connect-port> STRIDE_KIOSK=0 ./tools/unchain.sh
```

On Gen 7, "Disabling com.ifit.launcher" prints a `SecurityException`.
Privileged mode has already disabled the launcher, and Android won't disable it
twice. It's harmless. The line that matters is
`Package com.ifit.eru new state: disabled-user`.

> The console's USB port is the link to the motor board, so Wi-Fi ADB is
> usually your only way in. Whether it survives a power cycle varies by
> console. Test yours before relying on it. STRIDE itself needs no ADB to run.

### 1.6 Identify the motor board

`console/usbprobe` asks the board what it is. It sends only identity and
read requests, never a write, and nothing it sends can move the belt or deck.
It recognises both a Gen 6 (FitPro 1, USB product `0x0002`) and a Gen 7
(FitPro 2, `0x0003`) board and speaks each one's own protocol. The two are
never crossed.

```sh
cd console/usbprobe
./run.sh
```

Read the result on the console's screen.

✅ It names a board and a device id. This machine reports FitPro, device `0x04`,
incline -3 to +12 %, speed 1.6 to 20 km/h.

If it says the device is claimed, `com.ifit.eru` is running again. Re-run
`tools/unchain.sh`.

**Gen 7:** iFit's motor service (`com.ifit.glassos_service`) holds the board
and restarts itself after a force-stop. `unchain.sh` disables it when it sees
the Gen 7 board. If the probe says the device is claimed, check it's still
disabled:

```sh
adb shell pm disable-user --user 0 com.ifit.glassos_service
```

Leave it disabled: STRIDE needs the board too. `pm enable` hands it back to iFit.

✅ On Gen 7 it shows product info, "*n* features advertised", and live
`SYSTEM_MODE`, `WORKOUT_STATE`, speed and incline values. Pull the safety key
and `SYSTEM_MODE` changes from 1 (IDLE) to 2 (DMK). Serial numbers go to
logcat only, so a photo of the screen is safe to post. The machine above showed
61 features, speed 1.5–20 km/h and incline −6 to +40 %.

When you're done with it, uninstall the probe
(`adb uninstall dev.stride.usbprobe`). It and STRIDE both register for the
board, and Android will otherwise ask which one to open every time.

**[Open an issue](https://github.com/keranm/strideApp/issues) with what it
says,** working or not. A list of known-good boards is the most useful thing
this project could have and it does not exist yet.

### 1.7 Install STRIDE

Two ways in. They end at the same screen, so take whichever suits — but read
the last paragraph before picking, because moving between them later costs you
your settings.

**Gen 7:** Gen 7 support arrived after v0.8. Until a release includes it, use
Option B. The v0.8 APK only looks for the Gen 6 board.

#### Option A — install the released APK

Nothing to build. You need `adb`, which you already have from step 1.4.

Download the APK from
[the latest release](https://github.com/keranm/strideApp/releases/latest), then:

```sh
adb install -r stride-*.apk
adb shell am start -n dev.stride.hud/.MainActivity
```

To check what you downloaded, use the certificate rather than a file hash. The
hash changes every release; the certificate does not. Every build published
here is signed by `CN=STRIDE, O=The Idea Works, C=AU`, SHA-256
`274a6f2e308df50e1e4219f5bd1f4aca5fda8ccd5feccc7c52579c0c8dd302e8`:

```sh
apksigner verify --print-certs stride-*.apk
```

Anything else did not come from here.

It ships with no broker credentials of anyone's — they are compiled out, and
the console is where you set your own in step 2.1.

#### Option B — build it yourself

You need JDK 17 and the Android SDK.

```sh
cd console/stride
./run.sh
```

`run.sh` builds, installs, launches, and tails the log. It finds the Android
SDK via `ANDROID_HOME` or `~/Library/Android/sdk`.

**Read what it prints.** Without a signing key in `local.properties` it builds
a *debug* APK and says so. A debug build is `debuggable`, which lets anything
else on the console read this app's stored settings — broker password
included — and it opens the WebView DevTools socket. That is fine on a bench
and is not what you want on a machine that sits in a hallway for years. To
build the same thing the release is, generate a key and use `./run.sh release`;
the four properties are documented at the top of
`console/stride/app/build.gradle.kts`.

#### Pick one and stay on it

Android identifies an app by its signing key, so a build of yours and the
released APK are different apps as far as the console is concerned. Installing
one over the other fails with a signature mismatch, and the only way across is
`adb uninstall dev.stride.hud` first — which takes your settings, your people
and your workout history with it.

✅ Either way: the console shows the STRIDE welcome screen, and the log prints
`limits: <min>..<max> km/h, <min>..<max> %`.

**Gen 7:**

- The console asks **"Allow STRIDE to access iFIT-LargeX?"** Tick *Always
  open STRIDE…* and tap OK. If the prompt vanishes before you can tap it, the
  board has just re-attached. Close STRIDE and open it again.
- The log also prints `fp2: model '…'` and `fp2: subscribed`.
- **The belt's slowest speed is about 1.5 km/h; there is no slower.** Starting
  a workout ramps to the warm-up speed (Settings → Treadmill). Taking the speed
  down to 0 **pauses the belt** while the workout carries on, and raising it
  again restarts from 1.5. STOP and the safety key work as on Gen 6.
- The fan tile is not connected to the fan on Gen 7 yet.
- **Powering on:** run `unchain.sh` with `STRIDE_KIOSK=0`. It grants STRIDE
  *display over other apps*, which is what Android 13 needs before it lets
  STRIDE open itself at boot. The Android launcher stays the home screen, so
  other apps stay reachable. Pin STRIDE to the dock (long-press it in the app
  list and drag it down) for one-tap access. Give the console a minute after
  changes like these before switching off at the wall.
- Tested first with the belt empty (start, speed, zero, pause, resume, STOP,
  incline, the safety key, the watchdog), then walked on. Do the empty-belt run
  yourself before stepping on.

### 1.8 Set up STRIDE

Everything is on the treadmill's own screen. There are no config files.

- **Settings → Who walks.** Add yourself. Nobody is pre-configured.
- **Settings → Treadmill.** Units, warm-up, cool-down, guided-walk speed limit.
- **Settings → Interface.** Pick one of five. *Original* is the most tested.

✅ Clip the safety key, choose a workout, and check the belt starts and the
distance climbs.

**Stop here if you do not use Home Assistant.**

---

## Part 2. Home Assistant

### 2.1 Connect the treadmill

Requires the [MQTT integration](https://www.home-assistant.io/integrations/mqtt/)
and a broker. Nothing from this repository is installed for this step.

1. On the console, go to **Settings → Home Assistant**.
2. Enter broker host, port, username, password.
3. Tap **Test**.

✅ A **Treadmill** device appears under Settings → Devices with speed, incline,
distance, elapsed, pulse, calories, mode and workout. A second device appears
per person the first time they walk.

Build whatever dashboard you like from those entities. There is no custom
component to install.

### 2.2 Optional: weekly and monthly totals

The console's distance counter resets every walk, so totals accumulate in Home
Assistant.

1. Copy [`homeassistant/packages/stride.yaml`](../homeassistant/packages/stride.yaml)
   into your config as `packages/stride.yaml`.
2. Add to `configuration.yaml`:

   ```yaml
   homeassistant:
     packages: !include_dir_named packages
   ```

3. Restart Home Assistant.

✅ `sensor.treadmill_distance_weekly` exists and is not `unavailable`.

If it is unavailable, your treadmill device is named something other than
`Treadmill`. Set `treadmill_prefix` in `stride.conf` and the `source:` lines in
the package to match.

> **Gap:** this file is valid YAML and mirrors a working configuration, but has
> not been installed on a clean Home Assistant.

### 2.3 Optional: the coach and reminders

```sh
cd homeassistant
cp stride.conf.example stride.conf
```

1. Open `stride.conf`. Every key is documented in the file. Leave a key blank to
   skip that subject.
2. Set `coach_ai_task`. Find your AI entity under Developer Tools → States,
   prefix `ai_task.`.
3. Run what you want:

   ```sh
   python3 stride_coach_llm.py      # coach, and the automations that fire it
   python3 stride_reminders.py      # weigh-in and blood-pressure reminders
   python3 stride_persons.py        # publishes your HA people to the console
   ```

✅ `script.stride_coach` exists. Run it with `kind: morning` and a `task`, and a
message appears on `sensor.stride_coach`.

After `stride_persons.py`, the console's **Settings → Who walks** lists your
Home Assistant people under *From Home Assistant*.

---

## Part 3. Apple Health (iPhone)

Optional. Nothing else depends on it.

Use **[AH for HA](https://github.com/keranm/ah-for-ha)**, which is a separate
project with its own guide. There is nothing in this repository to install for
it.

AH for HA also publishes outdoor walks as gradient profiles over MQTT, which
STRIDE replays on the deck.

> `homeassistant/stride_health_webhook.py` is frozen. Setting this up for the
> first time? You do not need it.

---

## Windows

Nothing on the console side cares what the computer runs.

1. Download [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)
   for Windows, unzip it, and add the folder to your `PATH`. Check with
   `adb version` in a new terminal.
2. Install [Git for Windows](https://git-scm.com/download/win) and run every
   `.sh` script from **Git Bash**. The plain `adb` commands in this guide work
   in PowerShell too.
3. To install STRIDE, use the released APK ([1.7 option A](#option-a--install-the-released-apk)).
   No build is needed.
4. `console/usbprobe` is only shipped as source, so step 1.6 needs a build:
   install Android Studio (which brings the SDK, found automatically under
   `%LOCALAPPDATA%\Android\Sdk`) and a JDK 17, then
   `export JAVA_HOME="/c/Program Files/<your JDK 17 folder>"` before `./run.sh`.

The first time `adb` runs, Windows Firewall may ask about it. Allow it on
private networks.

> **Gap:** written for Windows but not yet followed end to end on it.

---

## If it goes wrong

1. Pull the safety key.
2. Switch the treadmill off at the wall.
3. To restore the manufacturer's software, factory reset from the console's own
   recovery.

---

## Credit

Steps 1.1 to 1.4 follow a community *NordicTrack Gen 6 modding guide*. That
guide heads towards QZ Companion. STRIDE goes a different way after 1.4.

If you know who wrote it, [tell us](https://github.com/keranm/strideApp/issues).

---

## Testing this guide

Most likely to be wrong:

- **Steps 1.1 to 1.4.** Written up after the fact. Nobody has followed this page
  on a freshly reset machine.
- **Step 2.2.** Never installed on a clean Home Assistant.
- **Every ✅ check.** If one does not happen, that is the bug.
- **Anything you had to work out that is not written down.**

Open an issue. "Stuck at 1.1 on a 2019 Commercial 1750" is useful.
