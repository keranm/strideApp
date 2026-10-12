# The FitPro 2 protocol (Gen 7)

An independent interoperability description of the USB protocol spoken by the
motor control board in Gen 7 NordicTrack consoles. iFit's software calls it
"FitPro 2". It is **not** [FitPro 1](FITPRO_PROTOCOL.md) on a new product id.
The framing, the encoding and the command set are all different.

> Independent interoperability work, for compatibility with hardware the authors
> own. Assembled from USB traffic on a real Gen 7 board and from a static reading
> of the publicly distributed console software (`com.ifit.glassos_service`
> 2026.08.25). Described in our own words. Nothing here is copied from anyone's
> documentation or source.
>
> Not affiliated with or endorsed by iFIT Health & Fitness, ICON Health &
> Fitness, NordicTrack or ProForm.

Each claim is marked one of:

- **HARDWARE**: seen on a real board.
- **CODE**: read from the console software but not yet seen on a board.
- **INFERRED**: a reasonable guess. Treat it as a hypothesis.

**Status, October 2026:** STRIDE drives FitPro 2 on one Gen 7 board. It was
tested first **with the belt empty** (start, speed up and down, speed to zero
and back, incline, pause, resume, STOP, end, the safety key, the app dying
and the app stalling), and since then **walked on**. See
[Seen on hardware](#seen-on-hardware) and [Driving it](#driving-it-what-the-board-actually-does).

---

## ⚠️ Read this first: never send FitPro 1 frames to this board

A FitPro 1 frame's **length** byte lands in FitPro 2's **type** slot. Any
FitPro 1 frame of length 9 (`02 09 …`) would be read as **`BOOTLOADER`**.
Lengths 1, 2, 7 and 14 would become SUBSCRIBE, WRITE, UNSUBSCRIBE and EXTENDED.
Choose the protocol by product id and never fall back from one to the other.

---

## Transport (HARDWARE)

| | |
|---|---|
| VID / PID | `0x213C` / **`0x0003`**, product string "iFIT-LargeX" |
| Interface | 0, **class `0xFF` (vendor-specific)**, not HID. There is no HID report descriptor |
| IN endpoint | `0x81`, interrupt, 64 bytes, interval 1 |
| OUT endpoint | **`0x01`**, interrupt, 64 bytes, interval 1 |
| Access | An ordinary app can claim it through `UsbManager`, with no root needed. iFit's `com.ifit.glassos_service` holds it while it runs, and it restarts itself after a force-stop. Disable it to free the board |

The console software also accepts PIDs 4 and 6 as FitPro 2 variants (an FTDI
bridge and "Stannite") (CODE). Only PID 3 has been seen.

**One USB packet carries one message** (CODE). There is no reassembly on this
channel. Long data, such as the feature list, arrives as several complete
messages and ends with an empty one (HARDWARE). Writes are the length of the
frame and are not padded to 64 bytes (CODE).

**The board waits to be spoken to.** Nothing arrived before the first request
(HARDWARE).

---

## Frame format

```
[0]   channel       0x02 = FitPro 2
[1]   source|type   high nibble = sender, low nibble = message type
[2]   payload length
[3…]  payload
```

- **No checksum** and **no sequence number** (CODE, and consistent with
  HARDWARE).
- **Feature ids** are UInt16 little-endian. **Values** are always IEEE-754
  float32 little-endian, whatever their meaning, so ints, bools and enums are
  sent as floats (CODE).
- Sender nibble: `0x0_` app (us), `0x1_` the board, `0x2_` / `0x3_` other
  controllers (CODE; `0x1_` is HARDWARE).
- iFit's own requests carry **one extra trailing `0x00`** after the payload,
  except `SUPPORTED_FEATURES`. Nothing is ever written into that byte, so it
  isn't a checksum. Copying it is the cautious choice (CODE). The board also
  tolerated frames with stray extra bytes (HARDWARE).

### Channels: byte 0 (CODE)

| Byte 0 | Channel | |
|---|---|---|
| `0x01` | File system | **never send** |
| **`0x02`** | **FitPro 2** | the only one STRIDE needs |
| `0x03` | ANT+ | |
| `0x05` | Memfault (crash reporting) | |
| `0x06` | **Firmware update** | **never send**. Its "hello" opens the update protocol |

---

## Message types

### Requests (app → board), low nibble of byte 1 (CODE)

| Type | Name | Payload |
|---|---|---|
| `0x1` | `SUBSCRIBE` | n × UInt16 feature id (iFit sends batches of 8) |
| `0x2` | `WRITE` | UInt16 feature id + float32 value (6 bytes) |
| `0x6` | `SUPPORTED_FEATURES` | none |
| `0x7` | `UNSUBSCRIBE` | n × UInt16 feature id |
| `0x9` | ⚠️ `BOOTLOADER` | **never send** |
| `0xE` | `EXTENDED` | 1 byte info type + data. Info type 2 = product info |

Every other type number is undefined. The board rejects one with error 6
(HARDWARE).

### Responses (board → app)

| Byte 1 | Name | Payload | |
|---|---|---|---|
| `0x11` | `FEATURES` | n × UInt16 feature id, **empty = end of list** | HARDWARE |
| `0x13` | `ACKNOWLEDGE` | the source/type byte being acknowledged | HARDWARE |
| `0x14` | `ERROR` | `[request byte] [error code] [details…]` | HARDWARE |
| `0x15` | `EVENT` | UInt16 feature id + float32 value | HARDWARE |
| `0x1E` | `EXTENDED` | `[info type] [data…]` | HARDWARE |

### Error codes (CODE; code 6 HARDWARE)

| Code | Meaning | Details |
|---|---|---|
| 2 | framing | |
| 3 | features not supported | feature ids |
| 4 | write not supported | feature id |
| 5 | data out of range | feature id + rejected float |
| **6** | **request not supported** | none |
| 7 | write value not allowed | feature id (+ float) |

Example (HARDWARE): `02 14 02 04 06` means "error: request byte `0x04` (app,
type 4) is not a request, code 6".

