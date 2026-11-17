// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Chorągwie krajów: czyste funkcje (public/js/flag-model.js) i atrapa Kraje (test/fake-flag.mjs) przez fałszywy plugin.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  normLimits, fileFormat, sniffFormat, checkFile, fitWithin, coverCrop, uploadPlan, safeImage, currentStatus, historyStatus, etaText,
  packSentence, checkReason, originalText, isDenied, flagNav, dataUrlLength, formatBytes, DEFAULT_LIMITS, HARD_MAX_BYTES, MAX_DATA_CHARS,
  bannerPreviewState, deltaEText,
} from "../public/js/flag-model.js";
import { createWorld, makeHandlers } from "./fake-world.mjs";
import { pngBuffer, pngDataUrl } from "./fake-flag.mjs";

const L = { maxBytes: 100_000, width: 64, height: 40, maxColors: 16, formats: ["PNG", "JPEG"], minSourceWidth: 32, minSourceHeight: 20, maxSourcePixels: 1_000_000 };

test("limity: wartości z serwera i zapasowe", () => {
  assert.deepEqual(normLimits(L), L);
  assert.deepEqual(normLimits(null), { ...DEFAULT_LIMITS, formats: ["PNG", "JPEG"] });
  assert.equal(normLimits({ maxBytes: -5, formats: ["png"] }).maxBytes, DEFAULT_LIMITS.maxBytes);
  assert.deepEqual(normLimits({ formats: ["png"] }).formats, ["PNG"]);
});

test("format pliku: MIME, rozszerzenie i sygnatura", () => {
  assert.equal(fileFormat({ type: "image/png", name: "x" }), "PNG");
  assert.equal(fileFormat({ type: "image/jpeg", name: "x" }), "JPEG");
  assert.equal(fileFormat({ type: "", name: "Herb.JPG" }), "JPEG");
  assert.equal(fileFormat({ type: "image/gif", name: "a.gif" }), null);
  assert.equal(fileFormat({ type: "image/svg+xml", name: "a.png.svg" }), null);
  assert.equal(sniffFormat(pngBuffer(2, 2, () => [0, 0, 0])), "PNG");
  assert.equal(sniffFormat(new Uint8Array([0xff, 0xd8, 0xff, 0xe0])), "JPEG");
  assert.equal(sniffFormat(new Uint8Array([0x47, 0x49, 0x46, 0x38])), null);
  assert.equal(sniffFormat(new Uint8Array([])), null);
});

test("kontrola pliku przed wczytaniem", () => {
  assert.deepEqual(checkFile({ type: "image/png", name: "a.png", size: 5000 }, L), { ok: true, format: "PNG" });
  assert.match(checkFile({ type: "image/gif", name: "a.gif", size: 5000 }, L).error, /tylko pliki PNG i JPEG/);
  assert.match(checkFile({ type: "image/png", name: "a.png", size: 0 }, L).error, /pusty/);
  assert.match(checkFile({ type: "image/png", name: "a.png", size: HARD_MAX_BYTES + 1 }, L).error, /za duży/);
  // większy niż maxBytes, ale rozsądny → przyjęty (pomniejszenie w przeglądarce)
  assert.equal(checkFile({ type: "image/jpeg", name: "a.jpg", size: 2_000_000 }, L).ok, true);
  assert.match(checkFile(null, L).error, /Wybierz/);
  assert.match(checkFile({ type: "image/jpeg", name: "a.jpg", size: 10 }, { formats: ["PNG"] }).error, /tylko pliki PNG\./);
});

test("skalowanie z zachowaniem proporcji, bez powiększania", () => {
  assert.deepEqual(fitWithin(1280, 800, 256, 160), { width: 256, height: 160, scaled: true });
  assert.deepEqual(fitWithin(1000, 1000, 256, 160), { width: 160, height: 160, scaled: true });
  assert.deepEqual(fitWithin(100, 50, 256, 160), { width: 100, height: 50, scaled: false });
  assert.deepEqual(fitWithin(4000, 100, 256, 160), { width: 256, height: 6, scaled: true });
});

