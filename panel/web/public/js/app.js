// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — powłoka SPA: router (hash), nagłówek, menu (szuflada na telefonie), baner offline, odświeżanie co 30 s.
import { h, clear, icon, toast, skeletonCards, add } from "./dom.js";
import * as api from "./api.js";
import { roles, RANKS, fmtTime } from "./util.js";
import { money } from "./components.js";
import { loginView } from "./views/login.js";
import { dashboardView } from "./views/dashboard.js";
import { countryView } from "./views/country.js";
import { rankingView } from "./views/ranking.js";
import { npcListView, npcDetailView } from "./views/npc.js";
import { adminView } from "./views/admin.js";
import { railwayView } from "./views/railway.js";
import { worldView, worldAccountView } from "./views/world.js";
import { economyView } from "./views/economy.js";
import { navHref as economyNavHref } from "./economy-model.js";
import { flagView } from "./views/flag.js";
import { flagModerationView } from "./views/flag-moderation.js";
import { flagNav } from "./flag-model.js";
import { firmyView } from "./views/firmy.js";

const REFRESH_MS = 30000;

export const app = { me: null, overview: null, stale: false };

const ROUTES = [
  [/^\/gospodarka(?:\/([^/]+))?$/, economyView],
  [/^\/skarbswiata(?:\/(bilans|konta|przeplywy|gospodarstwa|monety|ruchy|kupiec))?$/, worldView],
  [/^\/skarbswiata\/konto\/([^/]+)$/, worldAccountView],
  [/^\/kolej$/, railwayView],
  [/^\/$/, dashboardView],
  [/^\/kraj\/([^/]+)\/flaga$/, flagView],
  [/^\/flagi$/, flagModerationView],
  [/^\/kraj\/([^/]+)(?:\/([a-z]+))?$/, countryView],
  [/^\/ranking$/, rankingView],
  [/^\/firmy(?:\/([^/]+))?$/, firmyView],
  [/^\/npc$/, npcListView],
  [/^\/npc\/([^/]+)(?:\/([a-z]+))?$/, npcDetailView],
  [/^\/admin(?:\/([a-z]+))?$/, adminView],
];

// ---------------------------------------------------------------- motyw

