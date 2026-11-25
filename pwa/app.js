'use strict';

const $ = id => document.getElementById(id);
const ICON_CALL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2a1 1 0 0 1 1.02-.24c1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C11.61 21 4 13.39 4 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02z"/></svg>';
const ICON_MIC = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5.3-3h1.7a7 7 0 0 1-6 6.72V21h-2v-3.28A7 7 0 0 1 5 11h1.7a5.3 5.3 0 0 0 10.6 0z"/></svg>';
const ICON_MIC_OFF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23c.56-.98.9-2.09.9-3.28zM14.98 11.17 9 5.18V5a3 3 0 0 1 5.98-.17v6.17c0 .06 0 .11-.02.17zM4.27 3 3 4.27l6.01 6.01V11a3 3 0 0 0 3.64 2.92l1.66 1.66a5.3 5.3 0 0 1-8.61-4.58H5a7 7 0 0 0 6 6.72V21h2v-3.28c.91-.13 1.77-.45 2.54-.9L19.73 21 21 19.73 4.27 3z"/></svg>';
const ICON_CAMERA = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11z"/></svg>';
const ICON_CAMERA_OFF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 6.5-4 4V7a1 1 0 0 0-1-1H9.82L21 17.18zM3.27 2 2 3.27 4.73 6H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12c.21 0 .39-.08.54-.18L19.73 21 21 19.73z"/></svg>';
const ICON_CAMERA_SWITCH = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7h9.17l-2.58-2.59L15 3l5 5-5 5-1.41-1.41L16.17 9H7a3 3 0 0 0-3 3v1H2v-1a5 5 0 0 1 5-5zm10 10H7.83l2.58 2.59L9 21l-5-5 5-5 1.41 1.41L7.83 15H17a3 3 0 0 0 3-3v-1h2v1a5 5 0 0 1-5 5z"/></svg>';
const ICON_SPEAKER = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 9v6h4l5 4V5L7 9H3zm13.5 3a3.5 3.5 0 0 0-2-3.16v6.32a3.5 3.5 0 0 0 2-3.16zm-2-8.5v2.06a7 7 0 0 1 0 12.88v2.06a9 9 0 0 0 0-17z"/></svg>';
const ICON_HANGUP = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08a1 1 0 0 1 0-1.41C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67a1 1 0 0 1 0 1.42l-2.48 2.48a1 1 0 0 1-1.41.01c-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1A16 16 0 0 0 12 9z"/></svg>';
const state = {
  token: localStorage.getItem('kukukha.token') || '',
  deviceId: localStorage.getItem('kukukha.device') || makeId(),
  user: null, contacts: [], online: new Set(), ws: null, reconnectTimer: 0,
  call: null, pc: null, localStream: null, remoteStream: null,
  cameraDevices: [], cameraDeviceId: '', cameraFacingMode: 'user',
  audioOutputs: [], speakerDeviceId: '', speakerAvailable: false, speakerOn: false,
  pendingCandidates: [], muted: false, cameraOn: false, selectedAvatar: null,
  selectedContact: null,
  wakeCall: new URLSearchParams(location.search).get('wake') || ''
};
localStorage.setItem('kukukha.device', state.deviceId);

let callToneAudio = null;
let callToneMode = '';
let callToneRequestedMode = '';
let callToneUrls = null;
let callToneUnlocking = false;
let callToneUnlocked = false;
let callToneManualStop = false;
let callToneStopTimer = 0;

function makeToneWavUrl(mode) {
  const sampleRate = 22050;
  const duration = mode === 'incoming' ? 6 : 4;
  const samples = sampleRate * duration;
  const buffer = new ArrayBuffer(44 + samples * 2);
  const view = new DataView(buffer);
  const writeText = (offset, value) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)); };
  writeText(0, 'RIFF'); view.setUint32(4, 36 + samples * 2, true); writeText(8, 'WAVE');
  writeText(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, sampleRate, true); view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true); view.setUint16(34, 16, true); writeText(36, 'data'); view.setUint32(40, samples * 2, true);
  for (let i = 0; i < samples; i++) {
    const t = i / sampleRate;
    const cycle = mode === 'incoming' ? 6 : mode === 'busy' ? 1 : 4;
    const onDuration = mode === 'incoming' ? 2 : mode === 'busy' ? 0.5 : 1;
    const phase = t % cycle;
    let sample = 0;
    if (phase < onDuration) {
      const edge = Math.min(1, phase / 0.008, (onDuration - phase) / 0.008);
      const envelope = Math.max(0, edge);
      const tone = mode === 'incoming'
        ? (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)) * 0.10
        : mode === 'busy'
          ? (Math.sin(2 * Math.PI * 480 * t) + Math.sin(2 * Math.PI * 620 * t)) * 0.12
          : Math.sin(2 * Math.PI * 440 * t) * 0.20;
      sample = tone * envelope;
    }
    view.setInt16(44 + i * 2, Math.max(-1, Math.min(1, sample)) * 32767, true);
  }
  return URL.createObjectURL(new Blob([buffer], {type:'audio/wav'}));
}

function ensureCallToneAudio() {
  if (callToneAudio) return callToneAudio;
  callToneAudio = document.createElement('audio');
  callToneAudio.preload = 'auto';
  callToneAudio.loop = true;
  callToneAudio.volume = 0.35;
  callToneAudio.setAttribute('playsinline', '');
  callToneAudio.playsInline = true;
  callToneAudio.style.display = 'none';
  (document.body || document.documentElement).appendChild(callToneAudio);
  callToneUrls = {ringback:makeToneWavUrl('ringback'), incoming:makeToneWavUrl('incoming'), busy:makeToneWavUrl('busy')};
  return callToneAudio;
}

function unlockCallAudio(fromGesture = false) {
  const audio = ensureCallToneAudio();
  if (!fromGesture) return Promise.resolve(callToneUnlocked);
  // Once unlocked, never replace the active ringback/incoming source on later clicks.
  if (callToneUnlocked) {
    return Promise.resolve(true);
  }
  if (callToneUnlocking) return Promise.resolve(callToneUnlocked);
  callToneUnlocking = true;
  // Play a silent WAV from the actual user gesture to unlock this media element.
  if (!callToneUrls.silence) {
    const sampleRate = 8000;
    const buffer = new ArrayBuffer(44 + 800);
    const view = new DataView(buffer);
    const put = (offset, value) => { for (let i = 0; i < value.length; i++) view.setUint8(offset + i, value.charCodeAt(i)); };
    put(0,'RIFF'); view.setUint32(4,836,true); put(8,'WAVE'); put(12,'fmt '); view.setUint32(16,16,true);
    view.setUint16(20,1,true); view.setUint16(22,1,true); view.setUint32(24,sampleRate,true); view.setUint32(28,sampleRate*2,true);
    view.setUint16(32,2,true); view.setUint16(34,16,true); put(36,'data'); view.setUint32(40,800,true);
    callToneUrls.silence = URL.createObjectURL(new Blob([buffer], {type:'audio/wav'}));
  }
  callToneManualStop = true;
  audio.src = callToneUrls.silence;
  audio.loop = false;
  audio.currentTime = 0;
  const promise = audio.play();
  if (!promise || typeof promise.then !== 'function') {
    callToneUnlocked = true; callToneUnlocking = false; audio.pause();
    return Promise.resolve(true);
  }
  return promise.then(() => {
    callToneUnlocked = true;
    // Pause only if the element still contains the silent unlock clip.
    // A call tone may already have replaced it while play() was resolving.
    if (audio.src === callToneUrls.silence) {
      audio.pause();
      audio.currentTime = 0;
    }
    return true;
  }).catch(() => {
    return false;
  }).finally(() => {
    callToneUnlocking = false;
    callToneManualStop = false;
  });
}

