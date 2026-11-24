package dev.stride.hud

/**
 * FitPro protocol — the ICON/NordicTrack control board ("brainboard").
 *
 * Derived from ICON's own .NET implementation; see re/FITPRO_PROTOCOL.md.
 * This file owns framing and encoding only — no I/O.
 */
object FitPro {

    const val MAX_MSG = 64

    // --- Commands (Sindarin.FitPro1.Commands.Command) -----------------------
    object Cmd {
        const val NONE = 0
        const val READ_WRITE_DATA = 2
        const val CONNECT = 4
        const val DISCONNECT = 5
        const val SUPPORTED_DEVICES = 128
        const val DEVICE_INFO = 129
        const val SYSTEM_INFO = 130
        const val VERSION_INFO = 132
        const val VERIFY_SECURITY = 144
        const val SPEED_GRADE_LIMIT = 146
        // DELIBERATELY ABSENT: Update(9) and EnterBootloader(56) are firmware
        // operations. `02 04 09 0F` resets the board. Never expose them here.
    }

    // --- Devices (com.ifit.shire.fitpro.equipment.dataobjects.Device) -------
    object Dev {
        const val MAIN: Byte = 2
        const val TREADMILL: Byte = 4
        const val SPEED: Byte = 65
        const val GRADE: Byte = 66
    }

    /**
     * Byte 3 of a response (Sindarin.FitPro1.Commands.CmdStatus).
     *
     * Only [DONE] carries usable field data. Anything else — including the
     * failure codes — still arrives as a well-formed, correctly-checksummed
     * frame, but with the data region filled with 0xFF. Parsing one of those
     * yields a treadmill that has apparently run 4,294,967 km.
     */
    object Status {
        const val DEV_NOT_SUPPORTED = 0
        const val CMD_NOT_SUPPORTED = 1
        const val DONE = 2
        const val IN_PROGRESS = 3
        const val FAILED = 4
        /** Not a failure: the board wants VerifySecurity. See [securityHash]. */
        const val SECURITY_BLOCK = 8

        fun name(v: Int) = when (v) {
            DEV_NOT_SUPPORTED -> "devNotSupported"; CMD_NOT_SUPPORTED -> "cmdNotSupported"
            DONE -> "done"; IN_PROGRESS -> "inProgress"; FAILED -> "failed"
            SECURITY_BLOCK -> "securityBlock"
            5 -> "timeLeft"; 7 -> "unknownFailure"; 9 -> "commFailed"
            else -> "status$v"
        }
    }

    /**
     * Console state (Sindarin.FitPro1.DataObjects.WorkoutMode).
     *
     * The belt will not respond to a speed write while the console is IDLE —
     * that is a hardware interlock, not a protocol quirk. Incline moves
     * regardless, which is how we found this.
     */
    object Mode {
        const val UNKNOWN = 0
        const val IDLE = 1
        const val RUNNING = 2
        const val PAUSE = 3
        const val RESULTS = 4
        const val DEBUG = 5
        const val LOG = 6
        const val MAINTENANCE = 7
        /** The safety key is out. See MainActivity.accumulate. */
        const val DMK = 8
        const val DEMO = 9
        const val WARM_UP = 10
        const val COOL_DOWN = 11

        /**
         * The machine itself is dormant — not the panel, the hardware.
         *
         * The board runs an idle timer of its own and drops into this mode
         * when it expires, which on a treadmill left alone for a week is what
         * you come back to: the Android side is fine, the HUD draws, and the
         * belt will not move for anything. Writing [IDLE] is the way back —
         * it is the transition ICON's own console makes to bring a board out
         * of any non-idle state.
         *
         * The whole reason this list is now complete: 12 used to render as
         * "mode12" in the log, which is not a thing anybody recognises as a
         * sleeping treadmill.
         */
        const val SLEEP = 12
        const val RESUME = 13
        const val LOCKED = 14
        const val PAUSE_OVERRIDE = 20