---

## Session start-up

iFit's main start-up routine didn't decompile cleanly, so iFit's own **order** is
INFERRED from a sibling device that uses the same stack. This order works
(HARDWARE):

1. **Product info.** Send `02 0E 01 02 00`. The board answers with
   `EXTENDED` frames, `02 1E <len> 02 <item> <ASCII…>`, ending with
   `02 1E 02 02 00` (item 0) (HARDWARE). Items: 1 software version,
   2 software part number, 3 hardware part number, 4 model, 5 serial,
   6 display type, 9 motor controller version, 13 product serial, 20 motor
   controller type. The board seen sent 1–6 (CODE for the rest).
2. **Supported features.** Send `02 06 00`. The board answers with `02 11 …`
   chunks of up to 8 ids and then an empty `02 11 00`, **once** (HARDWARE). It
   repeated the list endlessly only after a malformed request, a FitPro 1
   frame of length 6.
3. **Subscribe** to what you need, 8 ids per request. The board acknowledges
   (`02 13 01 01`), then sends one `EVENT` per id holding its current value,
   all within a few milliseconds (HARDWARE). After that it pushes `EVENT`s
   without being asked, and there is no polling (CODE).
4. iFit then writes `HEART_BEAT_INTERVAL` (161) = 720 once (CODE). The unit,
   and what the board does without it, are **unknown**.

**No security or unlock step.** FitPro 1's `VerifySecurity` has no FitPro 2
equivalent (CODE). The only challenge/response is ANT+ radio licensing, which
happens only if the board advertises feature 170. The board seen so far
doesn't.

A write counts as accepted when `ACKNOWLEDGE` or an `EVENT` for the same id
comes back. It is refused on `ERROR` (CODE). iFit waits up to 15 s and retries
3 times over USB (CODE).

---

## Feature ids

The board lists its own ids in step 2. The Gen 7 incline trainer seen so far
(HARDWARE) advertised these 61. Names are iFit's (CODE). "RW" is INFERRED from
a read-only flag in iFit's table. The board's own `WRITE_NOT_SUPPORTED` reply is
the authority.

