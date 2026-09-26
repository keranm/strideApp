package dev.stride.hud

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * STRIDE HUD.
 *
 * Kotlin owns the treadmill (USB + FitPro + MQTT) and the workout session; the
 * UI is an HTML page in a WebView so it can be designed and iterated in a
 * browser. State is pushed to JS at poll rate; button presses come back over
 * the `Stride` bridge.
 *
 * The physical safety key remains the stop of record.
 */
class MainActivity : Activity() {

    companion object {
        const val TAG = FitProConnection.TAG
        const val ACTION_USB_PERMISSION = "dev.stride.hud.USB_PERMISSION"
        const val POLL_MS = 200L

        /** Request code for the location permission a BLE scan needs. */
        const val SCAN_PERMISSION = 1

        /** How long to wait before answering a securityBlock again. The board
         *  can refuse for a moment while it settles; hammering VerifySecurity
         *  at 5 Hz would be its own kind of denial of service. */
        const val UNLOCK_RETRY_MS = 2000L

        /** How long our own write owns the target before the board's echo is
         *  believed again. Six polls — comfortably longer than the board takes
         *  to reflect a write, short enough that a physical press still feels
         *  immediate. */
        const val FOLLOW_SETTLE_MS = 1200L

        /**
         * How long the setpoint must have been still before a shortfall counts
         * as the belt failing rather than the belt on its way somewhere.
         *
         * The board ramps to a new speed over several seconds, during which the
         * odometer is honestly and correctly below the setpoint. Without this,
         * every press of + would be filed as a slip.
         */
        const val SLIP_SETTLE_MS = 8_000L

        /** Shortest shortfall worth a line in the incident record. Below this
         *  it is the estimator's own noise, which is what the wide band on
         *  Snapshot.slipping already allows for. */
        const val SLIP_MIN_MS = 3_000L

        /** The grid the speed keys move on. An adopted value snaps to it, so a
         *  board reporting 6.499 cannot render as 6.4 and then take two presses
         *  to reach 6.5. Grade is deliberately not quantised — its step size is
         *  not settled here and incline is not what was reported broken. */
        const val KPH_STEP = 0.1

        /**
         * What a session is called, now that there is only one kind.
         *
         * Was "walk" or "run", chosen on a screen of its own. The distinction
         * bought a word and cost a tap, and the ceiling it advertised — 6.5 km/h
         * against 19 — was never enforced anywhere: both clamp to the board's
         * own MIN_KPH/MAX_KPH. Deriving it from pace instead would be a guess,
         * because 7-8 km/h is a walk for some people and a jog for the same
         * person on a different day. The console does not know, so it does not
         * say. Sessions recorded before 2026-09-07 still read "walk"; nothing
         * rewrites them.
         */
        const val WORKOUT = "workout"

        /**
         * How far the wall clock may disagree with the monotonic clock before
         * it counts as having been corrected rather than merely drifting.
         *
         * NTP moves this board by *sixteen years*, so anything in the region of
         * a minute separates the two cases with room to spare. Small enough to
         * catch an ordinary time-zone correction too; large enough that normal
         * clock skew over a 40-minute walk never trips it.
         */
        const val CLOCK_JUMP_MS = 60_000L

        /**
         * Before this, a date is the board's boot clock rather than a date.
         *
         * 2024-01-01. The console cannot have recorded a walk before it was
         * built, so anything earlier is the 2010 epoch this board comes up on —
         * whatever year it happens to claim.
         */
        const val SANE_EPOCH_MS = 1_704_067_200_000L

        /**
         * Longer than this between two live pulse readings and the seconds in
         * between are credited to no zone.
         *
         * The strap reports about once a second; four seconds is a dropout, and
         * on 17 August 2026 one lasted from the end of the walk until the strap
         * was taken off entirely. Time nobody was measured is not time in a zone.
         */
        const val ZONE_MAX_GAP_MS = 4_000L

        /** Fan is polled on its own exchange, not folded into TELEMETRY —
         *  see [readFan]. */
        const val FAN_EVERY_MS = 1000L

        /** How many times to command the fan off before letting it go. On a
         *  board that will not report fan state there is nothing to confirm
         *  against, so this cannot be "until it agrees" the way levelling is. */
        const val FAN_OFF_NAGS = 25

        /** How many consecutive rejections before a queued write is abandoned.
         *
         *  This is a responsiveness number, not just a safety valve. PAUSE and
         *  STOP are queued writes, so however long we keep retrying is how long
         *  the console feels unresponsive when the board is refusing — the
         *  first attempt at fifty tries meant ten seconds of nothing happening
         *  after a tap, which read as broken rather than busy.
         *
         *  Fifteen is three seconds: still far more than the "one dropped frame
         *  must not spend a command" rule needs, and short enough that a
         *  refusal surfaces while the person is still looking at the button. */
        const val WRITE_TRIES = 15
        const val MQTT_EVERY_MS = 1000L

        /**
         * How long the board may say nothing before it counts as not answering.
         *
         * Distinct from a refused frame, which is the board talking. Silence is
         * either the machine asleep — see [FitPro.Mode.SLEEP] — or the board
         * gone from the USB bus, and the second of those is only ever fixed by
         * a new connection.
         */
        const val SILENCE_MS = 5_000L

        /** How long to leave between two attempts at rebuilding the transport.
         *  Nothing about a silent board is urgent enough to retry at poll rate. */
        const val REACQUIRE_MS = 5_000L

        /**
         * How long a wake is given before it is called a failure.
         *
         * Long enough for a reacquire and a mode write to land at 5 Hz, short
         * enough that somebody standing in front of a dark machine at 6am gets
         * a straight answer rather than a spinner.
         */
        const val WAKE_WAIT_MS = 8_000L

        /**
         * How long to wait before trying the *first* MQTT connection again,
         * and the ceiling the wait backs off to.
         *
         * Paho reconnects by itself, but only once it has connected once: a
         * first attempt that throws is final, and nothing after it ever tries
         * again. On a console that starts itself at boot that is the common
         * case, not the rare one — this machine autostarts 0.3 s after the
         * boot broadcast and its Wi-Fi associates about ten seconds later, so
         * the one attempt reliably lands in the window where there is no
         * network to connect over. It came back up, ran a whole walk with the
         * coach mute, and logged "mqtt down, event not sent" thirty times.
         *
         * Backing off to a minute rather than hammering: nothing here is
         * urgent, and a broker that is down is usually down for a while.
         */
        const val MQTT_RETRY_MS = 5_000L
        const val MQTT_RETRY_MAX_MS = 60_000L

        /**
         * The selectable interfaces, in the order the settings screen shows them.
         *
         * Each is one document in `assets/ui/`, owning its whole DOM and CSS and
         * implementing the same two entry points — `window.render(state)` and
         * `window.plan(p)`. They are not themes: switching one for another is
         * expected to change what the screen is made of, not just its colours.
         *
         * Anything named here but not yet present in the APK falls back to
         * [DEFAULT_UI], so this list can run ahead of the files.
         */
        val UIS = listOf("original", "ember", "cluster", "daylight", "pacer")
        const val DEFAULT_UI = "original"

        const val PREFS = "stride"
        const val PREF_UI = "ui"

        /** How briskly a ramp winds the belt up. */
        const val RAMP_KPH_PER_SEC = 1.0

        /**
         * How briskly the belt is allowed to wind *down* to a standstill.
         *
         * The first full guided walk ended by cutting the belt from 4.2 km/h to
         * zero the instant the plan expired, which was enough to catch someone
         * off guard — at a run it would have been enough to put them on the
         * floor. Every automatic finish now decelerates instead. Quick, but not
         * sudden: 2 km/h per second brings a brisk walk to a stop in about two
         * seconds and a hard run in five.
         *
         * The STOP button has its own, brisker one — see [STOP_DECEL_KPH_PER_SEC].
         */
        const val DECEL_KPH_PER_SEC = 2.0

        /**
         * The STOP button, above a walking pace.
         *
         * STOP used to hand the board a bare {KPH 0, PAUSE}, which is the
         * machine's own rapid stop. At a walk that is exactly right: somebody
         * reaching for STOP wants the belt to stop, now. Above about 6 km/h it
         * is enough to pitch you forward — reported on 2026-08-10 after a
         * stumble.
         *
         * So a stop from a run sheds the speed a run cannot absorb first, and
         * hands over to that same rapid stop once the belt is down to
         * [STOP_EASE_ABOVE_KPH] — the speed it is already fine from. Below that
         * threshold nothing changes at all.
         *
         * **The rate is set by what pressing STOP means.** It is a decision,
         * not a stumble: unlike the safety key, the person has chosen this and
         * is braced for it, so easing must not read as the console dithering.
         * At 6 km/h per second the belt is off the top of its range in about a
         * second and into the ordinary stop — quick enough to feel answered,
         * gradual enough not to throw anyone forward. Raised from 3.0 on
         * 2026-08-10: the first rate on real legs was safe and slow.
         *
         *     from 12 km/h   1.0 s of easing, then the stop
         *     from 10 km/h   0.8 s
         *     from  8 km/h   0.4 s
         *
         * Three times [DECEL_KPH_PER_SEC], which is the same trade seen from
         * the other end: a plan running out has not asked for anything, so it
         * gets the gentler wind-down and the whole way to a standstill.
         */
        const val STOP_DECEL_KPH_PER_SEC = 6.0
        const val STOP_EASE_ABOVE_KPH = 6.0

        /**
         * How long a wind-down may hold [enforceStopped] off.
         *
         * A wind-down is the stop being carried out, so the safety net stands
         * aside while one is in flight — but not indefinitely. Every wind-down
         * only ever takes speed off and reaches its floor within a few seconds,
         * so anything still running after this has stuck, and the net wins.
         */
        const val WIND_DOWN_MAX_MS = 10_000L

        /**
         * Below this the belt counts as stopped. Not zero: the board reports
         * small non-zero speeds while the belt coasts to rest, and demanding
         * an exact zero would nag forever.
         */
        const val STOPPED_KPH = 0.3

        /**
         * Metres the odometer must advance before an idle belt counts as moving.
         *
         * Distance is whole metres, so one of them can appear at the boundary of
         * a walk that has just ended. Two cannot arrive on a belt that is
         * standing still — nothing fabricates metres.
         */
        const val IDLE_ODO_METRES = 2.0

        /**
         * No odometer movement at all for this long, and the idle belt really is
         * stopped. Only ever consulted when *nothing* has moved: a belt crawling
         * too slowly to have covered [IDLE_ODO_METRES] yet keeps its anchor, so
         * a slow runaway is still caught, however long it takes to prove.
         */
        const val IDLE_STILL_MS = 4000L

        /** Close enough to level to stop asking. The deck reports whole percent. */
        const val LEVEL_GRADE = 0.5

        /* ---- the speed readout ------------------------------------------
         *
         * See [beltSpeed]. The board will not tell us the belt speed, so it is
         * estimated from two things that disagree in useful ways: what the belt
         * was told to do, which is instant but may be a lie, and the odometer,
         * which is the truth but arrives in whole metres and several seconds
         * late. These are the weights on that trade.
         */

        /** How fast the model lets the belt gain and shed speed, km/h per
         *  second. Deliberately a little *under* what the machine can do: a
         *  readout that arrives slightly behind climbs smoothly, and one that
         *  arrives ahead has to come back down, which is a wobble. */
        const val BELT_UP_KPH_PER_SEC = 1.2
        const val BELT_DOWN_KPH_PER_SEC = 2.5

        /** How much of the odometer's disagreement is taken back per poll —
         *  into where the model thinks it is, and into how fast it thinks it is
         *  going. The second is small on purpose: it is an integrator, and it
         *  is the only thing that can outvote the commanded pace. */
        const val TRIM_POSITION = 0.15
        const val TRIM_SPEED = 0.006

        /** Whole metres at 6 km/h is a sawtooth with a 0.6 s period. Nothing
         *  about a belt changes that fast, so the disagreement is smoothed
         *  before it is allowed to move the number on screen. Without this the
         *  last digit flickers about half of every second. */
        const val TRIM_SMOOTH = 0.15

        /** A disagreement smaller than this is measurement noise or belt
         *  calibration, not news, and it bleeds away at [TRIM_SETTLE_PER_SEC]
         *  so a steady pace reads as the pace that was asked for.
         *
         *  This is the old settle band, kept as a decision but moved: it used
         *  to switch the whole readout over to the target once it came close,
         *  which is why leaving it looked like a jump. Now it pulls the
         *  *correction* to nothing, so arriving and leaving are both smooth,
         *  and a belt that is genuinely a long way off still says so. */
        const val TRIM_SETTLE_KPH = 0.35
        const val TRIM_SETTLE_PER_SEC = 0.10

        /** The correction is bounded, so one wild odometer frame cannot run the
         *  readout away from the machine. */
        const val TRIM_MAX_KPH = 4.0

        /**
         * How close the model must be to the commanded pace before the odometer
         * is allowed to correct it at all.
         *
         * Wider than one step of the model's own ramp (BELT_UP_KPH_PER_SEC at
         * POLL_MS is 0.24 km/h), so "arrived" is not a state it can skip over
         * between two polls, and far below the smallest change anyone can ask
         * for by hand. See beltSpeed() for what this protects against.
         */
        const val TRIM_ARRIVED_KPH = 0.30

        /**
         * Fallbacks only. The live values are in [Settings] — warm-up length,
         * cool-down length and warm-up speed are all set on the console now,
         * and these are what a fresh install starts from.
         */
        const val WARMUP_KPH = 2.0
        const val WARMUP_MS = 2 * 60 * 1000L
        const val COOLDOWN_MS = 2 * 60 * 1000L

        /** Longer than this between polls and the estimator re-seeds rather
         *  than integrating across a gap it knows nothing about. */
        const val MAX_STEP_MS = 2000L

        /**
         * How fast a guided walk is allowed to move the deck.
         *
         * One percent at a time, no more often than every three seconds, so the
         * biggest jump in any template — flat to nine percent — takes about half
         * a minute to arrive rather than landing under someone mid-stride.
         */
        const val INCLINE_STEP = 1.0
        const val INCLINE_EVERY_MS = 3000L

        /**
         * How long one lap of an open-ended route is.
         *
         * "Open" means walk until you have had enough, and the ground repeats
         * rather than being invented — so a template becomes a circuit and you
         * go round it. Twenty minutes is long enough to have a shape and short
         * enough that you actually come round again; at thirty most walks would
         * never see the same hill twice, which is the same as not looping.
         */
        const val OPEN_LOOP_MIN = 20

        val TELEMETRY = listOf(
            FitPro.Field.ACTUAL_KPH,
            FitPro.Field.ACTUAL_INCLINE,
            FitPro.Field.WORKOUT_MODE,
            FitPro.Field.DISTANCE,
            FitPro.Field.CURRENT_TIME,
            FitPro.Field.PULSE,
            FitPro.Field.CALORIES,
            // Read on the chance it is a different sensor from DISTANCE — see
            // Snapshot.rpm. It has only ever been asked for by probeSpeed(),
            // which runs once per app start and had already rolled out of the
            // buffer every time anyone went looking. Reading it every poll is
            // how we find out what it does over a whole walk.
            FitPro.Field.RPM,
        )
        val LIMITS = listOf(
            FitPro.Field.MIN_KPH, FitPro.Field.MAX_KPH,
            FitPro.Field.MIN_GRADE, FitPro.Field.MAX_GRADE,
        )
        /** Kept out of TELEMETRY on purpose — see [readFan]. */
        val FAN_READ = listOf(FitPro.Field.FAN_STATE)

        /** The board's own idle timer, read once at startup — see [readSleepConfig]. */
        val SLEEP_FIELDS = listOf(
            FitPro.Field.IDLE_TIMEOUT,
            FitPro.Field.IDLE_MODE_LOCKOUT,
            FitPro.Field.SLEEP_TIMER_STATE,
        )

        /** KPH/GRADE are the board's *targets* — read so physical buttons don't desync us. */
        val READS = listOf(FitPro.Field.KPH, FitPro.Field.GRADE) + TELEMETRY

        /** Candidate sources for a real belt speed — see probeSpeed(). */
        val SPEED_PROBE = listOf(FitPro.Field.RPM, FitPro.Field.ACTUAL_KPH)
    }

    private lateinit var web: WebView

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val conn by lazy { FitProConnection(this) }
    private val mqtt = MqttPublisher()
    private val cfg by lazy { Settings(this) }
    /* The strap hunt is held off while an FTMS client is subscribed: this
       console's radio cannot scan as a central and hold a peripheral link at
       the same time. See HeartRate.seek. */
    private val strap by lazy { HeartRate(this) { ftms.listeners > 0 } }
    /** The treadmill as a standard Bluetooth fitness machine, for Zwift and
     *  friends. Telemetry only, and silent on a radio that cannot advertise. */
    private val ftms by lazy { Ftms(this) }
    private val voice by lazy { CoachVoice(this) }
    private val coach = Coach()