test("przycięcie środka do proporcji 64:40", () => {
  assert.deepEqual(coverCrop(64, 40, 64, 40), { sx: 0, sy: 0, sw: 64, sh: 40 });
  assert.deepEqual(coverCrop(200, 40, 64, 40), { sx: 68, sy: 0, sw: 64, sh: 40 });
  const r = coverCrop(100, 100, 64, 40);
  assert.equal(r.sw, 100);
  assert.equal(r.sh, 62.5);
  assert.equal(r.sy, 18.75);
});

test("plan wysyłki: oryginał, pomniejszenie, za mały, zbyt wydłużony", () => {
  assert.deepEqual(uploadPlan({ format: "PNG", bytes: 4000, width: 128, height: 80 }, L), { ok: true, resize: false, width: 128, height: 80, notes: [] });
  const big = uploadPlan({ format: "JPEG", bytes: 900_000, width: 1920, height: 1200 }, L);
  assert.equal(big.ok, true);
  assert.equal(big.resize, true);
  assert.deepEqual([big.width, big.height], [256, 160]);
  assert.match(big.notes[0], /pomniejszę go do 256×160/);
  const pixels = uploadPlan({ format: "PNG", bytes: 50_000, width: 2000, height: 1000 }, L);
  assert.equal(pixels.resize, true);
  assert.match(pixels.notes[0], /bardzo dużo pikseli/);
  // limit długości data: (base64) — nawet gdy serwer pozwoliłby na większy plik
  assert.equal(uploadPlan({ format: "PNG", bytes: 200_000, width: 300, height: 200 }, { ...L, maxBytes: 500_000 }).resize, true);
  assert.ok(dataUrlLength(179_982) <= MAX_DATA_CHARS);
  assert.ok(dataUrlLength(180_000) > MAX_DATA_CHARS);
  assert.match(uploadPlan({ format: "PNG", bytes: 100, width: 16, height: 10 }, L).error, /za mały: 16×10 px/);
  assert.match(uploadPlan({ format: "PNG", bytes: 900_000, width: 4000, height: 100 }, L).error, /wydłużone/);
  assert.match(uploadPlan({ format: "PNG", bytes: 1, width: 0, height: 0 }, L).error, /odczytać/);
});

test("obrazki z serwera: tylko data:image/png|jpeg;base64", () => {
  const png = pngDataUrl(4, 4, () => [1, 2, 3]);
  assert.equal(safeImage(png), png);
  assert.equal(safeImage("data:image/jpeg;base64,/9j/4AAQ"), "data:image/jpeg;base64,/9j/4AAQ");
  assert.equal(safeImage("data:image/svg+xml;base64,PHN2Zz4="), null);
  assert.equal(safeImage("https://evil.example/x.png"), null);
  assert.equal(safeImage('data:image/png;base64,AAA" onerror="x'), null);
  assert.equal(safeImage(null), null);
});

test("etykiety statusów obecnej chorągwi i historii", () => {
  assert.equal(currentStatus({ source: "DEFAULT", status: "LIVE" }).key, "DEFAULT");
  assert.equal(currentStatus(null).label, "Domyślna (z koloru kraju)");
  assert.deepEqual(currentStatus({ source: "UPLOAD", status: "LIVE" }), { key: "LIVE", label: "W grze", cls: "ok" });
  assert.equal(currentStatus({ source: "UPLOAD", status: "WAITING_PACK" }).label, "Zatwierdzona — czeka na przebudowę paczki");
  assert.deepEqual(historyStatus("REJECTED"), { label: "Odrzucona", cls: "err" });
  assert.equal(historyStatus("RESET").label, "Przywrócono domyślną");
  assert.equal(historyStatus("RESTORED").label, "Przywrócona z historii");
  assert.equal(historyStatus("NOWY").label, "NOWY");
});

