package dev.stride.hud

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A tiny HTTP/1.1 server, bound to loopback only, that serves the UI out of the
 * APK's `assets/ui/`.
 *
 * GeckoView cannot load `file:///android_asset/`, so the gecko flavor serves the
 * same HTML/JS unchanged over `http://127.0.0.1:<port>/`. 127.0.0.1 is a secure
 * context in Gecko, so the page behaves as it would from a file. Assets are a
 * handful of small files, so the accept loop handles connections sequentially.
 */
class LocalAssetServer(
    private val assets: AssetManager,
    private val base: String = "ui",
) {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var running = true

    fun start() {
        Thread({
            while (running) {
                val sock = try { server.accept() } catch (e: Exception) { break }
                try { handle(sock) } catch (e: Exception) {
                    Log.w(TAG, "assets: ${e.message}")
                } finally {
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        }, "stride-assets").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
    }

    private fun handle(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())
        val request = readLine(input) ?: return
        // Drain the rest of the request headers.
        while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break }

        val parts = request.split(" ")
        val out = sock.getOutputStream()
        if (parts.size < 2 || parts[0] != "GET") { respond(out, 400, "text/plain", "bad request".toByteArray()); return }

        var path = parts[1].substringBefore('?').trimStart('/')
        if (path.isEmpty()) path = "original.html"
        // Keep it inside assets/<base>: no traversal.
        if (path.contains("..")) { respond(out, 403, "text/plain", "forbidden".toByteArray()); return }

        val bytes = try { assets.open("$base/$path").use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) { respond(out, 404, "text/plain", "not found".toByteArray()); return }
        respond(out, 200, mime(path), bytes)
    }

    /** Read one CRLF-terminated line as ASCII, without over-reading the body. */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun mime(path: String) = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    companion object { const val TAG = "Stride" }
}
