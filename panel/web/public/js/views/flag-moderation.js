// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — moderacja chorągwi (#/flagi): kolejka obrazków (obecna vs proponowana), zatwierdzanie i odrzucanie z powodem,
// ostatnie decyzje, stan paczki zasobów i przebudowa na żądanie. Rolę moderatora sprawdza Kraje przy każdym wywołaniu;
// odmowa → karta „Brak dostępu” bez żadnych danych.
import { h, add, icon, modal, confirmDanger, skeleton } from "../dom.js";
import { card, btn, act, table, countryName, empty } from "../components.js";
import * as api from "../api.js";
import { fmtDate, fmtAgo } from "../util.js";
import { originalText, packSentence, etaText, historyStatus, checkReason, REASON_MAX } from "../flag-model.js";
import { flagImage } from "./flag.js";

const clock = (ts) => new Intl.DateTimeFormat("pl-PL", { hour: "2-digit", minute: "2-digit" }).format(new Date(ts));

function deniedCard(main, message) {
  add(main, card("Brak dostępu", h("p", "Moderacja chorągwi jest dostępna wyłącznie moderatorom."), message ? h("p.muted", message) : null));
}

async function rejectDialog(item) {
  const area = h("textarea.input.flag-reason-input", { rows: "3", maxlength: String(REASON_MAX), placeholder: "np. Obraźliwy napis, cudzy herb, nieczytelny obrazek", "aria-label": "Powód odrzucenia" });
  const count = h("span.field-help", `0 / ${REASON_MAX}`);
  const errBox = h("div.flag-reason-error", { "aria-live": "polite" });
  area.addEventListener("input", () => {
    count.textContent = `${area.value.trim().length} / ${REASON_MAX}`;
    errBox.replaceChildren();
    area.classList.remove("invalid");
  });
  return modal({
    title: "Odrzucić chorągiew?",
    body: h(
      "div",
      h("div.flag-restore", flagImage(item.png, { color: item.color, alt: "Odrzucana chorągiew" }), h("p", "Kraj ", h("strong", item.name), " zobaczy powód odrzucenia na stronie chorągwi.")),
      h("label.field", h("span.field-label", "Powód (3–200 znaków)"), area, count),
      errBox,
    ),
    actions: [
      { label: "Anuluj", value: null },
      {
        label: "Odrzuć",
        kind: "danger",
        onClick: () => {
          const r = checkReason(area.value);
          if (!r.ok) {
            area.classList.add("invalid");
            errBox.replaceChildren(h("div.alert.alert-err", icon("warn"), h("span", r.error)));
            area.focus();
            return false;
          }
          return r.value;
        },
      },
    ],
  });
}

