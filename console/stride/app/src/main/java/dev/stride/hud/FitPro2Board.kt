package dev.stride.hud

import android.os.SystemClock
import android.util.Log

/**
 * A Gen 7 board, made to look like a FitPro 1 board to the poll loop.
 *
 * Everything that keeps a person safe — the ramps, the clamps, enforceStopped,
 * the safety-key latch — is written in terms of [FitPro.Field] reads and
 * writes, and was proved on FitPro 1. Rather than fork that logic, this
 * translates at the edge: FitPro 2 feature ids in, FitPro 1 fields out, and
 * the reverse for writes. The poll loop keeps one shape of safety code.
 *
 * The board pushes values once subscribed, so this keeps the latest of each and
 * every [cycle] reports the whole picture, the way a FitPro 1 reply would.
 */
class FitPro2Board(private val conn: FitProConnection) {

    companion object {
        const val TAG = "Stride"

        /**
         * What the poll loop reads, in iFit's batches of eight. The board seen
         * so far advertises all of these; any it does not are simply absent.
         */
        val SUBSCRIBE = listOf(
            FitPro2.Id.SYSTEM_MODE, FitPro2.Id.WORKOUT_STATE,
            FitPro2.Id.TARGET_KPH, FitPro2.Id.CURRENT_KPH,
            FitPro2.Id.TARGET_GRADE, FitPro2.Id.CURRENT_GRADE,
            FitPro2.Id.MIN_KPH, FitPro2.Id.MAX_KPH,
            FitPro2.Id.MIN_GRADE, FitPro2.Id.MAX_GRADE,
            FitPro2.Id.DISTANCE, FitPro2.Id.RUNNING_TIME,
            FitPro2.Id.PULSE, FitPro2.Id.CURRENT_CALORIES,
            FitPro2.Id.KEY_COOKED,
        )

        /** How long to wait for a write to be acknowledged. iFit allows 15 s; a
         *  poll loop cannot, and an unanswered write is retried by the caller. */
        const val WRITE_WAIT_MS = 400L

        /** The board repeats its latest event every ~1.5 s even when idle, so
         *  this long with nothing at all means the link is gone. */
        const val SILENCE_MS = 5_000L

        /** A belt reading unchanged this long outside a running state is a stopped belt. */
        const val STALE_KPH_MS = 3_000L
    }

    sealed class Result {
        class Values(val v: Map<FitPro.Field, Double>) : Result()
        class Rejected(val reason: String) : Result()
        object Silent : Result()
    }

    private val values = HashMap<Int, Float>()
    private var features = emptySet<Int>()
    private var lastFrameMs = 0L
    private var lastKey = 0
    private var fanWarned = false
    private var lastError = ""
    /** We paused the board because the speed was taken to zero mid-workout. */
    private var heldAtZero = false
    private var lastCurrentKph = -1f
    /** When CURRENT_KPH last arrived with a different value. See [actualKph]. */
    private var currentKphMovedMs = 0L
    /** When WORKOUT_STATE last changed. A steady belt's reading is constant
     *  too, so the stale clock cannot start before the belt was told to stop. */
    private var stateChangedMs = 0L

    var model = ""; private set
    var software = ""; private set

    /** Product info, the feature list, then subscribe. True if values are flowing. */
    fun setup(): Boolean {
        values.clear()

        conn.send(FitPro2.productInfo())
        val info = HashMap<Int, String>()
        collect(3000) { f ->
            if (f.type != FitPro2.Resp.EXTENDED || f.payload.size < 2 ||
                f.payload[0] != FitPro2.INFO_PRODUCT) return@collect false
            val item = f.payload[1].toInt() and 0xFF
            if (item == 0) return@collect true
            info[item] = String(f.payload, 2, f.payload.size - 2, Charsets.US_ASCII).trim()
            false
        }
        software = info[1] ?: ""
        model = info[4] ?: ""
        Log.i(TAG, "fp2: model '$model', board software '$software'")

        conn.send(FitPro2.supportedFeatures())
        val seen = sortedSetOf<Int>()
        var chunks = 0
        collect(5000) { f ->
            if (f.type != FitPro2.Resp.FEATURES) return@collect false
            if (f.payload.isEmpty()) return@collect chunks > 0
            chunks++
            for (i in 0 until f.payload.size / 2) seen.add(FitPro2.u16(f.payload, 2 * i))
            false
        }
        features = seen
        Log.i(TAG, "fp2: ${seen.size} features advertised")
        if (seen.isEmpty()) return false
        for (w in FitPro2.WRITABLE) if (w !in seen) Log.w(TAG, "fp2: board does not advertise writable $w")

        for (batch in SUBSCRIBE.filter { it in seen }.chunked(8)) {
            conn.send(FitPro2.subscribe(batch))
            collect(2000) { batch.all { values.containsKey(it) } }
        }
        Log.i(TAG, "fp2: subscribed, ${values.size} values in hand")
        return values.isNotEmpty()
    }

