package dev.stride.hud

import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * gecko flavor: the UI rendered through a bundled Mozilla GeckoView, for
 * consoles whose System WebView is too old to run it (the Elite 900, Android 7,
 * WebView 51).
 *
 * GeckoView gives no synchronous JS<->native bridge and no evaluateJavascript,
 * so the `Stride` contract is rebuilt over a bundled WebExtension (assets/webext):
 *
 *   - page  -> Kotlin: commands arrive as {t:"cmd",m,a} and are dispatched by
 *     reflection into the same `Stride` [bridge] the classic flavor hands to
 *     addJavascriptInterface — no logic is duplicated.
 *   - Kotlin -> page: window.render/plan/coach go out as {t:"call",fn,json}.
 *   - the five synchronous getters the UI reads (routes, availableUis,
 *     currentUi, settingsJson, wakeState) are satisfied by pushing their values
 *     as {t:"data",d} on connect and whenever they change, so the page's shim
 *     returns them without a round trip.
 *
 * The UI itself is served unchanged from the APK over loopback; see
 * [LocalAssetServer].
 */
fun buildWebHost(
    activity: MainActivity,
    bridge: Any,
    startUi: String,
    onReady: () -> Unit,
): WebHost = GeckoWebHost(activity, bridge, startUi, onReady)

private class GeckoWebHost(
    private val activity: MainActivity,
    private val bridge: Any,
    startUi: String,
    private val onReady: () -> Unit,
) : WebHost {

    private val runtime = GeckoRuntime.getDefault(activity)
    private val session = GeckoSession()
    private val geckoView = GeckoView(activity)
    private val server = LocalAssetServer(activity.assets).also { it.start() }
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "stride-gecko-bridge").apply { isDaemon = true }
    }

    @Volatile private var port: WebExtension.Port? = null
    @Volatile private var lastData: String = ""

    private val getterNames = listOf("routes", "availableUis", "currentUi", "settingsJson", "wakeState")

    init {
        // The one frame between splash and UI — matched to the launch colour so
        // the handover is invisible, as the classic WebView background is.
        geckoView.coverUntilFirstPaint(0xFF100F0E.toInt())

        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStop(s: GeckoSession, success: Boolean) { if (success) onReady() }
        })
        session.open(runtime)
        geckoView.setSession(session)

        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/webext/", "stride@stride.dev")
            .accept({ ext ->
                ext?.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(p: WebExtension.Port) {
                        port = p
                        p.setDelegate(portDelegate)
                        pushGetters(force = true)
                    }
                }, "stride")
                session.loadUri(url(startUi))
            }, { e ->
                Log.e(TAG, "gecko: web extension failed to load; UI will have no bridge", e)
                session.loadUri(url(startUi))
            })

        // wakeState (and, in principle, settings) drift over time; re-push the
        // getter snapshot each second, but only when something actually changed.
        worker.scheduleWithFixedDelay({ pushGetters(force = false) }, 1, 1, TimeUnit.SECONDS)
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, p: WebExtension.Port) {
            val obj = message as? JSONObject ?: return
            when (obj.optString("t")) {
                "cmd" -> dispatch(obj.optString("m"), obj.optJSONArray("a"))
                "hello" -> pushGetters(force = true)
            }
        }
        override fun onDisconnect(p: WebExtension.Port) { if (port === p) port = null }
    }

    override val view: View get() = geckoView

    override fun loadUi(name: String) = activity.runOnUiThread { session.loadUri(url(name)) }

    override fun callJs(fn: String, json: String) {
        val p = port ?: return  // page not up yet; render is 5 Hz, so dropping a frame is fine
        val msg = JSONObject().put("t", "call").put("fn", fn).put("json", json)
        activity.runOnUiThread {
            try { p.postMessage(msg) } catch (e: Exception) { Log.w(TAG, "callJs $fn: ${e.message}") }
        }
    }

    override fun destroy() {
        worker.shutdownNow()
        server.stop()
        activity.runOnUiThread { try { session.close() } catch (_: Exception) {} }
    }

    private fun url(name: String) = "http://127.0.0.1:${server.port}/$name.html"

    // ---- page -> Kotlin: reflect a command into the existing bridge ----------
    private fun dispatch(method: String, args: JSONArray?) {
        if (method.isEmpty()) return
        worker.execute {
            try {
                val n = args?.length() ?: 0
                val m = bridge.javaClass.methods.firstOrNull {
                    it.name == method && it.parameterTypes.size == n
                } ?: run { Log.w(TAG, "bridge: no $method/$n"); return@execute }
                val types = m.parameterTypes
                val call = arrayOfNulls<Any?>(n)
                for (i in 0 until n) call[i] = coerce(args?.opt(i), types[i])
                m.invoke(bridge, *call)
            } catch (e: Exception) {
                Log.w(TAG, "bridge: $method failed: ${e.message}")
            }
        }
    }

    private fun coerce(v: Any?, t: Class<*>): Any? = when (t) {
        java.lang.String::class.java -> v?.toString()
        java.lang.Double::class.java, java.lang.Double.TYPE ->
            (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: 0.0
        java.lang.Integer::class.java, java.lang.Integer.TYPE ->
            (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull() ?: 0
        java.lang.Boolean::class.java, java.lang.Boolean.TYPE ->
            (v as? Boolean) ?: (v?.toString()?.toBoolean() ?: false)
        java.lang.Long::class.java, java.lang.Long.TYPE ->
            (v as? Number)?.toLong() ?: v?.toString()?.toLongOrNull() ?: 0L
        else -> v
    }

    // ---- Kotlin -> page: push the synchronous getters' current values --------
    private fun pushGetters(force: Boolean) {
        val p = port ?: return
        worker.execute {
            val d = JSONObject()
            for (name in getterNames) d.put(name, invokeGetter(name))
            val serialized = d.toString()
            if (!force && serialized == lastData) return@execute
            lastData = serialized
            val msg = JSONObject().put("t", "data").put("d", d)
            activity.runOnUiThread {
                try { p.postMessage(msg) } catch (e: Exception) { Log.w(TAG, "push data: ${e.message}") }
            }
        }
    }

    private fun invokeGetter(name: String): String = try {
        val m = bridge.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
        (m?.invoke(bridge) as? String) ?: ""
    } catch (e: Exception) {
        Log.w(TAG, "getter $name: ${e.message}"); ""
    }

    companion object { const val TAG = "Stride" }
}
