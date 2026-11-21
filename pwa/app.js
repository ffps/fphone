'use strict';

const $ = id => document.getElementById(id);
const ICON_CALL = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2a1 1 0 0 1 1.02-.24c1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1C11.61 21 4 13.39 4 4c0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02z"/></svg>';
const ICON_MIC = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5.3-3h1.7a7 7 0 0 1-6 6.72V21h-2v-3.28A7 7 0 0 1 5 11h1.7a5.3 5.3 0 0 0 10.6 0z"/></svg>';
const ICON_MIC_OFF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23c.56-.98.9-2.09.9-3.28zM14.98 11.17 9 5.18V5a3 3 0 0 1 5.98-.17v6.17c0 .06 0 .11-.02.17zM4.27 3 3 4.27l6.01 6.01V11a3 3 0 0 0 3.64 2.92l1.66 1.66a5.3 5.3 0 0 1-8.61-4.58H5a7 7 0 0 0 6 6.72V21h2v-3.28c.91-.13 1.77-.45 2.54-.9L19.73 21 21 19.73 4.27 3z"/></svg>';
const ICON_CAMERA = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11z"/></svg>';
const ICON_CAMERA_OFF = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 6.5-4 4V7a1 1 0 0 0-1-1H9.82L21 17.18zM3.27 2 2 3.27 4.73 6H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12c.21 0 .39-.08.54-.18L19.73 21 21 19.73z"/></svg>';
const ICON_HANGUP = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.28-.7.28-.28 0-.53-.11-.71-.29L.29 13.08a1 1 0 0 1 0-1.41C3.34 8.78 7.46 7 12 7s8.66 1.78 11.71 4.67a1 1 0 0 1 0 1.42l-2.48 2.48a1 1 0 0 1-1.41.01c-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v-3.1A16 16 0 0 0 12 9z"/></svg>';
const state = {
  token: localStorage.getItem('kukukha.token') || '',
  deviceId: localStorage.getItem('kukukha.device') || makeId(),
  user: null, contacts: [], online: new Set(), ws: null, reconnectTimer: 0,
  call: null, pc: null, localStream: null, remoteStream: null,
  pendingCandidates: [], muted: false, cameraOn: false, selectedAvatar: null
};
localStorage.setItem('kukukha.device', state.deviceId);

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
  loadProfile(); loadContacts(); connectSocket();
}
function logoutLocal() {
  state.token = ''; state.user = null;
  localStorage.removeItem('kukukha.token');
  if (state.ws) state.ws.close();
  state.ws = null;
  endCall(false);
  hide('main-screen'); hide('call-screen'); show('login-screen');
}
$('logout').addEventListener('click', async () => {
  try { await api('/logout', {method:'POST'}); } catch (_) {}
  logoutLocal();
});

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
  $('contacts-empty').classList.toggle('hidden', visibleContacts.length > 0);
  $('contacts-empty').textContent = state.contacts.length ? 'Нет абонентов в сети' : 'Контактов пока нет';
  for (const contact of visibleContacts) {
    const row = document.createElement('div'); row.className = 'contact';
    const avatar = document.createElement('img'); avatar.className = 'avatar'; avatar.alt = '';
    loadAvatar(contact.id, contact.avatar, avatar);
    const info = document.createElement('div'); info.className = 'contact-info';
    const name = document.createElement('div'); name.className = 'contact-name'; name.textContent = contact.name || contact.login;
    const status = document.createElement('div'); status.className = 'contact-status';
    const online = state.online.has(Number(contact.id)) || contact.online;
    const dot = document.createElement('span'); dot.className = 'dot' + (online ? ' online' : '');
    status.append(dot, document.createTextNode(online ? 'В сети' : contact.reachable ? 'Доступен для вызова' : 'Не в сети'));
    info.append(name, status);
    const call = document.createElement('button'); call.className = 'call-button'; call.type = 'button';
    call.innerHTML = ICON_CALL; call.title = 'Видеозвонок'; call.setAttribute('aria-label', 'Видеозвонок');
    call.addEventListener('click', () => startCall(contact, 'video'));
    row.append(avatar, info, call); list.append(row);
  }
}

