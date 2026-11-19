/* EasyDocs spreadsheet viewer. ES5 only, so it runs on old Android WebViews.
 * The grid is virtualised: only the visible cells (plus a small margin) exist in the DOM. */
(function () {
  'use strict';

  var Bridge = window.EasyDocs || {
    onLoaded: function () {},
    onError: function (m) { if (window.console) console.log(m); }
  };
  if (/[?&]dark=1/.test(location.search)) document.documentElement.className = 'dark';

  var RH0 = 22, CW0 = 72, HH0 = 22, FS0 = 13, MAX_SPILL = 8;
  var MIN_Z = 0.3, MAX_Z = 3;

  var $ = function (id) { return document.getElementById(id); };
  var sc = $('sc'), sp = $('sp'), cells = $('cells'), ch = $('ch'), rh = $('rh'), corner = $('corner');
  var tabs = $('tabs'), refEl = $('ref'), valEl = $('val'), msg = $('msg');
  var chi = document.createElement('div'); chi.style.position = 'absolute'; chi.style.top = '0'; ch.appendChild(chi);
  var rhi = document.createElement('div'); rhi.style.position = 'absolute'; rhi.style.left = '0'; rh.appendChild(rhi);

  var wb = null, S = null, zoom = 1, HW = 40, HH = HH0;
  var dark = document.documentElement.className === 'dark';
  var rendered = null; // {r0,r1,c0,c1}
  var rafPending = false;

  var raf = window.requestAnimationFrame || window.webkitRequestAnimationFrame ||
    function (f) { return setTimeout(f, 16); };

  // ---------- loading ----------

  function fail(e) {
    var m = String((e && e.message) || e || '');
    if (window.console) console.log('EasyDocs error: ' + m + ' ' + ((e && e.stack) || '').split('\n').slice(0, 3).join(' | '));
    var text;
    if (/password|encrypt/i.test(m)) text = 'This spreadsheet is password-protected and can\'t be opened.';
    else if (/allocation|memory|Invalid array length|Invalid string length/i.test(m)) text = 'This spreadsheet is too large to open on this phone.';
    else if (/read the file/i.test(m)) text = m;
    else text = 'This file can\'t be opened. It may be damaged or not a real Excel file.';
    Bridge.onError(text);
  }

  function load() {
    var x = new XMLHttpRequest();
    x.open('GET', '/doc', true);
    x.responseType = 'arraybuffer';
    x.onload = function () {
      if (x.status !== 200 || !x.response || !x.response.byteLength) {
        fail('Could not read the file.');
        return;
      }
      var buf = x.response;
      x = null;
      setTimeout(function () { parse(buf); }, 0);
    };
    x.onerror = function () { fail('Could not read the file.'); };
    x.send();
  }

  function parse(buf) {
    try {
      wb = XLSX.read(new Uint8Array(buf), {
        type: 'array', dense: true, cellStyles: true, cellFormula: false,
        cellHTML: false, cellNF: false, sheetStubs: false, bookVBA: false
      });
    } catch (e) {
      fail(e);
      return;
    }
    buf = null;
    if (!wb.SheetNames || !wb.SheetNames.length) { fail('empty'); return; }
    buildTabs();
    showSheet(0);
    Bridge.onLoaded(wb.SheetNames.length);
  }

  // ---------- sheet model ----------

  function buildTabs() {
    var h = '';
    for (var i = 0; i < wb.SheetNames.length; i++) {
      h += '<span data-i="' + i + '">' + esc(wb.SheetNames[i]) + '</span>';
    }
    tabs.innerHTML = h;
  }

  function showSheet(i) {
    var spans = tabs.getElementsByTagName('span');
    for (var t = 0; t < spans.length; t++) spans[t].className = t === i ? 'on' : '';
    if (spans[i] && spans[i].scrollIntoView) spans[i].scrollIntoView(false);

    var name = wb.SheetNames[i];
    var ws = wb.Sheets[name] || {};
    var data = ws['!data'] || [];
    var nr = data.length, nc = 0, r, c;
    for (r = 0; r < data.length; r++) if (data[r] && data[r].length > nc) nc = data[r].length;

    var merges = ws['!merges'] || [];
    for (var m = 0; m < merges.length; m++) {
      if (merges[m].e.r + 1 > nr) nr = merges[m].e.r + 1;
      if (merges[m].e.c + 1 > nc) nc = merges[m].e.c + 1;
    }
    var dataRows = nr, dataCols = nc;
    nr = Math.max(nr + 10, 50);
    nc = Math.max(nc + 3, 12);

    var colsInfo = ws['!cols'] || [], rowsInfo = ws['!rows'] || [];
    var colX = new Float64Array(nc + 1), rowY = new Float64Array(nr + 1);
    for (c = 0; c < nc; c++) colX[c + 1] = colX[c] + colWidth(colsInfo[c]);
    for (r = 0; r < nr; r++) rowY[r + 1] = rowY[r] + rowHeight(rowsInfo[r]);

    // Map every cell covered by a merge to its merge (bounded so huge merges can't exhaust memory).
    var mergeMap = null, budget = 300000;
    if (merges.length) {
      mergeMap = {};
      for (m = 0; m < merges.length; m++) {
        var g = merges[m];
        g.id = m;
        var area = (g.e.r - g.s.r + 1) * (g.e.c - g.s.c + 1);
        if (area > budget) { mergeMap[g.s.r + ',' + g.s.c] = g; continue; }
        budget -= area;
        for (r = g.s.r; r <= g.e.r; r++) for (c = g.s.c; c <= g.e.c; c++) mergeMap[r + ',' + c] = g;
      }
    }

    S = {
      index: i, name: name, data: data, nr: nr, nc: nc, colX: colX, rowY: rowY,
      mergeMap: mergeMap, sel: null, dataRows: dataRows, dataCols: dataCols
    };
    msg.style.display = ws['!type'] === 'chart' ? 'block' : 'none';
    msg.textContent = 'Chart sheets are not shown.';
    showInfo();
    rendered = null;
    layout();
    sc.scrollTop = 0;
    sc.scrollLeft = 0;
    render(true);
  }

  function colWidth(ci) {
    if (!ci) return CW0;
    if (ci.hidden) return 0;
    // Excel widths are in characters; SheetJS's pixel guess assumes a wide font, so prefer characters.
    var w = (ci.wch ? ci.wch * 7 + 5 : 0) || (ci.width ? ci.width * 7 + 5 : 0) || ci.wpx || CW0;
    return Math.max(16, Math.min(w, 600));
  }

  function rowHeight(ri) {
    if (!ri) return RH0;
    if (ri.hidden) return 0;
    var h = (ri.hpt ? ri.hpt * 4 / 3 : 0) || ri.hpx || RH0;
    return Math.max(18, Math.min(h, 400));
  }

  // ---------- layout and rendering ----------

  function layout() {
    var z = zoom;
    HH = Math.round(HH0 * z);
    var digits = String(S.nr).length;
    HW = Math.round(Math.max(32, digits * 8 + 12) * z);
    corner.style.width = HW + 'px'; corner.style.height = HH + 'px';
    ch.style.left = HW + 'px'; ch.style.height = HH + 'px';
    rh.style.top = HH + 'px'; rh.style.width = HW + 'px';
    sc.style.left = HW + 'px'; sc.style.top = HH + 'px';
    sp.style.width = Math.ceil(S.colX[S.nc] * z) + 'px';
    sp.style.height = Math.ceil(S.rowY[S.nr] * z) + 'px';
    cells.style.fontSize = (FS0 * z).toFixed(2) + 'px';
    chi.style.fontSize = rhi.style.fontSize = (12 * z).toFixed(2) + 'px';
    rendered = null;
  }

  /** Largest i in [0, n-1] with arr[i] <= v. */
  function findIdx(arr, n, v) {
    var lo = 0, hi = n - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (arr[mid] <= v) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  function onScroll() {
    chi.style.left = -sc.scrollLeft + 'px';
    rhi.style.top = -sc.scrollTop + 'px';
    if (!rafPending) { rafPending = true; raf(function () { rafPending = false; render(false); }); }
  }

  function render(force) {
    if (!S) return;
    var z = zoom, sl = sc.scrollLeft, st = sc.scrollTop;
    var vw = sc.clientWidth || window.innerWidth, vh = sc.clientHeight || window.innerHeight;
    var vr0 = findIdx(S.rowY, S.nr, st / z), vr1 = findIdx(S.rowY, S.nr, (st + vh) / z);
    var vc0 = findIdx(S.colX, S.nc, sl / z), vc1 = findIdx(S.colX, S.nc, (sl + vw) / z);
    var R = rendered;
    if (!force && R && vr0 >= R.r0 && vr1 <= R.r1 && vc0 >= R.c0 && vc1 <= R.c1) return;

    // Render with a margin so small scrolls don't rebuild the DOM.
    var r0 = Math.max(0, vr0 - 15), r1 = Math.min(S.nr - 1, vr1 + 15);
    var c0 = Math.max(0, vc0 - 3), c1 = Math.min(S.nc - 1, vc1 + 3);
    rendered = { r0: r0, r1: r1, c0: c0, c1: c1 };

    var out = [], spill = [], drawn = {}, r, c;
    for (r = r0; r <= r1; r++) {
      var top = S.rowY[r] * z, h = (S.rowY[r + 1] - S.rowY[r]) * z;
      if (h <= 0) continue;
      var row = S.data[r];
      for (c = c0; c <= c1; c++) {
        var w = (S.colX[c + 1] - S.colX[c]) * z;
        if (w <= 0) continue;
        if (S.mergeMap) {
          var g = S.mergeMap[r + ',' + c];
          if (g) {
            if (!drawn[g.id]) {
              drawn[g.id] = 1;
              var mr = S.data[g.s.r];
              cellDiv(out, mr && mr[g.s.c], g.s.r, g.s.c,
                S.colX[g.s.c] * z, S.rowY[g.s.r] * z,
                (S.colX[g.e.c + 1] - S.colX[g.s.c]) * z, (S.rowY[g.e.r + 1] - S.rowY[g.s.r]) * z, ' m');
            }
            continue;
          }
        }
        var cell = row && row[c];
        // Text spills into empty neighbours, as in Excel.
        var sw = w;
        if (cell && cell.t === 's' && row) {
          for (var k = 1; k <= MAX_SPILL && c + k < S.nc; k++) {
            var nb = row[c + k];
            if ((nb && nb.v != null && nb.v !== '') || (S.mergeMap && S.mergeMap[r + ',' + (c + k)])) break;
            sw += (S.colX[c + k + 1] - S.colX[c + k]) * z;
          }
        }
        if (sw > w) {
          cellDiv(out, null, r, c, S.colX[c] * z, top, w, h, '');
          cellDiv(spill, cell, r, c, S.colX[c] * z, top, sw, h, ' s');
        } else {
          cellDiv(out, cell, r, c, S.colX[c] * z, top, w, h, '');
        }
      }
    }
    cells.innerHTML = out.join('') + spill.join('');

    var hh = [];
    for (c = c0; c <= c1; c++) {
      var cw = (S.colX[c + 1] - S.colX[c]) * z;
      if (cw <= 0) continue;
      hh.push('<div class="h" style="left:', (S.colX[c] * z).toFixed(1), 'px;width:', cw.toFixed(1),
        'px;height:', HH, 'px;line-height:', HH, 'px">', colName(c), '</div>');
    }
    chi.innerHTML = hh.join('');
    hh = [];
    for (r = r0; r <= r1; r++) {
      var rhh = (S.rowY[r + 1] - S.rowY[r]) * z;
      if (rhh <= 0) continue;
      hh.push('<div class="h" style="top:', (S.rowY[r] * z).toFixed(1), 'px;width:', HW,
        'px;height:', rhh.toFixed(1), 'px;line-height:', rhh.toFixed(1), 'px">', r + 1, '</div>');
    }
    rhi.innerHTML = hh.join('');
    chi.style.left = -sl + 'px';
    rhi.style.top = -st + 'px';
  }

  function cellDiv(out, cell, r, c, x, y, w, h, extra) {
    var cls = 'c' + extra, style = '', text = '';
    if (cell) {
      text = cellText(cell);
      if (cell.t === 'n' || cell.t === 'd') cls += ' n';
      else if (cell.t === 'b') cls += ' b';
      else if (cell.t === 'e') cls += ' e';
      var s = cell.s;
      if (s && s.patternType === 'solid' && s.fgColor && s.fgColor.rgb) {
        var rgb = String(s.fgColor.rgb);
        if (rgb.length === 8) rgb = rgb.substr(2);
        if (/^[0-9A-Fa-f]{6}$/.test(rgb) && !(dark && /^ffffff$/i.test(rgb))) {
          var n = parseInt(rgb, 16);
          var lum = 0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255);
          style = 'background:#' + rgb + ';color:' + (lum < 140 ? '#FFFFFF' : '#1B1F27') + ';';
        }
      }
    }
    if (extra === ' s') { cls = cls.replace('c s', 'c'); style += 'border:0;z-index:1;'; if (!style.match(/background/)) style += 'background:transparent;'; }
    if (S.sel && S.sel.r === r && S.sel.c === c) cls += ' sel';
    out.push('<div class="', cls, '" data-a="', r, ',', c, '" style="', style,
      'left:', x.toFixed(1), 'px;top:', y.toFixed(1), 'px;width:', w.toFixed(1), 'px;height:', h.toFixed(1),
      'px;line-height:', (extra === ' m' ? 'normal' : h.toFixed(1) + 'px'), '">', esc(text), '</div>');
  }

  function cellText(cell) {
    if (cell.w != null) return String(cell.w);
    if (cell.v == null) return '';
    if (cell.t === 'b') return cell.v ? 'TRUE' : 'FALSE';
    if (cell.v instanceof Date) return cell.v.toLocaleDateString();
    return String(cell.v);
  }

  function colName(c) {
    var s = '';
    c++;
    while (c > 0) { var m = (c - 1) % 26; s = String.fromCharCode(65 + m) + s; c = Math.floor((c - 1) / 26); }
    return s;
  }

  function esc(t) {
    return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function showInfo() {
    if (S.sel) {
      var row = S.data[S.sel.r], cell = row && row[S.sel.c];
      refEl.textContent = colName(S.sel.c) + (S.sel.r + 1);
      valEl.textContent = cell ? cellText(cell) : '';
    } else {
      refEl.textContent = '';
      valEl.textContent = S.name + ' · ' + S.dataRows.toLocaleString() + ' rows × ' +
        S.dataCols.toLocaleString() + ' columns';
    }
  }

  // ---------- zoom ----------

  function setZoom(z, fx, fy) {
    z = Math.max(MIN_Z, Math.min(MAX_Z, z));
    if (!S || Math.abs(z - zoom) < 0.001) return;
    var cx = (sc.scrollLeft + fx) / zoom, cy = (sc.scrollTop + fy) / zoom;
    zoom = z;
    layout();
    sc.scrollLeft = cx * z - fx;
    sc.scrollTop = cy * z - fy;
    render(true);
  }

  window.zoomBy = function (f) {
    setZoom(zoom * f, sc.clientWidth / 2, sc.clientHeight / 2);
  };

  // Two-finger pinch.
  var pinch = null;
  function dist(t) {
    var dx = t[0].clientX - t[1].clientX, dy = t[0].clientY - t[1].clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }
  sc.addEventListener('touchstart', function (e) {
    if (e.touches.length === 2) {
      var rect = sc.getBoundingClientRect();
      pinch = {
        d: dist(e.touches) || 1, z: zoom,
        x: (e.touches[0].clientX + e.touches[1].clientX) / 2 - rect.left,
        y: (e.touches[0].clientY + e.touches[1].clientY) / 2 - rect.top
      };
    }
  }, false);
  sc.addEventListener('touchmove', function (e) {
    if (pinch && e.touches.length === 2) {
      e.preventDefault();
      var nz = pinch.z * dist(e.touches) / pinch.d;
      if (Math.abs(nz - zoom) / zoom > 0.02) setZoom(nz, pinch.x, pinch.y);
    }
  }, false);
  sc.addEventListener('touchend', function (e) { if (e.touches.length < 2) pinch = null; }, false);

  // ---------- events ----------

  sc.addEventListener('scroll', onScroll, false);
  window.addEventListener('resize', function () { render(true); }, false);

  cells.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t !== cells && !t.getAttribute('data-a')) t = t.parentNode;
    if (!t || t === cells) return;
    var a = t.getAttribute('data-a').split(',');
    var r = +a[0], c = +a[1];
    S.sel = (S.sel && S.sel.r === r && S.sel.c === c) ? null : { r: r, c: c };
    showInfo();
    render(true);
  }, false);

  tabs.addEventListener('click', function (e) {
    var i = e.target.getAttribute && e.target.getAttribute('data-i');
    if (i != null && wb && +i !== S.index) {
      showSheet(+i);
    }
  }, false);

  window.onerror = function (m) { if (!S) fail(m); };

  if (typeof XLSX === 'undefined') fail('Spreadsheet engine failed to load.');
  else load();
})();
