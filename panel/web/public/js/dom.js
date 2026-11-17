// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — budowanie DOM bez innerHTML (żadne dane z serwera nie trafiają do HTML jako kod), toasty, modale.

/**
 * h("div.klasa#id", {attr, on:{click}, style:{...}, dataset}, ...dzieci)
 * Dzieci: węzły, stringi/liczby (jako tekst), tablice, null/false (pomijane).
 */
export function h(tag, props, ...kids) {
  if (props && (typeof props !== "object" || props instanceof Node || Array.isArray(props))) {
    kids.unshift(props);
    props = null;
  }
  const m = /^([a-z0-9-]+)?((?:[.#][\w-]+)*)$/i.exec(tag);
  const el = document.createElement((m && m[1]) || "div");
  if (m && m[2]) {
    for (const part of m[2].match(/[.#][\w-]+/g)) {
      if (part[0] === ".") el.classList.add(part.slice(1));
      else el.id = part.slice(1);
    }
  }
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "on") for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (k === "style") for (const [sk, sv] of Object.entries(v)) el.style.setProperty(sk, sv);
      else if (k === "dataset") Object.assign(el.dataset, v);
      else if (k === "class") String(v).split(/\s+/).filter(Boolean).forEach((c) => el.classList.add(c));
      else if (k === "text") el.textContent = v;
      else if (k in el && typeof v !== "string" && k !== "list") el[k] = v;
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, String(v));
    }
  }
  append(el, kids);
  return el;
}

function append(el, kids) {
  for (const k of kids) {
    if (k === null || k === undefined || k === false || k === true) continue;
    if (Array.isArray(k)) append(el, k);
    else if (k instanceof Node) el.appendChild(k);
    else el.appendChild(document.createTextNode(String(k)));
  }
}

/** Jak Element.append, ale pomija null/false/undefined (natywne append wstawiłoby tekst "null"). */
export function add(el, ...kids) {
  append(el, kids);
  return el;
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

/** Ikony SVG (stałe, bez danych z zewnątrz) — budowane przez DOM API. */
const ICONS = {
  home: "M3 11.5 12 4l9 7.5M5.5 9.8V20h4.5v-5.5h4V20h4.5V9.8",
  shield: "M12 3 4.5 6v5.6c0 4.4 3.1 8.2 7.5 9.4 4.4-1.2 7.5-5 7.5-9.4V6L12 3z",
  crown: "M3.5 8.5 7.5 13l4.5-7 4.5 7 4-4.5-1.8 10H5.3L3.5 8.5zM5.5 21h13",
  scroll: "M7 4h11a2 2 0 0 1 2 2v1h-4M7 4a2 2 0 0 0-2 2v12a2 2 0 0 1-2 2h11a2 2 0 0 0 2-2V7M7 4a2 2 0 0 1 2 2v1H5M9 11h5M9 15h5",
  key: "M14.5 9.5a4 4 0 1 1-8 0 4 4 0 0 1 8 0zM13.4 12.4 20 19m-3-3 2-2m-4.5-.5 1.5-1.5",
  robot: "M12 3v3M6 8h12v10H6zM9.5 12.5h.01M14.5 12.5h.01M9.5 15.5h5M3.5 12v3M20.5 12v3",
  menu: "M4 7h16M4 12h16M4 17h16",
  close: "M6 6l12 12M18 6 6 18",
  sun: "M12 7.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
  moon: "M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5z",
  logout: "M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10",
  coins: "M8.5 9.5a5 2.5 0 1 0 0-.01zM3.5 9.5v4c0 1.4 2.2 2.5 5 2.5s5-1.1 5-2.5v-4M10.5 15.5v3c0 1.4 2.2 2.5 5 2.5s5-1.1 5-2.5v-7c0-1.4-2.2-2.5-5-2.5-.7 0-1.4.1-2 .2",
  users: "M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6M16 4.3a3.5 3.5 0 0 1 0 6.4M18 14.4c2.1.8 3.5 2.8 3.5 5.6",
  swords: "M14.5 17.5 3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2M9.5 6.5 14.5 1.5M21 3v3l-6 6",
  house: "M4 10 12 4l8 6v10H4zM10 20v-6h4v6",
  gear: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z",
  search: "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM15.5 15.5 20 20",
  eye: "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
  map: "M9 4 3 6.5v13.5l6-2.5 6 2.5 6-2.5V4l-6 2.5L9 4zM9 4v13.5M15 6.5V20",
  sort: "M8 4v16M8 4 4.5 7.5M8 4l3.5 3.5M16 20V4M16 20l-3.5-3.5M16 20l3.5-3.5",
  check: "M5 12.5 10 17.5 19.5 7",
  warn: "M12 3.5 2.5 20h19L12 3.5zM12 10v4.5M12 17.5h.01",
  bolt: "M13 2.5 4.5 13.5h6.5l-1 8 8.5-11h-6.5l1-8z",
  gauge: "M3.5 17.5a8.5 8.5 0 1 1 17 0M12 14l4.5-5M11 14a1 1 0 1 0 2 0 1 1 0 1 0-2 0M6.5 12.5h1M16.5 12.5h1M12 7.5v1",
  pause: "M8 5v14M16 5v14",
  play: "M7 4.5v15L19.5 12 7 4.5z",
  flask: "M9.5 3h5M10.5 3v6L4.8 18.4A1.7 1.7 0 0 0 6.3 21h11.4a1.7 1.7 0 0 0 1.5-2.6L13.5 9V3M7.6 14.5h8.8",
  lock: "M6.5 10.5h11v9.5h-11zM8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7.5V12l3 2",
  // edytor planu państwa NPC
  cursor: "M5 3.5 18.5 12l-6 1.5 3.5 6.5-2.5 1.3-3.5-6.6L5 19.5z",
  zone: "M4 7.5 10 4l9.5 3.5L18 17l-8.5 3L4 14.5zM4 7.5h.01M10 4h.01M19.5 7.5h.01M18 17h.01M9.5 20h.01M4 14.5h.01",
  road: "M8 3 4 21M16 3l4 18M12 4v3M12 10.5v3M12 17v3",
  wall: "M3 20V9h3V6h3v3h2V6h2v3h2V6h3v3h3v11zM10 20v-5a2 2 0 0 1 4 0v5",
  eraser: "M8.5 20h11M4.4 13.6l8.9-8.9a2 2 0 0 1 2.8 0l3.2 3.2a2 2 0 0 1 0 2.8L12 18l-2 2H8.5l-4.1-4.1a1.6 1.6 0 0 1 0-2.3zM9 9l6.5 6.5",
  terrain: "M3 3h8v8H3zM13 3h8v8h-8zM3 13h8v8H3zM16.5 13.5v7M13 17h7",
  hand: "M8 13V5.5a1.5 1.5 0 0 1 3 0V11M11 10.5V4a1.5 1.5 0 0 1 3 0v7M14 10.5V5.5a1.5 1.5 0 0 1 3 0V13M17 9.5a1.5 1.5 0 0 1 3 0V14c0 4-3 7-7 7h-1.5c-2.5 0-3.9-1.1-5.3-3L3.6 14.4a1.5 1.5 0 0 1 2.3-1.9L8 14.5",
  undo: "M9 14 4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11",
  redo: "M15 14l5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13",
  plus: "M12 5v14M5 12h14",
  minus: "M5 12h14",
  fit: "M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5",
  grid: "M4 4h16v16H4zM4 9.3h16M4 14.6h16M9.3 4v16M14.6 4v16",
  pen: "M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5zM13.5 7l3 3",
  rect: "M4.5 6.5h15v11h-15z",
  gate: "M4 21V8l8-5 8 5v13M8 21v-7a4 4 0 0 1 8 0v7M8 17h8",
  brush: "M18.5 3.5 10 12M7.5 13.5c-2 0-3.5 1.6-3.5 3.5 0 1.3-.5 2.3-1.5 3 3.5.6 7.5-.8 7.5-4.5zM10 12l2 2",
  fill: "M5 11 12 4l7 7-7 7zM5 11h14M20 15.5s1.5 2 1.5 3a1.5 1.5 0 0 1-3 0c0-1 1.5-3 1.5-3z",
  ring: "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
  rotate: "M20 11a8 8 0 1 0-2.3 5.7M20 4.5V11h-6.5",
  download: "M12 4v11M7.5 10.5 12 15l4.5-4.5M4.5 19.5h15",
  upload: "M12 15V4M7.5 8.5 12 4l4.5 4.5M4.5 19.5h15",
  trash: "M4.5 7h15M9.5 7V4.5h5V7M6.5 7l1 13h9l1-13M10 11v5.5M14 11v5.5",
  church: "M12 2.5v4M10 4.5h4M7 21v-9l5-4 5 4v9zM10 21v-4a2 2 0 0 1 4 0v4M3.5 21h17",
  wheat: "M12 21V9M12 13c-2.5 0-4-1.5-4-4 2.5 0 4 1.5 4 4zM12 13c2.5 0 4-1.5 4-4-2.5 0-4 1.5-4 4zM12 17.5c-2.5 0-4-1.5-4-4 2.5 0 4 1.5 4 4zM12 17.5c2.5 0 4-1.5 4-4-2.5 0-4 1.5-4 4zM12 9c-1.4-1-1.4-4 0-6 1.4 2 1.4 5 0 6z",
  tower: "M7 21V3h10v18M4.5 21h15M10 6h1M13 6h1M10 10h1M13 10h1M10 14h1M13 14h1",
};

export function icon(name, cls = "ic") {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("class", cls);
  svg.setAttribute("aria-hidden", "true");
  const p = document.createElementNS(ns, "path");
  p.setAttribute("d", ICONS[name] || ICONS.scroll);
  svg.appendChild(p);
  return svg;
}

// ---------------------------------------------------------------- toasty

let toastBox = null;
export function toast(msg, kind = "ok", ms = 4200) {
  if (!toastBox) {
    toastBox = h("div.toasts", { role: "status", "aria-live": "polite" });
    document.body.appendChild(toastBox);
  }
  const t = h("div.toast.toast-" + kind, icon(kind === "ok" ? "check" : "warn"), h("span", msg));
  const close = () => {
    t.classList.add("out");
    setTimeout(() => t.remove(), 300);
  };
  t.addEventListener("click", close);
  toastBox.appendChild(t);
  setTimeout(close, ms);
}

// ---------------------------------------------------------------- modale (<dialog>)

/**
 * Otwiera modal. body: węzeł albo funkcja (dialog) → węzeł. actions: [{label, kind, value, onClick}]
 * Zwraca Promise z wartością przycisku (albo null po anulowaniu/Esc).
 */
export function modal({ title, body, actions = [], wide = false }) {
  return new Promise((resolve) => {
    const dlg = h("dialog.modal" + (wide ? ".modal-wide" : ""), { "aria-label": title });
    let done = false;
    const finish = (v) => {
      if (done) return;
      done = true;
      dlg.close();
      dlg.remove();
      resolve(v);
    };
    const content = typeof body === "function" ? body(dlg, finish) : body;
    const btns = actions.map((a) =>
      h(
        "button.btn" + (a.kind ? ".btn-" + a.kind : ""),
        {
          type: "button",
          on: {
            click: async () => {
              if (a.onClick) {
                const r = await a.onClick(dlg);
                if (r === false) return;
                finish(r === undefined ? a.value ?? true : r);
              } else finish(a.value ?? null);
            },
          },
        },
        a.label,
      ),
    );
    add(dlg, 
      h("div.modal-head", h("h2", title), h("button.icon-btn", { type: "button", "aria-label": "Zamknij", on: { click: () => finish(null) } }, icon("close"))),
      h("div.modal-body", content),
      btns.length ? h("div.modal-foot", btns) : null,
    );
    dlg.addEventListener("cancel", (e) => {
      e.preventDefault();
      finish(null);
    });
    dlg.addEventListener("click", (e) => {
      if (e.target === dlg) finish(null);
    });
    document.body.appendChild(dlg);
    dlg.showModal();
    const first = dlg.querySelector(".modal-body input, .modal-body select, .modal-body textarea") || dlg.querySelector(".modal-foot .btn");
    if (first) first.focus();
  });
}

/** Potwierdzenie akcji nieodwracalnej. requireText: użytkownik musi przepisać tekst (np. nazwę kraju). */
export async function confirmDanger({ title, text, confirmLabel = "Potwierdzam", requireText = null }) {
  let input = null;
  const body = h(
    "div.confirm",
    h("div.confirm-ic", icon("warn")),
    h("div", h("p", text), requireText ? h("p.muted", "Aby potwierdzić, wpisz: ", h("strong", requireText)) : null, requireText ? (input = h("input.input", { type: "text", autocomplete: "off", "aria-label": "Potwierdzenie" })) : null),
  );
  const r = await modal({
    title,
    body,
    actions: [
      { label: "Anuluj", value: null },
      {
        label: confirmLabel,
        kind: "danger",
        onClick: () => {
          if (requireText && input.value.trim() !== requireText) {
            input.classList.add("invalid");
            input.focus();
            return false;
          }
          return true;
        },
      },
    ],
  });
  return r === true;
}

export function skeleton(lines = 4, cls = "") {
  return h("div.skel-wrap" + (cls ? "." + cls : ""), { "aria-busy": "true", "aria-label": "Wczytywanie" }, Array.from({ length: lines }, (_, i) => h("div.skel", { class: i === 0 ? "skel-title" : "" })));
}

export function skeletonCards(n = 3) {
  return h("div.grid-cards", Array.from({ length: n }, () => h("div.card", skeleton(4))));
}
