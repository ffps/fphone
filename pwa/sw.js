const CACHE = 'kukukha-pwa-v28';
const FILES = ['./', './index.html', './app.css', './app.js', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(FILES)));
  self.skipWaiting();
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key)))));
  self.clients.claim();
});
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/') || url.pathname === '/ws') return;
  event.respondWith(fetch(event.request).then(response => {
    if (!response.ok || !url.pathname.startsWith('/pwa/')) return response;
    const copy = response.clone();
    return caches.open(CACHE)
      .then(cache => cache.put(event.request, copy))
      .catch(error => console.warn('PWA cache:', error))
      .then(() => response);
  }).catch(() => caches.match(event.request).then(cached => cached || (event.request.mode === 'navigate' ? caches.match('./index.html') : Response.error()))));
});



const PUSH_DB = 'kukukha-push';
const PUSH_STORE = 'pending';

function openPushDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(PUSH_DB, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(PUSH_STORE, {keyPath: 'call'});
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readPushRecord(call) {
  const db = await openPushDB();
  const value = await new Promise((resolve, reject) => {
    const request = db.transaction(PUSH_STORE).objectStore(PUSH_STORE).get(call);
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error);
  });
  db.close();
  return value;
}

async function writePushRecord(record) {
  const db = await openPushDB();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(PUSH_STORE, 'readwrite');
    tx.objectStore(PUSH_STORE).put(record);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
  db.close();
}

async function deletePushRecord(call) {
  const db = await openPushDB();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(PUSH_STORE, 'readwrite');
    tx.objectStore(PUSH_STORE).delete(call);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
  db.close();
}

// Show incoming call alerts while the PWA is in the background.
self.addEventListener('push', event => {
  if (!event.data) return;
  let data;
  try {
    data = event.data.json();
  } catch (_) {
    return;
  }
  event.waitUntil((async () => {
    if (data.type === 'call.cancelled') {
      const notes = await self.registration.getNotifications({tag: 'call-' + data.call});
      notes.forEach(note => note.close());
      try {
        await writePushRecord({call: data.call, cancelled: true, exp: Math.floor(Date.now() / 1000) + 86400});
      } catch (error) {
        console.warn('Push queue:', error);
      }
      return;
    }
    if (data.type === 'message') {
      const timestamp = Number(data.time) || Date.now();
      await self.registration.showNotification(data.title || 'Сообщение', {
        body: data.body || data.text || '',
        icon: './icon-192.png',
        badge: './icon-192.png',
        tag: 'message-' + timestamp,
        renotify: true,
        silent: false,
        timestamp,
        data: {type: 'message'}
      });
      if (data.message_id && data.ack_token) {
        try {
          const response = await fetch('/api/v1/messages/ack', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({message_id: data.message_id, ack_token: data.ack_token})
          });
          if (!response.ok) console.warn('Message delivery acknowledgement:', response.status);
        } catch (error) {
          console.warn('Message delivery acknowledgement:', error);
        }
      }
      return;
    }
    if (data.type === 'message.delivered') {
      const timestamp = Number(data.time) || Date.now();
      await self.registration.showNotification(data.title || 'Кукуха', {
        body: data.body || data.text || '',
        icon: './icon-192.png',
        badge: './icon-192.png',
        tag: 'message-delivered-' + timestamp,
        renotify: true,
        silent: false,
        timestamp,
        data: {type: 'message.delivered'}
      });
      return;
    }
    if (data.type !== 'call.incoming' || !data.call) return;
    if (Number(data.exp) > 0 && Number(data.exp) * 1000 <= Date.now()) return;
    try {
      const old = await readPushRecord(data.call);
      if (old?.cancelled) return;
      await writePushRecord(data);
    } catch (error) {
      console.warn('Push queue:', error);
    }
    await self.registration.showNotification(data.title || 'Входящий вызов', {
      body: data.body || 'Откройте Кукуху, чтобы ответить',
      icon: './icon-192.png',
      badge: './icon-192.png',
      tag: 'call-' + data.call,
      renotify: true,
      requireInteraction: true,
      silent: false,
      vibrate: [400, 200, 400, 800],
      timestamp: Date.now(),
      data: {call: data.call}
    });
    if (data.ack_token) {
      let failure = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          const response = await fetch('/api/v1/calls/ack', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({call_id: data.call, ack_token: data.ack_token})
          });
          if (!response.ok) throw new Error('HTTP ' + response.status);
          failure = null;
          break;
        } catch (error) {
          failure = error;
          if (attempt < 2) await new Promise(resolve => setTimeout(resolve, 1000 * (attempt + 1)));
        }
      }
      if (failure) console.warn('Call push acknowledgement:', failure);
    }
  })());
});

self.addEventListener('notificationclick', event => {
  event.notification.close();
  const call = event.notification.data?.call;
  const url = new URL('./', self.location);
  if (call) url.searchParams.set('wake', call);
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({type: 'window', includeUncontrolled: true});
    for (const client of windows) {
      if ('focus' in client) {
        if ('navigate' in client) await client.navigate(url.href);
        return client.focus();
      }
    }
    return self.clients.openWindow(url.href);
  })());
});
