// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Głos — klient przeglądarkowy: WebSocket do Durable Object, WebRTC mesh, przestrzenny dźwięk z Web Audio.
// Bez bibliotek. Protokół: PROTOCOL.md.

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const STUN_FALLBACK = [{ urls: "stun:stun.cloudflare.com:3478" }];
const MODES = ["whisper", "normal", "shout"];

const PEER_GONE_MS = 5000; // rozłącz, gdy peer zniknie z `peers` na > 5 s
const VAD_HANG_MS = 300; // histereza VAD
const VAD_TICK_MS = 50;
const HOUSEKEEP_MS = 500;
const CONNECT_TIMEOUT_MS = 15000; // tyle czekamy na zestawienie / ICE restart
const ANSWERER_GIVEUP_MS = 25000;
const RESTART_GAP_MS = 10000;
const PING_IDLE_MS = 25000; // ping tylko, gdy nic nie przyszło od 25 s (state płynie 5 Hz, więc zwykle nigdy)
const WS_DEAD_MS = 70000;
const WS_CONNECT_TIMEOUT_MS = 10000;
const STATE_STALE_MS = 3000;

const REASON_INVALID = "Link jest nieważny. Wpisz /glos w grze, aby dostać nowy.";
const REASON_OTHER_TAB = "Połączono w innej karcie";
const MUFFLED_HZ = 700;

// ------------------------------------------------------------------ narzędzia

const $ = (id) => document.getElementById(id);
const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const num = (v, d = 0) => (typeof v === "number" && Number.isFinite(v) ? v : d);
const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

const store = {
  get(key, def) {
    try {
      const v = localStorage.getItem("glos." + key);
      return v === null ? def : JSON.parse(v);
    } catch {
      return def;
    }
  },
  set(key, val) {
    try {
      localStorage.setItem("glos." + key, JSON.stringify(val));
    } catch {}
  },
};

function blocks(n) {
  n = Math.round(n);
  const a = Math.abs(n);
  if (a === 1) return n + " blok";
  const l = a % 10;
  const h = a % 100;
  if (l >= 2 && l <= 4 && !(h >= 12 && h <= 14)) return n + " bloki";
  return n + " bloków";
}

function keyLabel(code) {
  if (!code) return "?";
  if (code.startsWith("Key")) return code.slice(3);
  if (code.startsWith("Digit")) return code.slice(5);
  if (code.startsWith("Numpad")) return "Num " + code.slice(6);
  const map = { Space: "Spacja", ShiftLeft: "L Shift", ShiftRight: "P Shift", ControlLeft: "L Ctrl", ControlRight: "P Ctrl", AltLeft: "L Alt", AltRight: "P Alt", Backquote: "`", CapsLock: "Caps Lock", Tab: "Tab" };
  return map[code] || code;
}

// ------------------------------------------------------------------ stan aplikacji

const token = (new URLSearchParams(location.search).get("t") || "").trim();

const S = {
  // WebSocket
  ws: null,
  welcomed: false,
  attempt: 0,
  reconnectAt: 0,
  reconnectTimer: 0,
  lastMsgAt: 0,
  lastPingAt: 0,
  wsStartedAt: 0,
  kicked: false,
  myId: null,
  myName: "",
  ice: STUN_FALLBACK,
  // stan z gry
  me: null,
  lastStateAt: 0,
  adminMuted: false,
  // audio
  ctx: null,
  master: null,
  analyser: null,
  buf: null,
  micStream: null,
  micTrack: null,
  micSource: null,
  sendTrack: null,
  sendStream: null,
  noMic: false,
  clearHz: 20000,
  // ustawienia
  selfMuted: false,
  deafened: false,
  masterVol: clamp(num(store.get("master", 1), 1), 0, 2),
  threshold: clamp(num(store.get("thr", -45), -45), -80, -10),
  pttMode: store.get("tx", "vad") === "ptt",
  pttKey: typeof store.get("pttKey", "KeyV") === "string" ? store.get("pttKey", "KeyV") : "KeyV",
  pttDown: false,
  capturingKey: false,
  // VAD
  vadOn: false,
  vadLast: 0,
  transmitting: false,
  speakingSent: false,
  // peery
  peers: new Map(),
  speakingIds: new Set(),
  lastRender: 0,
};

// ------------------------------------------------------------------ ekrany i status

function show(screen) {
  for (const s of ["notoken", "connect", "kicked", "main"]) $("screen-" + s).hidden = s !== screen;
}

function setStatus(state, text) {
  const el = $("status");
  el.hidden = false;
  el.dataset.state = state;
  $("status-text").textContent = text;
}

// ------------------------------------------------------------------ start

function init() {
  if (!token) {
    show("notoken");
    return;
  }
  show("connect");
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!window.RTCPeerConnection || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !AC) {
    const err = $("connect-error");
    err.hidden = false;
    err.textContent = window.isSecureContext === false
      ? "Strona musi być otwarta przez https://, aby użyć mikrofonu."
      : "Ta przeglądarka nie obsługuje rozmów głosowych. Spróbuj aktualnego Chrome, Firefoksa albo Safari.";
    $("btn-connect").disabled = true;
    return;
  }
  refreshDevices();
  $("btn-connect").addEventListener("click", onConnect);
}

