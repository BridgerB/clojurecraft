// The results page's script (bb site, issue #116). It reads the data the publishers keep on the
// gh-pages branch (data/summary.json first, a day's file when the history opens it, gyms.ndjson,
// agents.json) and the orchestrator's state live, and draws four tabs: the road from spawn to the
// dragon, the trends over days, the whole history by day, and the agents' work. Charts are inline
// SVG drawn here at the width they get; every mark has its numbers on hover or tap. No library.
(function () {
  'use strict';

  // ---------------------------------------------------------------- data and helpers

  var repo = document.body.dataset.repo || 'BridgerB/clojurecraft';
  var gh = 'https://github.com/' + repo;
  var ROAD = JSON.parse(document.getElementById('road').textContent);
  var STAGES = ROAD.stages;
  var CHAIN = STAGES.filter(function (s) { return s.race; }).map(function (s) { return s.id; });
  var BY_ID = {};
  STAGES.forEach(function (s, i) { s.i = i; BY_ID[s.id] = s; });
  var PHASES = [];
  STAGES.forEach(function (s) { if (PHASES.indexOf(s.phase) < 0) PHASES.push(s.phase); });
  var PHASE_NAMES = { wood: 'Wood', stone: 'Stone', iron: 'Iron', portal: 'Portal', nether: 'Nether', stronghold: 'Stronghold', end: 'The End' };
  var KEY_MILESTONES = ['iron-kit', 'portal-kit', 'portal', 'nether', 'fortress'].filter(function (m) { return CHAIN.indexOf(m) >= 0; });

  var S = {
    summary: null, gyms: null, agents: null, orch: null, days: {}, failed: {},
    tab: 'road', range: '2', trendKind: 'real', trendRange: '30', timeRange: '7',
    open: {}, hist: { day: null, kind: 'all', commit: '', reach: '', page: 0, open: null },
    prPage: 0, logAll: false
  };

  function esc(t) {
    return String(t == null ? '' : t).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function phaseVar(p) { return 'var(--' + (p || 'wood') + ')'; }
  function phaseOf(id) { var s = BY_ID[id]; return s ? s.phase : (id === 'fortress' || id === 'nether' ? 'nether' : 'wood'); }
  function pct(x) { return x == null || isNaN(x) ? '—' : (x > 0 && x < 0.01 ? '<1%' : Math.round(x * 100) + '%'); }
  function share(n, d) { return d > 0 ? n / d : null; }
  function clock(s) {
    if (s == null || isNaN(s)) return '—';
    s = Math.round(s);
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return h ? h + ':' + (m < 10 ? '0' : '') + m + ':' + (x < 10 ? '0' : '') + x : m + ':' + (x < 10 ? '0' : '') + x;
  }
  function compact(n) { return n >= 10000 ? Math.round(n / 1000) + 'k' : n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(Math.round(n)); }
  var fmtTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  var fmtClock = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
  var fmtDay = new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });
  var fmtShortDay = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' });
  var rel = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
  function ago(iso) {
    var d = new Date(iso); if (isNaN(d)) return '';
    var s = (d - Date.now()) / 1000, a = Math.abs(s);
    if (a < 90) return rel.format(Math.round(s), 'second');
    if (a < 5400) return rel.format(Math.round(s / 60), 'minute');
    if (a < 172800) return rel.format(Math.round(s / 3600), 'hour');
    return rel.format(Math.round(s / 86400), 'day');
  }
  function when(iso) { var d = new Date(iso); return isNaN(d) ? '' : '<time datetime="' + esc(iso) + '" title="' + esc(d.toString()) + '">' + esc(fmtTime.format(d)) + '</time>'; }
  function dayLabel(date) { return fmtDay.format(new Date(date + 'T00:00:00Z')); }
  function shortDay(date) { return fmtShortDay.format(new Date(date + 'T00:00:00Z')); }
  function addDays(date, n) { var d = new Date(date + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); }
  function span(a, b) { var x = new Date(a), y = new Date(b); return isNaN(x) || isNaN(y) ? '—' : clock((y - x) / 1000); }
  function issueLink(n, text) { return n ? '<a href="' + gh + '/issues/' + n + '">' + esc(text || ('#' + n)) + '</a>' : esc(text || ''); }
  function prLink(n) { return '<a href="' + gh + '/pull/' + n + '">#' + n + '</a>'; }
  function runLink(id, text) { return '<a href="' + gh + '/actions/runs/' + id + '">' + esc(text || 'run') + '</a>'; }
  function commitLink(sha) { return sha ? '<a href="' + gh + '/commit/' + esc(sha) + '"><code>' + esc(String(sha).slice(0, 7)) + '</code></a>' : '—'; }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } return null; }
  function median(xs) { var v = xs.slice().sort(function (a, b) { return a - b; }); var n = v.length; return n ? (n % 2 ? v[(n - 1) / 2] : (v[n / 2 - 1] + v[n / 2]) / 2) : null; }

  function getJSON(url, nocache) {
    return fetch(url, nocache ? { cache: 'no-store' } : {}).then(function (r) { if (!r.ok) throw new Error(url + ': ' + r.status); return r.json(); });
  }
  function getText(url) { return fetch(url).then(function (r) { if (!r.ok) throw new Error(url + ': ' + r.status); return r.text(); }); }
  function ndjson(t) { return t.split('\n').filter(function (l) { return l.trim(); }).map(function (l) { try { return JSON.parse(l); } catch (e) { return null; } }).filter(Boolean); }

  // Totals over days: the publishers keep each day's per kind; these add them up for a range.
  function sumTotals(list) {
    var t = { runs: 0, ok: 0, rounds: 0, deaths: 0, died: 0, deathRuns: 0, reached: {}, stops: {}, speed: {}, first: {}, fail: {}, issues: {}, commits: {} };
    list.forEach(function (x) {
      if (!x) return;
      t.runs += x.runs || 0; t.ok += x.ok || 0; t.rounds += x.rounds || 0;
      if (x.deaths != null) { t.deaths += x.deaths; t.died += x.died || 0; t.deathRuns += x['death-runs'] || 0; }
      Object.keys(x.reached || {}).forEach(function (m) { t.reached[m] = (t.reached[m] || 0) + x.reached[m]; });
      Object.keys(x.stops || {}).forEach(function (m) { t.stops[m] = (t.stops[m] || 0) + x.stops[m]; });
      Object.keys(x.speed || {}).forEach(function (m) {
        var a = x.speed[m], b = t.speed[m] || (t.speed[m] = { n: 0, fastest: Infinity, sum: 0 });
        b.n += a.n; b.fastest = Math.min(b.fastest, a.fastest); b.sum += a.n * a.median;
      });
      Object.keys(x.first || {}).forEach(function (m) { if (!t.first[m] || x.first[m] < t.first[m]) t.first[m] = x.first[m]; });
      (x.failures || []).forEach(function (f) { var k = f[0] + ' ' + f[1]; t.fail[k] = (t.fail[k] || 0) + f[2]; });
      Object.keys(x.issues || {}).forEach(function (k) { t.issues[k] = x.issues[k]; });
      (x.commits || []).forEach(function (c) { t.commits[c] = 1; });
    });
    Object.keys(t.speed).forEach(function (m) { var b = t.speed[m]; b.median = b.n ? b.sum / b.n : null; });
    t.failures = Object.keys(t.fail).map(function (k) { var p = k.split(' '); return [p[0], p[1], t.fail[k]]; })
      .sort(function (a, b) { return b[2] - a[2]; });
    return t;
  }
  function rangeDays(spec) {
    var days = (S.summary && S.summary.days) || [];
    if (!days.length || spec === 'all') return days;
    var last = days[days.length - 1].date, from = addDays(last, -(Number(spec) - 1));
    return days.filter(function (d) { return d.date >= from; });
  }
  function rangeText(spec) {
    var ds = rangeDays(spec);
    if (!ds.length) return 'no days yet';
    return ds.length === 1 ? dayLabel(ds[0].date) + ' (UTC)' : shortDay(ds[0].date) + ' – ' + shortDay(ds[ds.length - 1].date) + ' (UTC days)';
  }
  function openIssuesFor(stage) {
    if (!S.agents) return [];
    return S.agents.issues.filter(function (i) { return i.state === 'open' && i.stage === stage; });
  }
  function issueFor(totals, stage, reason) {
    var k = stage + ' ' + reason;
    if (totals && totals.issues && totals.issues[k]) return totals.issues[k];
    if (!S.agents) return null;
    var hit = S.agents.issues.filter(function (i) { return i.stage === stage && i.reason === reason; }).sort(function (a, b) { return b.n - a.n; })[0];
    return hit ? hit.n : null;
  }
  function gymsFor(goal) {
    var out = [];
    (S.gyms || []).forEach(function (g) {
      (g.goals || []).forEach(function (x) { if (x.goal === goal) out.push({ g: g, x: x }); });
    });
    return out.sort(function (a, b) { return String(b.g.at).localeCompare(String(a.g.at)); });
  }

  // ---------------------------------------------------------------- tooltip

  var tip = document.getElementById('tip');
  function showTip(html, x, y) {
    tip.innerHTML = html; tip.hidden = false;
    var w = tip.offsetWidth, h = tip.offsetHeight, vw = window.innerWidth, vh = window.innerHeight;
    var left = x + 14 + w > vw - 8 ? Math.max(8, x - w - 14) : x + 14;
    var top = Math.min(Math.max(8, y - h / 2), vh - h - 8);
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }
  function hideTip() { tip.hidden = true; }
  function tipRows(title, rows) {
    return '<b>' + esc(title) + '</b>' + rows.map(function (r) {
      return '<div class="tr"><span>' + (r[2] ? '<i class="key" style="background:' + r[2] + '"></i>' : '') + esc(r[0]) + '</span><span>' + esc(r[1]) + '</span></div>';
    }).join('');
  }
  document.addEventListener('scroll', hideTip, { passive: true });

  // ---------------------------------------------------------------- charts (inline SVG)

  function niceMax(v) {
    if (!(v > 0)) return 1;
    var p = Math.pow(10, Math.floor(Math.log10(v))), f = v / p;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
  }
  function widthOf(host) { return Math.max(260, Math.floor(host.clientWidth || host.parentNode.clientWidth || 600)); }
  function xLabels(labels, x, H, iw) {
    var n = labels.length, room = Math.max(1, Math.floor(iw / 64)), step = Math.max(1, Math.ceil(n / room)), out = '';
    for (var i = 0; i < n; i += step) out += '<text x="' + x(i) + '" y="' + (H - 6) + '" text-anchor="middle">' + esc(labels[i]) + '</text>';
    return out;
  }
  // gridlines at clean values: four steps, or five or two when four would split a count
  function yGrid(m, W, ih, ymax, y, fmt, whole) {
    var out = '', steps = 4;
    if (whole) steps = [4, 5, 2, 1].filter(function (k) { return ymax % k === 0; })[0];
    for (var k = 0; k <= steps; k++) {
      var v = ymax * k / steps, yy = y(v);
      out += '<line class="' + (k ? 'grid' : 'axis') + '" x1="' + m.l + '" x2="' + (W - m.r) + '" y1="' + yy + '" y2="' + yy + '"/>';
      out += '<text x="' + (m.l - 6) + '" y="' + (yy + 4) + '" text-anchor="end">' + esc(fmt(v)) + '</text>';
    }
    return out;
  }
  // index-hover over a band chart: the pointer's x picks a band, onIndex draws its highlight
  function hover(svg, n, bandAt, onIndex) {
    function at(ev) {
      var r = svg.getBoundingClientRect(), px = (ev.touches ? ev.touches[0].clientX : ev.clientX) - r.left;
      var i = bandAt(px * (svg.viewBox.baseVal.width / r.width));
      if (i < 0 || i >= n) { onIndex(null); hideTip(); return; }
      onIndex(i, ev.touches ? ev.touches[0] : ev);
    }
    svg.addEventListener('pointermove', at);
    svg.addEventListener('pointerdown', at);
    svg.addEventListener('pointerleave', function () { onIndex(null); hideTip(); });
  }
  function legend(items) {
    return '<div class="legend">' + items.map(function (it) {
      return '<span><i class="' + (it.box ? 'box' : '') + '" style="background:' + it.color + '"></i>' + esc(it.name) + '</span>';
    }).join('') + '</div>';
  }

  // lines over days: series [{name color values}], values null where a day has none
  function lineChart(host, o) {
    var W = widthOf(host), H = o.height || 220, m = { l: 42, r: o.endLabels ? 74 : 14, t: 10, b: 24 };
    var n = o.labels.length, iw = W - m.l - m.r, ih = H - m.t - m.b;
    var x = function (i) { return m.l + (n <= 1 ? iw / 2 : i * iw / (n - 1)); };
    var all = []; o.series.forEach(function (s) { s.values.forEach(function (v) { if (v != null) all.push(v); }); });
    var ymax = o.yMax || niceMax(Math.max.apply(null, all.concat([0])));
    var y = function (v) { return m.t + ih - (v / ymax) * ih; };
    var g = yGrid(m, W, ih, ymax, y, o.yFmt || compact) + xLabels(o.labels, x, H, iw);
    var ends = [];
    o.series.forEach(function (s) {
      var d = '', pen = false, last = null;
      s.values.forEach(function (v, i) {
        if (v == null) { pen = false; return; }
        d += (pen ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(v).toFixed(1) + ' '; pen = true; last = i;
        var lone = (i === 0 || s.values[i - 1] == null) && (i === n - 1 || s.values[i + 1] == null);
        if (lone) g += '<circle cx="' + x(i) + '" cy="' + y(v) + '" r="3" style="fill:' + s.color + '"/>';
      });
      g += '<path d="' + d + '" fill="none" style="stroke:' + s.color + '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>';
      if (o.endLabels && last != null) ends.push({ y: y(s.values[last]), x: x(last), name: s.name, color: s.color });
    });
    ends.sort(function (a, b) { return a.y - b.y; });
    var prev = -99;
    ends.forEach(function (e) {
      var yy = Math.max(e.y + 4, prev + 12); prev = yy;
      g += '<text class="lab" x="' + (W - m.r + 6) + '" y="' + yy + '">' + esc(e.name) + '</text>';
    });
    g += '<line class="cross" id="cx" x1="0" x2="0" y1="' + m.t + '" y2="' + (m.t + ih) + '" visibility="hidden"/><g id="cd"></g>';
    host.innerHTML = '<svg class="plot" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">' + g + '</svg>';
    var svg = host.firstChild, cx = svg.querySelector('#cx'), cd = svg.querySelector('#cd');
    hover(svg, n, function (px) { return n <= 1 ? 0 : Math.round((px - m.l) / (iw / (n - 1))); }, function (i, ev) {
      if (i == null) { cx.setAttribute('visibility', 'hidden'); cd.innerHTML = ''; return; }
      cx.setAttribute('x1', x(i)); cx.setAttribute('x2', x(i)); cx.setAttribute('visibility', 'visible');
      cd.innerHTML = o.series.map(function (s) {
        var v = s.values[i]; return v == null ? '' : '<circle cx="' + x(i) + '" cy="' + y(v) + '" r="4.5" style="fill:' + s.color + ';stroke:var(--card)" stroke-width="2"/>';
      }).join('');
      showTip(o.tip(i), ev.clientX, ev.clientY);
    });
  }

  // columns over days: stacked shares (o.stacked) or grouped counts; keys [{name color}], rows[i][k]
  function columnChart(host, o) {
    var W = widthOf(host), H = o.height || 220, m = { l: 42, r: 14, t: 10, b: 24 };
    var n = o.labels.length, iw = W - m.l - m.r, ih = H - m.t - m.b, band = iw / Math.max(1, n);
    var k = o.keys.length, totals = o.rows.map(function (r) { return r.reduce(function (a, b) { return a + (b || 0); }, 0); });
    var ymax = o.stacked ? 1 : niceMax(Math.max.apply(null, o.rows.map(function (r) { return Math.max.apply(null, r.map(function (v) { return v || 0; })); }).concat([1])));
    var y = function (v) { return m.t + ih - (v / ymax) * ih; };
    var x = function (i) { return m.l + band * i + band / 2; };
    var g = yGrid(m, W, ih, ymax, y, o.stacked ? function (v) { return Math.round(v * 100) + '%'; } : compact, !o.stacked) + xLabels(o.labels, x, H, iw);
    var bw = o.stacked ? Math.min(24, band * 0.72) : Math.min(14, (band * 0.8) / k);
    o.rows.forEach(function (r, i) {
      if (o.stacked) {
        if (!totals[i]) return;
        var acc = 0;
        r.forEach(function (v, j) {
          if (!v) return;
          var y0 = y(acc / totals[i]), y1 = y((acc + v) / totals[i]); acc += v;
          var h = Math.max(0, y0 - y1 - (acc < totals[i] ? 2 : 0));
          g += '<rect x="' + (x(i) - bw / 2) + '" y="' + y1 + '" width="' + bw + '" height="' + h + '" rx="' + (acc >= totals[i] ? 3 : 0) + '" style="fill:' + o.keys[j].color + '"/>';
        });
      } else {
        r.forEach(function (v, j) {
          if (!v) return;
          var x0 = x(i) - (bw * k + 2 * (k - 1)) / 2 + j * (bw + 2), h = y(0) - y(v);
          g += '<path d="M' + x0 + ' ' + y(0) + 'V' + (y(v) + Math.min(3, h)) + 'q0 -3 3 -3h' + Math.max(0, bw - 6) + 'q3 0 3 3V' + y(0) + 'Z" style="fill:' + o.keys[j].color + '"/>';
        });
      }
    });
    g += '<rect id="hl" class="hit" x="0" y="' + m.t + '" width="' + band + '" height="' + ih + '" visibility="hidden" style="fill:var(--wash)"/>';
    host.innerHTML = '<svg class="plot" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">' + g + '</svg>';
    var svg = host.firstChild, hl = svg.querySelector('#hl');
    svg.insertBefore(hl, svg.firstChild);
    hover(svg, n, function (px) { return Math.floor((px - m.l) / band); }, function (i, ev) {
      if (i == null) { hl.setAttribute('visibility', 'hidden'); return; }
      hl.setAttribute('x', m.l + band * i); hl.setAttribute('visibility', 'visible');
      showTip(o.tip(i), ev.clientX, ev.clientY);
    });
  }

  // fastest-to-median per milestone, real and sim, on a log time axis
  function timeChart(host, o) {
    var W = widthOf(host), rowH = 30, lab = Math.min(118, W * 0.3), m = { l: lab, r: 16, t: 8, b: 24 };
    var H = m.t + m.b + rowH * o.rows.length, iw = W - m.l - m.r;
    var lo = Math.log(5), hi = Math.log(Math.max(600, o.max * 1.15));
    var x = function (s) { return m.l + (Math.log(Math.max(5, s)) - lo) / (hi - lo) * iw; };
    var ticks = [[10, '10 s'], [60, '1 min'], [600, '10 min'], [3600, '1 h'], [3 * 3600, '3 h'], [10 * 3600, '10 h']];
    var g = '';
    ticks.forEach(function (t) {
      if (Math.log(t[0]) > hi) return;
      g += '<line class="grid" x1="' + x(t[0]) + '" x2="' + x(t[0]) + '" y1="' + m.t + '" y2="' + (H - m.b) + '"/><text x="' + x(t[0]) + '" y="' + (H - 6) + '" text-anchor="middle">' + t[1] + '</text>';
    });
    o.rows.forEach(function (r, i) {
      var y0 = m.t + i * rowH;
      g += '<text class="lab" x="' + (m.l - 8) + '" y="' + (y0 + rowH / 2 + 4) + '" text-anchor="end">' + esc(r.name) + '</text>';
      r.tracks.forEach(function (t, j) {
        if (!t || t.median == null) return;
        var yy = y0 + 9 + j * 12;
        g += '<line x1="' + x(t.fastest) + '" x2="' + x(t.median) + '" y1="' + yy + '" y2="' + yy + '" style="stroke:' + t.color + '" stroke-width="2" stroke-linecap="round" opacity="0.55"/>';
        g += '<circle cx="' + x(t.fastest) + '" cy="' + yy + '" r="3.5" style="fill:var(--card);stroke:' + t.color + '" stroke-width="2"/>';
        g += '<circle cx="' + x(t.median) + '" cy="' + yy + '" r="4.5" style="fill:' + t.color + ';stroke:var(--card)" stroke-width="2"/>';
      });
    });
    g += '<rect id="hl" x="0" y="0" width="' + W + '" height="' + rowH + '" visibility="hidden" style="fill:var(--wash)"/>';
    host.innerHTML = '<svg class="plot" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">' + g + '</svg>';
    var svg = host.firstChild, hl = svg.querySelector('#hl');
    svg.insertBefore(hl, svg.firstChild);
    function rowAt(ev) {
      var r = svg.getBoundingClientRect(), py = ((ev.touches ? ev.touches[0].clientY : ev.clientY) - r.top) * (H / r.height);
      return Math.floor((py - m.t) / rowH);
    }
    function on(ev) {
      var i = rowAt(ev); if (i < 0 || i >= o.rows.length) { hl.setAttribute('visibility', 'hidden'); hideTip(); return; }
      hl.setAttribute('y', m.t + i * rowH); hl.setAttribute('visibility', 'visible');
      var p = ev.touches ? ev.touches[0] : ev; showTip(o.tip(i), p.clientX, p.clientY);
    }
    svg.addEventListener('pointermove', on); svg.addEventListener('pointerdown', on);
    svg.addEventListener('pointerleave', function () { hl.setAttribute('visibility', 'hidden'); hideTip(); });
  }

  // horizontal bars: items [{label value color tip}]
  function barList(host, items, fmt) {
    var W = widthOf(host), rowH = 24, lab = Math.min(140, W * 0.36), m = { l: lab, r: 46, t: 4, b: 4 };
    var H = m.t + m.b + rowH * items.length, iw = W - m.l - m.r;
    var max = Math.max.apply(null, items.map(function (i) { return i.value; }).concat([1]));
    var g = '';
    items.forEach(function (it, i) {
      var y0 = m.t + i * rowH, w = Math.max(it.value ? 2 : 0, it.value / max * iw);
      g += '<rect class="hit" x="0" y="' + y0 + '" width="' + W + '" height="' + rowH + '" data-i="' + i + '"/>';
      g += '<text class="lab" x="' + (m.l - 8) + '" y="' + (y0 + 16) + '" text-anchor="end">' + esc(it.label) + '</text>';
      if (w) g += '<path d="M' + m.l + ' ' + (y0 + 5) + 'h' + Math.max(0, w - 4) + 'q4 0 4 4v6q0 4 -4 4h-' + Math.max(0, w - 4) + 'Z" style="fill:' + it.color + '" pointer-events="none"/>';
      g += '<text x="' + (m.l + w + 6) + '" y="' + (y0 + 16) + '" pointer-events="none">' + esc((fmt || String)(it.value)) + '</text>';
    });
    host.innerHTML = '<svg class="plot" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '">' + g + '</svg>';
    var svg = host.firstChild;
    function on(ev) { var t = ev.target.getAttribute && ev.target.getAttribute('data-i'); if (t == null) { hideTip(); return; } showTip(items[+t].tip, ev.clientX, ev.clientY); }
    svg.addEventListener('pointermove', on); svg.addEventListener('pointerdown', on); svg.addEventListener('pointerleave', hideTip);
  }

  // a stage's reach over the last days, real and sim, as a sparkline
  function spark(id, days) {
    var W = 112, H = 30, n = days.length;
    if (!n) return '';
    var x = function (i) { return 2 + (n <= 1 ? (W - 4) / 2 : i * (W - 4) / (n - 1)); };
    var y = function (v) { return H - 3 - v * (H - 6); };
    var out = '<line class="axis" x1="0" x2="' + W + '" y1="' + (H - 3) + '" y2="' + (H - 3) + '"/>';
    ['sim', 'real'].forEach(function (k) {
      var d = '', pen = false, tips = [];
      days.forEach(function (day, i) {
        var t = day[k], v = t && t.runs ? (t.reached[id] || 0) / t.runs : null;
        if (v == null) { pen = false; return; }
        d += (pen ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(v).toFixed(1); pen = true;
        tips.push(v);
      });
      if (d) out += '<path d="' + d + '" fill="none" style="stroke:var(--' + k + ')" stroke-width="1.6" stroke-linejoin="round"/>';
    });
    var title = days.map(function (day) {
      var r = day.real, s = day.sim;
      return shortDay(day.date) + ': real ' + (r && r.runs ? pct((r.reached[id] || 0) / r.runs) : '—') + ', sim ' + (s && s.runs ? pct((s.reached[id] || 0) / s.runs) : '—');
    }).join('\n');
    return '<svg class="spark" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="reach over the last ' + n + ' days"><title>' + esc(title) + '</title>' + out + '</svg>';
  }

  function funnelMini(row) {
    var W = Math.max(60, CHAIN.length * 6), H = 22, bw = W / CHAIN.length;
    return '<svg class="funnel-mini" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '" aria-hidden="true">' + CHAIN.map(function (m, i) {
      var v = row.runs ? (row.reached[m] || 0) / row.runs : 0, h = Math.max(v > 0 ? 2 : 0, v * (H - 2));
      return '<rect x="' + (i * bw + 0.5) + '" y="' + (H - h) + '" width="' + Math.max(1, bw - 1.5) + '" height="' + h + '" rx="1" style="fill:' + phaseVar(phaseOf(m)) + '"/>' +
        (v ? '' : '<rect x="' + (i * bw + 0.5) + '" y="' + (H - 1) + '" width="' + Math.max(1, bw - 1.5) + '" height="1" style="fill:var(--axis)"/>');
    }).join('') + '</svg>';
  }

  // ---------------------------------------------------------------- the Road tab

  function seg(name, value, options) {
    return '<span class="seg" role="group" data-ctl="' + name + '">' + options.map(function (o) {
      return '<button type="button" data-v="' + esc(o[0]) + '" aria-pressed="' + (String(value) === String(o[0])) + '">' + esc(o[1]) + '</button>';
    }).join('') + '</span>';
  }

  // the wall: the stage where most of the window's runs stopped, on the real servers when any
  // real run stopped (the model is the stand-in), else in the model
  function wallOf(real, sim) {
    function top(t) {
      var best = null, total = 0;
      Object.keys(t.stops).forEach(function (s) { total += t.stops[s]; if (!best || t.stops[s] > t.stops[best]) best = s; });
      return best ? { stage: best, n: t.stops[best], total: total } : null;
    }
    var r = top(real), s = top(sim), w = r || s;
    return w ? { stage: w.stage, n: w.n, total: w.total, kind: r ? 'real' : 'sim', t: r ? real : sim, other: r ? s : null } : null;
  }

  function reachTile(label, id, real, sim) {
    function line(k, t) {
      var n = t.reached[id] || 0;
      return '<span><i class="key ' + k + '"></i>' + k + '</span><span><div class="meter ' + k + '"><span style="width:' + (t.runs ? (100 * n / t.runs).toFixed(1) : 0) + '%"></span></div></span><span class="num">' + pct(share(n, t.runs)) + ' <span class="muted">' + n + '/' + t.runs + '</span></span>';
    }
    return '<div class="tile"><div class="label">' + esc(label) + '</div><div class="pair">' + line('real', real) + line('sim', sim) + '</div></div>';
  }

  function renderRoad(host) {
    if (!S.summary) { host.innerHTML = S.failed.summary ? '<p class="error">The data could not be loaded (' + esc(S.failed.summary) + ').</p>' : '<p class="loading">Loading the rounds…</p>'; return; }
    var days = rangeDays(S.range), all = S.summary.days;
    var real = sumTotals(days.map(function (d) { return d.real; })), sim = sumTotals(days.map(function (d) { return d.sim; }));
    var everR = sumTotals(all.map(function (d) { return d.real; })), everS = sumTotals(all.map(function (d) { return d.sim; }));
    var wall = wallOf(real, sim);
    var furthest = function (t) { var f = null; CHAIN.forEach(function (m) { if (t.reached[m]) f = m; }); return f; };
    var fR = furthest(everR), fS = furthest(everS);
    var gymBest = null;
    STAGES.forEach(function (s) { if (!s.race && gymsFor(s.id).some(function (e) { return e.x.pass > 0; })) gymBest = s.id; });
    var recent = all.slice(-14);
    var h = '';
    h += '<div class="controls"><span class="ctl-label">Window</span>' + seg('range', S.range, [['1', 'Latest day'], ['2', '2 days'], ['7', '7 days'], ['30', '30 days'], ['all', 'All']]) +
      '<span class="muted">' + esc(rangeText(S.range)) + ' · ' + compact(real.runs) + ' real runs, ' + compact(sim.runs) + ' sim runs in ' + (real.rounds + sim.rounds) + ' lane runs</span></div>';
    h += '<div class="tiles">';
    h += '<div class="tile"><div class="label">Furthest ever</div><div class="value">' + esc(fR || '—') + ' <small>real</small></div>' +
      '<div class="foot">' + (fR ? (everR.reached[fR] || 0) + ' real runs reached it, first ' + (everR.first[fR] ? when(everR.first[fR]) : 'in a round') : 'no real run yet') + '</div>' +
      '<div class="foot">Sim: <b>' + esc(fS || '—') + '</b>' + (fS ? ' (' + (everS.reached[fS] || 0) + ' runs)' : '') + (gymBest ? ' · gym-proven up to <b>' + esc(gymBest) + '</b>' : '') + '</div></div>';
    h += reachTile('Reached the Nether', 'nether', real, sim);
    if (ROAD.end && ROAD.end !== 'nether') h += reachTile('Reached the ' + ROAD.end + ' (the race’s target)', ROAD.end, real, sim);
    if (wall) {
      var top = wall.t.failures.filter(function (f) { return f[0] === wall.stage; }).slice(0, 3);
      h += '<div class="tile wall"><div class="label">The wall: where most ' + wall.kind + ' runs stop</div><div class="value">' + esc(wall.stage) + ' <small>' + pct(wall.n / wall.total) + ' of ' + wall.kind + ' stops</small></div>' +
        '<div class="foot">' + wall.n + ' runs stopped on the way: ' + top.map(function (f) {
          return issueLink(issueFor(wall.t, f[0], f[1]), f[1]) + ' ' + f[2];
        }).join(', ') + '</div>' +
        (wall.other ? '<div class="foot">In the model: <b>' + esc(wall.other.stage) + '</b> (' + pct(wall.other.n / wall.other.total) + ' of sim stops)</div>' : '') + '</div>';
    }
    h += '</div>';
    h += '<h2>The road to the dragon</h2><p class="lede">Every stage from spawn to the win, in the order the game forces. The race plays the first ' + CHAIN.length +
      ' (up to <b>' + esc(ROAD.end) + '</b>): each bar is the share of the window’s runs that got there, real servers <i class="key real"></i>and the server model <i class="key sim"></i>; the line on the right is that share day by day for the last ' + recent.length +
      ' days. Past the race, a stage is proven only in a gym, or not built yet. Tap a stage for its stops, times and gym runs. (The model\u2019s Nether is a single column, so no sim run finds a fortress.)</p>';
    var phase = null;
    h += '<div>';
    STAGES.forEach(function (s, i) {
      if (s.phase !== phase) { if (phase) h += '</ol>'; phase = s.phase; h += '<div class="phase-head"><span class="swatch" style="background:' + phaseVar(s.phase) + '"></span>' + esc(PHASE_NAMES[s.phase] || s.phase) + '</div><ol class="road">'; }
      h += stageRow(s, i, real, sim, everR, everS, wall, recent);
    });
    h += '</ol></div>';
    host.innerHTML = h;
  }

  function stageRow(s, i, real, sim, everR, everS, wall, recent) {
    var gyms = gymsFor(s.id), passed = gyms.filter(function (e) { return e.x.pass > 0; });
    var reachedR = real.reached[s.id] || 0, reachedS = sim.reached[s.id] || 0;
    var ever = (everR.reached[s.id] || 0) + (everS.reached[s.id] || 0);
    var badge, cls = '';
    if (wall && wall.stage === s.id) badge = '<span class="badge wall">the wall</span>';
    else if (s.race && (reachedR || reachedS)) badge = '<span class="badge ok">✓ reached</span>';
    else if (s.race && ever) badge = '<span class="badge no">reached before this window</span>';
    else if (s.race) badge = '<span class="badge no">not reached yet</span>';
    else if (passed.length) badge = '<span class="badge gym">proven in a gym</span>';
    else if (gyms.length) badge = '<span class="badge no">gym: no pass yet</span>';
    else if (s.goal) badge = '<span class="badge no">built, not tried</span>';
    else { badge = '<span class="badge no">not built yet</span>'; cls = ' unbuilt'; }
    if (wall && wall.stage === s.id) cls += ' wall';
    var h = '<li class="stage' + cls + '" style="--ph:' + phaseVar(s.phase) + '" data-stage="' + esc(s.id) + '"><div class="row" role="button" tabindex="0" aria-expanded="' + !!S.open[s.id] + '">';
    h += '<div class="n">' + (i + 1 < 10 ? '0' : '') + (i + 1) + '</div><div>';
    h += '<div><span class="name">' + esc(s.id) + '</span> <span class="says">' + esc(s.says) + '</span>' + badge + '</div>';
    if (s.race) {
      h += '<div class="meters">';
      [['real', real, reachedR], ['sim', sim, reachedS]].forEach(function (k) {
        var t = k[1];
        h += '<span class="muted">' + k[0] + '</span><div class="meter ' + k[0] + '"><span style="width:' + (t.runs ? (100 * k[2] / t.runs).toFixed(1) : 0) + '%"></span></div><span class="num">' + pct(share(k[2], t.runs)) + ' <span class="muted">' + k[2] + '/' + t.runs + '</span></span>';
      });
      h += '</div>';
      var facts = [];
      var sp = real.speed[s.id] || sim.speed[s.id];
      if (sp) facts.push('median ' + clock(sp.median) + ' from setting off' + (real.speed[s.id] ? '' : ' (sim)') + ', fastest ' + clock(sp.fastest));
      var stops = (real.stops[s.id] || 0) + (sim.stops[s.id] || 0);
      if (stops) facts.push(stops + ' stopped on the way here');
      var first = everR.first[s.id];
      if (first) facts.push('first reached ' + when(first));
      if (facts.length) h += '<div class="facts">' + facts.map(function (f) { return '<span>' + f + '</span>'; }).join('') + '</div>';
    } else if (gyms.length) {
      var latest = gyms[0];
      h += '<div class="facts"><span>latest gym: ' + latest.x.pass + '/' + latest.x.n + ' passed' + (latest.g.rows && latest.g.rows.length ? ' on ' + esc(latest.g.rows.join(', ')) : '') + ', ' + when(latest.g.at) + ' (' + runLink(latest.g.run, 'run') + ')</span>' +
        (passed.length ? '<span>' + passed.length + ' of ' + gyms.length + ' gym runs had a pass</span>' : '') + '</div>';
    }
    var chips = openIssuesFor(s.id).map(function (it) { return '<a class="chip bad" href="' + gh + '/issues/' + it.n + '">#' + it.n + ' ' + esc(it.reason || '') + '</a>'; });
    if (s.issue) chips.push('<a class="chip" href="' + gh + '/issues/' + s.issue + '">roadmap #' + s.issue + '</a>');
    if (chips.length) h += '<div class="chips">' + chips.join('') + '</div>';
    h += '</div><div class="spark-cell">' + (s.race ? spark(s.id, recent) : '') + '</div></div>';
    if (S.open[s.id]) h += '<div class="detail">' + stageDetail(s, real, sim, gyms) + '</div>';
    return h + '</li>';
  }

  function stageDetail(s, real, sim, gyms) {
    var h = '';
    if (s.race) {
      var rows = [];
      [['real', real], ['sim', sim]].forEach(function (k) {
        k[1].failures.forEach(function (f) { if (f[0] === s.id) rows.push([k[0], f[1], f[2], issueFor(k[1], f[0], f[1])]); });
      });
      rows.sort(function (a, b) { return b[2] - a[2]; });
      h += '<h4>Why runs stopped on the way to ' + esc(s.id) + ' (this window)</h4>';
      h += rows.length ? '<div class="scroll"><table><thead><tr><th>kind</th><th>reason</th><th class="r">runs</th><th>issue</th></tr></thead><tbody>' + rows.map(function (r) {
        return '<tr><td><span class="tag ' + r[0] + '">' + r[0] + '</span></td><td>' + esc(r[1]) + '</td><td class="r num">' + r[2] + '</td><td>' + (r[3] ? issueLink(r[3]) : '<span class="muted">not filed</span>') + '</td></tr>';
      }).join('') + '</tbody></table></div>' : '<p class="muted">None stopped here in this window.</p>';
      h += '<h4>Time from setting off (this window)</h4><div class="scroll"><table><thead><tr><th>kind</th><th class="r">reached</th><th class="r">median</th><th class="r">fastest</th><th class="r">timed</th></tr></thead><tbody>' +
        [['real', real], ['sim', sim]].map(function (k) {
          var sp = k[1].speed[s.id];
          return '<tr><td><span class="tag ' + k[0] + '">' + k[0] + '</span></td><td class="r num">' + (k[1].reached[s.id] || 0) + '/' + k[1].runs + '</td><td class="r num">' + clock(sp && sp.median) + '</td><td class="r num">' + clock(sp && sp.fastest) + '</td><td class="r num">' + (sp ? sp.n : 0) + '</td></tr>';
        }).join('') + '</tbody></table></div>';
    }
    h += '<h4>Gym runs of this stage</h4>';
    h += gyms.length ? '<div class="scroll"><table><thead><tr><th>when</th><th>rows</th><th class="r">passed</th><th>95% interval</th><th>outcomes</th><th>branch</th><th></th></tr></thead><tbody>' + gyms.slice(0, 12).map(function (e) {
      var o = e.x.outcomes || {};
      return '<tr><td class="time">' + when(e.g.at) + '</td><td>' + esc((e.g.rows || []).join(', ') || e.g.label || '') + '</td><td class="r num">' + e.x.pass + '/' + e.x.n + '</td><td class="num">' + (e.x.lo != null ? e.x.lo + '–' + e.x.hi + '%' : '—') + '</td><td>' + esc(Object.keys(o).map(function (k) { return k + ' ' + o[k]; }).join(', ')) + '</td><td><code>' + esc(e.g.branch || '') + '</code></td><td>' + runLink(e.g.run) + '</td></tr>';
    }).join('') + '</tbody></table></div>' : '<p class="muted">No gym run of this stage on record' + (S.gyms ? '' : ' (the gym record has not loaded)') + '.</p>';
    if (s.issue) h += '<p>Roadmap: ' + issueLink(s.issue, '#' + s.issue) + '</p>';
    return h;
  }

  // ---------------------------------------------------------------- the Trends tab

  function renderTrends(host) {
    if (!S.summary) { renderRoad(host); return; }
    var days = rangeDays(S.trendRange), labels = days.map(function (d) { return shortDay(d.date); });
    var k = S.trendKind;
    var h = '<div class="controls"><span class="ctl-label">Kind</span>' + seg('trendKind', k, [['real', 'Real servers'], ['sim', 'Server model']]) +
      '<span class="ctl-label">Days</span>' + seg('trendRange', S.trendRange, [['14', '14'], ['30', '30'], ['90', '90'], ['all', 'All']]) + '</div>';
    h += '<div class="charts">';
    h += '<div class="chart"><h3>How far runs get</h3><p class="sub">Share of each day’s ' + (k === 'real' ? 'real' : 'sim') + ' runs that reached each milestone (by the UTC day their lane started).</p><div id="c-reach"></div></div>';
    h += '<div class="chart"><h3>Where runs stop</h3><p class="sub">Each day’s ' + k + ' failures by the phase of the stage the bot was working on, as a share of that day’s stops.</p><div id="c-stops"></div></div>';
    h += '<div class="chart"><h3>Time to each milestone</h3><p class="sub">From setting off, over the last ' + seg('timeRange', S.timeRange, [['1', '1 day'], ['7', '7 days'], ['30', '30 days']]) + ': ● median, ○ fastest. Real <i class="key real"></i> above, sim <i class="key sim"></i> below. Log scale.</p><div id="c-time"></div></div>';
    h += '<div class="chart"><h3>Runs a day</h3><p class="sub">Runs that ended, real and sim, each day.</p><div id="c-runs"></div></div>';
    h += '<div class="chart"><h3>Deaths per run</h3><p class="sub">Deaths over runs, each day (counted since the publishers began to keep them).</p><div id="c-deaths"></div></div>';
    h += '<div class="chart"><h3>Issues opened and closed</h3><p class="sub">Bot-failure issues a day: opened, closed as fixed, closed with no reproducible snapshot.</p><div id="c-issues"></div></div>';
    h += '</div>';
    host.innerHTML = h;

    var ramp = ['var(--r1)', 'var(--r2)', 'var(--r3)', 'var(--r4)', 'var(--r5)'];
    var series = KEY_MILESTONES.map(function (mm, i) {
      return { name: mm, color: ramp[Math.round(i * 4 / Math.max(1, KEY_MILESTONES.length - 1))], values: days.map(function (d) { var t = d[k]; return t && t.runs ? (t.reached[mm] || 0) / t.runs : null; }) };
    });
    var reach = document.getElementById('c-reach');
    reach.insertAdjacentHTML('beforebegin', legend(series.map(function (s) { return { name: s.name, color: s.color }; })));
    lineChart(reach, {
      labels: labels, series: series, yMax: 1, endLabels: true, yFmt: function (v) { return Math.round(v * 100) + '%'; },
      tip: function (i) { var t = days[i][k]; return tipRows(dayLabel(days[i].date) + ' · ' + (t ? t.runs : 0) + ' ' + k + ' runs', series.map(function (s) { return [s.name, t && t.runs ? pct(s.values[i]) + ' (' + (t.reached[s.name] || 0) + ')' : '—', s.color]; })); }
    });

    var race = PHASES.filter(function (p) { return CHAIN.some(function (m) { return phaseOf(m) === p; }); });
    var keys = race.map(function (p) { return { name: PHASE_NAMES[p] || p, color: phaseVar(p), phase: p }; });
    var stops = document.getElementById('c-stops');
    stops.insertAdjacentHTML('beforebegin', legend(keys.map(function (x) { return { name: x.name, color: x.color, box: true }; })));
    columnChart(stops, {
      labels: labels, keys: keys, stacked: true,
      rows: days.map(function (d) { var t = d[k]; return keys.map(function (x) { return t ? CHAIN.filter(function (m) { return phaseOf(m) === x.phase; }).reduce(function (a, m) { return a + ((t.stops || {})[m] || 0); }, 0) : 0; }); }),
      tip: function (i) {
        var t = days[i][k] || { stops: {} }, total = Object.keys(t.stops || {}).reduce(function (a, m) { return a + t.stops[m]; }, 0);
        var rows = CHAIN.filter(function (m) { return (t.stops || {})[m]; }).map(function (m) { return [m, t.stops[m] + ' (' + pct(t.stops[m] / total) + ')', phaseVar(phaseOf(m))]; });
        return tipRows(dayLabel(days[i].date) + ' · ' + total + ' stops', rows.length ? rows : [['no stops', '', '']]);
      }
    });

    var tdays = rangeDays(S.timeRange), tr = sumTotals(tdays.map(function (d) { return d.real; })), ts = sumTotals(tdays.map(function (d) { return d.sim; }));
    var max = 0, trows = CHAIN.filter(function (m) { return tr.speed[m] || ts.speed[m]; }).map(function (m) {
      var a = tr.speed[m], b = ts.speed[m];
      [a, b].forEach(function (z) { if (z) max = Math.max(max, z.median, z.fastest); });
      return { name: m, tracks: [a && { median: a.median, fastest: a.fastest, color: 'var(--real)' }, b && { median: b.median, fastest: b.fastest, color: 'var(--sim)' }], a: a, b: b };
    });
    var time = document.getElementById('c-time');
    if (trows.length) {
      timeChart(time, {
        rows: trows, max: max,
        tip: function (i) {
          var r = trows[i];
          return tipRows(r.name, [['real median', r.a ? clock(r.a.median) : '—', 'var(--real)'], ['real fastest', r.a ? clock(r.a.fastest) : '—', ''], ['real timed', r.a ? String(r.a.n) : '0', ''],
            ['sim median', r.b ? clock(r.b.median) : '—', 'var(--sim)'], ['sim fastest', r.b ? clock(r.b.fastest) : '—', ''], ['sim timed', r.b ? String(r.b.n) : '0', '']]);
        }
      });
    } else time.innerHTML = '<p class="muted">No times in this window.</p>';

    var kinds = [{ name: 'real', color: 'var(--real)' }, { name: 'sim', color: 'var(--sim)' }];
    var runs = document.getElementById('c-runs');
    runs.insertAdjacentHTML('beforebegin', legend(kinds.map(function (x) { return { name: x.name, color: x.color, box: true }; })));
    columnChart(runs, {
      labels: labels, keys: kinds,
      rows: days.map(function (d) { return [d.real ? d.real.runs : 0, d.sim ? d.sim.runs : 0]; }),
      tip: function (i) { var d = days[i]; return tipRows(dayLabel(d.date), [['real runs', d.real ? d.real.runs : 0, 'var(--real)'], ['real lane runs', d.real ? d.real.rounds : 0, ''], ['sim runs', d.sim ? d.sim.runs : 0, 'var(--sim)'], ['sim lane runs', d.sim ? d.sim.rounds : 0, '']]); }
    });

    var dd = document.getElementById('c-deaths');
    var dser = kinds.map(function (x) { return { name: x.name, color: x.color, values: days.map(function (d) { var t = d[x.name]; return t && t['death-runs'] ? t.deaths / t['death-runs'] : null; }) }; });
    if (dser.some(function (s) { return s.values.some(function (v) { return v != null; }); })) {
      dd.insertAdjacentHTML('beforebegin', legend(kinds));
      lineChart(dd, {
        labels: labels, series: dser, endLabels: true, yFmt: function (v) { return v.toFixed(v < 1 ? 2 : 1); },
        tip: function (i) { var d = days[i]; return tipRows(dayLabel(d.date), kinds.map(function (x) { var t = d[x.name]; return [x.name, t && t['death-runs'] ? (t.deaths / t['death-runs']).toFixed(2) + ' (' + t.deaths + ' in ' + t['death-runs'] + ', ' + t.died + ' runs died)' : '—', x.color]; })); }
      });
    } else dd.innerHTML = '<p class="muted">No deaths counted yet: the publishers keep them from this version on.</p>';

    issuesChart(document.getElementById('c-issues'), 21);
  }

  function issuesChart(host, n) {
    if (!S.agents) { host.innerHTML = '<p class="muted">' + (S.failed.agents ? 'The agents’ report could not be loaded.' : 'Loading…') + '</p>'; return; }
    var today = new Date().toISOString().slice(0, 10), dates = [];
    var first = S.agents.issues.reduce(function (a, it) { var o = String(it.opened || '').slice(0, 10); return o && o < a ? o : a; }, today);
    var span = Math.round((new Date(today) - new Date(first)) / 86400000) + 1;
    n = Math.max(7, Math.min(n, span));
    for (var i = n - 1; i >= 0; i--) dates.push(addDays(today, -i));
    var idx = {}; dates.forEach(function (d, i) { idx[d] = i; });
    var rows = dates.map(function () { return [0, 0, 0]; });
    S.agents.issues.forEach(function (it) {
      var o = String(it.opened || '').slice(0, 10), c = String(it.closed || '').slice(0, 10);
      if (idx[o] != null) rows[idx[o]][0]++;
      if (it.closed && idx[c] != null) rows[idx[c]][it.why === 'completed' ? 1 : 2]++;
    });
    var keys = [{ name: 'opened', color: 'var(--nether)' }, { name: 'closed: fixed', color: 'var(--stronghold)' }, { name: 'closed: no snapshot', color: 'var(--axis)' }];
    host.insertAdjacentHTML('beforebegin', legend(keys.map(function (x) { return { name: x.name, color: x.color, box: true }; })));
    columnChart(host, {
      labels: dates.map(shortDay), keys: keys, rows: rows,
      tip: function (i) { return tipRows(dayLabel(dates[i]) + ' (UTC)', keys.map(function (x, j) { return [x.name, rows[i][j], x.color]; })); }
    });
  }

  // ---------------------------------------------------------------- the History tab

  function loadDay(date) {
    if (S.days[date] || S.days[date] === false) return Promise.resolve(S.days[date]);
    var days = S.summary.days, newest = days.length && days[days.length - 1].date === date;
    return getJSON('data/days/' + date + '.json', newest).then(function (d) { S.days[date] = d; return d; }, function () { S.days[date] = false; return false; });
  }

  function furthestOf(r) { var f = null; CHAIN.forEach(function (m) { if ((r.reached || {})[m]) f = m; }); return f; }

  function renderHistory(host) {
    if (!S.summary) { renderRoad(host); return; }
    var all = S.summary.days;
    if (!all.length) { host.innerHTML = '<p class="muted">No rounds yet.</p>'; return; }
    var H = S.hist;
    if (!H.day || !all.some(function (d) { return d.date === H.day; })) H.day = all[all.length - 1].date;
    var i = all.findIndex(function (d) { return d.date === H.day; }), cur = all[i];
    var maxRuns = Math.max.apply(null, all.map(function (d) { return (d.real ? d.real.runs : 0) + (d.sim ? d.sim.runs : 0); }).concat([1]));
    var h = '<div class="controls daynav"><button class="ghost" data-day="' + (i > 0 ? all[i - 1].date : '') + '" ' + (i > 0 ? '' : 'disabled') + ' aria-label="Day before">◀</button>' +
      '<span class="day">' + esc(dayLabel(H.day)) + '</span><button class="ghost" data-day="' + (i < all.length - 1 ? all[i + 1].date : '') + '" ' + (i < all.length - 1 ? '' : 'disabled') + ' aria-label="Day after">▶</button>' +
      '<select data-ctl="day" aria-label="Pick a day">' + all.slice().reverse().map(function (d) { return '<option value="' + d.date + '"' + (d.date === H.day ? ' selected' : '') + '>' + esc(dayLabel(d.date)) + '</option>'; }).join('') + '</select>' +
      (i < all.length - 1 ? '<button class="ghost" data-day="' + all[all.length - 1].date + '">Latest</button>' : '') + '<span class="muted">' + all.length + ' days on record, UTC</span></div>';
    h += '<div class="days" role="group" aria-label="Every day, its runs">' + all.map(function (d) {
      var n = (d.real ? d.real.runs : 0) + (d.sim ? d.sim.runs : 0);
      return '<button type="button" data-day="' + d.date + '" aria-pressed="' + (d.date === H.day) + '" title="' + esc(dayLabel(d.date) + ': ' + n + ' runs') + '"><span style="height:' + Math.max(3, Math.round(40 * n / maxRuns)) + 'px"></span></button>';
    }).join('') + '</div>';
    var tr = cur.real, ts = cur.sim;
    h += '<p class="ink2">' + [['real', tr], ['sim', ts]].filter(function (k) { return k[1]; }).map(function (k) {
      var t = k[1], f = furthestOf(t);
      return '<span class="tag ' + k[0] + '">' + k[0] + '</span> ' + t.rounds + ' lane runs, ' + t.runs + ' runs, ' + t.ok + ' won' + (f ? ', furthest <b>' + esc(f) + '</b> (' + t.reached[f] + ')' : '') + (t['death-runs'] ? ', ' + t.deaths + ' deaths' : '');
    }).join(' &nbsp;·&nbsp; ') + '</p>';
    var day = S.days[H.day];
    if (day === undefined) { h += '<p class="loading">Loading the day…</p>'; host.innerHTML = h; loadDay(H.day).then(render); return; }
    if (day === false) { h += '<p class="error">This day’s file could not be loaded.</p>'; host.innerHTML = h; return; }
    var commits = []; day.runs.forEach(function (r) { if (commits.indexOf(r.commit) < 0) commits.push(r.commit); });
    h += '<div class="controls"><span class="ctl-label">Kind</span>' + seg('histKind', H.kind, [['all', 'All'], ['real', 'Real'], ['sim', 'Sim']]) +
      '<select data-ctl="commit" aria-label="Commit"><option value="">every commit</option>' + commits.map(function (c) { return '<option value="' + esc(c) + '"' + (c === H.commit ? ' selected' : '') + '>' + esc(String(c).slice(0, 7)) + '</option>'; }).join('') + '</select>' +
      '<select data-ctl="reach" aria-label="Reached at least"><option value="">reached anything</option>' + CHAIN.map(function (m) { return '<option value="' + m + '"' + (m === H.reach ? ' selected' : '') + '>reached ' + esc(m) + '</option>'; }).join('') + '</select></div>';
    var rows = day.runs.filter(function (r) {
      return (H.kind === 'all' || r.kind === H.kind) && (!H.commit || r.commit === H.commit) && (!H.reach || (r.reached || {})[H.reach] > 0);
    }).slice().reverse();
    var per = 25, pages = Math.max(1, Math.ceil(rows.length / per));
    if (H.page >= pages) H.page = pages - 1;
    var shown = rows.slice(H.page * per, H.page * per + per);
    h += '<div class="scroll"><table><thead><tr><th>lane started</th><th class="r">took</th><th>kind</th><th class="hide-sm">runner</th><th>commit</th><th class="r">runs</th><th class="r" title="runs that reached the target the commit raced to">won</th><th>furthest</th><th class="hide-sm">funnel</th><th>top stops</th><th></th></tr></thead><tbody>';
    if (!shown.length) h += '<tr><td colspan="11" class="muted">No runs match.</td></tr>';
    shown.forEach(function (r) {
      var f = furthestOf(r), key = r.run + '-' + r.kind, open = H.open === key;
      h += '<tr class="click' + (open ? ' open' : '') + '" data-row="' + esc(key) + '"><td class="time">' + (r.started ? '<time datetime="' + esc(r.started) + '">' + esc(fmtClock.format(new Date(r.started))) + '</time>' : '—') + '</td>' +
        '<td class="r num">' + (r.started && r.ended ? span(r.started, r.ended) : '—') + '</td><td><span class="tag ' + esc(r.kind) + '">' + esc(r.kind) + '</span></td>' +
        '<td class="hide-sm num">' + esc((r.runners || []).join(', ')) + '</td><td>' + commitLink(r.commit) + '</td><td class="r num">' + r.runs + '</td><td class="r num">' + (r.ok ? '<b style="color:var(--good)">' + r.ok + '</b>' : '0') + '</td>' +
        '<td>' + (f ? esc(f) + ' <span class="muted">(' + r.reached[f] + ')</span>' : '—') + '</td><td class="hide-sm">' + funnelMini(r) + '</td>' +
        '<td>' + (r.failures || []).slice(0, 2).map(function (x) { return issueLink((r.issues || {})[x[0] + ' ' + x[1]], x[0] + ': ' + x[1]) + ' <span class="muted">' + x[2] + '</span>'; }).join('<br>') + '</td>' +
        '<td>' + runLink(r.run) + '</td></tr>';
      if (open) h += '<tr><td colspan="11">' + runDetail(r) + '</td></tr>';
    });
    h += '</tbody></table></div>';
    h += '<div class="pager"><span class="muted">' + rows.length + ' lane runs' + (pages > 1 ? ', page ' + (H.page + 1) + ' of ' + pages : '') + '</span>' +
      (pages > 1 ? '<button class="ghost" data-page="' + (H.page - 1) + '" ' + (H.page ? '' : 'disabled') + '>Newer</button><button class="ghost" data-page="' + (H.page + 1) + '" ' + (H.page < pages - 1 ? '' : 'disabled') + '>Older</button>' : '') +
      '<a href="data/batches/' + H.day + '.ndjson">every batch of the day (NDJSON)</a></div>';
    host.innerHTML = h;
  }

  function runDetail(r) {
    var h = '<div class="split"><div><h4>Funnel</h4><table><thead><tr><th>milestone</th><th class="r">reached</th><th class="r">share</th><th class="r">median</th><th class="r">fastest</th><th class="hide-sm">first</th></tr></thead><tbody>';
    var prev = r.runs;
    CHAIN.forEach(function (m) {
      var n = (r.reached || {})[m] || 0, sp = (r.speed || {})[m], drop = prev > 0 ? (prev - n) / prev : 0;
      h += '<tr><td><span class="swatch" style="background:' + phaseVar(phaseOf(m)) + '"></span> ' + esc(m) + '</td><td class="r num">' + n + (drop >= 0.005 && prev ? ' <span class="muted">−' + pct(drop) + '</span>' : '') + '</td><td class="r num">' + pct(share(n, r.runs)) + '</td><td class="r num">' + clock(sp && sp.median) + '</td><td class="r num">' + clock(sp && sp.fastest) + '</td><td class="hide-sm time">' + ((r.first || {})[m] ? esc(fmtClock.format(new Date(r.first[m]))) : '') + '</td></tr>';
      prev = n;
    });
    h += '</tbody></table></div><div><h4>Every stop</h4><table><thead><tr><th>stage: reason</th><th class="r">runs</th></tr></thead><tbody>' +
      ((r.failures || []).map(function (x) { return '<tr><td>' + issueLink((r.issues || {})[x[0] + ' ' + x[1]], x[0] + ': ' + x[1]) + '</td><td class="r num">' + x[2] + '</td></tr>'; }).join('') || '<tr><td colspan="2" class="muted">none</td></tr>') + '</tbody></table>';
    if (r.leader) {
      h += '<h4>Furthest bot: <span class="mono">' + esc(r.leader.bot) + '</span></h4><p class="ink2">' + CHAIN.filter(function (m) { return r.leader.splits[m] != null; }).map(function (m) { return esc(m) + ' ' + clock(r.leader.splits[m]); }).join(' · ') + '</p>';
    }
    h += '<p class="muted">' + r.batches + ' batches' + (r.deaths != null ? ', ' + r.deaths + ' deaths over ' + r['death-runs'] + ' runs' : '') + ' · ' + runLink(r.run, 'the lane’s run') + ' · ' + commitLink(r.commit) + '</p></div></div>';
    return h;
  }

  // ---------------------------------------------------------------- the Agents tab

  var WORDS = { assign: 'assigned to Copilot', reassign: 'assigned again (the session never started)', ready: 'marked ready for review', release: 'released runs held for approval', nudge: 'asked Copilot to go on', merge: 'merged', review: 'left for a person (it changes CI)', stuck: 'left for a person (asked eight times)', wait: 'waiting', leave: 'left alone' };
  var CHECKS = { green: 'green', red: 'red', pending: 'running', approval: 'held for approval' };

  function renderAgents(host) {
    var h = '<p class="lede">The orchestrator (the copilot workflow, once a minute) gives the bot-failure issues to Copilot’s bot-fixer agent one at a time, releases its held runs, marks finished drafts ready, merges what is green and asks it to go on when a check is red. Fixer sessions work the issues a person names (fix/* branches). Live state first, then the history.</p>';
    h += '<div id="ag-live">' + orchBlock() + '</div>';
    h += '<h2>History</h2>' + (S.agents ? agentsHistory() : '<p class="muted">' + (S.failed.agents ? 'The agents’ report (data/agents.json) could not be loaded.' : 'Loading…') + '</p>');
    host.innerHTML = h;
    var c = document.getElementById('a-issues'); if (c) issuesChart(c, 21);
    var b = document.getElementById('a-stages');
    if (b) {
      var open = S.agents.issues.filter(function (i) { return i.state === 'open'; });
      var items = STAGES.filter(function (s) { return open.some(function (i) { return i.stage === s.id; }); }).map(function (s) {
        var mine = open.filter(function (i) { return i.stage === s.id; });
        return { label: s.id, value: mine.length, color: phaseVar(s.phase), tip: tipRows(s.id + ': ' + mine.length + ' open', mine.map(function (i) { return ['#' + i.n, i.reason || '', '']; })) };
      });
      if (items.length) barList(b, items); else b.innerHTML = '<p class="muted">No open bot-failure issue.</p>';
    }
  }

  function orchBlock() {
    var st = S.orch;
    if (!st) return '<p class="muted">' + (S.failed.orch ? 'No orchestrator state yet (orchestrator.json on gh-pages).' : 'Reading the orchestrator’s state…') + '</p>';
    var at = new Date(st.at), fresh = Date.now() - at < 15 * 60000;
    var held = st.issues.filter(function (i) { return i.copilot; }), queue = st.issues.filter(function (i) { return !i.copilot; });
    var h = '<p><span class="dot' + (fresh ? ' on' : '') + '"></span><b>Orchestrator</b>: last pass ' + esc(ago(st.at)) + ' (' + esc(fmtTime.format(at)) + '); ' + st.issues.length + ' open bot-failure issues, Copilot on ' + held.length + ' of at most ' + st.cap + ' at once.' + (fresh ? '' : ' <b class="error">It has not reported for 15 minutes: check the copilot workflow.</b>') + '</p>';
    h += '<div class="split"><div class="card"><h3>Working now</h3><div class="scroll"><table><thead><tr><th>issue</th><th>pull request</th><th>checks</th><th>session</th><th class="r">asks</th><th>this minute</th></tr></thead><tbody>' +
      (held.map(function (i) {
        var m = st.moves.filter(function (x) { return x.issue === i.number; })[0] || {};
        return '<tr><td>' + issueLink(i.number) + '<div class="muted">' + esc(i.title) + '</div></td><td>' + (i.pr ? prLink(i.pr) : '') + '</td><td>' + (i.pr ? esc(CHECKS[i.checks] || i.checks || '') : '') + (i.draft ? ' (draft)' : '') + '</td><td>' + (i.session ? '<span class="dot on"></span>running' : 'ended') + '</td><td class="r num">' + (i.nudges || 0) + '/8</td><td>' + esc(WORDS[m.move] || m.move || '') + (m.why ? ' (' + esc(m.why) + ')' : '') + (i.quiet ? ' <b>quiet for 2 h</b>' : '') + '</td></tr>';
      }).join('') || '<tr><td colspan="6" class="muted">Nothing held right now.</td></tr>') + '</tbody></table></div></div>';
    h += '<div class="card"><h3>Waiting <span class="muted">(' + queue.length + ', oldest first: the order it takes them)</span></h3><div class="scroll"><table><tbody>' +
      (queue.map(function (i) {
        var off = (i.labels || []).filter(function (l) { return /no-agent|agent-stuck|needs-review/.test(l); });
        return '<tr><td>' + issueLink(i.number) + '</td><td>' + esc(i.title) + '</td><td class="muted">' + esc(off.join(', ')) + '</td></tr>';
      }).join('') || '<tr><td class="muted">Empty.</td></tr>') + '</tbody></table></div></div></div>';
    var log = st.log.slice().reverse(), shown = S.logAll ? log : log.slice(0, 15);
    h += '<div class="card" style="margin-top:1rem"><h3>What it did</h3><div class="scroll"><table><thead><tr><th>when</th><th>issue</th><th>action</th><th></th><th>why</th></tr></thead><tbody>' +
      (shown.map(function (e) { return '<tr><td class="time">' + when(e.at) + '</td><td>' + issueLink(e.issue) + '</td><td>' + esc(WORDS[e.move] || e.move) + '</td><td>' + (e.pr ? prLink(e.pr) : '') + '</td><td class="muted">' + esc(e.why || '') + '</td></tr>'; }).join('') || '<tr><td colspan="5" class="muted">Nothing yet.</td></tr>') +
      '</tbody></table></div>' + (log.length > 15 ? '<div class="pager"><button class="ghost" data-act="log">' + (S.logAll ? 'Show the newest 15' : 'Show all ' + log.length) + '</button></div>' : '') + '</div>';
    return h;
  }

  function agentsHistory() {
    var A = S.agents, now = Date.now(), week = now - 7 * 86400000;
    var open = A.issues.filter(function (i) { return i.state === 'open'; });
    var closed7 = A.issues.filter(function (i) { return i.closed && new Date(i.closed) >= week; });
    var fixed7 = closed7.filter(function (i) { return i.why === 'completed'; }).length;
    var merged = A.prs.filter(function (p) { return p.merged; });
    var merged7 = merged.filter(function (p) { return new Date(p.merged) >= week; });
    var byIssue = {}; A.issues.forEach(function (i) { byIssue[i.n] = i; });
    var fixTimes = [], fixTimes7 = [];
    merged.forEach(function (p) {
      p.fixes.forEach(function (n) {
        var i = byIssue[n]; if (!i) return;
        var h = (new Date(p.merged) - new Date(i.opened)) / 3600000; if (h < 0) return;
        fixTimes.push(h); if (new Date(p.merged) >= week) fixTimes7.push(h);
      });
    });
    var person = open.filter(function (i) { return (i.labels || []).some(function (l) { return /agent-stuck|needs-review|no-agent/.test(l); }); });
    var hrs = function (x) { return x == null ? '—' : x < 48 ? x.toFixed(1) + ' h' : (x / 24).toFixed(1) + ' d'; };
    var who = function (list, w) { return list.filter(function (p) { return p.by === w; }).length; };
    var h = '<div class="tiles">';
    h += '<div class="tile"><div class="label">Open bot-failure issues</div><div class="value">' + open.length + '</div><div class="foot">' + A.issues.length + ' filed in all; ' + person.length + ' wait for a person</div></div>';
    h += '<div class="tile"><div class="label">Closed in the last 7 days</div><div class="value">' + closed7.length + '</div><div class="foot">' + fixed7 + ' fixed, ' + (closed7.length - fixed7) + ' closed with no reproducible snapshot</div></div>';
    h += '<div class="tile"><div class="label">Fix pull requests merged</div><div class="value">' + merged.length + ' <small>' + merged7.length + ' this week</small></div><div class="foot">Copilot ' + who(merged, 'copilot') + ', fixer sessions ' + who(merged, 'fixer') + ', others ' + who(merged, 'person') + '</div></div>';
    h += '<div class="tile"><div class="label">Time to fix (issue filed → fix merged)</div><div class="value">' + hrs(median(fixTimes7)) + ' <small>median, 7 days</small></div><div class="foot">all time ' + hrs(median(fixTimes)) + ' over ' + fixTimes.length + ' fixes</div></div>';
    h += '</div>';
    h += '<div class="charts" style="margin-top:1rem"><div class="chart"><h3>Issues opened and closed</h3><p class="sub">Bot-failure issues a day, the last 21 UTC days.</p><div id="a-issues"></div></div>' +
      '<div class="chart"><h3>Open issues by stage</h3><p class="sub">Where the open bot-failure issues stop the bot, in road order.</p><div id="a-stages"></div></div></div>';
    if (person.length) {
      h += '<h2>Waiting for a person</h2><div class="scroll"><table><thead><tr><th>issue</th><th>failure</th><th>labels</th><th>opened</th></tr></thead><tbody>' + person.map(function (i) {
        return '<tr><td>' + issueLink(i.n) + '</td><td>' + esc(i.title) + '</td><td>' + esc((i.labels || []).filter(function (l) { return l !== 'bot-failure'; }).join(', ')) + '</td><td class="time">' + when(i.opened) + '</td></tr>';
      }).join('') + '</tbody></table></div>';
    }
    var prs = A.prs.slice().sort(function (a, b) { return b.n - a.n; }), per = 20, pages = Math.max(1, Math.ceil(prs.length / per));
    if (S.prPage >= pages) S.prPage = pages - 1;
    h += '<h2>Fix pull requests</h2><p class="lede">Every pull request that closes a bot-failure issue, and every one Copilot opened, newest first.</p><div class="scroll"><table><thead><tr><th>pull request</th><th>fixes</th><th>by</th><th>state</th><th>opened</th><th class="r">open for</th></tr></thead><tbody>' +
      prs.slice(S.prPage * per, S.prPage * per + per).map(function (p) {
        var end = p.merged || p.closed;
        return '<tr><td>' + prLink(p.n) + ' <span class="ink2">' + esc(p.title) + '</span></td><td>' + (p.fixes.map(function (n) { return issueLink(n); }).join(' ') || '<span class="muted">—</span>') + '</td><td>' + esc(p.by) + '</td>' +
          '<td><span class="tag ' + esc(p.merged ? 'merged' : p.state) + '">' + esc(p.merged ? 'merged' : p.state + (p.draft ? ' (draft)' : '')) + '</span></td><td class="time">' + when(p.opened) + '</td><td class="r num">' + (end ? hrs((new Date(end) - new Date(p.opened)) / 3600000) : '<span class="muted">open</span>') + '</td></tr>';
      }).join('') + '</tbody></table></div>';
    h += '<div class="pager"><span class="muted">' + prs.length + ' pull requests' + (pages > 1 ? ', page ' + (S.prPage + 1) + ' of ' + pages : '') + ' · as of ' + when(A.updated) + '</span>' +
      (pages > 1 ? '<button class="ghost" data-pr="' + (S.prPage - 1) + '" ' + (S.prPage ? '' : 'disabled') + '>Newer</button><button class="ghost" data-pr="' + (S.prPage + 1) + '" ' + (S.prPage < pages - 1 ? '' : 'disabled') + '>Older</button>' : '') + '</div>';
    return h;
  }

  // ---------------------------------------------------------------- the frame: tabs, events, loading

  var RENDER = { road: renderRoad, trends: renderTrends, history: renderHistory, agents: renderAgents };
  function route() {
    var hash = (location.hash || '#road').slice(1).split('/');
    S.tab = RENDER[hash[0]] ? hash[0] : 'road';
    if (S.tab === 'history' && hash[1]) S.hist.day = hash[1];
    document.querySelectorAll('nav.tabs a').forEach(function (a) { a.setAttribute('aria-selected', String(a.dataset.tab === S.tab)); });
    document.querySelectorAll('section.tab').forEach(function (sec) { sec.hidden = sec.id !== 'tab-' + S.tab; });
    render();
  }
  function render() {
    hideTip();
    var host = document.getElementById('tab-' + S.tab);
    try { RENDER[S.tab](host); } catch (e) { host.innerHTML = '<p class="error">This tab failed to draw: ' + esc(e.message) + '</p>'; if (window.console) console.error(e); }
  }
  function setHistoryDay(d) {
    if (!d) return;
    S.hist.day = d; S.hist.page = 0; S.hist.open = null;
    history.replaceState(null, '', '#history/' + d);
    render();
  }

  document.addEventListener('click', function (ev) {
    var t = ev.target.closest('[data-ctl] button, [data-day], [data-page], [data-pr], [data-act], tr[data-row], .stage > .row');
    if (!t || ev.target.closest('a')) return;
    var ctl = t.parentNode && t.parentNode.dataset ? t.parentNode.dataset.ctl : null;
    if (ctl && t.dataset.v != null) {
      var v = t.dataset.v;
      if (ctl === 'range') S.range = v;
      else if (ctl === 'trendKind') S.trendKind = v;
      else if (ctl === 'trendRange') S.trendRange = v;
      else if (ctl === 'timeRange') S.timeRange = v;
      else if (ctl === 'histKind') { S.hist.kind = v; S.hist.page = 0; }
      store('cc.' + ctl, v);
      render(); return;
    }
    if (t.dataset.day !== undefined) { setHistoryDay(t.dataset.day); return; }
    if (t.dataset.page !== undefined) { S.hist.page = Number(t.dataset.page); render(); return; }
    if (t.dataset.pr !== undefined) { S.prPage = Number(t.dataset.pr); render(); return; }
    if (t.dataset.act === 'log') { S.logAll = !S.logAll; render(); return; }
    if (t.dataset.row) { S.hist.open = S.hist.open === t.dataset.row ? null : t.dataset.row; render(); return; }
    var st = t.closest('.stage');
    if (st) { var id = st.dataset.stage; S.open[id] = !S.open[id]; render(); }
  });
  document.addEventListener('keydown', function (ev) {
    if ((ev.key === 'Enter' || ev.key === ' ') && ev.target.matches && ev.target.matches('.stage > .row')) { ev.preventDefault(); ev.target.click(); }
  });
  document.addEventListener('change', function (ev) {
    var c = ev.target.dataset && ev.target.dataset.ctl;
    if (c === 'day') setHistoryDay(ev.target.value);
    else if (c === 'commit') { S.hist.commit = ev.target.value; S.hist.page = 0; render(); }
    else if (c === 'reach') { S.hist.reach = ev.target.value; S.hist.page = 0; render(); }
  });
  window.addEventListener('hashchange', route);
  var resizeTimer = null, lastW = window.innerWidth;
  window.addEventListener('resize', function () {
    if (window.innerWidth === lastW) return;
    lastW = window.innerWidth; clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 150);
  });

  // the theme: the system's, or the switch's (light, dark), remembered on this device
  var themes = ['auto', 'light', 'dark'];
  function applyTheme(t) { if (t === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t); document.getElementById('theme').title = 'Theme: ' + t; }
  var theme = store('cc.theme') || 'auto';
  applyTheme(theme);
  document.getElementById('theme').addEventListener('click', function () { theme = themes[(themes.indexOf(theme) + 1) % 3]; store('cc.theme', theme); applyTheme(theme); render(); });
  ['range', 'trendKind', 'trendRange', 'timeRange'].forEach(function (k) { var v = store('cc.' + k); if (v) S[k] = v; });
  var tz = document.getElementById('tz'); if (tz) tz.textContent = Intl.DateTimeFormat().resolvedOptions().timeZone;

  // what is running now: one call to GitHub's public API, kept two minutes on this device
  function live() {
    var el = document.getElementById('live');
    function show(j) {
      var runs = (j.workflow_runs || []).filter(function (r) { return /(^|\/)(race|simrace)\.yml$/.test(r.path || ''); });
      var real = runs.filter(function (r) { return /\/race\.yml$/.test(r.path) || r.path === 'race.yml'; }), sim = runs.filter(function (r) { return /simrace\.yml$/.test(r.path); });
      var shas = {}; runs.forEach(function (r) { shas[r.head_sha.slice(0, 7)] = 1; });
      el.innerHTML = '<span class="dot' + (runs.length ? ' on' : '') + '"></span>' + (runs.length ? '<a href="' + gh + '/actions">' + real.length + ' real and ' + sim.length + ' sim lanes running</a> on ' + Object.keys(shas).map(function (s) { return '<code>' + esc(s) + '</code>'; }).join(', ') : 'No lane running');
    }
    var cached = null;
    try { cached = JSON.parse(sessionStorage.getItem('cc.live') || 'null'); } catch (e) { cached = null; }
    if (cached && Date.now() - cached.t < 120000) { show(cached.j); return; }
    fetch('https://api.github.com/repos/' + repo + '/actions/runs?status=in_progress&per_page=100').then(function (r) { if (!r.ok) throw r.status; return r.json(); }).then(function (j) {
      try { sessionStorage.setItem('cc.live', JSON.stringify({ t: Date.now(), j: { workflow_runs: (j.workflow_runs || []).map(function (r) { return { path: r.path, head_sha: r.head_sha }; }) } })); } catch (e) { /* private mode */ }
      show(j);
    }).catch(function () { el.textContent = 'Lanes: GitHub did not answer (rate limit?)'; });
  }

  function loaded(key, p) {
    return p.then(function (v) { S[key] = v; }, function (e) { S.failed[key] = e && e.message ? e.message : 'unavailable'; }).then(render);
  }
  route();
  loaded('summary', getJSON('data/summary.json', true));
  loaded('gyms', getText('data/gyms.ndjson').then(ndjson));
  loaded('agents', getJSON('data/agents.json', true));
  loaded('orch', getJSON('https://raw.githubusercontent.com/' + repo + '/gh-pages/orchestrator.json?t=' + Date.now(), true).catch(function () { return getJSON('orchestrator.json', true); }));
  live();
})();