    @Volatile private var running = false
    @Volatile private var deviceId: Byte = FitPro.Dev.TREADMILL
    @Volatile private var targetKph = 0.0
    @Volatile private var targetGrade = 0.0
    @Volatile private var fanState = 0
    @Volatile private var pendingWrite: Map<FitPro.Field, Double>? = null

    /** True while the board is refusing traffic pending VerifySecurity. The
     *  HUD shows this rather than sitting silently unable to act. */
    @Volatile private var boardLocked = false
    private var unlockAttempts = 0
    private var lastUnlockMs = 0L

    /** When the board last said anything at all — a refusal counts, silence
     *  does not. See [boardAwake] and the null branch of [pollLoop]. */
    @Volatile private var lastReplyMs = 0L
    @Volatile private var lastReacquireMs = 0L

    /** When somebody last asked for the machine to be woken, or 0 if nobody
     *  has since it was last awake. See [wakeState]. */
    @Volatile private var wakeAskedAt = 0L

    /** When we last wrote a target. Until this settles, our value outranks the
     *  board's echo of it — see the follow block in [pollLoop]. */
    private var lastWriteMs = 0L
    private var sinceFan = 0L
    private var fanStopping = false
    private var fanOffNags = 0

    /** Consecutive rejections of the same queued write, and the last snapshot
     *  that came off a good frame — what the HUD keeps showing while the board
     *  is refusing. */
    private var writeTries = 0
    @Volatile private var lastSnap: Snapshot? = null
    /** Null until the board has answered a fan read once. Stays null on a board
     *  that does not report fan state, and the HUD keeps its local value. */
    private var fanReadable: Boolean? = null

    /** Snap to a step grid, killing float drift like 6.500000000000001. */
    private fun quantise(v: Double, step: Double) = Math.round(v / step) * step

    /**
     * Average pace for the session, km/h, from distance over time.
     *
     * Both numbers are counters the board keeps, so this is measured rather than
     * estimated — unlike the old average, which was the mean of the odometer
     * estimator's per-poll output and therefore carried all of that estimator's
     * drift into the summary screen.
     */
    private fun avgPace(): Double {
        val secs = elapsedSec()
        return if (secs > 1.0) sessionDistance / secs * 3.6 else 0.0
    }

    /**
     * Which zone a share of maximum heart rate falls in, 0-5.
     *
     * The usual five-zone split on percentage of maximum. Zone 0 is everything
     * below 50%, which is standing about rather than a training zone, and is
     * counted separately so it cannot inflate zone 1.
     *
     * Kept here rather than shared with Coach.zoneOf deliberately: that one
     * answers "what should the coach call this effort right now" and returns
     * 1-5 with no floor, because a coach saying "barely ticking over" about a
     * 45% share is right. This one is dividing up a walk and needs the floor.
     */
    private fun zoneIndex(share: Double): Int = when {
        share < 0.50 -> 0
        share < 0.60 -> 1
        share < 0.70 -> 2
        share < 0.80 -> 3
        share < 0.90 -> 4
        else -> 5
    }

    /** Pace at the moment of pausing, and the speed the resume ramp is climbing
     *  towards. Zero for either means no ramp is in progress. */
    @Volatile private var pausedKph = 0.0
    @Volatile private var rampTo = 0.0
    /** "warmup", "resuming" or "cooldown" while a ramp is in flight. */
    @Volatile private var rampReason = ""

    /** Deadline for a timed phase (warm-up / cool-down), on the elapsedRealtime clock. */
    @Volatile private var phaseEndsAt = 0L

    // --- session (ours, not the board's) ------------------------------------
    @Volatile private var session = Session.WELCOME
    @Volatile private var workout = "none"
    @Volatile private var dmk = false

    /**
     * Whose walk this is. Set from the welcome screen, defaulting to whoever
     * Settings names — which is nobody on a fresh install, so the screen asks.
     */
    @Volatile private var walker = ""

    /**
     * The current walker's maximum heart rate, or 0 if they have not given an
     * age. Cached rather than looked up per frame: reading it means parsing the
     * whole person list out of JSON in SharedPreferences, and the poll loop
     * builds a Snapshot five times a second. Refreshed wherever [walker] is
     * set, which is the only thing that can change the answer mid-walk.
     */
    @Volatile private var walkerHrMax = 0

    private fun refreshWalkerHrMax() {
        walkerHrMax = cfg.person(walker)?.maxPulse ?: 0
    }

    // --- guided walk ---------------------------------------------------------
    /** Empty on a casual walk. Set once at START and never rewritten mid-walk. */
    @Volatile private var planName = ""
    /**
     * The route being walked, if this is a route rather than a template.
     *
     * Held alongside `planSteps` rather than replacing it: the HUD's sparkline
     * and segment counter read the steps, so a route fills both — the steps for
     * anything that wants to draw the ground, and this for the thing that
     * decides which part of it you are standing on.
     */
    @Volatile private var route: Route? = null

    @Volatile private var planSteps: List<Plan.Step> = emptyList()
    @Volatile private var stepIndex = -1

    /**
     * An open-ended route: walk until you decide to stop, and the ground goes
     * round again rather than being invented. The template becomes a circuit
     * one [OPEN_LOOP_MIN] lap long, and nothing ever ends the walk except the
     * person on it.
     */
    @Volatile private var planLoops = false
    @Volatile private var planLap = 1
    /** Plan time, wrapped into the current lap. Equals elapsed when not looping. */
    @Volatile private var planElapsed = 0.0

    /**
     * The pace Sam settled at during the opening segment. Every suggestion is
     * relative to this, so a plan adapts to the day instead of assuming a number
     * that was true whenever the template was written.
     */
    @Volatile private var baselineKph = 0.0

    /**
     * Cleared the moment he touches INCLINE, and only restored at the next
     * segment. A plan that argues with the person on the treadmill about the
     * hill they are standing on is a plan that feels possessed.
     */
    @Volatile private var inclineAuto = false
    private var lastInclineMoveAt = 0L

    /** The coach's closing line, asked for near the end and shown on the summary. */
    @Volatile private var summaryLine = ""

    /** Its audio, held back until the summary is actually on screen. */
    @Volatile private var summaryUrl = ""
    @Volatile private var summarySpoken = false

    /** Last speed the *board* reported, as opposed to what we asked for. */
    @Volatile private var lastActualKph = 0.0
    /** The grade the board last reported, for enforceLevel. */
    @Volatile private var lastActualGrade = 0.0

    /**
     * How fast the belt is turning while no workout is running, from the
     * odometer. See [idleBeltSpeed] — this is what [enforceStopped] actually
     * runs on, because [lastActualKph] is zero on this board at every speed.
     */
    @Volatile private var idleBeltKph = 0.0
    /** Odometer anchor for [idleBeltSpeed]; negative means "not anchored yet". */
    private var idleOdoMetres = -1.0
    private var idleOdoAt = 0L

    /** How many times we have had to re-command a stop. Reset when it takes. */
    @Volatile private var stopNags = 0

    /** Wall clock and uptime at which the current runaway was first seen. */
    private var runawayAtWall = 0L
    private var runawayAtUptime = 0L
    private var runawayPeakKph = 0.0

    /** Uptime the current shortfall began at; 0 when the belt is keeping up. */
    private var slipSince = 0L
    /** Worst shortfall in the current episode, km/h, and the setpoint it was
     *  measured against. */
    private var slipWorst = 0.0
    private var slipTarget = 0.0
    /** Last setpoint seen and when it moved — see [SLIP_SETTLE_MS]. */
    private var slipLastTarget = -1.0
    private var slipTargetChangedAt = 0L

    /**
     * Write one line to the console's own incident record.
     *
     * Not Log.w. Logcat on this console holds roughly a day and had already
     * rolled past the start of the 2026-08-28 runaway by the time anyone
     * looked — the whole reason that morning has no answer. This file is small,
     * append-only, and survives both the buffer and a reboot.
     *
     * The clock is stamped as well as the uptime because this board boots at
     * 2010-01-01 and only jumps to the real time once the network is up. A line
     * claiming 2010 is not a broken record; it is a runaway that began before
     * NTP landed, and the uptime is what orders it.
     */
    private fun recordIncident(line: String) {
        try {
            val f = java.io.File(filesDir, "incidents.log")
            // Keep it bounded. This should be a handful of lines a year, and a
            // file that can grow without limit on a device nobody logs into is
            // its own bug.
            if (f.length() > 64 * 1024) f.delete()
            val when_ = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("$when_ (up ${SystemClock.elapsedRealtime() / 1000}s) $line\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not write incident record: ${e.message}")
        }
    }
    /**
     * Watch for the belt running short of the pace it was told to hold, and
     * write one line per episode to the incident record.
     *
     * Edge-triggered rather than per-poll: an episode is a start, a worst
     * point and an end, and five polls a second of "still short" is not a
     * record, it is a flood. Nothing here stops or corrects anything — a belt
     * that cannot reach its pace is a maintenance question, not a safety one,
     * and the walker can already feel it. This exists so that the next time it
     * happens there is something to read afterwards, which on 8 September 2026
     * there was not.
     *
     * Be clear about what this can and cannot see: it compares the setpoint
     * against the odometer, and the odometer counts the drive roller. A belt
     * slipping *over* that roller moves neither number. See Snapshot.slipping.
     */
    private fun watchSlip(s: Snapshot) {
        val now = SystemClock.elapsedRealtime()

        // A setpoint that has just moved is not a shortfall — the belt is on
        // its way. Abandon any episode in progress rather than attributing it
        // to a speed that is no longer the one being asked for.
        if (s.targetSpeed != slipLastTarget) {
            slipLastTarget = s.targetSpeed
            slipTargetChangedAt = now
            slipSince = 0L
            return
        }
        if (now - slipTargetChangedAt < SLIP_SETTLE_MS) { slipSince = 0L; return }

        if (s.slipping) {
            if (slipSince == 0L) { slipSince = now; slipWorst = 0.0 }
            val short = s.speed - s.beltKph
            if (short > slipWorst) { slipWorst = short; slipTarget = s.speed }
            return
        }

        if (slipSince == 0L) return
        val ran = now - slipSince
        slipSince = 0L
        if (ran < SLIP_MIN_MS) return
        recordIncident("BELT SHORT OF PACE for ${ran / 1000}s — worst " +
                "${"%.1f".format(slipWorst)} km/h under a " +
                "${"%.1f".format(slipTarget)} km/h setpoint (rpm ${"%.0f".format(s.rpm)})")
        Log.w(TAG, "belt short of pace for ${ran / 1000}s, worst " +
                "${"%.1f".format(slipWorst)} km/h under ${"%.1f".format(slipTarget)}")
    }

    /** True while the deck is being walked back to level after a workout. */
    @Volatile private var levelling = false
    @Volatile private var levelNags = 0

    /**
     * The wind-down: true while the belt is being eased to a stop, with the
     * rate it is being eased at, the speed it hands over to the machine's own
     * stop at, and when it started. See [beginWindDown] and [decelStep].
     *
     * One mechanism for both kinds of stop — a workout ending and the STOP
     * button — because they differ only in those two numbers.
     */
    @Volatile private var stopping = false
    @Volatile private var stopRate = DECEL_KPH_PER_SEC
    @Volatile private var stopFloor = 0.0
    @Volatile private var stopSince = 0L

    /** Wall time is unusable here — the console's clock is years out and may
     *  jump if NTP ever reaches it. elapsedRealtime() only ever moves forward. */
    @Volatile private var activeSince = 0L
    @Volatile private var accumulatedMs = 0L

    /** The board's counters are lifetime-ish, so a session is a delta from
     *  wherever they happened to be when START was pressed. */
    @Volatile private var armBaseline = false
    @Volatile private var baseDistance = 0.0
    @Volatile private var baseCalories = 0.0
    @Volatile private var sessionDistance = 0.0
    @Volatile private var sessionCalories = 0.0

    // Rolling stats for the summary screen.
    @Volatile private var maxSpeed = 0.0
    @Volatile private var maxIncline = 0.0
    // Written by the poll thread, cleared from the WebView thread on START.
    @Volatile private var speedSum = 0.0
    @Volatile private var inclineSum = 0.0
    @Volatile private var samples = 0

    /** Pulse keeps its own count: it is the one number that can be absent for
     *  part of a walk, and averaging zeros from before the strap connected
     *  would report a heart rate nobody had. */
    @Volatile private var maxPulse = 0
    @Volatile private var pulseSum = 0L
    @Volatile private var pulseSamples = 0

    /**
     * Seconds in each heart-rate zone, 0-5. Index 0 is below zone 1.
     *
     * Accumulated in wall-clock time rather than in samples, so a strap that
     * drops out for a minute does not quietly donate that minute to whichever
     * zone it was in when it went. Only advanced while a reading is live.
     */
    private val zoneSecs = DoubleArray(6)
    @Volatile private var lastZoneAt = 0L

    /**
     * Metres climbed, integrated as the walk goes.
     *
     * The HUD has always computed this in the page (see profile() in
     * stride-core.js) and thrown it away at the end. History needs it after the
     * fact, so it is accumulated here too, from the same rise-over-run.
     */
    @Volatile private var climbM = 0.0
    @Volatile private var lastClimbMetres = 0.0

    /** Set once when the belt stops, so the summary screen can show it. */
    @Volatile private var earned: List<String> = emptyList()
    @Volatile private var sessionStartedAt = 0L

    /**
     * The same instant as [sessionStartedAt], on the clock that cannot be moved.
     *
     * [SystemClock.elapsedRealtime] counts since boot and is unaffected by NTP,
     * which is the whole point: it is the only witness to how long ago the walk
     * began that survives the wall clock being corrected underneath it. See
     * [sessionStartWall].
     */
    @Volatile private var sessionStartedUptime = 0L

    @Volatile private var maxKph = 19.0
    @Volatile private var minKph = 1.6
    @Volatile private var maxGrade = 12.0
    @Volatile private var minGrade = -3.0

    // Speed-probe bookkeeping — see probeSpeed().
    /** Last board mode we logged, so transitions are visible without spam. */
    @Volatile private var lastBoardMode: Int? = null

    /** Sticky safety-key alarm — see accumulate(). Cleared only by acknowledging. */
    @Volatile private var dmkLatched = false

    private var missingSpeedPolls = 0
    private var speedProbed = false