async function loadProfile() {
  try {
    const data = await api('/me');
    state.user = data.user;
    $('my-name').value = data.user.name || '';
    await loadAvatar(data.user.id, data.avatar, $('my-avatar'));
  } catch (error) { toast(error.message); }
}
$('settings-open').addEventListener('click', () => { hide('contacts-view'); show('settings-view'); $('top-title').textContent = 'Настройки'; loadProfile(); });
$('settings-close').addEventListener('click', () => { hide('settings-view'); show('contacts-view'); $('top-title').textContent = 'Контакты'; });
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

function connectSocket() {
  clearTimeout(state.reconnectTimer);
  if (!state.token || state.ws) return;
  const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  const ws = new WebSocket(url); state.ws = ws;
  ws.addEventListener('open', () => ws.send(JSON.stringify({t:'auth', token:state.token, proto:1, ...(state.call?.id ? {call:state.call.id} : {})})));
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
      if (message.calls?.length && state.call && !state.call.id) state.call.id = message.calls[0].id;
      break;
    case 'presence':
      if (message.online) state.online.add(Number(message.user)); else state.online.delete(Number(message.user));
      renderContacts(); break;
    case 'call.created':
      if (!state.call) return;
      state.call.id = message.call.id; state.call.state = 'calling'; state.call.ice = message.ice;
      $('call-state').textContent = 'Вызов'; $('call-message').textContent = 'Вызов абонента…';
      break;
    case 'call.incoming': incomingCall(message); break;
    case 'call.ringing': if (state.call?.id === message.call) $('call-message').textContent = 'Ожидание ответа…'; break;
    case 'call.accepted':
      if (state.call?.id === message.call) {
        state.call.state = 'connecting';
        if (state.call.outgoing) createPeerAndOffer();
        else $('call-message').textContent = 'Соединение…';
      }
      break;
    case 'call.signal': handleSignal(message); break;
    case 'call.media': if (state.call?.id === message.call) $('call-kind').textContent = message.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок'; break;
    case 'call.ended': if (state.call?.id === message.call) { const reason = message.reason; endCall(false); toast(endReason(reason)); } break;
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
  if (message.ref === 'call.invite' && state.call) endCall(false);
}