test("czas do przebudowy paczki", () => {
  const now = 1_800_000_000_000;
  assert.equal(etaText(null, now), null);
  assert.equal(etaText(now - 5000, now), "lada chwila");
  assert.equal(etaText(now + 45_000, now), "za mniej niż minutę");
  assert.equal(etaText(now + 12 * 60000, now), "za ok. 12 min");
  assert.equal(etaText(now + 60 * 60000, now), "za ok. 1 godz.");
  assert.equal(etaText(now + 135 * 60000, now), "za ok. 2 godz. 15 min");
  assert.equal(packSentence(null, now), "Pojawi się w grze przy najbliższej przebudowie paczki zasobów.");
  assert.equal(packSentence(now + 20 * 60000, now, () => "14:05"), "Pojawi się w grze po przebudowie paczki zasobów ok. 14:05 (za ok. 20 min).");
});

test("powód odrzucenia 3–200 znaków", () => {
  assert.equal(checkReason("  ").ok, false);
  assert.match(checkReason("ab").error, /co najmniej 3/);
  assert.deepEqual(checkReason("  Cudzy   herb "), { ok: true, value: "Cudzy herb" });
  assert.equal(checkReason("x".repeat(200)).ok, true);
  assert.match(checkReason("x".repeat(201)).error, /najwyżej 200 znaków \(teraz 201\)/);
});

test("drobne formatowanie i role", () => {
  assert.equal(formatBytes(512), "512 B");
  assert.equal(originalText({ format: "PNG", width: 640, height: 400, bytes: 2048 }), "PNG · 640×400 px · 2 KB");
  assert.equal(originalText(null), "—");
  assert.equal(isDenied({ app: true, message: "Tylko moderator chorągwi może to zrobić." }), true);
  assert.equal(isDenied({ status: 503, message: "Serwer offline" }), false);
  assert.deepEqual(flagNav({ country: { id: "c 1", rank: "ADVISOR" }, roles: { flagModerator: true } }), { country: "#/kraj/c%201/flaga", moderation: true });
  assert.deepEqual(flagNav({ country: { id: "c", rank: "CITIZEN" }, roles: {} }), { country: null, moderation: false });
  assert.deepEqual(flagNav(null), { country: null, moderation: false });
});

// ---------------------------------------------------------------- atrapa Kraje przez fałszywy plugin

function setup() {
  const W = createWorld();
  const H = makeHandlers(W);
  const call = async (user, method, params = {}) => H.rpc({ t: "rpc", id: "1", user: { uuid: user.uuid, name: user.name }, method, params });
  return { W, call };
}
const IMG = pngDataUrl(128, 80, (x, y) => [x * 2, y * 3, 90]);

test("atrapa: moderator tylko dla admina panelu i listy moderatorów", async () => {
  const { W, call } = setup();
  const me = async (u) => (await call(u, "me")).data.roles.flagModerator;
  assert.equal(await me(W.U.maciej), true);
  assert.equal(await me(W.U.dobrawa), false);
  assert.equal(await me(W.U.bolko), false);
  W.flagModerators.add(W.U.ziemowit.uuid);
  assert.equal(await me(W.U.ziemowit), true);
  const r = await call(W.U.bolko, "admin.flag.queue");
  assert.equal(r.ok, false);
  assert.match(r.error, /moderator/);
});

test("atrapa: wgranie tylko Doradca/Władca własnego kraju", async () => {
  const { W, call } = setup();
  let r = await call(W.U.siemowit, "country.flag.upload", { id: "c-polanie", data: IMG });
  assert.equal(r.ok, false, "obywatel bez rangi");
  assert.match(r.error, /Władca i Doradcy/);
  r = await call(W.U.maciej, "country.flag.upload", { id: "c-polanie", data: IMG });
  assert.equal(r.ok, false, "obcy kraj (nawet admin)");
  r = await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: "data:image/gif;base64,R0lGODlh" });
  assert.equal(r.ok, false);
  r = await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: pngDataUrl(16, 10, () => [0, 0, 0]) });
  assert.match(r.error, /za mały/);
  r = await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: IMG, filename: "herb.png" });
  assert.equal(r.ok, true, r.error);
  assert.deepEqual(r.data.pending.original, { width: 128, height: 80, format: "PNG", bytes: Buffer.from(IMG.split(",")[1], "base64").length });
});

