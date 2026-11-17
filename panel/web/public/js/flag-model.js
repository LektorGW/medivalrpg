// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — chorągwie krajów: czyste funkcje bez DOM (walidacja pliku, skalowanie, etykiety statusów, czas przebudowy paczki).
// Serwer (Kraje) i tak sprawdza wszystko ponownie; te funkcje służą tylko czytelnym komunikatom przed wysłaniem.

/** Zapasowe limity, gdy serwer ich nie poda (te same nazwy pól co `limits` z country.flag.get). */
export const DEFAULT_LIMITS = Object.freeze({
  maxBytes: 160 * 1024,
  width: 64,
  height: 40,
  maxColors: 32,
  formats: ["PNG", "JPEG"],
  minSourceWidth: 32,
  minSourceHeight: 20,
  maxSourcePixels: 4096 * 4096,
});
/** Pomniejszenie w przeglądarce, gdy plik jest za duży: najwyżej 256×160 px, PNG. */
export const RESIZE_MAX = Object.freeze({ width: 256, height: 160 });
/** Plik wejściowy ponad ten rozmiar jest odrzucany od razu (nie wczytujemy go do pamięci). */
export const HARD_MAX_BYTES = 15 * 1024 * 1024;
/** Limit długości `data` w Panelu (base64 razem z prefiksem). */
export const MAX_DATA_CHARS = 240000;
export const REASON_MIN = 3;
export const REASON_MAX = 200;

const MIME = { "image/png": "PNG", "image/jpeg": "JPEG", "image/jpg": "JPEG", "image/pjpeg": "JPEG" };
const EXT = { png: "PNG", jpg: "JPEG", jpeg: "JPEG" };

export function normLimits(l) {
  const src = l && typeof l === "object" ? l : {};
  const num = (v, d) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) : d);
  const formats = Array.isArray(src.formats) && src.formats.length ? src.formats.map((f) => String(f).toUpperCase()) : DEFAULT_LIMITS.formats.slice();
  return {
    maxBytes: num(src.maxBytes, DEFAULT_LIMITS.maxBytes),
    width: num(src.width, DEFAULT_LIMITS.width),
    height: num(src.height, DEFAULT_LIMITS.height),
    maxColors: num(src.maxColors, DEFAULT_LIMITS.maxColors),
    formats,
    minSourceWidth: num(src.minSourceWidth, DEFAULT_LIMITS.minSourceWidth),
    minSourceHeight: num(src.minSourceHeight, DEFAULT_LIMITS.minSourceHeight),
    maxSourcePixels: num(src.maxSourcePixels, DEFAULT_LIMITS.maxSourcePixels),
  };
}

/** Format z typu MIME albo rozszerzenia nazwy: "PNG" | "JPEG" | null. */
export function fileFormat(file) {
  if (!file) return null;
  const byMime = MIME[String(file.type || "").toLowerCase()];
  if (byMime) return byMime;
  const m = /\.([a-z0-9]+)$/i.exec(String(file.name || ""));
  return (m && EXT[m[1].toLowerCase()]) || null;
}

/** Format z pierwszych bajtów pliku (sygnatura PNG / znacznik SOI JPEG). */
export function sniffFormat(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes || []);
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47 && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) return "PNG";
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "JPEG";
  return null;
}

export function formatBytes(n) {
  const v = Number(n);
  if (!Number.isFinite(v) || v < 0) return "—";
  if (v < 1024) return `${v} B`;
  if (v < 1024 * 1024) return `${(v / 1024).toLocaleString("pl-PL", { maximumFractionDigits: 1 })} KB`;
  return `${(v / 1024 / 1024).toLocaleString("pl-PL", { maximumFractionDigits: 1 })} MB`;
}

/** Długość adresu data: dla n bajtów (base64 + prefiks). */
export function dataUrlLength(bytes, format = "PNG") {
  const prefix = format === "JPEG" ? "data:image/jpeg;base64,".length : "data:image/png;base64,".length;
  return prefix + 4 * Math.ceil(Math.max(0, Number(bytes) || 0) / 3);
}

/**
 * Pierwsza kontrola wybranego pliku (bez wczytywania): {ok, format?, error?}.
 * Za duży względem maxBytes nie jest błędem — zostanie pomniejszony w przeglądarce.
 */
export function checkFile(file, limits) {
  const l = normLimits(limits);
  if (!file) return { ok: false, error: "Wybierz plik z obrazkiem." };
  const format = fileFormat(file);
  if (!format || !l.formats.includes(format)) return { ok: false, error: `Dozwolone są tylko pliki ${l.formats.join(" i ")}.` };
  const size = Number(file.size) || 0;
  if (size <= 0) return { ok: false, error: "Plik jest pusty." };
  if (size > HARD_MAX_BYTES) return { ok: false, error: `Plik jest za duży (${formatBytes(size)}; najwyżej ${formatBytes(HARD_MAX_BYTES)}).` };
  return { ok: true, format };
}