function startCall(contact, media) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) { toast('Нет связи с сервером'); return; }
  if (state.call) return;
  state.call = {id:'', peerId:contact.id, peerName:contact.name || contact.login, media, outgoing:true, state:'calling', ice:null};
  showCallScreen();
  $('call-state').textContent = 'Исходящий вызов'; $('call-message').textContent = 'Вызов абонента…';
  $('call-kind').textContent = media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
  send({t:'call.invite', to:Number(contact.id), media});
}
function incomingCall(message) {
  const call = message.call;
  if (state.call && state.call.id !== call.id) { send({t:'call.reject', call:call.id}); return; }
  state.call = {id:call.id, peerId:call.peer.id, peerName:call.peer.name, media:call.media, outgoing:false, state:'incoming', ice:message.ice};
  send({t:'call.ringing', call:call.id});
  showCallScreen();
  $('call-state').textContent = 'Входящий вызов'; $('call-message').textContent = 'Вам звонят';
  $('call-kind').textContent = call.media === 'video' ? 'Видеозвонок' : 'Аудиозвонок';
  show('incoming-actions'); hide('active-actions');
  $('answer-video').classList.toggle('hidden', call.media !== 'video');
}
function showCallScreen() {
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
$('reject-call').addEventListener('click', () => { if (state.call) send({t:'call.reject', call:state.call.id}); endCall(false); });
async function acceptCall(withVideo) {
  if (!state.call || state.call.outgoing) return;
  try {
    await startMedia(withVideo && state.call.media === 'video');
    state.call.state = 'connecting';
    send({t:'call.accept', call:state.call.id});
    hide('incoming-actions'); show('active-actions');
    $('call-state').textContent = 'Соединение'; $('call-message').textContent = 'Соединение…';
    updateCallButtons();
  } catch (error) { toast('Нет доступа к микрофону или камере: ' + error.message); }
}
async function createPeerAndOffer() {
  try {
    await startMedia(state.call.media === 'video');
    await createPeer();
    const offer = await state.pc.createOffer();
    await state.pc.setLocalDescription(offer);
    send({t:'call.signal', call:state.call.id, kind:'offer', sdp:offer.sdp});
    $('call-message').textContent = 'Соединение…';
    show('active-actions'); updateCallButtons();
  } catch (error) { toast('Не удалось начать звонок: ' + error.message); send({t:'call.hangup', call:state.call.id}); endCall(false); }
}
async function startMedia(withVideo) {
  const constraints = {audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}, video:withVideo ? {facingMode:'user',width:{ideal:640},height:{ideal:360},frameRate:{ideal:20,max:24}} : false};
  state.localStream = await navigator.mediaDevices.getUserMedia(constraints);
  $('local-video').srcObject = withVideo ? state.localStream : null;
  state.cameraOn = withVideo;
  await createPeer();
  for (const track of state.localStream.getTracks()) state.pc.addTrack(track, state.localStream);
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
      state.call.state = 'active'; send({t:'call.connected',call:state.call.id});
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
$('camera-call').addEventListener('click', () => {
  if (!state.localStream?.getVideoTracks().length) { toast('Камера не включена для этого звонка'); return; }
  state.cameraOn = !state.cameraOn;
  state.localStream.getVideoTracks().forEach(track => track.enabled = state.cameraOn);
  send({t:'call.signal',call:state.call.id,kind:'camera',enabled:state.cameraOn});
  updateCallButtons();
});
function updateCallButtons() {
  const mute = $('mute-call');
  mute.innerHTML = state.muted ? ICON_MIC_OFF : ICON_MIC;
  mute.title = state.muted ? 'Включить микрофон' : 'Выключить микрофон'; mute.setAttribute('aria-label', mute.title);
  const camera = $('camera-call');
  camera.classList.toggle('hidden', state.call?.media !== 'video' || !state.localStream?.getVideoTracks().length);
  camera.innerHTML = state.cameraOn ? ICON_CAMERA : ICON_CAMERA_OFF;
  camera.title = state.cameraOn ? 'Выключить камеру' : 'Включить камеру'; camera.setAttribute('aria-label', camera.title);
  $('hangup-call').innerHTML = ICON_HANGUP;
}
$('hangup-call').addEventListener('click', () => { if (state.call?.id) send({t:'call.hangup',call:state.call.id}); endCall(false); });
function endCall(showMain = true) {
  if (state.pc) { state.pc.ontrack = null; state.pc.onicecandidate = null; state.pc.onconnectionstatechange = null; state.pc.close(); }
  state.pc = null;
  if (state.localStream) state.localStream.getTracks().forEach(track => track.stop());
  state.localStream = null; state.remoteStream = null; state.pendingCandidates = [];
  state.call = null; state.muted = false; state.cameraOn = false;
  hide('call-screen');
  if (showMain && state.token) show('main-screen');
  else if (state.token) show('main-screen');
  else show('login-screen');
}

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && state.token && (!state.ws || state.ws.readyState > WebSocket.OPEN)) connectSocket();
});
window.addEventListener('online', () => { if (state.token) connectSocket(); });
if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(error => console.warn('Service worker:', error));
if (state.token) {
  api('/me').then(data => { state.user = data.user; enterApp(); }).catch(() => {});
}
setInterval(() => { if (state.ws?.readyState === WebSocket.OPEN) send({t:'ping'}); }, 20000);