    /**
     * Apply [writes], take in whatever the board has said, and report.
     *
     * A write that the board refuses rejects the cycle, so the caller's
     * pendingWrite survives and is retried — the same rule as a FitPro 1 frame
     * that comes back with a bad status.
     */
    fun cycle(writes: Map<FitPro.Field, Double>?, minKph: Double): Result {
        // Released — by the watchdog, or the board left the bus. That is
        // silence, which the poll loop answers by reconnecting; a refusal
        // would only be retried against a pipe that is not there.
        if (!conn.isOpen) return Result.Silent
        // Starting or resuming: hold the setpoint at the machine's minimum on
        // both sides of the RUNNING write. FitPro 2 keeps its last target
        // through a pause — nothing ever writes it to zero — so RUNNING alone
        // could bring the belt back at whatever pace it last had, instead of
        // the crawl the poll loop's ramp climbs from.
        //
        // Before: best effort. An idle board refuses any target speed as out
        // of range (seen on hardware, 11/10/2026), and that refusal used to
        // abort the start — so RUNNING was never sent, while the HUD went on
        // ramping a belt that had not moved. After: the one that counts, sent
        // the moment the board is running and will accept it.
        val mode = writes?.get(FitPro.Field.WORKOUT_MODE)?.toInt()
        val starting = mode == FitPro.Mode.RUNNING || mode == FitPro.Mode.RESUME || mode == FitPro.Mode.WARM_UP
        val floor = (values[FitPro2.Id.MIN_KPH]?.toDouble()?.takeIf { it > 0 } ?: minKph).toFloat()
        if (starting && conn.send(FitPro2.write(FitPro2.Id.TARGET_KPH, floor))) {
            awaitWrite(FitPro2.Id.TARGET_KPH)?.let { Log.i(TAG, "fp2: pre-start floor not taken ($it), continuing") }
        }
        if (mode != null) heldAtZero = false
        speedThroughState(writes, minKph, floor)?.let { return it }
        writes?.forEach { (field, value) ->
            val (id, v) = translateWrite(field, value, minKph) ?: return@forEach
            if (!conn.send(FitPro2.write(id, v))) return Result.Rejected("fp2 write did not go")
            awaitWrite(id)?.let { return Result.Rejected(it) }
        }
        if (starting) {
            if (!conn.send(FitPro2.write(FitPro2.Id.TARGET_KPH, floor))) return Result.Rejected("fp2 write did not go")
            awaitWrite(FitPro2.Id.TARGET_KPH)?.let { return Result.Rejected(it) }
        }
        // Drain what has queued up since the last poll, without stalling it.
        var n = 0
        while (n < 20) {
            val raw = conn.receive(15) ?: break
            take(raw)
            n++
        }
        if (SystemClock.elapsedRealtime() - lastFrameMs > SILENCE_MS) return Result.Silent
        return Result.Values(translateIn())
    }

    fun has(id: Int) = id in features

