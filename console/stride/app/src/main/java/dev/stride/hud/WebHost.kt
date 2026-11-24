package dev.stride.hud

import android.view.View

/**
 * The web engine behind the UI, abstracted so the same app can render through
 * either the console's own System WebView or a bundled GeckoView.
 *
 * The product flavor decides which: `classic` provides a [WebHost] over
 * android.webkit.WebView (correct for Gen 6 consoles with a current WebView),
 * and `gecko` provides one over GeckoView (for consoles whose System WebView is
 * too old to parse the UI — the confirmed case is the NordicTrack Elite 900,
 * Android 7.0 with WebView 51). Each flavor's source set defines exactly one
 * top-level [buildWebHost]; MainActivity calls it without knowing which.
 *
 * MainActivity touches the engine in only a handful of places, all here:
 *   - [view]      the View to put on screen,
 *   - [loadUi]    load (or reload, on a UI switch) one of the HTML interfaces,
 *   - [callJs]    push a one-shot call into the page — window.render / plan / coach,
 * and the page calls back into Kotlin through the [bridge] object (its
 * @JavascriptInterface methods). classic wires that with addJavascriptInterface;
 * gecko relays it over a bundled WebExtension.
 */
interface WebHost {
    /** The view to hand to setContentView. */
    val view: View

    /** Load interface `name` (e.g. "original"). Safe to call from any thread. */
    fun loadUi(name: String)

    /**
     * Invoke `window.<fn>(<json>)` in the page. `fn` is one of render, plan,
     * coach; `json` is already-serialised JSON. Safe to call from any thread.
     */
    fun callJs(fn: String, json: String)

    /** Release the engine. */
    fun destroy() {}
}
