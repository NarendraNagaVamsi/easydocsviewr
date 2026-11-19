/* Minimal ES2015 polyfills for old Android WebViews (Chrome 37 on Android 5.0).
 * Each one is only installed when the browser lacks it. */
(function () {
  function def(o, name, fn) {
    if (o && !o[name]) {
      try { Object.defineProperty(o, name, { value: fn, writable: true, configurable: true }); }
      catch (e) { o[name] = fn; }
    }
  }
  var typed = ['Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array', 'Uint16Array',
    'Int32Array', 'Uint32Array', 'Float32Array', 'Float64Array'];
  for (var i = 0; i < typed.length; i++) {
    var T = window[typed[i]];
    if (!T) continue;
    def(T.prototype, 'slice', function (b, e) {
      var s = this.subarray(b, e), r = new this.constructor(s.length);
      r.set(s);
      return r;
    });
    def(T.prototype, 'fill', function (v, b, e) {
      var n = this.length;
      b = b == null ? 0 : (b < 0 ? Math.max(n + b, 0) : Math.min(b, n));
      e = e == null ? n : (e < 0 ? Math.max(n + e, 0) : Math.min(e, n));
      for (var k = b; k < e; k++) this[k] = v;
      return this;
    });
    def(T.prototype, 'indexOf', function (v, from) { return Array.prototype.indexOf.call(this, v, from); });
    def(T.prototype, 'forEach', function (f, t) { return Array.prototype.forEach.call(this, f, t); });
    def(T.prototype, 'map', function (f, t) {
      var r = new this.constructor(this.length);
      for (var k = 0; k < this.length; k++) r[k] = f.call(t, this[k], k, this);
      return r;
    });
    def(T.prototype, 'join', function (s) { return Array.prototype.join.call(this, s); });
    def(T.prototype, 'reduce', function () { return Array.prototype.reduce.apply(this, arguments); });
  }
  def(Array.prototype, 'fill', function (v, b, e) {
    var n = this.length;
    b = b == null ? 0 : (b < 0 ? Math.max(n + b, 0) : Math.min(b, n));
    e = e == null ? n : (e < 0 ? Math.max(n + e, 0) : Math.min(e, n));
    for (var k = b; k < e; k++) this[k] = v;
    return this;
  });
  def(Array.prototype, 'find', function (f, t) {
    for (var k = 0; k < this.length; k++) if (f.call(t, this[k], k, this)) return this[k];
  });
  def(Array.prototype, 'findIndex', function (f, t) {
    for (var k = 0; k < this.length; k++) if (f.call(t, this[k], k, this)) return k;
    return -1;
  });
  def(Array.prototype, 'includes', function (v) { return this.indexOf(v) !== -1; });
  def(Array, 'from', function (a, f, t) {
    var r = [], n = a.length >>> 0;
    for (var k = 0; k < n; k++) r.push(f ? f.call(t, a[k], k) : a[k]);
    return r;
  });
  def(Array, 'of', function () { return Array.prototype.slice.call(arguments); });
  def(Object, 'assign', function (t) {
    for (var k = 1; k < arguments.length; k++) {
      var s = arguments[k];
      if (s != null) for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p)) t[p] = s[p];
    }
    return t;
  });
  def(String.prototype, 'startsWith', function (s, p) { p = p || 0; return this.substr(p, s.length) === s; });
  def(String.prototype, 'endsWith', function (s, p) {
    var str = String(this); p = p === undefined ? str.length : p;
    return str.slice(Math.max(0, p - s.length), p) === s;
  });
  def(String.prototype, 'includes', function (s, p) { return this.indexOf(s, p) !== -1; });
  def(String.prototype, 'repeat', function (n) { var r = ''; for (var k = 0; k < n; k++) r += this; return r; });
  def(Math, 'trunc', function (x) { return x < 0 ? Math.ceil(x) : Math.floor(x); });
  def(Math, 'sign', function (x) { x = +x; return x > 0 ? 1 : x < 0 ? -1 : x; });
  def(Math, 'log10', function (x) { return Math.log(x) / Math.LN10; });
  def(Math, 'log2', function (x) { return Math.log(x) / Math.LN2; });
  def(Number, 'isFinite', function (x) { return typeof x === 'number' && isFinite(x); });
  def(Number, 'isNaN', function (x) { return typeof x === 'number' && x !== x; });
  def(Number, 'isInteger', function (x) { return typeof x === 'number' && isFinite(x) && Math.floor(x) === x; });
})();
