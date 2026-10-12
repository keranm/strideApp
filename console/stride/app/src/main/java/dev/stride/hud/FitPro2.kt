package dev.stride.hud

/**
 * FitPro 2 — the Gen 7 control board (USB 0x213C/0x0003, "iFIT-LargeX").
 *
 * Not FitPro 1 on a new product id: different framing, no checksum, every value
 * a float32, and the board pushes values rather than being polled. See
 * protocol/FITPRO2_PROTOCOL.md. This file owns framing and encoding only — no
 * I/O — and it is also the gate: it will not build a frame STRIDE has no
 * business sending.
 *
 * The one rule above all the others: a FitPro 1 frame must never reach this
 * board. FitPro 1's length byte lands in FitPro 2's type slot, and a 9-byte
 * FitPro 1 frame reads as BOOTLOADER. Nothing in here builds one, and
 * MainActivity decides which protocol to speak from the product id alone.
 */
object FitPro2 {

    const val PID = 0x0003
    const val CHANNEL: Byte = 0x02

    /** Request types: the low nibble of byte 1, sent with source APP (0x0_). */
    object Req {
        const val SUBSCRIBE = 0x1
        const val WRITE = 0x2
        const val SUPPORTED_FEATURES = 0x6
        const val EXTENDED = 0xE
        // DELIBERATELY ABSENT: BOOTLOADER (0x9), UNSUBSCRIBE (0x7) and every
        // other channel — file system 0x01, firmware update 0x06. Never add them.
    }

    /** Byte 1 of a frame from the board: source CONSOLE (0x1_) | type. */
    object Resp {
        const val FEATURES = 0x11
        const val ACK = 0x13
        const val ERROR = 0x14
        const val EVENT = 0x15
        const val EXTENDED = 0x1E
    }

    const val INFO_PRODUCT: Byte = 2

    /** Feature ids STRIDE uses. Names are iFit's. */
    object Id {
        const val SYSTEM_MODE = 102
        const val KEY_COOKED = 109
        const val CURRENT_CALORIES = 202
        const val PULSE = 222
        const val DISTANCE = 252
        const val TARGET_KPH = 301
        const val CURRENT_KPH = 302
        const val MIN_KPH = 303
        const val MAX_KPH = 304
        const val TARGET_GRADE = 401
        const val CURRENT_GRADE = 402
        const val MIN_GRADE = 403
        const val MAX_GRADE = 404
        const val WORKOUT_STATE = 602
        const val RUNNING_TIME = 604
    }

    /**
     * The only features STRIDE may write. Speed, incline, and the workout state
     * that starts, pauses and ends a walk — the same three things it drives on
     * FitPro 1. Calibration, system mode, motor parameters and the rest are out
     * of reach by construction, not by care.
     */
    val WRITABLE = setOf(Id.TARGET_KPH, Id.TARGET_GRADE, Id.WORKOUT_STATE)

    /** SYSTEM_MODE values. 2 is the safety key out ("DMK"), seen on hardware. */
    object SystemMode {
        const val BOOT_UP = 0
        const val IDLE = 1
        const val DMK = 2
        const val WORKOUT = 3
        const val MAINTENANCE = 4
        const val ERROR = 5
    }

    /** WORKOUT_STATE values. */
    object WorkoutState {
        const val NONE = 0
        const val READY_TO_START = 1
        const val WARM_UP = 2
        const val RUNNING = 3
        const val COOL_DOWN = 4
        const val PAUSED = 5
        const val RESULTS = 6
        const val OFF_MACHINE = 7
    }

    fun productInfo(): ByteArray = frame(Req.EXTENDED, byteArrayOf(INFO_PRODUCT))

    /** Exactly three bytes, no padding: the way iFit sends this one. */
    fun supportedFeatures(): ByteArray = byteArrayOf(CHANNEL, Req.SUPPORTED_FEATURES.toByte(), 0)

    fun subscribe(ids: List<Int>): ByteArray {
        require(ids.isNotEmpty() && ids.size <= 8) { "subscribe 1..8 ids, got ${ids.size}" }
        val p = ByteArray(ids.size * 2)
        ids.forEachIndexed { i, id -> p[2 * i] = id.toByte(); p[2 * i + 1] = (id shr 8).toByte() }
        return frame(Req.SUBSCRIBE, p)
    }

    fun write(id: Int, value: Float): ByteArray {
        require(id in WRITABLE) { "FitPro 2 feature $id is not writable from STRIDE" }
        require(!value.isNaN() && !value.isInfinite()) { "not a number: $value" }
        val bits = java.lang.Float.floatToIntBits(value)
        return frame(Req.WRITE, byteArrayOf(
            id.toByte(), (id shr 8).toByte(),
            bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte(),
        ))
    }

    /**
     * `[0x02][APP | type][length][payload][0x00]`. No checksum. The trailing
     * zero is iFit's own padding — nothing on either side reads it, and
     * copying it is the cautious choice.
     */
    private fun frame(type: Int, payload: ByteArray): ByteArray {
        require(type == Req.SUBSCRIBE || type == Req.WRITE || type == Req.EXTENDED) {
            "FitPro 2 request type $type is not one STRIDE sends"
        }
        val f = ByteArray(3 + payload.size + 1)
        f[0] = CHANNEL
        f[1] = type.toByte()
        f[2] = payload.size.toByte()
        payload.copyInto(f, 3)
        return f
    }

    /** One parsed frame from the board. [payload] is exactly the declared length. */
    class Frame(val type: Int, val payload: ByteArray)

    fun parse(raw: ByteArray, n: Int = raw.size): Frame? {
        if (n < 3 || raw[0] != CHANNEL) return null
        val len = raw[2].toInt() and 0xFF
        if (3 + len > n) return null
        return Frame(raw[1].toInt() and 0xFF, raw.copyOfRange(3, 3 + len))
    }

    fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun f32(b: ByteArray, off: Int): Float = java.lang.Float.intBitsToFloat(
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24))

    fun errorName(code: Int) = when (code) {
        2 -> "framing"; 3 -> "featuresNotSupported"; 4 -> "writeNotSupported"
        5 -> "dataOutOfRange"; 6 -> "requestNotSupported"; 7 -> "writeValueNotAllowed"
        else -> "error$code"
    }
}
