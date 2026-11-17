// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Atrapa chorągwi krajów (Kraje → flagPanel) dla fałszywego pluginu: wgranie tworzy obrazek w kolejce, zatwierdzenie
// przenosi go do obecnej chorągwi ze statusem WAITING_PACK (do przebudowy paczki), odrzucenie zapisuje lastRejection.
// Moderator = admin panelu albo UUID z listy moderatorów (W.flagModerators). Wgrywać może tylko Doradca/Władca danego kraju.
import { deflateSync } from "node:zlib";
import { randomBytes } from "node:crypto";

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

/** PNG RGBA w×h; pixel(x, y) → [r, g, b, a]. */
export function pngBuffer(w, h, pixel) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const [r, g, b, a = 255] = pixel(x, y);
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = r;
      raw[o + 1] = g;
      raw[o + 2] = b;
      raw[o + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}
export const pngDataUrl = (w, h, pixel) => "data:image/png;base64," + pngBuffer(w, h, pixel).toString("base64");

const rgb = (hex) => [1, 3, 5].map((i) => parseInt(String(hex || "#8a7350").slice(i, i + 2), 16) || 0);
const mix = (a, b, t) => a.map((v, i) => Math.round(v * (1 - t) + b[i] * t));

/** Domyślna chorągiew z koloru kraju: pole w kolorze, jaśniejszy pas i ciemny brzeg. */
export function defaultFlag(color) {
  const c = rgb(color);
  const light = mix(c, [255, 255, 255], 0.45);
  const dark = mix(c, [0, 0, 0], 0.45);
  return pngDataUrl(64, 40, (x, y) => (x < 2 || y < 2 || x > 61 || y > 37 ? dark : y >= 16 && y < 24 ? light : c));
}

/** „Przetworzona” chorągiew z wgrania: wzór zależny od skrótu obrazka (atrapa skalowania do 64×40). */
function processedFlag(seed) {
  const a = [seed[0], seed[1], seed[2]];
  const b = [255 - seed[3], 255 - seed[4], (seed[5] + 128) & 255];
  const gold = [226, 191, 116];
  return pngDataUrl(64, 40, (x, y) => {
    const dx = x - 32, dy = y - 20;
    if (dx * dx + dy * dy * 2.2 < 90) return gold;
    return (x >> 3) % 2 === (y >> 3) % 2 ? a : b;
  });
}

/** Podgląd sztandaru 20×40 ×4 (jak Kraje BannerFit.previewPng(design, 4)): tło + pas + opcjonalne koło. */
export function bannerPng(color, upload) {
  const c = upload ? [176, 46, 38] : rgb(color);
  const light = upload ? [249, 255, 254] : mix(c, [255, 255, 255], 0.45);
  const gold = [254, 216, 61];
  return pngDataUrl(80, 160, (x, y) => {
    const bx = x >> 2, by = y >> 2;
    if (upload && Math.hypot(bx - 10, by - 18) < 5) return gold;
    if (upload ? by < 18 : by >= 16 && by < 24) return light;
    return c;
  });
}

function decodeImage(data) {
  const m = /^(?:data:image\/(png|jpeg);base64,)?([A-Za-z0-9+/]+={0,2})$/.exec(String(data || ""));
  if (!m) return null;
  const buf = Buffer.from(m[2], "base64");
  if (buf.length >= 24 && buf.readUInt32BE(0) === 0x89504e47) return { format: "PNG", bytes: buf.length, width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), buf };
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return { format: "JPEG", bytes: buf.length, width: 640, height: 400, buf };
  return null;
}

const LIMITS = { maxBytes: 160 * 1024, width: 64, height: 40, maxColors: 32, formats: ["PNG", "JPEG"], minSourceWidth: 32, minSourceHeight: 20, maxSourcePixels: 4096 * 4096 };
const W8 = { RULER: 3, ADVISOR: 2, CITIZEN: 1 };

