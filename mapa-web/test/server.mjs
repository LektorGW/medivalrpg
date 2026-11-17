// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Lokalny serwer testowy mapy: pliki squaremap (kopia referencyjna) + nasze pliki z web/,
// /kraje.json z kraje.example.json, markers.json z dorysowanymi granicami krajów przykładowych.
// Kafelki i brakujące pliki idą przez proxy do prawdziwego serwera (z pamięcią podręczną na dysku).
//
//   node test/server.mjs [--port 8099] [--no-kraje] [--offline]
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "..");
const REF = path.resolve(ROOT, "..", "downloads", "squaremap-web");
const WEB = path.join(ROOT, "web");
const FIX = path.join(HERE, "fixtures");
const CACHE = path.join(HERE, "cache");
const UPSTREAM = "http://mc.example.org:8100";

const MIME = {
    ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8", ".png": "image/png", ".ico": "image/x-icon", ".map": "application/json"
};

function readJson(p) { return JSON.parse(fs.readFileSync(p, "utf8")); }

// --- dane testowe -------------------------------------------------------------
function chunkRect(country) {
    // prostokąt chunków o polu >= chunks wokół stolicy
    const n = Math.max(1, country.chunks);
    const w = Math.ceil(Math.sqrt(n));
    const h = Math.ceil(n / w);
    const cx = Math.floor(country.capital.x / 16), cz = Math.floor(country.capital.z / 16);
    const x0 = (cx - Math.floor((w - 1) / 2)) * 16, z0 = (cz - Math.floor((h - 1) / 2)) * 16;
    const x1 = x0 + w * 16, z1 = z0 + h * 16;
    return [[[{ x: x0, z: z0 }, { x: x1, z: z0 }, { x: x1, z: z1 }, { x: x0, z: z1 }]]];
}

function countryMarkers(country) {
    const c = country.color;
    const name = `<span style="font-size:15px;font-weight:bold;color:${c}">${country.name}</span>`;
    return [
        {
            type: "icon", icon: "kraje_capital", point: { x: country.capital.x - 7, z: country.capital.z + 1 },
            size: { x: 16, z: 16 }, anchor: { x: 8, z: 8 }, tooltip_anchor: { x: 0, z: -8 },
            tooltip: `Stolica: <b>${country.name}</b>`
        },
        {
            type: "polygon", points: chunkRect(country), color: c.toLowerCase(), fillColor: c.toLowerCase(),
            fillOpacity: 0.55, weight: 4,
            tooltip: `${name}<br>Władca: ${country.ruler} • ${country.chunks} chunk`,
            popup: `<div style="min-width:180px"> ${name}<br> <b>Władca:</b> ${country.ruler}</div>`
        }
    ];
}

function buildFixtures() {
    const kraje = readJson(path.join(ROOT, "kraje.example.json"));
    const live = readJson(path.join(FIX, "markers.live.json"));
    const settings = readJson(path.join(FIX, "settings.json"));
    const worldSettings = readJson(path.join(FIX, "world-settings.json"));
    const byWorld = {};
    for (const c of kraje.countries) {
        if (c.name === "WPK") continue; // WPK jest już w prawdziwym markers.json
        (byWorld[c.capital.world] ||= []).push(...countryMarkers(c));
    }
    const overworld = live.map((l) => (l.id === "kraje_countries" ? { ...l, markers: [...l.markers, ...(byWorld.minecraft_overworld || [])], timestamp: l.timestamp + 1 } : l));
    const nether = [{ hide: false, z_index: 5, name: "Kraje", control: true, id: "kraje_countries", order: 5, timestamp: 1, markers: byWorld.minecraft_the_nether || [] }];
    if (!settings.worlds.some((w) => w.name === "minecraft_the_nether")) {
        settings.worlds.push({ name: "minecraft_the_nether", icon: "", display_name: "minecraft:the_nether", type: "nether", order: 1 });
    }
    return {
        "/tiles/settings.json": JSON.stringify(settings),
        "/tiles/minecraft_overworld/settings.json": JSON.stringify(worldSettings),
        "/tiles/minecraft_overworld/markers.json": JSON.stringify(overworld),
        "/tiles/minecraft_the_nether/settings.json": JSON.stringify({ ...worldSettings, spawn: { x: 0, z: 0 } }),
        "/tiles/minecraft_the_nether/markers.json": JSON.stringify(nether)
    };
}

// --- proxy z pamięcią podręczną -----------------------------------------------
async function proxied(urlPath, offline) {
    const file = path.join(CACHE, urlPath.replace(/[?#].*$/, "").replace(/^\/+/, "").replace(/[<>:"|?*]/g, "_"));
    if (fs.existsSync(file)) return { status: 200, body: fs.readFileSync(file) };
    if (offline) return { status: 404, body: Buffer.alloc(0) };
    try {
        const ctl = new AbortController();
        const t = setTimeout(() => ctl.abort(), 15000);
        const res = await fetch(UPSTREAM + urlPath, { signal: ctl.signal });
        clearTimeout(t);
        const body = Buffer.from(await res.arrayBuffer());
        if (res.ok) {
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, body);
        }
        return { status: res.status, body };
    } catch {
        return { status: 502, body: Buffer.alloc(0) };
    }
}

export function startServer({ port = 8099, kraje = true, offline = false } = {}) {
    const fixtures = buildFixtures();
    const state = { kraje };
    const server = http.createServer(async (req, res) => {
        let p = decodeURIComponent(new URL(req.url, "http://x").pathname);
        if (p === "/") p = "/index.html";
        const send = (status, body, type) => {
            res.writeHead(status, { "Content-Type": type || MIME[path.extname(p)] || "application/octet-stream", "Cache-Control": "no-store" });
            res.end(body);
        };
        if (p === "/kraje.json") {
            if (!state.kraje) return send(404, "");
            return send(200, fs.readFileSync(path.join(ROOT, "kraje.example.json")));
        }
        if (fixtures[p]) return send(200, fixtures[p]);
        for (const base of [WEB, REF]) {
            const f = path.join(base, p);
            if (f.startsWith(base) && fs.existsSync(f) && fs.statSync(f).isFile()) return send(200, fs.readFileSync(f));
        }
        if (p.startsWith("/tiles/minecraft_the_nether/")) return send(200, ""); // brak kafelków Netheru
        const r = await proxied(p, offline);
        send(r.status, r.body);
    });
    return new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve({ server, state, url: `http://127.0.0.1:${port}/` })));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const args = process.argv.slice(2);
    const port = Number(args[args.indexOf("--port") + 1]) || 8099;
    startServer({ port, kraje: !args.includes("--no-kraje"), offline: args.includes("--offline") }).then(({ url }) =>
        console.log("Mapa testowa: " + url)
    );
}