| Id | Hex | Name | Notes | RW |
|---|---|---|---|---|
| 10 | `000A` | `DEVICE_TYPE` | 4 treadmill, 5 incline trainer | R |
| **102** | `0066` | **`SYSTEM_MODE`** | 0 boot, 1 idle, **2 DMK = safety key out**, 3 workout, 4 maintenance, 5 error | **never write** |
| 103 | `0067` | `IDLE_SYSTEM_MODE_LOCK` | bool, a lock-out flag | avoid |
| 105 / 106 | `0069` / `006A` | `USER_WEIGHT_KG` / `MAX_USER_WEIGHT_KG` | kg | W / R |
| 109 | `006D` | `KEY_COOKED` | physical keys: 1 STOP, 2 START, 3/4 speed ±, 5/6 incline ± … | R |
| 111 | `006F` | `VOLUME_PERCENT` | | W |
| 113 / 114 | `0071` / `0072` | total in-use time / time since start-up | s | R |
| 129 | `0081` | `FAN_STATE` | enum | W |
| 140 | `008C` | `DISPLAY_UNITS` | bool, polarity unknown | W |
| 150 | `0096` | `KEY_ARRAY_1` | | R |
| 161 | `00A1` | `HEART_BEAT_INTERVAL` | iFit writes 720. Unit unknown | avoid |
| 202–205 | `00CA`–`00CD` | current / warm-up / running / cool-down calories | kcal? | R |
| 222 | `00DE` | `PULSE` | bpm | R (W?) |
| 223 | `00DF` | `PULSE_SOURCE` | 0 none, 1 hand … 6 BLE | R |
| 227 / 228 | `00E3` / `00E4` | average / max pulse | bpm | R |
| 252–256 | `00FC`–`0100` | distance (current, warm-up, running, cool-down, machine total) | **units unknown** | R |
| 265 / 266 | `0109` / `010A` | belt total hours / metres | | R |
| 267 | `010B` | `BELT_REPLACED` | | **never write** |
| **301** | `012D` | **`TARGET_KPH`** | km/h | **W** |
| **302** | `012E` | **`CURRENT_KPH`** | km/h | R |
| 303 / 304 | `012F` / `0130` | `MIN_KPH` / `MAX_KPH` | km/h | R |
| 307 / 308 | `0133` / `0134` | average / workout max km/h | | R |
| **401** | `0191` | **`TARGET_GRADE_PERCENT`** | % | **W** |
| **402** | `0192` | **`CURRENT_GRADE_PERCENT`** | % | R |
| 403 / 404 | `0193` / `0194` | `MIN_GRADE_PERCENT` / `MAX_GRADE_PERCENT` | % | R |
| 407 / 408 | `0197` / `0198` | average / workout max grade | % | R |
| 411 / 412 | `019B` / `019C` | vertical metres net / gain | m | R |
| 415 | `019F` | `CALIBRATE_GRADE` | runs the lift through calibration | **never write** |
| 416 | `01A0` | `GRADE_FEEDBACK_RANGE` | | R |
| **602** | `025A` | **`WORKOUT_STATE`** | 0 none, 1 ready to start, 2 warm-up, **3 running**, 4 cool-down, **5 paused**, 6 results, 7 off machine | **W** |
| 603–605 | `025B`–`025D` | warm-up / **running (elapsed)** / cool-down time | s | R |
| 610 | `0262` | `GOAL_TIME` | s | W |
| 615–625 | `0267`–`0271` | warm-up / cool-down / results / pause timeouts and count-downs | s? | W? |
| 2108 | `083C` | `CONTROLLER_ERROR` | | R |
| 2132 / 2133 | `0854` / `0855` | controller comms success / retry rate | | R |

### Doing things

| To | |
|---|---|
| Read speed, incline | subscribe to 302, 402 |
| Know the limits | subscribe to 303, 304, 403, 404 |
| Detect the safety key | `SYSTEM_MODE` (102) = 2 (HARDWARE). Debounce it: it bounces while the key is clipped in |
| See physical keys | `KEY_COOKED` (109) |
| Set target speed / incline | write 301 / 401 |
| Start, pause, end | write `WORKOUT_STATE` (602): 3 running, 5 paused, 6 results, 0 none. The board reports 1 when the user presses Start (CODE) |

