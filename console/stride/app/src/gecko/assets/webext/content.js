/* Stride bridge — content script (runs at document_start on the local UI).
 *
 * Two jobs:
 *   1. Inject `window.Stride` into the PAGE's own JS world, so the UI's calls
 *      (stride-core.js, original.html, ...) find the same object they would
 *      under addJavascriptInterface. Content scripts live in an isolated world,
 *      so the shim is injected as a <script> element and talks to this content
 *      script through window CustomEvents (string detail only — safe across the
 *      Xray boundary).
 *   2. Relay: page command events -> background port -> Kotlin, and
 *      Kotlin pushes (getter data, render/plan/coach calls) -> page events.
 *
 * The five getters return the last snapshot Kotlin pushed; everything else is a
 * fire-and-forget command. This mirrors exactly what the UI consumes (only
 * routes/availableUis/currentUi/settingsJson/wakeState are read synchronously).
 */
'use strict';

// ---- the page-world shim, as source injected into the page ----------------
var SHIM = '(' + function () {
  var data = {};
  function cmd(m, a) {
    try {
      window.dispatchEvent(new CustomEvent('stride:cmd', { detail: JSON.stringify({ m: m, a: a }) }));
    } catch (e) {}
  }
  window.addEventListener('stride:data', function (e) {
    try { var d = JSON.parse(e.detail); for (var k in d) data[k] = d[k]; } catch (_) {}
  });
  window.addEventListener('stride:call', function (e) {
    try {
      var p = JSON.parse(e.detail);
      var f = window[p.fn];
      if (typeof f === 'function') f(JSON.parse(p.json));
    } catch (_) {}
  });
  function getter(key, dflt) { return function () { return (key in data) ? data[key] : dflt; }; }
  window.Stride = {
    // getters — synchronous, return the last snapshot Kotlin pushed
    routes: getter('routes', '[]'),
    availableUis: getter('availableUis', '["original","ember","cluster","daylight","pacer"]'),
    currentUi: getter('currentUi', 'original'),
    settingsJson: getter('settingsJson', '{}'),
    wakeState: getter('wakeState', ''),
    // commands — fire and forget
    choose: function () { cmd('choose', []); },
    chooseGuided: function (t, m) { cmd('chooseGuided', [t, m]); },
    chooseRoute: function (id, loop) { cmd('chooseRoute', [id, loop]); },
    skipWarmup: function () { cmd('skipWarmup', []); },
    skipCooldown: function () { cmd('skipCooldown', []); },
    pause: function () { cmd('pause', []); },
    resume: function () { cmd('resume', []); },
    end: function () { cmd('end', []); },
    home: function () { cmd('home', []); },
    speed: function (d) { cmd('speed', [d]); },
    setSpeed: function (k) { cmd('setSpeed', [k]); },
    incline: function (d) { cmd('incline', [d]); },
    fan: function () { cmd('fan', []); },
    setFan: function (l) { cmd('setFan', [l]); },
    setWalker: function (n) { cmd('setWalker', [n]); },
    ackDmk: function () { cmd('ackDmk', []); },
    hushCoach: function () { cmd('hushCoach', []); },
    setUi: function (n) { cmd('setUi', [n]); },
    dim: function (on) { cmd('dim', [on]); },
    wakeBoard: function () { cmd('wakeBoard', []); }
  };
}.toString() + ')();';

var s = document.createElement('script');
s.textContent = SHIM;
(document.head || document.documentElement).appendChild(s);
s.remove();

// ---- fit the fixed 1280x800 design to this console's screen ----------------
// The five UIs declare `width=1280,height=800,initial-scale=1`, sized for the
// Gen 6 console. On a smaller panel (the Elite 900 is 1024x600) that overflows.
// Gecko scales vector/text crisply, so we rewrite the viewport to a pure
// initial-scale that fits — dropping the fixed width so the scale isn't floored
// to device-width/1280. Only shrinks; a >=1280x800 panel is left untouched.
function fitViewport() {
  var scale = Math.min(1, window.screen.width / 1280, window.screen.height / 800);
  if (scale >= 0.999) return;
  var content = 'initial-scale=' + scale + ',minimum-scale=' + scale +
                ',maximum-scale=' + scale + ',user-scalable=no';
  var m = document.querySelector('meta[name=viewport]');
  if (m) { m.setAttribute('content', content); }
  else {
    m = document.createElement('meta');
    m.setAttribute('name', 'viewport');
    m.setAttribute('content', content);
    (document.head || document.documentElement).appendChild(m);
  }
}
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fitViewport);
else fitViewport();

// ---- relay -----------------------------------------------------------------
var port = browser.runtime.connect({ name: 'stride-cs' });

// page -> Kotlin
window.addEventListener('stride:cmd', function (e) {
  try {
    var d = JSON.parse(e.detail);
    port.postMessage({ t: 'cmd', m: d.m, a: d.a });
  } catch (_) {}
});

// Kotlin -> page
port.onMessage.addListener(function (msg) {
  if (!msg) return;
  if (msg.t === 'data') {
    window.dispatchEvent(new CustomEvent('stride:data', { detail: JSON.stringify(msg.d) }));
  } else if (msg.t === 'call') {
    window.dispatchEvent(new CustomEvent('stride:call', { detail: JSON.stringify({ fn: msg.fn, json: msg.json }) }));
  }
});