        fun name(v: Int) = when (v) {
            UNKNOWN -> "unknown"; IDLE -> "idle"; RUNNING -> "running"
            PAUSE -> "pause"; RESULTS -> "results"; DEBUG -> "debug"
            LOG -> "log"; MAINTENANCE -> "maintenance"; DMK -> "safetyKeyOut"
            DEMO -> "demo"; WARM_UP -> "warmup"; COOL_DOWN -> "cooldown"
            SLEEP -> "sleep"; RESUME -> "resume"; LOCKED -> "locked"
            PAUSE_OVERRIDE -> "pauseOverride"
            else -> "mode$v"
        }
    }

    /**
     * A readable/writable value on the board.
     *
     * [size] is the converter width in bytes; [scale] and [signed] describe the
     * on-wire encoding. Speed is unsigned, grade is *signed* — that asymmetry is
     * real and getting it wrong turns -3% into ~655%.
     */
    enum class Field(
        val id: Int,
        val size: Int,
        val scale: Double = 1.0,
        val signed: Boolean = false,
        val writable: Boolean = false,
        val label: String = "",
        val unit: String = "",
    ) {
        KPH(0, 2, 0.01, false, true, "Target speed", "km/h"),
        GRADE(1, 2, 0.01, true, true, "Target incline", "%"),
        RPM(5, 2, 1.0, false, false, "Belt RPM", ""),
        DISTANCE(6, 4, 1.0, false, false, "Distance", "m"),
        FAN_SPEED(8, 1, 1.0, false, true, "Fan", ""),
        // 4 bytes wide, but only byte 0 is bpm — see decode().
        PULSE(10, 4, 1.0, false, false, "Pulse", "bpm"),
        WORKOUT_MODE(12, 1, 1.0, false, true, "Mode", ""),
        // CaloriesConverter: raw * 1024 / 1e8
        CALORIES(13, 4, 1024.0 / 100_000_000.0, false, false, "Calories", "kcal"),
        ACTUAL_KPH(16, 2, 0.01, false, false, "Speed", "km/h"),
        ACTUAL_INCLINE(17, 2, 0.01, true, false, "Incline", "%"),
        ACTUAL_DISTANCE(19, 4, 1.0, false, false, "Distance", "m"),
        CURRENT_TIME(20, 4, 1.0, false, false, "Elapsed", "s"),
        MAX_GRADE(27, 2, 0.01, true, false, "Max incline", "%"),
        MIN_GRADE(28, 2, 0.01, true, false, "Min incline", "%"),
        MAX_KPH(30, 2, 0.01, false, false, "Max speed", "km/h"),
        MIN_KPH(31, 2, 0.01, false, false, "Min speed", "km/h"),
        /* The board's own account of when it will go to sleep. All three are
           writable on the hardware and all three are declared read-only here
           on purpose: waking the machine is a [Mode] change, and changing how
           long it stays awake is a different decision that nobody has asked
           for. Read once at startup and logged — see MainActivity's
           readSleepConfig. */
        IDLE_TIMEOUT(34, 2, 1.0, false, false, "Idle timeout", "s"),
        /* The belt's cold-start interlock (ICON BitField IdleModeLockout).
           A 1-byte bool. When 1, the board refuses every software
           WorkoutMode=running write from IDLE (status:failed, 04 05 02 04 0F) —
           the belt cannot be started in software. When 0, it can. The board
           sets it to 1 whenever it idles out (after IDLE_TIMEOUT), so a machine
           left sitting relocks itself. iFit manages it explicitly:
           SetIdleModeLockout(true) at init, then false on the workout flow for
           aerobic machines (treadmills). So it is WRITABLE here, and MainActivity
           clears it (=0) before any belt-starting write — this is what lets the
           Elite 900 (and any board that relocks on idle) start the belt in pure
           software. Found by decompiling the stock console app. */
        IDLE_MODE_LOCKOUT(95, 1, 1.0, false, true, "Idle lockout", ""),
        // FanState: 0 Off, 1 Low, 2 Medium, 3 High, 4 Auto
        FAN_STATE(98, 1, 1.0, false, true, "Fan", ""),
        SLEEP_TIMER_STATE(107, 1, 1.0, false, false, "Sleep timer", ""),
        ;

        /** Decode this field's raw bytes into a scaled value. */
        fun decode(b: ByteArray, off: Int): Double {
            // Pulse is a packed struct, not a number:
            //   [0] userPulse (bpm)  [1] average  [2] count  [3] source
            // Reading all four as an integer yields garbage once a strap connects.
            if (this == PULSE) return (b[off].toLong() and 0xFF).toDouble()

            var raw = 0L
            for (i in 0 until size) raw = raw or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            if (signed) {
                val signBit = 1L shl (size * 8 - 1)
                if (raw and signBit != 0L) raw -= (1L shl (size * 8))
            }
            return raw * scale
        }

        /**
         * True if this field's bytes are all 0xFF — the board's "no value"
         * fill. Left unscaled it looks like a real number: 0xFFFFFFFF metres
         * is 4,294,967 km, and as a signed grade it reads a believable -0.01 %.
         */
        fun isSentinel(b: ByteArray, off: Int): Boolean {
            for (i in 0 until size) if ((b[off + i].toInt() and 0xFF) != 0xFF) return false
            return true
        }

        /** Encode a scaled value back to wire bytes. */
        fun encode(value: Double): ByteArray {
            val raw = Math.round(value / scale)
            return ByteArray(size) { i -> ((raw shr (8 * i)) and 0xFF).toByte() }
        }
    }

