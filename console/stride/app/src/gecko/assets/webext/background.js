/* Stride bridge — background script.
 *
 * The only part of the extension allowed to use native messaging, so it is the
 * hub: one port to Kotlin, N ports to content scripts (one per page). It relays
 * between them and caches the last getter snapshot so a content script that
 * connects after Kotlin sent it still gets it.
 *
 * Why the retry: the extension's background starts the instant it installs,
 * which can be before the app has called setMessageDelegate. A connectNative
 * with no delegate yet falls back to a (non-existent, on Android) native
 * manifest and the port disconnects. So we simply reconnect until the delegate
 * is in place — a second at most — and the app pushes the first snapshot from
 * its own onConnect.
 *
 * Message shapes (JSON):
 *   Kotlin -> here:  {t:"data", d:{...getter strings...}}  getter snapshot
 *                    {t:"call", fn:"render|plan|coach", json:"..."}  push to page
 *   here  -> Kotlin: {t:"cmd", m:"<method>", a:[...]}      command from page
 *                    {t:"hello"}                            a page is ready
 */
'use strict';

var nativePort = null;
var csPorts = [];
var lastData = null;

function onNativeMessage(msg) {
  if (msg && msg.t === 'data') lastData = msg;
  csPorts.forEach(function (p) {
    try { p.postMessage(msg); } catch (e) {}
  });
}

function connectNative() {
  var p;
  try {
    p = browser.runtime.connectNative('stride');
  } catch (e) {
    setTimeout(connectNative, 500);
    return;
  }
  p.onMessage.addListener(onNativeMessage);
  p.onDisconnect.addListener(function () {
    nativePort = null;
    setTimeout(connectNative, 500);  // delegate not ready yet, or the app went away
  });
  nativePort = p;
  // Nudge the app to send the first snapshot (it also does so from onConnect).
  try { p.postMessage({ t: 'hello' }); } catch (e) {}
}

connectNative();

browser.runtime.onConnect.addListener(function (port) {
  if (port.name !== 'stride-cs') return;
  csPorts.push(port);

  if (lastData) { try { port.postMessage(lastData); } catch (e) {} }
  if (nativePort) { try { nativePort.postMessage({ t: 'hello' }); } catch (e) {} }

  port.onMessage.addListener(function (m) {
    if (nativePort) { try { nativePort.postMessage(m); } catch (e) {} }
  });
  port.onDisconnect.addListener(function () {
    csPorts = csPorts.filter(function (x) { return x !== port; });
  });
});
