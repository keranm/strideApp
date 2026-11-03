/* ===========================================================================
   STRIDE — settings, shared by every interface.
   ---------------------------------------------------------------------------
   One document, injected into whichever UI is up.

   **Why this is not five settings screens.** The five interfaces each own their
   whole DOM and CSS and are meant to look nothing like each other, and the
   Interface picker already lived in all five — five copies of one list, which
   was survivable. Nine sections of real configuration is not: the copy nobody
   opens is the copy that silently stops matching the store.

   **Why it brings its own look rather than inheriting.** The five UIs do not
   share a palette or even variable names — Original is `--bg-0` navy, Ember is
   `--bg` near-black, Daylight is a *light* theme on `--bg:#eae6dc`. There is no
   set of tokens to inherit. So this is self-contained and every selector is
   prefixed `sx-`, which also means it cannot collide with a host document's
   styles. Settings looking the same everywhere is the same call an OS makes.

   Everything is read from and written to the Kotlin bridge immediately — there
   is no Save button and nothing is held here. A console that loses a setting
   because somebody walked away from a form is a console that lies about its own
   configuration.
   =========================================================================== */
(function (global) {
  'use strict';

  var S = null;          // last settings object from the bridge
  var root = null;
  var shadowHost = null;
  var poll = null;

  function bridge() { return global.Stride; }
  function has(fn) { try { return typeof bridge()[fn] === 'function'; } catch (e) { return false; } }

  function load() {
    try { S = JSON.parse(bridge().settingsJson()); } catch (e) { S = S || {}; }
    return S;
  }

  function save(key, value) {
    try { bridge().saveSetting(key, String(value)); } catch (e) {}
    S[key] = value;
  }

  /* ---- styles ------------------------------------------------------------ */
  var CSS = [
    ':host{all:initial}',
    '.sx-root{position:absolute;top:0;right:0;bottom:0;left:0;display:flex;',
    '  background:#050b1f;color:#eaf0ff;',
    '  font-family:"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased;',
    '  user-select:none;-webkit-tap-highlight-color:rgba(0,0,0,0)}',

    '*{margin:0;padding:0;box-sizing:border-box}',
    'button{font-family:inherit;outline:none;border:none;background:none;',
    '  -webkit-tap-highlight-color:rgba(0,0,0,0)}',
    '.sx-root::before{content:"";position:absolute;top:0;right:0;bottom:0;left:0;pointer-events:none;',
    '  background:radial-gradient(ellipse 900px 520px at 50% 44%,',
    '  rgba(28,58,140,.55) 0%,rgba(10,21,51,.25) 45%,transparent 72%)}',

    '.sx-rail{position:relative;z-index:2;flex:none;width:302px;height:100%;',
    '  border-right:1px solid #1e3068;padding:26px 0 0;display:flex;flex-direction:column}',
    '.sx-title{padding:0 30px 22px;font-size:34px;font-weight:200;letter-spacing:.02em}',
    '.sx-navs{flex:1;overflow:hidden}',
    '.sx-nav{display:flex;align-items:center;width:100%;height:66px;padding:0 30px;',
    '  background:none;border:none;color:#7f92c4;font-size:19px;font-weight:300;',
    '  text-align:left;border-left:3px solid transparent}',
    '.sx-nav .sx-ic{width:26px;height:26px;flex:none;opacity:.75;margin-right:16px}',
    '.sx-nav.sx-on{color:#eaf0ff;border-left-color:#39e0ff;background:rgba(57,224,255,.09)}',
    '.sx-nav.sx-on .sx-ic{opacity:1}',
    '.sx-nav.sx-down{background:rgba(57,224,255,.14)}',
    /* The console has a bottom bezel and a finger needs somewhere to land that
       is not the edge of the glass. */
    '.sx-foot{padding:20px 30px 34px}',
    '.sx-close{width:100%;height:78px;border-radius:20px;background:rgba(57,224,255,.14);',
    '  border:2px solid #39e0ff;color:#eaf0ff;font-size:19px;letter-spacing:.16em}',
    '.sx-close.sx-down{transform:scale(.98)}',

    '.sx-panel{position:relative;z-index:2;flex:1;height:100%;padding:34px 40px 0 44px;overflow:hidden}',
    '.sx-pane{display:none;height:100%;flex-direction:column}',
    '.sx-pane.sx-on{display:flex}',
    '.sx-h{font-size:38px;font-weight:200}',
    '.sx-sub{margin-top:9px;color:#7f92c4;font-size:17px;font-weight:300;line-height:1.45;max-width:820px}',
    /* padding-bottom, not a spacer: it scrolls with the content, so the last
       row can always be brought clear of the bezel instead of resting under it. */
    '.sx-scroll{margin-top:24px;flex:1;overflow-y:auto;padding-right:14px;padding-bottom:46px;',
    '  -webkit-overflow-scrolling:touch}',
    '.sx-scroll::-webkit-scrollbar{width:8px}',
    '.sx-scroll::-webkit-scrollbar-thumb{background:#1e3068;border-radius:4px}',

    '.sx-row{display:flex;align-items:center;min-height:84px;padding:16px 0;',
    '  border-bottom:1px solid rgba(30,48,104,.6)}',
    '.sx-row:last-child{border-bottom:none}',
    '.sx-lbl{flex:1;min-width:0;margin-right:20px}',
    '.sx-n{font-size:20px;font-weight:300}',
    '.sx-d{margin-top:5px;font-size:14px;color:#44548a;line-height:1.4}',
    '.sx-ctl{flex:none}',
    '.sx-group{margin-top:30px}',
    '.sx-gh{font-size:13px;letter-spacing:.2em;text-transform:uppercase;color:#44548a;padding-bottom:6px}',

    '.sx-sw{width:96px;height:52px;border-radius:26px;background:rgba(30,48,104,.9);',
    '  border:2px solid #1e3068;position:relative;transition:.16s}',
    '.sx-sw i{position:absolute;top:4px;left:4px;width:40px;height:40px;border-radius:50%;',
    '  background:#44548a;transition:.16s}',
    '.sx-sw.sx-on{background:rgba(57,224,255,.22);border-color:#39e0ff}',
    '.sx-sw.sx-on i{left:48px;background:#39e0ff}',

    '.sx-seg{display:flex;border:2px solid #1e3068;border-radius:16px;overflow:hidden}',
    '.sx-seg button{height:56px;padding:0 22px;background:none;border:none;color:#7f92c4;',
    '  font-size:17px;font-weight:300;border-right:1px solid #1e3068}',
    '.sx-seg button:last-child{border-right:none}',
    '.sx-seg button.sx-on{background:rgba(57,224,255,.16);color:#eaf0ff}',

    '.sx-step{display:flex;align-items:center}',
    '.sx-step button{margin:0 2px}',
    '.sx-step button{width:60px;height:56px;border-radius:14px;background:rgba(22,37,90,.5);',
    '  border:2px solid #1e3068;color:#eaf0ff;font-size:26px;font-weight:200}',
    '.sx-step button.sx-down{background:rgba(57,224,255,.2)}',
    '.sx-step .sx-v{min-width:128px;text-align:center;font-size:24px;font-weight:300}',
    '.sx-step .sx-v small{font-size:15px;color:#7f92c4;margin-left:5px}',

    '.sx-fld{height:56px;min-width:300px;padding:0 18px;border-radius:14px;',
    '  background:rgba(5,11,31,.7);border:2px solid #1e3068;color:#eaf0ff;',
    '  font-size:18px;font-weight:300;font-family:inherit;user-select:text}',
    '.sx-fld.sx-short{min-width:132px}',
    '.sx-fld:focus{border-color:#39e0ff}',

    '.sx-pill{height:56px;padding:0 26px;border-radius:16px;background:rgba(22,37,90,.5);',
    '  border:2px solid #1e3068;color:#eaf0ff;font-size:16px;letter-spacing:.1em}',
    '.sx-pill.sx-down{transform:scale(.98);background:rgba(57,224,255,.2)}',
    '.sx-pill.sx-go{border-color:#39e0ff;background:rgba(57,224,255,.16)}',
    '.sx-pill.sx-warn{border-color:#ff5c7a;color:#ffc4cf}',

    '.sx-state{display:inline-flex;align-items:center;font-size:15px;color:#7f92c4}',
    '.sx-dot{width:10px;height:10px;border-radius:50%;background:#44548a;flex:none;margin-right:9px}',
    '.sx-dot.sx-ok{background:#5dff9b;box-shadow:0 0 12px rgba(93,255,155,.7)}',
    '.sx-dot.sx-bad{background:#ff5c7a;box-shadow:0 0 12px rgba(255,92,122,.6)}',
    '.sx-dot.sx-busy{background:#ff9d3c;animation:sxp 1s infinite}',
    '@keyframes sxp{0%,100%{opacity:1}50%{opacity:.25}}',

    '.sx-people{display:flex;flex-wrap:wrap;margin-top:6px}',
    '.sx-person{width:200px;margin:0 16px 16px 0;padding:18px 14px 14px;border-radius:22px;text-align:center;',
    '  background:rgba(22,37,90,.4);border:2px solid #1e3068;color:#eaf0ff}',
    '.sx-person.sx-on{border-color:#39e0ff;background:rgba(57,224,255,.14)}',
    '.sx-av{width:64px;height:64px;border-radius:50%;margin:0 auto 12px;',
    '  background:rgba(57,224,255,.14);border:2px solid #1e3068;display:flex;',
    '  align-items:center;justify-content:center;font-size:25px;font-weight:200;color:#39e0ff}',
    '.sx-pn{font-size:20px;font-weight:300}',
    '.sx-pd{margin-top:5px;font-size:13px;color:#44548a;min-height:17px;line-height:1.35}',
    '.sx-person.sx-add{border-style:dashed;color:#7f92c4}',
    '.sx-person.sx-add .sx-av{color:#7f92c4;font-size:32px}',

    '.sx-dev{display:flex;align-items:center;padding:18px 20px;margin-top:12px;',
    '  border-radius:18px;background:rgba(22,37,90,.36);border:2px solid #1e3068}',
    '.sx-dev.sx-on{border-color:#39e0ff;background:rgba(57,224,255,.12)}',
    '.sx-dev .sx-dn{flex:1;min-width:0;margin-right:18px}',
    '.sx-dev .sx-pill{margin-left:18px}',
    '.sx-bpm{font-size:30px;font-weight:200;color:#39e0ff;min-width:104px;text-align:right}',
    '.sx-bpm small{font-size:13px;color:#7f92c4;margin-left:4px}',
    '.sx-empty{margin-top:14px;padding:26px;border-radius:18px;text-align:center;',
    '  border:2px dashed #1e3068;color:#44548a;font-size:16px;line-height:1.5}',

    '.sx-uis{display:flex;flex-wrap:wrap;margin-top:6px}',
    '.sx-uicard{width:196px;margin:0 14px 14px 0;padding:13px 13px 18px;border-radius:22px;text-align:center;',
    '  background:rgba(22,37,90,.4);border:2px solid #1e3068;color:#eaf0ff}',
    '.sx-uicard.sx-on{border-color:#39e0ff;background:rgba(57,224,255,.14)}',
    '.sx-uicard.sx-soon{opacity:.34}',
    '.sx-uicard svg{display:block;width:168px;height:104px;border-radius:10px;margin:0 auto}',
    '.sx-un{margin-top:12px;font-size:16px;letter-spacing:.16em;text-transform:uppercase}',
    '.sx-ub{margin-top:5px;font-size:12px;color:#44548a;line-height:1.35;min-height:32px}',

    '.sx-kb{position:absolute;left:0;right:0;bottom:0;z-index:20;display:none;',
    '  background:#081127;border-top:2px solid #1e3068;padding:16px 20px 20px}',
    '.sx-kb.sx-on{display:block}',
    '.sx-kb-peek{display:flex;align-items:center;margin:0 6px 14px;height:58px;',
    '  border-radius:14px;background:rgba(5,11,31,.8);border:2px solid #39e0ff;',
    '  padding:0 18px;overflow:hidden}',
    '.sx-kb-peek .sx-kb-lbl{font-size:14px;color:#44548a;flex:none;margin-right:14px;',
    '  text-transform:uppercase;letter-spacing:.14em}',
    '.sx-kb-peek .sx-kb-val{font-size:22px;font-weight:300;color:#eaf0ff;',
    '  white-space:nowrap;overflow:hidden}',
    '.sx-kb-peek .sx-kb-car{color:#39e0ff;font-weight:200}',
    '.sx-kb-row{display:flex;justify-content:center;margin-bottom:8px}',
    '.sx-kb-row button{height:62px;min-width:62px;margin:0 4px;border-radius:12px;',
    '  background:rgba(22,37,90,.75);border:1px solid #1e3068;color:#eaf0ff;',
    '  font-size:22px;font-weight:300}',
    '.sx-kb-row button.sx-down{background:rgba(57,224,255,.28)}',
    '.sx-kb-row button.sx-wide{min-width:150px;font-size:16px;letter-spacing:.1em}',
    '.sx-kb-row button.sx-space{min-width:320px}',
    '.sx-kb-row button.sx-go{border-color:#39e0ff;background:rgba(57,224,255,.18)}',
    '.sx-note{margin-top:20px;padding:18px 20px;border-radius:16px;',
    '  background:rgba(255,157,60,.09);border-left:3px solid #ff9d3c;',
    '  font-size:14px;color:#7f92c4;line-height:1.55}',
    '.sx-ro{font-size:19px;font-weight:300;color:#7f92c4}',
    '.sx-ro b{color:#eaf0ff;font-weight:300}'
  ].join('');

  /* ---- tiny DOM helpers -------------------------------------------------- */
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  /* Presses fire on touchstart for the same reason the rest of the console
     does — `click` waits for touch-end plus gesture disambiguation, which reads
     as an ignored press. Mouse events too, so this is usable in a browser. */
  function press(e, fn) {
    if (!e) return e;
    var done = false;
    e.addEventListener('touchstart', function (ev) {
      done = true; e.classList.add('sx-down'); fn(ev); ev.preventDefault();
    }, { passive: false });
    var up = function () { e.classList.remove('sx-down'); };
    e.addEventListener('touchend', up);
    e.addEventListener('touchcancel', up);
    e.addEventListener('click', function (ev) { if (!done) fn(ev); });
    return e;
  }

  function row(name, desc, ctl) {
    // The keyboard covers the row it is editing, so the control needs to carry
    // its own name into the preview.
    if (ctl && ctl.classList && ctl.classList.contains('sx-fld')) ctl.sxLabel = name;
    if (ctl && ctl.querySelector) {
      var inner = ctl.querySelector('.sx-fld');
      if (inner) inner.sxLabel = name;
    }
    var r = el('div', 'sx-row');
    var l = el('div', 'sx-lbl');
    l.appendChild(el('div', 'sx-n', name));
    if (desc) l.appendChild(el('div', 'sx-d', desc));
    r.appendChild(l);
    var c = el('div', 'sx-ctl');
    c.appendChild(ctl);
    r.appendChild(c);
    return r;
  }

  function toggle(key, initial, onChange) {
    var t = el('div', 'sx-sw' + (initial ? ' sx-on' : ''), '<i></i>');
    press(t, function () {
      var on = !t.classList.contains('sx-on');
      t.classList.toggle('sx-on', on);
      if (key) save(key, on);
      if (onChange) onChange(on);
    });
    return t;
  }

  function segment(key, options, current, onChange) {
    var s = el('div', 'sx-seg');
    options.forEach(function (o) {
      var b = el('button', o[0] === current ? 'sx-on' : '', o[1]);
      press(b, function () {
        [].forEach.call(s.children, function (c) { c.classList.remove('sx-on'); });
        b.classList.add('sx-on');
        if (key) save(key, o[0]);
        if (onChange) onChange(o[0]);
      });
      s.appendChild(b);
    });
    return s;
  }

  function stepper(key, value, min, max, inc, unit, dp) {
    var s = el('div', 'sx-step');
    var v = el('div', 'sx-v');
    var draw = function () {
      v.innerHTML = value.toFixed(dp || 0) + '<small>' + unit + '</small>';
    };
    var bump = function (d) {
      value = Math.min(max, Math.max(min, value + d * inc));
      // Floating point: 0.1 + 0.2 has no business reaching a settings store.
      value = Math.round(value * 100) / 100;
      draw();
      save(key, value);
    };
    s.appendChild(press(el('button', '', '&minus;'), function () { bump(-1); }));
    s.appendChild(v);
    s.appendChild(press(el('button', '', '+'), function () { bump(1); }));
    draw();
    return s;
  }

  /* A speed that is stored in km/h whatever the screen says. In miles the
     stepper counts in whole half-mph (1.0, 1.5, 2.0 ...) and converts on the
     way out. The range is the km/h one, narrowed to the half-mph values inside
     it, so a 1–6 km/h range is 1.0–3.5 mph. A value stored off that grid shows
     as it is until the first tap, which lands on the next step in that
     direction. */
  function speedStepper(key, kph, minKph, maxKph) {
    if (!STRIDE.mi()) return stepper(key, kph, minKph, maxKph, 0.5, 'km/h', 1);
    var K = 1.609344, STEP = 0.5, EPS = 1e-9;
    var lo = Math.ceil(minKph / K / STEP - EPS) * STEP;
    var hi = Math.floor(maxKph / K / STEP + EPS) * STEP;
    var mph = kph / K;
    var s = el('div', 'sx-step');
    var v = el('div', 'sx-v');
    var draw = function () { v.innerHTML = mph.toFixed(1) + '<small>mph</small>'; };
    var bump = function (d) {
      var next = d > 0 ? (Math.floor(mph / STEP + EPS) + 1) * STEP
                       : (Math.ceil(mph / STEP - EPS) - 1) * STEP;
      mph = Math.min(hi, Math.max(lo, next));
      draw();
      save(key, Math.round(mph * K * 100) / 100);
    };
    s.appendChild(press(el('button', '', '&minus;'), function () { bump(-1); }));
    s.appendChild(v);
    s.appendChild(press(el('button', '', '+'), function () { bump(1); }));
    draw();
    return s;
  }

  /* Somebody's age, which saves through setPersonAge rather than the global
     store the ordinary stepper writes to — it belongs to a person, not to the
     console.

     "Not set" is a real value and the one it ships in: stepping below the
     bottom clears it rather than sticking at a floor, because declining to
     give a treadmill your age has to stay a supported answer. The first tap
     up lands mid-range rather than at 13, so nobody has to press + forty
     times to reach themselves. */
  function ageStepper(p) {
    var s = el('div', 'sx-step');
    var v = el('div', 'sx-v');
    var value = p.age || 0;
    var draw = function () {
      v.innerHTML = value >= 13
        ? value + '<small>years</small>' : '<small>not set</small>';
    };
    var bump = function (d) {
      if (value < 13) value = d > 0 ? 40 : 0;
      else value = value + d;
      if (value > 100) value = 100;
      if (value < 13) value = 0;
      draw();
      try { S = JSON.parse(bridge().setPersonAge(p.name, value)); } catch (e) {}
      p.age = value;
    };
    s.appendChild(press(el('button', '', '&minus;'), function () { bump(-1); }));
    s.appendChild(v);
    s.appendChild(press(el('button', '', '+'), function () { bump(1); }));
    draw();
    return s;
  }

  /* What a stored password looks like from here. The console does not send
     the password itself — Settings.json() sends `mqtt_pass_set` instead — so
     this is a statement that one exists, never the thing itself. */
  var SECRET_MASK = '••••••••';

  function field(key, value, short, type) {
    var f = el('input', 'sx-fld' + (short ? ' sx-short' : ''));
    f.value = value == null ? '' : value;
    if (type) f.type = type;
    var secret = type === 'password';
    var hadOne = secret && f.value === SECRET_MASK;
    // readonly: the caret and the (never-appearing) system keyboard are both
    // noise here. The on-screen keyboard owns the value.
    f.setAttribute('readonly', 'readonly');
    // Saved when the keyboard closes, not per keystroke — a bridge call per
    // character would write a broker host of "1", "19", "192"… and try to
    // connect to each one.
    f.sxCommit = function () {
      var v = f.value.trim();
      // A password box that was left alone must not wipe the password. This
      // page is never given the stored one, so "empty" here means "nothing
      // was typed", not "clear it" — and putting the mask back keeps the
      // screen honest about the fact that one is still stored.
      if (secret && (v === '' || v === SECRET_MASK)) {
        if (hadOne) f.value = SECRET_MASK;
        return;
      }
      save(key, v);
    };
    press(f, function () {
      // Clear the mask before the keyboard opens, or the first thing typed
      // lands on the end of eight bullets and gets saved that way.
      if (secret && f.value === SECRET_MASK) f.value = '';
      openKeyboard(f);
    });
    return f;
  }

  function group(title) {
    var g = el('div', 'sx-group');
    if (title) g.appendChild(el('div', 'sx-gh', title));
    return g;
  }


  /* ---- on-screen keyboard ------------------------------------------------
     The console never shows the system one.

     It has an IME installed and enabled (AOSP LatinIME) but the activity runs
     under SYSTEM_UI_FLAG_FULLSCREEN | HIDE_NAVIGATION | IMMERSIVE_STICKY, and
     `dumpsys input_method` confirms `mInputShown=false` when a WebView field
     takes focus: the window never gives it room. Dropping immersive mode to
     get a keyboard would put the Android navigation bar across the bottom of a
     treadmill console for the sake of typing a name twice in its life.

     So the screen brings its own. It is also simply better here — sized for
     the panel, laid out for what actually gets typed (names, hostnames,
     passwords) and reachable at arm's length.
     -------------------------------------------------------------------- */
  var kb = null, kbTarget = null, kbShift = false;

  var KB_ROWS = [
    '1234567890'.split(''),
    'qwertyuiop'.split(''),
    'asdfghjkl'.split(''),
    'zxcvbnm'.split('')
  ];
  /* Everything a broker host, a topic prefix or a password realistically needs,
     and nothing else — a full symbol plane would be another shift layer to
     find. */
  var KB_SYMS = ['.', '-', '_', ':', '/', '@', '#', '!'];

  function buildKeyboard() {
    if (kb) return kb;
    kb = el('div', 'sx-kb');
    var peek = el('div', 'sx-kb-peek');
    kb.appendChild(peek);

    var draw = function () {
      var v = kbTarget ? kbTarget.value : '';
      var label = (kbTarget && kbTarget.sxLabel) || '';
      // Password fields are dotted here too — the keyboard is at chest height
      // on a screen in a room, not on a phone held to your face.
      if (kbTarget && kbTarget.type === 'password') v = v.replace(/./g, '\u2022');
      peek.innerHTML =
        (label ? '<span class="sx-kb-lbl">' + label + '</span>' : '') +
        '<span class="sx-kb-val">' + v.replace(/</g, '&lt;') +
        '<span class="sx-kb-car">|</span></span>';
    };
    kb.sxDraw = draw;

    var type = function (ch) {
      if (!kbTarget) return;
      var max = parseInt(kbTarget.getAttribute('maxlength') || '0', 10);
      if (max && kbTarget.value.length >= max) return;
      kbTarget.value += ch;
      // Shift is a one-shot, as on every phone keyboard. Left latched it types
      // KEZ when you wanted Kez, and nothing on screen explains why.
      if (kbShift) { kbShift = false; paintCase(); }
      draw();
    };

    /* Names want a capital; hostnames and topic prefixes do not. Auto-shift
       only where a capital is the likely next character. */
    var autoShift = function () {
      kbShift = !!(kbTarget && kbTarget.sxCapitalise && !kbTarget.value.length);
      paintCase();
    };

    KB_ROWS.forEach(function (chars, i) {
      var r = el('div', 'sx-kb-row');
      chars.forEach(function (c) {
        var b = press(el('button', '', c), function () {
          type(kbShift && i > 0 ? c.toUpperCase() : c);
        });
        b.sxChar = c;
        r.appendChild(b);
      });
      if (i === 3) {
        r.insertBefore(press(el('button', 'sx-wide', 'SHIFT'), function () {
          kbShift = !kbShift;
          paintCase();
        }), r.firstChild);
        r.appendChild(press(el('button', 'sx-wide', 'DELETE'), function () {
          if (!kbTarget) return;
          kbTarget.value = kbTarget.value.slice(0, -1);
          draw();
        }));
      }
      kb.appendChild(r);
    });

    kb.sxAutoShift = autoShift;

    var last = el('div', 'sx-kb-row');
    KB_SYMS.forEach(function (c) {
      last.appendChild(press(el('button', '', c), function () { type(c); }));
    });
    last.appendChild(press(el('button', 'sx-space', 'space'), function () { type(' '); }));
    last.appendChild(press(el('button', 'sx-wide sx-go', 'DONE'), closeKeyboard));
    kb.appendChild(last);

    root.appendChild(kb);
    return kb;
  }

  function paintCase() {
    if (!kb) return;
    [].forEach.call(kb.querySelectorAll('.sx-kb-row button'), function (b) {
      if (b.sxChar && /[a-z]/i.test(b.sxChar)) {
        b.textContent = kbShift ? b.sxChar.toUpperCase() : b.sxChar;
      }
    });
  }

  function openKeyboard(input) {
    buildKeyboard();
    kbTarget = input;
    kb.sxAutoShift();
    kb.sxDraw();
    kb.classList.add('sx-on');
  }

  function closeKeyboard() {
    if (!kb) return;
    kb.classList.remove('sx-on');
    // Commit on close, the same moment a blur would have.
    if (kbTarget && kbTarget.sxCommit) kbTarget.sxCommit();
    kbTarget = null;
  }

  /* ---- sections ---------------------------------------------------------- */
  var ICONS = {
    people: 'M12 12a5 5 0 100-10 5 5 0 000 10zm-9 9a9 9 0 0118 0',
    hr: 'M12 21s-8-5.4-8-11a4.5 4.5 0 018-2.8A4.5 4.5 0 0120 10c0 5.6-8 11-8 11z',
    ha: 'M3 11l9-8 9 8M5 10v10h14V10',
    deck: 'M3 17h18M6 17l3-9h6l3 9M9 8V5h6v3',
    coach: 'M4 5h16v11H9l-5 4V5z',
    ui: 'M3 4h18v14H3zM3 9h18M9 9v9',
    screen: 'M3 4h18v12H3zM8 20h8M12 16v4',
    routes: 'M3 18l6-3 6 3 6-3V6l-6 3-6-3-6 3zM9 15V6M15 18V9',
    about: 'M12 22a10 10 0 100-20 10 10 0 000 20zM12 10v7M12 7h.01'
  };

  var SECTIONS = [
    ['people', 'Who walks',      paneWho],
    ['hr',     'Heart rate',     paneHeart],
    ['ha',     'Home Assistant', paneHa],
    ['deck',   'Treadmill',      paneDeck],
    ['routes', 'My routes',      paneRoutes],
    ['coach',  'Coach',          paneCoach],
    ['ui',     'Interface',      paneUi],
    ['screen', 'Display',        paneScreen],
    ['about',  'About',          paneAbout]
  ];

  function head(pane, title, sub) {
    pane.appendChild(el('div', 'sx-h', title));
    pane.appendChild(el('div', 'sx-sub', sub));
    var sc = el('div', 'sx-scroll');
    pane.appendChild(sc);
    return sc;
  }

  // --- who walks ---
  function paneWho(pane) {
    var sc = head(pane, 'Who walks',
      'Everyone who uses this treadmill. Each person keeps their own distance and ' +
      'history, and chooses whether any of it leaves the console.');

    var g = group('Household');
    var list = el('div', 'sx-people');
    g.appendChild(list);
    sc.appendChild(g);

    function drawPeople() {
      list.innerHTML = '';
      (S.people || []).forEach(function (p) {
        var c = el('button', 'sx-person' + (p.name === S.default_walker ? ' sx-on' : ''));
        var bits = [];
        bits.push(p.coached ? 'Coaching' : 'No coaching');
        // Being linked is a different fact from being published, and the one
        // people actually ask about: is this the same person Home Assistant
        // knows? Somebody with no link is not broken — they simply only exist
        // here, and saying so is better than leaving it to be guessed.
        bits.push(p.ha_person ? 'linked' : 'console only');
        c.innerHTML = '<div class="sx-av">' + p.name.charAt(0).toUpperCase() + '</div>' +
                      '<div class="sx-pn">' + p.name + '</div>' +
                      '<div class="sx-pd">' + bits.join(' · ') + '</div>';
        press(c, function () { editPerson(p); });
        list.appendChild(c);
      });
      var add = el('button', 'sx-person sx-add',
        '<div class="sx-av">+</div><div class="sx-pn">Add someone</div><div class="sx-pd"></div>');
      press(add, addPerson);
      list.appendChild(add);
    }

    /**
     * An inline field, not `prompt()`.
     *
     * `window.prompt` in an Android WebView does nothing at all unless the app
     * installs a WebChromeClient to handle `onJsPrompt` — it returns null and
     * the button appears dead. This console has no WebChromeClient, and adding
     * one to get a system dialog on a treadmill is the wrong trade anyway: the
     * dialog is sized for a phone and lands wherever it likes.
     */
    function addPerson() {
      detail.innerHTML = '';
      detail.appendChild(el('div', 'sx-gh', 'Add someone'));

      var input = el('input', 'sx-fld');
      input.setAttribute('placeholder', 'Name');
      input.setAttribute('maxlength', '20');
      input.setAttribute('autocomplete', 'off');

      var commit = function () {
        var name = input.value.trim();
        if (!name) return;
        try { S = JSON.parse(bridge().addPerson(name)); } catch (e) {}
        detail.innerHTML = '';
        drawPeople(); drawDefault(); drawHa();
      };
      input.setAttribute('readonly', 'readonly');
      input.sxCapitalise = true;
      input.sxCommit = function () {};
      press(input, function () { openKeyboard(input); });

      var wrap = el('div');
      wrap.style.cssText = 'display:flex;align-items:center';
      input.style.marginRight = '14px';
      wrap.appendChild(input);
      wrap.appendChild(press(el('button', 'sx-pill sx-go', 'ADD'), commit));

      detail.appendChild(row('Their name',
        'Everyone who walks gets their own totals. Coaching and recording are ' +
        'set per person once they are here.', wrap));

      // Straight into the keyboard — the only reason to be on this screen is
      // to type a name.
      openKeyboard(input);
    }

    /* Editing is a second tap on the card rather than a nested screen: there
       are only three things to change and one of them is "remove". */
    function editPerson(p) {
      detail.innerHTML = '';
      detail.appendChild(el('div', 'sx-gh', p.name));
      detail.appendChild(row('Home Assistant',
        p.ha_person
          ? 'Linked, so walks recorded here line up with the person Home '
            + 'Assistant already knows.'
          : 'Not linked. This person exists only on the console — recording '
            + 'them still works, it just creates a device with no matching '
            + 'person in Home Assistant.',
        el('div', 'sx-ro', p.ha_person
          ? '<b>' + p.ha_person + '</b>' : 'console only')));
      detail.appendChild(row('Coaching',
        'Send this walk to Home Assistant to be turned into words. The prompt there ' +
        'carries one person\'s weight and blood pressure, which is why this is per person.',
        toggle(null, p.coached, function (on) {
          try { S = JSON.parse(bridge().setPersonFlag(p.name, 'coached', on)); } catch (e) {}
          p.coached = on; drawPeople();
        })));
      detail.appendChild(row('Age',
        'Only used for heart-rate zones. With it the coach can tell "taking it ' +
        'easy" from "working hard"; without it, it can still tell "harder than ' +
        'earlier in this walk" and says so. Leaving it unset costs the first of ' +
        'those and nothing else — the estimate behind it is rough anyway, so ' +
        'the coach talks in words rather than numbers either way.',
        ageStepper(p)));
      detail.appendChild(row('Record to Home Assistant',
        'Their distance, time and calories, published under their own name.',
        toggle(null, p.publish, function (on) {
          try { S = JSON.parse(bridge().setPersonFlag(p.name, 'publish', on)); } catch (e) {}
          p.publish = on; drawPeople();
        })));
      detail.appendChild(row('Start as ' + p.name,
        'Assume this person when the belt starts without anyone tapping a name.',
        press(el('button', 'sx-pill', 'MAKE DEFAULT'), function () {
          save('default_walker', p.name); drawPeople(); drawDefault();
        })));
      detail.appendChild(row('Remove ' + p.name,
        'Their recorded walks are not deleted.',
        press(el('button', 'sx-pill sx-warn', 'REMOVE'), function () {
          try { S = JSON.parse(bridge().removePerson(p.name)); } catch (e) {}
          detail.innerHTML = ''; drawPeople(); drawDefault(); drawHa();
        })));
    }

    var detail = group('');
    sc.appendChild(detail);

    /* Offered, never applied. A list arriving over MQTT is not permission to
       create walkers on somebody's treadmill — the person standing in front of
       it decides, one at a time. */
    var fromHa = group('From Home Assistant');
    var haBox = el('div', 'sx-people');
    fromHa.appendChild(haBox);
    sc.appendChild(fromHa);

    function drawHa() {
      var list = [];
      try { list = JSON.parse(bridge().haPeople()); } catch (e) {}
      haBox.innerHTML = '';
      fromHa.style.display = list.length ? '' : 'none';
      list.forEach(function (h) {
        var c = el('button', 'sx-person');
        c.innerHTML = '<div class="sx-av">' + h.first.charAt(0).toUpperCase() + '</div>' +
                      '<div class="sx-pn">' + h.first + '</div>' +
                      '<div class="sx-pd">' + h.name + '</div>';
        press(c, function () {
          try { S = JSON.parse(bridge().addHaPerson(h.entity_id, h.first)); } catch (e) {}
          drawPeople(); drawDefault(); drawHa();
        });
        haBox.appendChild(c);
      });
    }

    var g2 = group('When nobody chooses');
    var defRow = el('div');
    g2.appendChild(defRow);
    g2.appendChild(row('Guest walks',
      'A one-off walk counted on the console but never recorded against a person. ' +
      'Guests are never coached and never published.',
      toggle('allow_guest', S.allow_guest !== false)));
    sc.appendChild(g2);

    function drawDefault() {
      defRow.innerHTML = '';
      var opts = [['', 'Ask every time']];
      (S.people || []).forEach(function (p) { opts.push([p.name, p.name]); });
      defRow.appendChild(row('Start as', 'Who the welcome screen assumes.',
        segment('default_walker', opts, S.default_walker || '', function () { drawPeople(); })));
    }

    drawPeople();
    drawDefault();
    drawHa();
  }

  // --- heart rate ---
  function paneHeart(pane) {
    var sc = head(pane, 'Heart rate',
      'A Bluetooth chest strap is how most machines get a pulse here. Some ' +
      'treadmills also wire contact grips on the handlebar to the board, but many do not, including the one STRIDE was built on.');

    var g = group('Where the pulse comes from');
    g.appendChild(row('Source',
      'Automatic prefers a paired strap and falls back to the board. If your machine has no grips, the board reads zero and only a strap will work.',
      segment('hr_source', [['auto', 'Automatic'], ['strap', 'Strap only'], ['grips', 'Board only']],
        S.hr_source || 'auto')));
    sc.appendChild(g);

    var g2 = group('Paired strap');
    var pairedBox = el('div');
    g2.appendChild(pairedBox);
    sc.appendChild(g2);

    var g3 = group('Add a strap');
    var scanBtn = press(el('button', 'sx-pill sx-go', 'SCAN'), function () {
      scanBtn.textContent = 'SCANNING';
      foundBox.innerHTML = '<div class="sx-empty"><span class="sx-state">' +
        '<i class="sx-dot sx-busy"></i>Looking for straps…</span></div>';
      try { bridge().hrScan(); } catch (e) {}
      setTimeout(drawFound, 9000);
    });
    g3.appendChild(row('Scan for straps',
      'Any strap using the standard Bluetooth heart rate service — Polar, Garmin, ' +
      'Wahoo, Coospo and most others.', scanBtn));
    var foundBox = el('div');
    g3.appendChild(foundBox);
    sc.appendChild(g3);

    function drawFound() {
      scanBtn.textContent = 'SCAN AGAIN';
      var list = [];
      try { list = JSON.parse(bridge().hrFound()); } catch (e) {}
      foundBox.innerHTML = '';
      if (!list.length) {
        foundBox.appendChild(el('div', 'sx-empty',
          'No straps found.<br>Put yours on — most only advertise once they detect skin.'));
        return;
      }
      list.forEach(function (d) {
        var r = el('div', 'sx-dev');
        r.innerHTML = '<div class="sx-dn"><div class="sx-n">' + d.name + ' · ' +
          d.address.slice(0, 8) + '</div><div class="sx-d">Heart rate service · ' +
          d.rssi + ' dBm</div></div>';
        r.appendChild(press(el('button', 'sx-pill sx-go', 'PAIR'), function () {
          try { bridge().hrPair(d.address, d.name); } catch (e) {}
          foundBox.innerHTML = '';
          scanBtn.textContent = 'SCAN';
          load(); drawPaired();
        }));
        foundBox.appendChild(r);
      });
    }

    function drawPaired() {
      var st = {};
      try { st = JSON.parse(bridge().hrStatus()); } catch (e) {}
      pairedBox.innerHTML = '';

      if (!st.available) {
        pairedBox.appendChild(el('div', 'sx-empty',
          'Bluetooth is off, or this console has none.<br>Without a strap there is no pulse unless your machine has grips.'));
        return;
      }
      if (!st.address) {
        pairedBox.appendChild(el('div', 'sx-empty',
          'No strap paired.<br>Put yours on and scan.'));
        return;
      }
      var r = el('div', 'sx-dev' + (st.connected ? ' sx-on' : ''));
      var bits = [st.connected ? 'Connected' : 'Not in range'];
      if (st.battery >= 0) bits.push('battery ' + st.battery + '%');
      r.innerHTML = '<div class="sx-dn"><div class="sx-n">' + (st.name || 'Strap') +
        ' · ' + st.address.slice(0, 8) + '</div><div class="sx-d">' + bits.join(' · ') +
        '</div></div><div class="sx-bpm">' + (st.bpm > 0 ? st.bpm : '—') +
        ' <small>bpm</small></div>';
      r.appendChild(press(el('button', 'sx-pill sx-warn', 'FORGET'), function () {
        try { bridge().hrForget(); } catch (e) {}
        load(); drawPaired();
      }));
      pairedBox.appendChild(r);
    }

    drawPaired();
    pane.sxTick = drawPaired;   // live bpm while this section is open
  }

  // --- home assistant ---
  function paneHa(pane) {
    var sc = head(pane, 'Home Assistant',
      'Optional. The treadmill runs perfectly well with none of this set — you lose ' +
      'the history, the dashboards and the talking coach, and nothing else.');

    var g = group('Connection');
    g.appendChild(row('Publish to Home Assistant',
      'Everything below is ignored while this is off.',
      toggle('ha_enabled', !!S.ha_enabled)));
    g.appendChild(row('MQTT broker', 'Host or IP of the broker, not of Home Assistant itself.',
      field('mqtt_host', S.mqtt_host)));
    g.appendChild(row('Port', '1883 plain, 8883 with TLS.',
      field('mqtt_port', S.mqtt_port, true)));
    g.appendChild(row('Username', '', field('mqtt_user', S.mqtt_user)));
    g.appendChild(row('Password', '',
      field('mqtt_pass', S.mqtt_pass_set ? SECRET_MASK : '', false, 'password')));
    g.appendChild(row('Use TLS', 'Off is reasonable on a home network you control.',
      toggle('mqtt_tls', !!S.mqtt_tls)));
    g.appendChild(row('Topic prefix',
      'Change only if stride/ already means something else on your broker.',
      field('mqtt_prefix', S.mqtt_prefix)));

    var stateEl = el('span', 'sx-state', '<i class="sx-dot"></i>Not tested');
    var testWrap = el('div');
    testWrap.style.cssText = 'display:flex;align-items:center';
    stateEl.style.marginRight = '18px';
    testWrap.appendChild(stateEl);
    testWrap.appendChild(press(el('button', 'sx-pill', 'TEST'), function () {
      stateEl.innerHTML = '<i class="sx-dot sx-busy"></i>Connecting…';
      try { bridge().mqttTest(); } catch (e) {}
      setTimeout(drawState, 2500);
    }));
    g.appendChild(row('Test the connection',
      'Connects, publishes discovery, and reports back. Changes nothing else.', testWrap));
    sc.appendChild(g);

    function drawState() {
      var st = {};
      try { st = JSON.parse(bridge().mqttStatus()); } catch (e) {}
      if (!st.enabled) stateEl.innerHTML = '<i class="sx-dot"></i>Not publishing';
      else if (st.connected) stateEl.innerHTML = '<i class="sx-dot sx-ok"></i>Connected';
      else stateEl.innerHTML = '<i class="sx-dot sx-bad"></i>Could not connect';
    }
    drawState();
    pane.sxTick = drawState;
  }

  // --- treadmill ---
  function paneDeck(pane) {
    var sc = head(pane, 'Treadmill',
      'How the console drives the deck. The board\'s own limits always win — these ' +
      'are preferences within them, never overrides of them.');

    var g2 = group('Starting and stopping');
    g2.appendChild(row('Warm-up', 'Skippable. Zero starts every walk at your chosen pace.',
      stepper('warmup_min', S.warmup_min || 0, 0, 10, 1, 'min')));
    g2.appendChild(row('Cool-down', '',
      stepper('cooldown_min', S.cooldown_min || 0, 0, 10, 1, 'min')));
    g2.appendChild(row('Warm-up speed', 'Also the speed a cool-down eases back to.',
      speedStepper('warmup_kph', S.warmup_kph || 2, 1, 6)));
    sc.appendChild(g2);

    var g3 = group('Guided walks');
    g3.appendChild(row('Incline changes',
      'How fast the deck may move during a plan. Gentler is kinder underfoot, and no ' +
      'plan is ever allowed to move it all at once.',
      segment('incline_rate', [['gentle', 'Gentle'], ['normal', 'Normal'], ['quick', 'Quick']],
        S.incline_rate || 'normal')));
    g3.appendChild(row('Open circuit lap',
      'How long one lap is before the ground repeats. Nothing ends an open circuit but STOP.',
      stepper('open_lap_min', S.open_lap_min || 20, 5, 45, 5, 'min')));
    sc.appendChild(g3);

    var g4 = group('Reported by the board');
    var lim = { minGrade: 0, maxGrade: 0, minKph: 0, maxKph: 0 };
    try { lim = JSON.parse(bridge().boardLimits()); } catch (e) {}
    g4.appendChild(row('Incline range', 'Read from the machine. Plans are clamped to this.',
      el('div', 'sx-ro', '<b>' + lim.minGrade.toFixed(1) + '</b> to <b>+' +
        lim.maxGrade.toFixed(1) + '</b> %')));
    g4.appendChild(row('Speed range', '',
      el('div', 'sx-ro', '<b>' + STRIDE.spd(lim.minKph) + '</b> to <b>' +
        STRIDE.spd(lim.maxKph) + '</b> ' + STRIDE.spdUnit())));
    sc.appendChild(g4);
  }

  // --- coach ---
  function paneCoach(pane) {
    var sc = head(pane, 'Coach',
      'Marks your kilometres and says something occasionally. It never touches the ' +
      'belt — every pace change is yours.');

    var g = group('');
    g.appendChild(row('Coach', 'Off means a silent walk with no ribbon at all.',
      toggle('coach_on', S.coach_on !== false)));
    g.appendChild(row('How much it talks',
      'Sparing marks kilometres and little else. Chatty adds pace and rhythm observations.',
      segment('coach_talk', [['sparing', 'Sparing'], ['normal', 'Normal'], ['chatty', 'Chatty']],
        S.coach_talk || 'normal')));
    g.appendChild(row('Voice', 'Only affects wording, never what it is willing to say.',
      segment('coach_voice', [
        ['Supportive Friend', 'Supportive'], ['Drill Sergeant', 'Drill sergeant'],
        ['Data Nerd', 'Data nerd'], ['Zen Master', 'Zen']
      ], S.coach_voice || 'Supportive Friend')));
    g.appendChild(row('Written by Home Assistant',
      'Off keeps every line on the console — the built-in ones, which are shorter and never late.',
      toggle('coach_remote', S.coach_remote !== false)));
    g.appendChild(row('Announce milestones', 'First mark at 500 m, then every kilometre.',
      toggle('coach_milestones', S.coach_milestones !== false)));
    sc.appendChild(g);
  }

  // --- interface ---
  function paneUi(pane) {
    var sc = head(pane, 'Interface',
      'Five ways to walk. The treadmill behaves identically in all of them — these ' +
      'change what the screen is made of, not just its colours.');
    var wrap = el('div', 'sx-uis');
    sc.appendChild(wrap);

    var built = [], here = 'original';
    try { built = global.STRIDE.uiList(); } catch (e) {}
    try { here = global.STRIDE.currentUi(); } catch (e) {}
    var meta = (global.STRIDE && global.STRIDE.UI_META) || [];

    meta.forEach(function (u) {
      var ready = built.indexOf(u.id) >= 0;
      var c = el('button', 'sx-uicard' + (u.id === here ? ' sx-on' : '') + (ready ? '' : ' sx-soon'));
      c.innerHTML = '<svg viewBox="0 0 160 100">' + u.sketch + '</svg>' +
        '<div class="sx-un">' + u.name + '</div>' +
        '<div class="sx-ub">' + (ready ? u.blurb : 'not in this build yet') + '</div>';
      // Switching is refused mid-walk by Kotlin, and reloads the WebView — which
      // takes this whole screen with it, so there is nothing to redraw after.
      if (ready && u.id !== here) press(c, function () {
        try { global.Stride.setUi(u.id); } catch (e) {}
      });
      wrap.appendChild(c);
    });
  }

  // --- display ---
  function paneScreen(pane) {
    var sc = head(pane, 'Display',
      'The console is a screen in a room, on all day. These are about living with it ' +
      'rather than walking on it.');

    /* Units live here rather than under Treadmill, where they sat until
       somebody asked on Reddit for an MPH setting that had existed for
       months. It is a question about what the screen says, and this is the
       screen page. The board is metric underneath either way: nothing about
       this reaches the serial link. */
    var gu = group('Units');
    gu.appendChild(row('Distance and speed',
      'Everything on every screen, and everything published. The board stays ' +
      'metric underneath.',
      segment('units', [['km', 'Kilometres'], ['mi', 'Miles']], S.units || 'km')));
    sc.appendChild(gu);

    var g = group('');
    g.appendChild(row('Sleep after',
      'Shows a clock. Any tap wakes it, and the belt never sleeps mid-walk.',
      stepper('sleep_min', S.sleep_min || 5, 1, 60, 1, 'min')));
    g.appendChild(row('Clock', '',
      segment('clock_24', [['true', '24 hour'], ['false', '12 hour']],
        String(S.clock_24 !== false))));
    g.appendChild(row('Brightness',
      'Dimmer is easier to live with in a dark room and reads fine at arm\'s length.',
      segment('brightness', [['dim', 'Dim'], ['normal', 'Normal'], ['bright', 'Bright']],
        S.brightness || 'normal')));
    g.appendChild(row('Keep the screen on while walking',
      'Off lets it sleep even mid-walk, which saves the panel on long sessions.',
      toggle('keep_awake', S.keep_awake !== false)));
    sc.appendChild(g);
  }

  // --- about ---
  /* Routes converted from real outdoor walks. Read-only here on purpose: they
     are made on the phone, where the walk and its GPS track are, and a second
     place to edit a name is a second place for the two to disagree. What this
     screen is for is answering "did it arrive" without a laptop. */
  function paneRoutes(pane) {
    var sc = head(pane, 'My routes',
      'Walks you have already done outdoors, converted into ground this ' +
      'treadmill can walk. They arrive from the STRIDE Health app on your ' +
      'phone and are kept on the console, so they work with the network down.');

    /* Live, because routes arrive while you are looking at this.
       `rebuild()` runs once per `open()` and builds all nine panes together, so
       a pane built before the phone synced would say "no routes yet" for as
       long as the screen stayed open — and clicking the rail only toggles
       visibility, so it would never correct itself. That is exactly what
       happened the first time a route was synced with settings already up.
       The payload is compared as a string and only redrawn when it changes,
       so an idle pane costs one bridge call every 1.2 s and no DOM work. */
    var raw = '[]';
    try { raw = bridge().routes() || '[]'; } catch (e) {}

    /* The payload this pane was drawn from. Compared, not re-parsed: an
       unchanged pane must cost one bridge call and no DOM work, or a rebuild
       every 1.2 s would fight the scroll position under your finger. */
    var seen = raw;
    pane.sxTick = function () {
      var now = '[]';
      try { now = bridge().routes() || '[]'; } catch (e) {}
      if (now === seen) return;
      var wasOpen = pane.classList.contains('sx-on');
      pane.innerHTML = '';
      paneRoutes(pane);
      if (wasOpen) pane.classList.add('sx-on');
    };

    var routes = [];
    try { routes = JSON.parse(raw); } catch (e) { routes = []; }

    if (!routes.length) {
      var g0 = group('');
      g0.appendChild(row('No routes yet',
        'Open STRIDE Health on your phone, go to Workouts & Routes, convert an ' +
        'outdoor walk and keep it. It arrives here on the next sync.',
        el('div', 'sx-ro', '—')));
      sc.appendChild(g0);
      return;
    }

    var g = group(routes.length + (routes.length === 1 ? ' route' : ' routes'));
    routes.forEach(function (r) {
      var km = STRIDE.kmText((r.distance_m || 0) / 1000);
      var climb = Math.round(r.climb_m || 0);
      var changes = (r.segments || []).length;
      var desc = km + ' · ' + climb + ' m of climb · ' + changes +
                 ' incline changes';
      if (r.difficulty && r.difficulty !== 1) {
        desc += ' · at ' + Math.round(r.difficulty * 100) + '%';
      }
      g.appendChild(row(r.name || 'Route', desc,
        el('div', 'sx-ro', spark(r))));
    });
    sc.appendChild(g);

    var g2 = group('Where they come from');
    g2.appendChild(row('Home Assistant',
      'Routes ride the same webhook as your health data and are cached here. ' +
      'Delete or rename them on the phone; this console follows.',
      el('div', 'sx-ro', S.ha_enabled ? 'connected' : 'not configured')));
    sc.appendChild(g2);
  }

  /* The route's own profile as a small inline SVG — the ground it is, rather
     than a number saying how much of it there is. Segments are
     [startM, endM, incline] triples; the deck runs -3..+12. */
  function spark(r) {
    var segs = r.segments || [];
    if (!segs.length) return '';
    var total = segs[segs.length - 1][1] || 1;
    var pts = [];
    for (var i = 0; i <= 40; i++) {
      var d = i / 40 * total, v = segs[segs.length - 1][2];
      for (var j = 0; j < segs.length; j++) {
        if (d < segs[j][1]) { v = segs[j][2]; break; }
      }
      pts.push((i / 40 * 120).toFixed(1) + ',' +
               (34 - (v + 3) / 15 * 30).toFixed(1));
    }
    return '<svg width="120" height="36" viewBox="0 0 120 36" ' +
           'preserveAspectRatio="none"><polyline points="' + pts.join(' ') +
           '" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>';
  }

  function paneAbout(pane) {
    var sc = head(pane, 'About', 'What this is talking to, and what it is.');
    var a = {};
    try { a = JSON.parse(bridge().about()); } catch (e) {}
    var g = group('');
    g.appendChild(row('STRIDE', '', el('div', 'sx-ro', a.version + ' · build ' + a.build)));
    g.appendChild(row('Treadmill board', 'Detected over USB serial at start-up.',
      el('div', 'sx-ro', a.board || '—')));
    g.appendChild(row('Console', '', el('div', 'sx-ro', 'Android ' + a.android)));
    sc.appendChild(g);

    var g2 = group('Starting over');
    g2.appendChild(row('Reset every setting',
      'People, totals and history are kept. Only settings go back to defaults.',
      press(el('button', 'sx-pill sx-warn', 'RESET'), function () {
        try { S = JSON.parse(bridge().resetSettings()); } catch (e) {}
        rebuild();
      })));
    sc.appendChild(g2);
  }

  /* ---- build ------------------------------------------------------------- */
  var currentSection = 'people';

  function rebuild() {
    load();
    var panel = root.querySelector('.sx-panel');
    panel.innerHTML = '';
    SECTIONS.forEach(function (s) {
      var pane = el('div', 'sx-pane' + (s[0] === currentSection ? ' sx-on' : ''));
      pane.setAttribute('data-p', s[0]);
      s[2](pane);
      panel.appendChild(pane);
    });
    [].forEach.call(root.querySelectorAll('.sx-nav'), function (n) {
      n.classList.toggle('sx-on', n.getAttribute('data-go') === currentSection);
    });
  }

  /**
   * Built inside a shadow root, which is the only thing that actually works.
   *
   * The first device build rendered as a 160px column of shrink-wrapped pills.
   * `original.html` carries a bare `button { flex:1; max-width:160px;
   * height:76px; border-radius:38px }` for its own big touch targets, and a
   * bare element selector reaches anything in the document — including a
   * settings screen injected into it. Prefixing every class `sx-` prevented
   * *collisions*; it does nothing about inheritance from element selectors.
   *
   * And it would have been a different wrong shape in each of the five UIs,
   * one of which is a light theme, so there was no set of overrides to write.
   *
   * A shadow root is a real boundary: page rules do not cross it. Only
   * inherited properties do, which `:host{all:initial}` stops. Chromium 83 has
   * had `attachShadow` since long before it — but if it is ever missing, this
   * falls back to appending in the light DOM, which is the old broken-looking
   * behaviour rather than no settings screen at all.
   */
  function build() {
    if (root) return;

    var host = el('div');
    host.id = 'stride-settings';
    // Positioning lives on the host, inline, where no page rule can reach it.
    host.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;' +
                         'z-index:9000;display:none';
    document.body.appendChild(host);

    var mount = host;
    if (host.attachShadow) mount = host.attachShadow({ mode: 'open' });

    var style = document.createElement('style');
    style.textContent = CSS;
    mount.appendChild(style);

    shadowHost = host;
    root = el('div', 'sx-root');

    var rail = el('div', 'sx-rail');
    rail.appendChild(el('div', 'sx-title', 'Settings'));
    var navs = el('div', 'sx-navs');
    SECTIONS.forEach(function (s) {
      var b = el('button', 'sx-nav' + (s[0] === currentSection ? ' sx-on' : ''));
      b.setAttribute('data-go', s[0]);
      b.innerHTML = '<svg class="sx-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
        'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="' +
        ICONS[s[0]] + '"/></svg><span>' + s[1] + '</span>';
      press(b, function () {
        currentSection = s[0];
        [].forEach.call(navs.children, function (c) { c.classList.remove('sx-on'); });
        b.classList.add('sx-on');
        [].forEach.call(root.querySelectorAll('.sx-pane'), function (p) {
          p.classList.toggle('sx-on', p.getAttribute('data-p') === s[0]);
        });
      });
      navs.appendChild(b);
    });
    rail.appendChild(navs);

    var foot = el('div', 'sx-foot');
    foot.appendChild(press(el('button', 'sx-close', 'CLOSE'), close));
    rail.appendChild(foot);

    root.appendChild(rail);
    root.appendChild(el('div', 'sx-panel'));
    mount.appendChild(root);
  }

  function open() {
    build();
    rebuild();
    shadowHost.style.display = 'block';
    // Only the visible section ticks — polling a bridge for a heart rate while
    // looking at the broker settings is work nobody asked for.
    poll = setInterval(function () {
      var pane = root.querySelector('.sx-pane.sx-on');
      if (pane && pane.sxTick) pane.sxTick();
    }, 1200);
  }

  function close() {
    if (!shadowHost) return;
    closeKeyboard();
    shadowHost.style.display = 'none';
    if (poll) { clearInterval(poll); poll = null; }
    // The host document may be showing something this screen just changed —
    // the welcome screen's list of people, most obviously. It cannot know that
    // without being told, and polling for it would be worse.
    try {
      document.dispatchEvent(new CustomEvent('stride-settings-closed'));
    } catch (e) {
      // Chromium 83 has CustomEvent; a host without it simply misses the hint.
    }
  }

  function isOpen() { return !!shadowHost && shadowHost.style.display !== 'none'; }

  global.STRIDE_SETTINGS = { open: open, close: close, isOpen: isOpen };
})(this);
