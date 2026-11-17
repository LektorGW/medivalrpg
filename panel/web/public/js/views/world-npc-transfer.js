// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Skarb Świata → Zarząd: przelew między rezerwą Skarbu Świata a skarbcem wybranego państwa NPC
// (world.npcTreasuries / world.npcTransfer). Kwota jest potwierdzana w modalu i wysyłana dwa razy: jako tekst
// (`amount`) i jako liczba miedziaków (`confirm`) — serwer porównuje obie. Powód (3–120 znaków) trafia do księgi.
// Przelew do WSZYSTKICH państw NPC: ta sama kwota dla każdego, jedna operacja z kluczem `key` (idempotentna —
// ponowne wysłanie tego samego klucza niczego nie przelewa drugi raz), `confirm` = suma w md, `count` = liczba państw
// z podglądu; kierunek `toAllNpc` istniejącego RPC world.npcTransfer. Historia: `bulk` z world.npcTreasuries.
import { h, add, clear, icon, modal, toast } from "../dom.js";
import { card, moneyInput, field, btn, shield, empty } from "../components.js";
import { formatMoney } from "../money.js";
import { fmtInt } from "../util.js";
import * as api from "../api.js";
import { fold } from "../world-stats-model.js";

const DIRS = [
  { id: "toCountry", label: "Do kraju", help: "Z rezerwy Skarbu Świata do skarbca państwa." },
  { id: "toReserve", label: "Zwrot do rezerwy", help: "Ze skarbca państwa z powrotem do rezerwy." },
];
export const displayName = (n) => String(n ?? "").replace(/_/g, " ");

