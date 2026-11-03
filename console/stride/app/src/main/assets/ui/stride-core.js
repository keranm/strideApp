/* ===========================================================================
   STRIDE core — the part every UI shares.

   The console runs one HTML document at a time (see MainActivity: the chosen
   UI is loaded into the WebView by asset name). Each document owns its whole
   look — its own DOM, its own CSS, no shared stylesheet, because a shared
   stylesheet is how five deliberately different interfaces start leaking into
   each other. What they *do* share is everything that is not a look:

     * the 400 m track maths, which is fiddly and easy to get subtly wrong;
     * the state contract, so one Kotlin snapshot reads the same everywhere;
     * the setup flow (who / what / how / which), which is behaviour, not style;
     * touch handling, which took real tuning to feel right mid-stride.

   Loaded with a plain <script src="stride-core.js"> from a sibling file in
   assets/. That is local, not remote — the "no external resources" rule is
   about the network, and this ships inside the APK.

   Chromium 83 (2020). No optional catch binding beyond what shipped then, no
   `String.replaceAll`, no `Array.prototype.at`, no `:has()`, no `inset`.
   =========================================================================== */
(function (global) {
'use strict';

/* ===========================================================================
   1. THE 400 m TRACK
   ---------------------------------------------------------------------------
   A running track is a *stadium*, not an ellipse: two parallel straights
   closed by two exact semicircles. Position along it is closed form, so there
   is no reason to ask the browser — and two reasons not to. `getPointAtLength`
   is unreliable on this WebView and is outright broken on <rect>, and an
   ellipse approximation puts the head dot off the rail, visibly, on the bends.

   THE OBLIQUE TRAP, which is the whole reason this is one function and not
   four copies:

       Arc-length is not preserved under a non-uniform scale.

   Squash the oval first and then measure along it and the dot drifts off the
   rail — worst at the ends, where the squash bites hardest. So the ordering is
   fixed and not negotiable:

       1. walk the arc-length in TRUE track space, where the maths is clean;
       2. THEN apply the squash to the resulting point.

   The dot then stays welded to the rail, and its screen speed varies round the
   loop — quicker along the straights, slower round the ends. That is not a
   bug; that is what a foreshortened track looks like.

   The same trap catches the progress arc. `stroke-dasharray` measures the
   *drawn* path, which is the squashed one, so a dash fraction taken from true
   arc-length would not end underneath the dot. `dashFraction()` maps between
   the two through a table built once at construction.

   Geometry is a parameter, not a constant. The handoff's canonical track is
   two 248 px straights and r = 158 semicircles and that is the default, but a
   UI that draws the oval smaller passes its own numbers and gets arc-length
   along the stadium it actually drew.
   =========================================================================== */

/**
 * @param opts.straight  length of ONE straight, px (default 248)
 * @param opts.radius    semicircle radius, px (default 158)
 * @param opts.cx,cy     centre of the oval in screen coords
 * @param opts.squash    vertical scale; 1 is face-on, ~0.5 is a shallow oblique
 * @param opts.lapMetres real-world lap, m (default 400)
 * @param opts.samples   resolution of the true→screen length table
 */
function track(opts) {
  opts = opts || {};
  var straight  = opts.straight  == null ? 248 : opts.straight;
  var radius    = opts.radius    == null ? 158 : opts.radius;
  var cx        = opts.cx        == null ? 0   : opts.cx;
  var cy        = opts.cy        == null ? 0   : opts.cy;
  var squash    = opts.squash    == null ? 1   : opts.squash;
  var lapMetres = opts.lapMetres || 400;
  var samples   = opts.samples   || 720;

  var half      = straight / 2;
  var bend      = Math.PI * radius;          // one semicircle
  var perimeter = 2 * straight + 2 * bend;

  /* Distance zero is the left end of the top straight, running clockwise, so
     it lines up with where an SVG path written `M left,top H right A…` starts
     drawing. Keeping those two in step is what lets a dash and a dot describe
     the same position. */
  function trueAt(d) {
    d = d % perimeter;
    if (d < 0) d += perimeter;
    var th;
    if (d <= straight) {                                    // top straight, →
      return { x: -half + d, y: -radius, tx: 1, ty: 0 };
    }
    if (d <= straight + bend) {                             // right bend, ↓
      th = (d - straight) / radius;
      return { x: half + radius * Math.sin(th), y: -radius * Math.cos(th),
               tx: Math.cos(th), ty: Math.sin(th) };
    }
    if (d <= 2 * straight + bend) {                         // bottom straight, ←
      return { x: half - (d - straight - bend), y: radius, tx: -1, ty: 0 };
    }
    th = (d - 2 * straight - bend) / radius;                // left bend, ↑
    return { x: -half - radius * Math.sin(th), y: radius * Math.cos(th),
             tx: -Math.cos(th), ty: -Math.sin(th) };
  }

  /* True space → screen. The squash lands here and nowhere else. */
  function toScreen(p) {
    var tx = p.tx, ty = p.ty * squash;
    var tl = Math.sqrt(tx * tx + ty * ty) || 1;
    /* Outward normal is the tangent turned a quarter clockwise: on the top
       straight that is (0,-1), which points off the track, as it should. */
    var nx = ty / tl, ny = -tx / tl;
    return {
      x: cx + p.x,
      y: cy + p.y * squash,
      tx: tx / tl, ty: ty / tl,
      nx: nx, ny: ny,
      angle: Math.atan2(ty, tx) * 180 / Math.PI
    };
  }

  /* Cumulative *screen* length at each sample of true arc-length. Built once;
     720 samples of hypot is nothing, and it is the only honest way to convert
     a true-space distance into a dash offset on a squashed path. */
  var cum = new Array(samples + 1);
  cum[0] = 0;
  var prev = toScreen(trueAt(0));
  for (var i = 1; i <= samples; i++) {
    var pt = toScreen(trueAt(i / samples * perimeter));
    var dx = pt.x - prev.x, dy = pt.y - prev.y;
    cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dy * dy);
    prev = pt;
  }
  var screenLength = cum[samples];

  function wrap(f) {
    f = f % 1;
    if (f < 0) f += 1;
    return f;
  }

  function r2(n) { return Math.round(n * 100) / 100; }

  return {
    straight: straight, radius: radius, squash: squash,
    cx: cx, cy: cy,
    perimeter: perimeter,
    screenLength: screenLength,
    lapMetres: lapMetres,

    /**
     * SVG `d` for the rail. `inset` pulls it inwards, which is how concentric
     * lanes are drawn: a stadium offset inwards keeps its straights and loses
     * radius, so the lanes stay parallel the whole way round.
     *
     * Under squash the semicircles become half-ellipses — still one path, still
     * a constant stroke width. True perspective (near straight wider than far)
     * would need a tapering stroke, which means filling a polygon instead of
     * stroking a path: a lot of work for a subtle gain. Not done.
     */
    pathD: function (inset) {
      var r = radius - (inset || 0), ry = r * squash;
      var l = r2(cx - half), rt = r2(cx + half);
      var t = r2(cy - ry), b = r2(cy + ry);
      r = r2(r); ry = r2(ry);
      return 'M ' + l + ' ' + t + ' H ' + rt +
             ' A ' + r + ' ' + ry + ' 0 0 1 ' + rt + ' ' + b +
             ' H ' + l +
             ' A ' + r + ' ' + ry + ' 0 0 1 ' + l + ' ' + t + ' Z';
    },

    /** Screen point + tangent + outward normal at a fraction of one lap. */
    atFraction: function (f) { return toScreen(trueAt(wrap(f) * perimeter)); },

    /** Same, from metres run. Laps beyond the first simply wrap. */
    at: function (metres) { return this.atFraction((metres || 0) / lapMetres); },

    /**
     * Dash length for a path carrying `pathLength="1000"`, such that the arc
     * ends exactly under `atFraction(f)`. See the oblique trap above.
     */
    dashFraction: function (f) {
      var x = wrap(f) * samples;
      var i = Math.floor(x);
      var s = i >= samples ? cum[samples]
                           : cum[i] + (cum[i + 1] - cum[i]) * (x - i);
      return 1000 * s / screenLength;
    },
    dash: function (metres) { return this.dashFraction((metres || 0) / lapMetres); },

    laps:        function (metres) { return Math.floor((metres || 0) / lapMetres); },
    lapFraction: function (metres) { return wrap((metres || 0) / lapMetres); }
  };
}

/* ===========================================================================
   1b. THE ELEVATION PROFILE
   ---------------------------------------------------------------------------
   The guided hero in three of the four directions is the same idea: the whole
   walk drawn end to end as ground, with the part already covered picked out
   bright and a marker showing where you are on it.

   THE LINE IS THE DECK. Not an impression of it — the actual thing the motor
   is going to do, plotted against the same clock.

   This was got wrong once, in a way worth writing down. The first version drew
   a vertex at the *middle* of each segment and joined those up, which makes a
   handsome rolling landscape and is a lie: it slopes continuously while the
   deck is holding dead flat, and it agrees with the machine at exactly two
   instants per segment. Walking on it, the line rises under your marker while
   the deck does nothing, then flattens out just as the deck starts to climb.
   Reported from the belt within minutes, and quite right.

   What the deck actually does — see MainActivity.driveIncline — is hold, move,
   hold. At a segment boundary it starts stepping [INCLINE_STEP] percent every
   [INCLINE_EVERY_MS], so a flat-to-six-and-a-half change takes about twenty
   seconds, and then it sits there for the rest of the segment. So that is what
   gets drawn: a flat run, a ramp of the real duration, a flat run. Every slope
   on screen is a slope the motor is making, and every flat is a flat.

   The same arc-length trap as the track applies, for the same reason: progress
   along the *time* axis is not progress along the drawn line, because a ramp
   covers more path per second than a flat. So `dashAt` walks the real polyline
   rather than assuming the two are proportional, and the bright line ends
   underneath the marker instead of somewhere near it. The staircase makes this
   matter more, not less — the risers are nearly vertical and eat drawn length
   without eating any clock.
   =========================================================================== */

/**
 * Seconds the deck takes per percent of grade change.
 *
 * MainActivity moves it one percent at a time, no oftener than every three
 * seconds. If either of those constants changes there, change this — a drawing
 * that claims to be the machine has to be kept honest by hand.
 */
var DECK_SEC_PER_PCT = 3;

/**
 * @param steps  plan steps as Kotlin sends them: {start, end, incline, label}
 * @param opts.width, opts.height   the drawing box, px
 * @param opts.headroom             px kept clear at the top for the marker
 * @param opts.startIncline         grade the deck is on at t=0; chooseGuided
 *                                  writes zero, so that is the default
 */
function profile(steps, opts) {
  opts = opts || {};
  var W = opts.width || 576;
  var H = opts.height || 200;
  var headroom = opts.headroom == null ? 24 : opts.headroom;

  steps = steps || [];
  if (!steps.length) {
    return { empty: true, d: '', fill: '', pins: [], total: 0, hi: 0, lo: 0,
             length: 0, yFor: function () { return H; },
             atSeconds: function () { return { x: 0, y: H }; },
             dashAt: function () { return 0; } };
  }

  var total = steps[steps.length - 1].end || 1;
  var i;

  /* ---- what the deck actually does ------------------------------------
     Hold, move, hold. At each segment boundary the deck starts stepping
     towards the new grade at DECK_SEC_PER_PCT per percent, and then it sits.

     Crucially it does not always get there. A segment can end before the deck
     has finished travelling — on a five-minute walk the segments are fifteen
     to thirty-five seconds and a jump to nine and a half percent needs nearly
     thirty, so the deck spends the whole walk chasing targets it never
     reaches. Drawing the plan's intent there would be drawing a hill nobody
     climbs. Each segment therefore starts from wherever the deck genuinely got
     to in the last one, and the line shows the shortfall.

     A short plan consequently looks much flatter than its template. That is
     not the drawing being timid; it is what a five-minute rolling-hills walk
     is. */
  var reach = [];
  var here = opts.startIncline == null ? 0 : opts.startIncline;
  var from = here;
  for (i = 0; i < steps.length; i++) {
    var seconds = steps[i].end - steps[i].start;
    var delta = steps[i].incline - here;
    var most = seconds / DECK_SEC_PER_PCT;         // percent it can travel
    var got = Math.abs(delta) <= most ? steps[i].incline
                                      : here + (delta > 0 ? most : -most);
    reach.push({ from: here, to: got,
                 ramp: Math.abs(got - here) * DECK_SEC_PER_PCT });
    here = got;
  }

  /* ---- gradient, or the ground it describes? --------------------------
     A template's x axis is seconds and its y axis is grade: the plan is a
     shape in time, and there is no distance to integrate over until somebody
     picks a pace.

     A route is different. Its steps are metres of real ground, so the honest
     drawing is the ground — cumulative elevation — and plotting grade there
     misleads. Reported from a walk on 2026-08-07: a steady -3% drew as a flat
     line, because an unchanging gradient *is* flat on a gradient axis, and it
     read as level ground. Worse, a decline easing -3 → -2 → -1 drew as a
     rising line and looked like a climb, while the walker was still going down
     and the coach was correctly saying the decline was ending. Two readings of
     one picture, and the picture was the one that was wrong.

     Integrating settles the jaggedness too: a 1% rung is a visible step on a
     gradient axis, but on an elevation axis it is a change of slope — which is
     what a hill actually looks like. */
  var byDistance = opts.byDistance != null ? !!opts.byDistance : planByDistance;
  var hi, lo, elevAt = null;

  if (byDistance) {
    // Elevation in metres at the end of each segment: rise = grade% × run.
    var elev = 0;
    elevAt = [0];
    for (i = 0; i < steps.length; i++) {
      elev += (reach[i].to / 100) * (steps[i].end - steps[i].start);
      elevAt.push(elev);
    }
    hi = lo = elevAt[0];
    for (i = 1; i < elevAt.length; i++) {
      if (elevAt[i] > hi) hi = elevAt[i];
      if (elevAt[i] < lo) lo = elevAt[i];
    }
    if (hi - lo < 1) hi = lo + 1;             // never a zero-height axis
  } else {
    /* The axis comes off the grades that get drawn, not the ones that were
       asked for — an axis topped at 9.5% over a line that only reaches 6 is
       three separate lies in one label. */
    hi = 4; lo = 0;
    for (i = 0; i < reach.length; i++) {
      if (reach[i].to > hi) hi = reach[i].to;
      if (reach[i].to < lo) lo = reach[i].to;
    }
    hi = Math.ceil(hi * 2) / 2;               // to the nearest half percent
  }

  function yFor(v) {
    return H - ((v - lo) / (hi - lo)) * (H - headroom);
  }
  function xFor(t) { return Math.max(0, Math.min(1, t / total)) * W; }

  function vertex(t, v, label) {
    return { t: t, v: v, x: xFor(t), y: yFor(v), label: label };
  }

  /** Elevation partway through segment [i], by linear interpolation. */
  function elevAtT(i, t) {
    var s = steps[i];
    var span = s.end - s.start || 1;
    var f = Math.max(0, Math.min(1, (t - s.start) / span));
    return elevAt[i] + (elevAt[i + 1] - elevAt[i]) * f;
  }

  var pts = [vertex(0, byDistance ? elevAt[0] : from)];
  var crests = [];
  for (i = 0; i < steps.length; i++) {
    var r = reach[i];
    var arriveT = steps[i].start + r.ramp;
    var arrive = vertex(arriveT, byDistance ? elevAtT(i, arriveT) : r.to, steps[i].label);
    // A zero-length ramp would put two vertices on the same spot; skip it.
    if (r.ramp > 0) pts.push(arrive);
    crests.push(arrive);
    if (steps[i].start + r.ramp < steps[i].end) {
      pts.push(vertex(steps[i].end,
                      byDistance ? elevAt[i + 1] : r.to, steps[i].label));
    }
  }

  var d = 'M ' + pts.map(function (p) {
    return p.x.toFixed(1) + ' ' + p.y.toFixed(1);
  }).join(' L ');

  /* Cumulative drawn length, so a time can be turned into a dash offset. */
  var cum = [0];
  for (i = 1; i < pts.length; i++) {
    var dx = pts[i].x - pts[i - 1].x, dy = pts[i].y - pts[i - 1].y;
    cum.push(cum[i - 1] + Math.sqrt(dx * dx + dy * dy));
  }
  var length = cum[cum.length - 1] || 1;

  /** Where the marker goes at `t` seconds in. */
  function atSeconds(t) {
    t = Math.max(0, Math.min(total, t || 0));
    for (var j = 1; j < pts.length; j++) {
      if (t <= pts[j].t) {
        var span = pts[j].t - pts[j - 1].t || 1;
        var f = (t - pts[j - 1].t) / span;
        return { x: pts[j - 1].x + (pts[j].x - pts[j - 1].x) * f,
                 y: pts[j - 1].y + (pts[j].y - pts[j - 1].y) * f,
                 seg: j - 1, f: f };
      }
    }
    var e = pts[pts.length - 1];
    return { x: e.x, y: e.y, seg: pts.length - 2, f: 1 };
  }

  /** Dash length for a path carrying pathLength="1000". */
  function dashAt(t) {
    var p = atSeconds(t);
    var base = cum[p.seg];
    var segLen = cum[p.seg + 1] - cum[p.seg];
    return 1000 * (base + segLen * p.f) / length;
  }

  return {
    empty: false,
    total: total, hi: hi, lo: lo, width: W, height: H, length: length,
    /** What the y axis means: 'm' of elevation for a route, '%' of grade for
     *  a template. A caller labelling the axis must read this, not assume. */
    unit: byDistance ? 'm' : '%',
    byDistance: byDistance,
    d: d,
    fill: d + ' L ' + W + ' ' + H.toFixed(1) + ' L 0 ' + H.toFixed(1) + ' Z',
    /**
     * One per segment, at the moment the deck finishes arriving at that
     * segment's grade — which is a real event you can feel, unlike a vertex
     * that only exists because the polyline needed a corner.
     */
    pins: crests,
    yFor: yFor, xFor: xFor,
    atSeconds: atSeconds,
    dashAt: dashAt
  };
}


/* ===========================================================================
   2. THE STATE CONTRACT
   ---------------------------------------------------------------------------
   Kotlin pushes one flat Snapshot at 5 Hz (Session.kt: Snapshot.toJson). The
   handoff assumes something richer and nested. Rather than churn the Kotlin —
   which is the part that is proven on hardware and has no business changing
   for a repaint — the flat frame is adapted here, once, into the documented
   shape. Every UI then reads the same object and none of them re-derive laps
   or climb five separate ways.

   Two things are derived rather than received:

   * `session.climb` — metres ascended. Kotlin does not track it; integrating
     Δdistance × grade on this side is accurate enough for a number displayed
     to the nearest metre, and costs nothing.
   * `control` — casual or guided, which is simply whether a plan was resolved.

   `phase` deliberately does NOT become 'fault' when the safety key is out.
   Pulling the key is an interrupt, not a route: it preempts whatever is on
   screen, and putting the key back returns to exactly where the user was with
   the session intact. A UI reads `safetyKey` and paints over the top.
   =========================================================================== */

var climbM = 0;
var lastDistance = 0;

/** New walk, or the odometer went backwards: start the climb count again. */
function resetDerived() { climbM = 0; lastDistance = 0; }

function adapt(raw) {
  raw = raw || {};
  var distance = raw.distance || 0;
  var incline  = raw.incline  || 0;
  var mode     = (raw.mode || 'welcome').toLowerCase();

  if (distance < lastDistance - 1 || mode === 'welcome') {
    resetDerived();
  } else if (distance > lastDistance) {
    /* Grade is a percentage, so rise = run × grade/100. At walking speeds the
       difference between that and the true sine is under half a percent, well
       inside the noise on a whole-metre odometer. */
    climbM += (distance - lastDistance) * incline / 100;
  }
  lastDistance = distance;

  var segments = raw.segments || 0;
  var laps = Math.floor(distance / 400);

  return {
    profile:  raw.who || '',
    control:  segments > 0 ? 'guided' : 'casual',

    /* welcome | warmup | active | paused | cooldown | summary.
       'active' rather than Kotlin's 'running' because the handoff says active
       and a run is a mode, not a phase — 'running' meant both. */
    phase:    mode === 'running' ? 'active' : mode,

    /* `speed` is the pace the console is holding — the setpoint. It is not the
       odometer estimate, and that is deliberate: see Snapshot.speed in
       Session.kt. Showing the estimate is what made the dial wander by a couple
       of tenths whenever the deck tilted, so a 0.1 press landed 0.2 away.

       `measured` is that estimate, kept for anyone who wants it, and `slipping`
       is the case it exists to catch — the belt genuinely not reaching what was
       asked for. Nothing has to draw either. */
    belt:     { speed: raw.speed || 0, incline: incline,
                measured: raw.beltKph || 0,
                slipping: !!raw.slipping },

    /* Guided owns incline only. `speed` here is a suggestion the user is free
       to ignore, and nothing may apply it to the belt. */
    targets:  { speed: raw.targetSpeed || 0,
                incline: raw.targetIncline || 0,
                suggest: raw.suggestPace || 0,
                inclineAuto: !!raw.inclineAuto },

    /* `elapsed` here is *plan* time — wrapped into the current lap when the
       route is a circuit, so a hero drawing one lap of ground can use it
       directly and never has to know about looping. `session.elapsed` is still
       the whole walk. */
    plan:     { name: raw.plan || '', steps: planSteps,
                loops: !!raw.planLoops,
                lap: raw.planLap || 1,
                elapsed: raw.planElapsed == null ? (raw.elapsed || 0) : raw.planElapsed },

    segment:  { index: raw.segment || 0, count: segments,
                name: raw.segmentLabel || '',
                secondsLeft: raw.segmentLeft || 0,
                leftIsDistance: !!raw.segmentLeftIsDistance,
                /* Preformatted, because a route measures the stretch you are on
                   in metres and a template measures it in seconds, and five UIs
                   each writing that conditional is five places to get it wrong.
                   The first route walked rendered a 275 m opening stretch as
                   "4:35 left" — the number was the distance, read as a clock. */
                leftLabel: raw.segmentLeftIsDistance
                  ? (raw.segmentLeft >= 1000
                       ? (raw.segmentLeft / 1000).toFixed(2) + ' km'
                       : Math.round(raw.segmentLeft || 0) + ' m')
                  : mmss(raw.segmentLeft || 0),
                nextName: raw.nextLabel || '',
                nextIncline: raw.nextIncline || 0 },

    /* The coach's closing line, asked for before the walk ends so it is here
       when the summary appears rather than arriving after it. */
    summaryLine: raw.summaryLine || '',

    session:  { elapsed: raw.elapsed || 0,
                distance: distance,
                calories: raw.calories || 0,
                climb: climbM,
                laps: laps,
                lapFraction: (distance % 400) / 400,
                pulse: raw.pulse || 0,
                avgSpeed: raw.avgSpeed || 0, maxSpeed: raw.maxSpeed || 0,
                avgIncline: raw.avgIncline || 0, maxIncline: raw.maxIncline || 0,
                avgPulse: raw.avgPulse || 0, maxPulse: raw.maxPulse || 0,

                /* The walker's own maximum, 0 when they have not given an age.
                   Zero is the ordinary case and the whole UI has to cope with
                   it: no age means no zones, and a console that drew five bars
                   off 220-minus-a-guess would be inventing physiology. */
                hrMax: raw.hrMax || 0,

                /* Seconds per zone, index 0-5, where 0 is below zone 1. Empty
                   when there is no maximum to divide by. */
                zones: raw.zoneSecs || [],

                /* Filled once, when the belt stops. See History.kt. */
                achievements: raw.achievements || [] },

    safetyKey: raw.dmk ? 'out' : 'in',
    fan:       raw.fan || 0,

    /* How the control board is behaving. 'ok', or one of two kinds of trouble
       that deserve different words:

         'locked' — it wants VerifySecurity and the console is authenticating.
                    Recoverable, usually within a second, and nothing the person
                    needs to do.
         'quiet'  — it is refusing frames for some other reason. The numbers on
                    screen are the last good ones, not current.

       Both used to be invisible. On 2026-08-07 a locked board produced a
       console that could navigate but not act, with nothing on screen to say
       so, and the morning went into guessing at hardware. A UI that shows this
       turns that into a sentence anyone can read. */
    board:     raw.boardLocked ? 'locked' : (raw.boardOk === false ? 'quiet' : 'ok'),

    /* Seconds left in a timed phase, and which ramp is in flight ("warmup",
       "resuming", "cooldown", "stopping" or ""). */
    phaseLeft: raw.phaseLeft || 0,
    ramping:   raw.ramping || '',

    raw: raw
  };
}

/**
 * The five heart-rate zones, as shares of maximum.
 *
 * The textbook split. `name` is what gets drawn; the percentages stay off the
 * screen because they are the crude part — 220-minus-age is ±10-12 bpm between
 * two people of the same age, so the boundary is softer than a printed "80%"
 * makes it look. The colours run cool to hot and are the same five in every
 * theme, so a glance at the summary means the same thing whichever UI is on.
 */
var ZONES = [
  { key: 'z0', name: 'Resting',   from: 0.00, colour: '#3a4348' },
  { key: 'z1', name: 'Very light',from: 0.50, colour: '#5aa9c8' },
  { key: 'z2', name: 'Light',     from: 0.60, colour: '#5fc08a' },
  { key: 'z3', name: 'Moderate',  from: 0.70, colour: '#e0c264' },
  { key: 'z4', name: 'Hard',      from: 0.80, colour: '#e08a4a' },
  { key: 'z5', name: 'Maximum',   from: 0.90, colour: '#d75d5d' }
];

/**
 * Turn the raw per-zone seconds into rows worth drawing.
 *
 * Zone 0 is dropped: "you spent four minutes below half your maximum" is the
 * warm-up and the cool-down, and listing it pushes the zones that mean
 * something down the screen. Zones with no time in them are dropped too — an
 * empty bar is a row that says nothing.
 *
 * `share` is against the *drawn* total rather than the session, so the bars
 * fill the width they are given and a walk that was half warm-up does not
 * render four stubs.
 *
 * Returns [] when there is nothing to show, which is the signal to fall back to
 * average and peak — see any theme's renderSummary.
 */
function zoneRows(zones) {
  if (!zones || zones.length < 2) return [];
  var rows = [], total = 0, i;
  for (i = 1; i < zones.length && i < ZONES.length; i++) {
    var secs = zones[i] || 0;
    if (secs <= 0) continue;
    total += secs;
    rows.push({ zone: i, name: ZONES[i].name, colour: ZONES[i].colour, secs: secs });
  }
  if (!total) return [];
  for (i = 0; i < rows.length; i++) rows[i].share = rows[i].secs / total;
  return rows;
}

/**
 * A CSS animation-duration that makes something beat at the shown rate.
 *
 * The point of the animation is that it agrees with the number beside it, so it
 * is driven by the number rather than being a fixed pulse that happens to look
 * heart-ish. Clamped either side: below about 30 bpm the strap is wrong rather
 * than the heart being slow, and above 220 the animation is a flicker.
 *
 * Returns '' when there is no reading, which stops the animation entirely
 * rather than leaving it beating at whatever the last value was — a heart that
 * carries on after the strap comes off is the one thing this must not do.
 */
function beatStyle(bpm) {
  if (!bpm || bpm < 30 || bpm > 220) return '';
  return (60 / bpm).toFixed(3) + 's';
}

/**
 * How many hills a plan actually contains.
 *
 * Not the number of segments with a positive gradient, which is what the
 * summary counted until 2026-08-07 and which produced "That's 24 hills" for a
 * 3 km route with three climbs in it. A route is a resampled GPS track cut into
 * 25 m steps and quantised to 1% rungs, so one hill arrives as a dozen
 * consecutive rising segments. Counting them is counting the sampling grid, and
 * a number that big and that wrong is worse than no number: it reads as an
 * achievement and it is an artefact.
 *
 * A hill is a *run* of climbing — the transitions from not-going-up to
 * going-up. The 0.5% floor keeps the profile's own rounding between two grid
 * steps from registering as terrain, which is the same reasoning the coach uses
 * before it will speak about a gradient change.
 */
function hillCount(steps) {
  var hills = 0, climbing = false;
  steps = steps || [];
  for (var i = 0; i < steps.length; i++) {
    var up = steps[i].incline > 0.5;
    if (up && !climbing) hills++;
    climbing = up;
  }
  return hills;
}

/* The path ahead, sent once per walk by window.plan(), not per frame. */
var planSteps = [];
/* Whether those steps are metres of ground or seconds of plan. Remembered
   here rather than passed by each UI, so all five get an elevation profile for
   a route without five separate edits — and so a UI that forgets to ask still
   gets the right drawing. */
var planByDistance = false;
function setPlan(p) {
  planSteps = (p && p.steps) || [];
  planByDistance = !!(p && p.byDistance);
  return planSteps;
}

/**
 * Which of the eleven screens the live side is showing.
 *
 * Only the live screens — 01-04 are the setup flow, which the page owns before
 * Kotlin knows a walk exists, and 07/10 are overlays rather than screens.
 * Returns 'welcome' when the setup flow should be driving.
 */
function screenFor(state) {
  switch (state.phase) {
    case 'warmup':   return 'warmup';                     // 08
    case 'cooldown': return 'cooldown';
    case 'paused':   return 'paused';                     // 09
    case 'summary':  return 'summary';                    // 11
    case 'active':   return state.control === 'guided'
                            ? 'live-guided'               // 06
                            : 'live-casual';              // 05
    default:         return 'welcome';                    // 01-04
  }
}

/* ===========================================================================
   3. THE SETUP FLOW — who / what / how / which
   ---------------------------------------------------------------------------
   Screens 01-04. Entirely client side: nothing reaches Kotlin and the belt
   cannot move until the last step commits, because on this machine choosing a
   workout *is* starting it.
   =========================================================================== */

/**
 * Who the setup flow offers, read from Settings on every call.
 *
 * This was a literal of four names — one household, in the file that all five
 * interfaces share, so fixing `original.html` alone fixed one screen out of
 * five and left Ember, Cluster, Daylight and Pacer still offering strangers.
 *
 * It matters more than it looks now that coaching and recording are per person:
 * a name offered here that Settings does not have is a walker who silently gets
 * neither. The picker and the store have to be one list.
 */
function profiles() {
  var names = people().map(function (p) { return p.name; });

  /* Guest sits alongside the household rather than instead of it: somebody
     else's turn on the belt is common, and it should not mean picking a name
     that is not yours. It is never coached and never published, because a
     guest has not agreed to anything — Settings.coachedFor / publishFor return
     false for any name that is not in the people list, so "Guest" needs no
     special case in Kotlin.

     With nobody set up at all, Guest is the only option and the treadmill
     still works, which beats refusing to start until a form is filled in. */
  if (!names.length) return ['Guest'];
  if (allowGuest()) names.push('Guest');
  return names;
}

/**
 * Walk lengths. 5 is a bench test — every segment in about thirty seconds.
 *
 * **0 means open**: no end, and the route becomes a circuit that comes round
 * again rather than running out. Kotlin resolves it at OPEN_LOOP_MIN and
 * reports which lap you are on; nothing but STOP finishes the walk.
 */
var DURATIONS = [5, 15, 20, 30, 45, 0];
function durationLabel(m) { return m > 0 ? String(m) : 'OPEN'; }

/* The four templates, mirroring Plan.kt. `points` are the real segment
   inclines from that file, so a sparkline on the picker is the walk you are
   about to get rather than a decorative squiggle. */
var SHAPES = [
  { id: 'steady',  name: 'Steady',        blurb: 'One long hill, honestly earned',
    points: [0, 2, 3.5, 5, 3, 1.5, 0] },
  { id: 'rolling', name: 'Rolling hills', blurb: 'Uneven ground, no two the same',
    points: [0, 4, -1, 6.5, 1, 9.5, 2, 5.5, -1.5, 3, 0] },
  { id: 'pyramid', name: 'Pyramid',       blurb: 'Up in steps, down in steps',
    points: [0, 2.5, 5, 7.5, 10, 11.5, 7, 4, 1.5, 0] },
  { id: 'climb',   name: 'The long climb', blurb: 'One ascent, with a sting near the top',
    points: [0, 3, 5.5, 4.5, 7.5, 11, 6, 2.5, 0] }
];

/**
 * @param onStep  called with (stepId, flow) whenever the step changes, so the
 *                UI can show the right card. Steps: profile | control | plan.
 *
 * There is no walk-or-run step, and deliberately no walk-or-run *anything*.
 * It cost a tap on every session to set a noun, and it sold itself on a speed
 * ceiling — "up to 6.5 km/h" against "6.5 and up" — that nothing enforced:
 * speed has always clamped to the board's own MIN_KPH/MAX_KPH whichever card
 * was tapped. Deriving the noun from pace instead was considered and dropped,
 * because 7-8 km/h is honestly ambiguous — the same person walks and jogs at
 * that speed — and a console that guessed would be captioning a number it
 * cannot know. It is a workout. See Snapshot.workout in Session.kt.
 */
function flow(onStep) {
  var f = {
    step: 'profile',
    profile: defaultWalker() || profiles()[0],
    control: 'guided',   // the accented, recommended card on screen 03
    minutes: 30,
    shape: 'rolling',
    /** A recorded route's id, or null for one of the four templates. */
    route: null,
    /** Walk the chosen route out and then home again — see loopMark(). Belongs
     *  to this walk, not to the route, so it is never stored anywhere. */
    loop: false,

    go: function (step) {
      f.step = step;
      if (onStep) onStep(step, f);
      return f;
    },

    reset: function () { return f.go('profile'); },

    setProfile: function (name) {
      f.profile = name;
      global.Stride.setWalker(name);
      return f.go('control');
    },

    /* Three choices, not two: casual, one of the four shapes, or a route you
       have walked. Routes are a peer of "free" and "shaped", not a variant of
       one — and putting them on the screen before keeps the plan step to four
       entries, which is all it has room for above the BACK button. */
    setControl: function (c) {
      f.control = c;
      if (c === 'casual') return f.commit();
      // Either picker is entered clean, so a route chosen and backed out of
      // cannot arm the belt when a shape is picked afterwards.
      f.route = null;
      f.loop = false;
      return f.go('plan');
    },

    /** True while the plan step is choosing a route rather than a shape. */
    pickingRoute: function () { return f.control === 'routes'; },

    /* Moving to a different route drops the loop rather than carrying it over.
       Looping is a decision about a particular walk — "this one is only half an
       hour one way" — and inheriting it silently is how somebody sets off on
       twice the walk they chose. */
    setRoute: function (id) {
      if (id !== f.route) f.loop = false;
      f.route = id;
      return f;
    },

    /** Tapping the loop mark picks that route as well as looping it, the way
     *  repeat on a music player applies to the thing you are playing. */
    toggleLoop: function (id) {
      f.loop = id === f.route ? !f.loop : true;
      f.route = id;
      return f;
    },

    /** Is this route the chosen one, and looped? */
    looped: function (id) { return f.loop && f.route === id; },

    /** The routes cached on this console, or [] when there are none. */
    routes: function () {
      try { return JSON.parse(global.Stride.routes() || '[]'); }
      catch (e) { return []; }
    },

    setMinutes: function (m) { f.minutes = m; return f; },
    setShape:   function (s) { f.shape = s;   return f; },

    /** The one call that arms the belt. */
    commit: function () {
      if (f.control === 'casual') { global.Stride.choose(); return f; }
      if (f.control === 'routes') {
        // Nothing to arm the belt with if no route was picked.
        if (f.route) global.Stride.chooseRoute(f.route, f.loop);
        return f;
      }
      global.Stride.chooseGuided(f.shape, f.minutes);
      return f;
    },

    back: function () {
      if (f.step === 'plan')    return f.go('control');
      if (f.step === 'control') return f.go('profile');
      return f;
    }
  };
  return f;
}

/* ===========================================================================
   3b. THE FIVE INTERFACES
   ---------------------------------------------------------------------------
   Every UI carries a settings screen that can reach the other four, so the
   list of what exists — and what each one looks like — is shared rather than
   copied five times.

   The sketches are 160×100 inline SVG, each drawn in its own palette. Inline
   because the console may boot offline and there is nowhere to fetch an image
   from; SVG rather than a screenshot because a thumbnail of a layout only has
   to say "big dials" or "one bright pill", and 300 bytes says that fine.
   =========================================================================== */

var UI_META = [
  /* The arcs below are drawn on the *same* stadium as the rail under them —
     top straight plus the right bend, which is exactly half the perimeter. An
     approximate arc reads as a mistake at this size, because the eye has the
     rail sitting right there to compare it against. */
  { id: 'original', name: 'Original', blurb: 'Oval track, big flanking numbers. The one it has been walked on.',
    sketch:
      '<rect width="160" height="100" fill="#0a1533"/>' +
      '<rect x="54" y="24" width="52" height="34" rx="17" fill="none" stroke="#16255a" stroke-width="1.5"/>' +
      '<path d="M71 24 H89 A17 17 0 0 1 89 58" fill="none" stroke="#39e0ff" stroke-width="2.5" stroke-linecap="round"/>' +
      '<circle cx="89" cy="58" r="2.5" fill="#eaf0ff"/>' +
      '<rect x="12" y="30" width="26" height="12" rx="2" fill="#eaf0ff" opacity=".85"/>' +
      '<rect x="122" y="30" width="26" height="12" rx="2" fill="#eaf0ff" opacity=".85"/>' +
      '<rect x="12" y="66" width="136" height="1" fill="#16255a"/>' +
      '<g fill="#16255a"><rect x="12" y="82" width="20" height="10" rx="5"/><rect x="36" y="82" width="20" height="10" rx="5"/>' +
      '<rect x="60" y="82" width="20" height="10" rx="5"/><rect x="84" y="82" width="20" height="10" rx="5"/>' +
      '<rect x="108" y="82" width="20" height="10" rx="5"/></g>' +
      '<rect x="132" y="82" width="16" height="10" rx="5" fill="#ff5c7a" opacity=".6"/>' },

  { id: 'ember', name: 'Ember', blurb: 'Warm near-black, hairlines, one amber light. Built for 6am.',
    sketch:
      '<rect width="160" height="100" fill="#0b0a09"/>' +
      '<rect x="54" y="22" width="52" height="36" rx="18" fill="none" stroke="#221f1c" stroke-width="1"/>' +
      '<path d="M62 22 H98 A18 18 0 0 1 106 40" fill="none" stroke="#e0a458" stroke-width="2" stroke-linecap="round"/>' +
      '<rect x="14" y="12" width="18" height="3" fill="#7c756b" opacity=".5"/>' +
      '<rect x="14" y="64" width="132" height="1" fill="#1c1917"/>' +
      '<g fill="#f4efe6" opacity=".7"><rect x="14" y="72" width="20" height="7"/><rect x="46" y="72" width="20" height="7"/>' +
      '<rect x="78" y="72" width="20" height="7"/><rect x="110" y="72" width="20" height="7"/></g>' +
      '<g fill="none" stroke="#2a2521"><rect x="14" y="86" width="26" height="9"/><rect x="44" y="86" width="20" height="9"/>' +
      '<rect x="68" y="86" width="20" height="9"/><rect x="92" y="86" width="20" height="9"/></g>' +
      '<rect x="120" y="86" width="26" height="9" fill="#e0553e"/>' },

  { id: 'cluster', name: 'Cluster', blurb: 'Machined dials and mono figures. An instrument cluster.',
    sketch:
      '<rect width="160" height="100" fill="#0d0f10"/>' +
      '<circle cx="46" cy="40" r="21" fill="none" stroke="#23282b" stroke-width="5"/>' +
      '<path d="M46 19 A21 21 0 0 1 63 51" fill="none" stroke="#6fd0e0" stroke-width="5" stroke-linecap="round"/>' +
      '<circle cx="114" cy="40" r="21" fill="none" stroke="#23282b" stroke-width="5"/>' +
      '<path d="M114 19 A21 21 0 0 1 130 30" fill="none" stroke="#e5b567" stroke-width="5" stroke-linecap="round"/>' +
      '<g fill="#141719"><rect x="12" y="74" width="32" height="16" rx="3"/><rect x="48" y="74" width="32" height="16" rx="3"/>' +
      '<rect x="84" y="74" width="32" height="16" rx="3"/></g>' +
      '<rect x="120" y="74" width="28" height="16" rx="3" fill="#d92d20"/>' },

  /* No oval on this one, because there isn't one: Daylight's casual hero is a
     lap count set enormous with a progress bar under it. A thumbnail showing a
     ring would be advertising the wrong interface. What it shows instead is
     what actually distinguishes it — paper, ink borders, editorial left-align,
     one big figure. */
  { id: 'daylight', name: 'Daylight', blurb: 'Warm paper and ink. The light one — bright rooms, older eyes.',
    sketch:
      '<rect width="160" height="100" fill="#eae6dc"/>' +
      '<rect x="11" y="12" width="138" height="2" fill="#1b1a17"/>' +
      '<text x="11" y="52" font-family="Helvetica,Arial,sans-serif" font-size="38" font-weight="700" fill="#1b1a17">2</text>' +
      '<rect x="11" y="60" width="88" height="7" fill="#dcd7c9"/>' +
      '<rect x="11" y="60" width="62" height="7" fill="#3d6b4a"/>' +
      '<rect x="112" y="22" width="37" height="18" rx="3" fill="#f6f3ea" stroke="#1b1a17" stroke-width="2"/>' +
      '<rect x="112" y="46" width="37" height="18" rx="3" fill="#f6f3ea" stroke="#1b1a17" stroke-width="2"/>' +
      '<g fill="#f6f3ea" stroke="#1b1a17" stroke-width="2"><rect x="11" y="78" width="30" height="14" rx="3"/>' +
      '<rect x="47" y="78" width="26" height="14" rx="3"/><rect x="79" y="78" width="26" height="14" rx="3"/></g>' +
      '<rect x="111" y="78" width="38" height="14" rx="3" fill="#c02b1d" stroke="#1b1a17" stroke-width="2"/>' },

  { id: 'pacer', name: 'Pacer', blurb: 'Violet and lime, chunky pills, plain-spoken. Hard to misread.',
    sketch:
      '<rect width="160" height="100" fill="#16121f"/>' +
      '<rect x="10" y="10" width="66" height="26" rx="13" fill="#211b2e"/>' +
      '<rect x="84" y="10" width="66" height="26" rx="13" fill="#211b2e"/>' +
      '<rect x="10" y="44" width="140" height="26" rx="13" fill="#1c1728"/>' +
      '<rect x="10" y="44" width="86" height="26" rx="13" fill="#c6f24e"/>' +
      '<g fill="#2b2340"><rect x="10" y="78" width="34" height="16" rx="8"/><rect x="48" y="78" width="30" height="16" rx="8"/>' +
      '<rect x="82" y="78" width="30" height="16" rx="8"/></g>' +
      '<rect x="116" y="78" width="34" height="16" rx="8" fill="#ff4d3d"/>' }
];

/** Ask the bridge what is actually in this build; fall back to all of them. */
function uiList() {
  try {
    return JSON.parse(global.Stride.availableUis());
  } catch (e) {
    return UI_META.map(function (u) { return u.id; });
  }
}

function currentUi() {
  try { return global.Stride.currentUi(); } catch (e) { return 'original'; }
}

/**
 * Everyone set up on this console, newest state every call.
 *
 * The welcome screen used to hold this as a literal — four names of one
 * household, in a file that ships to anybody. Worse than untidy: the coach is
 * now gated per person, so a name that exists on the picker but not in Settings
 * is a walker who silently gets no coaching and no history. The picker and the
 * store have to be the same list, and this is the only copy of it.
 *
 * Empty is a real answer, not a failure — a console nobody has set up yet.
 */
function people() {
  try {
    return JSON.parse(global.Stride.settingsJson()).people || [];
  } catch (e) {
    return [];
  }
}

function defaultWalker() {
  try {
    return JSON.parse(global.Stride.settingsJson()).default_walker || '';
  } catch (e) {
    return '';
  }
}

function allowGuest() {
  try {
    return JSON.parse(global.Stride.settingsJson()).allow_guest !== false;
  } catch (e) {
    return true;
  }
}

/* ===========================================================================
   4. TOUCH
   ---------------------------------------------------------------------------
   Fire on touchstart, not click: `click` waits for touch-end plus gesture
   disambiguation, which reads as an ignored press when you are mid-stride.
   Every tappable thing also wants `touch-action: manipulation` in CSS or the
   WebView spends ~300 ms deciding whether it was a double-tap.
   =========================================================================== */

/* Nothing counts as a press in the moment just after waking the screen — the
   tap that woke it was aimed at a dark panel, not at whatever is under it. */
var quietUntil = 0;
function suppress(ms) { quietUntil = Date.now() + (ms || 500); }
function justWoke() { return Date.now() < quietUntil; }

function tap(el, fn) {
  if (!el) return el;
  var handled = false;
  el.addEventListener('touchstart', function (e) {
    handled = true;
    el.classList.add('down');
    if (!justWoke()) fn(e);
    e.preventDefault();
  }, { passive: false });
  var up = function () { el.classList.remove('down'); };
  el.addEventListener('touchend', up);
  el.addEventListener('touchcancel', up);
  el.addEventListener('click', function (e) { if (!handled && !justWoke()) fn(e); });
  return el;
}

/* Fine on a tap, coarse on a hold, the way the physical buttons behave.
   A flat coarse repeat was 3.6 km/h per second and the target "just took off";
   a uniformly slow one made a deliberate run to 18 km/h tedious. So it
   accelerates — gentle for the first second, quicker the longer you hold. */
var HOLD_MS = 550, REPEAT_START_MS = 420, REPEAT_MIN_MS = 110, REPEAT_DECAY = 0.82;

function stepper(el, fn, fine, coarse) {
  if (!el) return el;
  var holdTimer = null, repeatTimer = null, handled = false;
  function begin() {
    if (justWoke()) return;
    fn(fine);
    var gap = REPEAT_START_MS;
    function again() {
      fn(coarse);
      gap = Math.max(REPEAT_MIN_MS, gap * REPEAT_DECAY);
      repeatTimer = setTimeout(again, gap);
    }
    holdTimer = setTimeout(again, HOLD_MS);
  }
  function end() {
    clearTimeout(holdTimer); clearTimeout(repeatTimer);
    holdTimer = repeatTimer = null;
    el.classList.remove('down');
  }
  el.addEventListener('touchstart', function (e) {
    handled = true; el.classList.add('down'); begin(); e.preventDefault();
  }, { passive: false });
  el.addEventListener('touchend', end);
  el.addEventListener('touchcancel', end);
  el.addEventListener('mousedown',  function () { if (!handled) begin(); });
  el.addEventListener('mouseup',    function () { if (!handled) end(); });
  el.addEventListener('mouseleave', function () { if (!handled) end(); });
  return el;
}

/**
 * Hold-to-continue, for the safety-key screen. A single tap must not clear an
 * alarm that means the belt cut out under someone.
 *
 * @param onProgress optional, called with 0..1 so the UI can fill something.
 */
function hold(el, ms, fn, onProgress) {
  if (!el) return el;
  var startedAt = 0, timer = null, raf = null;
  function tick() {
    var p = Math.min(1, (Date.now() - startedAt) / ms);
    if (onProgress) onProgress(p);
    if (p < 1) raf = setTimeout(tick, 50);
  }
  function begin(e) {
    if (justWoke()) return;
    startedAt = Date.now();
    el.classList.add('down');
    timer = setTimeout(function () { stop(); fn(); }, ms);
    tick();
    if (e && e.preventDefault) e.preventDefault();
  }
  function stop() {
    clearTimeout(timer); clearTimeout(raf);
    timer = raf = null;
    el.classList.remove('down');
    if (onProgress) onProgress(0);
  }
  el.addEventListener('touchstart', begin, { passive: false });
  el.addEventListener('touchend', stop);
  el.addEventListener('touchcancel', stop);
  el.addEventListener('mousedown', begin);
  el.addEventListener('mouseup', stop);
  el.addEventListener('mouseleave', stop);
  return el;
}

/* ===========================================================================
   5. FORMATTING
   =========================================================================== */

function mmss(s) {
  s = Math.max(0, Math.round(s || 0));
  var h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
  var mm = h ? (m < 10 ? '0' + m : '' + m) : '' + m;
  return (h ? h + ':' : '') + mm + ':' + (sec < 10 ? '0' + sec : sec);
}

/* 3201 -> "3,201". By hand rather than toLocaleString so it cannot depend on
   whatever ICU data this 2020-vintage WebView happens to ship with. */
function nf(n) {
  return String(Math.round(n || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/* Units. Settings → Display stores "km" or "mi"; the board is metric either
   way, so this only changes what is drawn. settingsJson() builds the whole
   settings document, so it is read through the bridge at most once a second
   rather than on every repaint. */
var MI_PER_KM = 0.621371;
var M_PER_MI = 1609.344;
var unitsAt = 0, unitsMi = false, warmKph = 2;
function mi() {
  var now = Date.now();
  if (now - unitsAt > 1000) {
    unitsAt = now;
    try {
      var cfg = JSON.parse(global.Stride.settingsJson());
      unitsMi = cfg.units === 'mi';
      warmKph = cfg.warmup_kph > 0 ? cfg.warmup_kph : 2;
    }
    catch (e) { /* no bridge (a browser): stay metric, 2 km/h */ }
  }
  return unitsMi;
}

/** Belt speed from km/h to whatever the screen is set to. */
function spd(kph, dp) {
  return ((kph || 0) * (mi() ? MI_PER_KM : 1)).toFixed(dp == null ? 1 : dp);
}
function spdUnit() { return mi() ? 'mph' : 'km/h'; }

/** The warm-up speed from Settings → Treadmill, as a phrase: "2 km/h",
 *  "1.5 mph". The trailing ".0" is dropped so a round number reads round. */
function warmupText() {
  var v = spd(warmKph);
  return String(+v) + ' ' + spdUnit();
}
function distUnit() { return mi() ? 'mi' : 'km'; }

/** Metres to miles, two decimals for the same reason as [km]. */
function miles(m, dp) { return ((m || 0) / M_PER_MI).toFixed(dp == null ? 2 : dp); }

/** A distance already in km, with its unit, for the route cards. */
function kmText(k) {
  return mi() ? (k * MI_PER_KM).toFixed(2) + ' mi' : k.toFixed(2) + ' km';
}

/** Metres to km, or to miles when the screen is set to them. Two decimals by
 *  default: at walking pace one decimal sits still for two minutes at a
 *  time, which reads as a frozen display. */
function km(m, dp) {
  return mi() ? miles(m, dp) : ((m || 0) / 1000).toFixed(dp == null ? 2 : dp);
}

/* Static unit labels in the markup carry data-u="speed" | "dist" | "distm"
   ("distm" is metres, or miles), or "speedU" / "distU" for the upper-case
   labels some screens use. Re-applied every second so a change made on
   the settings screen shows without a reload. */
function applyUnits() {
  var els = document.querySelectorAll('[data-u]');
  for (var i = 0; i < els.length; i++) {
    var k = els[i].getAttribute('data-u');
    var t = k === 'speed'  ? spdUnit()
          : k === 'speedU' ? spdUnit().toUpperCase()
          : k === 'dist'   ? distUnit()
          : k === 'distU'  ? distUnit().toUpperCase()
          : k === 'distm'  ? (mi() ? 'mi' : 'm')
          : k === 'warmup' ? warmupText() : null;
    if (t === null || els[i].textContent.trim() === t) continue;
    els[i].textContent = (/^\s/.test(els[i].textContent) ? ' ' : '') + t;
  }
}
if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', applyUnits);
  setInterval(applyUnits, 1000);
}

var FAN_NAMES = ['OFF', 'LOW', 'MEDIUM', 'HIGH', 'AUTO'];
function fanName(n) { return FAN_NAMES[n] || 'OFF'; }
/** The control row's own label: "OFF" or "2 OF 4". */
function fanLabel(n) { return n ? n + ' OF 4' : 'OFF'; }

/* ===========================================================================
   6. THE BRIDGE
   ---------------------------------------------------------------------------
   `Stride` is the @JavascriptInterface object MainActivity injects. Stubbing
   it here means every UI opens standalone in a desktop browser without each
   one carrying its own copy of the stub.
   =========================================================================== */

var BRIDGE = ['choose', 'chooseGuided', 'chooseRoute', 'skipWarmup', 'skipCooldown', 'pause',
              'setSpeed',
              'resume', 'end', 'home', 'speed', 'incline', 'fan', 'setFan',
              'setWalker', 'ackDmk', 'hushCoach', 'dim', 'setUi', 'wakeBoard'];

/** @return true if this page is running without the console behind it. */
function stub() {
  if (global.Stride && global.Stride.pause) return false;
  var s = {};
  BRIDGE.forEach(function (name) {
    s[name] = function () {
      var args = Array.prototype.slice.call(arguments);
      console.log('Stride.' + name, args);
    };
  });
  /* The two that return something. On a desktop every UI is "available", which
     is what you want when you are comparing them side by side in a browser. */
  /* The machine is awake on a desktop, unless you are here to look at the
     wake step — `?asleep=1` opens on a console whose board has gone to sleep,
     and the pretend board comes back a second and a half after WAKE, the way
     a real one does. `?asleep=dead` never comes back, which is the branch
     that is otherwise only reachable by unplugging a treadmill. */
  var pretendWake = /(^|[?&])asleep=/.test(global.location.search) ? 'asleep' : 'awake';
  var pretendDead = /(^|[?&])asleep=dead/.test(global.location.search);
  s.wakeState = function () { return pretendWake; };
  s.wakeBoard = function () {
    pretendWake = 'waking';
    if (pretendDead) return;
    global.setTimeout(function () { pretendWake = 'awake'; }, 1500);
  };

  var pretendUi = 'original';
  s.currentUi = function () { return pretendUi; };
  s.availableUis = function () {
    return '["original","ember","cluster","daylight","pacer"]';
  };
  s.setUi = function (name) { pretendUi = name; console.log('Stride.setUi', name); };

  /* Two routes on the desktop, so the third control card and the route list can
     be designed and reviewed in a browser. Real ones come from the phone, and
     the shape matches RouteStore.swift exactly: segments are
     [startM, endM, incline] triples. */
  s.routes = function () {
    return JSON.stringify([
      { id: 'demo-river', name: 'Friday river loop', distance_m: 5060,
        climb_m: 18, difficulty: 1.0,
        segments: [[0,900,0],[900,1400,1],[1400,2100,2],[2100,2600,1],
                   [2600,3400,0],[3400,3900,-1],[3900,4500,0],[4500,5060,1]] },
      { id: 'demo-hill', name: 'Thursday the hill', distance_m: 3030,
        climb_m: 68, difficulty: 1.0,
        segments: [[0,200,6],[200,700,8],[700,1100,5],[1100,1600,2],
                   [1600,2100,-2],[2100,2500,-3],[2500,3030,0]] }
    ]);
  };
  global.Stride = s;
  return true;
}

/* ===========================================================================
   6b. WAKING THE MACHINE
   ---------------------------------------------------------------------------
   Two things on this console sleep, and the tap only ever woke one of them.

   The panel dims itself after five idle minutes; each UI owns that, because
   each owns its own sleep screen. The control board runs an idle timer of its
   own that STRIDE does not set, and when it expires the machine goes dormant —
   FitPro.Mode.SLEEP, which the HUD reads every poll and used to render as a
   shrug. Come back after a week away and everything looks right: the screen
   lights, the buttons answer, and the belt will not move for anything.

   iFit's own console had the step this restores. A tap woke the display, and
   then it asked, separately, whether to wake the machine as well. Keeping the
   two apart is right and not merely nostalgic: waking the board spins a motor
   controller up in a dark room, and somebody walking past to read the clock
   has not asked for that.

   So the tap always wakes the panel. If the board is already up — the ordinary
   morning — nothing else happens and the tap is the whole gesture, exactly as
   it has always been. The question is only put when there is something to ask.
   =========================================================================== */

var WAKE_POLL_MS = 300;
var WAKE_LINES = {
  asleep:      ['The treadmill is asleep.',
                'The screen is awake. Wake the machine as well?'],
  waking:      ['Waking the machine\u2026', 'It takes a moment to come back.'],
  unreachable: ['The machine did not answer.',
                'Switch it off at the wall, wait a moment, and switch it back on.']
};

/* One wake at a time. Every press inside the question also reaches the
   overlay's own tap handler underneath it, and without this each one would
   tear down the exchange it was answering. */
var wakeBusy = false;

/**
 * The tap on a sleeping console.
 *
 * Always wakes the panel. Calls `done` — the UI's own "put the sleep screen
 * away" — either immediately, when the machine is up and there is nothing to
 * ask, or once the question has been answered one way or the other.
 */
function wakeStep(overlay, done) {
  /* Unconditionally and first: whatever gets decided about the machine, the
     tap that arrived was aimed at a dark screen. */
  Stride.dim(false);
  if (wakeBusy) return;
  if (!overlay || Stride.wakeState() === 'awake') { done(); return; }
  wakeBusy = true;

  var ask = overlay.querySelector('.wakeask');
  if (!ask) {
    /* Built here rather than in five documents, and styled inline for the same
       reason: this is one question with one answer, and the five sleep screens
       are all but identical — a dark field with a clock on it. Deliberately
       colourless, so it does not have to pick a side between five accents. */
    ask = document.createElement('div');
    ask.className = 'wakeask';
    ask.style.cssText = 'position:absolute;top:0;right:0;bottom:0;left:0;' +
      'display:flex;flex-direction:column;align-items:center;justify-content:center;' +
      'text-align:center;color:#d6d1cb;font-weight:200;z-index:1';
    ask.innerHTML =
      '<div class="wt" style="font-size:44px;letter-spacing:.01em"></div>' +
      '<div class="ws" style="margin-top:20px;font-size:20px;color:#837d75"></div>' +
      '<div class="wb" style="margin-top:54px;display:flex"></div>';
    overlay.appendChild(ask);
  }

  /* The clock and "tap to wake" belong to the sleeping screen, not to this
     one. Hidden rather than removed — they are the UI's markup, and they go
     back exactly as they were. */
  var hidden = [];
  for (var i = 0; i < overlay.children.length; i++) {
    if (overlay.children[i] === ask) continue;
    hidden.push(overlay.children[i]);
    overlay.children[i].style.visibility = 'hidden';
  }

  var timer = null;

  function button(label, fn) {
    var b = document.createElement('div');
    b.style.cssText = 'margin:0 14px;padding:0 46px;height:84px;line-height:84px;' +
      'font-size:19px;letter-spacing:.22em;border:1px solid #4d4841;color:#d6d1cb;' +
      'touch-action:manipulation';
    b.textContent = label;
    return tap(b, fn);
  }

  function show(state, buttons) {
    ask.querySelector('.wt').textContent = WAKE_LINES[state][0];
    ask.querySelector('.ws').textContent = WAKE_LINES[state][1];
    var row = ask.querySelector('.wb');
    row.innerHTML = '';
    buttons.forEach(function (b) { row.appendChild(b); });
    ask.style.display = 'flex';
    /* The finger that opened this is still on the glass, over whichever
       button has just been drawn under it. Same rule as waking the screen. */
    suppress(400);
  }

  function close() {
    if (timer) { global.clearInterval(timer); timer = null; }
    ask.style.display = 'none';
    hidden.forEach(function (c) { c.style.visibility = ''; });
    wakeBusy = false;
    done();
  }

  show('asleep', [
    button('WAKE', function () {
      Stride.wakeBoard();
      show('waking', []);
      timer = global.setInterval(function () {
        var state = Stride.wakeState();
        if (state === 'awake') { close(); return; }
        if (state === 'unreachable') {
          global.clearInterval(timer);
          timer = null;
          show('unreachable', [button('CLOSE', close)]);
        }
      }, WAKE_POLL_MS);
    }),
    button('NOT NOW', close)
  ]);
}

/* ===========================================================================
   7. DESKTOP DEMO
   ---------------------------------------------------------------------------
   Only runs when the bridge is a stub, so it can never fight the real
   renderer on the console. Drives window.render at the same 5 Hz Kotlin does,
   which is the only honest way to check that a layout does not jitter.

       ?screen=live-guided   open straight onto one screen
       ?dmk=1                safety key out
       ?coach=1              a coach line on arrival

   Number keys 1-9 and 0 jump between screens while it runs.
   =========================================================================== */

var DEMO_SCREENS = ['welcome', 'warmup', 'live-casual', 'live-guided',
                    'paused', 'cooldown', 'summary'];

function demo(opts) {
  opts = opts || {};
  var q = {};
  global.location.search.replace(/^\?/, '').split('&').forEach(function (kv) {
    if (!kv) return;
    var p = kv.split('=');
    q[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || '');
  });

  var screen = q.screen || opts.screen || 'live-guided';
  var guided = screen === 'live-guided' || screen === 'warmup' || opts.guided;

  /* Rolling hills at 30 minutes, resolved the way Plan.kt would. */
  var shape = SHAPES[1].points;
  var total = 30 * 60, steps = [], at = 0;
  var shares = [0.11, 0.09, 0.07, 0.12, 0.06, 0.05, 0.09, 0.08, 0.07, 0.12, 0.14];
  var labels = ['Settle in', 'First rise', 'Down the far side', 'The long one',
                'Short recovery', 'The wall', 'Over the top', 'One more',
                'Long descent', 'The last drag', 'Cool down'];
  var inclines = [0, 4, -1, 6.5, 1, 9.5, 2, 5.5, -1.5, 3, 0];
  for (var i = 0; i < shares.length; i++) {
    var end = i === shares.length - 1 ? total : at + total * shares[i];
    steps.push({ start: Math.round(at), end: Math.round(end),
                 incline: inclines[i], label: labels[i] });
    at = end;
  }

  var t = opts.elapsed == null ? 512 : opts.elapsed;   // mid-walk, mid-hill
  var dist = 720, cals = 78;

  function frame() {
    var phase = screen === 'live-casual' || screen === 'live-guided' ? 'running'
              : screen === 'welcome' ? 'welcome' : screen;
    var moving = phase === 'running' || phase === 'warmup' || phase === 'cooldown';
    var step = null, idx = 0;
    for (var j = 0; j < steps.length; j++) {
      if (t < steps[j].end) { step = steps[j]; idx = j + 1; break; }
    }
    if (!step) { step = steps[steps.length - 1]; idx = steps.length; }

    var speed = moving ? (phase === 'warmup' ? 2.0 : 5.4) : 0;
    if (moving) { t += 0.2; dist += speed / 3.6 * 0.2; cals += 0.02; }

    global.render({
      speed: speed, incline: guided ? step.incline : 1.5,
      targetSpeed: speed, targetIncline: guided ? step.incline : 1.5,
      distance: Math.round(dist), elapsed: Math.round(t),
      calories: Math.round(cals), pulse: 0, fan: 2,
      workout: 'workout', dmk: q.dmk === '1',
      ramping: '', phaseLeft: phase === 'warmup' ? 161 : 0, boardMode: 2,
      avgSpeed: 5.2, maxSpeed: 6.1, avgIncline: 3.4, maxIncline: 9.5,
      who: 'Sam',
      plan: guided ? 'Rolling hills' : '',
      segment: guided ? idx : 0, segments: guided ? steps.length : 0,
      segmentLabel: guided ? step.label : '',
      segmentLeft: guided ? step.end - t : 0,
      suggestPace: guided ? 5.6 : 0, inclineAuto: true,
      mode: phase
    });
  }

  if (guided && global.plan) global.plan({ name: 'Rolling hills', steps: steps });
  if (q.coach === '1' && global.coach) {
    setTimeout(function () {
      global.coach({ line: 'Nine and a half percent for the next forty seconds. ' +
                           'Shorten your stride and stay tall.', kind: 'local' });
    }, 400);
  }

  frame();
  setInterval(frame, 200);

  global.addEventListener('keydown', function (e) {
    var n = parseInt(e.key, 10);
    if (isNaN(n)) return;
    var pick = DEMO_SCREENS[n === 0 ? 9 : n - 1];
    if (!pick) return;
    screen = pick;
    guided = pick === 'live-guided' || pick === 'warmup';
    if (guided && global.plan) global.plan({ name: 'Rolling hills', steps: steps });
    if (pick === 'welcome') resetDerived();
  });

  return { screens: DEMO_SCREENS };
}

/* ===========================================================================
   ROUTES IN A PICKER
   ---------------------------------------------------------------------------
   Two helpers all five interfaces need, kept here rather than copied into each
   of them — a route's name is untrusted text in every one of the five, and a
   route's profile is sampled the same way whatever the card looks like.
   =========================================================================== */

/**
 * A route's own gradient profile, sampled evenly for a card sparkline.
 *
 * Strictly better than a generic shape icon: it is the ground you are about to
 * walk. Segments arrive as `[startM, endM, incline]` triples — see
 * RouteStore.swift for why they are bare arrays.
 */
function routePoints(r) {
  var segs = (r && r.segments) || [];
  if (!segs.length) return [0, 0];
  var total = segs[segs.length - 1][1] || 1, out = [];
  for (var i = 0; i <= 40; i++) {
    var d = i / 40 * total, v = segs[segs.length - 1][2];
    for (var j = 0; j < segs.length; j++) {
      if (d < segs[j][1]) { v = segs[j][2]; break; }
    }
    out.push(v);
  }
  return out;
}

/* ---------------------------------------------------------------------------
   THE LOOP MARK
   ---------------------------------------------------------------------------
   A recorded route is usually one way, and one way is not always a walk: the
   half hour from the hospital to the front door is a fine walk and half a
   workout. The mark turns that route into an out-and-back for *this* session —
   Kotlin reverses the ground and inverts the gradients, so the hill you climbed
   on the way out is the one you come down on the way home. See
   Route.outAndBack.

   The glyph is the repeat arrows everybody already knows from a music player,
   because that is what it means and nothing else needs explaining.

   It is a `<span>` inside the card's own button, not a button of its own. A
   button inside a button is invalid and Chromium does the obvious wrong thing
   with the nested tap; the card's single handler asks [tappedLoop] which half
   of itself was hit. That keeps five interfaces on one tap handler each.
   --------------------------------------------------------------------------- */

var LOOP_PATH = 'M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z';

/**
 * Total ascent in metres of walking this route out and back.
 *
 * Mirrors `Route.outAndBack` in Routes.kt, and has to: a card that says "68 m
 * of climb" beside a looped route is describing a walk nobody is going to take.
 * Reversing the ground turns every descent into a climb, so the answer is the
 * sum of the *absolute* rise of every segment — which for a route that does not
 * end where it started is not twice its one-way figure.
 */
function loopedClimb(r) {
  var segs = (r && r.segments) || [], m = 0;
  for (var i = 0; i < segs.length; i++) {
    m += Math.abs(segs[i][2]) / 100 * (segs[i][1] - segs[i][0]);
  }
  return m;
}

/** Markup for the mark. `on` lights it. Every UI styles `.loop` itself. */
function loopMark(on) {
  return '<span class="loop' + (on ? ' on' : '') + '">' +
         '<svg viewBox="0 0 24 24"><path d="' + LOOP_PATH + '"/></svg></span>';
}

/**
 * Did this tap land on the loop mark rather than on the card around it?
 *
 * Walks up from the target rather than using `closest`, which on this WebView
 * is not reliable from an SVG child, and the thing under a finger here is
 * usually the `<path>`.
 */
function tappedLoop(e) {
  for (var n = e && e.target; n; n = n.parentNode) {
    if (n.classList && n.classList.contains('loop')) return true;
  }
  return false;
}

/** Route names are typed by a person on a phone, and every UI builds its cards
 *  with innerHTML. */
function esc(t) {
  return String(t == null ? '' : t).replace(/[&<>"]/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}

/* ========================================================================= */

global.STRIDE = {
  track: track,
  profile: profile,
  hillCount: hillCount,
  adapt: adapt,
  setPlan: setPlan,
  screenFor: screenFor,
  resetDerived: resetDerived,

  flow: flow,
  routePoints: routePoints,
  loopMark: loopMark,
  tappedLoop: tappedLoop,
  loopedClimb: loopedClimb,
  esc: esc,
  profiles: profiles,
  allowGuest: allowGuest,
  DURATIONS: DURATIONS,
  SHAPES: SHAPES,

  UI_META: UI_META,
  people: people,
  defaultWalker: defaultWalker,
  uiList: uiList,
  currentUi: currentUi,

  tap: tap,
  stepper: stepper,
  hold: hold,
  suppress: suppress,
  justWoke: justWoke,
  wakeStep: wakeStep,

  mmss: mmss,
  nf: nf,
  km: km,
  mi: mi,
  spd: spd,
  spdUnit: spdUnit,
  warmupText: warmupText,
  distUnit: distUnit,
  miles: miles,
  kmText: kmText,
  applyUnits: applyUnits,
  durationLabel: durationLabel,
  fanName: fanName,
  fanLabel: fanLabel,

  ZONES: ZONES,
  zoneRows: zoneRows,
  beatStyle: beatStyle,

  stub: stub,
  demo: demo,

  /** document.getElementById, because every UI wants it on the first line. */
  $: function (id) { return document.getElementById(id); }
};

})(window);