document.addEventListener('pointerdown', () => unlockCallAudio(true), {passive:true});
document.addEventListener('keydown', () => unlockCallAudio(true));

function playCallTone(mode) {
  if (!callToneRequestedMode || callToneRequestedMode !== mode) return;
  const audio = ensureCallToneAudio();
  if (callToneMode === mode && !audio.paused) {
    return;
  }
  try {
    callToneMode = mode;
    callToneManualStop = false;
    audio.src = callToneUrls[mode];
    audio.loop = true;
    audio.currentTime = 0;
    const result = audio.play();
    if (result && typeof result.then === 'function') {
      result.catch(() => {});
    }
  } catch (_) {
  }
}

function stopCallTone() {
  clearTimeout(callToneStopTimer);
  callToneStopTimer = 0;
  callToneRequestedMode = '';
  callToneMode = '';
  if (callToneAudio) {
    callToneManualStop = true;
    callToneAudio.pause();
    try { callToneAudio.currentTime = 0; } catch (_) {}
    callToneManualStop = false;
  }
}

function startCallTone(mode) {
  clearTimeout(callToneStopTimer);
  callToneStopTimer = 0;
  callToneRequestedMode = mode;
  playCallTone(mode);
}

function makeId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
}

function show(id) { $(id).classList.remove('hidden'); }

function hide(id) { $(id).classList.add('hidden'); }

function toast(message) {
  $('toast').textContent = message;
  show('toast');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => hide('toast'), 3000);
}

function setConnection(text, kind = '') {
  $('connection-state').textContent = text;
  $('connection-state').className = 'connection ' + kind;
}

// Send an authenticated API request.
async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (state.token) headers.set('Authorization', 'Bearer ' + state.token);
  if (options.body && !(options.body instanceof Blob) && !(options.body instanceof ArrayBuffer)) headers.set('Content-Type', 'application/json');
  const response = await fetch('/api/v1' + path, {...options, headers});
  if (response.status === 401) {
    logoutLocal();
    throw new Error('Сеанс завершён. Войдите снова.');
  }
  const type = response.headers.get('content-type') || '';
  const data = type.includes('application/json') ? await response.json() : null;
  if (!response.ok) throw new Error(data?.error?.code || `Ошибка сервера (${response.status})`);
  return data;
}

async function loadAvatar(id, version, image) {
  try {
    const response = await fetch(`/api/v1/users/${id}/avatar?v=${version || 0}`, {headers:{Authorization:'Bearer ' + state.token}});
    if (!response.ok) { image.src = 'icon-192.png'; return; }
    const blob = await response.blob();
    image.src = URL.createObjectURL(blob);
  } catch (_) { image.src = 'icon-192.png'; }
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = $('login-form').querySelector('button');
  button.disabled = true;
  $('login-error').textContent = '';
  try {
    const result = await fetch('/api/v1/login', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body:JSON.stringify({login:$('login').value.trim(), password:$('password').value, device:{id:state.deviceId,name:deviceName()}})
    });
    const data = await result.json();
    if (!result.ok) throw new Error(data?.error?.code || 'Не удалось войти');
    state.token = data.token;
    state.user = data.user;
    localStorage.setItem('kukukha.token', state.token);
    $('password').value = '';
    enterApp();
  } catch (error) { $('login-error').textContent = loginError(error.message); }
  finally { button.disabled = false; }
});

function deviceName() {
  const ua = navigator.userAgent;
  if (/iPhone/i.test(ua)) return 'iPhone';
  if (/iPad/i.test(ua)) return 'iPad';
  return 'Браузер';
}

function loginError(code) {
  const errors = {invalid_credentials:'Неверный логин или пароль',rate_limited:'Слишком много попыток. Попробуйте позже.',network:'Нет связи с сервером',bad_request:'Проверьте логин и пароль'};
  return errors[code] || code;
}

function enterApp() {
  hide('login-screen'); show('main-screen');
  $('top-title').textContent = 'Контакты';
  loadProfile(); loadContacts(); connectSocket(); renderHistory(); updatePushStatus(); syncExistingPushSubscription(); syncPendingPushHistory();
}

// Keep each account's call history on this browser only.
function historyKey() {
  return 'kukukha.history.' + (state.user?.id || 'unknown');
}

function loadHistory() {
  try {
    const rows = JSON.parse(localStorage.getItem(historyKey()) || '[]');
    return Array.isArray(rows) ? rows : [];
  } catch (_) {
    return [];
  }
}

// Read calls received by the Service Worker while this page was closed.
async function syncPendingPushHistory() {
  if (!state.user?.id || !('indexedDB' in window)) return;
  try {
    const db = await openPendingPushDB();
    const pending = await new Promise((resolve, reject) => {
      const request = db.transaction('pending').objectStore('pending').getAll();
      request.onsuccess = () => resolve(request.result || []);
      request.onerror = () => reject(request.error);
    });
    const rows = loadHistory();
    let changed = false;
    for (const item of pending) {
      if (item.cancelled || Number(item.to_id) !== Number(state.user.id)) {
        await deletePendingPush(db, item.call);
        changed = true;
        continue;
      }
      if (Number(item.exp) * 1000 > Date.now()) continue;
      if (!rows.some(row => row.id === item.call)) {
        rows.unshift({
          id: item.call,
          peerId: Number(item.from_id) || 0,
          peerName: item.from_name || 'Абонент',
          peerLogin: '',
          outgoing: false,
          media: item.media || 'audio',
          reason: 'no_answer',
          startedAt: Number(item.started_at || item.exp) * 1000,
          endedAt: Date.now(),
          duration: 0
        });
        changed = true;
      }
      await deletePendingPush(db, item.call);
    }
    db.close();
    if (changed) {
      localStorage.setItem(historyKey(), JSON.stringify(rows.slice(0, 100)));
      renderHistory();
      renderContacts();
    }
  } catch (error) {
    console.warn('Pending push history:', error);
  }
}