async function onConnect() {
  const btn = $("btn-connect");
  btn.disabled = true;
  btn.textContent = "Łączenie…";
  $("connect-error").hidden = true;

  // AudioContext musi powstać w geście użytkownika.
  const AC = window.AudioContext || window.webkitAudioContext;
  try {
    S.ctx = new AC({ latencyHint: "interactive" });
  } catch {
    S.ctx = new AC();
  }
  S.ctx.resume().catch(() => {});
  S.clearHz = Math.min(20000, S.ctx.sampleRate / 2 - 100);
  S.master = S.ctx.createGain();
  S.master.gain.value = S.masterVol;
  S.master.connect(S.ctx.destination);

  S.analyser = S.ctx.createAnalyser();
  S.analyser.fftSize = 1024;
  S.analyser.smoothingTimeConstant = 0;
  S.buf = new Uint8Array(S.analyser.fftSize);
  // Niektóre przeglądarki przetwarzają węzeł tylko, gdy jest podpięty do wyjścia — podpinamy przez ciszę.
  const sink = S.ctx.createGain();
  sink.gain.value = 0;
  S.analyser.connect(sink);
  sink.connect(S.ctx.destination);

  const wanted = $("mic-select-pre").value || store.get("mic", "");
  try {
    await startMic(wanted);
  } catch (e) {
    console.warn("Mikrofon niedostępny:", e);
    S.noMic = true;
    $("banner-nomic").hidden = false;
    $("banner-nomic").textContent =
      e && e.name === "NotAllowedError"
        ? "Brak zgody na mikrofon — możesz tylko słuchać. Zezwól na mikrofon w ustawieniach strony i odśwież."
        : "Nie udało się włączyć mikrofonu — możesz tylko słuchać.";
  }
  S.ctx.resume().catch(() => {});

  setupMainUi();
  show("main");
  connectWs();
  setInterval(vadTick, VAD_TICK_MS);
  setInterval(housekeeping, HOUSEKEEP_MS);
}

// ------------------------------------------------------------------ mikrofon

async function startMic(deviceId) {
  const base = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: deviceId ? { ...base, deviceId: { exact: deviceId } } : base });
  } catch (e) {
    if (!deviceId || !(e && (e.name === "OverconstrainedError" || e.name === "NotFoundError"))) throw e;
    stream = await navigator.mediaDevices.getUserMedia({ audio: base });
  }
  const track = stream.getAudioTracks()[0];
  if (!track) throw new Error("brak ścieżki audio");

  const oldStream = S.micStream;
  const oldSend = S.sendTrack;

  S.micStream = stream;
  S.micTrack = track;
  // Osobna kopia do wysyłania: wyłączamy ją (VAD/PTT/wyciszenie), a analizator dalej słyszy mikrofon.
  S.sendTrack = track.clone();
  S.sendTrack.enabled = false;
  S.transmitting = false;
  S.sendStream = new MediaStream([S.sendTrack]);
  track.addEventListener("ended", onMicEnded);

  if (S.micSource) {
    try {
      S.micSource.disconnect();
    } catch {}
  }
  S.micSource = S.ctx.createMediaStreamSource(stream);
  S.micSource.connect(S.analyser);

  for (const peer of S.peers.values()) attachSendTrack(peer);

  if (oldStream) oldStream.getTracks().forEach((t) => t.stop());
  if (oldSend) oldSend.stop();

  S.noMic = false;
  $("banner-nomic").hidden = true;
  const settings = track.getSettings ? track.getSettings() : {};
  S.currentDeviceId = settings.deviceId || deviceId || "";
  updateTransmit();
  await refreshDevices();
}

function onMicEnded() {
  if (S.kicked) return;
  // Urządzenie odłączone — spróbuj domyślnego.
  startMic("").catch(() => {
    S.noMic = true;
    $("banner-nomic").hidden = false;
    $("banner-nomic").textContent = "Mikrofon został odłączony — możesz tylko słuchać.";
    updateTransmit();
  });
}

async function refreshDevices() {
  let devices = [];
  try {
    devices = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === "audioinput");
  } catch {}
  const current = S.currentDeviceId || store.get("mic", "");
  for (const id of ["mic-select-pre", "mic-select"]) {
    const sel = $(id);
    if (!sel) continue;
    sel.textContent = "";
    const def = document.createElement("option");
    def.value = "";
    def.textContent = "Domyślny mikrofon";
    sel.appendChild(def);
    let i = 0;
    for (const d of devices) {
      if (!d.deviceId || d.deviceId === "default" || d.deviceId === "communications") continue;
      const o = document.createElement("option");
      o.value = d.deviceId;
      o.textContent = d.label || "Mikrofon " + ++i;
      sel.appendChild(o);
    }
    sel.value = [...sel.options].some((o) => o.value === current) ? current : "";
  }
}

/**
 * Mikrofon wysyłamy tylko peerom, których plugin podał nam w `state.peers` (są w zasięgu).
 * Oferta od kogoś spoza listy (np. zmodyfikowany klient po drugiej stronie mapy) dostaje odpowiedź,
 * ale bez naszego dźwięku — inaczej dało się podsłuchiwać dowolnego podłączonego gracza.
 */
function sendTrackFor(peer) {
  return peer.inPeers ? S.sendTrack : null;
}

function attachSendTrack(peer) {
  const pc = peer.pc;
  if (!pc || pc.signalingState === "closed") return;
  const tr = pc.getTransceivers ? pc.getTransceivers()[0] : null;
  if (!tr || !tr.sender) return;
  if (!peer.initiator && !pc.remoteDescription) return;
  const want = sendTrackFor(peer);
  if (tr.sender.track !== want) tr.sender.replaceTrack(want).catch(() => {});
}

// ------------------------------------------------------------------ VAD / nadawanie