    /**
     * Speed to zero, and back, inside a workout.
     *
     * On FitPro 1 a speed of 0 stops the belt while the workout carries on. A
     * FitPro 2 board refuses anything under its ~1.5 km/h minimum, so that
     * write used to be dropped — and the belt kept turning at the minimum,
     * while the board's untouched target was then adopted back by the poll
     * loop ("follow the board") and the HUD crept back up to 2 km/h. Seen on
     * hardware 11/10/2026, belt empty.
     *
     * So zero is a PAUSED, which is proved to stop this belt, and a speed back
     * above the minimum from there is RUNNING, the floor, then the speed —
     * the same order as any start. Only when the write is speed alone: a mode
     * travelling with it already says what the belt should do.
     */
    private fun speedThroughState(writes: Map<FitPro.Field, Double>?, minKph: Double, floor: Float): Result? {
        val kph = writes?.get(FitPro.Field.KPH) ?: return null
        if (writes.containsKey(FitPro.Field.WORKOUT_MODE)) return null
        val state = values[FitPro2.Id.WORKOUT_STATE]?.toInt()
        val running = state == FitPro2.WorkoutState.RUNNING ||
            state == FitPro2.WorkoutState.WARM_UP || state == FitPro2.WorkoutState.COOL_DOWN

        if (kph < minKph - 0.05) {
            if (!running) return null
            Log.i(TAG, "fp2: speed to zero — pausing the belt, workout carries on")
            stateWrite(FitPro2.WorkoutState.PAUSED)?.let { return it }
            heldAtZero = true
            return null
        }
        if (heldAtZero && state == FitPro2.WorkoutState.PAUSED) {
            Log.i(TAG, "fp2: speed up from zero — belt back to running from the minimum")
            stateWrite(FitPro2.WorkoutState.RUNNING)?.let { return it }
            if (!conn.send(FitPro2.write(FitPro2.Id.TARGET_KPH, floor))) return Result.Rejected("fp2 write did not go")
            awaitWrite(FitPro2.Id.TARGET_KPH)?.let { return Result.Rejected(it) }
            heldAtZero = false
        }
        return null
    }

    private fun stateWrite(state: Int): Result? {
        if (!conn.send(FitPro2.write(FitPro2.Id.WORKOUT_STATE, state.toFloat())))
            return Result.Rejected("fp2 write did not go")
        return awaitWrite(FitPro2.Id.WORKOUT_STATE)?.let { Result.Rejected(it) }
    }

    /**
     * A STRIDE write as a FitPro 2 one, or null if there is nothing to send.
     *
     * Speed below the machine's minimum is not sent. FitPro 1 stops the belt
     * with {KPH 0, mode PAUSE}; on FitPro 2 the minimum is a real ~1.5 km/h,
     * iFit never writes less, and the board refuses it as out of range. The
     * stop travels in the mode, which the poll loop always sends alongside —
     * WORKOUT_STATE PAUSED. That this actually stops the belt must be proved on
     * the hardware, belt empty, before anybody walks on it.
     */
    private fun translateWrite(field: FitPro.Field, value: Double, minKph: Double): Pair<Int, Float>? =
        when (field) {
            FitPro.Field.KPH ->
                if (value < minKph - 0.05) null else FitPro2.Id.TARGET_KPH to value.toFloat()
            FitPro.Field.GRADE -> FitPro2.Id.TARGET_GRADE to value.toFloat()
            FitPro.Field.WORKOUT_MODE -> {
                val state = when (value.toInt()) {
                    FitPro.Mode.IDLE -> FitPro2.WorkoutState.NONE
                    FitPro.Mode.RUNNING, FitPro.Mode.RESUME -> FitPro2.WorkoutState.RUNNING
                    FitPro.Mode.PAUSE, FitPro.Mode.PAUSE_OVERRIDE -> FitPro2.WorkoutState.PAUSED
                    FitPro.Mode.RESULTS -> FitPro2.WorkoutState.RESULTS
                    FitPro.Mode.WARM_UP -> FitPro2.WorkoutState.WARM_UP
                    FitPro.Mode.COOL_DOWN -> FitPro2.WorkoutState.COOL_DOWN
                    else -> null
                }
                if (state == null) {
                    Log.w(TAG, "fp2: no workout state for mode ${FitPro.Mode.name(value.toInt())} — not sent")
                    null
                } else FitPro2.Id.WORKOUT_STATE to state.toFloat()
            }
            FitPro.Field.FAN_STATE, FitPro.Field.FAN_SPEED -> {
                // The fan's FitPro 2 encoding is not known yet, and guessing
                // costs nothing but a fan that stays as it is.
                if (!fanWarned) { fanWarned = true; Log.i(TAG, "fp2: fan control not mapped yet — ignored") }
                null
            }
            else -> null
        }

