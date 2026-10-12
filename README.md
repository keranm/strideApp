# STRIDE

**Replace the iFit software on a NordicTrack treadmill. Keep the belt, lose the subscription.**

**Please note**: This is a hobby project by one person and Claude Code, please don't get upset at that. Claude Code is enabling me to be able to do something I've dreamed of doing for a few years but didn't have the ability. Now I'm able to walk/run on my own physical hardware that I bought without it having someone else's subscription software on it. If you don't like that 90% of this was coded with AI tools like Claude Code - then this isn't the project for you. If you want to fork this, put your fingerprints all over it ... please do, I'd welcome that!

> ### 🚧 Early days and factory reset has saved me more than once
>
> It works, and I walk on it most days, but it works on *my* machine. Nobody
> else has installed it yet and I've no idea how it behaves on other hardware.
>
> Nothing here is supported, so use it at your own risk. If it goes wrong a
> factory reset puts the original software back, and I've done that plenty of
> times while building this.
>
> Read the code, take the [protocol notes](protocol/FITPRO_PROTOCOL.md), and
> tell me what board your treadmill has.

> ⚠️ **This drives a motorised treadmill.** Read [SAFETY.md](SAFETY.md) before
> you install anything. Your physical safety key is still the stop that matters
> and it cuts the motor directly. STRIDE drives the incline, but speed is only
> ever suggested. You decide when to go faster.

> **Gen 7.** The jailbreak works, and STRIDE drives the Gen 7's motor board,
> which speaks a different protocol, [FitPro 2](protocol/FITPRO2_PROTOCOL.md).
> It runs on a Gen 7 NordicTrack X22i with iFit builds from August 2026. It was
> tested with the belt empty first, and has since been walked on. The steps are
> in [INSTALL.md](docs/INSTALL.md), marked Gen 7. One machine so far.


I got tired of iFit on my NordicTrack. Manual mode, a 400 m loop, and an
interface that kept asking me to subscribe. The console turns out to be an
Android tablet talking to a separate motor board, so I replaced what runs on
the tablet and left the motor board alone.

