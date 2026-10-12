# Treadmills

What is known about each NordicTrack console generation, and about each
machine STRIDE has actually been run on.

The install guide is the how-to. These pages are the "what will I find"
reference: which board, which protocol, which quirks, and what was tested.

## Generations

| Generation | Android | Motor board | Protocol | STRIDE | Page |
|---|---|---|---|---|---|
| Gen 6 | 9 | FitPro, USB `0x213C`/`0x0002` | [FitPro 1](../protocol/FITPRO_PROTOCOL.md) | In daily use | [gen6.md](gen6.md) |
| Gen 7 | 13 | iFIT-LargeX, USB `0x213C`/`0x0003` | [FitPro 2](../protocol/FITPRO2_PROTOCOL.md) | Walked on, one machine | [gen7.md](gen7.md) |

To find yours: **Settings → Equipment info** on the iFit console shows
*Generation*. Or look at **Settings → About tablet → Build number**: `EKA2_…`
has been Gen 6, `VKX1_…` Gen 7.

## Machines

| Machine | Generation | Type | Status | Page |
|---|---|---|---|---|
| NordicTrack C 1750 | Gen 6 | Treadmill | Daily use since 2026 | [nordictrack-c1750-gen6.md](nordictrack-c1750-gen6.md) |
| NordicTrack X22i (`ETNT39225-INT`) | Gen 7 | Incline trainer | Walked on, October 2026 | [nordictrack-x22i-gen7.md](nordictrack-x22i-gen7.md) |

## Add yours

Copy [TEMPLATE.md](TEMPLATE.md) to `<brand>-<model>-gen<n>.md`, fill in what
you know, and open a pull request. "I got as far as privileged mode and then
it stopped" is a useful page. A list of machines where it works is only as
good as the list of machines where it doesn't.

**Leave out anything that identifies your console:** serial numbers, the
brainboard serial, the console UUID, MAC and IP addresses. Model, part numbers,
software versions and ranges are what's useful, and they're the same on every
unit of that model.