/** Karta przelewu. data: wynik world.npcTreasuries albo {error}. */
export function npcTransferCard(data, ctx) {
  if (!data || data.error)
    return card("Przelew do skarbca państwa NPC", h("div.alert.alert-warn", icon("warn"), h("span", "Nie udało się wczytać państw NPC: " + ((data && data.error) || "brak danych") + ". Ta funkcja wymaga aktualnego pluginu Skarbca.")));
  const countries = Array.isArray(data.countries) ? data.countries : [];
  const st = { id: null, dir: "toCountry", q: "" };
  const list = h("div.ws-npc-list", { role: "radiogroup", "aria-label": "Państwo NPC" });
  const search = h("input.input.input-sm", { type: "search", placeholder: "Szukaj państwa…", maxlength: "32", "aria-label": "Szukaj państwa NPC", autocomplete: "off" });
  const amount = moneyInput({ name: "npc-amount", placeholder: "np. 100 zł" });
  amount.input.setAttribute("aria-label", "Kwota przelewu");
  const reason = h("input.input", { type: "text", maxlength: "120", placeholder: "np. Wsparcie odbudowy po wojnie", "aria-label": "Powód przelewu", autocomplete: "off" });
  const counter = h("span.field-help", "0 / 120");
  const errBox = h("div.ws-npc-errors", { role: "alert" });
  const dirSeg = h("div.seg.ws-npc-dir", { role: "group", "aria-label": "Kierunek przelewu" });
  const dirHelp = h("p.muted.small");

  const selected = () => countries.find((c) => c.id === st.id) || null;
  const drawList = () => {
    clear(list);
    const q = fold(st.q.trim());
    const rows = countries.filter((c) => !q || fold(displayName(c.name)).includes(q) || fold(c.name).includes(q));
    if (!rows.length) return add(list, empty(countries.length ? "Żadne państwo NPC nie pasuje." : "Brak państw NPC."));
    for (const c of rows) {
      const on = c.id === st.id;
      const b = h(
        "button.ws-npc-item" + (on ? ".on" : ""),
        { type: "button", role: "radio", "aria-checked": String(on), dataset: { id: c.id } },
        shield(c.color),
        h("span.ws-npc-name", displayName(c.name)),
        c.npc !== false ? h("span.tag.tag-npc", "NPC") : null,
        h("span.money.ws-npc-bal", { title: fmtInt(c.balance) + " md" }, formatMoney(c.balance)),
      );
      b.addEventListener("click", () => {
        st.id = c.id;
        drawList();
      });
      add(list, b);
    }
  };
  const drawDir = () => {
    clear(dirSeg);
    for (const d of DIRS) {
      const b = h("button.seg-btn" + (st.dir === d.id ? ".on" : ""), { type: "button", "aria-pressed": String(st.dir === d.id), dataset: { dir: d.id } }, d.label);
      b.addEventListener("click", () => {
        st.dir = d.id;
        drawDir();
      });
      add(dirSeg, b);
    }
    dirHelp.textContent = DIRS.find((d) => d.id === st.dir).help;
  };
  search.addEventListener("input", () => {
    st.q = search.value;
    drawList();
  });
  reason.addEventListener("input", () => (counter.textContent = `${reason.value.trim().length} / 120`));

  const submit = async (_, button) => {
    const c = selected();
    const md = amount.value();
    const why = reason.value.trim();
    const problems = [];
    if (!c) problems.push("Wybierz państwo NPC.");
    if (md === null || !(md > 0)) problems.push("Podaj poprawną kwotę większą od zera.");
    if (why.length < 3 || why.length > 120) problems.push("Podaj powód przelewu (3–120 znaków).");
    add(clear(errBox), problems.length ? h("div.alert.alert-err", icon("warn"), h("ul.ws-npc-problems", problems.map((p) => h("li", p)))) : null);
    if (problems.length) return;
    const toCountry = st.dir === "toCountry";
    const reserveAfter = data.reserve + (toCountry ? -md : md);
    const balanceAfter = c.balance + (toCountry ? md : -md);
    const short = toCountry ? reserveAfter < 0 : balanceAfter < 0;
    const ok = await modal({
      title: "Potwierdź przelew",
      body: h(
        "div.ws-npc-confirm",
        h("p.ws-npc-dirline", toCountry ? "Z rezerwy Skarbu Świata → do skarbca: " : "Ze skarbca państwa → do rezerwy Skarbu Świata: ", h("strong", displayName(c.name))),
        h("div.ws-npc-amount", formatMoney(md)),
        h("p.muted.small", `= ${fmtInt(md)} md`),
        h(
          "dl.ws-facts",
          h("dt", "Rezerwa"),
          h("dd", formatMoney(data.reserve), " → ", h("strong" + (reserveAfter < 0 ? ".neg" : ""), formatMoney(reserveAfter))),
          h("dt", "Skarbiec: " + displayName(c.name)),
          h("dd", formatMoney(c.balance), " → ", h("strong" + (balanceAfter < 0 ? ".neg" : ""), formatMoney(balanceAfter))),
          h("dt", "Powód w księdze"),
          h("dd", "WorldSteward: " + why),
        ),
        short ? h("div.alert.alert-err", icon("warn"), h("span", toCountry ? "Za mało środków w rezerwie Skarbu Świata." : "Za mało środków w skarbcu kraju.")) : h("p.muted.small", "Przelew przenosi istniejące pieniądze — łączna pula serwera się nie zmienia."),
      ),
      actions: short ? [{ label: "Anuluj", value: null }] : [{ label: "Anuluj", value: null }, { label: "Przelej " + formatMoney(md), kind: "primary", value: true }],
    });
    if (!ok) return;
    button.disabled = true;
    button.classList.add("busy");
    try {
      const r = await api.mutate("world.npcTransfer", { country: c.id, amount: amount.input.value.trim(), confirm: md, reason: why, direction: st.dir });
      toast((r && r.message) || "Przelew zapisany w księdze", "ok");
      amount.reset();
      reason.value = "";
      const main = document.getElementById("main");
      if (main) delete main.dataset.dirty;
      ctx.reload();
    } catch (e) {
      toast(e.message || String(e), "err", 7000);
    } finally {
      button.disabled = false;
      button.classList.remove("busy");
    }
  };

  drawList();
  drawDir();
  const bulk = bulkSection(data, countries, ctx);
  return card(
    "Przelew do skarbca państwa NPC",
    h("p.muted", "Rezerwa Skarbu Świata: ", h("strong.money", { title: fmtInt(data.reserve) + " md" }, formatMoney(data.reserve)), ". Każdy przelew trafia do księgi jako „Skarb Świata — przydziały” z Twoim powodem."),
    h("div.search-row.ws-npc-search", icon("search"), search),
    list,
    h("div.ws-npc-form", h("div.field", h("span.field-label", "Kierunek"), dirSeg, dirHelp), field("Kwota", amount.el), field("Powód", reason, counter)),
    errBox,
    h("div.form-actions", btn("Przelej…", { kind: "primary", icon: "coins" }, submit)),
    bulk,
  );
}

/** Nowy klucz operacji zbiorczej (raz na podgląd; po błędzie sieci ten sam klucz chroni przed podwójnym przelewem). */
export function newBulkKey() {
  const raw = globalThis.crypto && typeof globalThis.crypto.randomUUID === "function" ? globalThis.crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2);
  return "npcbulk-" + raw.replace(/[^a-f0-9]/gi, "").toLowerCase().slice(0, 16).padEnd(16, "0");
}

/** Podgląd przelewu do wszystkich państw NPC (czysta funkcja — testowana). */
export function bulkPreview(reserve, countries, perCountry) {
  const count = countries.length;
  const total = perCountry > 0 ? perCountry * count : 0;
  return { count, total, reserveAfter: reserve - total, short: reserve - total < 0, empty: count === 0 };
}

