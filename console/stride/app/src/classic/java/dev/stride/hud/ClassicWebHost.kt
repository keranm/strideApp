package dev.stride.hud

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView

/**
 * classic flavor: the UI rendered through the console's own Android System
 * WebView. This is the original Stride behaviour, unchanged — correct for Gen 6
 * consoles (the confirmed C 1750) whose System WebView is current enough to run
 * the UI. The page reaches Kotlin through addJavascriptInterface, which gives
 * the `Stride` bridge its synchronous call-and-return contract directly.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
fun buildWebHost(
    activity: MainActivity,
    bridge: Any,
    startUi: String,
    onReady: () -> Unit,
): WebHost = ClassicWebHost(activity, bridge, startUi, onReady)

private class ClassicWebHost(
    private val activity: MainActivity,
    bridge: Any,
    startUi: String,
    onReady: () -> Unit,
) : WebHost {

    private val web: WebView

    init {
        // Lets the HUD be inspected and driven from a laptop over adb, so layout
        // can be checked against fake state without arming the belt. Debug builds
        // only: the DevTools socket this opens will execute anything in the
        // page's context — the `Stride` bridge, which is to say the belt — for
        // whoever can reach it. A fair trade on a bench, none at all in a hallway.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            /* The launch screen's field colour, not Original's navy. The WebView
               paints this before the document does, so it is the one frame
               between the splash and the UI — matching it makes the handover
               invisible. Navy was only ever right for one of the five. */
            setBackgroundColor(0xFF100F0E.toInt())
            addJavascriptInterface(bridge, "Stride")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = onReady()
            }
            loadUrl(assetFor(startUi))
        }
    }

    private fun assetFor(name: String) = "file:///android_asset/ui/$name.html"

    override val view: View get() = web

    override fun loadUi(name: String) = activity.runOnUiThread { web.loadUrl(assetFor(name)) }

    override fun callJs(fn: String, json: String) =
        activity.runOnUiThread { web.evaluateJavascript("window.$fn($json)", null) }

    override fun destroy() {
        web.destroy()
    }
}