    /**
     * Null once the board accepts the write; a reason if it refuses or says nothing.
     *
     * An ERROR only counts if it is about *this* write: a WRITE request, and —
     * where the error carries one — this feature id. The board repeats its
     * latest frame every ~1.5 s, errors included, so a refusal from minutes
     * ago keeps arriving; read as "whatever we just sent was refused", it
     * failed writes the board had in fact taken (seen 11/10/2026: a PAUSED the
     * board acted on, logged as refused).
     */
    private fun awaitWrite(id: Int): String? {
        val until = SystemClock.elapsedRealtime() + WRITE_WAIT_MS
        while (SystemClock.elapsedRealtime() < until) {
            val raw = conn.receive(50) ?: continue
            val f = take(raw) ?: continue
            when (f.type) {
                FitPro2.Resp.ACK ->
                    if (f.payload.isNotEmpty() && f.payload[0].toInt() == FitPro2.Req.WRITE) return null
                FitPro2.Resp.EVENT ->
                    if (f.payload.size >= 2 && FitPro2.u16(f.payload, 0) == id) return null
                FitPro2.Resp.ERROR -> {
                    if (f.payload.isEmpty() || f.payload[0].toInt() != FitPro2.Req.WRITE) continue
                    val code = if (f.payload.size > 1) f.payload[1].toInt() and 0xFF else -1
                    // Codes 4, 5 and 7 name the feature. Another feature's
                    // refusal is not this one's.
                    if (f.payload.size >= 4 && code in setOf(4, 5, 7) && FitPro2.u16(f.payload, 2) != id) continue
                    return "fp2 ${FitPro2.errorName(code)} writing $id"
                }
            }
        }
        return "fp2 no answer writing $id"
    }