function openPendingPushDB() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('kukukha-push', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('pending', {keyPath: 'call'});
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function deletePendingPush(db, call) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction('pending', 'readwrite');
    tx.objectStore('pending').delete(call);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

async function clearPendingPush(call) {
  if (!call || !('indexedDB' in window)) return;
  try {
    const db = await openPendingPushDB();
    await deletePendingPush(db, call);
    db.close();
  } catch (_) {}
}

function saveHistory(call, reason) {
  if (!call || call.historyRecorded) return;
  call.historyRecorded = true;
  clearPendingPush(call.id);
  const rows = loadHistory();
  rows.unshift({
    id: call.id || makeId(),
    peerId: call.peerId,
    peerName: call.peerName || 'Абонент',
    peerLogin: call.peerLogin || '',
    outgoing: !!call.outgoing,
    media: call.media || 'audio',
    reason: reason || 'hangup',
    startedAt: call.startedAt || Date.now(),
    endedAt: Date.now(),
    duration: call.connectedAt ? Math.max(0, Math.floor((Date.now() - call.connectedAt) / 1000)) : 0
  });
  localStorage.setItem(historyKey(), JSON.stringify(rows.slice(0, 100)));
  renderHistory();
  renderContacts();
}

function renderHistory() {
  const list = $('history-list');
  if (!list) return;
  list.replaceChildren();
  const rows = loadHistory();
  $('history-empty').classList.toggle('hidden', rows.length > 0);
  for (const item of rows) {
    const row = document.createElement('div');
    row.className = 'history-item';
    const icon = document.createElement('span');
    icon.className = 'history-direction ' + (item.reason === 'missed' || item.reason === 'no_answer' && !item.outgoing ? 'missed' : item.outgoing ? 'outgoing' : 'incoming');
    icon.textContent = item.outgoing ? '↗' : '↙';
    const info = document.createElement('div');
    info.className = 'history-info';
    const name = document.createElement('div');
    name.className = 'contact-name';
    name.textContent = item.peerName || item.peerLogin || 'Абонент';
    const detail = document.createElement('div');
    detail.className = 'contact-status';
    const kind = item.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
    detail.textContent = historyResult(item) + ' · ' + kind + ' · ' + formatHistoryDate(item.startedAt);
    info.append(name, detail);
    const duration = document.createElement('span');
    duration.className = 'history-duration';
    duration.textContent = item.duration ? formatDuration(item.duration) : '';
    row.append(icon, info, duration);
    if (Number(item.peerId) > 0) {
      const call = document.createElement('button');
      call.className = 'history-call';
      call.type = 'button';
      call.innerHTML = ICON_CALL;
      call.title = item.media === 'video' ? 'Повторить видеозвонок' : 'Повторить аудиозвонок';
      call.setAttribute('aria-label', call.title);
      call.addEventListener('click', () => startCall({
        id: Number(item.peerId),
        name: item.peerName || item.peerLogin || 'Абонент',
        login: item.peerLogin || ''
      }, item.media === 'video' ? 'video' : 'audio'));
      row.append(call);
    }
    list.append(row);
  }
}

function historyResult(item) {
  if (item.reason === 'missed' || item.reason === 'no_answer' && !item.outgoing) return 'Пропущен';
  if (item.reason === 'rejected' || item.reason === 'declined') return 'Отклонён';
  if (item.reason === 'no_answer') return 'Нет ответа';
  if (item.reason === 'busy') return 'Занято';
  if (item.reason === 'offline') return 'Недоступен';
  if (item.reason === 'cancelled') return 'Отменён';
  if (item.reason === 'answered_elsewhere') return 'Ответили на другом устройстве';
  return item.outgoing ? 'Исходящий' : 'Входящий';
}

function formatHistoryDate(timestamp) {
  const date = new Date(timestamp);
  const today = new Date();
  const yesterday = new Date();
  yesterday.setDate(today.getDate() - 1);
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const label = sameDay(date, today) ? 'Сегодня' : sameDay(date, yesterday) ? 'Вчера' : date.toLocaleDateString('ru-RU', {day:'2-digit',month:'2-digit'});
  return label + ', ' + date.toLocaleTimeString('ru-RU', {hour:'2-digit',minute:'2-digit'});
}

function formatDuration(seconds) {
  const minutes = Math.floor(seconds / 60);
  return minutes ? minutes + ':' + String(seconds % 60).padStart(2, '0') : seconds + ' с';
}

function showMainTab(tab) {
  const history = tab === 'history';
  if (history) {
   show('history-view');
   show('clear-history');
   hide('contacts-view');
   hide('refresh-contacts');
   $('tab-history').classList.add('selected');
   $('tab-contacts').classList.remove('selected');
   $('top-title').textContent('Недавние');
   renderHistory();
  }
  else {
   show('contacts-view');
   show('refresh-contacts');
   hide('history-view');
   hide('clear-history');
   $('tab-history').classList.remove('selected');
   $('tab-contacts').classList.add('selected');
   $('top-title').textContent('Контакты');
  }
}

$('tab-contacts').addEventListener('click', () => showMainTab('contacts'));

$('tab-history').addEventListener('click', () => showMainTab('history'));

$('clear-history').addEventListener('click', () => {
  if (!confirm('Очистить историю звонков?')) return;
  localStorage.removeItem(historyKey());
  renderHistory();
});

function logoutLocal() {
  endCall(false, 'cancelled');
  state.token = ''; state.user = null;
  localStorage.removeItem('kukukha.token');
  if (state.ws) state.ws.close();
  state.ws = null;
  hide('main-screen'); hide('call-screen'); show('login-screen');
}

$('logout').addEventListener('click', async () => {
  try { await api('/logout', {method:'POST'}); } catch (_) {}
  logoutLocal();
});

// Load the current contact list from the server.
async function loadContacts() {
  try {
    const data = await api('/contacts');
    state.contacts = data.contacts || [];
    renderContacts();
  } catch (error) { toast(error.message); }
}

$('refresh-contacts').addEventListener('click', loadContacts);
function renderContacts() {
  const list = $('contacts-list'); list.replaceChildren();
  const visibleContacts = state.contacts.filter(contact => state.online.has(Number(contact.id)) || contact.online || contact.reachable);
  const latestCalls = new Map();
  for (const item of loadHistory()) {
    const peerId = Number(item.peerId);
    if (peerId && !latestCalls.has(peerId)) latestCalls.set(peerId, item);
  }
  $('contacts-empty').classList.toggle('hidden', visibleContacts.length > 0);
  $('contacts-empty').textContent = state.contacts.length ? 'Нет абонентов в сети' : 'Контактов пока нет';
  for (const contact of visibleContacts) {
    const row = document.createElement('div'); row.className = 'contact';
    row.tabIndex = 0;
    row.setAttribute('role', 'button');
    row.setAttribute('aria-label', 'Подробнее: ' + (contact.name || contact.login));
    row.addEventListener('click', () => openContactDetails(contact, latestCalls.get(Number(contact.id))));
    row.addEventListener('keydown', event => {
      if (event.target !== row) return;
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openContactDetails(contact, latestCalls.get(Number(contact.id)));
      }
    });
    const avatar = document.createElement('img'); avatar.className = 'avatar'; avatar.alt = '';
    loadAvatar(contact.id, contact.avatar, avatar);
    const info = document.createElement('div'); info.className = 'contact-info';
    const name = document.createElement('div'); name.className = 'contact-name'; name.textContent = contact.name || contact.login;
    const status = document.createElement('div'); status.className = 'contact-status';
    const online = state.online.has(Number(contact.id)) || contact.online;
    const dot = document.createElement('span'); dot.className = 'dot' + (online ? ' online' : '');
    status.append(dot, document.createTextNode(online ? 'В сети' : contact.reachable ? 'Доступен для вызова' : 'Не в сети'));
    info.append(name, status);
    const lastCall = latestCalls.get(Number(contact.id));
    if (lastCall) {
      const lastCallLine = document.createElement('div');
      lastCallLine.className = 'contact-status';
      const kind = lastCall.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
      lastCallLine.textContent = kind + ' · ' + historyResult(lastCall) + ' · ' + formatHistoryDate(lastCall.startedAt);
      info.append(lastCallLine);
    }
    const call = document.createElement('button'); call.className = 'call-button'; call.type = 'button';
    call.innerHTML = ICON_CALL; call.title = 'Видеозвонок'; call.setAttribute('aria-label', 'Видеозвонок');
    call.addEventListener('click', event => { event.stopPropagation(); startCall(contact, 'video'); });
    row.append(avatar, info, call); list.append(row);
  }
}