function bulkSection(data, countries, ctx) {
  const st = { key: null, sig: "" };
  const amount = moneyInput({ name: "npc-bulk-amount", placeholder: "np. 50 zł na państwo" });
  amount.input.setAttribute("aria-label", "Kwota dla każdego państwa NPC");
  const reason = h("input.input", { type: "text", maxlength: "120", placeholder: "np. Wsparcie zimowe dla wszystkich państw", "aria-label": "Powód przelewu zbiorczego", autocomplete: "off" });
  const errBox = h("div.ws-npc-errors", { role: "alert" });
  const submit = async (_, button) => {
    const md = amount.value();
    const why = reason.value.trim();
    const problems = [];
    if (!countries.length) problems.push("Brak państw NPC — nie ma komu przelać.");
    if (md === null || !(md > 0)) problems.push("Podaj poprawną kwotę na jedno państwo (większą od zera).");
    if (why.length < 3 || why.length > 120) problems.push("Podaj powód przelewu (3–120 znaków).");
    add(clear(errBox), problems.length ? h("div.alert.alert-err", icon("warn"), h("ul.ws-npc-problems", problems.map((p) => h("li", p)))) : null);
    if (problems.length) return;
    const pv = bulkPreview(data.reserve, countries, md);
    const sig = md + "|" + why + "|" + countries.map((c) => c.id).join(",");
    if (st.sig !== sig || !st.key) {
      st.key = newBulkKey();
      st.sig = sig;
    }
    const ok = await modal({
      title: "Przelew do wszystkich państw NPC",
      wide: true,
      body: h(
        "div.ws-npc-confirm",
        h("p.ws-npc-dirline", "Z rezerwy Skarbu Świata → do skarbca ", h("strong", "każdego z " + pv.count + " państw NPC"), " po:"),
        h("div.ws-npc-amount", formatMoney(md)),
        h(
          "dl.ws-facts",
          h("dt", "Liczba państw"),
          h("dd", fmtInt(pv.count)),
          h("dt", "Suma"),
          h("dd", h("strong", formatMoney(pv.total)), ` (${fmtInt(pv.total)} md)`),
          h("dt", "Rezerwa"),
          h("dd", formatMoney(data.reserve), " → ", h("strong" + (pv.short ? ".neg" : ""), formatMoney(pv.reserveAfter))),
          h("dt", "Powód w księdze"),
          h("dd", "WorldSteward: " + why),
        ),
        h("ul.ws-npc-bulk-list", countries.map((c) => h("li", shield(c.color), " ", displayName(c.name), " — ", formatMoney(c.balance), " → ", h("strong", formatMoney(c.balance + md))))),
        pv.short
          ? h("div.alert.alert-err", icon("warn"), h("span", "Za mało środków w rezerwie Skarbu Świata — nic nie zostanie przelane."))
          : h("p.muted.small", "Jedna operacja: wszystkie przelewy albo żaden. Łączna pula serwera się nie zmienia."),
      ),
      actions: pv.short ? [{ label: "Anuluj", value: null }] : [{ label: "Anuluj", value: null }, { label: "Przelej razem " + formatMoney(pv.total), kind: "primary", value: true }],
    });
    if (!ok) return;
    button.disabled = true;
    button.classList.add("busy");
    try {
      const r = await api.mutate("world.npcTransfer", { direction: "toAllNpc", amount: amount.input.value.trim(), confirm: pv.total, count: pv.count, reason: why, key: st.key });
      toast((r && r.message) || "Przelew zbiorczy zapisany w księdze", "ok");
      st.key = null;
      st.sig = "";
      amount.reset();
      reason.value = "";
      const main = document.getElementById("main");
      if (main) delete main.dataset.dirty;
      ctx.reload();
    } catch (e) {
      toast(e.message || String(e), "err", 7000);
    } finally {
      button.disabled = false;
      button.classList.remove("busy");
    }
  };
  const history = Array.isArray(data.bulk) && data.bulk.length
    ? h(
        "div.ws-npc-bulk-history",
        h("h4", "Ostatnie przelewy zbiorcze"),
        data.bulk.map((op) =>
          h(
            "details.ws-npc-bulk-op",
            h("summary", new Date(op.time).toLocaleString("pl-PL"), " — ", fmtInt(op.count), " × ", formatMoney(op.perCountry), " = ", h("strong", formatMoney(op.total)), " — ", op.reason, op.state !== "DONE" ? h("span.tag", " " + op.state) : null),
            h("ul", (op.legs || []).map((l) => h("li", displayName(l.name), " — ", formatMoney(op.perCountry)))),
          ),
        ),
      )
    : null;
  if (data.bulkAvailable === false) return h("div.ws-npc-bulk", h("h3", "Przelej wszystkim państwom NPC"), h("div.alert.alert-warn", icon("warn"), h("span", "Rejestr przelewów zbiorczych jest niedostępny.")));
  return h(
    "div.ws-npc-bulk",
    h("h3", "Przelej wszystkim państwom NPC"),
    h("p.muted", "Ta sama kwota z rezerwy do skarbca każdego państwa NPC (" + fmtInt(countries.length) + "). Podgląd pokaże listę, sumę i saldo rezerwy po przelewie."),
    h("div.ws-npc-form", field("Kwota na jedno państwo", amount.el), field("Powód", reason)),
    errBox,
    h("div.form-actions", btn("Przelej wszystkim…", { kind: "primary", icon: "coins" }, submit)),
    history,
  );
}
