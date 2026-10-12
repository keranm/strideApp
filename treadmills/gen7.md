# Gen 7 consoles

STRIDE drives a Gen 7 as of October 2026, on one machine, the
[NordicTrack X22i](nordictrack-x22i-gen7.md). It was tested with the belt empty
first and has since been walked on. Treat it as new.

## At a glance

| | |
|---|---|
| Android | 13 |
| System version prefix | `VKX1_` |
| Console hardware | MediaTek MT8390, product "Xenon" |
| Motor board | "iFIT-LargeX", USB `0x213C`/`0x0003`, vendor class 255 (not HID) |
| Protocol | [FitPro 2](../protocol/FITPRO2_PROTOCOL.md): no checksum, float32 values, pushed rather than polled |
| Board security | None. No `VerifySecurity` equivalent |
| ADB | Wireless debugging: pair once with a code, then a connect port that changes on reboot |
| What holds the board | `com.ifit.glassos_service`, which restarts itself after a force-stop |
| iFit packages | Split across `com.ifit.eru`, `gandalf`, `arda`, `rivendell`, `mithlond`, `eriador`, `glassos_service`, `launcher`, and `com.ergatta.bolt.ifit` |
| Preinstalled | Amazon Prime Video and Spotify |

## The route in, and where it differs from Gen 6

Everything is in the install guide, marked **Gen 7**. The differences:

1. **You can't sign out of iFit offline.** It shows "no internet connection".
   Reset with the pinhole instead (reported on the left side of the enclosure).
2. **Privileged mode still works** on iFit builds from 25/08/2026. The 10
   taps, 7 s, 10 taps sequence gives a challenge code. It opens iFit's own
   maintenance menu, not Android's apps screen. Don't tap *check for updates*.
3. **Force stop on iFit Admin never greys out**; it restarts. There's no iFit
   app to uninstall after a reset. `unchain.sh` disables iFit Admin properly.
4. **ADB is Wireless debugging.** `adb mdns services` finds the pairing port,
   so only the six-digit code has to be read off the screen. A flaky Wi‑Fi
   link moves the connect port; pairing survives.
5. **`unchain.sh` disables `glassos_service`** when it sees the Gen 7 board,
   because otherwise STRIDE can't claim it.
6. **Android 13 blocks an app opening itself at boot.** `unchain.sh` grants
   STRIDE *display over other apps*, which lifts that, and the Android launcher
   stays the home app. A home-screen alias, as used on Gen 6, does not survive
   a power cycle: the system starts the launcher itself.
7. **The USB prompt can vanish** if the board re-attaches while it is open.
   Close STRIDE and open it again, then tick *Always*.

## How the board behaves

From the hardware, belt empty. Details in the [protocol notes](../protocol/FITPRO2_PROTOCOL.md#driving-it-what-the-board-actually-does).

- There is **no speed below ~1.5 km/h** and no speed 0. To stop, the workout
  state goes to PAUSED, which does stop the belt.
- An **idle board refuses any target speed**. Start with RUNNING, then set the
  speed.
- **The board keeps its last target through a pause.** RUNNING alone would
  bring the belt back at the old pace.
- **The belt-speed reading freezes** at its last value instead of reading 0.
- **Errors repeat** with everything else, every ~1.5 s.
- **If the console app dies, the belt stops at once.** If it hangs while
  holding the board, the belt runs on. STRIDE has a watchdog for that, which
  releases the board after 3 s.
- **The board moves itself PAUSED → RESULTS after 10 minutes.**
- **The safety key** reads as `SYSTEM_MODE` 2, and the contact bounces for a
  few seconds on the way back in.

## What STRIDE does differently on Gen 7

- Takes the speed to zero mid-workout as a pause, and raising it again as a
  restart from the minimum.
- Treats a frozen speed reading outside a running state as a stopped belt.
- Gives a commanded stop 6 s to land before calling a coasting belt a
  runaway.
- The watchdog above.
- The fan tile is not wired to the fan yet.

FitPro 1 behaviour is unchanged. The two protocols are kept apart by USB
product id, and a FitPro 1 frame is refused outright on a Gen 7 board: its
length byte would be read as a message type, and a 9-byte one means
BOOTLOADER.

## Not known yet

- What the board's `HEART_BEAT_INTERVAL` (161) does. It may be how the board
  protects against a fully frozen console.
- The fan's encoding, and the calorie unit.
- Whether other Gen 7 models use the same board.