const THEME_KEY = "panel-theme";
function storedTheme() {
  try {
    return localStorage.getItem(THEME_KEY);
  } catch {
    return null;
  }
}
function applyTheme(t) {
  const theme = t || storedTheme() || (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
  document.documentElement.dataset.theme = theme;
  const btn = document.getElementById("theme-btn");
  if (btn) {
    clear(btn).appendChild(icon(theme === "dark" ? "sun" : "moon"));
    btn.setAttribute("aria-label", theme === "dark" ? "Jasny motyw" : "Ciemny motyw");
    btn.title = btn.getAttribute("aria-label");
  }
}
function toggleTheme() {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  try {
    localStorage.setItem(THEME_KEY, next);
  } catch {}
  applyTheme(next);
}

// ---------------------------------------------------------------- powłoka

let shell = null;

function parseHash() {
  const raw = location.hash.replace(/^#/, "") || "/";
  const [path, q] = raw.split("?");
  return { path: path || "/", query: new URLSearchParams(q || "") };
}

export function go(path) {
  if (location.hash !== "#" + path) location.hash = path;
  else render(false);
}

function buildShell() {
  const root = document.getElementById("app");
  clear(root);
  const drawerToggle = h("button.icon-btn.menu-btn#menu-btn", { type: "button", "aria-label": "Menu", "aria-expanded": "false", "aria-controls": "sidebar", on: { click: () => setDrawer(!document.body.classList.contains("drawer-open")) } }, icon("menu"));
  const topbar = h(
    "header.topbar",
    drawerToggle,
    h("a.brand", { href: "#/" }, h("img.brand-crest", { src: "/favicon.svg", alt: "", width: "34", height: "34" }), h("span.brand-text", h("span.brand-name", "MEDIVALRPG"), h("span.brand-sub", "Panel państw"))),
    h("div.topbar-space"),
    h("div.server-chip#server-chip"),
    h("div.user-chip#user-chip"),
    h("button.icon-btn#theme-btn", { type: "button", on: { click: toggleTheme } }),
    h("button.icon-btn#logout-btn", { type: "button", "aria-label": "Wyloguj", title: "Wyloguj", on: { click: doLogout } }, icon("logout")),
  );
  const banner = h("div.offline-banner#offline-banner", { role: "alert", hidden: true });
  const nav = h("nav.sidebar#sidebar", { "aria-label": "Menu główne" });
  const backdrop = h("div.backdrop", { on: { click: () => setDrawer(false) } });
  const main = h("main.main#main", { tabindex: "-1" });
  main.addEventListener("input", () => (main.dataset.dirty = "1"));
  const footer = h("footer.footer", h("span", "© 2026 Maciej Kaczmarczyk — "), h("a", { href: "https://lektoro.pl", rel: "noopener" }, "lektoro.pl"));
  add(root, h("div.shell", topbar, banner, h("div.layout", nav, backdrop, h("div.content", main, footer))));
  shell = { main, nav, banner };
  applyTheme();
}

function setDrawer(open) {
  document.body.classList.toggle("drawer-open", open);
  const b = document.getElementById("menu-btn");
  if (b) b.setAttribute("aria-expanded", String(open));
}

function navItem(href, label, ic, active) {
  return h("a.nav-item" + (active ? ".active" : ""), { href, "aria-current": active ? "page" : null, on: { click: () => setDrawer(false) } }, icon(ic), h("span", label));
}

function drawNav(path) {
  const me = app.me || {};
  const r = roles(me);
  const c = me.country;
  const myCountryHref = c ? "#/kraj/" + encodeURIComponent(c.id) : null;
  const economyHref = economyNavHref(me);
  const flags = flagNav(me);
  const nav = clear(shell.nav);
  add(nav,
    h("div.drawer-head", h("img.brand-crest", { src: "/favicon.svg", alt: "", width: "30", height: "30" }), h("span.brand-name", "MEDIVALRPG"), h("button.icon-btn", { type: "button", "aria-label": "Zamknij menu", on: { click: () => setDrawer(false) } }, icon("close"))),
    me.roles?.railwayManager ? h("div.nav-group", navItem("#/kolej", "Dyspozytornia kolei", "gauge", path === "/kolej")) : null,
    me.roles?.worldManager ? h("div.nav-group", navItem("#/skarbswiata", "Skarb Świata", "crown", path === "/skarbswiata" || path.startsWith("/skarbswiata/"))) : null,
    h("div.nav-group", navItem("#/", "Pulpit", "home", path === "/"), myCountryHref ? navItem(myCountryHref, c.name || "Mój kraj", "shield", path.startsWith("/kraj/" + encodeURIComponent(c.id)) || path.startsWith("/kraj/" + c.id)) : null, economyHref ? navItem(economyHref, "Gospodarka kraju", "wheat", path === "/gospodarka" || path.startsWith("/gospodarka/")) : null, navItem("#/ranking", "Ranking krajów", "crown", path === "/ranking"), navItem("#/firmy", "Firmy", "coins", path === "/firmy" || path.startsWith("/firmy/"))),
    flags.country || flags.moderation ? h("div.nav-group", h("div.nav-title", "Chorągwie"), flags.country ? navItem(flags.country, "Chorągiew kraju", "shield", "#" + path === flags.country) : null, flags.moderation ? navItem("#/flagi", "Moderacja chorągwi", "eye", path === "/flagi") : null) : null,
    r.canNpc ? h("div.nav-group", h("div.nav-title", "Zarządzanie"), navItem("#/npc", "Państwa NPC", "robot", path.startsWith("/npc")), r.admin ? navItem("#/admin", "Administracja", "key", path.startsWith("/admin") && path !== "/admin/serwer") : null, r.admin ? navItem("#/admin/serwer", "Wydajność serwera", "gauge", path === "/admin/serwer") : null) : null,
    h(
      "div.nav-foot",
      h("div.nav-user", h("div.avatar", (me.name || "?").slice(0, 1).toUpperCase()), h("div", h("div.nav-user-name", me.name || ""), h("div.nav-user-role", r.label || (c ? RANKS[c.rank] || c.rank || "Obywatel" : "Bez kraju")))),
      h("div.nav-balance", h("span.muted", "Konto bankowe"), money(me.balance)),
    ),
  );
  const uc = clear(document.getElementById("user-chip"));
  add(uc, h("span.user-name", me.name || ""), r.label ? h("span.role-badge" + (r.admin ? ".role-admin" : ".role-npc"), r.label) : c ? h("span.role-badge", RANKS[c.rank] || "Obywatel") : null);
  const sc = clear(document.getElementById("server-chip"));
  const ov = app.overview;
  if (api.state.offline) add(sc, h("span.dot.dot-off"), h("span", "Offline"));
  else if (ov && typeof ov.online === "number") add(sc, h("span.dot.dot-on"), h("span", `${ov.online}/${ov.maxPlayers ?? "?"} graczy`));
}

function drawBanner() {
  if (!shell) return;
  const b = shell.banner;
  const off = api.state.offline;
  b.hidden = !off;
  document.body.classList.toggle("offline", off);
  for (const el of document.querySelectorAll("[data-mut]")) {
    if (off) el.setAttribute("aria-disabled", "true");
    else el.removeAttribute("aria-disabled");
  }
  if (off) {
    add(clear(b), icon("warn"), h("div", h("strong", "Serwer gry jest offline."), " ", h("span", api.state.staleAt ? `Pokazuję ostatnie dane z ${fmtTime(api.state.staleAt)} — tryb tylko do odczytu.` : "Panel działa w trybie tylko do odczytu.")));
  }
}

async function doLogout() {
  await api.logout();
  app.me = null;
  app.overview = null;
  toast("Wylogowano. Do zobaczenia w grze!", "ok");
  location.hash = "/logowanie";
}

// ---------------------------------------------------------------- render

let renderSeq = 0;
let lastRender = 0;

function routeFor(path) {
  for (const [re, view] of ROUTES) {
    const m = re.exec(path);
    if (m) {
      try {
        return { view, params: m.slice(1).map((x) => (x === undefined ? undefined : decodeURIComponent(x))) };
      } catch {
        return null; // zniekształcony %-kod w adresie → pulpit
      }
    }
  }
  return null;
}

async function loadGlobals() {
  const [me, ov] = await Promise.all([api.read("me"), api.read("overview").catch((e) => (e.status === 401 ? Promise.reject(e) : { data: app.overview || { countries: [] }, stale: true }))]);
  app.me = me.data;
  app.overview = ov.data || { countries: [] };
  if (!Array.isArray(app.overview.countries)) app.overview.countries = [];
  app.stale = me.stale || ov.stale;
}

export async function render(silent = false, force = false) {
  const seq = ++renderSeq;
  const { path, query } = parseHash();

  if (path === "/logowanie") {
    shell = null;
    document.body.classList.remove("drawer-open", "offline");
    const root = clear(document.getElementById("app"));
    loginView(root, { query, onLogin: async (me) => ((app.me = me), go("/")) });
    applyTheme();
    document.title = "Logowanie · Panel MEDIVALRPG";
    return;
  }

  if (!shell) buildShell();
  const main = shell.main;
  if (silent && !force) {
    // data-keep: widok z własnym stanem (edytor planu) — automatyczne odświeżanie by go zniszczyło.
    if (document.querySelector("dialog[open]") || main.dataset.dirty || main.dataset.keep) return;
    const a = document.activeElement;
    if (a && main.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName)) return;
  }

  const route = routeFor(path);
  if (!route) {
    go("/");
    return;
  }
  const view = route.view;
  if (!silent) {
    add(clear(main), view.skeleton ? view.skeleton() : skeletonCards(3));
    delete main.dataset.dirty;
    delete main.dataset.keep;
  }
  try {
    await loadGlobals();
    if (seq !== renderSeq) return;
    drawNav(path);
    drawBanner();
    const ctx = { app, me: app.me, overview: app.overview, roles: roles(app.me), params: route.params, query, reload: () => render(true, true), forceReload: () => render(false), go };
    const data = await view.load(ctx);
    if (seq !== renderSeq) return;
    if (silent && !force && (document.querySelector("dialog[open]") || main.dataset.dirty || main.dataset.keep)) return;
    const scroll = window.scrollY;
    clear(main);
    delete main.dataset.dirty;
    delete main.dataset.keep;
    view.draw(main, data, ctx);
    document.title = (typeof view.title === "function" ? view.title(data, ctx) : view.title || "Panel") + " · Panel MEDIVALRPG";
    if (silent) window.scrollTo(0, scroll);
    else main.focus({ preventScroll: true });
    drawBanner();
    lastRender = Date.now();
  } catch (e) {
    if (seq !== renderSeq) return;
    if (e.status === 401) {
      location.hash = "/logowanie";
      return;
    }
    if (silent) return;
    add(clear(main), 
      h("section.card.error-card", h("h2", "Nie udało się wczytać danych"), h("p", e.message || String(e)), h("button.btn", { type: "button", on: { click: () => render(false) } }, "Spróbuj ponownie")),
    );
    drawBanner();
  }
}

api.onState((st) => {
  if (st.authLost && parseHash().path !== "/logowanie") {
    if (app.me) toast("Sesja wygasła — zaloguj się ponownie", "err");
    app.me = null;
    location.hash = "/logowanie";
    return;
  }
  drawBanner();
  if (shell && app.me) drawNav(parseHash().path);
  if (!st.offline && shell) render(true);
});

window.addEventListener("hashchange", () => {
  setDrawer(false);
  render(false);
  window.scrollTo(0, 0);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") setDrawer(false);
});
setInterval(() => {
  if (document.visibilityState === "visible" && shell && Date.now() - lastRender >= REFRESH_MS - 500) render(true);
}, 5000);
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && shell && Date.now() - lastRender >= REFRESH_MS) render(true);
});
matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => {
  if (!storedTheme()) applyTheme();
});

applyTheme();
render(false);