    /** Fold one packet into what we know. Returns it parsed, for anyone waiting on it. */
    private fun take(raw: ByteArray): FitPro2.Frame? {
        val f = FitPro2.parse(raw) ?: return null
        lastFrameMs = SystemClock.elapsedRealtime()
        if (f.type == FitPro2.Resp.EVENT && f.payload.size >= 6) {
            val id = FitPro2.u16(f.payload, 0)
            val v = FitPro2.f32(f.payload, 2)
            val before = values.put(id, v)
            if (id == FitPro2.Id.SYSTEM_MODE && before != v) Log.i(TAG, "fp2: SYSTEM_MODE -> ${v.toInt()}")
            if (id == FitPro2.Id.WORKOUT_STATE && before != v) {
                // Only leaving a driven state restarts the stale clock. The
                // board moves itself PAUSED → RESULTS after ten minutes, and
                // restarting the clock on that made the frozen reading live
                // again for 3 s — a false runaway every ten minutes, idle.
                if (before != null && driven(before.toInt())) stateChangedMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "fp2: WORKOUT_STATE -> ${v.toInt()}")
            }
            // Every real move of the belt reading. After a pause the poll loop
            // kept seeing the last running speed while the belt had stopped —
            // this is how we find out whether the board ever reports 0.
            // A change, not an arrival: the first value after subscribing is
            // whatever the board was left holding — on a stopped belt, the last
            // running speed — and counting it as movement raised a false
            // runaway at every app start.
            if (id == FitPro2.Id.CURRENT_KPH && before != null && before != v) {
                currentKphMovedMs = SystemClock.elapsedRealtime()
            }
            if (id == FitPro2.Id.CURRENT_KPH && Math.abs(v - lastCurrentKph) >= 0.1f) {
                lastCurrentKph = v
                Log.i(TAG, "fp2: CURRENT_KPH ${"%.2f".format(v)}")
            }
            if (id == FitPro2.Id.KEY_COOKED && v.toInt() != lastKey) {
                lastKey = v.toInt()
                if (lastKey != 0) Log.i(TAG, "fp2: key $lastKey")
            }
        } else if (f.type == FitPro2.Resp.ERROR) {
            // Once per distinct error: the board repeats it every ~1.5 s.
            val hex = FitPro.hex(raw, 3 + f.payload.size)
            if (hex != lastError) { lastError = hex; Log.w(TAG, "fp2: board error $hex") }
        }
        return f
    }

    private fun driven(state: Int) = state == FitPro2.WorkoutState.RUNNING ||
        state == FitPro2.WorkoutState.WARM_UP || state == FitPro2.WorkoutState.COOL_DOWN

    /** One collect loop for setup: read until [done] or [ms] runs out. */
    private fun collect(ms: Long, done: (FitPro2.Frame) -> Boolean) {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            val raw = conn.receive(200) ?: continue
            val f = take(raw) ?: continue
            if (done(f)) return
        }
    }

    /** The FitPro 1 view of the board. Only fields we actually have are present. */
    private fun translateIn(): Map<FitPro.Field, Double> {
        val out = HashMap<FitPro.Field, Double>()
        fun put(field: FitPro.Field, id: Int) { values[id]?.let { out[field] = it.toDouble() } }
        val mode = mode()
        // The target speed only means something while the belt is being driven.
        // A paused FitPro 2 board still holds its last target, where FitPro 1
        // reads 0 — and the poll loop adopts the board's target when idle, so
        // passing it through would hand a stale 6 km/h to the resume ramp.
        if (mode == FitPro.Mode.RUNNING || mode == FitPro.Mode.WARM_UP || mode == FitPro.Mode.COOL_DOWN) {
            put(FitPro.Field.KPH, FitPro2.Id.TARGET_KPH)
        } else if (values.containsKey(FitPro2.Id.TARGET_KPH)) {
            out[FitPro.Field.KPH] = 0.0
        }
        put(FitPro.Field.GRADE, FitPro2.Id.TARGET_GRADE)
        actualKph(mode)?.let { out[FitPro.Field.ACTUAL_KPH] = it }
        put(FitPro.Field.ACTUAL_INCLINE, FitPro2.Id.CURRENT_GRADE)
        put(FitPro.Field.MIN_KPH, FitPro2.Id.MIN_KPH)
        put(FitPro.Field.MAX_KPH, FitPro2.Id.MAX_KPH)
        put(FitPro.Field.MIN_GRADE, FitPro2.Id.MIN_GRADE)
        put(FitPro.Field.MAX_GRADE, FitPro2.Id.MAX_GRADE)
        put(FitPro.Field.CURRENT_TIME, FitPro2.Id.RUNNING_TIME)
        put(FitPro.Field.PULSE, FitPro2.Id.PULSE)
        put(FitPro.Field.CALORIES, FitPro2.Id.CURRENT_CALORIES)
        // FitPro 1 counts distance in metres. FitPro 2's unit is not yet
        // established, so this goes through unscaled and is checked against a
        // known walk before anything is built on it.
        put(FitPro.Field.DISTANCE, FitPro2.Id.DISTANCE)
        mode?.let { out[FitPro.Field.WORKOUT_MODE] = it.toDouble() }
        return out
    }

    /**
     * The belt's measured speed, with the board's frozen last reading taken out.
     *
     * Once the belt stops, this board never reports 0: CURRENT_KPH falls a
     * little and then simply stops changing — 2.13, 1.75, then nothing, with
     * the belt standing still (hardware, 11/10/2026). Passed through, the poll
     * loop sees a belt "still moving at 1.8 km/h outside a workout" for ever,
     * re-commands the stop every poll and records a runaway that never was.
     *
     * A belt that is turning gives a reading that jitters from one event to the
     * next; a stopped one gives the same float, repeated. So outside a running
     * state, a value that has not changed at all for [STALE_KPH_MS] is a
     * stopped belt. While running it always passes through, and anything still
     * changing passes through in any state — a belt winding down, or one
     * moving when it should not be, is never hidden.
     */
    private fun actualKph(mode: Int?): Double? {
        val v = values[FitPro2.Id.CURRENT_KPH]?.toDouble() ?: return null
        val driven = mode == FitPro.Mode.RUNNING || mode == FitPro.Mode.WARM_UP || mode == FitPro.Mode.COOL_DOWN
        if (driven) return v
        val since = maxOf(currentKphMovedMs, stateChangedMs)
        val still = SystemClock.elapsedRealtime() - since >= STALE_KPH_MS
        return if (still) 0.0 else v
    }

    /**
     * SYSTEM_MODE and WORKOUT_STATE folded into one FitPro 1 WorkoutMode.
     *
     * The safety key wins over everything, exactly as DMK does on FitPro 1 —
     * it is the one reading the poll loop latches. The key contact bounces for
     * a few seconds on the way back in; the latch is what absorbs that.
     */
    private fun mode(): Int? {
        if (values[FitPro2.Id.SYSTEM_MODE]?.toInt() == FitPro2.SystemMode.DMK) return FitPro.Mode.DMK
        return when (values[FitPro2.Id.WORKOUT_STATE]?.toInt() ?: return null) {
            FitPro2.WorkoutState.NONE, FitPro2.WorkoutState.READY_TO_START -> FitPro.Mode.IDLE
            FitPro2.WorkoutState.WARM_UP -> FitPro.Mode.WARM_UP
            FitPro2.WorkoutState.RUNNING -> FitPro.Mode.RUNNING
            FitPro2.WorkoutState.COOL_DOWN -> FitPro.Mode.COOL_DOWN
            FitPro2.WorkoutState.PAUSED, FitPro2.WorkoutState.OFF_MACHINE -> FitPro.Mode.PAUSE
            FitPro2.WorkoutState.RESULTS -> FitPro.Mode.RESULTS
            else -> FitPro.Mode.UNKNOWN
        }
    }
}