function contactLastSeen(timestamp) {
  if (!timestamp || timestamp <= 0) return 'давно не был в сети';
  const date = new Date(Number(timestamp) * 1000);
  const now = new Date();
  const yesterday = new Date();
  yesterday.setDate(now.getDate() - 1);
  const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const time = date.toLocaleTimeString('ru-RU', {hour:'2-digit', minute:'2-digit'});
  if (sameDay(date, now)) return 'был в сети сегодня, ' + time;
  if (sameDay(date, yesterday)) return 'был в сети вчера, ' + time;
  return 'был в сети ' + date.toLocaleDateString('ru-RU', {day:'2-digit', month:'2-digit', year:'numeric'}) + ', ' + time;
}

function openContactDetails(contact, lastCall) {
  state.selectedContact = contact;
  $('contact-dialog-name').textContent = contact.name || contact.login;
  $('contact-dialog-login').textContent = '@' + (contact.login || '');
  const online = state.online.has(Number(contact.id)) || contact.online;
  $('contact-dialog-status').textContent = online ? 'Сейчас в сети' : contactLastSeen(contact.last_seen);
  const image = $('contact-dialog-avatar');
  image.src = 'icon-192.png';
  loadAvatar(contact.id, contact.avatar, image);
  const lastCallLine = $('contact-dialog-last-call');
  if (lastCall) {
    const kind = lastCall.media === 'video' ? 'видео' : 'аудио';
    lastCallLine.textContent = 'Последний звонок: ' + historyResult(lastCall) + ' · ' + formatHistoryDate(lastCall.startedAt) + ' · ' + kind;
    show('contact-dialog-last-call');
  } else {
    lastCallLine.textContent = '';
    hide('contact-dialog-last-call');
  }
  $('contact-call').disabled = !!state.call || !state.ws || state.ws.readyState !== WebSocket.OPEN;
  show('contact-modal');
}

function closeContactDetails() {
  hide('contact-modal');
  state.selectedContact = null;
}

$('contact-dialog-close').addEventListener('click', closeContactDetails);
$('contact-modal').addEventListener('click', event => {
  if (event.target === $('contact-modal')) closeContactDetails();
});
$('contact-call').addEventListener('click', () => {
  const contact = state.selectedContact;
  if (!contact) return;
  closeContactDetails();
  startCall(contact, 'video');
});
$('contact-message-open').addEventListener('click', () => {
  if (!state.selectedContact) return;
  $('message-dialog-title').textContent = 'Сообщение: ' + (state.selectedContact.name || state.selectedContact.login);
  $('message-text').value = '';
  $('message-error').textContent = '';
  $('message-send').disabled = true;
  show('message-modal');
  $('message-text').focus();
});
$('message-text').addEventListener('input', () => {
  $('message-send').disabled = !$('message-text').value.trim() || $('message-text').value.length > 1000;
  $('message-error').textContent = '';
});
$('message-cancel').addEventListener('click', () => hide('message-modal'));
$('message-modal').addEventListener('click', event => {
  if (event.target === $('message-modal')) hide('message-modal');
});
$('message-send').addEventListener('click', async () => {
  const contact = state.selectedContact;
  const text = $('message-text').value.trim();
  if (!contact || !text || text.length > 1000) return;
  const button = $('message-send');
  button.disabled = true;
  $('message-cancel').disabled = true;
  $('message-error').textContent = '';
  try {
    await api('/messages', {method:'POST', body:JSON.stringify({to:Number(contact.id), text})});
    hide('message-modal');
    closeContactDetails();
    toast('Сообщение отправлено');
  } catch (error) {
    const messages = {
      no_push_devices:'У абонента нет устройств с включёнными уведомлениями',
      bad_request:'Сообщение слишком длинное или содержит недопустимый текст',
      push_disabled:'Отправка сообщений на сервере не настроена',
      unknown_user:'Контакт не найден',
      push_failed:'Не удалось отправить сообщение'
    };
    $('message-error').textContent = messages[error.message] || 'Не удалось отправить сообщение';
  } finally {
    button.disabled = !$('message-text').value.trim();
    $('message-cancel').disabled = false;
  }
});
document.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  if (!$('message-modal').classList.contains('hidden')) hide('message-modal');
  else if (!$('contact-modal').classList.contains('hidden')) closeContactDetails();
});

async function loadProfile() {
  try {
    const data = await api('/me');
    state.user = data.user;
    $('my-name').value = data.user.name || '';
    await loadAvatar(data.user.id, data.avatar, $('my-avatar'));
  } catch (error) { toast(error.message); }
}

$('settings-open').addEventListener('click', () => {
  if ($('settings-view').classList.contains('hidden')) {
    hide('contacts-view'); hide('history-view'); hide('main-tabs');
    show('settings-view'); $('top-title').textContent = 'Настройки';
    hide('refresh-contacts');
    hide('clear-history');
    loadProfile();
    updatePushStatus();
  }
  else {
    hide('settings-view');
    show('main-tabs');
    showMainTab('contacts');
  }

});

$('save-profile').addEventListener('click', async () => {
  $('profile-error').textContent = '';
  try {
    const name = $('my-name').value.trim();
    const data = await api('/me', {method:'PUT', body:JSON.stringify({name})});
    state.user = data.user; toast('Имя сохранено');
  } catch (error) { $('profile-error').textContent = error.message === 'name_taken' ? 'Это имя уже используется' : error.message; }
});

$('avatar-file').addEventListener('change', async event => {
  const file = event.target.files?.[0]; if (!file) return;
  try {
    const jpeg = await toJpeg(file);
    await api('/me/avatar', {method:'PUT', body:jpeg, headers:{'Content-Type':'image/jpeg'}});
    await loadProfile(); toast('Фото обновлено');
  } catch (error) { toast(error.message); }
  event.target.value = '';
});

