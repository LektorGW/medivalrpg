// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import { h, add, modal, confirmDanger, skeleton } from "../dom.js";
import { card, table, field, money, bar, btn, act } from "../components.js";
import * as api from "../api.js";
import { railwayMap, normalizeRailway, placeText } from "../railway-map.js";

const states = { QUEUED: "W kolejce", BUILDING: "Budowa", RUNNING: "Budowa", BLOCKED: "Zablokowane", HELD: "Oczekiwanie na potwierdzenie transakcji", VERIFYING: "Odbiór torów", DONE: "Ukończone", CANCELLED: "Anulowane" };
const requestStates = { REQUESTED: "Do wyceny", SURVEYING: "Pomiar terenu", QUOTED: "Wyceniony", EXPIRED: "Wymaga ponownej wyceny", FAILED: "Pomiar nieudany", SUBMITTED: "Zlecono budowę", RECONCILE: "Wymaga kontroli transakcji", CANCELLED: "Anulowany" };
const kinds = { station: "Stacja", rail: "Tor", road: "Droga" };
const text = (value = "", placeholder = "") => h("input.input", { type: "text", value, placeholder });
const select = (rows, value, label = r => r.id) => h("select.input", rows.map(r => h("option", { value: r.id, selected: r.id === value }, label(r))));

async function form(title, controls) {
  return modal({ title, body: h("div", controls.map(c => field(c.label, c.el, c.help))), actions: [
    { label: "Anuluj", value: null },
    { label: "Zapisz", kind: "primary", onClick: () => Object.fromEntries(controls.map(c => [c.key, c.el.value.trim()])) },
  ] });
}

