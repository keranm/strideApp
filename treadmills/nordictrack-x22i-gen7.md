# NordicTrack X22i (Gen 7)

The first Gen 7, and the first incline trainer, to run STRIDE. Everything in
[gen7.md](gen7.md) and the [FitPro 2 notes](../protocol/FITPRO2_PROTOCOL.md)
was found on this machine.

## The machine

| | |
|---|---|
| Model | NordicTrack X22i. The board reports `ETNT39225-INT` |
| Type | Incline trainer (iFit: `INCLINE_TRAINER`) |
| Generation | Gen 7 |
| Embedded type | CLASSIC |
| System version | `VKX1_20240619` |
| Android | 13 |
| Console hardware | MediaTek MT8390, "Xenon" |
| iFit / GlassOS | 2026.08.25 builds (Admin, GlassOS, launcher, workout player alike) |
| Board firmware | 1.19.2e177c9e6 |
| Part numbers | software 455915, hardware 455914 |
| Speed range | 1.5 to 20 km/h (the board reports 1.499) |
| Incline range | −6 to +40 % |

## The board

USB `0x213C`/`0x0003`, product "iFIT-LargeX", version 3.03, vendor class 255,
interrupt endpoints IN `0x81` / OUT `0x01`, 64 bytes.
[FitPro 2](../protocol/FITPRO2_PROTOCOL.md). It advertises 61 features.

## How far it got

Tested 11/10/2026 from a Windows PC: first everything below with the belt
empty and a hand on the safety key, then walked on.

- [x] Factory reset
- [x] Privileged mode: the challenge code appeared on the 25/08/2026 iFit builds
- [x] iFit Admin disabled
- [x] ADB: Wireless debugging pairing
- [x] `unchain.sh`
- [x] usbprobe (FitPro 2 path)
- [x] STRIDE connects and shows live values
- [x] Incline moves
- [x] Belt starts, changes speed, goes to zero and back, pauses, resumes, stops
- [x] Safety key seen
- [x] App force-closed mid-run: belt stopped at once
- [x] App stalled mid-run: watchdog stopped the belt in 3.5 s
- [x] Powers on into STRIDE, with the Android launcher kept
- [x] Walked on it

## Quirks

- The console's Wi‑Fi drops packets, and the ADB connect port moved twice in
  one evening. The pairing survived each time.
- Reinstalling STRIDE resets its USB permission; the prompt comes back.
- Prime Video and Spotify are preinstalled and still work alongside STRIDE.

## Tested by

The owner, with Claude Code, 11/10/2026, branch `gen7-fitpro2`.