$('avatar-delete').addEventListener('click', async () => {
  try { await api('/me/avatar', {method:'DELETE'}); await loadProfile(); toast('Фото удалено'); }
  catch (error) { toast(error.message); }
});

function toJpeg(file) {
  return new Promise((resolve, reject) => {
    const image = new Image(); const url = URL.createObjectURL(file);
    image.onload = () => {
      const scale = Math.min(1, 2048 / Math.max(image.width, image.height));
      const canvas = document.createElement('canvas'); canvas.width = Math.round(image.width * scale); canvas.height = Math.round(image.height * scale);
      canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
      canvas.toBlob(blob => { URL.revokeObjectURL(url); blob ? resolve(blob) : reject(new Error('Не удалось обработать фото')); }, 'image/jpeg', .86);
    };
    image.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Не удалось открыть изображение')); };
    image.src = url;
  });
}

// Keep the signaling connection alive while the app is open.
function connectSocket() {
  clearTimeout(state.reconnectTimer);
  if (!state.token || state.ws) return;
  const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  const ws = new WebSocket(url); state.ws = ws;
  ws.addEventListener('open', () => ws.send(JSON.stringify({t:'auth', token:state.token, proto:1, ...(state.call?.id ? {call:state.call.id} : state.wakeCall ? {call:state.wakeCall} : {})})));
  ws.addEventListener('message', event => {
    let message; try { message = JSON.parse(event.data); } catch (_) { return; }
    handleSocketMessage(message);
  });
  ws.addEventListener('close', event => {
    if (state.ws !== ws) return;
    state.ws = null;
    setConnection(event.code === 4401 ? 'Сеанс завершён' : 'Нет связи с сервером', 'offline');
    if (event.code === 4401) { logoutLocal(); return; }
    if (state.token) state.reconnectTimer = setTimeout(connectSocket, 1800);
  });
  ws.addEventListener('error', () => setConnection('Нет связи с сервером', 'offline'));
}

function send(message) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return false;
  state.ws.send(JSON.stringify(message)); return true;
}

function handleSocketMessage(message) {
  switch (message.t) {
    case 'auth.ok':
      state.online = new Set((message.online || []).map(Number));
      setConnection('На связи с сервером', 'online'); renderContacts(); loadContacts();
      if (state.wakeCall) {
        state.wakeCall = '';
        history.replaceState(null, '', location.pathname);
      }
      if (message.calls?.length) {
        if (state.call) {
          if (!state.call.id) state.call.id = message.calls[0].id;
          if (state.call.outgoing && message.calls[0].state === 'ringing') {
            $('call-message').textContent = 'Вызов…';
            startCallTone('ringback');
          }
        } else {
          // A page reload destroys the WebRTC peer connection. Do not leave
          // the server-side call reserved for this device after reconnecting.
          for (const call of message.calls) {
            if (call?.id) send({t:'call.hangup', call:call.id});
          }
          toast('Вызов сброшен');
        }
      }
      break;
    case 'presence':
      if (message.online) state.online.add(Number(message.user)); else state.online.delete(Number(message.user));
      renderContacts(); break;
    case 'call.created':
      if (!state.call) return;
      state.call.id = message.call.id; state.call.state = 'calling'; state.call.ice = message.ice;
      if (state.call.cancelRequested) {
        send({t:'call.cancel', call:state.call.id});
        endCall(false, 'cancelled');
        break;
      }
      $('call-state').textContent = 'Исходящий вызов'; $('call-message').textContent = 'Ожидание…';
      break;
    case 'call.incoming': incomingCall(message); break;
    case 'call.ringing':
      if (state.call?.id === message.call) {
        $('call-message').textContent = 'Вызов…';
        if (state.call.outgoing) startCallTone('ringback');
      }
      break;
    case 'call.accepted':
      if (state.call?.id === message.call) {
        stopCallTone();
        state.call.state = 'connecting';
        if (state.call.outgoing) createPeerAndOffer();
        else $('call-message').textContent = 'Соединение…';
      }
      break;
    case 'call.signal': handleSignal(message); break;
    case 'call.media': if (state.call?.id === message.call) { state.call.media = message.media; $('call-kind').textContent = message.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок'; $('answer-video').classList.toggle('hidden', message.media !== 'video'); } break;
    case 'call.ended': if (state.call?.id === message.call) { const reason = message.reason; endCall(false, reason); toast(endReason(reason)); } break;
    case 'ice': if (state.call?.id === message.call && state.pc) state.pc.setConfiguration(rtcConfig(message.ice)); break;
    case 'error': handleServerError(message); break;
    case 'pong': break;
  }
}

function endReason(reason) {
  const map = {busy:'Абонент занят',offline:'Абонент недоступен',rejected:'Вызов отклонён',cancelled:'Вызов отменён',hangup:'Вызов завершён',no_answer:'Нет ответа',answered_elsewhere:'Ответили на другом устройстве',gone:'Вызов уже завершён'};
  return map[reason] || 'Вызов завершён';
}

function handleServerError(message) {
  const map = {server_busy:'Сервер занят',already_in_call:'Вы уже участвуете в другом вызове',unknown_user:'Абонент не найден',busy:'Абонент занят',offline:'Абонент недоступен'};
  toast(map[message.code] || message.code || 'Ошибка сервера');
  if (message.ref === 'call.invite' && state.call) endCall(false, message.code || 'failed');
}

async function startCall(contact, media) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) { toast('Нет связи с сервером'); return; }
  if (state.call) return;
  state.muted = false;
  state.cameraOn = media === 'video';
  state.speakerOn = media === 'video';
  state.speakerAvailable = false;
  state.speakerDeviceId = '';
  state.call = {id:'', peerId:contact.id, peerName:contact.name || contact.login, peerLogin:contact.login, media, outgoing:true, state:'calling', ice:null, startedAt:Date.now()};
  showCallScreen();
  $('call-state').textContent = 'Исходящий вызов'; $('call-message').textContent = 'Запрос…';
  $('call-kind').textContent = media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
  show('active-actions'); updateCallButtons();
  try {
    await startMedia(media === 'video');
    if (!state.call) return;
    $('call-message').textContent = 'Запрос…';
    send({t:'call.invite', to:Number(contact.id), media});
  } catch (error) {
    toast('Не удалось включить микрофон или камеру: ' + error.message);
    endCall(false, 'failed');
  }
}

// Show a new incoming call.
function incomingCall(message) {
  const call = message.call;
  if (state.call && state.call.id !== call.id) { send({t:'call.reject', call:call.id}); return; }
  state.call = {id:call.id, peerId:call.peer.id, peerName:call.peer.name, media:call.media, outgoing:false, state:'incoming', ice:message.ice, startedAt:Date.now()};
  send({t:'call.ringing', call:call.id});
  startCallTone('incoming');
  showCallScreen();
  $('call-state').textContent = 'Входящий вызов'; $('call-message').textContent = 'Вам звонят';
  $('call-kind').textContent = call.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
  show('incoming-actions'); hide('active-actions');
  $('answer-video').classList.toggle('hidden', call.media !== 'video');
}