/** Wymiary w prostokącie maxW×maxH z zachowaniem proporcji; nigdy nie powiększa. */
export function fitWithin(w, h, maxW, maxH) {
  const W = Math.max(1, Math.round(Number(w) || 1));
  const H = Math.max(1, Math.round(Number(h) || 1));
  const s = Math.min(1, maxW / W, maxH / H);
  return { width: Math.max(1, Math.round(W * s)), height: Math.max(1, Math.round(H * s)), scaled: s < 1 };
}

/** Wycinek źródła (środek) wypełniający cel dstW×dstH — tak przycina serwer: {sx, sy, sw, sh}. */
export function coverCrop(srcW, srcH, dstW, dstH) {
  const sw0 = Number(srcW) || 1;
  const sh0 = Number(srcH) || 1;
  const target = dstW / dstH;
  if (sw0 / sh0 > target) {
    const sw = sh0 * target;
    return { sx: (sw0 - sw) / 2, sy: 0, sw, sh: sh0 };
  }
  const sh = sw0 / target;
  return { sx: 0, sy: (sh0 - sh) / 2, sw: sw0, sh };
}

/**
 * Plan wysyłki po wczytaniu obrazka: {ok, resize, width, height, error?, notes[]}.
 * resize=true → pomniejszenie w canvas do ≤ RESIZE_MAX i wysłanie jako PNG.
 */
export function uploadPlan({ format, bytes, width, height }, limits) {
  const l = normLimits(limits);
  const notes = [];
  const w = Number(width) || 0;
  const h = Number(height) || 0;
  if (w < 1 || h < 1) return { ok: false, error: "Nie udało się odczytać obrazka.", notes };
  if (w < l.minSourceWidth || h < l.minSourceHeight) return { ok: false, error: `Obrazek jest za mały: ${w}×${h} px (najmniej ${l.minSourceWidth}×${l.minSourceHeight} px).`, notes };
  const tooHeavy = Number(bytes) > l.maxBytes;
  const tooBig = w * h > l.maxSourcePixels;
  const tooLong = dataUrlLength(bytes, format) > MAX_DATA_CHARS;
  if (!tooHeavy && !tooBig && !tooLong) return { ok: true, resize: false, width: w, height: h, notes };
  const fit = fitWithin(w, h, RESIZE_MAX.width, RESIZE_MAX.height);
  if (fit.width < l.minSourceWidth || fit.height < l.minSourceHeight) {
    return { ok: false, error: `Obrazek ma zbyt wydłużone proporcje (${w}×${h} px) — po pomniejszeniu byłby mniejszy niż ${l.minSourceWidth}×${l.minSourceHeight} px.`, notes };
  }
  notes.push(tooBig ? `Obrazek ma bardzo dużo pikseli — pomniejszę go do ${fit.width}×${fit.height} px.` : `Plik ma ${formatBytes(bytes)} (limit ${formatBytes(l.maxBytes)}) — pomniejszę go do ${fit.width}×${fit.height} px i wyślę jako PNG.`);
  return { ok: true, resize: true, width: fit.width, height: fit.height, notes };
}

/** Tylko obrazki z serwera w postaci data:image/png|jpeg;base64 trafiają do <img src>. */
export function safeImage(src) {
  return typeof src === "string" && /^data:image\/(png|jpeg);base64,[A-Za-z0-9+/]+={0,2}$/.test(src) ? src : null;
}

/**
 * Wzór A — zwykły sztandar z barwników dobrany z obecnej (zatwierdzonej) flagi. Kraje 463b65a5+ dokłada do `current`
 * pola `banner` (PNG), `bannerText`, `bannerDeltaE` (null, gdy wzór jest jeszcze liczony); starszy Kraje ich nie zna.
 * → {key: "UNSUPPORTED"} | {key: "PENDING"} | {key: "READY", src, text, deltaE}
 */
export function bannerPreviewState(current) {
  const c = current && typeof current === "object" ? current : {};
  if (!Object.hasOwn(c, "banner")) return { key: "UNSUPPORTED" };
  const src = typeof c.banner === "string" && c.banner.startsWith("data:image/png;") ? safeImage(c.banner) : null;
  if (!src) return { key: "PENDING" };
  const text = typeof c.bannerText === "string" && c.bannerText.trim() ? c.bannerText.trim().slice(0, 300) : null;
  const d = Number(c.bannerDeltaE);
  return { key: "READY", src, text, deltaE: c.bannerDeltaE != null && Number.isFinite(d) && d >= 0 ? d : null };
}