function queueItem(item, ctx) {
  const approve = btn("Zatwierdź", { kind: "primary", icon: "check" }, async (_, b) => {
    const ok = await modal({
      title: "Zatwierdzić chorągiew?",
      body: h("div.flag-restore", flagImage(item.png, { color: item.color, alt: "Zatwierdzana chorągiew" }), h("p", "Chorągiew kraju ", h("strong", item.name), " trafi do gry przy najbliższej przebudowie paczki zasobów.")),
      actions: [{ label: "Anuluj", value: null }, { label: "Zatwierdź", kind: "primary", value: true }],
    });
    if (ok) act(b, () => api.mutate("admin.flag.approve", { id: item.id, imageId: item.imageId }), (r) => (r && r.message) || "Chorągiew zatwierdzona", ctx.reload);
  });
  const reject = btn("Odrzuć", { kind: "danger", icon: "close" }, async (_, b) => {
    const reason = await rejectDialog(item);
    if (reason) act(b, () => api.mutate("admin.flag.reject", { id: item.id, imageId: item.imageId, reason }), (r) => (r && r.message) || "Chorągiew odrzucona", ctx.reload);
  });
  const el = h(
    "section.card.flag-queue-item",
    { dataset: { country: item.id || "" } },
    h("div.flag-queue-head", countryName({ id: item.id, name: item.name, color: item.color, npc: item.npc }, { link: false }), h("span.muted.small", "Wgrał ", h("strong", item.uploadedBy || "—"), item.uploadedAt ? [" · ", h("span", { title: fmtDate(item.uploadedAt) }, fmtAgo(item.uploadedAt))] : null)),
    h(
      "div.flag-compare",
      h("figure.flag-fig", h("div.flag-frame", flagImage(item.currentPng, { color: item.color, alt: "Obecna chorągiew" })), h("figcaption", "Obecna")),
      h("div.flag-arrow", { "aria-hidden": "true" }, "→"),
      h("figure.flag-fig", h("div.flag-frame.flag-frame-new", flagImage(item.png, { color: item.color, alt: "Proponowana chorągiew" })), h("figcaption", "Proponowana")),
    ),
    h("dl.facts", h("dt", "Oryginał"), h("dd", originalText(item.original)), item.colors != null ? [h("dt", "Kolory"), h("dd", String(item.colors))] : null),
    h("div.form-actions", h("a.btn", { href: "#/kraj/" + encodeURIComponent(item.id) + "/flaga" }, h("span", "Historia kraju")), reject, approve),
  );
  el.style.setProperty("--c", item.color && /^#[0-9a-f]{6}$/i.test(item.color) ? item.color : "#8a7350");
  return el;
}

function packCard(pack, ctx) {
  const p = pack || {};
  return card(
    "Paczka zasobów",
    h(
      "dl.facts",
      h("dt", "Ostatnia przebudowa"),
      h("dd", p.lastRebuildAt ? h("span", { title: fmtDate(p.lastRebuildAt) }, fmtAgo(p.lastRebuildAt)) : "—"),
      h("dt", "Następna"),
      h("dd", p.nextRebuildAt ? `${clock(p.nextRebuildAt)} (${etaText(p.nextRebuildAt)})` : "nie zaplanowano"),
      h("dt", "Czeka na paczkę"),
      h("dd", String(Number(p.queued) || 0)),
      h("dt", "Odstęp między przebudowami"),
      h("dd", p.minIntervalMinutes ? `co najmniej ${p.minIntervalMinutes} min` : "—"),
    ),
    h("p.muted.small", "Zatwierdzone chorągwie trafiają do gry zbiorczo przy przebudowie paczki. Gracze pobiorą nową paczkę przy następnym wejściu albo po odświeżeniu."),
    h(
      "div.form-actions",
      btn("Przebuduj paczkę teraz", { icon: "rotate" }, async (_, b) => {
        const ok = await confirmDanger({ title: "Przebudować paczkę zasobów teraz?", text: "Gracze online dostaną prośbę o pobranie nowej paczki. Rób to tylko, gdy zatwierdzone chorągwie mają się pojawić szybciej.", confirmLabel: "Przebuduj teraz" });
        if (ok) act(b, () => api.mutate("admin.flag.rebuild", {}), (r) => (r && r.message) || "Przebudowa paczki zlecona", ctx.reload);
      }),
    ),
  );
}

export const flagModerationView = {
  title: () => "Moderacja chorągwi",
  skeleton: () => h("div.card", skeleton(8)),
  async load(ctx) {
    if (!ctx.me?.roles?.flagModerator) return { denied: true };
    try {
      return { data: await api.rpc("admin.flag.queue", {}) };
    } catch (e) {
      if (e.app || e.status === 403) return { denied: true, message: e.message };
      throw e;
    }
  },
  draw(main, r, ctx) {
    if (r.denied) return deniedCard(main, r.message);
    const d = r.data || {};
    const items = Array.isArray(d.items) ? d.items : [];
    add(
      main,
      h(
        "div.page-head",
        h("div", h("h1", "Moderacja chorągwi"), h("p.muted", items.length ? `W kolejce: ${items.length}. Sprawdź, czy obrazek nie jest obraźliwy i nie podszywa się pod cudzy herb.` : "Kolejka jest pusta.")),
        btn("Odśwież", { mut: false, icon: "rotate" }, () => ctx.forceReload()),
      ),
    );
    const pending = d.pack && d.pack.nextRebuildAt ? h("div.alert.alert-warn", icon("clock"), h("span", "Zatwierdzone chorągwie czekają na paczkę. ", packSentence(d.pack.nextRebuildAt, Date.now(), clock))) : null;
    add(main, h("div.flag-mod-layout", h("div.flag-queue", items.length ? items.map((i) => queueItem(i, ctx)) : card("Kolejka", empty("Brak obrazków do sprawdzenia.", "check"))), h("div.flag-mod-side", pending, packCard(d.pack, ctx))));
    const recent = Array.isArray(d.recent) ? d.recent : [];
    add(
      main,
      card(
        "Ostatnie decyzje",
        table({
          columns: [
            { key: "name", label: "Kraj", render: (x) => h("a", { href: "#/kraj/" + encodeURIComponent(x.id) + "/flaga" }, x.name || x.id) },
            { key: "status", label: "Decyzja", render: (x) => { const s = historyStatus(x.status); return h("span.flag-status.flag-status-" + s.cls, s.label); } },
            { key: "at", label: "Kiedy", render: (x) => (x.at ? fmtDate(x.at) : "—"), sort: (x) => Number(x.at) || 0 },
            { key: "by", label: "Moderator", render: (x) => x.by || "—" },
            { key: "note", label: "Powód / uwagi", render: (x) => x.note || "" },
          ],
          rows: recent,
          sort: { key: "at", dir: "desc" },
          emptyText: "Brak decyzji.",
          cls: "flag-recent",
        }),
      ),
    );
  },
};