The belt and the deck now work with no login, no subscription and no internet.
If you want it, everything the machine knows turns up in Home Assistant as
ordinary entities. I built it with Claude Code alongside me, and there's a
[full write-up of how the jailbreak works](https://theidea.works/blog/jailbreak-nordictrack-treadmill/)
if you'd rather read the story than the steps.

**[v0.8](https://github.com/keranm/strideApp/releases/latest)**, in daily use,
and it now pairs with Zwift over Bluetooth FTMS.

![STRIDE on a NordicTrack console](docs/screenshots/oval-original.png)

**[More screenshots →](docs/GALLERY.md)**

---

## What's in here

| | |
|---|---|
| **`console/`** | The Android app that replaces iFit on the treadmill's own screen. Talks to the motor board over USB, drives the incline, runs guided walks, and gives you five completely different interfaces to pick from. |
| **`homeassistant/`** | Optional scripts for the coach, the reminders and a health dashboard. The treadmill shows up in Home Assistant without any of them. |
| **`protocol/`** | The [FitPro serial protocol](protocol/FITPRO_PROTOCOL.md), decoded and written down, and its Gen 7 successor [FitPro 2](protocol/FITPRO2_PROTOCOL.md). If you're working on a different machine, start here. |

There used to be an `ios/` folder. That's now its own thing,
**[AH for HA](https://github.com/keranm/ah-for-ha)**, which pushes Apple Health
into Home Assistant and has no treadmill in it at all. It grew to 39 metrics,
rings, sleep stages and workouts, and most people who want that will never own
a treadmill.

STRIDE kept the half it cares about. An outdoor walk can still become a
gradient profile the deck replays, because that arrives over MQTT and the
console has never cared who published it.

---

## Home Assistant needs nothing installed

Put your broker details into Settings → Home Assistant on the treadmill and a
**Treadmill** device shows up, with speed, incline, distance, elapsed, pulse,
calories, mode and workout, plus a device per person as they walk. The console
publishes its own MQTT discovery, so there's no integration, no YAML and no
custom component.

Build whatever dashboard you like from it. There's no STRIDE dashboard you have
to accept.

The `homeassistant/` scripts are for the extras: the coach, the reminders, and
an opinionated health dashboard if you want a starting point.

---

## Will it work on my treadmill?

Honestly, maybe. It's proven on one machine, mine:

| | |
|---|---|
| Model | NordicTrack C 1750 |
| Generation | Gen 6, the embedded "CLASSIC" console |
| Motor board | FitPro, device id `0x04` |
| Reported ranges | incline −3 to +12 %, speed 1.6 to 20 km/h |

Other Gen 6 NordicTrack and ProForm consoles on the same board are probably
fine, because the protocol looks like a family rather than a single model, but
nobody has tried.

A second machine, a **Gen 7** NordicTrack X22i incline trainer (Android 13,
FitPro 2 board, incline −6 to +40 %, speed 1.5 to 20 km/h), is walked on with
STRIDE driving its belt and deck.

**[treadmills/](treadmills/README.md)** has a page per generation and per
machine: board, protocol, quirks, and exactly what was tested. Add yours. You also need a console you can reach with `adb`, and that's
the part that varies most by model and year.

[`console/usbprobe`](console/usbprobe) asks your board what it is and changes
nothing. It's an app you install on the console, so it needs ADB and the iFit
software out of the way first, which makes it step 6 below rather than
something you can run today.

**Please open an issue with whatever it tells you,** working or not. A list of
known-good boards would be the most useful thing this project could have and it
doesn't exist yet. I'm not buying more treadmills to build one.

---

## Installing

**[docs/INSTALL.md](docs/INSTALL.md)** has the full step by step, but read the
banner at the top of this page first. Nobody who didn't already know the
answers has followed that guide yet, and it marks its own weak spots.

In outline:

1. **Factory reset the console.** Stay offline afterwards.
2. **Enable privileged mode.** Tap ten times, wait seven seconds, tap ten more,
   then answer the challenge code the console gives you.
3. **Force stop iFit Admin** (`com.ifit.eru`) and take away its two
   permissions, then force stop and uninstall iFit (`com.ifit.standalone`).
4. **Enable ADB over Wi-Fi.** Tap Build number seven times, turn on USB
   debugging, join your network.
5. **Run [`tools/unchain.sh`](tools/unchain.sh).** Disables the iFit packages
   and installs the launcher.
6. **Identify your board** with [`console/usbprobe`](console/usbprobe), which
   reads and changes nothing. Report what it says.
7. **Install the console app**, then set it up on its own screen. Either grab
   the signed APK from [the latest release](https://github.com/keranm/strideApp/releases/latest)
   and `adb install -r` it, or build your own with `console/stride/run.sh`.
   Pick one and stay on it — they're signed with different keys, so swapping
   later means uninstalling and losing your settings.
8. **Optional:** enter broker details on the console to get the treadmill into
   Home Assistant, then add the coach.

Stop after step 7 and you've got a treadmill with no subscription. Steps 1 to 7
need nothing installed on the Home Assistant side.

If you'd rather not drive it by hand, point Claude Code at this repo, give
it the treadmill's IP address, and ask it to follow the install guide over ADB.
That's how most of my own installs have gone.

---

## Configuration

**The console configures itself.** Broker details, who walks, units, warm-up,
coach, heart rate, display: all of it lives in Settings on the treadmill's own
screen. No config files, no rebuild.

**The Home Assistant side reads one file:**

```sh
cp homeassistant/stride.conf.example homeassistant/stride.conf
```

Every key is documented in the example. Leave anything blank and that subject
is simply absent, so no scale, no blood-pressure cuff, no phone. The coach says
nothing about what it wasn't given rather than guessing at it.

`coach_ai_task` has no default, because which AI entity you have depends on
which integration you set up.

**For weekly and monthly totals**, copy
[`homeassistant/packages/stride.yaml`](homeassistant/packages/stride.yaml) into
your Home Assistant config. The console's distance counter resets every walk,
so the accumulating has to happen in HA. That file also creates the helpers the
coach and reminders need.

---

## How it's put together

A few decisions that will save you reading the code to find them.

- **Kotlin owns the treadmill, HTML owns the screen.** The five interfaces are
  HTML in a WebView, so you can design them in a browser. Tap SPEED+ and the
  belt speeds up, but the request goes through Kotlin first, which clamps it to
  whatever the board says it can do. A bad layout can't outrun the machine.
- **The coach decides when to speak, Home Assistant decides what it says.**
  With HA unreachable it still marks your kilometres, using a set of lines
  built into the console.
- **Never hand over a number without its age.** Health metrics that reset at
  midnight are withheld entirely if they haven't changed since midnight, rather
  than being passed on as today's. This one came from a real incident: the
  sensors hold yesterday's closing total and look exactly like today's running
  total, so the coach quoted yesterday's numbers back at me as though they were
  fresh.
- **Coaching and recording are per person, not per console.** Guests get
  neither. They're summarised at the end of a walk and then forgotten.

---

## Fork it

Please do. STRIDE is [Apache-2.0](LICENSE), so you can change it, ship it, sell
it and build something else out of it without asking me. There's no CLA and you
keep the copyright on whatever you write.

The thing I'd most like back is **what board your treadmill has**, working or
not. That list is one machine long today and it can only grow from other
people's treadmills. There's an
[issue template](https://github.com/keranm/strideApp/issues/new/choose) for it.

[CONTRIBUTING.md](CONTRIBUTING.md) covers getting set up, where things live,
how to redesign an interface in a browser without touching a treadmill, and the
rules I'd ask you to keep if you're changing anything that moves the belt.

---

## Thanks

**[nordictrack-ftms-bridge](https://github.com/ciarancoffey/nordictrack-ftms-bridge)**
by @[ciarancoffey](https://github.com/ciarancoffey). A couple of folks on reddit asked whether STRIDE could speak FTMS, which is the
standard Bluetooth service Zwift and friends use to talk to a treadmill, and
I took a look at Ciaran's project. This showed that YES an iFit Android console can be a Bluetooth LE *peripheral*, not just a
central. Using that as inspiration I coded up a working FTMS implementation

That project is AGPL-3.0 and STRIDE is Apache-2.0, so no code moves between
them. FTMS is a published Bluetooth SIG specification, and anything built here
gets built from the spec.

The install guide's [own credit](docs/INSTALL.md#credit) covers the community
Gen 6 modding guide that steps 1.1 to 1.4 follow.

---

## Licence

[Apache-2.0](LICENSE). No warranty, and see [SAFETY.md](SAFETY.md).

STRIDE is an independent project, built for compatibility with hardware its
authors own. Not affiliated with or endorsed by iFIT, ICON Health & Fitness,
NordicTrack, ProForm, Apple or Home Assistant. Trademarks belong to their
owners and are used here only to say what this works with.

This repository contains no code from any of those companies.
