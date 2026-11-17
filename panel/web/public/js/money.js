// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — kwoty w formacie Skarbca (1 zł = 100 md, 1 sr = 10 md). Zgodne z pl.medivalrpg.skarbiec.money.Money:
// format: "3 zł 4 sr 5 md", zera pomijane, "0 md" dla zera, "-" na początku dla ujemnych;
// parse: dowolne części <liczba><jednostka> rozdzielone spacją, przecinkiem, "+" albo "i", każda jednostka
// najwyżej raz, plus jedna goła liczba = miedziaki ("150"). Wielkość liter i polskie znaki bez znaczenia.
// Jedyna różnica: odrzucamy zapis z przecinkiem/kropką między cyframi ("1,5 zł"), bo to wygląda na ułamek.

export const MD_PER_SR = 10;
export const MD_PER_ZL = 100;

const UNITS = new Map();
for (const u of ["md", "m", "mdz", "miedziak", "miedziaki", "miedziakow", "miedz"]) UNITS.set(u, 1);
for (const u of ["sr", "s", "srebrnik", "srebrniki", "srebrnikow", "srebro"]) UNITS.set(u, MD_PER_SR);
for (const u of ["zl", "z", "zk", "zlote", "zlotych", "zloty", "korona", "korony", "koron", "zlota", "zloto"]) UNITS.set(u, MD_PER_ZL);

/** Formatuje kwotę w miedziakach. Nie-liczby → "—". */
export function formatMoney(amount) {
  if (typeof amount === "string" && /^-?\d+$/.test(amount.trim())) amount = Number(amount);
  if (typeof amount !== "number" || !Number.isFinite(amount)) return "—";
  amount = Math.trunc(amount);
  if (amount === 0) return "0 md";
  const neg = amount < 0;
  const abs = Math.abs(amount);
  const zl = Math.floor(abs / MD_PER_ZL);
  const rest = abs % MD_PER_ZL;
  const sr = Math.floor(rest / MD_PER_SR);
  const md = rest % MD_PER_SR;
  const parts = [];
  if (zl) parts.push(zl + " zł");
  if (sr) parts.push(sr + " sr");
  if (md) parts.push(md + " md");
  return (neg ? "-" : "") + parts.join(" ");
}

function normalize(s) {
  return s.toLowerCase().replace(/ł/g, "l").normalize("NFD").replace(/\p{M}/gu, "");
}

/**
 * Parsuje kwotę nieujemną. Zwraca liczbę miedziaków albo null (puste, ujemne, ułamki, nieznane jednostki,
 * powtórzona jednostka, więcej niż jedna goła liczba, przekroczenie Number.MAX_SAFE_INTEGER).
 */
export function parseMoney(input) {
  if (typeof input !== "string") return null;
  const text = normalize(input).trim();
  if (!text) return null;
  // "1,5 zł" / "1.5" to ułamek, a nie dwie części (Skarbiec przeczytałby to jako 1 md + 5 zł) — odrzucamy.
  if (/\d\s*[,.]\s*\d/.test(text)) return null;
  const re = /\s*(?:,|\+|\bi\b)?\s*(\d{1,19})\s*(?:([a-z]+)\.?)?/y;
  const used = new Set();
  let bare = false;
  let total = 0;
  let end = 0;
  for (;;) {
    re.lastIndex = end;
    const m = re.exec(text);
    if (!m || m[0].length === 0) break;
    end = re.lastIndex;
    const n = Number(m[1]);
    let mult;
    if (m[2] === undefined) {
      if (bare) return null;
      bare = true;
      mult = 1;
    } else {
      mult = UNITS.get(m[2]);
      if (!mult || used.has(mult)) return null;
      used.add(mult);
    }
    total += n * mult;
    if (!Number.isSafeInteger(n) || !Number.isSafeInteger(total)) return null;
  }
  if (end !== text.length || (used.size === 0 && !bare)) return null;
  return total;
}

/** Jak parseMoney, ale dopuszcza znak "-" albo "+" na początku (korekty admina). */
export function parseSignedMoney(input) {
  if (typeof input !== "string") return null;
  const m = /^\s*([+\-−])\s*(.*)$/s.exec(input);
  if (!m) return parseMoney(input);
  const v = parseMoney(m[2]);
  if (v === null) return null;
  return m[1] === "+" ? v : -v;
}
