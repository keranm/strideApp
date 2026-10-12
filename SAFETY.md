# Safety

Read this before you install anything.

STRIDE replaces the software on a treadmill console and then drives the
machine. It sets the belt speed and moves the incline deck while somebody is
standing on it.

---

## What can hurt you

**The belt and deck can move without you touching anything.** A guided walk
changes the incline on its own, and a warm-up ramps the belt up when a workout
starts.

**The incline deck moves under load** — flat to nine percent shifts real weight
while you're on it.

**Everything that was dangerous about the treadmill before still is.** The
motor, belt and deck are unchanged.

---

## What the software does about it

**The safety key is the emergency stop, not the software.** Pull it and the
hardware cuts the belt, independently of anything STRIDE is doing. STRIDE
notices and shows a screen, but it isn't what stopped the belt.

**STRIDE drives the incline. It only ever suggests a speed.** A guided walk
moves the deck, because the deck can't run away underneath you. It will say
"lift to 6.4 km/h" and leave the belt where it is.

**On Gen 7, a stuck app stops the belt.** If the loop that drives the board
stalls for three seconds mid-workout, a separate watchdog lets go of the board,
which stops the belt, and the workout is left paused until somebody presses
RESUME. If the app dies outright, the board stops the belt on its own. A
console frozen solid, app and watchdog together, is not covered yet: that is
the safety key's job. All of this was tested on one Gen 7 with the belt
empty, before anyone walked on it.

**Automatic stops ramp down** at 2 km/h per second. The first guided walk cut
the belt from 4.2 km/h to zero the moment the plan ended, which was enough to
catch someone off guard — at a run it would have put them on the floor. **The
STOP button is exempt and stops immediately**, because that is a person
deciding.

**The board's limits win.** Incline and speed ranges are read from the machine
at startup and every plan is clamped to them. Nothing in Settings can raise
them.

**Incline moves gradually** — a percent every three seconds by default, so the
biggest jump in any built-in plan takes about half a minute rather than landing
under you mid-stride. Settings offers Gentle, Normal and Quick rather than raw
numbers.

---

## What this isn't

STRIDE is not a medical device and is not certified as anything.

The coach is a language model given some numbers and told to be brief. It is
instructed never to give medical advice or express concern about a reading, and
there is a setting to take a subject off the table entirely. It can still be
wrong.

Bluetooth straps, and handlebar grips on machines that have them, are consumer
heart rate sensors. If a number looks alarming, believe your body and a doctor.

---

## What you're doing to your treadmill

- **Your warranty is almost certainly void.** Assume it is.
- **You lose iFit** — subscription workouts, the app, the account.
- **You can make the console unbootable.** Read the whole install guide before
  starting, not while stuck halfway.

**It's reversible.** A factory reset restores the manufacturer's software,
verified on the machine STRIDE was built against. You are replacing the screen
on the front, not the motor controller.

That is not the same as supported — nothing here is supported by anybody, and a
reset that worked on one console is not a promise about yours. But the
realistic worst case is "reset it and start again", not "you now own a broken
treadmill".

Don't do this to a treadmill you don't own.

---

## If something goes wrong

1. **Pull the safety key.**
2. Step off.
3. Switch off at the wall.

Both still work whatever the console is doing. STRIDE can't disable either.

To get the treadmill back as it was, factory reset from the console's recovery
and set up iFit again.

---

## Reporting a safety problem

Please open a
[security advisory](https://github.com/keranm/strideApp/security/advisories/new)
rather than a public issue, so it can be looked at before it is widely known.

Anything that moves the belt or deck in a way the person on it didn't expect is
a safety problem, not a bug.
