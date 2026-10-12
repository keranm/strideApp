package dev.stride.hud

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * The treadmill, as a standard Bluetooth fitness machine.
 *
 * Zwift, Kinomap and the rest do not know what a NordicTrack is. What they know
 * is FTMS, the Bluetooth SIG's Fitness Machine Service, and a machine speaking
 * it turns up in their device list next to the commercial ones. This publishes
 * that service so the console can be paired with directly, with no phone in the
 * middle and nothing installed anywhere else.
 *
 * **Why this can exist at all.** Being found by Zwift means being a BLE
 * *peripheral*, and a peripheral has to advertise. Plenty of cheap MediaTek
 * parts can only be a central, which is the role [HeartRate] already uses for
 * the strap, and on one of those this file would be dead code. This console
 * answers `isMultipleAdvertisementSupported = true`, on the same mt8163 Argon
 * silicon that ciarancoffey/nordictrack-ftms-bridge runs on. That project is
 * where the idea came from and is credited in the README. It is AGPL and this
 * is Apache-2.0, so nothing was taken from it: the encoding below is the
 * published FTMS spec, and the field order is the spec's order because the
 * spec says it is normative.
 *
 * **Telemetry only, on purpose.** FTMS also defines a Control Point that lets
 * the app on the other end set speed and incline. It is deliberately not here.
 * The rule the whole guided walk is built on is that nothing sets the belt
 * speed except the person standing on the belt (see MainActivity's setSpeed),
 * and handing that to software on another device, in another room, is a
 * different decision that nobody has asked for yet. Reading is safe. Driving
 * is not the same thing.
 */
class Ftms(private val context: Context) {

    companion object {
        private val TAG = FitProConnection.TAG

        /** Fitness Machine Service. */
        val SERVICE: UUID = uuid16(0x1826)
        /** Fitness Machine Feature: what this machine can report. Read. */
        val FEATURE: UUID = uuid16(0x2ACC)
        /** Treadmill Data: the telemetry itself. Notify. */
        val TREADMILL_DATA: UUID = uuid16(0x2ACD)
        /** Client Characteristic Configuration, the subscribe switch. */
        val CCCD: UUID = uuid16(0x2902)

        private fun uuid16(id: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", id))

        /**
         * Fitness Machine Feature, 4 bytes, then Target Setting Feature, 4
         * bytes. Little endian, one bit per capability.
         *
         * Claimed: average speed (bit 0), total distance (bit 2), inclination
         * (bit 3), heart rate (bit 10), elapsed time (bit 12). Target settings
         * are all zero, which is how a client is told there is no point
         * writing to a control point that isn't there.
         *
         * **This must agree with [FLAGS], field for field.** The first version
         * claimed expended energy and did not claim average speed, while the
         * frame sent average speed and no energy. Zwift read the feature, read
         * a frame that contradicted it, and hung up 96ms after subscribing.
         * A client is entitled to believe what a machine says about itself.
         */
        private const val FEATURES = (1 shl 0) or (1 shl 2) or (1 shl 3) or
                (1 shl 10) or (1 shl 12)

        /**
         * Treadmill Data flags, 16 bits, saying which fields follow.
         *
         * Bit 0 is the odd one: it means *More Data*, and instantaneous speed
         * is present when it is **clear**. So bit 0 stays 0 and the speed goes
         * in. The rest name what follows it: average speed (1), total distance
         * (2), inclination and ramp angle (3), heart rate (8), elapsed time
         * (10). Must agree with [FEATURES].
         */
        private const val FLAGS = (1 shl 1) or (1 shl 2) or (1 shl 3) or
                (1 shl 8) or (1 shl 10)

        /**
         * The advertised payload that makes this a *treadmill* rather than an
         * unspecified fitness machine: flags byte (bit 0, machine available),
         * then the machine type as uint16 (bit 0, treadmill).
         */
        private val SERVICE_DATA = byteArrayOf(0x01, 0x01, 0x00)

        /**
         * How often a subscribed client is sent a frame.
         *
         * Their bridge uses 1 Hz and it is what FTMS clients expect, so that
         * is what this was. On this console it made things worse: the longest
         * connection of the morning was three minutes at 5 Hz, and every 1 Hz
         * connection died inside ten seconds.
         *
         * The reasoning that led to 1 Hz was that five frames a second floods
         * a link with no margin. That is backwards. A BLE supervision timer is
         * fed by packets that arrive, and at around -90 dBm plenty of them do
         * not. Sending more often is more chances to keep the link up, not
         * fewer. Their radio is presumably not buried in this housing.
         */
        /**
         * How this treadmill introduces itself. Short on purpose: the name
         * rides in the 31-byte scan response alongside everything else.
         */
        const val DEVICE_NAME = "STRIDE Treadmill"

        const val NOTIFY_EVERY_MS = 200L
    }

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var data: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    /** Everyone currently subscribed. A notify to nobody is just radio. */
    private val ftmsSubs = LinkedHashSet<BluetoothDevice>()

    /**
     * Services still to be registered.
     *
     * `addService` is asynchronous and only one may be in flight: adding the
     * second before [BluetoothGattServerCallback.onServiceAdded] fires for the
     * first drops it, with no error anywhere. The queue exists so the two
     * services actually both arrive.
     */
    private val pending = ArrayDeque<BluetoothGattService>()

    /** The last frame's worth of numbers, so a new subscriber gets an answer
     *  straight away instead of waiting for the next poll. */
    @Volatile private var last: Snapshot? = null

    /**
     * Whether the first frame of this subscription has been logged.
     *
     * One line per subscriber, carrying the bytes that actually left, is
     * enough to tell a working link from a silent one. Logging every frame
     * fills a 256KB buffer that already struggles to hold two hours.
     */
    @Volatile private var loggedFirstFrame = false
    @Volatile private var lastLogAt = 0L
    @Volatile private var lastFrameKey = ""

    /**
     * The clock that actually sends frames.
     *
     * Deliberately not the board poll loop. A poll that carries a write gets a
     * reply with no telemetry in it, so the loop skips the rest of its body,
     * and a board refusing a write is retried for seconds at a time. Driving
     * notifications from there meant the treadmill went silent on Bluetooth
     * every time somebody pressed the speed button, which is the one moment a
     * client is most interested. Zwift read 0.0 and stopped the avatar.
     *
     * So this ticks on its own and sends whatever the last known numbers are.
     * A frame that repeats is fine. A gap is not.
     */
    private val tickThread = HandlerThread("ftms").apply { start() }
    private val ticker = Handler(tickThread.looper)
    private val tick = object : Runnable {
        override fun run() {
            // The reschedule is in a finally, and it is the whole point.
            //
            // It used to be the last statement in the body, so anything
            // throwing above it killed the clock permanently. Nothing else
            // notices: the GATT connection stays up, the client stays
            // subscribed, and the treadmill simply stops saying anything.
            // Zwift then decays its own speed to zero over several seconds
            // while the belt is still running, which reads as the treadmill
            // slowing to a halt and is nothing of the sort.
            //
            // Off the main thread as well. That one runs the WebView and the
            // coach's speech, and a frame that waits behind a repaint is a
            // frame that did not go out on time.
            try {
                last?.let { send(it) }
            } catch (e: Exception) {
                Log.w(TAG, "ftms: frame failed: ${e.message}")
            } finally {
                ticker.postDelayed(this, NOTIFY_EVERY_MS)
            }
        }
    }

    @Volatile var advertising = false
        private set

    /** How many clients are listening, for the settings screen to show. */
    val listeners: Int get() = synchronized(ftmsSubs) { ftmsSubs.size }

    /**
     * Never throws. This is called from the poll thread — the one driving the
     * motor — and a Bluetooth permission the console has not granted must cost
     * Zwift, not the treadmill. On a Gen 7 running Android 13 it threw, and the
     * app died, the board dropped off USB, re-attached, relaunched the app, and
     * round it went.
     */
    fun start() {
        try {
            startAdvertisingService()
        } catch (e: SecurityException) {
            Log.w(TAG, "ftms: not permitted on this console (${e.message}) — FTMS off")
            try { server?.close() } catch (_: SecurityException) {}
            server = null
        }
    }

    private fun startAdvertisingService() {
        val a = adapter ?: run { Log.i(TAG, "ftms: no bluetooth adapter"); return }
        if (!a.isEnabled) { Log.i(TAG, "ftms: bluetooth is off"); return }
        if (!a.isMultipleAdvertisementSupported) {
            // Not a failure worth shouting about. It is a property of the
            // radio, it will never change, and everything else still works.
            Log.i(TAG, "ftms: this radio cannot advertise, skipping")
            return
        }
        if (server != null) return

        val srv = manager?.openGattServer(context, callback) ?: run {
            Log.w(TAG, "ftms: could not open a GATT server"); return
        }
        server = srv

        val feature = BluetoothGattCharacteristic(
            FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply { value = featureBytes() }

        val treadmill = BluetoothGattCharacteristic(
            TREADMILL_DATA,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,   // notify carries no read permission of its own
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        data = treadmill

        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(feature)
        service.addCharacteristic(treadmill)

        pending.addLast(service)
        addNext()

        advertise(a)
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, NOTIFY_EVERY_MS)
    }

    /** Register the next service, one at a time. See [pending]. */
    private fun addNext() {
        val srv = server ?: return
        val next = pending.removeFirstOrNull() ?: return
        srv.addService(next)
    }

    private fun advertise(a: android.bluetooth.BluetoothAdapter) {
        // What the console calls itself over Bluetooth.
        //
        // The factory name is EwayMediatekArgon2, which is the tablet's model
        // and means nothing to somebody looking at a list of treadmills in
        // Zwift. This is the adapter's name, so it changes what the console is
        // called for every Bluetooth purpose, not just this one. On a machine
        // whose entire job is being this treadmill that is the right answer,
        // and it is what a shop-bought FTMS treadmill would advertise anyway.
        try {
            if (a.name != DEVICE_NAME) a.name = DEVICE_NAME
        } catch (e: Exception) {
            Log.w(TAG, "ftms: could not rename the adapter: ${e.message}")
        }

        advertiser = a.bluetoothLeAdvertiser
        val adv = advertiser ?: run { Log.w(TAG, "ftms: no advertiser"); return }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The name goes in the scan response rather than the advertisement.
        // An advertisement is 31 bytes, the service UUID and its service data
        // take 16 of them, and a console called EwayMediatekArgon2 does not
        // fit in what is left. A client that wants the name asks for it.
        val payload = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .addServiceData(ParcelUuid(SERVICE), SERVICE_DATA)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adv.startAdvertising(settings, payload, scanResponse, advertiseCallback)
    }

    /**
     * Put the advertisement back after a client has come and gone.
     *
     * Stopping first because the stack may or may not consider the old one
     * finished, and starting twice returns ALREADY_STARTED rather than
     * advertising twice. Both calls are wrapped: neither failing is worth
     * taking the console down for.
     */
    private fun restartAdvertising() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        try { advertise(a) } catch (e: Exception) {
            Log.w(TAG, "ftms: could not resume advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            Log.i(TAG, "ftms: advertising as a treadmill")
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            Log.w(TAG, "ftms: advertising refused (error $errorCode)")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val name = "fitness machine"
            if (status == BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "ftms: published $name")
            else Log.w(TAG, "ftms: $name refused (status $status)")
            addNext()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                synchronized(ftmsSubs) { ftmsSubs.remove(device) }
                // status is the whole story when a client keeps leaving: 19
                // is the far end hanging up on purpose, 8 is the link dying
                // under a weak signal, 22 is this side closing it. Guessing
                // between those three sends you looking in the wrong place.
                Log.i(TAG, "ftms: ${device.address} disconnected " +
                        "(status $status${when (status) {
                            8 -> ", link supervision timeout"
                            19 -> ", peer hung up"
                            22 -> ", local host closed it"
                            62 -> ", connection failed to establish"
                            else -> ""
                        }})")
                // Android stops a connectable advertisement the moment it is
                // accepted, and never restarts it. So one phone connecting once
                // takes the treadmill off the air for everything else until the
                // app is restarted: pair with a scanner to check it works, and
                // it is invisible to Zwift by the time you switch over. Put it
                // straight back.
                restartAdvertising()
            } else if (newState == BluetoothGatt.STATE_CONNECTED) {
                // Same reason. A second client can only find us if we are still
                // advertising while the first one is connected, and on this
                // stack we are not.
                Log.i(TAG, "ftms: ${device.address} connected (status $status)")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: ByteArray(0)
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0),
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD) {
                val on = value.isNotEmpty() && value[0].toInt() != 0
                synchronized(ftmsSubs) {
                    if (on) ftmsSubs.add(device) else ftmsSubs.remove(device)
                }
                loggedFirstFrame = false
                Log.i(TAG, "ftms: ${device.address} " +
                        "${if (on) "subscribed to" else "unsubscribed from"} treadmill data")
                // Answer immediately rather than at the next poll. A client
                // that subscribes and then sits in silence has no way to tell
                // a working treadmill from a broken one, and some give up in
                // well under a second.
                if (on) last?.let { s ->
                    val srv = server ?: return@let
                    push(srv, data, listOf(device)) { frame(s) }
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val on = synchronized(ftmsSubs) { ftmsSubs.contains(device) }
            server?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
            )
        }
    }

    /**
     * Push one frame to everyone listening.
     *
     * Called from the poll loop, so it runs at whatever rate the board is being
     * read at and costs nothing when nobody has subscribed.
     */
    /**
     * Hand over the latest numbers. Does not transmit: [tick] does that, once a
     * second, whether or not this has been called since.
     */
    fun update(s: Snapshot) { last = s }

    private fun send(s: Snapshot) {
        val srv = server ?: return
        val who = synchronized(ftmsSubs) { ftmsSubs.toList() }
        if (who.isEmpty()) return
        val bytes = frame(s)
        logFrame(s, bytes, who.size)
        push(srv, data, who) { bytes }
    }

    /**
     * What actually went out, in numbers rather than hex.
     *
     * Keyed on speed and incline only. Distance and elapsed change on nearly
     * every frame, so keying on the whole line meant a log line five times a
     * second, which fills this console's 256KB buffer in minutes and buries
     * whatever you were looking for.
     *
     * Reading this against the console's own dial is the only way to separate
     * "we sent the wrong number" from "the client did something else with the
     * right one" — which was the question for most of 9 September 2026.
     */
    private fun logFrame(s: Snapshot, b: ByteArray, listeners: Int) {
        val kph = ((b[3].toInt() and 0xFF) shl 8 or (b[2].toInt() and 0xFF)) / 100.0
        val key = "${"%.2f".format(kph)}/${"%.1f".format(s.incline)}"
        val line = "ftms >> ${"%.2f".format(kph)} km/h" +
                "  incline ${"%.1f".format(s.incline)}%" +
                "  ${s.distance.toInt()} m" +
                "  ${s.elapsed.toInt()} s" +
                "  hr ${s.pulse}" +
                "  (dial ${"%.1f".format(s.speed)}, belt ${"%.1f".format(s.beltKph)}," +
                " $listeners listening)"
        val now = SystemClock.elapsedRealtime()
        if (key != lastFrameKey || now - lastLogAt > 30_000L) {
            lastFrameKey = key
            lastLogAt = now
            Log.i(TAG, line)
        }
    }

    private inline fun push(
        srv: BluetoothGattServer,
        c: BluetoothGattCharacteristic?,
        who: List<BluetoothDevice>,
        body: () -> ByteArray,
    ) {
        if (c == null || who.isEmpty()) return
        val bytes = body()
        c.value = bytes
        for (d in who) {
            val ok = try {
                srv.notifyCharacteristicChanged(d, c, false)
            } catch (e: Exception) {
                Log.w(TAG, "ftms: notify to ${d.address} threw: ${e.message}")
                false
            }
            // Throttled, because this runs at poll rate and the point is to
            // see whether anything is leaving at all, not to fill the buffer.
            // notifyCharacteristicChanged returns false when the stack drops
            // the notification, which is silent otherwise and looks from the
            // other end exactly like a treadmill that is switched off.
            val now = SystemClock.elapsedRealtime()
            if (!ok || now - lastLogAt > 5_000L) {
                lastLogAt = now
                loggedFirstFrame = ok
                Log.i(TAG, "ftms: notify ${if (ok) "ok" else "REFUSED"} " +
                        "to ${d.address}: ${FitPro.hex(bytes)}")
            }
        }
    }

    private fun featureBytes(): ByteArray {
        val b = ByteArray(8)
        le32(b, 0, FEATURES)
        le32(b, 4, 0)          // no target settings: nothing here can be driven
        return b
    }

    /**
     * One Treadmill Data notification, in the order [FLAGS] promises.
     *
     * The speed sent is the setpoint rather than the odometer estimate, for the
     * same reason the console's own dial shows it: see Snapshot.speed. The
     * estimate wanders by a few tenths whenever the deck tilts, and a receiving
     * app has no way to know that and would draw the wander as real.
     */
    private fun frame(s: Snapshot): ByteArray {
        val b = ByteArray(16)
        var i = 0
        i = le16(b, i, FLAGS)
        // Instantaneous speed, km/h at 0.01 resolution.
        //
        // Snapshot.speed is zero unless STRIDE considers a session to be
        // running, because it was written for the console's own dial. A belt
        // turning in manual mode is still a belt turning, and a client asking
        // a treadmill how fast it is going does not have a concept of our
        // sessions. So fall back to what the odometer says the belt is doing.
        val kph = if (s.speed > 0.0) s.speed else s.beltKph
        i = le16(b, i, Math.round(kph * 100.0).toInt().coerceIn(0, 0xFFFF))
        // Average speed, same units. Already tracked for the summary screen.
        i = le16(b, i, Math.round(s.avgSpeed * 100.0).toInt().coerceIn(0, 0xFFFF))
        // Total distance, metres, three bytes.
        i = le24(b, i, s.distance.toInt().coerceIn(0, 0xFFFFFF))
        // Inclination, percent at 0.1, signed. The deck declines, so this goes
        // negative and the field is sint16 for exactly that reason.
        i = le16(b, i, Math.round(s.incline * 10.0).toInt().coerceIn(-32768, 32767))
        // Ramp angle setting. The board reports grade, not an angle, and the
        // spec pairs the two fields under one flag, so this is the "unknown"
        // value rather than a number nobody measured.
        i = le16(b, i, 0x7FFF)
        // Heart rate, bpm, whatever the strap is saying.
        b[i++] = (s.pulse.coerceIn(0, 255)).toByte()
        // Elapsed time, seconds.
        i = le16(b, i, s.elapsed.toInt().coerceIn(0, 0xFFFF))
        return b
    }

    private fun le16(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        return at + 2
    }

    private fun le24(b: ByteArray, at: Int, v: Int): Int {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        return at + 3
    }

    private fun le32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun stop() {
        ticker.removeCallbacks(tick)
        tickThread.quitSafely()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) { }
        advertising = false
        synchronized(ftmsSubs) { ftmsSubs.clear() }
        try { server?.close() } catch (_: Exception) { }
        server = null
        data = null
    }
}