**Not yet known:** whether the board stops the belt when the host goes quiet,
the calories unit, and the fan's encoding. Speed and incline are km/h and
percent, distance is metres, and `PAUSED` stops the belt (all HARDWARE). iFit seems to refuse target speeds below 0.5 km/h rather than stop by
writing 0 (INFERRED, from a garbled part of the decompile).

---

## Worked examples

No checksum anywhere. The trailing `00` is iFit's padding.

**Product info:** `02 0E 01 02 00`

**Supported features:** `02 06 00`

**Subscribe to current speed and current incline:**

```
02 01 04 2E 01 92 01 00
      │  └───┘ └───┘
      │  302   402
      └ 4 payload bytes
```

Replies: `02 13 01 01` (ACK), then one `EVENT` per id. For example,
`02 15 06 2E 01 00 00 A0 40` is `CURRENT_KPH` = 5.0 (float `0x40A00000`)
(HARDWARE for the format).

**Set target incline to 2.0 %:** (incline writes HARDWARE; this exact value illustrative)

```
02 02 06 91 01 00 00 00 40 00
      │  └───┘ └─────────┘
      │  401   2.0f
      └ 6 payload bytes
```

Expect `02 13 01 02` (ACK) and/or an `EVENT` for 401. A value out of range comes
back as `02 14 08 02 05 91 01 <float>`.

---

## Never send

So that a protocol layer can refuse these outright:

1. **Any FitPro 1 frame.** See the top of this page.
2. **Any byte 0 other than `0x02`**, in particular `0x01` (file system) and
   `0x06` (firmware update).
3. **Request type `0x9` (`BOOTLOADER`)** or any undefined type.
4. **`EXTENDED`** with any info type other than 2 (product info) or 3 (heart-rate
   monitor list).
5. **`WRITE`** to `SYSTEM_MODE` 102, `CALIBRATE_GRADE` 415, `BELT_REPLACED`
   267, motor-replaced and calibrate-motor flags, activation, disconnect,
   update-related flags, ANT licence, any raw drive/incline/resistance motor or
   throttle parameter (2301–2514, 701–725), and, until understood,
   `IDLE_SYSTEM_MODE_LOCK` 103 and `HEART_BEAT_INTERVAL` 161.

Writes to 301, 401 and 602 are legitimate but **move the machine**. Make them
only with someone at the treadmill, the safety key on, and STOP in reach.

---

## Seen on hardware

11/10/2026, a Gen 7 incline trainer, model string `ETNT39225-INT`, board
software 1.19, iFit 2026.08.25. Belt stopped, nobody on the deck, read-only
requests only.

```
→ 02 0E 01 02 00                          product info
← 02 15 06 5A 02 00 00 00 00              (unsolicited) WORKOUT_STATE = 0
← 02 1E 10 02 01 "1.19.2e177c9e6"         software version
← 02 1E 0F 02 04 "ETNT39225-INT"          model   (items 2, 3, 5, 6 too)
← 02 1E 02 02 00                          end of list
→ 02 06 00                                supported features
← 02 11 10 0A 00 66 00 … (8 chunks) … 02 11 00   61 ids, once
→ 02 01 10 66 00 5A 02 2E 01 92 01 2F 01 30 01 93 01 94 01 00   subscribe 8
← 02 13 01 01                             ACK of SUBSCRIBE
← 02 15 06 66 00 00 00 80 3F              SYSTEM_MODE   = 1.0  IDLE
← 02 15 06 2E 01 00 00 00 00              CURRENT_KPH   = 0.0
← 02 15 06 2F 01 3B DF BF 3F              MIN_KPH       = 1.499
← 02 15 06 30 01 00 00 A0 41              MAX_KPH       = 20.0
← 02 15 06 92 01 00 00 00 00              CURRENT_GRADE = 0.0
← 02 15 06 93 01 00 00 C0 C0              MIN_GRADE     = −6.0
← 02 15 06 94 01 00 00 20 42              MAX_GRADE     = 40.0
← 02 15 06 5A 02 00 00 00 00              WORKOUT_STATE = 0  NONE
```

What this confirms:

- **Values are float32 in real units.** km/h and percent, with no scaling.
  The limits match the console's own settings screen.