function vadTick() {
  if (!S.analyser || S.kicked) return;
  S.analyser.getByteTimeDomainData(S.buf);
  let sum = 0;
  for (let i = 0; i < S.buf.length; i++) {
    const v = (S.buf[i] - 128) / 128;
    sum += v * v;
  }
  const rms = Math.sqrt(sum / S.buf.length);
  const db = rms > 0 ? 20 * Math.log10(rms) : -100;
  const live = !!S.micTrack && S.micTrack.readyState === "live";
  $("meter-fill").style.width = (live ? clamp(((db + 80) / 80) * 100, 0, 100) : 0) + "%";

  const now = performance.now();
  if (live && db > S.threshold) {
    S.vadLast = now;
    if (!S.vadOn) {
      S.vadOn = true;
      updateTransmit();
    }
  } else if (S.vadOn && now - S.vadLast > VAD_HANG_MS) {
    S.vadOn = false;
    updateTransmit();
  }
}

function updateTransmit() {
  const can = !!S.sendTrack && !S.noMic && !S.selfMuted && !S.adminMuted && !S.kicked;
  const want = S.pttMode ? S.pttDown : S.vadOn;
  const on = can && want;
  S.transmitting = on;
  if (S.sendTrack && S.sendTrack.enabled !== on) S.sendTrack.enabled = on;
  $("my-speaking").classList.toggle("on", on);
  document.querySelector(".meter").classList.toggle("on", on);
  $("btn-hold").classList.toggle("active", S.pttDown);
  sendSpeaking(on);
}

function sendSpeaking(on) {
  if (S.speakingSent === on) return;
  if (send({ t: "speaking", on })) S.speakingSent = on;
}

