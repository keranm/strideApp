package dev.stride.usbprobe

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * STRIDE USB probe — READ ONLY.
 *
 * Answers four questions about the treadmill control board:
 *   1. Can a normal app claim it via UsbManager?
 *   2. Which protocol does it speak, and does it answer?
 *   3. Does it stream input reports unprompted?
 *   4. What do those reports look like, and which bytes move?
 *
 * Gen 6 boards (PID 2, FitPro 1) get the four identity queries in [IDENTITY],
 * the same ones STRIDE sends before it does anything else. Gen 7 boards
 * (PID 3, FitPro 2) get product info, the feature list and a subscription —
 * see [askFitPro2]. Neither path writes a field. Nothing here can move the
 * belt or the deck.
 */
class MainActivity : Activity() {

    companion object {
        const val TAG = "StrideProbe"
        const val ACTION_USB_PERMISSION = "dev.stride.usbprobe.USB_PERMISSION"

        // ICON Fitness. 0x0002 is the Gen 6 "ICON Generic HID" board STRIDE
        // was built on; 0x0003 is the Gen 7 "iFIT-LargeX", vendor class 255.
        const val VID = 0x213c
        val PIDS = listOf(0x0002, 0x0003)

        const val DEV_MAIN = 2

        /**
         * Identity queries, as (name, command, payload). Read-only by
         * construction: SupportedDevices, DeviceInfo, SystemInfo, VersionInfo.
         * Update (9) and EnterBootloader (56) are firmware operations and must
         * never appear here — `02 04 09 0F` resets the board.
         */
        val IDENTITY = listOf(
            Triple("SupportedDevices", 128, byteArrayOf()),
            Triple("DeviceInfo", 129, byteArrayOf()),
            Triple("SystemInfo", 130, byteArrayOf(0, 0)),
            Triple("VersionInfo", 132, byteArrayOf(0, 0)),
        )

        // FitPro 2 (PID 3). Only these request types may leave this app:
        // SUBSCRIBE 0x1, SUPPORTED_FEATURES 0x6, EXTENDED 0xE (product info
        // only). No WRITE (0x2), no BOOTLOADER (0x9), no other channel.
        const val PID_FITPRO2 = 0x0003
        const val FP2_SUBSCRIBE = 0x1
        const val FP2_SUPPORTED_FEATURES = 0x6
        const val FP2_EXTENDED = 0xE
        const val FP2_INFO_PRODUCT: Byte = 2
        val FP2_ALLOWED = setOf(FP2_SUBSCRIBE, FP2_SUPPORTED_FEATURES, FP2_EXTENDED)

        /** Features to subscribe to, if the board advertises them. Eight is iFit's batch size. */
        val WATCH = linkedMapOf(
            102 to "SYSTEM_MODE",
            602 to "WORKOUT_STATE",
            302 to "CURRENT_KPH",
            402 to "CURRENT_GRADE_%",
            303 to "MIN_KPH",
            304 to "MAX_KPH",
            403 to "MIN_GRADE_%",
            404 to "MAX_GRADE_%",
        )
        val SYSTEM_MODES = listOf("BOOT_UP", "IDLE", "DMK (safety key out)", "WORKOUT", "MAINTENANCE", "ERROR")
        val WORKOUT_STATES = listOf("NONE", "READY_TO_START", "WARM_UP", "RUNNING", "COOL_DOWN",
            "PAUSED", "RESULTS", "OFF_MACHINE")
        val PRODUCT_ITEMS = mapOf(
            1 to "software version", 2 to "software part", 3 to "hardware part",
            4 to "model", 5 to "serial", 6 to "display type", 9 to "motor controller version",
            13 to "product serial", 20 to "motor controller type",
        )

        const val MAX_LINES = 14
    }

