// Velo para Android: protección contra huellas digitales y fugas.
// Se inyecta antes que cualquier script de la página, en todos los marcos.
(() => {
  'use strict';
  const SESSION_SEED = __VELO_SEED__;

  const nativeToString = Function.prototype.toString;
  const disguised = new WeakMap();
  const disguise = (fn, name) => { disguised.set(fn, `function ${name}() { [native code] }`); return fn; };
  const toString = function toString() {
    return disguised.has(this) ? disguised.get(this) : nativeToString.call(this);
  };
  disguise(toString, 'toString');
  Function.prototype.toString = toString;

  const fixed = (obj, prop, value) => {
    try {
      Object.defineProperty(obj, prop, { get: disguise(function () { return value; }, `get ${prop}`), configurable: true, enumerable: true });
    } catch (e) { }
  };
  const replace = (obj, name, factory) => {
    if (!obj || typeof obj[name] !== 'function') return;
    const patched = disguise(factory(obj[name]), name);
    try { Object.defineProperty(obj, name, { value: patched, writable: true, configurable: true }); } catch (e) { }
  };
  const remove = (obj, prop) => { try { delete obj[prop]; } catch (e) { } };

  // ---------- WebRTC: eliminado por completo ----------
  // En Android el proxy del WebView no cubre UDP, así que WebRTC podría revelar
  // tu IP real. Se retira la API entera.
  for (const api of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel',
                     'RTCSessionDescription', 'RTCIceCandidate', 'RTCRtpSender', 'RTCRtpReceiver']) {
    remove(window, api);
  }
  if (window.MediaDevices) {
    replace(MediaDevices.prototype, 'enumerateDevices', () => function enumerateDevices() { return Promise.resolve([]); });
  }

  // ---------- Uniformar valores identificativos ----------
  const N = Navigator.prototype;
  fixed(N, 'hardwareConcurrency', 8);
  fixed(N, 'deviceMemory', 4);
  fixed(N, 'language', 'en-US');
  fixed(N, 'languages', Object.freeze(['en-US', 'en']));
  fixed(N, 'maxTouchPoints', 5);
  fixed(N, 'doNotTrack', null);
  fixed(N, 'globalPrivacyControl', true);
  for (const api of ['getBattery', 'connection', 'requestMIDIAccess', 'bluetooth', 'usb', 'hid', 'serial', 'keyboard', 'getInstalledRelatedApps']) remove(N, api);
  replace(N, 'getGamepads', () => function getGamepads() { return []; });

  const S = Screen.prototype;
  fixed(S, 'width', 412);
  fixed(S, 'height', 915);
  fixed(S, 'availWidth', 412);
  fixed(S, 'availHeight', 915);
  fixed(S, 'colorDepth', 24);
  fixed(S, 'pixelDepth', 24);

  if (window.speechSynthesis) {
    replace(SpeechSynthesis.prototype, 'getVoices', () => function getVoices() { return []; });
  }

  // ---------- Zona horaria UTC ----------
  const DP = Date.prototype;
  for (const unit of ['FullYear', 'Month', 'Date', 'Day', 'Hours', 'Minutes', 'Seconds', 'Milliseconds']) {
    const getUTC = DP['getUTC' + unit];
    replace(DP, 'get' + unit, () => function () { return getUTC.call(this); });
    if (unit !== 'Day') {
      const setUTC = DP['setUTC' + unit];
      replace(DP, 'set' + unit, () => function (...a) { return setUTC.apply(this, a); });
    }
  }
  replace(DP, 'getTimezoneOffset', () => function getTimezoneOffset() { return 0; });
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const p2 = (n) => String(n).padStart(2, '0');
  const getTime = DP.getTime;
  const dateStr = (d) => `${DAYS[d.getUTCDay()]} ${MONTHS[d.getUTCMonth()]} ${p2(d.getUTCDate())} ${String(d.getUTCFullYear()).padStart(4, '0')}`;
  const timeStr = (d) => `${p2(d.getUTCHours())}:${p2(d.getUTCMinutes())}:${p2(d.getUTCSeconds())} GMT+0000 (Coordinated Universal Time)`;
  replace(DP, 'toString', () => function toString() { return isNaN(getTime.call(this)) ? 'Invalid Date' : `${dateStr(this)} ${timeStr(this)}`; });
  replace(DP, 'toDateString', () => function toDateString() { return isNaN(getTime.call(this)) ? 'Invalid Date' : dateStr(this); });
  replace(DP, 'toTimeString', () => function toTimeString() { return isNaN(getTime.call(this)) ? 'Invalid Date' : timeStr(this); });
  for (const m of ['toLocaleString', 'toLocaleDateString', 'toLocaleTimeString']) {
    replace(DP, m, (orig) => function (locales, options) { return orig.call(this, locales, Object.assign({ timeZone: 'UTC' }, options)); });
  }
  const OrigDTF = Intl.DateTimeFormat;
  const DateTimeFormat = disguise(function DateTimeFormat(locales, options) {
    return new OrigDTF(locales, Object.assign({ timeZone: 'UTC' }, options));
  }, 'DateTimeFormat');
  DateTimeFormat.prototype = OrigDTF.prototype;
  DateTimeFormat.supportedLocalesOf = OrigDTF.supportedLocalesOf;
  try { Object.defineProperty(Intl, 'DateTimeFormat', { value: DateTimeFormat, writable: true, configurable: true }); } catch (e) { }

  // ---------- Ruido por sitio y sesión (canvas y audio) ----------
  let h = SESSION_SEED >>> 0;
  for (const ch of location.hostname) h = Math.imul(h ^ ch.charCodeAt(0), 2654435761) >>> 0;
  const SITE_SEED = h;
  const prng = (seed) => () => {
    seed = (seed + 0x6D2B79F5) >>> 0;
    let t = seed;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const noisePixels = (data) => {
    const rnd = prng(SITE_SEED);
    for (let i = 0; i < data.length; i += 4) {
      if (rnd() < 0.02) { const c = (rnd() * 3) | 0; data[i + c] ^= 1; }
    }
  };
  const ctx2d = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  const originalGetImageData = ctx2d && ctx2d.getImageData;
  const patchGetImageData = (orig) => function getImageData(...args) {
    const img = orig.apply(this, args);
    noisePixels(img.data);
    return img;
  };
  replace(ctx2d, 'getImageData', patchGetImageData);
  if (window.OffscreenCanvasRenderingContext2D) replace(OffscreenCanvasRenderingContext2D.prototype, 'getImageData', patchGetImageData);
  const noisyCopy = (canvas) => {
    try {
      const w = canvas.width, hh = canvas.height;
      if (!w || !hh || !originalGetImageData) return canvas;
      const copy = document.createElement('canvas');
      copy.width = w; copy.height = hh;
      const ctx = copy.getContext('2d');
      ctx.drawImage(canvas, 0, 0);
      const img = originalGetImageData.call(ctx, 0, 0, w, hh);
      noisePixels(img.data);
      ctx.putImageData(img, 0, 0);
      return copy;
    } catch (e) { return canvas; }
  };
  const canvasProto = window.HTMLCanvasElement && HTMLCanvasElement.prototype;
  replace(canvasProto, 'toDataURL', (orig) => function toDataURL(...a) { return orig.apply(noisyCopy(this), a); });
  replace(canvasProto, 'toBlob', (orig) => function toBlob(...a) { return orig.apply(noisyCopy(this), a); });

  const touched = new WeakSet();
  if (window.AudioBuffer) {
    replace(AudioBuffer.prototype, 'getChannelData', (orig) => function getChannelData(...args) {
      const data = orig.apply(this, args);
      if (!touched.has(data)) {
        touched.add(data);
        const rnd = prng(SITE_SEED ^ 0xA5A5A5A5);
        for (let i = 0; i < data.length; i += 97) data[i] += (rnd() - 0.5) * 1e-7;
      }
      return data;
    });
  }
  if (window.AnalyserNode) {
    replace(AnalyserNode.prototype, 'getFloatFrequencyData', (orig) => function getFloatFrequencyData(array) {
      orig.call(this, array);
      const rnd = prng(SITE_SEED ^ 0x5A5A5A5A);
      for (let i = 0; i < array.length; i++) array[i] += (rnd() - 0.5) * 1e-4;
    });
  }
})();