/** „ΔE 15,5 — dobra zgodność”: średnia różnica barw (CIE76) między flagą a sztandarem. */
export function deltaEText(d) {
  if (d == null || !Number.isFinite(Number(d))) return null;
  const v = Number(d);
  const word = v < 12 ? "bardzo dobra" : v < 20 ? "dobra" : v < 30 ? "przybliżona" : "luźna";
  return `ΔE ${v.toLocaleString("pl-PL", { minimumFractionDigits: 1, maximumFractionDigits: 1 })} — ${word} zgodność barw`;
}

/** Status obecnej chorągwi: {key, label, cls}. */
export function currentStatus(current) {
  const c = current || {};
  if (c.source !== "UPLOAD") return { key: "DEFAULT", label: "Domyślna (z koloru kraju)", cls: "info" };
  if (c.status === "WAITING_PACK") return { key: "WAITING_PACK", label: "Zatwierdzona — czeka na przebudowę paczki", cls: "warn" };
  return { key: "LIVE", label: "W grze", cls: "ok" };
}

export const HISTORY_LABELS = Object.freeze({
  APPROVED: "Zatwierdzona",
  REJECTED: "Odrzucona",
  WITHDRAWN: "Wycofana",
  REPLACED: "Zastąpiona",
  RESET: "Przywrócono domyślną",
  RESTORED: "Przywrócona z historii",
});
const HISTORY_CLS = { APPROVED: "ok", RESTORED: "ok", REJECTED: "err", WITHDRAWN: "info", REPLACED: "info", RESET: "info" };

export function historyStatus(status) {
  return { label: HISTORY_LABELS[status] || String(status || "—"), cls: HISTORY_CLS[status] || "info" };
}

/** Względny czas do przebudowy paczki: „za ok. 12 min”, „lada chwila”, null gdy brak terminu. */
export function etaText(ts, now = Date.now()) {
  const t = Number(ts);
  if (!ts || !Number.isFinite(t)) return null;
  const ms = t - now;
  if (ms <= 30000) return "lada chwila";
  if (ms < 60000) return "za mniej niż minutę";
  const min = Math.round(ms / 60000);
  if (min < 60) return `za ok. ${min} min`;
  const hrs = Math.floor(min / 60);
  const rest = min % 60;
  return rest ? `za ok. ${hrs} godz. ${rest} min` : `za ok. ${hrs} godz.`;
}

/** Zdanie o przebudowie paczki zasobów dla zatwierdzonej chorągwi. clock(ts) → „14:05”. */
export function packSentence(nextRebuildAt, now = Date.now(), clock = null) {
  const rel = etaText(nextRebuildAt, now);
  if (!rel) return "Pojawi się w grze przy najbliższej przebudowie paczki zasobów.";
  const at = clock ? clock(Number(nextRebuildAt)) : null;
  return `Pojawi się w grze po przebudowie paczki zasobów${at ? ` ok. ${at}` : ""} (${rel}).`;
}

/** Powód odrzucenia: {ok, value?, error?}. */
export function checkReason(text) {
  const v = String(text ?? "").replace(/\s+/g, " ").trim();
  if (v.length < REASON_MIN) return { ok: false, error: `Podaj powód (co najmniej ${REASON_MIN} znaki).` };
  if (v.length > REASON_MAX) return { ok: false, error: `Powód może mieć najwyżej ${REASON_MAX} znaków (teraz ${v.length}).` };
  return { ok: true, value: v };
}

/** Opis oryginału: „PNG 640×400 px · 120 KB”. */
export function originalText(o) {
  if (!o || typeof o !== "object") return "—";
  const dims = o.width && o.height ? `${o.width}×${o.height} px` : null;
  return [o.format || null, dims, o.bytes ? formatBytes(o.bytes) : null].filter(Boolean).join(" · ") || "—";
}

/** Odmowa dostępu z RPC (Kraje/Panel) → karta „Brak dostępu”. */
export function isDenied(e) {
  const msg = String((e && e.message) || "");
  return !!e && (e.status === 403 || e.app === true) && /brak (dostępu|uprawnień)|tylko moderator|moderator/i.test(msg);
}

/** Pozycje nawigacji: chorągiew dla Doradcy/Władcy, moderacja dla moderatora. */
export function flagNav(me) {
  const c = me && me.country;
  const rank = c && String(c.rank || "").toUpperCase();
  return {
    country: c && c.id && (rank === "ADVISOR" || rank === "RULER") ? "#/kraj/" + encodeURIComponent(c.id) + "/flaga" : null,
    moderation: !!(me && me.roles && me.roles.flagModerator),
  };
}