    @Volatile private var fitPro2 = false
    private val live = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    private lateinit var headerView: TextView
    private lateinit var maskView: TextView
    private lateinit var logView: TextView

    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    /** Per-offset record of every distinct value seen — the decoding aid. */
    private val seenValues = HashMap<Int, MutableSet<Int>>()
    private var firstReport: ByteArray? = null
    private val lines = ArrayDeque<String>()
    private var reportCount = 0L
    private var lastRendered = ""

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            // Ask the manager rather than trust the extras alone, so a reply
            // that arrives without them still gets the right answer.
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                ?: manager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId in PIDS }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                (device != null && manager.hasPermission(device))
            if (granted && device != null) {
                append("permission GRANTED")
                openAndRead(device)
            } else {
                append("permission DENIED — cannot proceed")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }

        findAndRequest()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
        }

        headerView = TextView(this).apply {
            setTextColor(Color.parseColor("#7FDBFF"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            text = "STRIDE USB probe — read only\nscanning…"
        }
        root.addView(headerView)

        maskView = TextView(this).apply {
            setTextColor(Color.parseColor("#FFDC00"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
        }
        root.addView(maskView)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        )

        setContentView(root)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun findAndRequest() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val all = manager.deviceList.values

        Log.i(TAG, "enumerating ${all.size} usb device(s)")
        all.forEach {
            Log.i(TAG, "  ${it.deviceName} vid=0x${it.vendorId.toString(16)} " +
                    "pid=0x${it.productId.toString(16)} ${it.manufacturerName} / ${it.productName}")
        }

        val device = all.firstOrNull { it.vendorId == VID && it.productId in PIDS }
        if (device == null) {
            setHeader(
                "STRIDE USB probe — read only\n" +
                "TARGET NOT FOUND (looking for ${hex4(VID)}:${PIDS.joinToString("/") { hex4(it) }})\n" +
                "saw ${all.size} device(s): " +
                all.joinToString { "${hex4(it.vendorId)}:${hex4(it.productId)}" }
            )
            append("Is com.ifit.eru still holding the device? It must stay disabled.")
            return
        }

        describe(device)

        if (manager.hasPermission(device)) {
            append("already have permission")
            openAndRead(device)
        } else {
            append("requesting permission…")
            // Must be MUTABLE: UsbManager answers by filling in EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED, and an immutable intent arrives with
            // neither, which reads as "denied". Found on a Gen 7, Android 13.
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
            )
            manager.requestPermission(device, pi)
        }
    }

    /** Dump the descriptor tree — needed to know which endpoint to talk to later. */
    private fun describe(device: UsbDevice) {
        val sb = StringBuilder()
        sb.append("STRIDE USB probe — read only\n")
        sb.append("${device.manufacturerName} / ${device.productName}\n")
        sb.append("vid=${hex4(device.vendorId)} pid=${hex4(device.productId)} ")
        sb.append("class=${device.deviceClass} ifaces=${device.interfaceCount}\n")

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            sb.append("  if$i class=${intf.interfaceClass} sub=${intf.interfaceSubclass} ")
            sb.append("proto=${intf.interfaceProtocol} eps=${intf.endpointCount}\n")
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT"
                sb.append("    ep$e $dir type=${epType(ep.type)} ")
                sb.append("addr=0x${ep.address.toString(16)} max=${ep.maxPacketSize} ")
                sb.append("interval=${ep.interval}\n")
            }
        }
        setHeader(sb.toString().trimEnd())
        Log.i(TAG, sb.toString())
    }

    private fun openAndRead(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager

        val conn = manager.openDevice(device)
        if (conn == null) {
            append("openDevice() returned null — another app holds the device")
            return
        }
        connection = conn

        // Prefer an interface that actually has an interrupt IN endpoint.
        var chosen: UsbInterface? = null
        var inEp: UsbEndpoint? = null
        outer@ for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                     ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
                ) {
                    chosen = intf; inEp = ep; break@outer
                }
            }
        }

        if (chosen == null || inEp == null) {
            append("no IN endpoint found — cannot read")
            return
        }

        // force=true detaches any kernel driver already bound to the interface.
        if (!conn.claimInterface(chosen, true)) {
            append("claimInterface() FAILED")
            return
        }
        claimedInterface = chosen
        append("claimed if=${chosen.id}, reading ep 0x${inEp.address.toString(16)} " +
                "(${inEp.maxPacketSize} bytes)")

        // A HID device declares its own report layout. Read it before guessing
        // at bytes — this is a standard read-only control transfer.
        dumpRawDescriptors(conn)
        fetchHidReportDescriptor(conn, chosen.id)

        // The OUT endpoint on the same interface, for the identity queries.
        // Gen 6 has it at 0x02, Gen 7 at 0x01, so find it rather than assume.
        var outEp: UsbEndpoint? = null
        for (e in 0 until chosen.endpointCount) {
            val ep = chosen.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT &&
                (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                 ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
            ) { outEp = ep; break }
        }

        running = true
        fitPro2 = device.productId == PID_FITPRO2
        val ep = inEp
        thread(name = "usb-read") {
            // The two protocols must never be crossed. A FitPro 1 frame's length
            // byte lands in FitPro 2's type slot, and a 9-byte one would read as
            // BOOTLOADER. So the product id alone decides which one gets spoken.
            when {
                outEp == null -> append("no OUT endpoint — listening only")
                fitPro2 -> askFitPro2(conn, outEp, ep)
                else -> askIdentity(conn, outEp, ep)
            }

            val buf = ByteArray(ep.maxPacketSize.coerceAtLeast(8))
            var consecutiveTimeouts = 0
            while (running) {
                val n = conn.bulkTransfer(ep, buf, buf.size, 1000)
                when {
                    n > 0 -> {
                        consecutiveTimeouts = 0
                        onReport(buf.copyOf(n))
                    }
                    n == 0 -> { /* zero-length packet; ignore */ }
                    else -> {
                        consecutiveTimeouts++
                        if (consecutiveTimeouts == 5) {
                            append("no data after 5s — device may be silent until polled")
                        }
                    }
                }
            }
        }
    }

    /**
     * Send each [IDENTITY] query to device MAIN and report what comes back.
     * A reply that echoes the device and command and passes the FitPro
     * checksum means the board speaks FitPro. Anything else means it doesn't,
     * or not in this framing.
     */
    private fun askIdentity(conn: UsbDeviceConnection, out: UsbEndpoint, inn: UsbEndpoint) {
        var understood = 0
        for ((name, cmd, payload) in IDENTITY) {
            require(cmd != 9 && cmd != 56) { "firmware command in the identity list" }
            val frame = frame(DEV_MAIN, cmd, payload)
            val sent = conn.bulkTransfer(out, frame, frame.size, 200)
            if (sent < 0) {
                append("$name: write failed (rc=$sent)")
                continue
            }
            val buf = ByteArray(inn.maxPacketSize.coerceAtLeast(64))
            val n = conn.bulkTransfer(inn, buf, buf.size, 1000)
            if (n <= 0) {
                append("$name → ${hex(frame)} : no reply")
                continue
            }
            val reply = buf.copyOf(n)
            val ok = isFitPro(reply, cmd)
            if (ok) understood++
            val status = if (n > 3) " status=${reply[3].toInt() and 0xFF}" else ""
            append("$name → ${if (ok) "FITPRO OK" else "NOT FITPRO"}$status")
            Log.i(TAG, "$name sent ${hex(frame)} got ${hex(reply)}")
            Thread.sleep(100)
        }
        val verdict = when (understood) {
            IDENTITY.size -> "VERDICT: speaks FitPro (${understood}/${IDENTITY.size})"
            0 -> "VERDICT: does NOT answer FitPro framing (0/${IDENTITY.size})"
            else -> "VERDICT: partly FitPro (${understood}/${IDENTITY.size})"
        }
        append(verdict)
    }

    /**
     * FitPro 2 (Gen 7, PID 3), read only. See protocol/FITPRO2_PROTOCOL.md.
     *
     * Product info, then the supported-feature list, then a subscription to
     * the handful of features worth watching. Every frame goes through
     * [fp2Frame], which refuses anything that is not one of those three
     * requests, so there is no WRITE here to send by mistake.
     */
    private fun askFitPro2(conn: UsbDeviceConnection, out: UsbEndpoint, inn: UsbEndpoint) {
        append("FitPro 2 board — asking product info")
        send2(conn, out, fp2Frame(FP2_EXTENDED, byteArrayOf(FP2_INFO_PRODUCT)))
        val info = LinkedHashMap<Int, String>()
        collect(conn, inn, 3000) { r ->
            if (r.type() != 0x1E || r.len() < 2 || r[3] != FP2_INFO_PRODUCT) return@collect false
            val item = r[4].toInt() and 0xFF
            if (item == 0) return@collect true
            info[item] = String(r, 5, r.len() - 2, Charsets.US_ASCII).trim()
            false
        }
        // Serial numbers (items 5 and 13) stay in logcat, off the screen,
        // so a photo of the probe is safe to post in an issue.
        info.forEach { (k, v) ->
            val name = PRODUCT_ITEMS[k] ?: "item $k"
            Log.i(TAG, "product $name = $v")
            if (k != 5 && k != 13) append("  $name: $v")
        }
        if (info.isEmpty()) append("  no product info reply")

        append("asking supported features")
        send2(conn, out, fp2Frame(FP2_SUPPORTED_FEATURES, byteArrayOf()))
        val features = sortedSetOf<Int>()
        var chunks = 0
        collect(conn, inn, 5000) { r ->
            if (r.type() != 0x11) return@collect false
            if (r.len() == 0) return@collect chunks > 0
            chunks++
            for (i in 0 until r.len() / 2) features.add(u16(r, 3 + 2 * i))
            false
        }
        Log.i(TAG, "features (${features.size}): ${features.joinToString(" ")}")
        append("  ${features.size} features advertised → logcat")

        val watch = WATCH.keys.filter { it in features }
        if (watch.isEmpty()) {
            append("none of the watched features advertised — not subscribing")
            return
        }
        val ids = ByteArray(watch.size * 2)
        watch.forEachIndexed { i, id -> ids[2 * i] = id.toByte(); ids[2 * i + 1] = (id shr 8).toByte() }
        append("subscribing to ${watch.size}: ${watch.joinToString { WATCH[it]!! }}")
        send2(conn, out, fp2Frame(FP2_SUBSCRIBE, ids))
        append("VERDICT: FitPro 2. Live values below — pull the safety key to see SYSTEM_MODE change")
    }

    private fun send2(conn: UsbDeviceConnection, out: UsbEndpoint, frame: ByteArray) {
        val rc = conn.bulkTransfer(out, frame, frame.size, 200)
        Log.i(TAG, "fp2 sent ${hex(frame)} rc=$rc")
        if (rc < 0) append("write failed (rc=$rc)")
    }

    /** Read until [done] says so or [ms] runs out, decoding events as they pass. */
    private fun collect(conn: UsbDeviceConnection, inn: UsbEndpoint, ms: Long, done: (ByteArray) -> Boolean) {
        val until = System.currentTimeMillis() + ms
        val buf = ByteArray(inn.maxPacketSize.coerceAtLeast(64))
        while (System.currentTimeMillis() < until) {
            val n = conn.bulkTransfer(inn, buf, buf.size, 500)
            if (n < 3 || buf[0].toInt() != 0x02) continue
            val r = buf.copyOf(n)
            Log.i(TAG, "fp2 got ${hex(r)}")
            if (r.type() == 0x14) append("  board ERROR: ${hex(r.copyOf(r.len() + 3))}")
            if (done(r)) return
        }
    }

    /**
     * `[0x02][APP | type][payload length][payload…][0x00]`. No checksum. The
     * trailing 0x00 is iFit's own padding; SUPPORTED_FEATURES goes without it,
     * as iFit sends it.
     */
    private fun fp2Frame(type: Int, payload: ByteArray): ByteArray {
        require(type in FP2_ALLOWED) { "FitPro 2 request type $type is not read-only" }
        if (type == FP2_EXTENDED) require(payload.size == 1 && payload[0] == FP2_INFO_PRODUCT) {
            "only EXTENDED product info is allowed"
        }
        val pad = if (type == FP2_SUPPORTED_FEATURES) 0 else 1
        val f = ByteArray(3 + payload.size + pad)
        f[0] = 0x02
        f[1] = type.toByte()
        f[2] = payload.size.toByte()
        payload.copyInto(f, 3)
        return f
    }

    private fun ByteArray.type() = this[1].toInt() and 0xFF
    private fun ByteArray.len() = (this[2].toInt() and 0xFF).coerceAtMost(size - 3)
    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** EVENT frames — `02 15 06 <id LE> <float32 LE>` — as a live readout. */
    private fun onFitPro2Event(r: ByteArray) {
        if (r.size < 9 || r.type() != 0x15 || r.len() < 6) return
        val id = u16(r, 3)
        val bits = (r[5].toInt() and 0xFF) or ((r[6].toInt() and 0xFF) shl 8) or
            ((r[7].toInt() and 0xFF) shl 16) or ((r[8].toInt() and 0xFF) shl 24)
        live[id] = java.lang.Float.intBitsToFloat(bits)
        runOnUiThread {
            maskView.text = WATCH.entries.filter { live.containsKey(it.key) }.joinToString("\n") { (k, name) ->
                val v = live[k]!!
                when (k) {
                    102 -> "$name = ${v.toInt()} ${SYSTEM_MODES.getOrElse(v.toInt()) { "?" }}"
                    602 -> "$name = ${v.toInt()} ${WORKOUT_STATES.getOrElse(v.toInt()) { "?" }}"
                    else -> "$name = ${"%.2f".format(v)}"
                }
            }
        }
    }

    /** `[device][length][cmd][payload…][checksum]`, checksum a truncated sum. */
    private fun frame(device: Int, cmd: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 4
        val f = ByteArray(length)
        f[0] = device.toByte()
        f[1] = length.toByte()
        f[2] = cmd.toByte()
        payload.copyInto(f, 3)
        f[length - 1] = checksum(f, length)
        return f
    }

    private fun checksum(f: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length - 1) sum += f[i]
        return sum.toByte()
    }

    private fun isFitPro(r: ByteArray, cmd: Int): Boolean {
        if (r.size < 5) return false
        val len = r[1].toInt() and 0xFF
        if (len < 5 || len > r.size) return false
        return (r[2].toInt() and 0xFF) == cmd && r[len - 1] == checksum(r, len)
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    /** Device + config descriptors, exactly as the device reported them. */
    private fun dumpRawDescriptors(conn: UsbDeviceConnection) {
        val raw = conn.rawDescriptors
        if (raw == null) {
            append("rawDescriptors unavailable")
            return
        }
        Log.i(TAG, "RAW DESCRIPTORS (${raw.size} bytes)")
        raw.toList().chunked(16).forEachIndexed { i, chunk ->
            Log.i(TAG, "  %04X  %s".format(i * 16, chunk.joinToString(" ") { "%02X".format(it) }))
        }
        append("raw descriptors: ${raw.size} bytes → logcat")
    }

    /**
     * GET_DESCRIPTOR(HID REPORT) — bmRequestType 0x81 (in, standard, interface),
     * bRequest 0x06, wValue 0x2200 (report descriptor, index 0), wIndex = interface.
     */
    private fun fetchHidReportDescriptor(conn: UsbDeviceConnection, ifaceId: Int) {
        val buf = ByteArray(4096)
        val n = conn.controlTransfer(0x81, 0x06, 0x2200, ifaceId, buf, buf.size, 2000)
        if (n <= 0) {
            append("HID report descriptor: request failed (rc=$n)")
            return
        }
        val desc = buf.copyOf(n)
        append("HID report descriptor: $n bytes → logcat")
        Log.i(TAG, "HID REPORT DESCRIPTOR ($n bytes)")
        desc.toList().chunked(16).forEachIndexed { i, chunk ->
            Log.i(TAG, "  %04X  %s".format(i * 16, chunk.joinToString(" ") { "%02X".format(it) }))
        }
        Log.i(TAG, "--- decoded ---")
        decodeHidReportDescriptor(desc).forEach { Log.i(TAG, "  $it") }
    }

    /**
     * Minimal HID item walker. Enough to surface Report ID / Size / Count and
     * usages, which is what tells us how the treadmill frames its data.
     */
    private fun decodeHidReportDescriptor(d: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var indent = 0
        while (i < d.size) {
            val b = d[i].toInt() and 0xFF
            val size = when (b and 0x03) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
            val type = (b shr 2) and 0x03
            val tag = (b shr 4) and 0x0F
            var value = 0L
            for (k in 0 until size) {
                if (i + 1 + k < d.size) {
                    value = value or ((d[i + 1 + k].toLong() and 0xFF) shl (8 * k))
                }
            }

            val name = when (type) {
                0 -> when (tag) { // Main
                    0x8 -> "INPUT"; 0x9 -> "OUTPUT"; 0xB -> "FEATURE"
                    0xA -> "COLLECTION"; 0xC -> "END_COLLECTION"
                    else -> "MAIN(tag=$tag)"
                }
                1 -> when (tag) { // Global
                    0x0 -> "USAGE_PAGE"; 0x1 -> "LOGICAL_MIN"; 0x2 -> "LOGICAL_MAX"
                    0x3 -> "PHYSICAL_MIN"; 0x4 -> "PHYSICAL_MAX"; 0x5 -> "UNIT_EXP"
                    0x6 -> "UNIT"; 0x7 -> "REPORT_SIZE"; 0x8 -> "REPORT_ID"
                    0x9 -> "REPORT_COUNT"
                    else -> "GLOBAL(tag=$tag)"
                }
                2 -> when (tag) { // Local
                    0x0 -> "USAGE"; 0x1 -> "USAGE_MIN"; 0x2 -> "USAGE_MAX"
                    else -> "LOCAL(tag=$tag)"
                }
                else -> "RESERVED(tag=$tag)"
            }

            if (name == "END_COLLECTION" && indent > 0) indent--
            out.add("  ".repeat(indent) + "$name 0x${value.toString(16).uppercase()} ($value)")
            if (name == "COLLECTION") indent++

            i += 1 + size
        }
        return out
    }

    private fun onReport(report: ByteArray) {
        reportCount++
        if (fitPro2) {
            if (report.size >= 3 && report[0].toInt() == 0x02) onFitPro2Event(report)
            Log.i(TAG, "report#$reportCount ${report.joinToString(" ") { "%02X".format(it) }}")
            return
        }

        if (firstReport == null) firstReport = report.copyOf()
        report.forEachIndexed { i, b ->
            seenValues.getOrPut(i) { HashSet() }.add(b.toInt() and 0xFF)
        }

        val hex = report.joinToString(" ") { "%02X".format(it) }
        // Only surface changes — a static report scrolling at 100 Hz tells us nothing.
        if (hex != lastRendered) {
            lastRendered = hex
            Log.i(TAG, "report#$reportCount $hex")
            append(hex)
            runOnUiThread { maskView.text = buildMask() }
        }
    }

    /**
     * Marks which byte offsets have taken more than one value. Those are the
     * live fields — speed, incline, distance. Static offsets are header/padding.
     */
    private fun buildMask(): String {
        val volatileOffsets = seenValues.filterValues { it.size > 1 }.keys.sorted()
        val sb = StringBuilder("reports=$reportCount  changing offsets: ")
        sb.append(if (volatileOffsets.isEmpty()) "(none yet)" else volatileOffsets.joinToString(","))
        sb.append("\n")
        volatileOffsets.take(8).forEach { off ->
            val vals = seenValues[off]!!
            sb.append("  [$off] ${vals.size} distinct, range ${vals.min()}..${vals.max()}\n")
        }
        return sb.toString().trimEnd()
    }

    private fun setHeader(s: String) = runOnUiThread { headerView.text = s }

    private fun append(s: String) {
        Log.i(TAG, s)
        runOnUiThread {
            lines.addLast(s)
            while (lines.size > MAX_LINES) lines.removeFirst()
            logView.text = lines.joinToString("\n")
        }
    }

    override fun onDestroy() {
        running = false
        claimedInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        try { unregisterReceiver(permissionReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    private fun hex4(v: Int) = "0x%04X".format(v)

    private fun epType(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "type$t"
    }
}
