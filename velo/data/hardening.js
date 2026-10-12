// Velo: protección contra huellas digitales (fingerprinting).
// Se inyecta antes que cualquier script de la página, en todos los marcos.
// Estrategia doble:
//  1) Uniformar: valores comunes y fijos (núcleos, memoria, pantalla, idioma).
//  2) Aleatorizar por sitio y sesión: ruido imperceptible en canvas y audio,
//     distinto para cada sitio y para cada "Nueva identidad", de modo que dos
//     sitios no obtienen la misma huella y no pueden relacionarte.
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
      const getter = disguise(function () { return value; }, `get ${prop}`);
      Object.defineProperty(obj, prop, { get: getter, configurable: true, enumerable: true });
    } catch (e) { /* propiedad no configurable */ }
  };
  const dynamic = (obj, prop, fn) => {
    try {
      Object.defineProperty(obj, prop, { get: disguise(fn, `get ${prop}`), configurable: true, enumerable: true });
    } catch (e) { }
  };
  const replace = (proto, name, factory) => {
    if (!proto || typeof proto[name] !== 'function') return;
    const original = proto[name];
    const patched = factory(original);
    disguise(patched, name);
    try { Object.defineProperty(proto, name, { value: patched, writable: true, configurable: true }); } catch (e) { }
  };
  const remove = (obj, prop) => { try { delete obj[prop]; } catch (e) { } };

  // ---------- 1) Uniformar ----------
  const N = Navigator.prototype;
  fixed(N, 'hardwareConcurrency', 4);
  fixed(N, 'deviceMemory', 8);
  fixed(N, 'language', 'en-US');
  fixed(N, 'languages', Object.freeze(['en-US', 'en']));
  fixed(N, 'maxTouchPoints', 0);
  fixed(N, 'doNotTrack', null);
  fixed(N, 'globalPrivacyControl', true);
  for (const api of ['getBattery', 'connection', 'requestMIDIAccess', 'bluetooth', 'usb', 'hid', 'serial', 'keyboard']) remove(N, api);
  replace(N, 'getGamepads', () => function getGamepads() { return []; });

  const S = Screen.prototype;
  fixed(S, 'width', 1920);
  fixed(S, 'height', 1080);
  fixed(S, 'availWidth', 1920);
  fixed(S, 'availHeight', 1080);
  fixed(S, 'availLeft', 0);
  fixed(S, 'availTop', 0);
  fixed(S, 'colorDepth', 24);
  fixed(S, 'pixelDepth', 24);
  dynamic(window, 'outerWidth', () => window.innerWidth);
  dynamic(window, 'outerHeight', () => window.innerHeight);
  fixed(window, 'screenX', 0);
  fixed(window, 'screenY', 0);
  fixed(window, 'screenLeft', 0);
  fixed(window, 'screenTop', 0);

  if (window.speechSynthesis) {
    replace(SpeechSynthesis.prototype, 'getVoices', () => function getVoices() { return []; });
  }

  // ---------- 2) Ruido por sitio y sesión ----------
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
      if (rnd() < 0.02) {
        const c = (rnd() * 3) | 0;
        data[i + c] ^= 1;  // cambia el bit menos significativo de un canal
      }
    }
  };

  const ctx2d = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  const originalGetImageData = ctx2d && ctx2d.getImageData;
  replace(ctx2d, 'getImageData', (orig) => function getImageData(...args) {
    const img = orig.apply(this, args);
    noisePixels(img.data);
    return img;
  });
  if (window.OffscreenCanvasRenderingContext2D) {
    replace(OffscreenCanvasRenderingContext2D.prototype, 'getImageData', (orig) => function getImageData(...args) {
      const img = orig.apply(this, args);
      noisePixels(img.data);
      return img;
    });
  }

  // toDataURL / toBlob: se exporta una copia con ruido; el canvas original no se toca.
  const noisyCopy = (canvas) => {
    try {
      const w = canvas.width, h = canvas.height;
      if (!w || !h || !originalGetImageData) return canvas;
      const copy = document.createElement('canvas');
      copy.width = w; copy.height = h;
      const ctx = copy.getContext('2d');
      ctx.drawImage(canvas, 0, 0);
      const img = originalGetImageData.call(ctx, 0, 0, w, h);
      noisePixels(img.data);
      ctx.putImageData(img, 0, 0);
      return copy;
    } catch (e) { return canvas; }
  };
  const canvasProto = window.HTMLCanvasElement && HTMLCanvasElement.prototype;
  replace(canvasProto, 'toDataURL', (orig) => function toDataURL(...args) {
    return orig.apply(noisyCopy(this), args);
  });
  replace(canvasProto, 'toBlob', (orig) => function toBlob(...args) {
    return orig.apply(noisyCopy(this), args);
  });

  // Audio: ruido de amplitud ínfima (inaudible) en los datos leídos.
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