function showCallScreen() {
  $('hangup-call').disabled = false;
  hide('main-screen'); show('call-screen');
  $('call-peer').textContent = state.call.peerName;
  $('incoming-actions').classList.add('hidden'); $('active-actions').classList.add('hidden');
  $('remote-video').srcObject = null; $('local-video').srcObject = null; $('remote-audio').srcObject = null;
  resetPreviewPosition();
  $('video-placeholder').classList.remove('hidden');
}

function resetPreviewPosition() {
  const preview = $('local-video');
  preview.style.left = 'auto'; preview.style.top = '12px';
  preview.style.right = '12px'; preview.style.bottom = 'auto';
}

(function enablePreviewDrag() {
  const preview = $('local-video');
  let drag = null;
  preview.addEventListener('pointerdown', event => {
    if (!preview.srcObject) return;
    const stage = $('remote-video').parentElement.getBoundingClientRect();
    const rect = preview.getBoundingClientRect();
    drag = {x:event.clientX, y:event.clientY, left:rect.left-stage.left, top:rect.top-stage.top, stage};
    preview.style.left = drag.left + 'px'; preview.style.top = drag.top + 'px';
    preview.style.right = 'auto'; preview.style.bottom = 'auto';
    preview.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  preview.addEventListener('pointermove', event => {
    if (!drag) return;
    const maxX = Math.max(0, drag.stage.width - preview.offsetWidth);
    const maxY = Math.max(0, drag.stage.height - preview.offsetHeight);
    const left = Math.min(maxX, Math.max(0, drag.left + event.clientX - drag.x));
    const top = Math.min(maxY, Math.max(0, drag.top + event.clientY - drag.y));
    preview.style.left = left + 'px'; preview.style.top = top + 'px';
  });
  const stop = () => { drag = null; };
  preview.addEventListener('pointerup', stop);
  preview.addEventListener('pointercancel', stop);
})();

$('answer-audio').addEventListener('click', () => acceptCall(false));

$('answer-video').addEventListener('click', () => acceptCall(true));

$('reject-call').addEventListener('click', () => { if (state.call) send({t:'call.reject', call:state.call.id}); endCall(false, 'rejected'); });

async function acceptCall(withVideo) {
  if (!state.call || state.call.outgoing) return;
  stopCallTone();
  state.muted = false;
  state.cameraOn = withVideo && state.call.media === 'video';
  state.speakerOn = state.cameraOn;
  state.speakerAvailable = false;
  state.speakerDeviceId = '';
  try {
    await startMedia(state.cameraOn);
    state.call.state = 'connecting';
    send({t:'call.accept', call:state.call.id});
    hide('incoming-actions'); show('active-actions');
    $('call-state').textContent = 'Соединение'; $('call-message').textContent = 'Соединение…';
    updateCallButtons();
  } catch (error) {
    toast('Нет доступа к микрофону или камере: ' + error.message);
    if (state.call?.state === 'incoming') startCallTone('incoming');
  }
}

async function createPeerAndOffer() {
  try {
    if (!state.localStream) await startMedia(state.call.media === 'video');
    await createPeer();
    addLocalTracks();
    const offer = await state.pc.createOffer();
    await state.pc.setLocalDescription(offer);
    send({t:'call.signal', call:state.call.id, kind:'offer', sdp:offer.sdp});
    $('call-message').textContent = 'Соединение…';
    show('active-actions'); updateCallButtons();
  } catch (error) { toast('Не удалось начать звонок: ' + error.message); send({t:'call.hangup', call:state.call.id}); endCall(false); }
}

// Ask for microphone and camera access.
async function startMedia(withVideo) {
  if (!state.localStream) {
    const constraints = {audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}, video:withVideo ? {facingMode:'user',width:{ideal:640},height:{ideal:360},frameRate:{ideal:20,max:24}} : false};
    state.localStream = await navigator.mediaDevices.getUserMedia(constraints);
    await refreshAudioOutputs();
    if (withVideo) {
      const track = state.localStream.getVideoTracks()[0];
      state.cameraDeviceId = track?.getSettings().deviceId || '';
      state.cameraFacingMode = track?.getSettings().facingMode || 'user';
      await refreshCameraDevices();
    }
  }
  state.localStream.getAudioTracks().forEach(track => track.enabled = !state.muted);
  state.localStream.getVideoTracks().forEach(track => track.enabled = state.cameraOn);
  $('local-video').srcObject = state.cameraOn ? state.localStream : null;
  updateLocalPreviewMirror();
  if (state.call?.ice) {
    await createPeer();
    addLocalTracks();
  }
  updateCallButtons();
}

async function refreshCameraDevices() {
  try {
    if (!navigator.mediaDevices?.enumerateDevices) {
      state.cameraDevices = [];
    } else {
      state.cameraDevices = (await navigator.mediaDevices.enumerateDevices())
        .filter(device => device.kind === 'videoinput');
    }
  } catch (_) {
    state.cameraDevices = [];
  }
  $('camera-switch').classList.toggle('hidden',
    !state.call || state.call.media !== 'video' || state.call.state === 'incoming' || state.cameraDevices.length < 2);
}

function updateLocalPreviewMirror() {
  $('local-video').style.transform = state.cameraFacingMode === 'user' ? 'scaleX(-1)' : 'none';
}

async function switchCamera() {
  if (!state.call || state.call.media !== 'video' || state.cameraDevices.length < 2 || !state.localStream) return;
  const currentIndex = state.cameraDevices.findIndex(device => device.deviceId === state.cameraDeviceId);
  const next = state.cameraDevices[(currentIndex + 1 + state.cameraDevices.length) % state.cameraDevices.length];
  if (!next) return;
  const button = $('camera-switch');
  button.disabled = true;
  const oldTrack = state.localStream.getVideoTracks()[0];
  const oldDeviceId = state.cameraDeviceId;
  const sender = state.pc?.getSenders().find(item => item.track?.kind === 'video');
  try {
    // Mobile browsers often cannot open a second camera while the current one is active.
    // Release the old camera before requesting the next device.
    if (oldTrack) {
      if (sender) await sender.replaceTrack(null);
      state.localStream.removeTrack(oldTrack);
      oldTrack.stop();
    }

    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {deviceId:{exact:next.deviceId},width:{ideal:640},height:{ideal:360},frameRate:{ideal:20,max:24}}
      });
    } catch (firstError) {
      // Some older Android camera stacks need facingMode instead of an exact deviceId.
      const facing = state.cameraFacingMode === 'user' ? 'environment' : 'user';
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {facingMode:{exact:facing},width:{ideal:640},height:{ideal:360},frameRate:{ideal:20,max:24}}
      });
    }

    const nextTrack = stream.getVideoTracks()[0];
    if (!nextTrack) throw new Error('Камера не вернула видеопоток');
    nextTrack.enabled = state.cameraOn;
    if (sender) await sender.replaceTrack(nextTrack);
    else if (state.pc) state.pc.addTrack(nextTrack, state.localStream);
    state.localStream.addTrack(nextTrack);

    const settings = nextTrack.getSettings();
    state.cameraDeviceId = settings.deviceId || next.deviceId;
    state.cameraFacingMode = settings.facingMode || (/back|rear|environment/i.test(next.label) ? 'environment' :
      /front|user|facetime/i.test(next.label) ? 'user' : (state.cameraFacingMode === 'user' ? 'environment' : 'user'));
    $('local-video').srcObject = state.cameraOn ? state.localStream : null;
    updateLocalPreviewMirror();
    button.title = 'Переключить камеру';
  } catch (error) {
    // If opening the new camera failed, try to restore the previous one.
    try {
      const restore = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: oldDeviceId
          ? {deviceId:{exact:oldDeviceId},width:{ideal:640},height:{ideal:360}}
          : {facingMode:{ideal:state.cameraFacingMode},width:{ideal:640},height:{ideal:360}}
      });
      const restoredTrack = restore.getVideoTracks()[0];
      if (restoredTrack) {
        restoredTrack.enabled = state.cameraOn;
        if (sender) await sender.replaceTrack(restoredTrack);
        else if (state.pc) state.pc.addTrack(restoredTrack, state.localStream);
        state.localStream.addTrack(restoredTrack);
        state.cameraDeviceId = restoredTrack.getSettings().deviceId || oldDeviceId;
        $('local-video').srcObject = state.cameraOn ? state.localStream : null;
        updateLocalPreviewMirror();
      }
    } catch (_) {}
    toast('Не удалось переключить камеру: ' + error.message);
  } finally {
    button.disabled = false;
    await refreshCameraDevices();
  }
}

