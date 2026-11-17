// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — wspólne elementy zakładek „Państwo NPC” (v2): słowniki kodów, przełącznik segmentowy,
// korekta ± z szybkimi przyciskami, kolor opinii, komunikat o braku danych z pluginu.
import { h, icon, add } from "../dom.js";
import { card, field, act, btn } from "../components.js";
import { fmtInt } from "../util.js";

export const NPC_PARAMS = [
  { key: "agresja", label: "Agresja", alt: ["aggression", "aggr"], range: true, help: "Skłonność do wypowiadania wojen i oblężeń" },
  { key: "ekspansja", label: "Ekspansja", alt: ["expansion"], range: true, help: "Tempo zajmowania nowych ziem" },
  { key: "dyplomacja", label: "Dyplomacja", alt: ["diplomacy"], range: true, help: "Chęć zawierania sojuszy i pokoju" },
  { key: "bogactwo", label: "Bogactwo", alt: ["wealth", "economy"], range: true, help: "Nacisk na gospodarkę i oszczędzanie (= mnożnik dochodu)" },
  { key: "max-chunki", label: "Maks. chunków", alt: ["maxChunks", "max_chunki", "maxChunki"], range: false, help: "Górny limit terytorium" },
  { key: "max-zolnierze", label: "Maks. żołnierzy", alt: ["maxSoldiers", "max_zolnierze", "maxZolnierze"], range: false, help: "Górny limit armii" },
];

export function settingValue(settings, p) {
  if (!settings) return undefined;
  for (const k of [p.key, ...p.alt]) if (settings[k] !== undefined) return Number(settings[k]);
  return undefined;
}
export const paramByKey = (k) => NPC_PARAMS.find((p) => p.key === k);

export function isPaused(npc) {
  if (!npc) return null;
  if (typeof npc.paused === "boolean") return npc.paused;
  const s = npc.settings || {};
  if (typeof s.aktywna === "boolean") return !s.aktywna;
  if (typeof s.active === "boolean") return !s.active;
  return null;
}

export const REL2 = { ALLIANCE: "Sojusz", NEUTRAL: "Pokój", WAR: "Wojna", VASSAL: "Wasal", SUZERAIN: "Suweren" };
export const REL2_ORDER = { WAR: 0, SUZERAIN: 1, VASSAL: 2, ALLIANCE: 3, NEUTRAL: 4 };
export const STANCES = [
  { id: "AUTO", label: "Auto (decyduje AI)" },
  { id: "FRIENDLY", label: "Przyjazne" },
  { id: "HOSTILE", label: "Wrogie" },
  { id: "NEVER_WAR", label: "Nigdy wojny" },
  { id: "PREFERRED_TARGET", label: "Preferowany cel ataku" },
];
export const POSTURES = [
  { id: "DEFENSIVE", label: "Obronna", help: "Wojsko trzyma się granic i posterunków, AI unika nowych wojen." },
  { id: "BALANCED", label: "Zrównoważona", help: "AI broni ziem i atakuje, gdy ma wyraźną przewagę." },
  { id: "OFFENSIVE", label: "Ofensywna", help: "AI chętnie oblega wrogów i zajmuje ziemie, kosztem obrony." },
];
export const LOG_CATS = [
  { id: "ALL", label: "Wszystko" },
  { id: "WAR", label: "Wojna" },
  { id: "DIPLOMACY", label: "Dyplomacja" },
  { id: "BUILD", label: "Budowy" },
  { id: "ARMY", label: "Armia" },
  { id: "ECONOMY", label: "Gospodarka" },
  { id: "OTHER", label: "Inne" },
];
export const GOAL_ICONS = { SAVING: "coins", WAR: "swords", EXPANSION: "map", BUILDING: "house", RECRUITING: "users", DIPLOMACY: "scroll", IDLE: "pause" };

/** Kolor opinii -100…100: czerwony → bursztyn → zielony (tokeny motywu, więc czytelny w obu motywach). */
export function opinionColor(v) {
  const n = Math.max(-100, Math.min(100, Number(v) || 0));
  return n < 0 ? `color-mix(in srgb, var(--danger) ${Math.round(-n)}%, var(--warn))` : `color-mix(in srgb, var(--ok) ${Math.round(n)}%, var(--warn))`;
}
export function opinionText(v) {
  const n = Math.round(Number(v) || 0);
  return (n > 0 ? "+" : "") + n;
}

/** Odpowiedź mutacji: komunikat z pluginu (PANEL.md v2: `message`), inaczej domyślny tekst. */
export const msg = (fallback) => (r) => (r && typeof r === "object" && typeof r.message === "string" && r.message ? r.message : typeof fallback === "function" ? fallback(r) : fallback);

/** Karta „brak danych” dla odczytu v2, który się nie udał (np. starszy plugin bez metody). */
export function v2Error(title, e) {
  const m = (e && e.message) || String(e || "");
  const old = /nieznana metoda|unknown method/i.test(m);
  return card(title, h("div.empty", icon("warn"), h("p", old ? "Ta funkcja wymaga nowszej wersji pluginu Panel (zarządzanie NPC v2)." : "Nie udało się wczytać danych: " + m)));
}

/** Przełącznik segmentowy (przyciski z aria-pressed). onPick(id, button). */
export function segmented(options, value, onPick, label) {
  return h(
    "div.seg",
    { role: "group", "aria-label": label },
    options.map((o) => {
      const b = h("button.seg-btn" + (o.id === value ? ".on" : ""), { type: "button", "aria-pressed": String(o.id === value), "data-mut": "1", dataset: { id: o.id } }, o.icon ? icon(o.icon) : null, h("span", o.label));
      b.addEventListener("click", () => {
        if (o.id !== value) onPick(o.id, b);
      });
      return b;
    }),
  );
}

/**
 * Korekta ± liczby (jedzenie, osadnicy): pole ze znakiem, „Zastosuj” i szybkie przyciski.
 * apply(delta, button) → Promise (zwykle act(...)).
 */
export function adjuster({ label, unit, quick, max, apply }) {
  const input = h("input.input", { type: "number", step: "1", min: String(-max), max: String(max), placeholder: "np. 50 albo -20", "aria-label": label + " — zmiana" });
  const submit = btn("Zastosuj", { kind: "primary", type: "submit" });
  const form = h(
    "form.inline-form",
    {
      on: {
        submit: (e) => {
          e.preventDefault();
          const v = Math.trunc(Number(input.value));
          if (!input.value.trim() || !Number.isFinite(v) || v === 0 || Math.abs(v) > max) {
            input.classList.add("invalid");
            input.focus();
            return;
          }
          input.classList.remove("invalid");
          apply(v, submit).then((r) => {
            if (r !== undefined) input.value = "";
          });
        },
      },
    },
    field(label + " (±)", input),
    submit,
  );
  const quickRow = h(
    "div.quick",
    h("span.quick-label", "Szybko:"),
    quick.map((q) => btn((q > 0 ? "+" : "−") + fmtInt(Math.abs(q)) + (unit ? " " + unit : ""), { small: true, kind: q < 0 ? "danger" : "" }, (_e, b) => apply(q, b))),
  );
  return add(h("div.adjuster"), form, quickRow);
}

export { act };