    /** Truncated sum of every byte before the checksum. */
    fun checksum(frame: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length - 1) sum += frame[i].toInt() and 0xFF
        return sum.toByte()
    }

    /**
     * One section of a ReadWriteData payload: a length byte, then one bitmask
     * byte per group of 8 field ids. Empty sections are a single 0x00.
     */
    private fun section(fields: List<Field>): ByteArray {
        if (fields.isEmpty()) return byteArrayOf(0)
        val numBytes = (fields.maxOf { it.id } / 8) + 1
        val out = ByteArray(1 + numBytes)
        out[0] = numBytes.toByte()
        for (f in fields) {
            val idx = 1 + (f.id / 8)
            out[idx] = (out[idx].toInt() or (1 shl (f.id % 8))).toByte()
        }
        return out
    }

    /**
     * Build a ReadWriteData frame.
     *
     * Write values are applied first, then reads are requested. Both lists are
     * sorted ascending by field id — the board relies on that ordering, and so
     * does response parsing.
     */
    fun readWrite(
        device: Byte,
        reads: List<Field> = emptyList(),
        writes: Map<Field, Double> = emptyMap(),
    ): ByteArray {
        val writeFields = writes.keys.filter { it.writable }.sortedBy { it.id }
        val readFields = reads.sortedBy { it.id }

        val body = ArrayList<Byte>()
        section(writeFields).forEach { body.add(it) }
        for (f in writeFields) f.encode(writes.getValue(f)).forEach { body.add(it) }
        section(readFields).forEach { body.add(it) }

        val length = body.size + 4
        require(length <= MAX_MSG) { "frame too long: $length" }

        val frame = ByteArray(length)
        frame[0] = device
        frame[1] = length.toByte()
        frame[2] = Cmd.READ_WRITE_DATA.toByte()
        for (i in body.indices) frame[3 + i] = body[i]
        frame[length - 1] = checksum(frame, length)
        return frame
    }

    /** A simple no-payload command, e.g. Connect. */
    fun simple(device: Byte, command: Int): ByteArray {
        val frame = ByteArray(4)
        frame[0] = device
        frame[1] = 4
        frame[2] = command.toByte()
        frame[3] = checksum(frame, 4)
        return frame
    }

    /** A command with a payload: `[device][length][cmd][content…][checksum]`. */
    fun command(device: Byte, cmd: Int, content: ByteArray = ByteArray(0)): ByteArray {
        val length = content.size + 4
        require(length <= MAX_MSG) { "frame too long: $length" }
        val frame = ByteArray(length)
        frame[0] = device
        frame[1] = length.toByte()
        frame[2] = cmd.toByte()
        content.copyInto(frame, 3)
        frame[length - 1] = checksum(frame, length)
        return frame
    }

    /* ---------------------------------------------------------------- *
     * Security
     * ---------------------------------------------------------------- *
     *
     * The board locks itself, and a locked board answers *every* ReadWriteData
     * with status 8 ([Status.SECURITY_BLOCK]) — reads included. It is not a
     * failure and it is not about the safety key: it means "authenticate".
     *
     * ICON's own console treats it exactly that way. FitPro1Console.cs:386:
     *
     *     if (command2 != null && command2.Status == CmdStatus.SecurityBlock)
     *     {
     *         Log.Trace("FitnessConsole", "Unlocking again", null);
     *         await Unlock().ConfigureAwait(false);
     *     }
     *
     * — and it re-unlocks again on any transition into ConsoleState.Locked
     * (line 103). Unlocking is routine housekeeping, not a one-off setup step.
     *
     * We did not implement it, and got away with it for months because the
     * board was still holding an unlock from a stock-app session. On
     * 2026-08-07 that lapsed: every frame came back securityBlock, the poll
     * loop rejected all of them, and the console became a HUD that could
     * navigate but could not act. Nothing had changed but time and power
     * cycles.
     */

    /**
     * The 32-byte challenge (`EquipmentUtil.CalculateSecurityHash`).
     *
     * Each byte starts as its own 1-based index, then mixes in either the part
     * number or the model depending on the corresponding bit of the serial
     * number. Byte-truncating arithmetic throughout — the intermediate values
     * overflow deliberately, which is why every step is masked back to 8 bits.
     */
    fun securityHash(serialNumber: Int, partNumber: Int, modelNumber: Int): ByteArray {
        val out = ByteArray(32)
        for (b in 0 until 32) {
            var v = (b + 1) and 0xFF
            if ((serialNumber ushr b) and 1 == 1) {
                val p = if (b < 16) ((partNumber shl 16) or (partNumber ushr 16)) ushr b
                        else partNumber ushr b
                v = v xor (p and 0xFF)
            } else {
                v = v xor ((v * (b + modelNumber)) and 0xFF)
            }
            out[b] = v.toByte()
        }
        return out
    }

    /** VerifySecurity: the 32-byte hash, then `8 * masterLibraryVersion` LE. */
    fun verifySecurity(device: Byte, hash: ByteArray, masterLibraryVersion: Int): ByteArray {
        require(hash.size == 32) { "hash must be 32 bytes, was ${hash.size}" }
        val content = ByteArray(36)
        hash.copyInto(content, 0)
        putIntLe(content, 32, 8 * masterLibraryVersion)
        return command(device, Cmd.VERIFY_SECURITY, content)
    }

    /** True if this reply is the board asking to be unlocked. */
    fun isSecurityBlock(reply: ByteArray): Boolean =
        reply.size >= 5 && isValid(reply) && (reply[3].toInt() and 0xFF) == Status.SECURITY_BLOCK

    private fun putIntLe(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u8(b: ByteArray, off: Int) = b[off].toInt() and 0xFF
    private fun u32le(b: ByteArray, off: Int) =
        u8(b, off) or (u8(b, off + 1) shl 8) or (u8(b, off + 2) shl 16) or (u8(b, off + 3) shl 24)

    /**
     * The three identity reads the hash is built from. Response data begins at
     * offset 4 in every case, after device / length / command / status, and
     * every multi-byte value is little-endian.
     *
     * Returns null rather than guessing when the frame is short or not Done —
     * a hash built from a misread serial number is not a hash, it is a lockout.
     */
    private fun payload(reply: ByteArray, need: Int): ByteArray? {
        if (!isValid(reply)) return null
        if ((reply[3].toInt() and 0xFF) != Status.DONE) return null
        val len = reply[1].toInt() and 0xFF
        if (len < 4 + need + 1) return null
        return reply
    }

    /** DeviceInfo (129) → software version and the board serial number. */
    class DeviceIdentity(val softwareVersion: Int, val serialNumber: Int)

    fun parseDeviceInfo(reply: ByteArray): DeviceIdentity? {
        val f = payload(reply, 6) ?: return null
        return DeviceIdentity(softwareVersion = u8(f, 4), serialNumber = u32le(f, 6))
    }

    /** SystemInfo (130) → model and part number. */
    class SystemIdentity(val model: Int, val partNumber: Int)

    fun parseSystemInfo(reply: ByteArray): SystemIdentity? {
        val f = payload(reply, 11) ?: return null
        val model = u32le(f, 7)
        var part = u32le(f, 11)
        // ICON's own fix-up, carried across verbatim: one production run
        // reports a part number that does not match its own security hash.
        if (part == 370357 && model == 39915) part = 374677
        return SystemIdentity(model = model, partNumber = part)
    }

    /** VersionInfo (132) → the master library version the secret key derives from. */
    fun parseMasterLibraryVersion(reply: ByteArray): Int? {
        val f = payload(reply, 1) ?: return null
        return u8(f, 4)
    }

    /**
     * True if the frame's trailing checksum matches its contents.
     *
     * Worth checking: an unvalidated garbled frame parses as plausible-looking
     * numbers and poisons everything downstream — we saw a 650 km/h reading on
     * a machine that tops out at 19.
     */
    fun isValid(frame: ByteArray): Boolean {
        if (frame.size < 4) return false
        val len = frame[1].toInt() and 0xFF
        if (len < 4 || len > frame.size) return false
        return frame[len - 1] == checksum(frame, len)
    }

    /** How long a ReadWriteData response for [reads] must be, to the byte. */
    fun expectedLength(reads: List<Field>): Int = 4 + reads.sumOf { it.size } + 1

    /** Why a response was thrown away, for logging. Null means it was good. */
    fun rejectReason(response: ByteArray, reads: List<Field>): String? {
        if (response.size < 5) return "short:${response.size}"
        if (!isValid(response)) return "checksum"
        val status = response[3].toInt() and 0xFF
        if (status != Status.DONE) return "status:${Status.name(status)}"
        val want = expectedLength(reads)
        val got = response[1].toInt() and 0xFF
        if (got != want) return "length:$got!=$want"
        var off = 4
        for (f in reads.sortedBy { it.id }) {
            if (f.isSentinel(response, off)) return "sentinel:${f.name}"
            off += f.size
        }
        return null
    }

    /**
     * Parse a ReadWriteData response. Field data begins at offset 4, after
     * device / length / command / status, in ascending field-id order.
     *
     * Returns empty unless the frame is *completely* trustworthy — right
     * checksum, status Done, exactly the length our read list implies, and no
     * 0xFF fill. A half-trusted frame is worse than none: it repaints the whole
     * HUD, so one bad response flashes maxed-out numbers across every tile.
     */
    fun parse(response: ByteArray, reads: List<Field>): Map<Field, Double> {
        val out = LinkedHashMap<Field, Double>()
        if (rejectReason(response, reads) != null) return out
        var off = 4
        for (f in reads.sortedBy { it.id }) {
            out[f] = f.decode(response, off)
            off += f.size
        }
        return out
    }

    fun hex(b: ByteArray, len: Int = b.size): String =
        (0 until minOf(len, b.size)).joinToString(" ") { "%02X".format(b[it]) }
}
