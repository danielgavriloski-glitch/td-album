const CACHE_PREFIX = 'td-employee-pwa-';
const CACHE = CACHE_PREFIX + 'v1.2';
const CORE = ['./index.html', './manifest.webmanifest', '../icon-192.png', '../icon-512.png'];
const INDEX = new URL('./index.html', self.registration.scope).href;
const MANIFEST = new URL('./manifest.webmanifest', self.registration.scope).href;

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  const inApp = url.href.startsWith(self.registration.scope);
  if (!inApp && !CORE.some(path => new URL(path, self.registration.scope).href === url.href)) return;
  const navigation = request.mode === 'navigate' && inApp;
  const currentFile = url.pathname === new URL(INDEX).pathname || url.pathname === new URL(MANIFEST).pathname;
  if (navigation || currentFile) {
    const cacheKey = navigation || url.pathname === new URL(INDEX).pathname ? INDEX : MANIFEST;
    event.respondWith((async () => {
      const cache = await caches.open(CACHE);
      try {
        const response = await fetch(request, {cache: 'no-store'});
        if (response.ok) await cache.put(cacheKey, response.clone());
        return response;
      } catch (error) {
        const saved = await cache.match(cacheKey);
        if (saved) return saved;
        throw error;
      }
    })());
    return;
  }
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const saved = await cache.match(request);
    if (saved) return saved;
    const response = await fetch(request);
    if (response.ok) await cache.put(request, response.clone());
    return response;
  })());
});

