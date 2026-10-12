package dev.stride.hud

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * USB transport to the brainboard.
 *
 * A Gen 6 board (FitPro 1, PID 2) is request/response: write a frame to the OUT
 * endpoint, then read the reply from IN, and it never speaks unprompted — that
 * is [exchange]. A Gen 7 board (FitPro 2, PID 3) pushes values once subscribed,
 * so it gets the separate [send] and [receive]. Which protocol a board speaks
 * is [productId], and nothing else decides it.
 */
class FitProConnection(private val context: Context) {

    companion object {
        const val TAG = "Stride"
        const val VID = 0x213c
        const val PID = 0x0002
        /** Both boards STRIDE knows. The interface is found the same way on each. */
        val PIDS = listOf(PID, FitPro2.PID)
        private const val READ_TIMEOUT_MS = 1000   // ResponseTimeoutMs in iFit
        private const val WRITE_TIMEOUT_MS = 200
    }

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    val isOpen: Boolean get() = connection != null

    /** The open board's USB product id: 2 is FitPro 1, 3 is FitPro 2. 0 when closed. */
    @Volatile var productId = 0
        private set

    val isFitPro2: Boolean get() = productId == FitPro2.PID

    fun findDevice(): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId in PIDS }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }

    /** Claim interface 0 and locate the interrupt endpoints. */
    fun open(device: UsbDevice): String? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = manager.openDevice(device) ?: return "openDevice() null — another app holds it"

        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until candidate.endpointCount) {
                val ep = candidate.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
            if (inEp != null && outEp != null) {
                if (!conn.claimInterface(candidate, true)) {
                    conn.close()
                    return "claimInterface() failed"
                }
                connection = conn; iface = candidate; epIn = inEp; epOut = outEp
                productId = device.productId
                return null
            }
        }
        conn.close()
        return "no interrupt IN+OUT pair found"
    }

    /** Safe from any thread — the watchdog calls this from its own. */
    @Synchronized
    fun close() {
        try { iface?.let { connection?.releaseInterface(it) } } catch (e: Exception) {}
        connection?.close()
        connection = null; iface = null; epIn = null; epOut = null
        productId = 0
    }

    /**
     * Send a frame and read the reply. Returns null on timeout or if not open.
     * Not synchronised — call from a single worker thread.
     *
     * FitPro 1 only. Refuses outright on a FitPro 2 board, where a FitPro 1
     * frame's length byte would be read as a message type.
     */
    fun exchange(frame: ByteArray): ByteArray? {
        if (isFitPro2) {
            Log.e(TAG, "refusing a FitPro 1 frame to a FitPro 2 board: ${FitPro.hex(frame)}")
            return null
        }
        val conn = connection ?: return null
        val out = epOut ?: return null
        val inn = epIn ?: return null

        val sent = conn.bulkTransfer(out, frame, frame.size, WRITE_TIMEOUT_MS)
        if (sent < 0) {
            Log.w(TAG, "write failed: ${FitPro.hex(frame)}")
            return null
        }

        val buf = ByteArray(FitPro.MAX_MSG)
        val n = conn.bulkTransfer(inn, buf, buf.size, READ_TIMEOUT_MS)
        if (n <= 0) return null
        return buf.copyOf(n)
    }

    /** FitPro 2: write one frame. False if it did not go. Single worker thread only. */
    fun send(frame: ByteArray): Boolean {
        if (!isFitPro2) return false
        val conn = connection ?: return false
        val out = epOut ?: return false
        // The watchdog may close the connection from its own thread while
        // this one is mid-transfer; a closed pipe is a failed write, not a crash.
        val sent = try { conn.bulkTransfer(out, frame, frame.size, WRITE_TIMEOUT_MS) } catch (e: Exception) { -1 }
        if (sent < 0) Log.w(TAG, "fp2 write failed: ${FitPro.hex(frame)}")
        return sent >= 0
    }

    /** FitPro 2: one packet from the board, or null if nothing came in [timeoutMs]. */
    fun receive(timeoutMs: Int): ByteArray? {
        if (!isFitPro2) return null
        val conn = connection ?: return null
        val inn = epIn ?: return null
        val buf = ByteArray(FitPro.MAX_MSG)
        val n = try { conn.bulkTransfer(inn, buf, buf.size, timeoutMs) } catch (e: Exception) { -1 }
        return if (n > 0) buf.copyOf(n) else null
    }

    fun describe(device: UsbDevice): String = buildString {
        append("${device.manufacturerName} / ${device.productName}\n")
        append("vid=0x%04X pid=0x%04X".format(device.vendorId, device.productId))
    }
}
