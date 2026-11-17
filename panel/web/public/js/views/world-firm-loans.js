// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — Skarb Świata → Zarząd: kredyty firm ze Skarbu Świata (etap 2 Firm). Każdy wniosek wymaga zgody WorldSteward:
// firmy.world.loans (wnioski, czynne kredyty, spłaty do zwrotu), firmy.world.loanDecide {id, approve, note} oraz
// firmy.world.loanSweep {id} (spłaty wracają do rezerwy). Karta wczytuje się sama, więc w world.js to jedno wywołanie.
// Firmy sprawdzają UUID właściciela przy KAŻDYM wywołaniu; gdy metody jeszcze nie ma — dopisek zamiast karty.
import { h, add, clear, icon, modal, skeleton } from "../dom.js";
import { card, money, btn, act, table, field } from "../components.js";
import { formatMoney } from "../money.js";
import { fmtInt, fmtDate } from "../util.js";
import * as api from "../api.js";
import { isStageMissing, firmHref, NOTE_MAX } from "../firmy-model.js";

const TITLE = "Kredyty firm ze Skarbu Świata (wymagają zgody)";

/** Karta kredytów firm. ctx — kontekst widoku (nieużywany poza zgodnością z innymi kartami zarządu). */
export function firmLoansCard(ctx) {
  const body = h("div", skeleton(3));
  const el = card(TITLE, body);
  const reload = async () => {
    try {
      const r = await api.read("firmy.world.loans", {});
      draw(r.data || {});
    } catch (e) {
      clear(body);
      if (isStageMissing(e)) add(body, h("p.muted", "Dostępne po wdrożeniu etapu 2 Firm."));
      else add(body, h("div.alert.alert-warn", icon("warn"), h("span", "Nie udało się wczytać kredytów firm: " + (e.message || String(e)))));
    }
  };

  const decide = async (a, approve, b) => {
    const note = h("input.input", { type: "text", maxlength: String(NOTE_MAX), placeholder: approve ? "np. zgoda na rozbudowę warsztatu" : "np. za mały obrót", autocomplete: "off", "aria-label": "Notatka" });
    const ok = await modal({
      title: approve ? "Zatwierdzić kredyt?" : "Odrzucić wniosek?",
      body: h(
        "div",
        h("p", h("strong", a.firmName || a.firm || "Firma"), " prosi o ", h("strong", formatMoney(a.amount)), " na ", fmtInt(a.days), " dni."),
        h("p", "Odsetki: ", money(a.interest), " · do zwrotu razem: ", h("strong", formatMoney((Number(a.amount) || 0) + (Number(a.interest) || 0)))),
        a.purpose ? h("p.muted", "Cel: " + a.purpose) : null,
        approve ? h("p", "Kwota ", h("strong", formatMoney(a.amount)), " przejdzie z rezerwy Skarbu Świata na konto firmy.") : null,
        field("Notatka", note, `Najwyżej ${NOTE_MAX} znaków; firma ją zobaczy.`),
      ),
      actions: [{ label: "Anuluj", value: null }, { label: approve ? "Zatwierdź " + formatMoney(a.amount) : "Odrzuć", kind: approve ? "primary" : "danger", onClick: () => true }],
    });
    if (ok) await act(b, () => api.mutate("firmy.world.loanDecide", { id: String(a.id), approve: approve ? "true" : "false", note: note.value.trim() }), (res) => (res && res.message) || (approve ? "Kredyt zatwierdzony" : "Wniosek odrzucony"), reload);
  };

  const sweep = async (t, b) => {
    const ok = await modal({
      title: "Zwrócić spłaty do rezerwy?",
      body: h("p", "Spłaty kredytu firmy ", h("strong", t.firmName || "?"), " (", h("strong", formatMoney(t.amount)), ") wrócą do rezerwy Skarbu Świata."),
      actions: [{ label: "Anuluj", value: null }, { label: "Zwróć do rezerwy", kind: "primary", onClick: () => true }],
    });
    if (ok) await act(b, () => api.mutate("firmy.world.loanSweep", { id: String(t.id) }), (res) => (res && res.message) || "Spłaty wróciły do rezerwy", reload);
  };

  const firmLink = (x) => (x.firm ? h("a", { href: firmHref(x.firm) }, x.firmName || x.firm) : x.firmName || "—");

  function draw(d) {
    clear(body);
    const apps = Array.isArray(d.applications) ? d.applications : [];
    const active = Array.isArray(d.active) ? d.active : [];
    const back = Array.isArray(d.toReturn) ? d.toReturn : [];
    add(
      body,
      h("p.muted.small", "Kredyt przenosi istniejące pieniądze z rezerwy do firmy i z powrotem — nie tworzy nowych monet."),
      h("h4", "Wnioski"),
      table({
        columns: [
          { key: "firm", label: "Firma", render: firmLink },
          { key: "amount", label: "Kwota", num: true, render: (a) => money(a.amount) },
          { key: "interest", label: "Odsetki", num: true, render: (a) => money(a.interest) },
          { key: "days", label: "Dni", num: true, render: (a) => fmtInt(a.days) },
          { key: "purpose", label: "Cel", render: (a) => a.purpose || "—" },
          { key: "basis", label: "Obrót 30 dni / kapitał / limit", render: (a) => [money(a.income30), " / ", money(a.capital), " / ", money(a.limit)] },
          { key: "requestedAt", label: "Złożony", render: (a) => fmtDate(a.requestedAt) },
          { key: "act", label: "Decyzja", cls: "col-actions", render: (a) => h("div.row-actions", btn("Zatwierdź", { small: true, kind: "primary" }, (_, b) => decide(a, true, b)), btn("Odrzuć", { small: true, kind: "danger" }, (_, b) => decide(a, false, b))) },
        ],
        rows: apps,
        sort: null,
        emptyText: "Brak wniosków o kredyt.",
      }),
      h("h4", "Czynne kredyty"),
      table({
        columns: [
          { key: "firmName", label: "Firma", render: firmLink },
          { key: "principal", label: "Kwota", num: true, render: (l) => money(l.principal) },
          { key: "interest", label: "Odsetki", num: true, render: (l) => money(l.interest) },
          { key: "outstanding", label: "Do spłaty", num: true, render: (l) => money(l.outstanding) },
          { key: "dueAt", label: "Termin", render: (l) => fmtDate(l.dueAt) },
          { key: "status", label: "Stan", render: (l) => l.statusName || l.status || "—" },
        ],
        rows: active,
        sort: null,
        emptyText: "Brak czynnych kredytów.",
      }),
      h("h4", "Spłaty do zwrotu do rezerwy"),
      table({
        columns: [
          { key: "firmName", label: "Firma", render: firmLink },
          { key: "amount", label: "Kwota", num: true, render: (t) => money(t.amount) },
          { key: "act", label: "", cls: "col-actions", render: (t) => btn("Zwróć spłaty do rezerwy", { small: true }, (_, b) => sweep(t, b)) },
        ],
        rows: back,
        sort: null,
        emptyText: "Nic do zwrotu.",
      }),
    );
  }

  reload();
  return el;
}