export function flagMethods(W, { err, okd }) {
  W.flagModerators = W.flagModerators || new Set([W.U.maciej.uuid]);
  W.flagPack = W.flagPack || { nextRebuildAt: null, lastRebuildAt: Date.now() - 3 * 3600 * 1000, minIntervalMinutes: 30, queued: 0 };
  W.flags = W.flags || {};
  W.flagRecent = W.flagRecent || [];
  W.isFlagModerator = (u) => !!u && (!!u.admin || W.flagModerators.has(u.uuid));

  const state = (id) => {
    const c = W.C[id];
    if (!c) return null;
    if (!W.flags[id]) W.flags[id] = { current: { source: "DEFAULT", imageId: null, approvedAt: null, approvedBy: null, status: "LIVE", liveSince: null }, pending: null, lastRejection: null, history: [], images: {} };
    return W.flags[id];
  };
  const country = (id) => {
    const c = W.C[id];
    if (!c) throw new Error("Nie ma takiego kraju.");
    return c;
  };
  const rank = (u, c) => W8[c.members[u.uuid]] || 0;
  const access = (u, c) => ({ view: true, upload: rank(u, c) >= 2, manage: rank(u, c) >= 2 || !!u.admin, moderate: W.isFlagModerator(u) });
  const currentPng = (c, s) => (s.current.source === "UPLOAD" ? s.images[s.current.imageId] : defaultFlag(c.color));
  const pack = () => ({ ...W.flagPack });
  const schedule = () => {
    const p = W.flagPack;
    const earliest = (p.lastRebuildAt || 0) + p.minIntervalMinutes * 60000;
    p.nextRebuildAt = Math.max(Date.now() + 5 * 60000, earliest);
    p.queued += 1;
    return p.nextRebuildAt;
  };
  const restorable = (s, e) => !!e.imageId && !!s.images[e.imageId] && (e.status === "APPROVED" || e.status === "RESTORED") && e.imageId !== s.current.imageId;
  const moderatorOnly = (u) => {
    if (!W.isFlagModerator(u)) throw new Error("Tylko moderator chorągwi może to zrobić.");
  };
  const needRank = (u, c) => {
    if (!c.members[u.uuid]) throw new Error("Nie jesteś członkiem tego kraju.");
    if (rank(u, c) < 2) throw new Error("Chorągiew zmieniają tylko Władca i Doradcy.");
  };

  const m = {};
  // Wzór A sztandaru (Kraje 463b65a5+): W.flagBanners = "old" (Kraje 9240f7b2 — brak pól), "pending" (null, wzór liczony)
  // albo "ready" (domyślnie dla atrapy nowego Kraje). Bez ustawienia atrapa udaje obecną produkcję (old).
  const bannerFields = (c, s) => {
    const mode = W.flagBanners || "old";
    if (mode === "old") return {};
    if (mode === "pending") return { banner: null, bannerText: null, bannerDeltaE: null };
    const upload = s.current.source === "UPLOAD";
    return {
      banner: bannerPng(c.color, upload),
      bannerText: upload ? "tło: czerwony; połowa górna: biały; koło: żółty" : "tło: brązowy; pas środkowy: jasnoszary",
      bannerDeltaE: upload ? 15.5 : 9.2,
    };
  };
  m["country.flag.get"] = (u, p) => {
    const c = country(p.id);
    const s = state(p.id);
    const a = access(u, c);
    const out = {
      id: c.id, name: c.name, color: c.color, npc: !!c.npc, access: a,
      current: { ...s.current, png: currentPng(c, s), ...bannerFields(c, s) },
      limits: { ...LIMITS },
      pack: pack(),
    };
    if (a.upload || a.moderate) {
      out.pending = s.pending ? { ...s.pending } : null;
      out.lastRejection = s.lastRejection ? { ...s.lastRejection } : null;
      out.history = s.history.map((e) => ({ ...e, png: e.imageId ? s.images[e.imageId] || null : null, restorable: restorable(s, e) }));
    }
    return okd(out);
  };
  m["country.flag.upload"] = (u, p) => {
    const c = country(p.id);
    needRank(u, c);
    if (String(p.data || "").length > 240000) throw new Error("Nieprawidłowy parametr chorągwi.");
    const img = decodeImage(p.data);
    if (!img) throw new Error("Dozwolone są tylko obrazki PNG i JPEG.");
    if (img.width < LIMITS.minSourceWidth || img.height < LIMITS.minSourceHeight) throw new Error(`Obrazek jest za mały (najmniej ${LIMITS.minSourceWidth}×${LIMITS.minSourceHeight} px).`);
    if (img.bytes > LIMITS.maxBytes) throw new Error("Plik jest za duży.");
    const s = state(p.id);
    if (s.pending) s.history.unshift({ imageId: s.pending.imageId, status: "WITHDRAWN", at: Date.now(), by: u.name, note: "Zastąpiony nowym wgraniem" });
    const imageId = randomBytes(8).toString("hex");
    const png = processedFlag(randomBytes(6));
    s.images[imageId] = png;
    s.pending = { imageId, png, uploadedAt: Date.now(), uploadedBy: u.name, original: { width: img.width, height: img.height, format: img.format, bytes: img.bytes }, colors: 3 };
    W.lastFlagUpload = { country: c.id, filename: p.filename || null, chars: String(p.data).length };
    return okd({ ok: true, message: "Obrazek czeka na zatwierdzenie moderatora.", pending: { ...s.pending } });
  };
  m["country.flag.withdraw"] = (u, p) => {
    const c = country(p.id);
    needRank(u, c);
    const s = state(p.id);
    if (!s.pending) throw new Error("Nic nie czeka na zatwierdzenie.");
    s.history.unshift({ imageId: s.pending.imageId, status: "WITHDRAWN", at: Date.now(), by: u.name, note: "" });
    s.pending = null;
    return okd({ ok: true, message: "Obrazek wycofany z kolejki." });
  };
  m["country.flag.reset"] = (u, p) => {
    const c = country(p.id);
    if (!access(u, c).manage) throw new Error("Chorągiew zmieniają tylko Władca i Doradcy.");
    const s = state(p.id);
    if (s.current.source === "DEFAULT") throw new Error("Kraj ma już domyślną chorągiew.");
    s.history.unshift({ imageId: null, status: "RESET", at: Date.now(), by: u.name, note: "" });
    s.current = { source: "DEFAULT", imageId: null, approvedAt: null, approvedBy: null, status: "LIVE", liveSince: Date.now() };
    return okd({ ok: true, message: "Przywrócono domyślną chorągiew." });
  };
  m["country.flag.restore"] = (u, p) => {
    const c = country(p.id);
    if (!access(u, c).manage) throw new Error("Chorągiew zmieniają tylko Władca i Doradcy.");
    const s = state(p.id);
    const e = s.history.find((x) => x.imageId === p.imageId && restorable(s, x));
    if (!e) throw new Error("Tej chorągwi nie można przywrócić.");
    s.current = { source: "UPLOAD", imageId: p.imageId, approvedAt: Date.now(), approvedBy: u.name, status: "WAITING_PACK", liveSince: null };
    s.history.unshift({ imageId: p.imageId, status: "RESTORED", at: Date.now(), by: u.name, note: "" });
    return okd({ ok: true, message: "Chorągiew przywrócona — pojawi się po przebudowie paczki.", nextRebuildAt: schedule() });
  };
  m["admin.flag.queue"] = (u) => {
    moderatorOnly(u);
    const items = Object.entries(W.flags)
      .filter(([id, s]) => s.pending && W.C[id])
      .map(([id, s]) => ({ id, name: W.C[id].name, color: W.C[id].color, npc: !!W.C[id].npc, ...s.pending, currentPng: currentPng(W.C[id], s) }))
      .sort((a, b) => a.uploadedAt - b.uploadedAt);
    return okd({ items, recent: W.flagRecent.slice(0, 20), pack: pack() });
  };
  m["admin.flag.approve"] = (u, p) => {
    moderatorOnly(u);
    const c = country(p.id);
    const s = state(p.id);
    if (!s.pending || s.pending.imageId !== p.imageId) throw new Error("Ten obrazek nie czeka już na zatwierdzenie.");
    s.current = { source: "UPLOAD", imageId: p.imageId, approvedAt: Date.now(), approvedBy: u.name, status: "WAITING_PACK", liveSince: null };
    s.history.unshift({ imageId: p.imageId, status: "APPROVED", at: Date.now(), by: u.name, note: "" });
    s.pending = null;
    W.flagRecent.unshift({ id: c.id, name: c.name, imageId: p.imageId, status: "APPROVED", at: Date.now(), by: u.name, note: "" });
    return okd({ ok: true, message: `Zatwierdzono chorągiew kraju ${c.name}.`, nextRebuildAt: schedule() });
  };
  m["admin.flag.reject"] = (u, p) => {
    moderatorOnly(u);
    const c = country(p.id);
    const s = state(p.id);
    const reason = String(p.reason || "").trim();
    if (reason.length < 3 || reason.length > 200) throw new Error("Powód odrzucenia musi mieć od 3 do 200 znaków.");
    if (!s.pending || s.pending.imageId !== p.imageId) throw new Error("Ten obrazek nie czeka już na zatwierdzenie.");
    s.lastRejection = { imageId: p.imageId, at: Date.now(), by: u.name, reason };
    s.history.unshift({ imageId: p.imageId, status: "REJECTED", at: Date.now(), by: u.name, note: reason });
    s.pending = null;
    W.flagRecent.unshift({ id: c.id, name: c.name, imageId: p.imageId, status: "REJECTED", at: Date.now(), by: u.name, note: reason });
    return okd({ ok: true, message: `Odrzucono chorągiew kraju ${c.name}.` });
  };
  m["admin.flag.rebuild"] = (u) => {
    moderatorOnly(u);
    for (const s of Object.values(W.flags)) if (s.current.status === "WAITING_PACK") Object.assign(s.current, { status: "LIVE", liveSince: Date.now() });
    Object.assign(W.flagPack, { lastRebuildAt: Date.now(), nextRebuildAt: null, queued: 0 });
    return okd({ ok: true, message: "Paczka zasobów przebudowana.", nextRebuildAt: null });
  };
  // Atrapa jak Kraje: błąd reguły → komunikat; wyjątki zamieniane na odpowiedź błędu.
  for (const [k, f] of Object.entries(m)) {
    m[k] = (u, p) => {
      try {
        return f(u, p || {});
      } catch (e) {
        return err(e.message);
      }
    };
  }
  return m;
}
