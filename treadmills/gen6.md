# Gen 6 consoles

The generation STRIDE was built on. One machine is in daily use:
[NordicTrack C 1750](nordictrack-c1750-gen6.md).

## At a glance

| | |
|---|---|
| Android | 9 |
| System version prefix | `EKA2_` |
| Motor board | FitPro, USB `0x213C`/`0x0002`, HID class |
| Protocol | [FitPro 1](../protocol/FITPRO_PROTOCOL.md): request/response, checksummed, polled |
| Board security | Yes. `VerifySecurity`, re-asked by the board on its own schedule |
| ADB | Classic ADB over Wi‑Fi on port 5555 |
| What holds the board | `com.ifit.eru` (iFit Admin) |

## The route in

The install guide's Part 1 as written: pinhole reset, privileged mode (10
taps, 7 s, 10 taps, challenge code), force-stop iFit Admin and take away its
two permissions, uninstall iFit, USB debugging, then `tools/unchain.sh`.

## Quirks worth knowing

- `ActualKph` reads 0.00 at every speed. Belt speed is estimated from the
  odometer.
- The belt ignores a speed write while the console is IDLE; incline moves in
  any state.
- The board has its own sleep (`WorkoutMode` 12) and is woken by writing IDLE.
- A whole frame is rejected if any one field in it is refused, so mode
  changes travel alone.

Most of these are documented where they matter, in
[`FitPro.kt`](../console/stride/app/src/main/java/dev/stride/hud/FitPro.kt) and
the protocol notes.

## Moving to Gen 7

The one Gen 7 machine seen so far has a different Android, a different motor
board and a different protocol. See [gen7.md](gen7.md). Whether an iFit
update can move a Gen 6 console onto any of that is unknown. If you are on
Gen 6 and STRIDE works, declining iFit updates costs nothing.