function addLocalTracks() {
  if (!state.pc || !state.localStream) return;
  for (const track of state.localStream.getTracks()) {
    if (!state.pc.getSenders().some(sender => sender.track?.id === track.id)) {
      state.pc.addTrack(track, state.localStream);
    }
  }
}

async function createPeer() {
  if (state.pc) return;
  state.pc = new RTCPeerConnection(rtcConfig(state.call.ice));
  state.remoteStream = new MediaStream();
  $('remote-video').srcObject = state.remoteStream;
  $('remote-audio').srcObject = state.remoteStream;
  $('remote-video').muted = true;
  state.pc.ontrack = event => {
    const stream = event.streams[0];
    if (stream) {
      for (const track of stream.getTracks()) {
        if (!state.remoteStream.getTracks().some(item => item.id === track.id)) state.remoteStream.addTrack(track);
      }
    } else if (!state.remoteStream.getTracks().some(item => item.id === event.track.id)) {
      state.remoteStream.addTrack(event.track);
    }
    $('video-placeholder').classList.toggle('hidden', state.remoteStream.getVideoTracks().length > 0);
  };
  state.pc.onicecandidate = event => {
    if (event.candidate && state.call?.id) send({t:'call.signal',call:state.call.id,kind:'candidate',candidate:event.candidate.toJSON()});
  };
  state.pc.onconnectionstatechange = () => {
    if (!state.pc || !state.call) return;
    if (state.pc.connectionState === 'connected') {
      state.call.state = 'active'; state.call.connectedAt = Date.now(); send({t:'call.connected',call:state.call.id});
      $('call-state').textContent = 'Разговор'; $('call-message').textContent = 'Соединение установлено';
    } else if (state.pc.connectionState === 'failed' || state.pc.connectionState === 'closed') {
      if (state.pc.connectionState === 'failed') { toast('Не удалось установить соединение'); send({t:'call.hangup',call:state.call.id}); endCall(false); }
    }
  };
  state.pc.oniceconnectionstatechange = () => {
    if (state.pc?.iceConnectionState === 'checking') $('call-message').textContent = 'Поиск соединения…';
  };
}

function rtcConfig(ice) {
  const servers = ice?.servers || [];
  return {iceServers:servers, iceTransportPolicy:ice?.policy === 'relay' ? 'relay' : 'all'};
}
// Apply signaling messages from the other caller.

async function handleSignal(message) {
  if (!state.call || state.call.id !== message.call) return;
  try {
    if (message.kind === 'camera') return;
    if (!state.pc) await createPeer();
    if (message.kind === 'offer') {
      await state.pc.setRemoteDescription({type:'offer',sdp:message.sdp});
      for (const candidate of state.pendingCandidates.splice(0)) await state.pc.addIceCandidate(candidate);
      const answer = await state.pc.createAnswer();
      await state.pc.setLocalDescription(answer);
      send({t:'call.signal',call:state.call.id,kind:'answer',sdp:answer.sdp});
    } else if (message.kind === 'answer') {
      await state.pc.setRemoteDescription({type:'answer',sdp:message.sdp});
      for (const candidate of state.pendingCandidates.splice(0)) await state.pc.addIceCandidate(candidate);
    } else if (message.kind === 'candidate' && message.candidate) {
      const candidate = new RTCIceCandidate(message.candidate);
      if (state.pc.remoteDescription) await state.pc.addIceCandidate(candidate);
      else state.pendingCandidates.push(candidate);
    }
  } catch (error) { console.error('WebRTC signal error', error); toast('Ошибка согласования звонка'); }
}

$('mute-call').addEventListener('click', () => {
  state.muted = !state.muted;
  state.localStream?.getAudioTracks().forEach(track => track.enabled = !state.muted);
  updateCallButtons();
});

$('camera-switch').addEventListener('click', switchCamera);
$('speaker-call').addEventListener('click', toggleSpeaker);

$('camera-call').addEventListener('click', () => {
  if (state.call?.media !== 'video') return;
  state.cameraOn = !state.cameraOn;
  state.localStream?.getVideoTracks().forEach(track => track.enabled = state.cameraOn);
  $('local-video').srcObject = state.cameraOn ? state.localStream : null;
  if (state.call.id) send({t:'call.signal',call:state.call.id,kind:'camera',enabled:state.cameraOn});
  updateCallButtons();
});

async function refreshAudioOutputs() {
  const audio = $('remote-audio');
  state.audioOutputs = [];
  state.speakerDeviceId = '';
  state.speakerAvailable = false;
  if (typeof audio.setSinkId !== 'function' || !navigator.mediaDevices?.enumerateDevices) {
    updateCallButtons();
    return;
  }
  try {
    state.audioOutputs = (await navigator.mediaDevices.enumerateDevices())
      .filter(device => device.kind === 'audiooutput');
    const speaker = state.audioOutputs.find(device =>
      device.deviceId !== 'default' && /speaker|loudspeaker|динамик|built.?in.*output/i.test(device.label));
    if (speaker) {
      state.speakerDeviceId = speaker.deviceId;
      state.speakerAvailable = true;
      if (state.call?.media === 'video' && state.speakerOn) {
        await audio.setSinkId(speaker.deviceId);
      } else {
        await audio.setSinkId('default');
      }
    }
  } catch (error) {
    state.speakerAvailable = false;
    state.speakerDeviceId = '';
    console.warn('Audio output selection unavailable:', error);
  }
  updateCallButtons();
}