export const railwayView = {
  title: () => "Dyspozytornia kolei",
  skeleton: () => h("div.card", skeleton(8)),
  async load(ctx) {
    if (!ctx.me?.roles?.railwayManager) return { denied: true };
    return normalizeRailway((await api.read("railway.get", {})).data);
  },
  draw(main, d, ctx) {
    if (d.denied) return add(main, card("Brak dostępu", h("p", "Dyspozytornia jest dostępna osobistemu zarządcy kolei.")));
    const mutate = (b, method, params) => act(b, () => api.mutate(method, params), "Zapisano w dyspozytorni", ctx.reload);
    add(main, h("div.page-head", h("div", h("h1", "Dyspozytornia kolei"), h("p.muted", d.company + " · zarząd osobisty, niezależny od państwa")), btn("Odśwież", { mut: false }, () => ctx.reload())),
      card("Środki przewoźnika", money(d.balance)));

    add(main, card("Mapa sieci kolejowej", d.network?.commission?h("p",d.network.commission):null, railwayMap(d.network)));
    const newRequest = async (kind, b) => {
      const controls = [{ key: "id", label: "Id wniosku", el: text("", "np. stacja_polnoc") }];
      if (kind === "station") controls.push({ key: "rotation", label: "Obrót stacji", el: select([0, 90, 180, 270].map(n => ({ id: String(n) })), "0"), help: "Stań w grze w miejscu stacji. Serwer zapisze twoje położenie przy utworzeniu wniosku." });
      else {
        controls.push({ key: "from", label: "Od stacji", el: select(d.stations, d.stations[0]?.id, n => `${n.name} (${n.world})`) },
          { key: "to", label: "Do stacji", el: select(d.stations, d.stations[1]?.id, n => `${n.name} (${n.world})`), help: "Dwie ukończone stacje w jednym świecie. Serwer sprawdzi długość, teren i zgody państw." });
      }
      const values = await form("Nowy wniosek: " + kinds[kind], controls);
      if (values) mutate(b, "railway.request.create", { ...values, kind });
    };
    add(main, card("Nowe zlecenia budowy", h("p.muted", "Zapisz wniosek, wykonaj wycenę i osobno potwierdź koszt. Pomiar i potwierdzenie wymagają obecności w grze w świecie projektu. Wycena jest ważna 3 minuty. Podczas pomiaru użyj Odśwież, aby sprawdzić wynik."),
      h("div.actions", btn("Nowa stacja", {}, (_, b) => newRequest("station", b)),
        d.stations.length >= 2 ? btn("Nowy tor", {}, (_, b) => newRequest("rail", b)) : null,
        d.stations.length >= 2 ? btn("Nowa droga", {}, (_, b) => newRequest("road", b)) : null),
      table({ rows: d.requests || [], columns: [
        { key: "id", label: "Wniosek" }, { key: "kind", label: "Rodzaj", render: r => kinds[r.kind] || r.kind },
        { key: "place", label: "Miejsce", render: placeText },
        { key: "state", label: "Stan", render: r => requestStates[r.state] || r.state },
        { key: "price", label: "Wycena", render: r => r.state === "QUOTED" ? h("span", money(r.price), ` · ${r.blocks} zmian · do ${new Date(r.expires).toLocaleTimeString("pl-PL")}`) : "—" },
        { key: "reason", label: "Informacja" }, { key: "job", label: "Projekt budowy" },
        { key: "actions", label: "Działania", render: r => h("div.actions",
          !["SURVEYING", "SUBMITTED", "CANCELLED", "RECONCILE"].includes(r.state) ? btn("Wyceń", { small: true }, (_, b) => mutate(b, "railway.request.quote", { id: r.id })) : null,
          r.state === "QUOTED" && r.expires > Date.now() ? btn("Potwierdź budowę", { small: true }, async (_, b) => {
            if (await confirmDanger({ title: "Zlecić budowę?", text: `${kinds[r.kind]} ${r.id}: ${r.blocks} zmian, koszt ${r.price} md ze środków przewoźnika. Serwer ponownie sprawdzi teren i cenę.`, confirmLabel: "Opłać i zleć budowę" })) mutate(b, "railway.request.confirm", { id: r.id, token: r.token });
          }) : null,
          !["SURVEYING", "SUBMITTED", "CANCELLED", "RECONCILE"].includes(r.state) ? btn("Odrzuć wniosek", { small: true }, (_, b) => mutate(b, "railway.request.cancel", { id: r.id })) : null) },
      ], emptyText: "Brak wniosków. Sam zapis wniosku nie rozpoczyna budowy." })));

    add(main, card("Roboty na sieci", table({ rows: d.works, columns: [
      { key: "id", label: "Projekt" },
      { key: "status", label: "Stan", render: r => states[r.status] || r.status },
      { key: "progress", label: "Postęp", render: r => bar(r.total ? r.done / r.total : r.status === "DONE" ? 1 : 0, `${r.done} / ${r.total}`) },
      { key: "reason", label: "Przyczyna postoju" },
      { key: "cost", label: "Koszt", render: r => money(r.cost) },
      { key: "actions", label: "Działania", render: r => h("div.actions",
        r.status === "BLOCKED" ? btn("Wznów", { small: true }, (_, b) => mutate(b, "railway.work.resume", { id: r.id })) : null,
        !["DONE", "CANCELLED"].includes(r.status) ? btn("Anuluj", { small: true }, async (_, b) => {
          if (await confirmDanger({ title: "Anulować budowę?", text: "Wykonane bloki pozostaną w świecie. Opłacone roboty nie zostaną cofnięte.", confirmLabel: "Anuluj budowę" })) mutate(b, "railway.work.cancel", { id: r.id });
        }) : null) },
    ], emptyText: "Nie ma jeszcze zleceń budowlanych." })));

    add(main, card("Linie i rozkład", btn("Utwórz linię", {}, async (_, b) => {
      const id = text(), stops = text("", "stacja_a,stacja_b");
      const values = await form("Nowa linia", [{ key: "id", label: "Id linii", el: id }, { key: "stops", label: "Stacje po kolei", el: stops, help: "Id stacji oddzielone przecinkami. Linia pozostanie wyłączona do ukończenia torów." }]);
      if (values) mutate(b, "railway.line.create", values);
    }), table({ rows: d.lines, columns: [
      { key: "id", label: "Linia" }, { key: "stops", label: "Przystanki", render: r => r.stops.join(" → ") },
      { key: "timetable", label: "Rozkład" }, { key: "fare", label: "Bilet", render: r => money(r.fare) },
      { key: "active", label: "Ruch", render: r => r.active ? "Włączony" : "Wyłączony" },
      { key: "actions", label: "Działania", render: r => btn("Ustawienia", { small: true }, async (_, b) => {
        const fare = text(String(r.fare)), interval = text("", "np. 30 albo 08:00,12:00"), active = select([{ id: "true" }, { id: "false" }], String(r.active), x => x.id === "true" ? "Włączony" : "Wyłączony");
        const values = await form("Ustawienia: " + r.id, [{ key: "fare", label: "Cena biletu (md)", el: fare }, { key: "interval", label: "Nowy rozkład (opcjonalnie)", el: interval, help: "Obecny: " + r.timetable + ". Puste pole zachowuje rozkład. Liczba oznacza odstęp w minutach przez całą dobę; godziny HH:MM oddziel przecinkami." }, { key: "active", label: "Ruch na linii", el: active }]);
        if (values) mutate(b, "railway.line.configure", { id: r.id, ...values });
      }) },
    ], emptyText: "Utwórz pierwszą linię po wyznaczeniu stacji." })));

    add(main, card("Tabor", table({ rows: d.trains, columns: [
      { key: "id", label: "Skład" }, { key: "status", label: "Stan" }, { key: "node", label: "Stacja" }, { key: "line", label: "Linia" }, { key: "seats", label: "Miejsca" },
      { key: "actions", label: "Działania", render: r => d.lines.length ? btn("Przydziel linię", { small: true }, async (_, b) => {
        const values = await form("Przydział składu " + r.id, [{ key: "line", label: "Linia", el: select(d.lines, r.line) }]);
        if (values) mutate(b, "railway.train.assign", { train: r.id, ...values });
      }) : null },
    ], emptyText: "Brak zakupionych składów." })));

    add(main, card("Maszyniści NPC", table({ rows: d.crews, columns: [
      { key: "name", label: "Imię" }, { key: "train", label: "Skład" }, { key: "paid", label: "Żołd", render: r => r.paid ? "Opłacony" : "Brak środków" },
      { key: "actions", label: "Działania", render: r => d.trains.length ? btn("Przydziel skład", { small: true }, async (_, b) => {
        const values = await form("Przydział: " + r.name, [{ key: "train", label: "Skład", el: select(d.trains, r.train) }]);
        if (values) mutate(b, "railway.crew.assign", { id: r.id, ...values });
      }) : null },
    ], emptyText: "Brak zatrudnionych maszynistów." })));
    add(main, card("Stacje", table({ rows: d.stations, columns: [{ key: "name", label: "Nazwa" }, { key: "id", label: "Id" }, { key: "position", label: "Położenie", render: r => `X ${r.x} · Y ${r.y} · Z ${r.z}` }] })),
      card("Zgody państw", table({ rows: d.permits, columns: [{ key: "name", label: "Państwo" }], emptyText: "Brak zgód. Władca nadaje zgodę komendą /kolej zgoda tak." })));
  },
};
