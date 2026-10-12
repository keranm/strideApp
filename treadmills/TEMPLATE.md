# <Brand> <Model> (Gen <n>)

> Copy this to `<brand>-<model>-gen<n>.md`. Leave out serial numbers, the
> console UUID, and MAC and IP addresses. Write "unknown" rather than guessing.

## The machine

| | |
|---|---|
| Model | |
| Type | treadmill / incline trainer |
| Generation | from Settings → Equipment info |
| Embedded type | e.g. CLASSIC |
| System version | Settings → About tablet → Build number |
| Android | |
| iFit / GlassOS version | |
| Board firmware | |
| Speed range | as the board reports it |
| Incline range | as the board reports it |

## The board

What `console/usbprobe` said: USB vendor/product id, product string,
protocol (FitPro 1 or 2), model string, board software version.

## How far you got

- [ ] Factory reset (where is the pinhole?)
- [ ] Privileged mode (did a challenge code appear? which iFit version?)
- [ ] iFit Admin disabled
- [ ] ADB: classic port 5555, or Wireless debugging pairing?
- [ ] `unchain.sh`
- [ ] usbprobe
- [ ] STRIDE connects and shows live values
- [ ] Incline moves
- [ ] Belt starts, changes speed, stops (belt empty)
- [ ] Safety key seen
- [ ] Powers on into STRIDE
- [ ] Walked on it

## Quirks

Anything that didn't match the install guide, and what you did about it.

## Tested by

Who, when (DD/MM/YYYY), and which STRIDE commit or release.