- **Safety key.** Pulling it sent `SYSTEM_MODE` = 2.0 (`02 15 06 66 00 00 00 00 40`).
  Clipping it back sent 1.0. For about 4 s while it was being clipped in, the
  value **bounced** between 1 and 2 (contact bounce). Debounce it before acting
  on it.
- **The board repeats its latest `EVENT` about every 1.5 s** while nothing
  changes. That's a usable sign the link is alive.
- No `ERROR` replies to any well-formed request.

---

## Driving it: what the board actually does

11/10/2026, same board, **belt empty**, safety key in and a hand on it. STRIDE
writing only `TARGET_KPH` (301), `TARGET_GRADE_PERCENT` (401) and
`WORKOUT_STATE` (602). All HARDWARE.

| Write | From | Result |
|---|---|---|
| 401 incline ±1 % | idle | Deck moves, in any state, as on FitPro 1 |
| 301 any speed | idle (`NONE`) | **Refused**: `02 14 08 02 05 2D 01 <float>`, data out of range. An idle board takes no target speed |
| 602 = 3 `RUNNING` | `NONE` | Accepted. `SYSTEM_MODE` → 3, belt starts |
| 301 ≥ minimum | `RUNNING` | Accepted. The belt follows |
| 301 < ~1.5 km/h | `RUNNING` | Refused: there is no speed 0 |
| 602 = 5 `PAUSED` | `RUNNING` | Accepted. **The belt stops** |
| 602 = 3 `RUNNING` | `PAUSED` | Accepted. The belt restarts at the board's held target |
| 602 = 0 `NONE` | `PAUSED` | Accepted. `SYSTEM_MODE` → 1, idle |
| 602 any | safety key out | Refused, code 7 (write value not allowed) |

What this means for anything driving it:

- **To stop the belt, write `PAUSED`.** A speed of 0 is refused, so FitPro
  1's "speed 0" has to become a state change. STRIDE does this when the speed
  is taken to zero mid-workout, and goes back to `RUNNING` when it is raised again.
- **The board keeps its last target through a pause.** `RUNNING` brings the
  belt back at that pace, so set the minimum speed immediately after `RUNNING`
  and ramp from there. Setting it *before* is refused while idle.
- **`CURRENT_KPH` never reports a stopped belt as 0.** It falls a little and
  then stops changing: 2.13, 1.75, then nothing, with the belt standing still.
  A fresh subscription returns that frozen value. A turning belt's reading
  changes from one event to the next; a stopped one repeats. STRIDE treats a
  reading unchanged for 3 s outside a running state as stopped.
- **The board repeats its latest frame about every 1.5 s, errors included.** An
  old refusal keeps arriving. Match an `ERROR` to the write it names (request
  type and feature id), not to whatever was sent last.
- **`DISTANCE` (252) is metres.** 33 m was reported for about a minute at
  1.5–2 km/h.
- **If the host app dies, the belt stops at once**, but the board's state
  does not say so. STRIDE was force-closed with the belt running at 2 km/h: the
  belt stopped immediately, the board dropped off USB and re-attached about
  5 s later, and the relaunched app found it still reporting `RUNNING` with
  `CURRENT_KPH` 2.13. So releasing the interface stops the motor, but whoever
  connects next must not trust `WORKOUT_STATE`. STRIDE's unattended-belt check
  wrote `PAUSED` within a second of reconnecting.
- **If the host hangs while still holding the interface, the belt runs on.**
  With STRIDE's process frozen, the belt kept going at 2 km/h until the safety
  key was pulled. Nothing in what STRIDE sends acts as a keep-alive. STRIDE now
  watches its own poll loop and releases the interface after 3 s of silence
  mid-workout, which stops the belt (tested). `HEART_BEAT_INTERVAL` (161) may be
  the board's own answer to a fully frozen host. It is unexplored.
- **The board leaves `PAUSED` for `RESULTS` by itself after 10 minutes**,
  presumably its `PAUSE_TIMEOUT` (619).

---

## Over Bluetooth too

The same framing appears to be used over BLE on some iFit equipment (INFERRED
from the transport-independent design). The board described here was reached
over USB.
