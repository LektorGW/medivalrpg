// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
import { h } from "./dom.js";

const ns = "http://www.w3.org/2000/svg";
const colors = { BUILT: "#5ebd89", BUILDING: "#e3b45f", PLANNED: "#9caac2", BLOCKED: "#e77c7c" };
const names = { BUILT: "Ukończony", BUILDING: "W budowie", PLANNED: "Planowany", BLOCKED: "Zablokowany" };
const point = p => Array.isArray(p) && p.length >= 3 && p.every(Number.isFinite);

const RAILWAY_LISTS = ["stations", "requests", "works", "lines", "trains", "crews", "permits"];

/** railway.get pochodzi z innej wtyczki (przez refleksję): listy zawsze jako tablice obiektów, `stops` linii jako tablica. */
export function normalizeRailway(data) {
  const d = data && typeof data === "object" ? { ...data } : {};
  for (const k of RAILWAY_LISTS) d[k] = (Array.isArray(d[k]) ? d[k] : []).filter((x) => x && typeof x === "object");
  d.lines = d.lines.map((l) => (Array.isArray(l.stops) ? l : { ...l, stops: [] }));
  return d;
}

/** Kolumna „Miejsce” wniosku: stacja → współrzędne (albo „—”), tor/droga → od → do. */
export const placeText = (r) => `${r.world}: ${r.kind === "station" ? (Array.isArray(r.position) ? r.position.join(", ") : "—") : r.from + " → " + r.to}`;

/** World separation and a shared X/Z scale preserve real network geometry, including bends. */
export function mapModel(network = {}, world) {
  const nodes = (network.nodes || []).filter(n => n.world === world && Number.isFinite(n.x) && Number.isFinite(n.z));
  const ids = new Set(nodes.map(n => n.id));
  const segments = (network.segments || []).filter(s => s.world === world && ids.has(s.from) && ids.has(s.to))
    .map(s => ({ ...s, points: (s.points || []).filter(point) }));
  const points = [...nodes.map(n => [n.x, n.y, n.z]), ...segments.flatMap(s => s.points)];
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (const p of points) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
  if (!points.length) minX = maxX = minZ = maxZ = 0;
  const scale = Math.min(780 / Math.max(1, maxX - minX), 330 / Math.max(1, maxZ - minZ));
  const project = (x, z) => [450 + (x - (minX + maxX) / 2) * scale, 210 + (z - (minZ + maxZ) / 2) * scale];
  return { nodes, segments, project };
}

function svgEl(tag, attrs = {}, text) {
  const el = document.createElementNS(ns, tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  if (text !== undefined) el.textContent = text;
  return el;
}

export function railwayMap(network) {
  if (!network?.nodes?.length) return h("p.muted", "Brak zarejestrowanej sieci kolejowej.");
  const worlds = [...new Set(network.nodes.map(n => n.world))].sort();
  const world = h("select.input", { "aria-label": "Świat mapy kolei" }, worlds.map(w => h("option", { value: w }, w)));
  const canvas = h("div", { style: { "min-width": "0" } });
  const detail = h("p.muted", { "aria-live": "polite" }, "Wybierz stację lub odcinek. Oś X → wschód; oś Z ↓ południe.");
  const draw = () => {
    canvas.replaceChildren();
    const m = mapModel(network, world.value);
    const svg = svgEl("svg", { viewBox: "0 0 900 420", role: "group", class: "railway-network", "aria-label": "Mapa sieci: " + world.value });
    const interactive = (el, label) => {
      el.setAttribute("tabindex", "0"); el.setAttribute("role", "button"); el.setAttribute("aria-label", label);
      el.appendChild(svgEl("title", {}, label));
      el.addEventListener("click", () => { detail.textContent = label; });
      el.addEventListener("keydown", e => { if (["Enter", " "].includes(e.key)) { e.preventDefault(); detail.textContent = label; } });
      return el;
    };
    for (const s of m.segments) {
      if (s.points.length < 2) continue; // No invented straight rails for unsurveyed segments.
      const path = svgEl("polyline", { points: s.points.map(p => m.project(p[0], p[2]).join(",")).join(" "), fill: "none", stroke: colors[s.status] || colors.PLANNED, "stroke-width": 5, "stroke-dasharray": s.status === "BUILT" ? "none" : "9 5" });
      svg.appendChild(interactive(path, `${s.id}: ${s.from} → ${s.to} · ${names[s.status] || s.status}${s.sampled ? " · uproszczony przebieg" : ""}`));
    }
    for (const n of m.nodes) {
      const [x, y] = m.project(n.x, n.z);
      const group = svgEl("g");
      group.appendChild(svgEl("circle", { cx: x, cy: y, r: n.type === "JUNCTION" ? 5 : 9, fill: n.type === "DEPOT" ? "#e3b45f" : "#c8d9f0", stroke: "#172134", "stroke-width": 2 }));
      group.appendChild(svgEl("text", { x: x > 650 ? x - 13 : x + 13, y: y - 13, "text-anchor": x > 650 ? "end" : "start", fill: "currentColor", "font-size": 18 }, n.name));
      svg.appendChild(interactive(group, `${n.name} (${n.id}) · ${n.world} · X ${n.x}, Y ${n.y}, Z ${n.z}`));
    }
    canvas.appendChild(svg);
  };
  world.addEventListener("change", () => { detail.textContent = "Wybierz stację lub odcinek."; draw(); });
  draw();
  return h("div", world, canvas, h("p.muted", "Zielony: ukończony tor · złoty: budowa · szary: plan. Kropki: stacje, bazy i rozjazdy. Przebieg długich odcinków jest uproszczony."), detail);
}