async function toggleSpeaker() {
  if (!state.speakerAvailable || !state.speakerDeviceId) return;
  const next = !state.speakerOn;
  try {
    await $('remote-audio').setSinkId(next ? state.speakerDeviceId : 'default');
    state.speakerOn = next;
    updateCallButtons();
  } catch (error) {
    toast('Не удалось переключить аудиовыход: ' + error.message);
  }
}

function updateCallButtons() {
  const switchButton = $('camera-switch');
  switchButton.innerHTML = ICON_CAMERA_SWITCH;
  switchButton.classList.toggle('hidden', !state.call || state.call.state === 'incoming' || state.call.media !== 'video' || state.cameraDevices.length < 2);

  const speaker = $('speaker-call');
  speaker.innerHTML = ICON_SPEAKER;
  speaker.classList.toggle('speaker-on', state.speakerOn);
  speaker.disabled = !state.speakerAvailable || !state.call || state.call.state === 'incoming';
  speaker.title = state.speakerAvailable
    ? (state.speakerOn ? 'Выключить громкую связь' : 'Включить громкую связь')
    : 'Браузер не поддерживает переключение на динамик';
  speaker.setAttribute('aria-label', speaker.title);

  const camera = $('camera-call');
  camera.classList.toggle('hidden', !state.call || state.call.state === 'incoming' || state.call.media !== 'video');
  camera.innerHTML = state.cameraOn ? ICON_CAMERA : ICON_CAMERA_OFF;
  camera.classList.toggle('is-off', !state.cameraOn);
  camera.title = state.cameraOn ? 'Выключить камеру' : 'Включить камеру';
  camera.setAttribute('aria-label', camera.title);

  const mute = $('mute-call');
  mute.classList.toggle('hidden', !state.call || state.call.state === 'incoming');
  mute.innerHTML = state.muted ? ICON_MIC_OFF : ICON_MIC;
  mute.classList.toggle('is-off', state.muted);
  mute.title = state.muted ? 'Включить микрофон' : 'Выключить микрофон';
  mute.setAttribute('aria-label', mute.title);

  $('hangup-call').innerHTML = ICON_HANGUP;
  $('hangup-call').title = 'Отбой';
  $('hangup-call').setAttribute('aria-label', 'Отбой');
}

$('hangup-call').addEventListener('click', () => {
  if (!state.call) return;
  if (state.call.outgoing && !state.call.id) {
    state.call.cancelRequested = true;
    $('hangup-call').disabled = true;
    $('call-message').textContent = 'Отмена…';
    return;
  }
  if (state.call.id) send({t:'call.hangup',call:state.call.id});
  endCall(false, state.call.outgoing && state.call.state === 'calling' ? 'cancelled' : 'hangup');
});

function endCall(showMain = true, reason = 'hangup') {
  const playBusyTone = reason === 'busy' && state.call?.outgoing;
  stopCallTone();
  if (playBusyTone) {
    startCallTone('busy');
    callToneStopTimer = setTimeout(stopCallTone, 2500);
  }
  saveHistory(state.call, reason);
  if (state.pc) { state.pc.ontrack = null; state.pc.onicecandidate = null; state.pc.onconnectionstatechange = null; state.pc.close(); }
  state.pc = null;
  if (state.localStream) state.localStream.getTracks().forEach(track => track.stop());
  state.localStream = null; state.remoteStream = null; state.pendingCandidates = [];
  state.call = null; state.muted = false; state.cameraOn = false;
  state.cameraDevices = []; state.cameraDeviceId = ''; state.cameraFacingMode = 'user';
  state.audioOutputs = []; state.speakerDeviceId = ''; state.speakerAvailable = false; state.speakerOn = false;
  $('remote-audio').setSinkId?.('default').catch(() => {});
  $('local-video').style.transform = 'scaleX(-1)';
  hide('call-screen');
  if (showMain && state.token) show('main-screen');
  else if (state.token) show('main-screen');
  else show('login-screen');
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.token && (!state.ws || state.ws.readyState > WebSocket.OPEN)) connectSocket();
});

window.addEventListener('online', () => { if (state.token) connectSocket(); });

let swRegistration = null;

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js', {updateViaCache:'none'})
    .then(registration => {
      swRegistration = registration;
      registration.update().catch(error => console.warn('Service worker update:', error));
    })
    .catch(error => console.warn('Service worker:', error));
}

// Ask for notification permission only after the user presses the button.
$('push-enable').addEventListener('click', enableWebPush);

$('push-disable').addEventListener('click', disableWebPush);

async function updatePushStatus() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
    $('push-status').textContent = 'Этот браузер не поддерживает Web Push';
    $('push-enable').classList.add('hidden');
    $('push-disable').classList.add('hidden');
    return;
  }
  try {
    const registration = swRegistration || await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    $('push-status').textContent = subscription ? 'Уведомления включены' :
      Notification.permission === 'denied' ? 'Уведомления заблокированы в браузере' : 'Уведомления не настроены';
    $('push-enable').classList.toggle('hidden', !!subscription || Notification.permission === 'denied');
    $('push-disable').classList.toggle('hidden', !subscription);
  } catch (_) {
    $('push-status').textContent = 'Не удалось проверить уведомления';
  }
}

async function syncExistingPushSubscription() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window) || Notification.permission !== 'granted') return;
  try {
    const registration = swRegistration || await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (!subscription) return;
    const key = await api('/push/vapid-public-key');
    if (key.enabled) {
      await api('/push/subscription', {method:'PUT', body:JSON.stringify(subscription.toJSON())});
    }
  } catch (error) {
    console.warn('Web Push subscription sync:', error);
  }
}

function decodeVapidKey(value) {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4);
  return Uint8Array.from(atob(padded), char => char.charCodeAt(0));
}

async function enableWebPush() {
  try {
    if (!('Notification' in window)) throw new Error('Web Push не поддерживается');
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') throw new Error('Разрешение на уведомления не получено');
    const key = await api('/push/vapid-public-key');
    if (!key.enabled || !key.public_key) throw new Error('Web Push не настроен на сервере');
    const registration = swRegistration || await navigator.serviceWorker.ready;
    let subscription = await registration.pushManager.getSubscription();
    if (!subscription) {
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: decodeVapidKey(key.public_key)
      });
    }
    await api('/push/subscription', {method:'PUT', body:JSON.stringify(subscription.toJSON())});
    toast('Уведомления включены');
    updatePushStatus();
  } catch (error) {
    toast(error.message || 'Не удалось включить уведомления');
    updatePushStatus();
  }
}

async function disableWebPush() {
  try {
    const registration = swRegistration || await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (subscription) await subscription.unsubscribe();
    await api('/push/subscription', {method:'DELETE'});
    toast('Уведомления отключены');
  } catch (error) {
    toast(error.message || 'Не удалось отключить уведомления');
  }
  updatePushStatus();
}

if (state.token) {
  api('/me').then(data => { state.user = data.user; enterApp(); }).catch(() => {});
}

setInterval(() => { if (state.ws?.readyState === WebSocket.OPEN) send({t:'ping'}); }, 20000);