    // The belt-speed estimator — see beltSpeed(). Poll thread only.
    /** What the belt was told to do, rate-limited: the feed-forward half. */
    private var modelKph = 0.0
    /** What the ground says it actually did, minus that: the correction half.
     *  Persistent, because a correction the model erases every poll is not a
     *  correction. */
    private var trimKph = 0.0
    /** Where the model believes the board's odometer should have got to, and
     *  the smoothed disagreement with where it actually is. */
    private var modelMetres = 0.0
    private var trimResidual = 0.0
    private var lastEstimateAt = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) start(device) else Log.w(TAG, "usb permission denied")
        }
    }

    /** Exposed to the page as `Stride`. Every call lands on a WebView thread. */
    inner class Bridge {

        /**
         * Welcome screen → straight into a warm-up, the way iFit did it:
         * choosing a workout *is* starting it, and the belt eases up to
         * [WARMUP_KPH] rather than waiting for a separate START press.
         *
         * The belt moves as a direct result of this tap, so the welcome screen
         * says so.
         *
         * Took a `type` of "walk" or "run" until 2026-09-07. Nothing downstream
         * did anything with it but change a word, and the screen that asked for
         * it sold the choice on a speed ceiling no code enforced — see flow()
         * in stride-core.js. It is a workout.
         */
        @JavascriptInterface fun choose() {
            if (dmk) return
            workout = WORKOUT
            clearPlan()
            resetSession()
            session = Session.WARMUP
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = activeSince + cfg.warmupMs()
            targetKph = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble())
            Log.i(TAG, "workout chosen — warming up to ${cfg.warmupKph()} km/h")
            repaint()
        }

        /**
         * Start a guided walk.
         *
         * Goes straight to ACTIVE rather than through the two-minute WARMUP
         * phase: every template opens with its own flat settle segment, and
         * having both would mean warming up twice. The plan's stated duration is
         * therefore the whole walk, closing segment included, which is what a
         * duration selector implies.
         */
        /**
         * @param minutes  the walk's length, or **0 for an open-ended route** —
         *                 a [OPEN_LOOP_MIN] circuit walked until STOP, with the
         *                 hills coming round again rather than running out.
         */
        @JavascriptInterface fun chooseGuided(templateId: String, minutes: Int) {
            if (dmk) return
            val template = Plan.byId(templateId) ?: run {
                Log.w(TAG, "no such template: $templateId")
                return
            }
            planLoops = minutes <= 0
            planLap = 1
            planElapsed = 0.0
            // Clamped against what this board actually reports, not what the
            // template hoped for.
            planSteps = Plan.resolve(template,
                if (planLoops) cfg.openLapMin() else minutes, minGrade, maxGrade)
            planName = template.name
            stepIndex = -1
            baselineKph = 0.0
            inclineAuto = true
            lastInclineMoveAt = 0L

            workout = WORKOUT
            resetSession()
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = 0L
            targetKph = 0.0
            targetGrade = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "guided walk: ${template.name}, " +
                    (if (planLoops) "open (${cfg.openLapMin()} min circuit)" else "$minutes min") +
                    ", ${planSteps.size} segments, grade clamped to $minGrade..$maxGrade")
            // The ground is fixed for the whole walk — one lap of it, if this is
            // a circuit — so it goes over once rather than riding along with
            // every frame.
            pushPlan()
            repaint()
        }

        /**
         * The routes cached on this console, for the picker.
         *
         * Read from disk at boot, so this answers whether or not there is a
         * network — which is the whole reason [Routes] writes a file.
         */
        @JavascriptInterface fun routes(): String = routes.json()

        /**
         * Start a walk on a recorded route.
         *
         * No `minutes`, unlike [chooseGuided], and that is deliberate rather
         * than an omission: a route has its own length. Cutting it to fit a
         * time slot means never reaching the summit, which is the reason for
         * walking it again.
         *
         * @param loop walk it out and then walk it home — see [Route.outAndBack].
         *             A property of *this* walk, chosen on the picker, not of
         *             the route: the same 30-minute one-way walk is the right
         *             length some mornings and half a walk on others.
         */
        @JavascriptInterface fun chooseRoute(id: String, loop: Boolean) {
            if (dmk) return
            val found = routes.byId(id) ?: run {
                Log.w(TAG, "no such route: $id")
                return
            }
            val r = if (loop) found.outAndBack() else found

            route = r
            planLoops = false
            planLap = 1
            planElapsed = 0.0
            // Steps carry the same ground so the HUD can draw it, expressed in
            // metres. Nothing reads them as seconds while a route is active —
            // routeTick owns the position — but the sparkline and the segment
            // counter both want the shape.
            planSteps = r.segments.map {
                Plan.Step(startSec = it.startM, endSec = it.endM,
                          incline = it.incline.coerceIn(minGrade, maxGrade),
                          paceDelta = 0.0, label = r.name, note = "")
            }
            planName = r.name
            stepIndex = -1
            baselineKph = 0.0
            inclineAuto = true
            lastInclineMoveAt = 0L

            workout = WORKOUT
            resetSession()
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            phaseEndsAt = 0L
            targetKph = 0.0
            targetGrade = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "warmup"
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "route: ${r.name}, ${"%.2f".format(r.distanceM / 1000)} km, " +
                    "${"%.0f".format(r.climbM)} m climb, ${r.segments.size} segments" +
                    if (loop) " (looped)" else "")
            pushPlan()
            repaint()
        }

        /** Straight to the workout proper, keeping whatever pace is set. */
        @JavascriptInterface fun skipWarmup() {
            if (session != Session.WARMUP) return
            session = Session.ACTIVE
            phaseEndsAt = 0L
            Log.i(TAG, "warm-up skipped")
            repaint()
        }

        /** Cool-down is the last thing standing between here and the summary. */
        @JavascriptInterface fun skipCooldown() {
            if (session != Session.COOLDOWN) return
            requestFinish()
            repaint()
        }

        /** Belt to a halt, timer held. The board's own Pause is the legal exit
         *  from Running — writing Idle while running is silently ignored. */
        @JavascriptInterface fun pause() {
            // Pressed again while the belt is still easing down: that is
            // somebody asking a second time, and the answer to asking twice is
            // the stop they would have got before any of this existed.
            if (session == Session.PAUSED && stopping) {
                stopping = false
                targetKph = 0.0
                pendingWrite = mapOf(
                    FitPro.Field.KPH to 0.0,
                    FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
                )
                Log.i(TAG, "stop pressed again — stopping the belt outright")
                repaint()
                return
            }
            if (Session.isMoving(session)) {
                accumulatedMs += SystemClock.elapsedRealtime() - activeSince
                session = Session.PAUSED
                phaseEndsAt = 0L
            }
            // Remember the pace so RESUME can climb back to it. Taken before the
            // wind-down starts eating it.
            if (targetKph > 0.0) pausedKph = targetKph
            rampTo = 0.0
            rampReason = ""
            // Above a walk the speed is shed first — see STOP_EASE_ABOVE_KPH.
            // The screen still answers the press immediately: the clock stops
            // here and the overlay goes up while the belt is still moving,
            // which is the right way round. A STOP that took two seconds to
            // acknowledge would read as a console that had missed it.
            if (beginWindDown(STOP_DECEL_KPH_PER_SEC, STOP_EASE_ABOVE_KPH, "stop pressed")) {
                repaint()
                return
            }
            targetKph = 0.0
            pendingWrite = mapOf(
                FitPro.Field.KPH to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
            )
            repaint()
        }

        /**
         * Pressing RESUME means "I want to keep going", so getting back to pace
         * shouldn't be a dozen taps. But it must not lurch either — the belt
         * starts from a standstill and climbs to the old speed at [RAMP_KPH_PER_SEC],
         * driven from the poll loop. Touching SPEED cancels it.
         */
        @JavascriptInterface fun resume() {
            if (dmk) return
            if (session != Session.PAUSED) return
            session = Session.ACTIVE
            activeSince = SystemClock.elapsedRealtime()
            // RESUME during a STOP's wind-down catches the belt where it is and
            // climbs from there. Zeroing the target would drop it to a
            // standstill first, which is a lurch in each direction for someone
            // who has changed their mind a second after pressing STOP.
            if (stopping) {
                stopping = false
                Log.i(TAG, "resumed mid wind-down at ${"%.1f".format(targetKph)} km/h")
            } else {
                targetKph = 0.0
            }
            rampTo = if (pausedKph > 0.0) pausedKph else cfg.warmupKph()
            rampReason = "resuming"
            pendingWrite = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble())
            Log.i(TAG, "resuming — ramping back to ${"%.1f".format(rampTo)} km/h")
            repaint()
        }

        /**
         * "End workout" doesn't stop dead — it eases down. The belt ramps back
         * to walking pace, the deck flattens, and the clock keeps running for
         * [COOLDOWN_MS]. SKIP goes straight to the summary.
         */
        @JavascriptInterface fun end() {
            if (session == Session.SUMMARY) return
            // Ending from a pause means the belt is already stopped. Running a
            // cool-down from there spins it back up to 2 km/h only to walk it
            // down again, which is disconcerting and faintly alarming — the
            // workout was over the moment STOP was pressed.
            if (session == Session.PAUSED) {
                Log.i(TAG, "ended from a pause — no cool-down, belt already stopping")
                // The plan is deliberately left standing: the summary is a
                // summary *of* it, and clearing it here is what used to put
                // "manual walk" at the top of a guided walk's recap. It goes on
                // the way out of the summary — see home().
                finishWorkout()
                repaint()
                return
            }
            val now = SystemClock.elapsedRealtime()
            // Coming out of a pause the clock is already stopped; restart it so
            // the cool-down is counted like any other part of the workout.
            if (!Session.isMoving(session)) activeSince = now
            session = Session.COOLDOWN
            phaseEndsAt = now + cfg.cooldownMs()
            pausedKph = 0.0
            rampTo = cfg.warmupKph()
            rampReason = "cooldown"
            // Put the machine back how you'd want to find it. Incline is the one
            // setting that persists otherwise, and starting the next walk on
            // yesterday's hill is a nasty surprise.
            targetGrade = 0.0
            pendingWrite = mapOf(
                FitPro.Field.GRADE to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.RUNNING.toDouble(),
            )
            Log.i(TAG, "cooling down for ${cfg.cooldownMs() / 1000}s")
            repaint()
        }

        /**
         * Summary → welcome.
         *
         * The belt is commanded to a stop here as well as at the end of the
         * workout. It should already be stopped, and on 31 July it was not —
         * the console went back to the welcome screen with the belt still
         * running. `enforceStopped` is the thing that actually guarantees it;
         * this is the cheap second attempt at the moment the screen changes.
         */
        @JavascriptInterface fun home() {
            session = Session.WELCOME
            workout = "none"
            summaryLine = ""
            summaryUrl = ""
            summarySpoken = false
            clearPlan()
            resetSession()
            // Leaving the summary ends any easing still in flight. The console
            // is about to say "welcome", and a belt still winding down behind
            // that screen is worse than a stop that nobody is standing on the
            // belt for. Taking the wind-down away also takes its tidy-up away,
            // so that is done here instead — otherwise leaving the summary in
            // the first second or two would strand the deck on a hill.
            val parked = stopping
            if (parked) {
                stopping = false
                Log.i(TAG, "left the summary mid wind-down — stopping the belt outright")
                parkDeckAndFan()
            }
            targetKph = 0.0
            pendingWrite = buildMap {
                put(FitPro.Field.KPH, 0.0)
                put(FitPro.Field.WORKOUT_MODE, FitPro.Mode.IDLE.toDouble())
                // Only when we are the ones levelling the deck. An incline set
                // by hand on the summary screen is a choice about the next
                // walk, and DONE is not the moment to throw it away.
                if (parked) put(FitPro.Field.GRADE, 0.0)
            }
            repaint()
        }

        @JavascriptInterface fun speed(delta: Double) {
            if (!Session.isMoving(session) || dmk) return
            rampTo = 0.0; rampReason = ""   // manual control abandons the ramp
            var next = targetKph + delta
            // Below the machine's minimum there is no "slow", only stopped —
            // so stepping down out of the bottom of the range means stop, and
            // stepping up out of zero jumps straight to the minimum.
            next = if (next < minKph) { if (delta > 0) minKph else 0.0 } else next
            targetKph = next.coerceIn(0.0, maxKph)
            pendingWrite = mapOf(FitPro.Field.KPH to targetKph)
        }

        /**
         * Take the coach's suggested pace, by tapping it.
         *
         * The suggestion was previously read-only — "try 6.8 km/h" and then you
         * held the + button until you got there. Accepting an offer should be
         * one tap.
         *
         * Still a suggestion: this only runs because somebody touched it. The
         * plan never sets the belt speed on its own, which is the rule the
         * whole guided walk is built on.
         */
        @JavascriptInterface fun setSpeed(kph: Double) {
            if (!Session.isMoving(session) || dmk) return
            rampTo = 0.0; rampReason = ""   // as with any manual change
            targetKph = if (kph < minKph) 0.0 else kph.coerceIn(0.0, maxKph)
            pendingWrite = mapOf(FitPro.Field.KPH to targetKph)
            Log.i(TAG, "pace: took the suggestion, ${"%.1f".format(targetKph)} km/h")
        }

        /** Incline has no interlock — the board services it in any state. */
        @JavascriptInterface fun incline(delta: Double) {
            // Touching the hill takes it back from the plan until the next
            // segment. Same rule as speed abandoning its ramp: the person on the
            // treadmill outranks the schedule.
            if (inclineAuto) {
                inclineAuto = false
                Log.i(TAG, "guided: incline taken over by hand for this segment")
            }
            // A hand on the incline outranks the levelling nag, exactly as it
            // outranks the plan. Otherwise setting a slope from the summary
            // screen would be undone a fifth of a second later.
            if (levelling) {
                levelling = false
                Log.i(TAG, "levelling abandoned — incline set by hand")
            }
            targetGrade = (targetGrade + delta).coerceIn(minGrade, maxGrade)
            pendingWrite = mapOf(FitPro.Field.GRADE to targetGrade)
        }

        /** Off → Low → Medium → High → Auto → Off */
        @JavascriptInterface fun fan() {
            fanState = (fanState + 1) % 5
            pendingWrite = mapOf(FitPro.Field.FAN_STATE to fanState.toDouble())
        }

        @JavascriptInterface fun setFan(level: Int) {
            fanState = level.coerceIn(0, 4)
            pendingWrite = mapOf(FitPro.Field.FAN_STATE to fanState.toDouble())
        }

        @JavascriptInterface fun setWalker(name: String) {
            walker = name
            refreshWalkerHrMax()
            Log.i(TAG, "walker: $name")
        }

        /** "I've put the key back." If it is still out, the next poll re-raises. */
        @JavascriptInterface fun ackDmk() {
            dmkLatched = false
            dmk = false
            repaint()
        }

        /**
         * Dismiss the coach: shut it up mid-sentence and drop anything still in
         * flight. Silencing the speaker is the point of the tap — clearing the
         * words while the audio played on would be the worst of both.
         */
        @JavascriptInterface fun hushCoach() {
            voice.stop()
            coach.hush()
        }

        /**
         * Switch to another interface.
         *
         * Reloads the WebView rather than swapping the DOM about. Simpler, and a
         * 2 GB device would much rather parse one document than hold five. The
         * session survives it untouched — Kotlin owns all of that and repaints
         * the new page at the next poll, 200 ms later — and [onPageFinished]
         * re-sends the plan, which is the one thing that goes over only once.
         *
         * Refused while the belt is moving. The reload takes a beat, and during
         * that beat there is no STOP button on a running treadmill.
         */
        @JavascriptInterface fun setUi(name: String) = switchUi(name)

        @JavascriptInterface fun currentUi(): String = chosenUi()

        /** Only the ones actually in this build, so the settings screen cannot
         *  offer a blank page. */
        @JavascriptInterface fun availableUis(): String =
            org.json.JSONArray(available()).toString()

        // --- settings -------------------------------------------------------
        //
        // The settings screen is one shared document (assets/ui/stride-settings.js)
        // rendered into whichever interface is up, so all of this is read and
        // written through here rather than through five copies of it.

        @JavascriptInterface fun settingsJson(): String = cfg.json().toString()

        /**
         * Who Home Assistant knows about, minus anyone already added here.
         *
         * Filtered on the console rather than in the settings screen so the
         * "already added" test uses the same `haPerson` link the rest of the
         * app does, instead of matching names in JavaScript.
         */
        @JavascriptInterface fun haPeople(): String {
            val linked = cfg.people().map { it.haPerson }.filter { it.isNotEmpty() }.toSet()
            val taken = cfg.people().map { it.name.lowercase() }.toSet()
            val out = JSONArray()
            try {
                val arr = JSONArray(haPersons)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("entity_id") in linked) continue
                    if (o.optString("first").lowercase() in taken) continue
                    out.put(o)
                }
            } catch (e: Exception) {
                Log.w(TAG, "persons: cannot parse (${e.message})")
            }
            return out.toString()
        }

        /** Everything arrives as a string; [Settings.set] types it by key. */
        @JavascriptInterface fun saveSetting(key: String, value: String) {
            cfg.set(key, value)
            Log.i(TAG, "settings: $key = ${if (key == Settings.MQTT_PASS) "***" else value}")
            applySetting(key)
        }

        @JavascriptInterface fun addPerson(name: String): String {
            cfg.addPerson(name)?.let { Log.i(TAG, "settings: added ${it.name} (${it.id})") }
            return cfg.json().toString()
        }

        /** Add somebody from Home Assistant, linked rather than merely named. */
        @JavascriptInterface fun addHaPerson(entityId: String, first: String): String {
            cfg.addPerson(first, entityId)?.let {
                Log.i(TAG, "settings: added ${it.name} linked to $entityId")
            }
            return cfg.json().toString()
        }

        @JavascriptInterface fun removePerson(name: String): String {
            cfg.savePeople(cfg.people().filterNot { it.name == name })
            // Whoever is mid-walk keeps walking; only the list changed.
            Log.i(TAG, "settings: removed $name")
            return cfg.json().toString()
        }

        @JavascriptInterface fun setPersonFlag(name: String, flag: String, on: Boolean): String {
            cfg.savePeople(cfg.people().map {
                if (it.name != name) it
                else if (flag == "coached") it.copy(coached = on) else it.copy(publish = on)
            })
            return cfg.json().toString()
        }

        /**
         * Set or clear somebody's age. Zero, or anything outside what
         * `220 - age` means anything for, clears it back to "has not said" —
         * which is a supported state and not an error. See Settings.Person.age.
         */
        @JavascriptInterface fun setPersonAge(name: String, age: Int): String {
            val clean = if (age in 13..100) age else 0
            cfg.savePeople(cfg.people().map {
                if (it.name == name) it.copy(age = clean) else it
            })
            // The walker may be the person just edited, and the zones the coach
            // uses come off a cached copy.
            refreshWalkerHrMax()
            Log.i(TAG, "settings: age for $name -> ${if (clean > 0) "$clean" else "not given"}")
            return cfg.json().toString()
        }

        // --- heart rate strap ---
        @JavascriptInterface fun hrScan() {
            // Asking is asynchronous: the dialog is still on screen when this
            // returns. Scanning through it is the documented way to get an
            // empty list that looks exactly like "no straps here", so the scan
            // waits for the answer — see onRequestPermissionsResult.
            if (ensureScanPermission()) strap.scan()
        }

        @JavascriptInterface fun hrFound(): String = JSONArray().apply {
            strap.found().forEach {
                put(JSONObject().put("address", it.address)
                    .put("name", it.name).put("rssi", it.rssi))
            }
        }.toString()

        @JavascriptInterface fun hrPair(address: String, name: String) {
            strap.stopScan()
            cfg.saveStrap(address, name)
            strap.connect(address)
            Log.i(TAG, "hr: pairing with $name")
        }

        @JavascriptInterface fun hrForget() {
            strap.disconnect()
            cfg.forgetStrap()
            Log.i(TAG, "hr: strap forgotten")
        }

        @JavascriptInterface fun hrStatus(): String = JSONObject()
            .put("available", strap.available)
            .put("scanning", strap.scanning)
            .put("connected", strap.connected)
            .put("bpm", strap.bpm())
            .put("battery", strap.battery)
            .put("name", cfg.hrName())
            .put("address", cfg.hrAddr())
            .toString()

        /**
         * Connect with whatever is currently saved and say what happened.
         *
         * Runs off the UI thread — Paho's connect blocks for up to the
         * connection timeout, and five seconds of frozen console is not a
         * diagnostic. The screen polls [mqttStatus] for the answer.
         */
        @JavascriptInterface fun mqttTest() {
            Thread {
                applyMqttSettings()
                Log.i(TAG, "settings: mqtt test — ${if (mqtt.connected) "connected" else "failed"}")
            }.start()
        }

        @JavascriptInterface fun mqttStatus(): String = JSONObject()
            .put("enabled", cfg.haEnabled())
            .put("connected", mqtt.connected)
            .put("broker", cfg.mqttHost())
            .toString()

        /** What the board says it can do. Reported, never set — see Settings. */
        @JavascriptInterface fun boardLimits(): String = JSONObject()
            .put("minGrade", minGrade).put("maxGrade", maxGrade)
            .put("minKph", minKph).put("maxKph", maxKph)
            .toString()

        @JavascriptInterface fun about(): String = JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("build", BuildConfig.VERSION_CODE)
            .put("board", "FitPro · device 0x%02x".format(deviceId))
            .put("android", android.os.Build.VERSION.RELEASE)
            .toString()

        @JavascriptInterface fun resetSettings(): String {
            cfg.resetSettings()
            applySetting("")
            Log.i(TAG, "settings: reset to defaults")
            return cfg.json().toString()
        }

        /** Screen sleep. The panel is the only light in the room at 6am. */
        @JavascriptInterface fun dim(on: Boolean) {
            runOnUiThread {
                window.attributes = window.attributes.apply {
                    screenBrightness = if (on) 0.02f else
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }

        /**
         * "awake" | "asleep" | "waking" | "unreachable".
         *
         * The sleep screen asks this on the tap that woke it, and keeps asking
         * while a wake is in flight. "awake" is the ordinary answer and means
         * the tap was the whole gesture — the question is only put when there
         * is something to ask about.
         */
        @JavascriptInterface fun wakeState(): String = this@MainActivity.wakeState()

        /** The second, deliberate half of the tap. See [wakeBoard]. */
        @JavascriptInterface fun wakeBoard() = this@MainActivity.wakeBoard()
    }

    /**
     * Which pulse to believe.
     *
     * The board's number comes from contact grips on machines that have them,
     * and is only there while both hands are on the bar — so it drops out on
     * exactly the hills where it is worth having. On a machine with no grips,
     * which is most of them now, it is zero for the whole walk. A strap does
     * not care what your arms are doing, and is the source that always works.
     *
     * Automatic prefers the strap and falls back, and the fallback is on the
     * strap's *reading* rather than its connection: a strap can be connected
     * and still reporting nothing, having been put on dry or slipped. Zero from
     * either source means "no reading", never "no pulse".
     */
    private fun pulseNow(fromBoard: Int): Int = when (cfg.hrSource()) {
        "grips" -> fromBoard
        "strap" -> strap.bpm()
        else -> strap.bpm().takeIf { it > 0 } ?: fromBoard
    }

    // --- applying settings ----------------------------------------------------

    /**
     * Bumped on every apply, so a retry still sleeping against the old broker
     * settings knows it has been superseded and stops.
     */
    private val mqttGen = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Connect, reconnect or stay down, according to what is configured.
     *
     * Called at start-up and whenever a broker setting changes. **A console
     * with no broker set is a working treadmill, not a broken one** — it does
     * not retry, does not warn, and does not log an error every poll. A
     * console with a broker set that could not be reached is a different
     * thing, and does keep trying: see [retryMqtt].
     */
    private fun applyMqttSettings() {
        val gen = mqttGen.incrementAndGet()
        if (!cfg.haEnabled()) {
            mqtt.close()
            Log.i(TAG, "mqtt: not configured, running standalone")
            return
        }
        mqtt.reconfigure(cfg.brokerUri(), cfg.mqttUser(), cfg.mqttPass(), cfg.mqttPrefix())
        // Re-registered because reconfigure() dropped the client, and a clean
        // session forgets subscriptions with it.
        mqtt.onMessage(mqtt.coachTopic, ::onCoachLine)
        mqtt.onMessage(mqtt.uiTopic, ::onUiCommand)
        mqtt.onMessage(mqtt.personsTopic, ::onPersons)
        mqtt.onMessage(mqtt.routesTopic, routes::accept)
        val err = mqtt.connect()
        if (err == null) {
            if (mqtt.connected) mqtt.publishUi(chosenUi())
            return
        }
        Log.w(TAG, "mqtt: $err")
        retryMqtt(gen)
    }

    /**
     * Keep trying until the broker answers, then hand over to Paho.
     *
     * **On its own thread, deliberately.** The start-up call to
     * [applyMqttSettings] is made from [pollLoop], and sleeping there would
     * stall telemetry, the coach and every board write behind a broker that
     * has nothing to do with any of them. A treadmill whose belt stops
     * responding because Home Assistant is down is a far worse machine than
     * one that walks fine without a coach.
     *
     * Stops at the first success rather than staying resident: from there
     * Paho's own `automaticReconnect` owns the connection, and it is better at
     * it than this is. The gap being filled is only the one Paho does not
     * cover — the first connection, which it never retries.
     */
    private fun retryMqtt(gen: Int) {
        Thread {
            var wait = MQTT_RETRY_MS
            while (gen == mqttGen.get() && running) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                // Settings changed, or HA was switched off, while we slept.
                if (gen != mqttGen.get()) return@Thread
                val err = mqtt.connect()
                if (err == null) {
                    Log.i(TAG, "mqtt: connected on retry")
                    if (mqtt.connected) mqtt.publishUi(chosenUi())
                    return@Thread
                }
                wait = (wait * 2).coerceAtMost(MQTT_RETRY_MAX_MS)
                Log.w(TAG, "mqtt: $err — trying again in ${wait / 1000}s")
            }
        }.start()
    }

    /**
     * React to one setting having changed, where it needs more than being
     * stored. Anything read at the point of use — warm-up length, incline rate,
     * units — needs nothing here and is deliberately absent.
     */
    private fun applySetting(key: String) {
        when (key) {
            Settings.HA_ENABLED, Settings.MQTT_HOST, Settings.MQTT_PORT,
            Settings.MQTT_USER, Settings.MQTT_PASS, Settings.MQTT_TLS,
            Settings.MQTT_PREFIX -> Thread { applyMqttSettings() }.start()

            Settings.BRIGHTNESS -> applyBrightness()

            Settings.HR_SOURCE, Settings.HR_ADDR -> applyStrap()

            // A reset changes everything at once.
            "" -> {
                Thread { applyMqttSettings() }.start()
                applyBrightness()
                applyStrap()
            }
        }
    }

    private fun applyBrightness() = runOnUiThread {
        window.attributes = window.attributes.apply { screenBrightness = cfg.brightness() }
    }

    /** Hold a connection only when a strap could actually be used. */
    private fun applyStrap() {
        if (cfg.hrSource() == "grips" || cfg.hrAddr().isBlank()) strap.disconnect()
        else strap.connect(cfg.hrAddr())
    }

    /**
     * A BLE scan on API 23–30 returns an empty list, not an error, without a
     * location permission. This app targets 28, so that is the rule that
     * applies — and an empty list is exactly what "no straps here" looks like,
     * which makes it the single most confusing way for this feature to fail.
     */
    private fun ensureScanPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return true
        val perm = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true
        }
        Log.i(TAG, "hr: asking for location permission so a BLE scan can return results")
        requestPermissions(arrayOf(perm), SCAN_PERMISSION)
        return false
    }

    /** The scan the user asked for, once they have answered the dialog. */
    override fun onRequestPermissionsResult(
        code: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != SCAN_PERMISSION) return
        val granted = results.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) strap.scan()
        else Log.w(TAG, "hr: location refused, a BLE scan will find nothing")
    }

    // --- which interface ------------------------------------------------------

    /** The UIs named in [UIS] that are actually in this APK. */
    private fun available(): List<String> {
        val present = try {
            assets.list("ui")?.toSet() ?: emptySet()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "ui: cannot list assets/ui (${e.message})")
            emptySet<String>()
        }
        return UIS.filter { present.contains("$it.html") }
    }

    /** Whatever was chosen last, if it still exists in this build. */
    private fun chosenUi(): String {
        val saved = prefs.getString(PREF_UI, DEFAULT_UI) ?: DEFAULT_UI
        return if (available().contains(saved)) saved else DEFAULT_UI
    }

    private fun assetFor(name: String) = "file:///android_asset/ui/$name.html"

    /** Both routes in — a tap on the settings screen and a publish from HA —
     *  land here, so neither can switch under conditions the other refuses. */
    private fun switchUi(name: String) {
        if (Session.isMoving(session)) {
            Log.i(TAG, "ui: not switching to $name while the belt is moving")
            return
        }
        if (!available().contains(name)) {
            Log.w(TAG, "ui: no such interface: $name")
            return
        }
        if (name == chosenUi()) return
        prefs.edit().putString(PREF_UI, name).apply()
        Log.i(TAG, "ui: switching to $name")
        mqtt.publishUi(name)
        runOnUiThread { web.loadUrl(assetFor(name)) }
    }

    /**
     * Home Assistant's people, retained on the broker by ha/stride_persons.py.
     *
     * Held rather than acted on. The console offers them under "Add from Home
     * Assistant" and somebody standing in front of it decides — a list arriving
     * over MQTT is not permission to create walkers.
     */
    /**
     * Routes from real walks. Unlike [haPersons] this is not a volatile field
     * holding whatever last arrived — it is cached to disk, because the console
     * can boot with no network and a route that is not already here cannot be
     * fetched at the moment somebody is standing on the belt choosing it.
     */
    private val routes by lazy { Routes(this) }

    /** Every walk this console has recorded — see History. */
    private val history by lazy { History(this) }

    /** The session's average pulse so far, or 0 if nothing ever read. */
    private fun avgPulseNow(): Int =
        if (pulseSamples > 0) (pulseSum / pulseSamples).toInt() else 0

    @Volatile private var haPersons: String = "[]"

    private fun onPersons(payload: String) {
        haPersons = payload.trim().ifBlank { "[]" }
        Log.i(TAG, "persons: received ${haPersons.length} bytes from HA")
    }

    /** A UI name published from Home Assistant. */
    private fun onUiCommand(payload: String) {
        val name = payload.trim().lowercase()
        Log.i(TAG, "ui: requested \"$name\" over mqtt")
        switchUi(name)
    }

    /**
     * Start easing the belt down, and say whether there was anything to ease.
     *
     * Both deliberate stops arm one of these: a workout ending, at
     * [DECEL_KPH_PER_SEC] all the way to a standstill, and the STOP button, at
     * [STOP_DECEL_KPH_PER_SEC] as far as [STOP_EASE_ABOVE_KPH]. False means the
     * belt is already at or below the floor, and the caller should command the
     * stop itself in the ordinary way.
     *
     * @param floor the speed to hand over to the machine's own stop at.
     */
    private fun beginWindDown(rate: Double, floor: Double, why: String): Boolean {
        // Nothing to wind down from — and a pulled safety key has already
        // stopped the belt more decisively than this ever could.
        if (targetKph <= floor || dmk) return false
        stopping = true
        stopRate = rate
        stopFloor = floor
        stopSince = SystemClock.elapsedRealtime()
        rampTo = 0.0
        rampReason = "stopping"
        // Whatever was queued is superseded: an incline nudge has nothing to say
        // to a belt that is stopping, and a write the board is refusing sits in
        // front of decelStep in the poll loop's order until it gives up on it —
        // seconds during which nothing would be taking speed off.
        pendingWrite = null
        Log.i(TAG, "$why — winding down from ${"%.1f".format(targetKph)} km/h " +
                "at ${"%.1f".format(rate)} km/h/s" +
                if (floor > 0.0) " to ${"%.1f".format(floor)} km/h" else "")
        return true
    }

    /**
     * Finish now, and let the belt catch up.
     *
     * Every automatic route to the summary comes through here — the plan
     * expiring, the cool-down expiring, SKIP — so none of them can drop the belt
     * out from under someone.
     *
     * The summary goes up at the moment of ending rather than when the belt
     * arrives at zero. Waiting meant the live screen stayed up through the
     * wind-down, and on a guided walk or a route that had just run out that
     * screen was the casual lap counter — a walk ending on the one display that
     * had nothing to do with it. Ending is a decision; the screen answers it
     * immediately and the belt eases down underneath.
     */
    private fun requestFinish() {
        beginWindDown(DECEL_KPH_PER_SEC, 0.0, "workout ended")
        // Reads the wind-down it just armed, and leaves the belt to it.
        finishWorkout()
    }

    /**
     * One tick of the wind-down, or null if nothing is stopping.
     *
     * Below the machine's minimum there is no "slow", only stopped, so the last
     * step goes straight to zero rather than trying to hold 1.5 km/h. Reaching
     * the floor commands the stop: a wind-down is only ever the approach to one.
     */

    private fun decelStep(): Map<FitPro.Field, Double>? {
        if (!stopping) return null
        val step = stopRate * POLL_MS / 1000.0
        targetKph = (targetKph - step).coerceAtLeast(0.0)
        if (targetKph <= stopFloor || targetKph < minKph) {
            stopping = false
            rampTo = 0.0
            rampReason = ""
            targetKph = 0.0
            Log.i(TAG, "wind-down complete — commanding stop")
            if (session == Session.SUMMARY) parkDeckAndFan()
            // Queued rather than returned directly: a one-shot write here can be
            // dropped by the board like any other frame, and nothing would ever
            // resend it — see the pendingWrite retry-until-landed logic in
            // pollLoop for why a stop command specifically must not be fire-and-forget.
            pendingWrite = mapOf(
                FitPro.Field.KPH to 0.0,
                FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
            )
            return null
        }
        return mapOf(FitPro.Field.KPH to targetKph)
    }
    
    /**
     * The belt must not be moving unless a workout is.
     *
     * This exists because it was not true. On 31 July a guided walk finished,
     * the console logged "belt stopped", showed the summary, and went back to
     * the welcome screen — while the belt kept running. It ran for another
     * thirty-five seconds until somebody hit the physical stop button.
     *
     * The stop was sent once, as a one-shot `pendingWrite`, and the log for
     * that exact moment shows `dropped frame (status:failed)`. One lost frame
     * and the command was simply gone. Nothing retried it and nothing checked,
     * because the code treated "I decremented my own target to zero" as
     * meaning the machine had stopped.
     *
     * So this asks the board what it is actually doing, every poll, and keeps
     * commanding a stop until it agrees. A treadmill running with nobody
     * driving it is the worst failure this project has, and one dropped USB
     * frame should not be able to cause it.
     *
     * It then spent a month unable to fire. What it asked the board was
     * ActualKph, which this board reports as 0.00 at every speed — the same
     * fact [beltSpeed] and [probeSpeed] are both written around — so the guard
     * returned at `actualKph < STOPPED_KPH` on every poll of every day. On
     * 2026-08-28 the belt was found running against the welcome screen with
     * twenty-three hours of log behind it and not one line from here.
     *
     * The lesson is narrower than "test the net": the net *was* reasoned about
     * carefully. It was wired to the one signal the codebase already knew was
     * dead, in a different file, in a comment written by the same hand. A
     * safety check has to be fed something proven to move — see [beltStillKph].
     */
    /**
     * The best evidence available that the belt is turning, km/h.
     *
     * Whichever source can see it: the board's own ActualKph if it ever starts
     * answering, and otherwise the odometer — see [idleBeltSpeed]. Taking the
     * larger means a broken field reading zero can no longer outvote a moving
     * belt, which is exactly how the net came to be dead.
     */
    private fun beltStillKph(): Double = maxOf(lastActualKph, idleBeltKph)

    private fun enforceStopped(actualKph: Double): Map<FitPro.Field, Double>? {
        if (Session.isMoving(session)) return null
        if (actualKph < STOPPED_KPH) return null
        // A wind-down *is* the stop being carried out, deliberately gradually —
        // slamming zero over it would undo the one thing it exists for. Bounded,
        // because this is the net and the net cannot be held off for ever: a
        // wind-down only ever takes speed off and reaches its floor in a few
        // seconds, so one still running after WIND_DOWN_MAX_MS has stuck.
        if (stopping) {
            if (SystemClock.elapsedRealtime() - stopSince < WIND_DOWN_MAX_MS) return null
            stopping = false
            Log.w(TAG, "wind-down still running after ${WIND_DOWN_MAX_MS / 1000}s " +
                    "— stopping the belt outright")
        }
        stopNags++
        runawayPeakKph = maxOf(runawayPeakKph, actualKph)
        if (stopNags == 1) {
            runawayAtWall = System.currentTimeMillis()
            runawayAtUptime = SystemClock.elapsedRealtime()
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(runawayAtWall))
            recordIncident("BELT RUNNING UNATTENDED at ${"%.1f".format(actualKph)} km/h " +
                    "(session=${Session.name(session)}, boardMode=$lastBoardMode) " +
                    "— commanding stop")
            mqtt.publishRunaway(true, actualKph, stamp, 0L)
        }
        if (stopNags == 1 || stopNags % 25 == 0) {
            Log.w(TAG, "belt still moving at ${"%.1f".format(actualKph)} km/h " +
                    "outside a workout — commanding stop again (attempt $stopNags)")
        }
        return mapOf(
            FitPro.Field.KPH to 0.0,
            FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
        )
    }

    /**
     * The deck must not be left tilted when the walk is over.
     *
     * `enforceStopped` exists because one dropped frame left the belt running.
     * This is the same failure for the other axis, and it went unnoticed
     * longer: every hand-authored template ends flat, so nothing was ever left
     * standing on a slope. A route can end anywhere — and a walk stopped early
     * ends wherever the ground happened to be, which on the first one tried was
     * a −3% descent.
     *
     * Only while `levelling`, so this can never fight somebody setting an
     * incline by hand from the welcome screen: the flag is set when a workout
     * ends and cleared the moment the deck is level or anything else takes
     * charge of the grade.
     */
    private fun enforceLevel(actualGrade: Double): Map<FitPro.Field, Double>? {
        if (!levelling) return null
        if (Session.isMoving(session)) { levelling = false; return null }
        if (Math.abs(actualGrade) < LEVEL_GRADE) { levelling = false; return null }
        levelNags++
        if (levelNags == 1 || levelNags % 25 == 0) {
            Log.w(TAG, "deck still at ${"%.1f".format(actualGrade)}% after the walk " +
                    "— commanding level again (attempt $levelNags)")
        }
        return mapOf(FitPro.Field.GRADE to 0.0)
    }

    /**
     * Turn the fan off after the walk, and keep asking until it is.
     *
     * The belt stops and the deck levels, both of them enforced rather than
     * asked once — and the fan was left out of that. It ran on after a walk
     * ended on 2026-08-07. It is not a safety interlock like the belt, but it
     * is the same promise: what the walk turned on, the end of the walk turns
     * off, and it is *seen* to turn off.
     *
     * Gives up after [FAN_OFF_NAGS] rather than nagging for ever, because on a
     * board that does not report FAN_STATE there is nothing to confirm against
     * and a silent forever-loop of writes is its own bug.
     */
    private fun enforceFanOff(): Map<FitPro.Field, Double>? {
        if (!fanStopping) return null
        if (Session.isMoving(session)) { fanStopping = false; return null }
        // Confirmed off by the board, where the board will say.
        if (fanReadable == true && fanState == 0) {
            fanStopping = false
            Log.i(TAG, "fan: confirmed off")
            return null
        }
        fanOffNags++
        if (fanOffNags > FAN_OFF_NAGS) {
            fanStopping = false
            // Not readable means not confirmable — say so rather than claim it.
            if (fanReadable == true) Log.w(TAG, "fan: still on after $fanOffNags attempts")
            else Log.i(TAG, "fan: off commanded (board does not report fan state)")
            return null
        }
        fanState = 0
        return mapOf(FitPro.Field.FAN_STATE to 0.0)
    }

    /**
     * Show the recap and put the machine back how it should be found.
     *
     * A wind-down still in flight owns the board, and this hands it everything:
     * no speed write, no deck write, no levelling nag. Those all outrank
     * [decelStep] in the poll loop's order, and a belt still at running pace
     * must not queue behind a deck that moves one percent every three seconds.
     * The wind-down does the tidying when it lands — see [parkDeckAndFan].
     *
     * The numbers are those of the moment of ending, not of the moment the belt
     * finally rests. A summary whose distance kept climbing while it was being
     * read would be a stranger thing than a couple of coasting metres missing
     * from it.
     */
    private fun finishWorkout() {
        if (Session.isMoving(session)) {
            accumulatedMs += SystemClock.elapsedRealtime() - activeSince
        }
        session = Session.SUMMARY
        phaseEndsAt = 0L
        rampTo = 0.0
        pausedKph = 0.0
        Log.i(TAG, "workout ended: ${"%.0f".format(sessionDistance)} m in " +
                "${accumulatedMs / 1000} s, ${"%.0f".format(sessionCalories)} kcal" +
                if (pulseSamples > 0) ", hr ${avgPulseNow()}/${maxPulse}" else "")
        // Recorded before the screen draws, so the summary can show what this
        // walk beat. Guests are summarised and forgotten — see History.record.
        earned = history.record(History.Walk(
            who = walker,
            startedAt = sessionStartWall(),
            elapsed = accumulatedMs / 1000.0,
            distance = sessionDistance,
            calories = sessionCalories,
            climbM = climbM,
            avgSpeed = avgPace(),
            maxSpeed = maxSpeed,
            maxIncline = maxIncline,
            avgPulse = avgPulseNow(),
            maxPulse = maxPulse,
            plan = planName,
        ))
        if (earned.isNotEmpty()) Log.i(TAG, "achievements: ${earned.joinToString("; ")}")
        if (stopping) return          // the wind-down parks the machine after it
        rampReason = ""
        targetKph = 0.0
        parkDeckAndFan()
        // The belt goes in the same write. This is the one path where no
        // wind-down has commanded the stop already, so it must be commanded
        // here — and it must not be left out of the write parkDeckAndFan
        // queued, which would drop it.
        pendingWrite = mapOf(
            FitPro.Field.KPH to 0.0,
            FitPro.Field.GRADE to 0.0,
            FitPro.Field.WORKOUT_MODE to FitPro.Mode.PAUSE.toDouble(),
        )
    }

    /**
     * Put the deck back to level and the fan off, and keep asking until the
     * board agrees on both — [enforceLevel] and [enforceFanOff] do the asking.
     *
     * Split out of [finishWorkout] because it does not always run there: a
     * workout can end while the belt is still winding down, and everything in
     * here has to wait until it has stopped.
     */
    private fun parkDeckAndFan() {
        targetGrade = 0.0
        pendingWrite = mapOf(FitPro.Field.GRADE to 0.0)
        levelling = true
        levelNags = 0
        // The fan goes off with everything else. It was running on after the
        // walk ended because nothing here ever told it to stop.
        fanStopping = true
        fanOffNags = 0
    }

    /** The segment after [idx], wrapping on a circuit and null at the end. */
    private fun nextStep(idx: Int): Plan.Step? {
        val steps = planSteps
        if (steps.isEmpty() || idx < 0) return null
        if (idx + 1 < steps.size) return steps[idx + 1]
        return if (planLoops) steps.firstOrNull() else null
    }

    private fun clearPlan() {
        route = null
        planName = ""
        planSteps = emptyList()
        stepIndex = -1
        baselineKph = 0.0
        inclineAuto = false
        planLoops = false
        planLap = 1
        planElapsed = 0.0
    }

    /** The segment the walk is currently in, or null on a casual walk. */
    private fun currentStep(): Plan.Step? {
        if (planSteps.isEmpty()) return null
        return planSteps.getOrNull(stepIndex.coerceAtLeast(0))
    }

    /**
     * Run the guided walk: advance the segment, then nudge the deck towards
     * whatever that segment asks for.
     *
     * Plan time is *session* time, which already excludes anything spent
     * paused — a walk interrupted for two minutes should still be the walk that
     * was chosen, not two minutes shorter.
     */
    /**
     * Run a route: the same job as [planTick], driven by **distance travelled**
     * rather than by the clock.
     *
     * This is the one substantive difference between a route and a template,
     * and it is not a detail. A route was recorded outdoors, where the hill
     * arrived at a certain point on the ground. Replay it on a timer while
     * walking slower indoors than out and the hill arrives late, the walk ends
     * before the route does, and the summit — the reason for choosing it — is
     * never reached. Driven by distance, the ground arrives where it did
     * outdoors however fast it is taken.
     */
    private fun routeTick(metres: Double) {
        val r = route ?: return

        // The HUD's position marker reads planElapsed against the plan steps,
        // and a route's steps are in metres — so this is metres too. Without
        // it the walker sat at the start line for the whole walk while the
        // ground moved under them, which is what the first route showed.
        planElapsed = metres

        if (metres >= r.distanceM) {
            if (!stopping) {
                Log.i(TAG, "route: ${r.name} complete at ${"%.0f".format(metres)} m")
                // The route is *not* cleared here, for the same reason a
                // template is not — see planTick. Clearing it dropped the guided
                // view, and what the console fell back to was the casual lap
                // counter, which is where a finished route used to spend its
                // last few seconds. It goes on the way out of the summary.
                requestFinish()
            }
            return
        }

        val idx = r.segments.indexOfFirst { metres < it.endM }.coerceAtLeast(0)
        if (idx != stepIndex) {
            if (stepIndex == 0 && baselineKph <= 0.0) {
                baselineKph = if (targetKph > 0.0) targetKph else cfg.warmupKph()
                Log.i(TAG, "route: baseline pace ${"%.1f".format(baselineKph)} km/h")
            }
            stepIndex = idx
            inclineAuto = true
            Log.i(TAG, "route: segment ${idx + 1}/${r.segments.size} at " +
                    "${"%.0f".format(metres)} m — ${"%.1f".format(r.segments[idx].incline)}%")
        }
        driveIncline(r.inclineAt(metres))
    }

    private fun planTick(elapsed: Double) {
        val steps = planSteps
        if (steps.isEmpty()) return
        val lap = steps.last().endSec

        if (planLoops) {
            // Round again. The hills are the same hills — that is the point of
            // a circuit — so nothing is regenerated and nothing ends the walk
            // but STOP.
            if (lap > 0) {
                val was = planLap
                planLap = (elapsed / lap).toInt() + 1
                planElapsed = elapsed % lap
                if (planLap != was) Log.i(TAG, "guided: lap $planLap of the circuit")
            }
        } else {
            planElapsed = elapsed
            // The whole plan is done: stop here rather than running the belt on.
            if (elapsed >= lap) {
                if (!stopping) {
                    Log.i(TAG, "guided: plan complete — winding the belt down")
                    // The plan is deliberately *not* cleared here. Clearing it
                    // drops the guided view and the console falls back to the
                    // casual oval; it is also what the summary reads its name
                    // off, so a guided walk would be recapped as a manual one.
                    // It is cleared on the way out of the summary instead.
                    requestFinish()
                }
                return
            }
        }

        val idx = steps.indexOfFirst { planElapsed < it.endSec }.coerceAtLeast(0)
        if (idx != stepIndex) {
            val step = steps[idx]
            // The opening segment is where he chooses a pace; everything after
            // is suggested relative to it.
            if (stepIndex == 0 && baselineKph <= 0.0) {
                baselineKph = if (targetKph > 0.0) targetKph else cfg.warmupKph()
                Log.i(TAG, "guided: baseline pace ${"%.1f".format(baselineKph)} km/h")
            }
            stepIndex = idx
            // A new segment hands the deck back, whatever happened in the last one.
            inclineAuto = true
            Log.i(TAG, "guided: segment ${idx + 1}/${steps.size} — ${step.label}, " +
                    "${"%.1f".format(step.incline)}%")
        }

        val step = steps.getOrNull(stepIndex) ?: return
        driveIncline(step.incline)
    }

    /**
     * Move the deck towards the segment's target, slowly.
     *
     * Only ever while the belt is genuinely moving and under the plan's control:
     * a paused walk, a pulled safety key or a manual override all stop the hill
     * dead where it is.
     */
    private fun driveIncline(target: Double) {
        if (!inclineAuto || dmk || session != Session.ACTIVE) return
        if (pendingWrite != null) return          // don't stamp on a queued write

        val goal = target.coerceIn(minGrade, maxGrade)
        if (Math.abs(targetGrade - goal) < 0.05) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastInclineMoveAt < cfg.inclineEveryMs()) return
        lastInclineMoveAt = now

        val next = if (targetGrade < goal) {
            (targetGrade + cfg.inclineStep()).coerceAtMost(goal)
        } else {
            (targetGrade - cfg.inclineStep()).coerceAtLeast(goal)
        }
        targetGrade = next.coerceIn(minGrade, maxGrade)
        pendingWrite = mapOf(FitPro.Field.GRADE to targetGrade)
        Log.i(TAG, "guided: incline -> ${"%.1f".format(targetGrade)}% (goal ${"%.1f".format(goal)}%)")
    }

    /** Warm-up rolls into the workout; cool-down rolls into the summary. */
    private fun advancePhase() {
        if (phaseEndsAt == 0L || SystemClock.elapsedRealtime() < phaseEndsAt) return
        when (session) {
            Session.WARMUP -> {
                session = Session.ACTIVE
                phaseEndsAt = 0L
                Log.i(TAG, "warm-up complete")
            }
            Session.COOLDOWN -> requestFinish()
        }
    }

    private fun phaseLeftSec(): Double {
        if (phaseEndsAt == 0L) return 0.0
        return ((phaseEndsAt - SystemClock.elapsedRealtime()) / 1000.0).coerceAtLeast(0.0)
    }

    private fun resetSession() {
        accumulatedMs = 0L
        activeSince = SystemClock.elapsedRealtime()
        armBaseline = true
        sessionDistance = 0.0
        sessionCalories = 0.0
        maxSpeed = 0.0
        maxIncline = 0.0
        speedSum = 0.0
        inclineSum = 0.0
        samples = 0
        maxPulse = 0
        pulseSum = 0L
        pulseSamples = 0
        zoneSecs.fill(0.0)
        lastZoneAt = 0L
        climbM = 0.0
        lastClimbMetres = 0.0
        earned = emptyList()
        sessionStartedAt = System.currentTimeMillis()
        sessionStartedUptime = SystemClock.elapsedRealtime()
    }

    /**
     * When this session actually began, in wall-clock time — or 0 if unknowable.
     *
     * This board boots at 2010-01-01 and only jumps to the real time once the
     * network is up, which takes five to ten minutes. Start a walk inside that
     * window and it is stamped 2010, which is what happened to three of the
     * first five walks this console ever recorded: two of them are dated
     * 2010-01-01 10:30 and 10:31, minutes apart, on days that were weeks apart.
     *
     * The correction is arithmetic rather than a guess. Two clocks started
     * together at [resetSession]: the wall clock, which NTP can yank, and
     * [SystemClock.elapsedRealtime], which it cannot. If the wall clock has
     * advanced by more than the monotonic clock says has actually passed, it
     * was corrected mid-walk — and *now* is trustworthy, so the true start is
     * simply now minus however long the walk has really been running. A walk
     * that began before NTP and ended after it dates itself exactly. Since
     * walks run 25-40 minutes and NTP lands in 5-10, that is the usual case.
     *
     * The remaining case is a walk that both starts and ends before the network
     * arrives. Nothing can date that one, so it returns 0 — "no date" — rather
     * than 2010. History treats 0 as undateable and leaves it out of streaks
     * and weekly counts instead of quietly filing it under a day in 2010 and
     * reporting a number that is wrong. Same rule as everywhere else here:
     * withhold rather than caption.
     */
    private fun sessionStartWall(): Long {
        if (sessionStartedAt <= 0L || sessionStartedUptime <= 0L) return 0L
        val ran = SystemClock.elapsedRealtime() - sessionStartedUptime
        val now = System.currentTimeMillis()
        val drifted = Math.abs(now - (sessionStartedAt + ran)) > CLOCK_JUMP_MS
        val started = if (drifted) now - ran else sessionStartedAt
        return if (started < SANE_EPOCH_MS) 0L else started
    }

    private fun elapsedSec(): Double {
        val live = if (Session.isMoving(session))
            SystemClock.elapsedRealtime() - activeSince else 0L
        return (accumulatedMs + live) / 1000.0
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lets the HUD be inspected and driven from a laptop over adb, so
        // layout can be checked against fake state without arming the belt.
        //
        // Debug builds only. The DevTools socket this opens will execute
        // anything in the page's context — including the `Stride` bridge,
        // which is to say the belt — for whoever can reach it. That is a fair
        // trade on a bench and no trade at all on a treadmill in a hallway.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        cfg.seedFromBuildConfig()
        walker = cfg.defaultWalker()
        refreshWalkerHrMax()
        applyBrightness()
        applyStrap()

        val ui = chosenUi()
        Log.i(TAG, "ui: $ui (available: ${available().joinToString(", ")})")

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            /* The launch screen's field colour, not Original's navy. The
               WebView paints this before the document does, so it is the one
               frame between the splash and the UI — matching it makes the
               handover invisible. Navy was only ever right for one of the five
               interfaces. */
            setBackgroundColor(0xFF100F0E.toInt())
            addJavascriptInterface(Bridge(), "Stride")
            /* A UI switch reloads the page, and the plan is the one thing sent
               once rather than at poll rate — so it has to go over again or the
               new document draws a guided walk with no ground ahead of it. */
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (planSteps.isNotEmpty()) pushPlan()
                }
            }
            loadUrl(assetFor(ui))
        }
        setContentView(web)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }

        acquireBoard()
    }

    /**
     * Wait for the control board to show up on the USB bus.
     *
     * It is not always there: it has been seen to drop off the bus entirely and
     * only re-enumerate after a console reboot. Failing once and giving up left
     * the HUD looking alive but permanently disconnected, so keep looking.
     */
    private fun acquireBoard() {
        thread(name = "usb-wait") {
            var announced = false
            while (running.not() && !isFinishing) {
                val device = conn.findDevice()
                if (device == null) {
                    if (!announced) {
                        Log.e(TAG, "control board not on the USB bus — waiting. " +
                                "(is com.ifit.eru enabled again? does the console need a reboot?)")
                        announced = true
                    }
                    Thread.sleep(5000)
                    continue
                }
                if (conn.hasPermission(device)) {
                    runOnUiThread { start(device) }
                    return@thread
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
                )
                runOnUiThread {
                    (getSystemService(Context.USB_SERVICE) as UsbManager)
                        .requestPermission(device, pi)
                }
                return@thread
            }
        }
    }

    private fun start(device: UsbDevice) {
        val err = conn.open(device)
        if (err != null) { Log.e(TAG, "open failed: $err"); return }
        running = true
        thread(name = "fitpro-poll") { pollLoop() }
    }

    /**
     * A coach line has come back from Home Assistant: show it, speak it.
     *
     * Re-serialised through JSONObject rather than injected raw — a malformed
     * payload should produce nothing, not a syntax error that takes the whole
     * page's script down with it.
     */
    private fun onCoachLine(payload: String) {
        val obj = org.json.JSONObject(payload)
        val line = obj.optString("line").trim()
        if (line.isEmpty()) return
        val safe = org.json.JSONObject()
            .put("line", line)
            .put("kind", obj.optString("kind"))
            .toString()
        // The round trip, every time. The fallback firing is the only symptom
        // slow coaching has, and it looks identical whether Home Assistant took
        // nine seconds or never answered at all. `grep "round trip"` over a
        // walk's logcat says which, and how close to the wire the good ones are.
        val took = coach.heard(line)
        if (took >= 0) Log.i(TAG, "coach: round trip ${took} ms — \"$line\"")
        else Log.i(TAG, "coach: \"$line\" (nothing was waiting on it)")
        if (obj.optString("kind") == "summary") {
            // Held, both the words and the audio. This was asked for at 98% of
            // the plan, so it arrives while there is still walking to do —
            // showing or speaking it then would be the opposite of the point.
            // `speakSummary` releases it once the summary is actually up.
            summaryLine = line
            summaryUrl = obj.optString("url")
            Log.i(TAG, "coach: closing line ready" +
                    if (summaryUrl.isEmpty()) " (no audio)" else "")
            return
        }
        showCoach(safe)
        obj.optString("url").takeIf { it.isNotEmpty() }?.let { voice.play(it) }
    }

    /**
     * Say the closing line, once, and only while the summary is on screen.
     *
     * Waiting for the screen rather than firing on arrival: the line is asked
     * for at 98% of the plan and can come back with a minute of walking still
     * to go. Reading somebody their summary while they are still on the belt
     * would be worse than silence.
     *
     * Polled rather than triggered from `finishWorkout`, because the answer may
     * not have arrived yet when the belt stops — this catches it whichever way
     * round they happen.
     *
     * Latched on the *result*, not the attempt. `play` drops a line when
     * something else is still speaking, so latching on the call would silently
     * lose the closing line whenever the last segment's coaching ran long.
     */
    private fun speakSummary() {
        if (session != Session.SUMMARY || summarySpoken) return
        if (summaryUrl.isEmpty()) return
        if (voice.play(summaryUrl)) {
            summarySpoken = true
            Log.i(TAG, "coach: speaking the closing line")
        }
    }

    private fun showCoach(json: String) {
        runOnUiThread { web.evaluateJavascript("window.coach($json)", null) }
    }

    /**
     * One frame through the coach: emit a moment if there is one, and fall back
     * to the canned line if Home Assistant does not answer in time.
     *
     * The workout never waits on any of this — a moment that cannot be sent is
     * simply lost, which is the correct outcome for a thing that was only ever
     * going to be a sentence.
     */
    private fun coachTick(snap: Snapshot) {
        coach.observe(snap)?.let { moment ->
            Log.i(TAG, "coach moment: ${moment.kind} — ${moment.detail}")
            // The live-coach prompt in Home Assistant carries one person's
            // weight, blood pressure and target. Sending somebody else's walk
            // to it would put those numbers in front of whoever is on the belt,
            // so this has always been gated — but it was gated on the walker
            // being *named Sam*, which meant a second person in the same
            // house silently got a different product and no way to change it.
            //
            // It is now a property of the person, set in Settings, and an
            // unknown walker gets nothing rather than falling through to "not
            // Sam". Everyone else still gets the local canned lines, which
            // are generic and name nobody.
            //
            // Whether the question actually left the building decides how long
            // it is worth waiting for an answer. If it did not — no broker, or
            // a walker with no coaching set up — there is nothing in flight and
            // the eight-second wait is eight seconds of silence for nothing.
            // Take the local line now.
            val asked = if (cfg.coachedFor(walker)) {
                mqtt.publishEvent(coach.payload(moment, snap))
                mqtt.connected
            } else {
                Log.i(TAG, "coach: $walker is not set up for coaching, keeping it local")
                false
            }
            if (!asked) coach.giveUp()?.let { showLocalCoach(it, "nobody to ask") }
        }
        coach.timedOut()?.let {
            showLocalCoach(it, "no answer from HA in ${Coach.FALLBACK_MS / 1000}s")
        }
    }

    /**
     * A line the console wrote itself.
     *
     * The closing line is held rather than shown, exactly as it is when Home
     * Assistant answers — it is asked for at 98% of the plan, so a walk with no
     * coaching used to put "That is another one done" over the belt with a
     * minute still to walk, and then show a blank summary.
     */
    private fun showLocalCoach(local: Coach.Local, why: String) {
        Log.i(TAG, "coach: $why, using \"${local.text}\"")
        if (local.kind == "summary") {
            summaryLine = local.text
            return
        }
        showCoach(org.json.JSONObject()
            .put("line", local.text).put("kind", "local").toString())
    }

    private fun pollLoop() {
        if (!resolveDeviceId()) { Log.e(TAG, "board did not answer on 4 or 2"); return }
        // Before anything else. A locked board refuses reads too, so limits,
        // telemetry and every workout command all depend on this.
        unlockWithRetries()
        readLimits()
        readSleepConfig()
        mqtt.onMessage(mqtt.coachTopic, ::onCoachLine)
        mqtt.onMessage(mqtt.uiTopic, ::onUiCommand)
        mqtt.onMessage(mqtt.personsTopic, ::onPersons)
        mqtt.onMessage(mqtt.routesTopic, routes::accept)
        applyMqttSettings()
        mqtt.publishUi(chosenUi())

        var sinceMqtt = 0L
        var rejects = 0
        while (running) {
            // enforceStopped comes first and outranks everything, including a
            // queued write: nothing is more important than a belt that should
            // not be moving.
            /* A mode change travels alone.
             *
             * The board rejects the *whole* frame if any one field in it is
             * invalid, and WORKOUT_MODE is the field most likely to be: it is
             * a state machine, and a transition the board does not accept from
             * where it currently is fails the speed write riding alongside it.
             * On 2026-08-07 starting a run against a board wedged in
             * WorkoutMode 2 refused {KPH, WORKOUT_MODE} together, over and
             * over, and the console cycled start → refuse → end → start.
             *
             * Sending the mode by itself also gets the order right, which
             * matters on this hardware: FitPro.Mode notes that the belt will
             * not act on a speed write while the console is IDLE. Mode first,
             * then the speed it enables — and if the mode is refused, the
             * speed is still queued rather than lost with it. */
            var queued = pendingWrite
            var rest: Map<FitPro.Field, Double>? = null
            if (queued != null && queued.size > 1 &&
                queued.containsKey(FitPro.Field.WORKOUT_MODE)) {
                rest = queued.filterKeys { it != FitPro.Field.WORKOUT_MODE }
                queued = mapOf(FitPro.Field.WORKOUT_MODE to
                               queued.getValue(FitPro.Field.WORKOUT_MODE))
            }

            val writes = enforceStopped(beltStillKph())
                ?: enforceLevel(lastActualGrade)
                ?: enforceFanOff()
                ?: queued ?: decelStep() ?: rampStep()

            if (writes != null) lastWriteMs = SystemClock.elapsedRealtime()
            val reply = conn.exchange(FitPro.readWrite(deviceId, READS, writes ?: emptyMap()))
            if (reply == null) {
                // Same rule as the reject path below: a board that has stopped
                // answering is a reason to say so, not a reason to stop
                // drawing. This one used to fall straight through to the sleep
                // and push nothing at all, which froze the screen for as long
                // as the USB read kept timing out — a second per attempt.
                repaint(boardOk = false)
                // Silence is two different things wearing the same face. The
                // machine asleep still owns its USB endpoint and is woken
                // deliberately — see [wakeBoard], which is the person's call,
                // not ours. But the board has also been seen to leave the bus
                // outright, and that one nothing but a fresh connection fixes;
                // before this, the poll loop read into a closed pipe for ever
                // and the only cure was the switch on the wall.
                val quiet = SystemClock.elapsedRealtime() - lastReplyMs
                if (quiet > SILENCE_MS &&
                    SystemClock.elapsedRealtime() - lastReacquireMs > REACQUIRE_MS) {
                    reacquire()
                }
                Thread.sleep(POLL_MS)
                continue
            }
            lastReplyMs = SystemClock.elapsedRealtime()

            // The board asking to be unlocked is not a dropped frame — it is a
            // request, and the only correct answer is to authenticate again.
            // Ignoring it costs everything: reads are refused too, so the HUD
            // goes quiet, every workout command is silently rejected, and the
            // console can still navigate while being unable to act.
            if (FitPro.isSecurityBlock(reply)) {
                boardLocked = true
                if (unlockAttempts == 0 || SystemClock.elapsedRealtime() - lastUnlockMs > UNLOCK_RETRY_MS) {
                    unlockAttempts++
                    lastUnlockMs = SystemClock.elapsedRealtime()
                    Log.w(TAG, "board locked (attempt $unlockAttempts) — authenticating again")
                    if (unlockWithRetries()) {
                        rejects = 0
                        unlockAttempts = 0
                    }
                }
                Thread.sleep(POLL_MS)
                continue
            }

            val why = FitPro.rejectReason(reply, READS)
            if (why != null) {
                // Log the first few and then every hundredth — a persistent
                // reject reason is a real signal, a lone one is just noise.
                if (rejects < 5 || rejects % 100 == 0) {
                    Log.w(TAG, "dropped frame ($why): ${FitPro.hex(reply)}")
                }
                rejects++

                // A write the board will never accept must not be retried for
                // ever. pendingWrite is deliberately kept until a frame lands,
                // so one dropped frame cannot spend a command — but if the
                // board is refusing this particular write, that same rule turns
                // into an infinite retry that blocks everything queued behind
                // it. On 2026-08-07 finishWorkout's {KPH 0, GRADE 0, PAUSE} was
                // refused the instant the deck finished levelling, and the
                // console spent twelve minutes retrying it at 5 Hz.
                if (queued != null && writes === queued) {
                    writeTries++
                    if (writeTries > WRITE_TRIES) {
                        Log.w(TAG, "giving up on a write the board keeps refusing ($why): " +
                                   queued.keys.joinToString { it.name })
                        // A refused mode change must not take the rest of the
                        // command down with it. The speed still wants sending.
                        pendingWrite = rest
                        writeTries = 0
                    }
                }

                // Keep the screen alive. The HUD used to repaint only after a
                // good frame, so a board that refused everything froze the
                // console mid-render — buttons appeared dead, and the last
                // thing painted stayed up. The board being unhappy is worth
                // saying out loud; it is not a reason to stop drawing.
                repaint(boardOk = false)

                Thread.sleep(POLL_MS)
                continue
            }
            boardLocked = false
            writeTries = 0

            // Only now is the queued write known to have landed. Clearing it
            // before the exchange is what lost the deck-levelling command on
            // 31 July: finishWorkout queued {KPH 0, GRADE 0, PAUSE}, the very
            // next frame came back `status:failed`, and the write was already
            // gone — so the belt stopped (the decel ramp had it) and the deck
            // stayed at -3% until somebody noticed. One dropped USB frame
            // should not be able to spend a command.
            // When a mode change was split off, landing it promotes the rest of
            // the command rather than clearing the queue — the speed that was
            // asked for alongside it still has to be sent, on the very next
            // poll, now that the board is in a state that will accept it.
            if (queued != null && writes === queued) pendingWrite = rest

            val v = FitPro.parse(reply, READS).filter { (f, value) ->
                // Reject anything outside what the machine says it can do.
                when (f) {
                    FitPro.Field.ACTUAL_KPH, FitPro.Field.KPH ->
                        value >= 0.0 && value <= maxKph + 0.5
                    FitPro.Field.ACTUAL_INCLINE, FitPro.Field.GRADE ->
                        value >= minGrade - 0.5 && value <= maxGrade + 0.5
                    else -> true
                }
            }
            if (v.isEmpty()) { Thread.sleep(POLL_MS); continue }

            // Follow the board rather than our own idea of the target —
            // physical button presses never reach this app.
            //
            // But not straight away. A write takes a few polls to appear in the
            // board's own KPH field, and adopting the echo before it lands puts
            // the old value back: press + at 6.4, we write 6.5, the next frame
            // still says 6.4, and the target snaps back. Press again and the
            // step is added to a stale number, so the display dances and settles
            // somewhere that was never asked for — 6.4 + 0.1 arriving at 6.6 by
            // way of 5.2, which is the board's ramp being echoed into a target.
            //
            // So the board only gets to speak once ours has had time to land.
            // Physical presses still come through: they are not writes of ours,
            // so nothing is holding the window open.
            if (writes == null && SystemClock.elapsedRealtime() - lastWriteMs > FOLLOW_SETTLE_MS) {
                v[FitPro.Field.KPH]?.let { targetKph = quantise(it, KPH_STEP) }
                v[FitPro.Field.GRADE]?.let { targetGrade = it }
            }

            val snap = accumulate(v)
            lastSnap = snap
            watchSlip(snap)
            ftms.update(snap)
            // Through repaint() rather than push(), so the session stamped on
            // the frame is the one that is true at the moment it is handed to
            // the page. A press lands on a WebView thread and can arrive
            // between accumulate() and here; pushing `snap` straight out would
            // then put the screen the person just left back for a fifth of a
            // second. One path, one answer to "which screen is this".
            repaint()
            coachTick(snap)
            speakSummary()

            sinceFan += POLL_MS
            if (sinceFan >= FAN_EVERY_MS) { sinceFan = 0; readFan() }

            sinceMqtt += POLL_MS
            if (sinceMqtt >= MQTT_EVERY_MS) {
                sinceMqtt = 0
                mqtt.publishState(snap)
                // Only while there is a workout to attribute. Publishing on the
                // welcome screen would zero whoever walked last.
                if (Session.isMoving(session) || session == Session.SUMMARY) {
                    cfg.person(walker)?.takeIf { it.publish }
                        ?.let { mqtt.publishPerson(it, snap) }
                }
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * One tick of the resume ramp, or null if there's nothing to wind up.
     *
     * The first step goes straight to the machine's minimum — below 1.6 km/h
     * this treadmill has no "slow", only stopped — and from there it climbs at
     * a walking-pace-per-second until it reaches where you left off.
     */
    private fun rampStep(): Map<FitPro.Field, Double>? {
        val goal = rampTo
        if (goal <= 0.0 || !Session.isMoving(session) || dmk) return null

        val step = RAMP_KPH_PER_SEC * POLL_MS / 1000.0
        val done: Boolean
        if (targetKph < goal) {
            // Below the machine's minimum there is no "slow", only stopped, so
            // the first step off zero jumps straight to it.
            targetKph = (if (targetKph < minKph) minKph else targetKph + step).coerceAtMost(goal)
            done = targetKph >= goal
        } else {
            // Ramping down — cool-down easing back from whatever pace was set.
            targetKph = (targetKph - step).coerceAtLeast(goal)
            done = targetKph <= goal
        }
        if (done) {
            rampTo = 0.0
            rampReason = ""
            Log.i(TAG, "ramp complete at ${"%.1f".format(targetKph)} km/h")
        }
        return mapOf(FitPro.Field.KPH to targetKph)
    }

    /** Fold one board reading into the session and return what to display. */
    private fun accumulate(v: Map<FitPro.Field, Double>): Snapshot {
        advancePhase()
        // WorkoutMode.Dmk — the dead man's key has been pulled.
        val boardMode = v[FitPro.Field.WORKOUT_MODE]?.toInt()
        // The board reports 8 only in bursts, dropping back to Pause(3) while the
        // key may still be out — so treat it as an edge and latch it. The banner
        // then stays up until acknowledged, and re-arms instantly if 8 recurs.
        val keyOut = boardMode == FitPro.Mode.DMK
        if (keyOut) dmkLatched = true
        if (boardMode != null && boardMode != lastBoardMode) {
            Log.i(TAG, "board WorkoutMode -> $boardMode (${FitPro.Mode.name(boardMode)})")
            lastBoardMode = boardMode
        }
        // Any mode but Sleep means the machine is up, however it got there —
        // our write, the physical START, or somebody's foot on the belt. The
        // wake is then over and a later tap starts a fresh one.
        if (boardMode != null && boardMode != FitPro.Mode.SLEEP) wakeAskedAt = 0L
        if (keyOut && !dmk) Log.w(TAG, "safety key removed")
        dmk = dmkLatched
        if (keyOut && Session.isMoving(session)) {
            accumulatedMs += SystemClock.elapsedRealtime() - activeSince
            session = Session.PAUSED
            phaseEndsAt = 0L
            targetKph = 0.0
            // Nothing gradual survives the safety key. The belt is already
            // stopped by the machine itself; a wind-down still counting down
            // towards a stop it has been beaten to is only in the way.
            stopping = false
            // Deliberately *not* remembered for the resume ramp. Someone pulled
            // the safety key; whatever happens next should start from a
            // standstill and be asked for explicitly.
            pausedKph = 0.0
            rampTo = 0.0
            rampReason = ""
        }

        val rawDistance = v[FitPro.Field.DISTANCE] ?: 0.0
        val rawCalories = v[FitPro.Field.CALORIES] ?: 0.0
        if (armBaseline) {
            baseDistance = rawDistance
            baseCalories = rawCalories
            armBaseline = false
            Log.i(TAG, "session baseline: d=$baseDistance c=$baseCalories")
        }

        val actual = v[FitPro.Field.ACTUAL_KPH] ?: 0.0
        lastActualKph = actual
        // Must come before the "confirmed stopped" check below, which is about
        // the belt rather than about ActualKph and has to see this frame's
        // verdict, not the previous one's.
        idleBeltSpeed(rawDistance)
        // Both sources, because "stopped" has to be true of the belt and not
        // merely of the field that never populates. ActualKph reading zero was
        // enough to declare the belt stopped after a single nag, which retired
        // the nag counter without anything having stopped.
        if (beltStillKph() < STOPPED_KPH && stopNags > 0) {
            Log.i(TAG, "belt confirmed stopped after $stopNags nag(s)")
            val ran = (SystemClock.elapsedRealtime() - runawayAtUptime) / 1000
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(runawayAtWall))
            recordIncident("belt stopped after ${ran}s unattended, " +
                    "peak ${"%.1f".format(runawayPeakKph)} km/h, $stopNags nag(s)")
            mqtt.publishRunaway(false, runawayPeakKph, stamp, ran)
            runawayPeakKph = 0.0
            stopNags = 0
        }
        val incline = v[FitPro.Field.ACTUAL_INCLINE] ?: 0.0
        lastActualGrade = incline
        if (levelNags > 0 && Math.abs(incline) < LEVEL_GRADE) {
            Log.i(TAG, "deck confirmed level after $levelNags nag(s)")
            levelNags = 0
        }

        // ActualKph reads 0.00 on this board at every speed — see beltSpeed().
        if (Session.isMoving(session) && targetKph > 0.0 && actual <= 0.0) {
            missingSpeedPolls++
            if (missingSpeedPolls > 25 && !speedProbed) { speedProbed = true; probeSpeed() }
        } else if (actual > 0.0) {
            missingSpeedPolls = 0
        }

        // The dial shows the setpoint. See Snapshot.speed for why, at length —
        // the short version is that the estimate below is built on a whole-metre
        // odometer, wanders by a few tenths whenever the deck tilts, and made a
        // 0.1 button press land two tenths away.
        //
        // The estimate is still computed, still corrected, and still allowed to
        // disagree; it just does not draw anything. It is carried as beltKph so
        // a belt that cannot reach the commanded pace still shows up — see
        // Snapshot.slipping — and so this stays diagnosable if the board ever
        // starts filling ActualKph in.
        val derived = beltSpeed(rawDistance)
        val beltKph = when {
            actual > 0.0 -> actual          // if the board ever starts answering
            // Not "zero because no workout is running". This field answers
            // "what is the belt doing", and a belt turning in manual mode is
            // turning whether or not STRIDE calls it a session. idleBeltSpeed
            // is derived from the odometer for exactly this case, and it is
            // what an FTMS client is asking for when it asks a treadmill how
            // fast it is going.
            !Session.isMoving(session) -> idleBeltKph
            derived != null -> derived      // model, corrected by the odometer
            else -> targetKph
        }
        val speed = if (Session.isMoving(session)) targetKph else 0.0

        val pulse = pulseNow(v[FitPro.Field.PULSE]?.toInt() ?: 0)

        if (Session.isMoving(session)) {
            sessionDistance = (rawDistance - baseDistance).coerceAtLeast(0.0)
            sessionCalories = (rawCalories - baseCalories).coerceAtLeast(0.0)
            if (speed > maxSpeed) maxSpeed = speed
            if (incline > maxIncline) maxIncline = incline
            speedSum += speed
            inclineSum += incline
            samples++

            // Rise over run, against ground actually covered. Guarded against
            // the odometer jumping backwards, which it does when the board
            // resets its own counter mid-session.
            val ran = sessionDistance - lastClimbMetres
            if (ran > 0.0 && ran < 100.0) climbM += ran * incline / 100.0
            lastClimbMetres = sessionDistance

            // Zero is "no reading", never "no pulse" — see pulseNow.
            if (pulse > 0) {
                if (pulse > maxPulse) maxPulse = pulse
                pulseSum += pulse
                pulseSamples++
                if (walkerHrMax > 0) {
                    val nowMs = SystemClock.elapsedRealtime()
                    // Only credit time we were actually reading through. A gap
                    // longer than a couple of polls is a strap dropout, and the
                    // seconds inside it belong to no zone.
                    if (lastZoneAt > 0L && nowMs - lastZoneAt < ZONE_MAX_GAP_MS) {
                        val z = zoneIndex(pulse.toDouble() / walkerHrMax)
                        zoneSecs[z] += (nowMs - lastZoneAt) / 1000.0
                    }
                    lastZoneAt = nowMs
                }
            } else {
                lastZoneAt = 0L
            }
        }

        val elapsedNow = elapsedSec()
        if (Session.isMoving(session)) {
            // A route is ground, not a timetable — see routeTick.
            if (route != null) routeTick(sessionDistance) else planTick(elapsedNow)
        }
        val step = currentStep()

        return Snapshot(
            speed = speed,
            beltKph = beltKph,
            rpm = v[FitPro.Field.RPM] ?: 0.0,
            incline = incline,
            targetSpeed = targetKph,
            targetIncline = targetGrade,
            distance = sessionDistance,
            elapsed = elapsedSec(),
            calories = sessionCalories,
            pulse = pulse,
            fan = fanState,
            session = session,
            workout = workout,
            dmk = dmk,
            ramping = if (rampTo > 0.0) rampReason else "",
            phaseLeft = phaseLeftSec(),
            boardMode = boardMode ?: -1,
            // Ground covered over time on the belt, not the average of a
            // sampled estimate. This is the one speed figure that can be
            // measured rather than modelled, and it is exact: both halves come
            // off counters the board keeps itself.
            avgSpeed = avgPace(),
            maxSpeed = maxSpeed,
            avgIncline = if (samples > 0) inclineSum / samples else 0.0,
            maxIncline = maxIncline,
            avgPulse = if (pulseSamples > 0) (pulseSum / pulseSamples).toInt() else 0,
            maxPulse = maxPulse,
            hrMax = walkerHrMax,
            zoneSecs = if (walkerHrMax > 0) zoneSecs.map { Math.round(it).toInt() }
                       else emptyList(),
            achievements = earned,
            who = walker,
            whoId = cfg.person(walker)?.id ?: "",
            plan = planName,
            segment = if (step != null) stepIndex + 1 else 0,
            segments = planSteps.size,
            segmentLabel = step?.label ?: "",
            // Against plan time, not session time — on a circuit those differ
            // by however many laps have gone by.
            // Seconds on a template, **metres** on a route — the steps of a
            // route carry distance in the same fields, which is what let the
            // HUD render a 275 m opening stretch as "4:35 left". The UI is
            // told which it is rather than being left to guess.
            segmentLeft = when {
                step == null -> 0.0
                route != null -> (step.endSec - sessionDistance).coerceAtLeast(0.0)
                else -> (step.endSec - planElapsed).coerceAtLeast(0.0)
            },
            segmentLeftIsDistance = route != null,
            // On a circuit the "next" segment after the last one is the first
            // one again, because the ground comes round rather than running out.
            nextLabel = nextStep(stepIndex)?.label ?: "",
            nextIncline = nextStep(stepIndex)?.incline ?: 0.0,
            planTotalSec = planSteps.lastOrNull()?.endSec ?: 0.0,
            // A route's steps are in metres, so its ascent is real and
            // measurable. A template's are in seconds and its climb depends on
            // how fast it is walked, so it does not claim one.
            planClimbM = route?.climbM ?: 0.0,
            planPeakIncline = planSteps.maxOfOrNull { it.incline } ?: 0.0,
            summaryLine = summaryLine,
            planElapsed = planElapsed,
            planLap = planLap,
            planLoops = planLoops,
            // No opinion until he has settled on a pace to be relative to.
            suggestPace = if (step != null && baselineKph > 0.0) {
                (baselineKph + step.paceDelta).coerceIn(minKph, maxKph)
            } else 0.0,
            inclineAuto = inclineAuto,
            boardLocked = boardLocked,
        )
    }

    /** Everything the HUD needs to draw the path ahead. Sent once per plan. */
    private fun planJson(): String {
        val arr = org.json.JSONArray()
        for (s in planSteps) {
            arr.put(org.json.JSONObject()
                .put("start", Math.round(s.startSec))
                .put("end", Math.round(s.endSec))
                .put("incline", s.incline)
                .put("label", s.label))
        }
        return org.json.JSONObject()
            .put("name", planName)
            .put("loops", planLoops)
            // Route steps are metres, template steps are seconds. They share
            // the field names (`startSec`/`endSec`) for historical reasons, so
            // the drawing cannot tell them apart without being told — and it
            // has to, because only one of the two can be integrated into an
            // elevation profile. See profile() in stride-core.js.
            .put("byDistance", route != null)
            .put("steps", arr)
            .toString()
    }

    /**
     * Belt speed in km/h.
     *
     * `ActualKph` (field 16) reads 0.00 on this board at every speed. It is not
     * a framing bug — `ActualIncline` decodes correctly from the two bytes
     * immediately after it, a bare single-field read of it returns the expected
     * 7-byte frame, and devices 2 and 65 answer the same way. The board simply
     * never fills it in.
     *
     * `Distance` *is* live and truthful though, so the belt tells us its speed
     * whether or not the firmware will say so.
     *
     * **What was wrong with reading it straight off the odometer.** Distance
     * arrives in whole metres, which is far too coarse to differentiate between
     * two polls, so it used to be differentiated across a four-second window.
     * That window is the bug. It reports the average of the last four seconds,
     * so the instant the pace changes the number is describing the pace you
     * *were* walking, and the whole-metre quantisation puts about ±0.5 km/h of
     * hash on top of it. A settle band hid all of that at a steady pace by
     * switching the readout over to the commanded speed — which meant the
     * moment you pressed a button the readout dropped out of the band, stopped
     * showing 6.0 and started showing the four-second average with the hash
     * back on it. Reported from the belt as "6 to 4.5 and then it slowly
     * settles", repeatedly, and quite right.
     *
     * **What it does instead.** Two halves that fail in opposite directions:
     *
     *  * [modelKph] — the belt chasing the commanded pace at a bounded rate.
     *    Instant and smooth, and wrong whenever the belt does not obey.
     *  * [trimKph] — the difference the odometer has actually accumulated,
     *    integrated slowly. Late and coarse, and the only thing here that has
     *    measured any real ground.
     *
     * The model carries the change, so pressing 7 at 6 km/h climbs from 6 to 7
     * and stops there. The trim carries the truth, so a belt that will not
     * reach 7 is shown at what it is doing within about ten seconds, and a
     * treadmill unplugged from its motor reads zero however hard the console
     * asks for 7. Neither half alone is honest; the readout is the sum.
     *
     * Returns null when there is no walk in progress.
     */
    private fun beltSpeed(rawDistance: Double): Double? {
        val now = SystemClock.elapsedRealtime()
        if (!Session.isMoving(session)) {
            modelKph = 0.0; trimKph = 0.0; trimResidual = 0.0; lastEstimateAt = 0L
            return null
        }

        // First frame of a walk, a poll gap long enough that integrating across
        // it would be invention, or a board that has reset its odometer: seed
        // and say nothing new. `+ 0.5` throughout because Distance is whole
        // metres rounded down, and a half-metre bias would read as a permanent
        // 0.2 km/h of shortfall at walking pace.
        val gap = now - lastEstimateAt
        if (lastEstimateAt == 0L || gap > MAX_STEP_MS || rawDistance + 0.5 < modelMetres - 5.0) {
            modelMetres = rawDistance + 0.5
            lastEstimateAt = now
            return (modelKph + trimKph).coerceIn(0.0, maxKph)
        }
        lastEstimateAt = now
        val dt = gap / 1000.0

        // Predict: the belt chases what it was told, no faster than a belt can.
        val up = BELT_UP_KPH_PER_SEC * dt
        val down = BELT_DOWN_KPH_PER_SEC * dt
        modelKph += (targetKph - modelKph).coerceIn(-down, up)

        val kph = (modelKph + trimKph).coerceAtLeast(0.0)
        modelMetres += kph / 3.6 * dt

        // Correct: how far off the ground says we are. Part goes straight back
        // into position, so the residual measures the speed error rather than
        // accumulating for ever; the rest is smoothed and integrated into the
        // speed, which is the only path by which the odometer can overrule the
        // number the console asked for.
        val residual = (rawDistance + 0.5) - modelMetres
        modelMetres += TRIM_POSITION * residual

        // But only while the model has arrived.
        //
        // The model climbs at BELT_UP_KPH_PER_SEC, deliberately slower than the
        // machine. While it is still climbing, the ground disagreeing with it
        // says nothing about how fast the belt is going — only that the model
        // has not caught up yet. Integrating that is integrating a known lag,
        // and it winds up: pressing + from 3 to 8 km/h on 2026-08-10 put five
        // km/h of climb into the correction, and the readout reached 8, kept
        // going to 9, and then walked back down as the trim unwound. Exactly
        // the wobble the estimator's own notes set out to avoid, arriving by
        // the one route they did not consider.
        //
        // So the correction is frozen — not reset — for the couple of seconds
        // the model spends catching up, and resumes untouched once it has.
        // This costs nothing that matters: the model always converges on the
        // target regardless of what the belt does, so a belt that genuinely
        // cannot reach the commanded pace still ends up arrived, still gets
        // corrected, and the odometer still outvotes the setpoint. That was
        // the behaviour worth protecting and it is untouched.
        val arrived = Math.abs(targetKph - modelKph) <= TRIM_ARRIVED_KPH
        if (arrived) {
            trimResidual += TRIM_SMOOTH * (residual - trimResidual)
            trimKph += TRIM_SPEED * trimResidual / dt * 3.6
        }

        // A small disagreement is noise or belt calibration. Let it bleed away
        // rather than sit there moving the last digit — see TRIM_SETTLE_KPH.
        if (Math.abs(trimKph) < TRIM_SETTLE_KPH) {
            val bleed = TRIM_SETTLE_PER_SEC * dt
            trimKph -= trimKph.coerceIn(-bleed, bleed)
        }
        trimKph = trimKph.coerceIn(-TRIM_MAX_KPH, TRIM_MAX_KPH)

        return (modelKph + trimKph).coerceIn(0.0, maxKph)
    }

    /**
     * How fast the belt is turning while no workout is running.
     *
     * [enforceStopped] is the net that catches a belt running with nobody
     * driving it, and it was fed [lastActualKph] — which this board reports as
     * 0.00 at every speed, as [probeSpeed] and [beltSpeed] both say at length.
     * A net whose input is always zero can never fire, so on this hardware the
     * one failure the net exists for was completely unguarded. On the morning of
     * 2026-08-28 the belt was found running on the welcome screen, and there is
     * not one "belt still moving" line in the log for the twenty-three hours
     * before it: the check ran every poll and returned at the first `if` every
     * time.
     *
     * Distance is the signal that works. It is whole metres and monotonic, it is
     * already polled every [POLL_MS], and it only advances when the belt turns —
     * so it says nothing about how fast, but it is decisive about *whether*,
     * which is the entire question the net asks.
     *
     * [beltSpeed] cannot serve here: it returns null unless a walk is in
     * progress, which is precisely when this is not needed.
     */
    private fun idleBeltSpeed(rawDistance: Double) {
        if (Session.isMoving(session)) {
            idleOdoMetres = -1.0
            idleBeltKph = 0.0
            return
        }
        val now = SystemClock.elapsedRealtime()
        // First idle frame, or an odometer that has gone backwards because the
        // board reseeded it: anchor here and claim nothing.
        if (idleOdoMetres < 0.0 || rawDistance < idleOdoMetres) {
            idleOdoMetres = rawDistance
            idleOdoAt = now
            idleBeltKph = 0.0
            return
        }
        val moved = rawDistance - idleOdoMetres
        val dt = now - idleOdoAt
        if (moved >= IDLE_ODO_METRES) {
            idleBeltKph = if (dt > 0L) moved / (dt / 1000.0) * 3.6 else 0.0
            idleOdoMetres = rawDistance
            idleOdoAt = now
        } else if (moved <= 0.0 && dt > IDLE_STILL_MS) {
            // Nothing has moved at all for long enough to call it still. Only
            // then is the anchor thrown away — a belt that has crawled one metre
            // keeps it, so slow movement still accumulates to a verdict instead
            // of being reset out of existence on every timeout.
            idleBeltKph = 0.0
            idleOdoMetres = rawDistance
            idleOdoAt = now
        }
    }

    /**
     * One-shot, read-only hunt for a live belt speed.
     *
     * Device 4 answers for everything else, but reports ActualKph as zero. The
     * device table has metrics as addressable devices in their own right
     * (SPEED = 65), so the reading may simply live somewhere else. Rpm comes
     * along because a motor tachometer would do just as well as a speedometer.
     */
    private fun probeSpeed() {
        Log.i(TAG, "--- speed probe: ActualKph reads 0 while the belt is moving ---")
        for (dev in listOf(FitPro.Dev.TREADMILL, FitPro.Dev.MAIN, FitPro.Dev.SPEED)) {
            val reply = conn.exchange(FitPro.readWrite(dev, SPEED_PROBE))
            if (reply == null) { Log.i(TAG, "  dev $dev: no reply"); continue }
            val why = FitPro.rejectReason(reply, SPEED_PROBE)
            if (why != null) { Log.i(TAG, "  dev $dev: rejected ($why) ${FitPro.hex(reply)}"); continue }
            val p = FitPro.parse(reply, SPEED_PROBE)
            Log.i(TAG, "  dev $dev: rpm=${p[FitPro.Field.RPM]} " +
                    "actualKph=${p[FitPro.Field.ACTUAL_KPH]}  raw=${FitPro.hex(reply)}")
            Thread.sleep(100)
        }
        Log.i(TAG, "--- end speed probe (target was ${"%.1f".format(targetKph)} km/h) ---")
    }

    private fun pushPlan() {
        val json = planJson()
        runOnUiThread { web.evaluateJavascript("window.plan($json)", null) }
    }

    /** Hand a state snapshot to the page. */
    private fun push(s: Snapshot) {
        val json = s.toJson()
        runOnUiThread { web.evaluateJavascript("window.render($json)", null) }
    }

    /**
     * Redraw now, on the strength of a button press, without waiting for the
     * board to answer.
     *
     * Which screen the console shows is decided entirely by our own session
     * state — but it only ever *changed* on the back of a good board frame,
     * because [pollLoop] is the only thing that pushed. So every screen
     * transition was queued behind a USB exchange, and when the board is slow
     * or unhappy that is not 200 ms. A dropped frame costs a poll; a board
     * that does not answer at all costs the full [FitProConnection] read
     * timeout, one second, every attempt; a board refusing frames repaints the
     * *previous* snapshot, which still says SUMMARY. Reported from the belt as
     * DONE taking ages to react, and it is the same wait behind PAUSE and STOP.
     *
     * The board owns the numbers. It does not get a vote on which screen the
     * person in front of it is looking at.
     *
     * The poll loop uses it too, with [boardOk] false, for the frames where
     * there is no new telemetry to draw. Those used to repaint [lastSnap]
     * verbatim, which meant a session change made on a WebView thread was
     * undone a fifth of a second later by a snapshot from before it.
     *
     * Deliberately does not write [lastSnap]: this runs on a WebView thread and
     * the poll thread owns that field. It is a repaint, not a state change —
     * the authoritative frame is along in a fifth of a second either way.
     */
    private fun repaint(boardOk: Boolean = true) {
        val base = lastSnap ?: return
        val steps = planSteps
        push(base.copy(
            boardOk = boardOk,
            session = session,
            workout = workout,
            dmk = dmk,
            plan = planName,
            segments = steps.size,
            // Same rule accumulate() uses, so a repaint between two polls
            // cannot show a segment number from the walk before this one.
            segment = if (steps.isEmpty() || stepIndex < 0) 0 else stepIndex + 1,
            summaryLine = summaryLine,
            targetSpeed = targetKph,
            targetIncline = targetGrade,
            elapsed = elapsedSec(),
            phaseLeft = phaseLeftSec(),
            ramping = if (rampTo > 0.0) rampReason else "",
        ))
    }

    private fun resolveDeviceId(): Boolean {
        val probe = listOf(FitPro.Field.ACTUAL_KPH)
        for (candidate in listOf(FitPro.Dev.TREADMILL, FitPro.Dev.MAIN)) {
            val reply = conn.exchange(FitPro.readWrite(candidate, probe))
            if (reply != null && FitPro.rejectReason(reply, probe) == null) {
                deviceId = candidate
                return true
            }
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Authenticate with the board.
     *
     * A locked board answers *every* ReadWriteData with securityBlock — reads
     * included — so this must succeed before the poll loop can do anything at
     * all. It is not one-off setup: the board re-locks on its own schedule,
     * across power cycles and idle days, and ICON's console re-runs the same
     * sequence every time it sees a securityBlock.
     *
     * Identity comes off the board itself rather than being configured, so a
     * different machine needs no changes: DeviceInfo gives the serial number,
     * SystemInfo the model and part number, VersionInfo the library version
     * that seeds the secret key.
     *
     * Returns true if the board accepted the challenge.
     */
    private fun unlockBoard(): Boolean {
        val dev = FitPro.Dev.MAIN

        val idReply = conn.exchange(FitPro.command(dev, FitPro.Cmd.DEVICE_INFO))
        val id = idReply?.let { FitPro.parseDeviceInfo(it) }
        if (id == null) { Log.w(TAG, "unlock: no DeviceInfo from the board"); return false }

        // ICON only unlocks above software version 75; below that the board has
        // no security to satisfy and VerifySecurity is not supported.
        if (id.softwareVersion <= 75) {
            Log.i(TAG, "unlock: board sw ${id.softwareVersion} predates security — nothing to do")
            boardLocked = false
            return true
        }

        val sysReply = conn.exchange(FitPro.command(dev, FitPro.Cmd.SYSTEM_INFO, byteArrayOf(0, 0)))
        val sys = sysReply?.let { FitPro.parseSystemInfo(it) }
        if (sys == null) { Log.w(TAG, "unlock: no SystemInfo from the board"); return false }

        val verReply = conn.exchange(FitPro.command(dev, FitPro.Cmd.VERSION_INFO, byteArrayOf(0, 0)))
        val mlv = verReply?.let { FitPro.parseMasterLibraryVersion(it) }
        if (mlv == null) { Log.w(TAG, "unlock: no VersionInfo from the board"); return false }

        val hash = FitPro.securityHash(id.serialNumber, sys.partNumber, sys.model)
        val reply = conn.exchange(FitPro.verifySecurity(dev, hash, mlv))
        if (reply == null || !FitPro.isValid(reply)) {
            Log.w(TAG, "unlock: no answer to VerifySecurity"); return false
        }
        val status = reply[3].toInt() and 0xFF
        val ok = status == FitPro.Status.DONE
        boardLocked = !ok
        // The serial, part and model ARE the unlock secret — with the public
        // hash algorithm, those three numbers authenticate this specific board.
        // So the log confirms they were read (non-zero) without printing them;
        // the identity never leaves the device. If you need the raw values for
        // debugging, read them off the board yourself, do not paste a log.
        val gotIdentity = id.serialNumber != 0 && sys.partNumber != 0 && sys.model != 0
        Log.i(TAG, "unlock: identity ${if (gotIdentity) "read" else "MISSING"}, " +
                   "mlv=$mlv -> ${FitPro.Status.name(status)}")
        return ok
    }

    /**
     * Unlock, retrying a few times before giving up on this attempt.
     *
     * The board can answer a command with nothing at all while it is settling
     * after a power cycle, and one silent frame should not cost a walk.
     */
    private fun unlockWithRetries(attempts: Int = 3): Boolean {
        for (i in 1..attempts) {
            if (unlockBoard()) {
                if (i > 1) Log.i(TAG, "unlock: succeeded on attempt $i")
                return true
            }
            Thread.sleep(300)
        }
        Log.w(TAG, "unlock: failed after $attempts attempts")
        return false
    }

    /**
     * Read the fan back off the board, so the physical buttons show on screen.
     *
     * FAN_STATE is writable and we have been writing it since the fan tile
     * existed, but it was never in the read list — so pressing the console's
     * own fan button changed the machine and the HUD never found out. The soft
     * tile and the hardware disagreed, and the tile was the one that was wrong.
     *
     * Deliberately its own exchange rather than another entry in TELEMETRY.
     * FAN_STATE is field 98, which widens the read bitmask from 4 bytes to 13,
     * and if this board turns out not to report it then a sentinel or a length
     * mismatch would reject *every* frame — losing speed, incline and the whole
     * HUD to a cosmetic feature. Today already showed what a board refusing
     * every frame looks like. On its own exchange the worst case is a fan tile
     * that does not track, which is exactly where we are now.
     */
    private fun readFan() {
        if (fanReadable == false) return
        val reply = conn.exchange(FitPro.readWrite(deviceId, FAN_READ)) ?: return
        val why = FitPro.rejectReason(reply, FAN_READ)
        if (why != null) {
            if (fanReadable == null) {
                fanReadable = false
                Log.i(TAG, "fan: board will not report FAN_STATE ($why) — tile stays local")
            }
            return
        }
        val v = FitPro.parse(reply, FAN_READ)[FitPro.Field.FAN_STATE] ?: return
        if (fanReadable == null) {
            fanReadable = true
            Log.i(TAG, "fan: board reports FAN_STATE — tile now follows the hardware")
        }
        // Same settle rule as speed: our own write owns the value until it lands.
        if (SystemClock.elapsedRealtime() - lastWriteMs <= FOLLOW_SETTLE_MS) return
        val level = v.toInt().coerceIn(0, 4)
        if (level != fanState) {
            fanState = level
            Log.i(TAG, "fan: board says $level")
        }
    }

    /* ---------------------------------------------------------------- *
     * Sleep
     * ---------------------------------------------------------------- *
     *
     * Two things on this console sleep, and only one of them was ever ours.
     *
     * The panel dims itself after five idle minutes and comes back on a tap —
     * each UI owns that, since each owns its own sleep screen. The control
     * board runs a separate idle timer that STRIDE neither sets nor is told
     * about, and when it expires the machine goes dormant: [FitPro.Mode.SLEEP].
     * Come back after a week away and the Android side is perfectly fine, the
     * HUD draws, every button answers — and the belt will not move for
     * anything, because the hardware behind it is asleep. Until now the only
     * known cure was the switch on the wall.
     *
     * iFit's own console had the step this restores. A tap woke the display,
     * and then it asked, separately and deliberately, whether to wake the
     * machine as well. That separation is the right one and not just history:
     * waking the board spins a motor controller up in a dark room, and
     * somebody walking past to read the clock has not asked for that.
     */

    /**
     * True when the board is up and talking.
     *
     * Both halves matter and they fail differently. A board that has stopped
     * answering may be asleep or may be off the bus; a board that answers
     * [FitPro.Mode.SLEEP] is plainly asleep and saying so.
     */
    private fun boardAwake(): Boolean =
        lastReplyMs > 0L &&
        SystemClock.elapsedRealtime() - lastReplyMs < SILENCE_MS &&
        lastBoardMode != FitPro.Mode.SLEEP

    /** Where the wake step has got to, for the page that is asking. */
    private fun wakeState(): String = when {
        boardAwake() -> "awake"
        wakeAskedAt == 0L -> "asleep"
        SystemClock.elapsedRealtime() - wakeAskedAt < WAKE_WAIT_MS -> "waking"
        else -> "unreachable"
    }

    /**
     * Ask the machine to come back.
     *
     * Writing [FitPro.Mode.IDLE] is the transition ICON's own console makes to
     * bring a board out of any state that is not idle, and idle is the state a
     * workout starts from — so this both wakes the machine and leaves it where
     * the START button expects to find it.
     *
     * Queued rather than sent from here: one thread owns the USB pipe (see
     * FitProConnection.exchange), and the mode is queued alone because the
     * board rejects a whole frame over one field it will not accept. The
     * enforcers that outrank a queued write are all about a belt or a deck
     * that is doing something it should not be, and a sleeping machine is
     * doing nothing at all, so none of them can starve this.
     */
    private fun wakeBoard() {
        wakeAskedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "wake: asked for the machine — board is ${
            if (lastBoardMode == null) "not answering" else FitPro.Mode.name(lastBoardMode!!)
        }")
        // Somebody is standing in front of it: worth an attempt at the
        // transport now rather than at the end of the retry gap.
        lastReacquireMs = 0L
        pendingWrite = mapOf(FitPro.Field.WORKOUT_MODE to FitPro.Mode.IDLE.toDouble())
    }

    /**
     * Rebuild the connection to the board without restarting the console.
     *
     * [acquireBoard] runs once, at startup, and stops the moment the poll loop
     * starts — so a board that left the bus afterwards was never looked for
     * again. This is that same search, from the poll thread, rate-limited by
     * [REACQUIRE_MS].
     *
     * Note the order: find the device *before* closing what we have. A board
     * that is merely asleep is still enumerated, and throwing away a working
     * handle to go looking for one would be the more expensive mistake.
     */
    private fun reacquire(): Boolean {
        lastReacquireMs = SystemClock.elapsedRealtime()
        val device = conn.findDevice()
        if (device == null) {
            Log.w(TAG, "board silent and not on the USB bus — waiting for it to re-enumerate")
            return false
        }
        if (!conn.hasPermission(device)) {
            Log.w(TAG, "board is back on the bus but USB permission is gone — needs a relaunch")
            return false
        }
        conn.close()
        val err = conn.open(device)
        if (err != null) { Log.w(TAG, "board silent — reopen failed: $err"); return false }
        if (!resolveDeviceId()) {
            Log.w(TAG, "board silent — reopened, but it answers on neither 4 nor 2")
            return false
        }
        Log.i(TAG, "board is answering again on device $deviceId")
        lastReplyMs = SystemClock.elapsedRealtime()
        // A board that has been away has usually re-locked while it was gone.
        unlockWithRetries()
        return true
    }

    /**
     * What the board's own idle timer is set to, read once and logged.
     *
     * Nothing reads these back: they are here because the machine going to
     * sleep is a decision the hardware makes on its own, and this is the only
     * account of that decision we can get. All three are writable on the board
     * and none of them is written — waking is a mode change, and changing how
     * long the machine stays awake is a different question that nobody has
     * asked.
     *
     * One exchange each, the way [readFan] is. The board rejects a whole frame
     * over a single field it does not support, and one missing value should
     * not take the other two down with it.
     */
    private fun readSleepConfig() {
        for (f in SLEEP_FIELDS) {
            val one = listOf(f)
            val reply = conn.exchange(FitPro.readWrite(deviceId, one))
            if (reply == null) { Log.i(TAG, "sleep: no answer for ${f.name}"); continue }
            val why = FitPro.rejectReason(reply, one)
            if (why != null) { Log.i(TAG, "sleep: board will not report ${f.name} ($why)"); continue }
            Log.i(TAG, "sleep: ${f.name} = ${FitPro.parse(reply, one)[f]}")
        }
    }

    private fun readLimits() {
        val reply = conn.exchange(FitPro.readWrite(deviceId, LIMITS)) ?: return
        val v = FitPro.parse(reply, LIMITS)
        v[FitPro.Field.MAX_KPH]?.let { if (it > 0) maxKph = it }
        v[FitPro.Field.MIN_KPH]?.let { if (it > 0) minKph = it }
        v[FitPro.Field.MAX_GRADE]?.let { if (it != 0.0) maxGrade = it }
        v[FitPro.Field.MIN_GRADE]?.let { minGrade = it }
        Log.i(TAG, "limits: $minKph..$maxKph km/h, $minGrade..$maxGrade %")
        strap.logRadioCapabilities()
        ftms.start()
    }

    override fun onDestroy() {
        running = false
        ftms.stop()
        voice.close()
        mqtt.close()
        conn.close()
        try { unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }
}