test("atrapa: get pokazuje kolejkę i historię tylko uprawnionym", async () => {
  const { W, call } = setup();
  await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: IMG });
  const own = (await call(W.U.dobrawa, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(own.access.upload, true);
  assert.ok(own.pending && own.pending.png.startsWith("data:image/png;base64,"));
  assert.equal(own.current.source, "DEFAULT");
  assert.ok(safeImage(own.current.png));
  const foreign = (await call(W.U.wedrowiec, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(foreign.access.upload, false);
  for (const k of ["pending", "history", "lastRejection"]) assert.equal(k in foreign, false, k);
  const citizen = (await call(W.U.siemowit, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal("pending" in citizen, false);
  const mod = (await call(W.U.maciej, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(mod.access.moderate, true);
  assert.ok(mod.pending);
});

test("atrapa: zatwierdzenie → WAITING_PACK, przebudowa → LIVE, przywracanie z historii", async () => {
  const { W, call } = setup();
  const up = (await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: IMG })).data.pending;
  const q = (await call(W.U.maciej, "admin.flag.queue")).data;
  assert.equal(q.items.length, 1);
  assert.equal(q.items[0].id, "c-polanie");
  assert.ok(q.items[0].currentPng);
  assert.equal((await call(W.U.maciej, "admin.flag.approve", { id: "c-polanie", imageId: "zly" })).ok, false);
  const ok = await call(W.U.maciej, "admin.flag.approve", { id: "c-polanie", imageId: up.imageId });
  assert.equal(ok.ok, true);
  assert.ok(ok.data.nextRebuildAt > Date.now());
  let d = (await call(W.U.dobrawa, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(d.current.source, "UPLOAD");
  assert.equal(d.current.status, "WAITING_PACK");
  assert.equal(d.current.imageId, up.imageId);
  assert.equal(d.pending, null);
  assert.equal(d.history[0].status, "APPROVED");
  assert.equal(d.history[0].restorable, false, "obecna nie jest do przywrócenia");
  assert.equal((await call(W.U.maciej, "admin.flag.queue")).data.recent[0].status, "APPROVED");
  await call(W.U.maciej, "admin.flag.rebuild");
  d = (await call(W.U.dobrawa, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(d.current.status, "LIVE");
  // reset → historia pozwala przywrócić zatwierdzony obrazek
  assert.equal((await call(W.U.dobrawa, "country.flag.reset", { id: "c-polanie" })).ok, true);
  d = (await call(W.U.dobrawa, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(d.current.source, "DEFAULT");
  const entry = d.history.find((e) => e.imageId === up.imageId && e.restorable);
  assert.ok(entry);
  const res = await call(W.U.bolko, "country.flag.restore", { id: "c-polanie", imageId: up.imageId });
  assert.equal(res.ok, true);
  d = (await call(W.U.bolko, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(d.current.status, "WAITING_PACK");
  assert.equal(d.history[0].status, "RESTORED");
});

test("atrapa: odrzucenie zapisuje powód, wycofanie czyści kolejkę", async () => {
  const { W, call } = setup();
  const up = (await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: IMG })).data.pending;
  assert.equal((await call(W.U.maciej, "admin.flag.reject", { id: "c-polanie", imageId: up.imageId, reason: "x" })).ok, false);
  assert.equal((await call(W.U.dobrawa, "admin.flag.reject", { id: "c-polanie", imageId: up.imageId, reason: "Cudzy herb" })).ok, false);
  assert.equal((await call(W.U.maciej, "admin.flag.reject", { id: "c-polanie", imageId: up.imageId, reason: "Cudzy herb" })).ok, true);
  const d = (await call(W.U.dobrawa, "country.flag.get", { id: "c-polanie" })).data;
  assert.equal(d.pending, null);
  assert.deepEqual({ reason: d.lastRejection.reason, by: d.lastRejection.by, imageId: d.lastRejection.imageId }, { reason: "Cudzy herb", by: "Maciej", imageId: up.imageId });
  assert.equal(d.current.source, "DEFAULT");
  await call(W.U.dobrawa, "country.flag.upload", { id: "c-polanie", data: IMG });
  assert.equal((await call(W.U.wedrowiec, "country.flag.withdraw", { id: "c-polanie" })).ok, false);
  assert.equal((await call(W.U.dobrawa, "country.flag.withdraw", { id: "c-polanie" })).ok, true);
  assert.equal((await call(W.U.maciej, "admin.flag.queue")).data.items.length, 0);
});

// ------------------------------------------------------------------ wzór A sztandaru (Kraje 463b65a5+) i Kraje 9240f7b2

test("podgląd sztandaru: stary Kraje (brak pól), liczony (null), gotowy, śmieci", () => {
  const png = pngDataUrl(80, 160, () => [176, 46, 38]);
  assert.deepEqual(bannerPreviewState({ source: "UPLOAD", png }), { key: "UNSUPPORTED" });
  assert.deepEqual(bannerPreviewState(undefined), { key: "UNSUPPORTED" });
  assert.deepEqual(bannerPreviewState(null), { key: "UNSUPPORTED" });
  assert.deepEqual(bannerPreviewState({ banner: null, bannerText: null, bannerDeltaE: null }), { key: "PENDING" });
  assert.deepEqual(bannerPreviewState({ banner: "javascript:alert(1)" }), { key: "PENDING" });
  assert.deepEqual(bannerPreviewState({ banner: "data:image/jpeg;base64,AAAA" }), { key: "PENDING" }, "tylko PNG");
  assert.deepEqual(bannerPreviewState({ banner: "data:image/png;base64,<script>" }), { key: "PENDING" });
  assert.deepEqual(bannerPreviewState({ banner: png, bannerText: "  tło czerwone ", bannerDeltaE: 15.5 }), { key: "READY", src: png, text: "tło czerwone", deltaE: 15.5 });
  assert.deepEqual(bannerPreviewState({ banner: png }), { key: "READY", src: png, text: null, deltaE: null });
  assert.equal(bannerPreviewState({ banner: png, bannerDeltaE: "x" }).deltaE, null);
  assert.equal(bannerPreviewState({ banner: png, bannerDeltaE: -1 }).deltaE, null);
  assert.equal(bannerPreviewState({ banner: png, bannerText: "a".repeat(500) }).text.length, 300);
});

test("podgląd sztandaru: opis zgodności ΔE", () => {
  assert.equal(deltaEText(null), null);
  assert.equal(deltaEText(undefined), null);
  assert.equal(deltaEText("abc"), null);
  assert.equal(deltaEText(9.2), "ΔE 9,2 — bardzo dobra zgodność barw");
  assert.equal(deltaEText(15.5), "ΔE 15,5 — dobra zgodność barw");
  assert.equal(deltaEText(25), "ΔE 25,0 — przybliżona zgodność barw");
  assert.equal(deltaEText(40), "ΔE 40,0 — luźna zgodność barw");
});

test("atrapa: country.flag.get bez pól sztandaru (Kraje 9240f7b2) i z nimi (Kraje 463b65a5)", async () => {
  const { W, call } = setup();
  const get = async () => (await call(W.U.wedrowiec, "country.flag.get", { id: "c-polanie" })).data.current;
  let cur = await get();
  for (const k of ["banner", "bannerText", "bannerDeltaE"]) assert.equal(k in cur, false, k);
  assert.equal(bannerPreviewState(cur).key, "UNSUPPORTED");
  W.flagBanners = "pending";
  cur = await get();
  assert.equal(cur.banner, null);
  assert.equal(bannerPreviewState(cur).key, "PENDING");
  W.flagBanners = "ready";
  cur = await get();
  const st = bannerPreviewState(cur);
  assert.equal(st.key, "READY");
  assert.ok(Buffer.from(st.src.split(",")[1], "base64").readUInt32BE(16) === 80, "PNG 80×160");
  assert.ok(st.text && st.deltaE > 0);
});