// ------------------------------------------------------------------ WebSocket

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${location.host}/ws?t=${encodeURIComponent(token)}`;
}

function send(obj) {
  const ws = S.ws;
  if (!ws || ws.readyState !== WebSocket.OPEN || !S.welcomed) return false;
  try {
    ws.send(JSON.stringify(obj));
    return true;
  } catch {
    return false;
  }
}

function sendSignal(to, data) {
  send({ t: "signal", to, data });
}

function connectWs() {
  if (S.kicked) return;
  clearTimeout(S.reconnectTimer);
  S.reconnectAt = 0;
  setStatus("connecting", "Łączenie…");
  let ws;
  try {
    ws = new WebSocket(wsUrl());
  } catch {
    scheduleReconnect();
    return;
  }
  S.ws = ws;
  S.welcomed = false;
  S.wsStartedAt = Date.now();
  S.lastMsgAt = Date.now();

  ws.onmessage = (ev) => {
    if (S.ws !== ws) return;
    S.lastMsgAt = Date.now();
    if (typeof ev.data !== "string") return;
    let m;
    try {
      m = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (isObj(m) && typeof m.t === "string") onMessage(m);
  };
  ws.onclose = (ev) => {
    if (S.ws !== ws) return;
    S.ws = null;
    S.welcomed = false;
    if (S.kicked) {
      if (ev.code === 4002) $("btn-here").hidden = false;
      return;
    }
    if (ev.code >= 4000 && ev.code < 4100) {
      const fallback = { 4001: REASON_INVALID, 4002: REASON_OTHER_TAB }[ev.code] || "Rozłączono przez serwer.";
      onKicked(fallback, ev.code);
      return;
    }
    scheduleReconnect();
  };
  ws.onerror = () => {};
}

function dropWs() {
  const ws = S.ws;
  S.ws = null;
  S.welcomed = false;
  if (ws) {
    try {
      ws.close();
    } catch {}
  }
}

function scheduleReconnect() {
  if (S.kicked) return;
  const base = Math.min(30000, 1000 * 2 ** Math.min(S.attempt, 5));
  const delay = Math.round(base * (0.7 + Math.random() * 0.6));
  S.attempt++;
  S.reconnectAt = Date.now() + delay;
  clearTimeout(S.reconnectTimer);
  S.reconnectTimer = setTimeout(connectWs, delay);
  updateReconnectStatus();
}

function updateReconnectStatus() {
  if (!S.reconnectAt) return;
  const s = Math.max(0, Math.ceil((S.reconnectAt - Date.now()) / 1000));
  setStatus("reconnecting", `Utracono połączenie — ponowna próba za ${s} s`);
}

function onMessage(m) {
  switch (m.t) {
    case "welcome":
      return onWelcome(m);
    case "state":
      return onState(m);
    case "signal":
      if (typeof m.from === "string" && UUID_RE.test(m.from) && isObj(m.data)) onSignal(m.from, m.data);
      return;
    case "speaking":
      if (typeof m.id === "string" && typeof m.on === "boolean") onSpeaking(m.id, m.on);
      return;
    case "kicked":
      return onKicked(typeof m.reason === "string" && m.reason ? m.reason : "Rozłączono.", 0);
    default:
      return; // m.in. "pong"
  }
}

function onWelcome(m) {
  S.welcomed = true;
  S.attempt = 0;
  if (typeof m.id === "string" && UUID_RE.test(m.id)) {
    if (S.myId && S.myId !== m.id) for (const p of [...S.peers.values()]) destroyPeer(p);
    S.myId = m.id;
  }
  S.myName = typeof m.n === "string" ? m.n : "";
  $("my-name").textContent = S.myName || "—";
  if (Array.isArray(m.ice) && m.ice.length) S.ice = m.ice.filter((s) => isObj(s) && s.urls);
  if (!S.ice.length) S.ice = STUN_FALLBACK;
  setStatus("connected", "Połączono");
  // DO po (ponownym) połączeniu uważa, że nie mówimy — wyślij aktualny stan.
  S.speakingSent = false;
  updateTransmit();
}

function onKicked(reason, code) {
  if (S.kicked) return;
  S.kicked = true;
  clearTimeout(S.reconnectTimer);
  dropWs();
  for (const p of [...S.peers.values()]) destroyPeer(p);
  if (S.micStream) S.micStream.getTracks().forEach((t) => t.stop());
  if (S.sendTrack) S.sendTrack.stop();
  if (S.ctx) S.ctx.suspend().catch(() => {});
  setStatus("kicked", "Rozłączono");
  $("kick-reason").textContent = reason;
  const otherTab = code === 4002 || reason === REASON_OTHER_TAB;
  $("btn-here").hidden = !otherTab;
  $("kick-hint").hidden = otherTab;
  show("kicked");
}

// ------------------------------------------------------------------ stan z gry

function onState(m) {
  const now = Date.now();
  S.lastStateAt = now;
  if (isObj(m.me)) applyMe(m.me);

  const seen = new Set();
  if (Array.isArray(m.peers)) {
    for (const p of m.peers) {
      if (!isObj(p) || typeof p.id !== "string" || !UUID_RE.test(p.id) || p.id === S.myId || seen.has(p.id)) continue;
      seen.add(p.id);
      let peer = S.peers.get(p.id);
      if (!peer) peer = newPeer(p.id);
      peer.n = typeof p.n === "string" && p.n ? p.n.slice(0, 32) : peer.n;
      peer.info = {
        x: num(p.x),
        y: num(p.y),
        z: num(p.z),
        r: Math.max(0, num(p.r)),
        m: p.m === true,
        mu: p.mu === true,
      };
      peer.inPeers = true;
      peer.missingSince = 0;
      if (S.myId && peer.initiator && !peer.pc && now >= peer.retryAt) createPc(peer);
      attachSendTrack(peer);
      applyPeerAudio(peer, false);
    }
  }
  for (const peer of S.peers.values()) {
    if (peer.inPeers && !seen.has(peer.id)) markMissing(peer, now);
  }
}

function markMissing(peer, since) {
  peer.inPeers = false;
  peer.missingSince = since;
  attachSendTrack(peer);
  applyPeerAudio(peer, false);
}

function applyMe(me) {
  S.me = me;
  const mode = MODES.includes(me.mode) ? me.mode : null;
  for (const b of document.querySelectorAll(".mode")) b.setAttribute("aria-pressed", String(b.dataset.mode === mode));
  $("my-range").textContent = typeof me.range === "number" ? blocks(me.range) : "—";

  const muted = me.muted === true;
  if (muted !== S.adminMuted) {
    S.adminMuted = muted;
    $("banner-muted").hidden = !muted;
    updateTransmit();
  }
  $("banner-uw").hidden = me.uw !== true;
  applyListener(num(me.yaw), num(me.pitch));
}

function applyListener(yawDeg, pitchDeg) {
  if (!S.ctx) return;
  const yaw = (yawDeg * Math.PI) / 180;
  const pitch = (clamp(pitchDeg, -89.5, 89.5) * Math.PI) / 180;
  const fx = -Math.sin(yaw) * Math.cos(pitch);
  const fy = -Math.sin(pitch);
  const fz = Math.cos(yaw) * Math.cos(pitch);
  const L = S.ctx.listener;
  const t = S.ctx.currentTime;
  if (L.forwardX) {
    const first = !S.listenerInit;
    setParam(L.forwardX, fx, t, first);
    setParam(L.forwardY, fy, t, first);
    setParam(L.forwardZ, fz, t, first);
    if (first) {
      setParam(L.upX, 0, t, true);
      setParam(L.upY, 1, t, true);
      setParam(L.upZ, 0, t, true);
      if (L.positionX) {
        setParam(L.positionX, 0, t, true);
        setParam(L.positionY, 0, t, true);
        setParam(L.positionZ, 0, t, true);
      }
    }
  } else if (L.setOrientation) {
    L.setOrientation(fx, fy, fz, 0, 1, 0);
    if (!S.listenerInit && L.setPosition) L.setPosition(0, 0, 0);
  }
  S.listenerInit = true;
}

/** Płynna zmiana parametru (state przychodzi 5 Hz). */
function setParam(param, value, t, immediate, tc = 0.06) {
  if (immediate) {
    param.cancelScheduledValues(t);
    param.setValueAtTime(value, t);
  } else {
    param.setTargetAtTime(value, t, tc);
  }
}

function onSpeaking(id, on) {
  if (on) S.speakingIds.add(id);
  else S.speakingIds.delete(id);
  const peer = S.peers.get(id);
  if (peer && peer.row) peer.row.dot.classList.toggle("on", on);
}

// ------------------------------------------------------------------ peery / WebRTC

function newPeer(id) {
  const vol = clamp(num(store.get("vol." + id, 1), 1), 0, 2);
  const peer = {
    id,
    n: id.slice(0, 8),
    info: null,
    inPeers: false,
    missingSince: Date.now(),
    pc: null,
    q: Promise.resolve(),
    pending: [],
    remoteSession: null,
    createdAt: 0,
    everConnected: false,
    badSince: 0,
    restarts: 0,
    lastRestart: 0,
    failures: 0,
    retryAt: 0,
    stream: null,
    nodes: null,
    el: null,
    row: null,
    vol,
    get initiator() {
      return !!S.myId && S.myId < this.id;
    },
  };
  S.peers.set(id, peer);
  return peer;
}

function pcState(pc) {
  if (pc.connectionState) return pc.connectionState;
  const s = pc.iceConnectionState;
  return s === "completed" ? "connected" : s === "checking" ? "connecting" : s;
}

function createPc(peer) {
  closePc(peer);
  let pc;
  try {
    pc = new RTCPeerConnection({ iceServers: S.ice });
  } catch (e) {
    console.warn("RTCPeerConnection:", e);
    pc = new RTCPeerConnection({ iceServers: STUN_FALLBACK });
  }
  peer.pc = pc;
  peer.createdAt = Date.now();
  peer.everConnected = false;
  peer.badSince = 0;
  peer.restarts = 0;
  peer.lastRestart = 0;
  peer.pending = [];
  peer.remoteSession = null;

  pc.onicecandidate = (e) => {
    if (peer.pc !== pc || !e.candidate || !e.candidate.candidate) return;
    const c = e.candidate.toJSON
      ? e.candidate.toJSON()
      : { candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex };
    sendSignal(peer.id, { candidate: c });
  };
  pc.ontrack = (e) => {
    if (peer.pc !== pc) return;
    attachRemote(peer, e.streams && e.streams[0] ? e.streams[0] : new MediaStream([e.track]));
  };
  const onState = () => {
    if (peer.pc !== pc) return;
    if (pcState(pc) === "connected") {
      peer.everConnected = true;
      peer.badSince = 0;
      peer.restarts = 0;
      peer.failures = 0;
    }
  };
  pc.onconnectionstatechange = onState;
  pc.oniceconnectionstatechange = onState;

  if (peer.initiator) {
    // Tylko peer z mniejszym uuid wysyła oferty (także przy ICE restart) — brak kolizji ofert.
    pc.onnegotiationneeded = () => {
      if (peer.pc !== pc) return;
      peer.q = peer.q.then(() => makeOffer(peer, pc)).catch((e) => console.warn("oferta:", e));
    };
    const t = sendTrackFor(peer); // recreatePc() bywa wołane także w 5-sekundowym oknie po zniknięciu z peers
    if (t) pc.addTransceiver(t, { direction: "sendrecv", streams: [S.sendStream] });
    else if (S.sendStream) pc.addTransceiver("audio", { direction: "sendrecv", streams: [S.sendStream] });
    else pc.addTransceiver("audio", { direction: "sendrecv" });
  }
  return pc;
}

async function makeOffer(peer, pc, iceRestart = false) {
  if (peer.pc !== pc || pc.signalingState !== "stable") return;
  const offer = await pc.createOffer(iceRestart ? { iceRestart: true } : undefined);
  if (peer.pc !== pc || pc.signalingState !== "stable") return;
  await pc.setLocalDescription(offer);
  const d = pc.localDescription;
  sendSignal(peer.id, { sdp: { type: d.type, sdp: d.sdp } });
}

function sdpSession(sdp) {
  const m = /^o=\S+ (\S+) /m.exec(sdp);
  return m ? m[1] : null;
}

function onSignal(from, data) {
  if (!S.myId || from === S.myId || S.kicked) return;
  let peer = S.peers.get(from);
  if (!peer) peer = newPeer(from); // oferta może przyjść przed `state` — usuniemy po 5 s, jeśli się nie pojawi
  peer.q = peer.q.then(() => handleSignal(peer, data)).catch((e) => console.warn("sygnalizacja:", e));
}

async function handleSignal(peer, data) {
  if (S.peers.get(peer.id) !== peer) return;
  if (isObj(data.sdp)) {
    const { type, sdp } = data.sdp;
    if (typeof sdp !== "string" || (type !== "offer" && type !== "answer")) return;
    if (type === "offer") {
      if (peer.initiator) return; // to my oferujemy temu peerowi
      const sess = sdpSession(sdp);
      let pc = peer.pc;
      // Nowa sesja po stronie oferującego (odtworzony RTCPeerConnection) → nowy pc także u nas.
      if (!pc || pc.signalingState !== "stable" || (peer.remoteSession && sess && sess !== peer.remoteSession)) {
        pc = createPc(peer);
      }
      peer.remoteSession = sess;
      await pc.setRemoteDescription({ type, sdp });
      if (peer.pc !== pc) return;
      const tr = pc.getTransceivers()[0];
      if (tr) {
        try {
          tr.direction = "sendrecv";
        } catch {}
        const want = sendTrackFor(peer);
        if (tr.sender.track !== want) await tr.sender.replaceTrack(want);
        if (S.sendStream && tr.sender.setStreams) {
          try {
            tr.sender.setStreams(S.sendStream);
          } catch {}
        }
      }
      await flushCandidates(peer, pc);
      const answer = await pc.createAnswer();
      if (peer.pc !== pc) return;
      await pc.setLocalDescription(answer);
      const d = pc.localDescription;
      sendSignal(peer.id, { sdp: { type: d.type, sdp: d.sdp } });
    } else {
      const pc = peer.pc;
      if (!peer.initiator || !pc || pc.signalingState !== "have-local-offer") return;
      await pc.setRemoteDescription({ type, sdp });
      await flushCandidates(peer, pc);
    }
  } else if ("candidate" in data) {
    const c = data.candidate;
    if (!isObj(c) || typeof c.candidate !== "string") return;
    const pc = peer.pc;
    if (!pc || !pc.remoteDescription) {
      if (peer.pending.length < 200) peer.pending.push(c);
      return;
    }
    try {
      await pc.addIceCandidate(c);
    } catch {}
  }
}

async function flushCandidates(peer, pc) {
  const list = peer.pending.splice(0);
  for (const c of list) {
    try {
      await pc.addIceCandidate(c);
    } catch {}
  }
}

function iceRestart(peer) {
  const pc = peer.pc;
  if (!pc) return;
  peer.restarts++;
  peer.lastRestart = Date.now();
  if (pc.restartIce) pc.restartIce(); // wywoła negotiationneeded → oferta z nowymi danymi ICE
  else peer.q = peer.q.then(() => makeOffer(peer, pc, true)).catch(() => {});
}

function recreatePc(peer, now) {
  closePc(peer);
  peer.failures++;
  peer.retryAt = now + Math.min(30000, 1000 * 2 ** peer.failures);
}

function closePc(peer) {
  const pc = peer.pc;
  peer.pc = null;
  peer.pending = [];
  peer.remoteSession = null;
  if (pc) {
    pc.onicecandidate = pc.ontrack = pc.onconnectionstatechange = pc.oniceconnectionstatechange = pc.onnegotiationneeded = null;
    try {
      pc.close();
    } catch {}
  }
  detachRemote(peer);
}

function destroyPeer(peer) {
  closePc(peer);
  if (peer.row) peer.row.li.remove();
  peer.row = null;
  S.speakingIds.delete(peer.id);
  S.peers.delete(peer.id);
}

/** Pilnowanie połączeń: ICE restart / ponowne zestawienie po `failed`. */
function watchPeer(peer, now) {
  const pc = peer.pc;
  if (!pc) return;
  const st = pcState(pc);
  if (st === "connected") return;
  if (!peer.badSince) peer.badSince = now;

  if (peer.initiator) {
    const since = Math.max(peer.createdAt, peer.lastRestart);
    const broken = st === "failed" || st === "closed" || (st === "disconnected" && now - peer.badSince > 5000);
    const stuck = (st === "new" || st === "connecting" || st === "checking") && now - since > CONNECT_TIMEOUT_MS;
    if (!broken && !stuck) return;
    if (now - since < RESTART_GAP_MS && st !== "closed") return;
    if (peer.everConnected && peer.restarts < 2 && st !== "closed" && pc.signalingState === "stable") iceRestart(peer);
    else recreatePc(peer, now);
  } else if ((st === "failed" || st === "closed" || st === "disconnected") && now - peer.badSince > ANSWERER_GIVEUP_MS) {
    // Strona odpowiadająca czeka na nową ofertę (ICE restart albo nowa sesja).
    closePc(peer);
  }
}

// ------------------------------------------------------------------ audio odbiorcze

function attachRemote(peer, stream) {
  if (peer.stream === stream && peer.nodes) return;
  detachRemote(peer);
  const ctx = S.ctx;
  peer.stream = stream;

  // Obejście błędu Chrome: zdalny strumień musi być też podpięty do (wyciszonego) elementu <audio>,
  // inaczej MediaStreamSource dostaje ciszę.
  const el = document.createElement("audio");
  el.muted = true;
  el.autoplay = true;
  el.playsInline = true;
  el.setAttribute("playsinline", "");
  el.srcObject = stream;
  $("audio-sink").appendChild(el);
  const p = el.play();
  if (p && p.catch) p.catch(() => {});
  peer.el = el;

  const src = ctx.createMediaStreamSource(stream);
  const filter = ctx.createBiquadFilter();
  filter.type = "lowpass";
  filter.Q.value = 0;
  filter.frequency.value = peer.info && peer.info.m ? MUFFLED_HZ : S.clearHz;
  const gain = ctx.createGain();
  gain.gain.value = 0;
  const panner = ctx.createPanner();
  panner.panningModel = "HRTF";
  panner.distanceModel = "linear";
  panner.refDistance = 1;
  panner.maxDistance = 24;
  panner.rolloffFactor = 1;
  panner.coneInnerAngle = 360;
  panner.coneOuterAngle = 360;
  panner.coneOuterGain = 1;

  src.connect(filter);
  filter.connect(gain);
  gain.connect(panner);
  panner.connect(S.master);
  peer.nodes = { src, filter, gain, panner, init: false };
  applyPeerAudio(peer, true);
}

function detachRemote(peer) {
  const n = peer.nodes;
  if (n) {
    for (const node of [n.src, n.filter, n.gain, n.panner]) {
      try {
        node.disconnect();
      } catch {}
    }
  }
  if (peer.el) {
    try {
      peer.el.pause();
    } catch {}
    peer.el.srcObject = null;
    peer.el.remove();
  }
  peer.nodes = null;
  peer.el = null;
  peer.stream = null;
}

function peerDistance(i) {
  return Math.sqrt(i.x * i.x + i.y * i.y + i.z * i.z);
}

function applyPeerAudio(peer, immediate) {
  const n = peer.nodes;
  if (!n || !S.ctx) return;
  const t = S.ctx.currentTime;
  const i = peer.info;
  let g = 0;
  if (i && peer.inPeers && !i.mu && peerDistance(i) <= i.r) g = peer.vol;
  const first = immediate || !n.init;
  setParam(n.gain.gain, g, t, false, 0.08); // gain zawsze płynnie (bez trzasków)
  if (i) {
    n.panner.maxDistance = Math.max(1.01, i.r);
    if (n.panner.positionX) {
      setParam(n.panner.positionX, i.x, t, first, 0.08);
      setParam(n.panner.positionY, i.y, t, first, 0.08);
      setParam(n.panner.positionZ, i.z, t, first, 0.08);
    } else if (n.panner.setPosition) {
      n.panner.setPosition(i.x, i.y, i.z);
    }
    setParam(n.filter.frequency, i.m ? MUFFLED_HZ : S.clearHz, t, first, 0.1);
    n.init = true;
  }
}

function applyMaster() {
  if (!S.master) return;
  setParam(S.master.gain, S.deafened ? 0 : S.masterVol, S.ctx.currentTime, false, 0.05);
}

// ------------------------------------------------------------------ pętla porządkowa (500 ms)

function housekeeping() {
  if (S.kicked) return;
  const now = Date.now();
  const ws = S.ws;

  // WebSocket: ping tylko przy ciszy, wykrywanie martwego połączenia.
  if (ws && ws.readyState === WebSocket.CONNECTING && now - S.wsStartedAt > WS_CONNECT_TIMEOUT_MS) {
    dropWs();
    scheduleReconnect();
  } else if (ws && ws.readyState === WebSocket.OPEN) {
    if (now - S.lastMsgAt > WS_DEAD_MS) {
      dropWs();
      scheduleReconnect();
    } else if (now - S.lastMsgAt > PING_IDLE_MS && now - S.lastPingAt > PING_IDLE_MS) {
      S.lastPingAt = now;
      try {
        ws.send('{"t":"ping"}');
      } catch {}
    }
  }
  if (!S.ws) updateReconnectStatus();

  // Brak `state` (np. plugin offline) — po 5 s traktujemy wszystkich jak znikniętych.
  const stale = !S.lastStateAt || now - S.lastStateAt > STATE_STALE_MS;
  if (S.welcomed) {
    $("banner-wait").hidden = !stale;
    if (stale) setStatus("waiting", "Połączono — czekam na grę");
    else setStatus("connected", "Połączono");
  } else {
    $("banner-wait").hidden = true;
  }
  if (S.lastStateAt && now - S.lastStateAt > PEER_GONE_MS) {
    for (const peer of S.peers.values()) if (peer.inPeers) markMissing(peer, S.lastStateAt);
  }

  for (const peer of [...S.peers.values()]) {
    if (!peer.inPeers && peer.missingSince && now - peer.missingSince > PEER_GONE_MS) {
      destroyPeer(peer);
      continue;
    }
    if (S.myId && S.welcomed && peer.inPeers && peer.initiator && !peer.pc && now >= peer.retryAt) createPc(peer);
    watchPeer(peer, now);
  }

  $("banner-audio").hidden = !S.ctx || S.ctx.state === "running";
  renderPeers(now);
}

// ------------------------------------------------------------------ UI

function setupMainUi() {
  $("my-name").textContent = "—";

  for (const b of document.querySelectorAll(".mode")) {
    b.addEventListener("click", () => send({ t: "mode", mode: b.dataset.mode }));
  }

  $("btn-mute").addEventListener("click", toggleMute);
  $("btn-deafen").addEventListener("click", () => {
    S.deafened = !S.deafened;
    $("btn-deafen").setAttribute("aria-pressed", String(S.deafened));
    $("btn-deafen").textContent = S.deafened ? "Włącz dźwięk" : "Wycisz wszystkich";
    applyMaster();
  });

  const master = $("master");
  master.value = String(Math.round(S.masterVol * 100));
  $("master-val").textContent = master.value + "%";
  master.addEventListener("input", () => {
    S.masterVol = clamp(Number(master.value) / 100, 0, 2);
    $("master-val").textContent = master.value + "%";
    store.set("master", S.masterVol);
    applyMaster();
  });

  const thr = $("threshold");
  thr.value = String(S.threshold);
  const showThr = () => {
    $("thr-val").textContent = S.threshold + " dB";
    $("meter-thr").style.left = clamp(((S.threshold + 80) / 80) * 100, 0, 100) + "%";
  };
  showThr();
  thr.addEventListener("input", () => {
    S.threshold = clamp(Number(thr.value), -80, -10);
    store.set("thr", S.threshold);
    showThr();
  });

  for (const r of document.querySelectorAll('input[name="tx"]')) {
    r.checked = (r.value === "ptt") === S.pttMode;
    r.addEventListener("change", () => {
      if (!r.checked) return;
      S.pttMode = r.value === "ptt";
      store.set("tx", S.pttMode ? "ptt" : "vad");
      showTxMode();
      updateTransmit();
    });
  }
  showTxMode();

  const keyBtn = $("btn-pttkey");
  keyBtn.textContent = keyLabel(S.pttKey);
  keyBtn.addEventListener("click", () => {
    S.capturingKey = true;
    keyBtn.textContent = "Naciśnij klawisz…";
  });

  const hold = $("btn-hold");
  const holdOn = (e) => {
    e.preventDefault();
    if (hold.setPointerCapture && e.pointerId !== undefined) {
      try {
        hold.setPointerCapture(e.pointerId);
      } catch {}
    }
    S.pttDown = true;
    updateTransmit();
  };
  const holdOff = () => {
    if (!S.pttDown) return;
    S.pttDown = false;
    updateTransmit();
  };
  hold.addEventListener("pointerdown", holdOn);
  hold.addEventListener("pointerup", holdOff);
  hold.addEventListener("pointercancel", holdOff);
  hold.addEventListener("lostpointercapture", holdOff);
  hold.addEventListener("contextmenu", (e) => e.preventDefault());

  document.addEventListener("keydown", onKeyDown);
  document.addEventListener("keyup", onKeyUp);
  window.addEventListener("blur", () => {
    if (S.pttDown) {
      S.pttDown = false;
      updateTransmit();
    }
  });

  $("mic-select").addEventListener("change", async (e) => {
    const id = e.target.value;
    store.set("mic", id);
    try {
      await startMic(id);
    } catch (err) {
      console.warn("Zmiana mikrofonu:", err);
    }
  });
  if (navigator.mediaDevices && navigator.mediaDevices.addEventListener) {
    navigator.mediaDevices.addEventListener("devicechange", () => refreshDevices());
  }

  // Mobilne przeglądarki potrafią wstrzymać AudioContext (np. połączenie telefoniczne).
  const resume = () => {
    if (S.ctx && S.ctx.state !== "running" && !S.kicked) S.ctx.resume().catch(() => {});
  };
  $("banner-audio").addEventListener("click", resume);
  document.addEventListener("pointerdown", resume, { passive: true });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) resume();
  });

  $("btn-here").addEventListener("click", () => location.reload());
  window.addEventListener("pagehide", () => dropWs());
}

function showTxMode() {
  $("vad-wrap").hidden = S.pttMode;
  $("ptt-wrap").hidden = !S.pttMode;
}

function toggleMute() {
  S.selfMuted = !S.selfMuted;
  const b = $("btn-mute");
  b.setAttribute("aria-pressed", String(S.selfMuted));
  b.textContent = "";
  b.append(S.selfMuted ? "Włącz mikrofon " : "Wycisz mikrofon ");
  const k = document.createElement("kbd");
  k.textContent = "M";
  b.append(k);
  updateTransmit();
}

function isTypingTarget(t) {
  return t && (t.tagName === "SELECT" || t.tagName === "TEXTAREA" || (t.tagName === "INPUT" && !["range", "radio", "checkbox", "button"].includes(t.type)));
}

function onKeyDown(e) {
  if (S.kicked) return;
  if (S.capturingKey) {
    e.preventDefault();
    if (e.code === "Escape") {
      S.capturingKey = false;
      $("btn-pttkey").textContent = keyLabel(S.pttKey);
      return;
    }
    S.capturingKey = false;
    S.pttKey = e.code;
    store.set("pttKey", S.pttKey);
    $("btn-pttkey").textContent = keyLabel(S.pttKey);
    return;
  }
  if (isTypingTarget(e.target) || e.ctrlKey || e.metaKey || e.altKey) return;
  if (S.pttMode && e.code === S.pttKey) {
    e.preventDefault();
    if (!e.repeat && !S.pttDown) {
      S.pttDown = true;
      updateTransmit();
    }
    return;
  }
  if (e.code === "KeyM" && !e.repeat) {
    e.preventDefault();
    toggleMute();
  }
}

function onKeyUp(e) {
  if (S.pttMode && e.code === S.pttKey && S.pttDown) {
    S.pttDown = false;
    updateTransmit();
  }
}

function renderPeers(now) {
  const list = [];
  for (const p of S.peers.values()) {
    if (p.info && (p.inPeers || now - p.missingSince < PEER_GONE_MS)) list.push(p);
  }
  for (const p of list) p._d = peerDistance(p.info);
  list.sort((a, b) => Math.round(a._d) - Math.round(b._d) || (a.n < b.n ? -1 : 1));

  const ul = $("peer-list");
  const keep = new Set();
  list.forEach((p, idx) => {
    const row = p.row || (p.row = buildRow(p));
    updateRow(p, row);
    keep.add(row.li);
    if (ul.children[idx] !== row.li) ul.insertBefore(row.li, ul.children[idx] || null);
  });
  for (const li of [...ul.children]) if (!keep.has(li)) li.remove();
  const audible = list.filter((p) => p.inPeers && !p.info.mu && p._d <= p.info.r).length;
  $("peer-count").textContent = list.length ? `— słyszysz ${audible} z ${list.length}` : "";
  $("peer-empty").hidden = list.length > 0;
}

function buildRow(p) {
  const li = document.createElement("li");
  li.className = "peer";
  const dot = document.createElement("span");
  dot.className = "speak-dot";
  const name = document.createElement("span");
  name.className = "name";
  const dist = document.createElement("span");
  dist.className = "dist";
  const tags = document.createElement("div");
  tags.className = "tags";
  const vol = document.createElement("label");
  vol.className = "vol";
  const volText = document.createElement("span");
  volText.textContent = "Głośność";
  const slider = document.createElement("input");
  slider.type = "range";
  slider.min = "0";
  slider.max = "200";
  slider.step = "5";
  slider.value = String(Math.round(p.vol * 100));
  const out = document.createElement("output");
  out.textContent = slider.value + "%";
  slider.addEventListener("input", () => {
    p.vol = clamp(Number(slider.value) / 100, 0, 2);
    out.textContent = slider.value + "%";
    store.set("vol." + p.id, p.vol);
    applyPeerAudio(p, false);
  });
  vol.append(volText, slider, out);
  li.append(dot, name, dist, tags, vol);
  return { li, dot, name, dist, tags, slider, last: "" };
}

function updateRow(p, row) {
  const i = p.info;
  const inRange = p.inPeers && p._d <= i.r;
  const tags = [];
  const st = p.pc ? pcState(p.pc) : "none";
  if (!p.inPeers) tags.push(["Odchodzi…", ""]);
  else if (st !== "connected") tags.push([st === "failed" ? "Problem z połączeniem" : "Łączenie…", st === "failed" ? "bad" : ""]);
  if (p.inPeers && !inRange) tags.push(["Poza zasięgiem", ""]);
  if (i.mu) tags.push(["Wyciszony przez administrację", "bad"]);
  if (i.m) tags.push(["Stłumiony", ""]);
  const key = p.n + "|" + Math.round(p._d) + "|" + tags.map((t) => t[0]).join(",") + "|" + inRange;
  row.dot.classList.toggle("on", S.speakingIds.has(p.id));
  if (key === row.last) return;
  row.last = key;
  row.name.textContent = p.n;
  row.dist.textContent = Math.round(p._d) + " bl.";
  row.tags.textContent = "";
  for (const [text, cls] of tags) {
    const s = document.createElement("span");
    s.className = "tag" + (cls ? " " + cls : "");
    s.textContent = text;
    row.tags.appendChild(s);
  }
  row.li.classList.toggle("far", !inRange || i.mu);
}

// Podgląd stanu do testów (tylko z ?debug w adresie).
if (new URLSearchParams(location.search).has("debug")) window.glosDebug = S;

init();
