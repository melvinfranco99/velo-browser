// Velo Shield: cancela las peticiones de terceros a dominios de rastreo,
// publicidad y malware (un dominio bloquea también todos sus subdominios).
'use strict';

const IGNORED = new Set(['0.0.0.0', '127.0.0.1', 'localhost', 'localhost.localdomain', 'local', 'broadcasthost']);
const domains = new Set();
let blocked = 0;
let reported = 0;

async function load(file) {
  const text = await (await fetch(browser.runtime.getURL(file))).text();
  for (const raw of text.split('\n')) {
    const line = raw.split('#', 1)[0].trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    const domain = ((parts.length >= 2 && (parts[0] === '0.0.0.0' || parts[0] === '127.0.0.1')) ? parts[1] : parts[0])
      .toLowerCase().replace(/\.$/, '');
    if (domain.includes('.') && !IGNORED.has(domain)) domains.add(domain);
  }
}

function isBlocked(host) {
  let h = host.toLowerCase().replace(/\.$/, '');
  for (;;) {
    if (domains.has(h)) return true;
    const dot = h.indexOf('.');
    if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false;
    h = h.slice(dot + 1);
  }
}

browser.webRequest.onBeforeRequest.addListener((details) => {
  // Las páginas que tú abres se cargan; sus recursos de rastreo, no.
  if (details.type === 'main_frame') return {};
  try {
    if (isBlocked(new URL(details.url).hostname)) {
      blocked++;
      return { cancel: true };
    }
  } catch (e) { /* URL no válida */ }
  return {};
}, { urls: ['<all_urls>'] }, ['blocking']);

// Informa a la app del contador (como mucho cada 2 segundos).
setInterval(() => {
  if (blocked !== reported) {
    reported = blocked;
    browser.runtime.sendNativeMessage('velo', { blocked }).catch(() => {});
  }
}, 2000);

Promise.all([load('blocklist.txt'), load('hosts.txt')])
  .then(() => browser.runtime.sendNativeMessage('velo', { domains: domains.size }).catch(() => {}));
