/*!
 * Kraje — panel krajów i nazwy krajów na mapie squaremap (MEDIVALRPG)
 * Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
 *
 * Dołączany w index.html PRZED głównym skryptem squaremap:
 *   <script src="kraje-panel.js" defer></script>
 * squaremap nie wystawia globalnego obiektu mapy (S jest zamknięte w module),
 * ale Leaflet z paczki ustawia window.L. Przechwytujemy to przypisanie
 * (akcesor na window.L) i rejestrujemy L.Map.addInitHook, który dostaje
 * instancję mapy zaraz po jej utworzeniu przez squaremap.
 */
(function () {
    "use strict";

    if (window.__krajePanelLoaded) return;
    window.__krajePanelLoaded = true;

    // ------------------------------------------------------------------
    // Konfiguracja (można nadpisać przez window.KRAJE_PANEL_CONFIG)
    // ------------------------------------------------------------------
    var CFG = {
        dataUrl: "kraje.json",
        refreshMs: 30000, // odświeżanie danych
        missingRefreshMs: 60000, // gdy kraje.json nie istnieje (404)
        markersLayerId: "kraje_countries", // warstwa krajów w markers.json
        mobileQuery: "(max-width: 760px)",
        highlightMs: 6500, // jak długo pulsuje granica
        labelAllZoom: 2, // od tego zoomu nazwy wszystkich krajów (o ile się mieszczą)
        labelMinAreaPx: 120, // poniżej labelAllZoom: tylko kraje zajmujące >= tylu px² na ekranie
        labelFontByZoom: [11, 12, 13, 15, 17, 19, 21],
        storagePrefix: "kraje-panel."
    };
    try {
        var user = window.KRAJE_PANEL_CONFIG;
        if (user && typeof user === "object") {
            for (var k in user) if (Object.prototype.hasOwnProperty.call(user, k)) CFG[k] = user[k];
        }
    } catch (e) { /* ignoruj */ }

    // ------------------------------------------------------------------
    // Narzędzia
    // ------------------------------------------------------------------
    function store(key, value) {
        try {
            if (value === undefined) return window.localStorage.getItem(CFG.storagePrefix + key);
            window.localStorage.setItem(CFG.storagePrefix + key, String(value));
        } catch (e) { /* tryb prywatny / zablokowane */ }
        return null;
    }

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text != null) e.textContent = text;
        return e;
    }

    var NBSP = "\u00a0";

    // Polskie grupowanie cyfr także dla liczb 4-cyfrowych („6 144”).
    function fmtNum(n) {
        n = Number(n);
        if (!isFinite(n)) return "0";
        var neg = n < 0;
        var s = String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NBSP);
        return (neg ? "−" : "") + s;
    }

    // 1 chunk, 2–4 chunki, 5+ chunków (12–14 chunków)
    function plural(n, one, few, many) {
        n = Math.abs(Math.round(Number(n) || 0));
        if (n === 1) return one;
        var d = n % 10, h = n % 100;
        if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return few;
        return many;
    }

    function fmtDate(ms) {
        if (!ms) return "—";
        var d = new Date(Number(ms));
        if (isNaN(d.getTime())) return "—";
        var p = function (x) { return (x < 10 ? "0" : "") + x; };
        return p(d.getDate()) + "." + p(d.getMonth() + 1) + "." + d.getFullYear();
    }

    function fmtDateTime(ms) {
        var d = new Date(Number(ms));
        if (!ms || isNaN(d.getTime())) return "";
        var p = function (x) { return (x < 10 ? "0" : "") + x; };
        return fmtDate(ms) + ", " + p(d.getHours()) + ":" + p(d.getMinutes());
    }

    function safeColor(c) {
        return typeof c === "string" && /^#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(c.trim()) ? c.trim() : "#9e9e9e";
    }

    function luminance(hex) {
        var h = hex.replace("#", "");
        if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
        var r = parseInt(h.substr(0, 2), 16) / 255, g = parseInt(h.substr(2, 2), 16) / 255, b = parseInt(h.substr(4, 2), 16) / 255;
        var f = function (v) { return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
    }

    function fold(s) {
        s = String(s == null ? "" : s).toLowerCase();
        try { s = s.normalize("NFD").replace(/[\u0300-\u036f]/g, ""); } catch (e) { /* stare przeglądarki */ }
        return s.replace(/ł/g, "l");
    }

    function num(v) { v = Number(v); return isFinite(v) ? v : 0; }

    function reducedMotion() {
        try { return window.matchMedia("(prefers-reduced-motion: reduce)").matches; } catch (e) { return false; }
    }

    var mq = null;
    try { mq = window.matchMedia(CFG.mobileQuery); } catch (e) { /* brak */ }
    function isMobile() { return mq ? mq.matches : window.innerWidth <= 760; }

    // ------------------------------------------------------------------
    // Przechwycenie mapy Leaflet tworzonej przez squaremap
    // ------------------------------------------------------------------
    var Lf = null; // Leaflet
    var map = null;
    var mapWaiters = [];
    var restoreFire = null;

    function onMap(fn) {
        if (map) fn(map);
        else mapWaiters.push(fn);
    }

    function captureMap(m) {
        if (map || !m) return;
        map = m;
        if (restoreFire) { restoreFire(); restoreFire = null; }
        var w = mapWaiters;
        mapWaiters = [];
        for (var i = 0; i < w.length; i++) {
            try { w[i](m); } catch (e) { reportError(e); }
        }
    }

    function hookLeaflet(L) {
        if (!L || !L.Map || L.__krajeHooked) return;
        L.__krajeHooked = true;
        Lf = L;
        // 1) Normalna ścieżka: hook wywoływany na końcu konstruktora każdej L.Map.
        L.Map.addInitHook(function () { captureMap(this); });
        // 2) Awaryjnie (gdy skrypt załadowano po utworzeniu mapy): pierwsze zdarzenie mapy.
        var proto = L.Map.prototype;
        var hadOwn = Object.prototype.hasOwnProperty.call(proto, "fire");
        var origFire = proto.fire;
        proto.fire = function () {
            // tylko w pełni zainicjalizowana mapa (po callInitHooks)
            if (!map && this._initHooksCalled) captureMap(this);
            return origFire.apply(this, arguments);
        };
        restoreFire = function () {
            if (hadOwn) proto.fire = origFire;
            else delete proto.fire;
        };
    }

    (function trapLeaflet() {
        if (window.L && window.L.Map) {
            hookLeaflet(window.L);
            return;
        }
        var current = window.L;
        try {
            Object.defineProperty(window, "L", {
                configurable: true,
                enumerable: true,
                get: function () { return current; },
                set: function (v) {
                    current = v;
                    try {
                        Object.defineProperty(window, "L", { value: v, writable: true, configurable: true, enumerable: true });
                    } catch (e) { /* zostaw akcesor */ }
                    try { hookLeaflet(v); } catch (e) { reportError(e); }
                }
            });
        } catch (e) {
            // Nie da się założyć akcesora — odpytuj.
            var tries = 0;
            var t = setInterval(function () {
                if (window.L && window.L.Map) { clearInterval(t); hookLeaflet(window.L); }
                else if (++tries > 600) clearInterval(t);
            }, 50);
        }
    })();

    function reportError(e) {
        // Błędy własne pokazujemy tylko w trybie debug, żeby nie zaśmiecać konsoli graczom.
        if (store("debug") === "1" && window.console) console.warn("[kraje-panel]", e);
    }

    // ------------------------------------------------------------------
    // Stan świata (squaremap nie wystawia S, więc czytamy go z mapy)
    // ------------------------------------------------------------------
    var worldState = { name: null, maxZoom: null };
    var worldsInfo = {}; // name -> {display_name, ...} z tiles/settings.json
    var worldListeners = [];
    var pendingWorld = null; // {name, cb, timer}

    function detectWorld() {
        var found = null;
        map.eachLayer(function (l) {
            if (l instanceof Lf.TileLayer && typeof l._url === "string") {
                var m = /tiles\/([^\/]+)\/\{z\}/.exec(l._url);
                if (m) found = { name: decodeURIComponent(m[1]), maxZoom: l.options.maxNativeZoom };
            }
        });
        return found;
    }

    function scale() {
        if (map && map.options && typeof map.options.scale === "number" && map.options.scale > 0) return map.options.scale;
        if (worldState.maxZoom != null) return 1 / Math.pow(2, worldState.maxZoom);
        return 1 / 8;
    }

    // Identyczne z SquaremapMap.toLatLng: L.latLng(-z * scale, x * scale),
    // gdzie scale = 1 / 2^zoom.max świata (CRS.Simple).
    function toLatLng(x, z) {
        var s = scale();
        return Lf.latLng(-num(z) * s, num(x) * s);
    }

    function maxMapZoom() {
        var mz = map.getMaxZoom();
        return isFinite(mz) ? mz : (worldState.maxZoom || 3) + 2;
    }

    function checkWorld() {
        if (!map) return;
        var w = detectWorld();
        if (!w) return;
        var changed = w.name !== worldState.name;
        worldState.name = w.name;
        worldState.maxZoom = w.maxZoom;
        if (changed) {
            for (var i = 0; i < worldListeners.length; i++) {
                try { worldListeners[i](w.name); } catch (e) { reportError(e); }
            }
        }
        if (pendingWorld && pendingWorld.name === w.name) {
            var p = pendingWorld;
            pendingWorld = null;
            clearTimeout(p.timer);
            // squaremap po załadowaniu świata wywołuje jeszcze centerOn(spawn) w tym samym
            // wywołaniu zwrotnym — ruszamy dopiero po nim.
            setTimeout(p.cb, 60);
        }
    }

    function worldDisplayName(name) {
        var w = worldsInfo[name];
        return w && w.display_name ? w.display_name : name;
    }

    function worldLink(name) {
        var box = document.getElementById("worlds");
        if (!box) return null;
        var links = box.getElementsByTagName("a");
        for (var i = 0; i < links.length; i++) if (links[i].id === name) return links[i];
        return null;
    }

    function switchWorld(name, cb, fallbackUrl) {
        var link = worldLink(name);
        if (!link) {
            // Nieznany świat w UI squaremap — przeładuj z parametrami URL (?world=&x=&z=&zoom=).
            if (worldsInfo[name] && fallbackUrl) window.location.search = fallbackUrl;
            return false;
        }
        if (pendingWorld) clearTimeout(pendingWorld.timer);
        pendingWorld = {
            name: name,
            cb: cb,
            timer: setTimeout(function () { pendingWorld = null; }, 10000)
        };
        link.click(); // WorldList: loadWorld(name) — tak samo jak klik gracza
        return true;
    }

    // ------------------------------------------------------------------
    // Dane krajów
    // ------------------------------------------------------------------
    var countries = [];
    var dataState = "loading"; // loading | ok | missing | error
    var lastJson = null;
    var updatedAt = 0;
    var refreshTimer = null;
    var requestSeq = 0;

    function normalizeCountry(c) {
        if (!c || typeof c !== "object" || typeof c.name !== "string" || !c.name) return null;
        var cap = c.capital && typeof c.capital === "object" && isFinite(Number(c.capital.x)) && isFinite(Number(c.capital.z))
            ? { world: typeof c.capital.world === "string" ? c.capital.world : null, x: Number(c.capital.x), z: Number(c.capital.z) }
            : null;
        var list = function (a) {
            return Array.isArray(a) ? a.filter(function (s) { return typeof s === "string" && s; }) : [];
        };
        return {
            id: typeof c.id === "string" && c.id ? c.id : c.name,
            name: c.name,
            color: safeColor(c.color),
            npc: !!c.npc,
            description: typeof c.description === "string" ? c.description : "",
            ruler: typeof c.ruler === "string" ? c.ruler : "",
            members: num(c.members),
            population: num(c.population),
            soldiers: num(c.soldiers),
            chunks: num(c.chunks),
            areaBlocks: c.areaBlocks != null ? num(c.areaBlocks) : num(c.chunks) * 256,
            founded: num(c.founded),
            capital: cap,
            allies: list(c.allies),
            wars: list(c.wars),
            sieges: num(c.sieges)
        };
    }

    function loadData() {
        clearTimeout(refreshTimer);
        var seq = ++requestSeq;
        var stale = function () { return seq !== requestSeq; };
        var next = CFG.refreshMs;
        var p;
        try {
            p = fetch(CFG.dataUrl, { cache: "no-store" });
        } catch (e) {
            p = Promise.reject(e);
        }
        p.then(function (res) {
            if (stale()) return null;
            if (res.status === 404) {
                next = CFG.missingRefreshMs;
                // doczytaj (puste) ciało, żeby przeglądarka nie zgłaszała przerwanego żądania
                return res.text().catch(function () {}).then(function () { if (!stale()) applyData(null, "missing"); });
            }
            if (!res.ok) {
                return res.text().catch(function () {}).then(function () { throw new Error("HTTP " + res.status); });
            }
            return res.text().then(function (txt) {
                if (stale()) return;
                if (txt === lastJson && dataState === "ok") return; // bez zmian
                var json = JSON.parse(txt);
                lastJson = txt;
                applyData(json, "ok");
            });
        }).catch(function (e) {
            if (stale()) return;
            reportError(e);
            if (dataState === "loading" || dataState === "missing") applyData(null, "error");
            else setStatus("error");
        }).then(function () {
            if (!stale()) refreshTimer = setTimeout(loadData, next);
        });
    }

    function applyData(json, state) {
        dataState = state;
        if (state === "ok") {
            var arr = json && Array.isArray(json.countries) ? json.countries : Array.isArray(json) ? json : [];
            var out = [];
            for (var i = 0; i < arr.length; i++) {
                var c = normalizeCountry(arr[i]);
                if (c) out.push(c);
            }
            countries = out;
            updatedAt = json && json.updated ? num(json.updated) : 0;
        } else if (state === "missing") {
            countries = [];
            lastJson = null;
            updatedAt = 0;
        }
        renderList();
        setStatus(state);
        if (map && worldState.name) rebuildLabels();
    }

    function byName(name) {
        for (var i = 0; i < countries.length; i++) if (countries[i].name === name) return countries[i];
        return null;
    }

    function byId(id) {
        for (var i = 0; i < countries.length; i++) if (countries[i].id === id) return countries[i];
        return null;
    }

    // ------------------------------------------------------------------
    // Granice z markers.json (do podświetlenia i dopasowania przybliżenia)
    // ------------------------------------------------------------------
    var markersCache = {}; // world -> {time, shapes:[{name, color, rings}]}

    function textOf(html) {
        if (typeof html !== "string" || !html) return "";
        try {
            var doc = new DOMParser().parseFromString(html, "text/html");
            var span = doc.querySelector("span, b, strong");
            return (span ? span.textContent : doc.body.textContent || "").trim();
        } catch (e) { return ""; }
    }

    // points: [ [ [ {x,z}... ] ] ] (multipolygon) lub płytsze warianty → lista pierścieni [[{x,z}]]
    function ringsOf(points) {
        var rings = [];
        (function walk(a) {
            if (!Array.isArray(a) || !a.length) return;
            if (a[0] && typeof a[0] === "object" && !Array.isArray(a[0]) && "x" in a[0]) {
                rings.push(a);
                return;
            }
            for (var i = 0; i < a.length; i++) walk(a[i]);
        })(points);
        return rings;
    }

    function loadShapes(worldName) {
        var c = markersCache[worldName];
        if (c && Date.now() - c.time < 15000) return Promise.resolve(c.shapes);
        return fetch("tiles/" + encodeURIComponent(worldName) + "/markers.json", { cache: "no-store" })
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (json) {
                var shapes = [];
                if (Array.isArray(json)) {
                    var layers = json.filter(function (l) { return l && l.id === CFG.markersLayerId; });
                    if (!layers.length) layers = json;
                    layers.forEach(function (layer) {
                        (layer.markers || []).forEach(function (m) {
                            if (!m || (m.type !== "polygon" && m.type !== "rectangle")) return;
                            var rings = m.type === "rectangle" && m.points && m.points.length === 2
                                ? [[m.points[0], { x: m.points[1].x, z: m.points[0].z }, m.points[1], { x: m.points[0].x, z: m.points[1].z }]]
                                : ringsOf(m.points);
                            if (!rings.length) return;
                            shapes.push({
                                name: textOf(m.tooltip) || textOf(m.popup),
                                color: typeof m.color === "string" ? m.color.toLowerCase() : "",
                                rings: rings
                            });
                        });
                    });
                }
                markersCache[worldName] = { time: Date.now(), shapes: shapes };
                return shapes;
            })
            .catch(function () { return []; });
    }

    function pointInRing(x, z, ring) {
        var inside = false;
        for (var i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            var xi = ring[i].x, zi = ring[i].z, xj = ring[j].x, zj = ring[j].z;
            if (((zi > z) !== (zj > z)) && (x < (xj - xi) * (z - zi) / (zj - zi) + xi)) inside = !inside;
        }
        return inside;
    }

    function shapesFor(country, shapes) {
        var r = shapes.filter(function (s) { return s.name === country.name; });
        if (r.length) return r;
        if (country.capital) {
            r = shapes.filter(function (s) {
                return s.rings.some(function (ring) { return pointInRing(country.capital.x + 0.5, country.capital.z + 0.5, ring); });
            });
            if (r.length) return r;
        }
        var col = country.color.toLowerCase();
        var sameColor = countries.filter(function (c) { return c.color.toLowerCase() === col; }).length;
        if (sameColor === 1) return shapes.filter(function (s) { return s.color === col; });
        return [];
    }

    // ------------------------------------------------------------------
    // Podświetlenie granicy
    // ------------------------------------------------------------------
    var hlLayer = null, hlTimer = null, hlRenderer = null;

    function clearHighlight() {
        clearTimeout(hlTimer);
        if (hlLayer) { hlLayer.remove(); hlLayer = null; }
    }

    function highlight(country, shapes) {
        clearHighlight();
        if (!hlRenderer) hlRenderer = Lf.svg({ padding: 0.5, pane: "krajeHighlight" });
        var group = Lf.layerGroup();
        var opts = function (extra) {
            var o = { renderer: hlRenderer, pane: "krajeHighlight", interactive: false, color: country.color, lineJoin: "round" };
            for (var k in extra) o[k] = extra[k];
            return o;
        };
        if (shapes.length) {
            shapes.forEach(function (s) {
                var latlngs = s.rings.map(function (ring) {
                    return ring.map(function (p) { return toLatLng(p.x, p.z); });
                });
                Lf.polygon(latlngs, opts({ className: "kp-hl-glow", color: "#fff6d5", weight: 9, opacity: 0.9, fill: false })).addTo(group);
                Lf.polygon(latlngs, opts({ className: "kp-hl-line", weight: 3.5, opacity: 1, fill: true, fillColor: country.color, fillOpacity: 0.18 })).addTo(group);
            });
        } else if (country.capital) {
            var ll = toLatLng(country.capital.x, country.capital.z);
            Lf.circleMarker(ll, opts({ className: "kp-hl-glow", radius: 22, color: "#fff6d5", weight: 6, fill: false })).addTo(group);
            Lf.circleMarker(ll, opts({ className: "kp-hl-line", radius: 22, weight: 3, fill: false })).addTo(group);
        }
        group.addTo(map);
        hlLayer = group;
        hlTimer = setTimeout(function () {
            var pane = map.getPane("krajeHighlight");
            if (pane) pane.classList.add("kp-hl-fade");
            hlTimer = setTimeout(function () {
                clearHighlight();
                if (pane) pane.classList.remove("kp-hl-fade");
            }, 700);
        }, CFG.highlightMs);
    }

    // ------------------------------------------------------------------
    // Przelot do kraju
    // ------------------------------------------------------------------
    function panelOffsetPx() {
        if (!panelOpen || isMobile() || !panel) return 0;
        return panel.getBoundingClientRect().width || 0;
    }

    function zoomForShapes(shapes, country) {
        var size = map.getSize();
        var usableW = Math.max(200, size.x - panelOffsetPx());
        var maxZ = Math.min(maxMapZoom(), (worldState.maxZoom || 3) + 1);
        var minZ = Math.max(map.getMinZoom() || 0, 1);
        var extent;
        if (shapes.length) {
            var minX = Infinity, maxX = -Infinity, minZz = Infinity, maxZz = -Infinity;
            shapes.forEach(function (s) {
                s.rings.forEach(function (r) {
                    r.forEach(function (p) {
                        if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x;
                        if (p.z < minZz) minZz = p.z; if (p.z > maxZz) maxZz = p.z;
                    });
                });
            });
            extent = { w: Math.max(16, maxX - minX), h: Math.max(16, maxZz - minZz) };
        } else {
            var side = Math.sqrt(Math.max(256, country.areaBlocks || 256));
            extent = { w: side, h: side };
        }
        // ile pikseli na blok przy zoomie z: 2^z * scale
        var fitW = (usableW * 0.6) / extent.w, fitH = (size.y * 0.6) / extent.h;
        var ppb = Math.min(fitW, fitH);
        var z = Math.floor(Math.log(ppb / scale()) / Math.LN2);
        if (!isFinite(z)) z = worldState.maxZoom || 3;
        return Math.max(minZ, Math.min(maxZ, z));
    }

    function flyTo(country) {
        if (!country.capital || !map) return;
        var cap = country.capital;
        var go = function () {
            var w = worldState.name;
            loadShapes(w).then(function (all) {
                if (worldState.name !== w) return;
                var shapes = shapesFor(country, all);
                var z = zoomForShapes(shapes, country);
                var target = toLatLng(cap.x + 0.5, cap.z + 0.5);
                // środek widocznej części mapy (na prawo od panelu)
                var off = panelOffsetPx() / 2;
                var center = off ? map.unproject(map.project(target, z).subtract([off, 0]), z) : target;
                map.stop();
                if (reducedMotion()) map.setView(center, z, { animate: false });
                else map.flyTo(center, z, { duration: 1.15, easeLinearity: 0.3 });
                highlight(country, shapes);
            });
        };
        if (cap.world && worldState.name && cap.world !== worldState.name) {
            var zoomGuess = (worldState.maxZoom || 3);
            var url = "?world=" + encodeURIComponent(cap.world) + "&x=" + Math.round(cap.x) + "&z=" + Math.round(cap.z) + "&zoom=" + zoomGuess;
            if (!worldsInfo[cap.world] && Object.keys(worldsInfo).length) {
                setStatus("noworld", worldDisplayName(cap.world));
                return;
            }
            switchWorld(cap.world, go, url);
        } else {
            go();
        }
    }

    // ------------------------------------------------------------------
    // Stałe napisy z nazwami krajów
    // ------------------------------------------------------------------
    var labelGroup = null, labels = [], showLabels = store("labels") !== "0", layoutQueued = false;

    function rebuildLabels() {
        if (!map || !labelGroup) return;
        labelGroup.clearLayers();
        labels = [];
        countries.forEach(function (c) {
            if (!c.capital) return;
            if (c.capital.world && worldState.name && c.capital.world !== worldState.name) return;
            var span = el("span", "kp-label" + (luminance(c.color) < 0.12 ? " kp-label-dark" : ""), c.name);
            span.style.color = c.color;
            var icon = Lf.divIcon({ className: "kp-label-anchor", html: span, iconSize: [0, 0], iconAnchor: [0, 0] });
            var m = Lf.marker(toLatLng(c.capital.x + 0.5, c.capital.z + 0.5), {
                icon: icon, pane: "krajeLabels", interactive: false, keyboard: false, bubblingMouseEvents: false
            });
            m.addTo(labelGroup);
            labels.push({ country: c, marker: m, span: span });
        });
        labels.sort(function (a, b) {
            return b.country.chunks - a.country.chunks || b.country.members - a.country.members || a.country.name.localeCompare(b.country.name, "pl");
        });
        queueLayout();
    }

    function queueLayout() {
        if (layoutQueued) return;
        layoutQueued = true;
        (window.requestAnimationFrame || setTimeout)(function () { layoutQueued = false; layoutLabels(); });
    }

    function layoutLabels() {
        if (!map) return;
        var pane = map.getPane("krajeLabels");
        if (!pane) return;
        pane.classList.toggle("kp-labels-off", !showLabels);
        if (!showLabels || !labels.length) return;
        var z = Math.round(map.getZoom());
        var fonts = CFG.labelFontByZoom;
        pane.style.setProperty("--kp-label-size", fonts[Math.max(0, Math.min(fonts.length - 1, z))] + "px");
        var ppb = Math.pow(2, map.getZoom()) * scale(); // piksele na blok
        var placed = [];
        var pad = 4;
        var size = map.getSize();
        for (var i = 0; i < labels.length; i++) {
            var L0 = labels[i];
            var areaPx = L0.country.areaBlocks * ppb * ppb;
            var ok = z >= CFG.labelAllZoom || areaPx >= CFG.labelMinAreaPx;
            var r = null;
            if (ok) {
                r = L0.span.getBoundingClientRect();
                var mr = map.getContainer().getBoundingClientRect();
                var box = { l: r.left - mr.left - pad, t: r.top - mr.top - pad, r: r.right - mr.left + pad, b: r.bottom - mr.top + pad };
                var onScreen = box.r > 0 && box.b > 0 && box.l < size.x && box.t < size.y;
                for (var j = 0; ok && j < placed.length; j++) {
                    var p = placed[j];
                    if (box.l < p.r && box.r > p.l && box.t < p.b && box.b > p.t) ok = false;
                }
                if (ok && onScreen) placed.push(box);
            }
            L0.span.classList.toggle("kp-label-hidden", !ok);
        }
    }

    function setShowLabels(v) {
        showLabels = !!v;
        store("labels", showLabels ? "1" : "0");
        if (labelsToggle) labelsToggle.checked = showLabels;
        queueLayout();
    }

    // ------------------------------------------------------------------
    // Panel
    // ------------------------------------------------------------------
    var panel, listEl, emptyEl, countEl, searchEl, statusEl, updatedEl, labelsToggle, backdrop, toggleBtn;
    var sortBtns = {};
    var panelOpen = false;
    var panelEnabled = true;
    var sortMode = store("sort") || "area";
    if (["area", "citizens", "name"].indexOf(sortMode) < 0) sortMode = "area";
    var expandedId = null;
    var query = "";

    function openKey() { return isMobile() ? "open.mobile" : "open.desktop"; }

    function setOpen(open, persist) {
        panelOpen = !!open;
        document.documentElement.classList.toggle("kp-open", panelOpen);
        if (panel) {
            panel.setAttribute("aria-hidden", panelOpen ? "false" : "true");
            if ("inert" in panel) panel.inert = !panelOpen;
        }
        if (toggleBtn) {
            toggleBtn.setAttribute("aria-expanded", panelOpen ? "true" : "false");
            toggleBtn.title = panelOpen ? "Zwiń listę krajów" : "Pokaż listę krajów";
        }
        if (persist !== false) store(openKey(), panelOpen ? "1" : "0");
        if (map) setTimeout(queueLayout, 300);
    }

    function initialOpen() {
        var v = store(openKey());
        if (v === "1") return true;
        if (v === "0") return false;
        return !isMobile();
    }

    function buildPanel() {
        panel = el("aside", "kp-panel");
        panel.id = "kraje-panel";
        panel.setAttribute("aria-label", "Kraje świata");

        var head = el("header", "kp-head");
        var titleRow = el("div", "kp-title-row");
        var crest = el("span", "kp-crest", "⚜");
        crest.setAttribute("aria-hidden", "true");
        var h = el("h2", "kp-title", "Kraje świata");
        countEl = el("span", "kp-count", "0");
        countEl.title = "Liczba krajów";
        var close = el("button", "kp-close", "‹");
        close.type = "button";
        close.title = "Zwiń panel";
        close.setAttribute("aria-label", "Zwiń panel");
        close.addEventListener("click", function () { setOpen(false); });
        titleRow.appendChild(crest);
        titleRow.appendChild(h);
        titleRow.appendChild(countEl);
        titleRow.appendChild(close);
        head.appendChild(titleRow);

        var tools = el("div", "kp-tools");
        var searchWrap = el("label", "kp-search");
        var sIcon = el("span", "kp-search-icon", "⌕");
        sIcon.setAttribute("aria-hidden", "true");
        searchEl = document.createElement("input");
        searchEl.type = "search";
        searchEl.placeholder = "Szukaj kraju lub władcy…";
        searchEl.setAttribute("aria-label", "Szukaj kraju lub władcy");
        searchEl.autocomplete = "off";
        searchEl.spellcheck = false;
        searchEl.addEventListener("input", function () { query = searchEl.value; renderList(); });
        searchEl.addEventListener("keydown", function (e) {
            if (e.key === "Escape" && searchEl.value) { searchEl.value = ""; query = ""; renderList(); e.stopPropagation(); }
        });
        searchWrap.appendChild(sIcon);
        searchWrap.appendChild(searchEl);
        tools.appendChild(searchWrap);

        var sortRow = el("div", "kp-sort");
        sortRow.setAttribute("role", "group");
        sortRow.setAttribute("aria-label", "Sortowanie");
        sortRow.appendChild(el("span", "kp-sort-label", "Sortuj:"));
        [["area", "Powierzchnia"], ["citizens", "Obywatele"], ["name", "Nazwa"]].forEach(function (s) {
            var b = el("button", "kp-sort-btn", s[1]);
            b.type = "button";
            b.addEventListener("click", function () {
                sortMode = s[0];
                store("sort", sortMode);
                updateSortButtons();
                renderList();
            });
            sortBtns[s[0]] = b;
            sortRow.appendChild(b);
        });
        tools.appendChild(sortRow);

        var lab = el("label", "kp-switch");
        labelsToggle = document.createElement("input");
        labelsToggle.type = "checkbox";
        labelsToggle.checked = showLabels;
        labelsToggle.addEventListener("change", function () { setShowLabels(labelsToggle.checked); });
        lab.appendChild(labelsToggle);
        lab.appendChild(el("span", "kp-switch-track"));
        lab.appendChild(el("span", "kp-switch-text", "Pokaż nazwy na mapie"));
        tools.appendChild(lab);
        head.appendChild(tools);
        panel.appendChild(head);

        listEl = el("ul", "kp-list");
        listEl.setAttribute("role", "list");
        panel.appendChild(listEl);

        emptyEl = el("div", "kp-empty");
        emptyEl.hidden = true;
        panel.appendChild(emptyEl);

        var foot = el("footer", "kp-foot");
        statusEl = el("div", "kp-status");
        statusEl.setAttribute("role", "status");
        updatedEl = el("div", "kp-updated");
        var copy = el("a", "kp-copy", "© 2026 Maciej Kaczmarczyk — lektoro.pl");
        copy.href = "https://lektoro.pl";
        copy.target = "_blank";
        copy.rel = "noopener";
        foot.appendChild(statusEl);
        foot.appendChild(updatedEl);
        foot.appendChild(copy);
        panel.appendChild(foot);

        // Kliknięcia w panelu nie mogą dochodzić do squaremap (np. przestawanie śledzenia gracza)
        ["click", "dblclick", "mousedown", "touchstart", "wheel", "contextmenu"].forEach(function (ev) {
            panel.addEventListener(ev, function (e) { e.stopPropagation(); }, { passive: true });
        });

        backdrop = el("div", "kp-backdrop");
        backdrop.addEventListener("click", function () { setOpen(false); });

        document.body.appendChild(backdrop);
        document.body.appendChild(panel);

        document.addEventListener("keydown", function (e) {
            if (e.key === "Escape" && panelOpen && isMobile()) setOpen(false);
        });

        if (mq) {
            var onMq = function () { setOpen(initialOpen(), false); };
            if (mq.addEventListener) mq.addEventListener("change", onMq);
            else if (mq.addListener) mq.addListener(onMq);
        }

        updateSortButtons();
        setOpen(initialOpen(), false);
        renderList();
        setStatus(dataState);
    }

    function updateSortButtons() {
        for (var k in sortBtns) {
            sortBtns[k].classList.toggle("kp-active", k === sortMode);
            sortBtns[k].setAttribute("aria-pressed", k === sortMode ? "true" : "false");
        }
    }

    function sorted() {
        var a = countries.slice();
        var cmpName = function (x, y) { return x.name.localeCompare(y.name, "pl", { sensitivity: "base" }); };
        if (sortMode === "name") a.sort(cmpName);
        else if (sortMode === "citizens") a.sort(function (x, y) { return (y.members - x.members) || (y.population - x.population) || cmpName(x, y); });
        else a.sort(function (x, y) { return (y.chunks - x.chunks) || (y.areaBlocks - x.areaBlocks) || cmpName(x, y); });
        return a;
    }

    function matches(c, q) {
        if (!q) return true;
        return fold(c.name).indexOf(q) >= 0 || fold(c.ruler).indexOf(q) >= 0;
    }

    function stat(label, value, title) {
        var s = el("span", "kp-stat");
        s.title = title;
        s.appendChild(el("span", "kp-stat-label", label + " "));
        s.appendChild(el("span", "kp-stat-value", fmtNum(value)));
        return s;
    }

    function relationLinks(names, cls) {
        var wrap = el("span", "kp-rel " + cls);
        if (!names.length) { wrap.textContent = "—"; return wrap; }
        names.forEach(function (n, i) {
            if (i) wrap.appendChild(document.createTextNode(", "));
            var target = byName(n);
            if (target) {
                var b = el("button", "kp-rel-link", n);
                b.type = "button";
                b.title = "Pokaż " + n + " na mapie";
                b.addEventListener("click", function (e) {
                    e.stopPropagation();
                    selectCountry(target.id, true);
                });
                wrap.appendChild(b);
            } else {
                wrap.appendChild(document.createTextNode(n));
            }
        });
        return wrap;
    }

    function detailRow(dl, label, value) {
        dl.appendChild(el("dt", null, label));
        var dd = el("dd");
        if (value instanceof Node) dd.appendChild(value);
        else dd.textContent = value;
        dl.appendChild(dd);
    }

    function buildItem(c) {
        var li = el("li", "kp-item");
        li.dataset.id = c.id;
        li.style.setProperty("--kp-c", c.color);
        var row = el("button", "kp-row");
        row.type = "button";
        row.setAttribute("aria-expanded", "false");
        row.title = c.capital ? "Pokaż " + c.name + " na mapie" : c.name;

        var shield = el("span", "kp-shield");
        shield.setAttribute("aria-hidden", "true");
        row.appendChild(shield);

        var main = el("span", "kp-main");
        var nameLine = el("span", "kp-name-line");
        var nm = el("span", "kp-name", c.name);
        nm.title = c.name;
        nameLine.appendChild(nm);
        if (c.npc) nameLine.appendChild(el("span", "kp-npc", "(NPC)"));
        if (c.wars.length || c.allies.length) {
            var flags = el("span", "kp-flags");
            if (c.wars.length) {
                var w = el("span", "kp-flag kp-war", "⚔");
                w.title = "Wojna: " + c.wars.join(", ");
                flags.appendChild(w);
            }
            if (c.allies.length) {
                var a = el("span", "kp-flag kp-ally", "🤝");
                a.title = "Sojusz: " + c.allies.join(", ");
                flags.appendChild(a);
            }
            nameLine.appendChild(flags);
        }
        main.appendChild(nameLine);

        if (c.ruler) {
            var ruler = el("span", "kp-ruler");
            ruler.appendChild(el("span", "kp-ruler-label", "Władca: "));
            ruler.appendChild(document.createTextNode(c.ruler));
            main.appendChild(ruler);
        }

        var area = el("span", "kp-area",
            fmtNum(c.chunks) + " " + plural(c.chunks, "chunk", "chunki", "chunków") + " · " + fmtNum(c.areaBlocks) + NBSP + "bl²");
        main.appendChild(area);

        var stats = el("span", "kp-stats");
        if (c.members > 0) stats.appendChild(stat("Członkowie", c.members, "Gracze należący do kraju"));
        if (c.population > 0) stats.appendChild(stat("Ludność", c.population, "Mieszkańcy NPC"));
        if (c.soldiers > 0) stats.appendChild(stat("Żołnierze", c.soldiers, "Żołnierze NPC"));
        if (stats.firstChild) main.appendChild(stats);
        row.appendChild(main);
        li.appendChild(row);

        var det = el("div", "kp-details");
        det.hidden = true;
        if (c.description) det.appendChild(el("p", "kp-desc", c.description));
        var dl = el("dl", "kp-dl");
        if (c.capital) {
            detailRow(dl, "Stolica", fmtNum(c.capital.x) + ", " + fmtNum(c.capital.z) +
                (c.capital.world && Object.keys(worldsInfo).length > 1 ? " (" + worldDisplayName(c.capital.world) + ")" : ""));
        }
        detailRow(dl, "Sojusze", relationLinks(c.allies, "kp-rel-ally"));
        detailRow(dl, "Wojny", relationLinks(c.wars, "kp-rel-war"));
        detailRow(dl, "Założony", fmtDate(c.founded));
        detailRow(dl, "Oblężenia", c.sieges > 0 ? fmtNum(c.sieges) : "brak");
        det.appendChild(dl);
        if (c.capital && c.capital.world && worldState.name && c.capital.world !== worldState.name) {
            det.appendChild(el("p", "kp-note", "Kraj leży w świecie: " + worldDisplayName(c.capital.world)));
        }
        if (c.capital) {
            var show = el("button", "kp-show", "Pokaż na mapie");
            show.type = "button";
            show.addEventListener("click", function (e) {
                e.stopPropagation();
                if (isMobile()) setOpen(false);
                onMap(function () { if (worldState.name) flyTo(c); });
            });
            det.appendChild(show);
        }
        li.appendChild(det);

        row.addEventListener("click", function () {
            if (expandedId === c.id) {
                expandedId = null;
                applyExpanded();
                clearHighlight();
                return;
            }
            selectCountry(c.id, false);
        });
        return li;
    }

    function selectCountry(id, scrollIntoView) {
        var c = byId(id);
        if (!c) return;
        if (query && !matches(c, fold(query))) {
            query = "";
            searchEl.value = "";
            renderList();
        }
        expandedId = id;
        applyExpanded();
        if (scrollIntoView) {
            var li = listEl.querySelector('li[data-id="' + cssEscape(id) + '"]');
            if (li && li.scrollIntoView) li.scrollIntoView({ block: "nearest", behavior: reducedMotion() ? "auto" : "smooth" });
        }
        onMap(function () {
            if (worldState.name) flyTo(c);
            else worldListeners.push(function once() {
                worldListeners.splice(worldListeners.indexOf(once), 1);
                flyTo(c);
            });
        });
    }

    function cssEscape(s) {
        return window.CSS && CSS.escape ? CSS.escape(s) : String(s).replace(/["\\]/g, "\\$&");
    }

    function applyExpanded() {
        var items = listEl.children;
        for (var i = 0; i < items.length; i++) {
            var li = items[i];
            var open = li.dataset.id === expandedId;
            li.classList.toggle("kp-expanded", open);
            var row = li.firstChild, det = li.lastChild;
            if (row) row.setAttribute("aria-expanded", open ? "true" : "false");
            if (det && det.classList.contains("kp-details")) det.hidden = !open;
        }
    }

    function renderList() {
        if (!listEl) return;
        var scroll = listEl.scrollTop;
        var q = fold(query.trim());
        var list = sorted().filter(function (c) { return matches(c, q); });
        listEl.textContent = "";
        var frag = document.createDocumentFragment();
        list.forEach(function (c) { frag.appendChild(buildItem(c)); });
        listEl.appendChild(frag);
        if (expandedId && !byId(expandedId)) expandedId = null;
        applyExpanded();
        listEl.scrollTop = scroll;

        countEl.textContent = q && list.length !== countries.length ? list.length + "/" + countries.length : String(countries.length);
        countEl.title = plural(countries.length, "kraj", "kraje", "krajów");

        var msg = "";
        if (!countries.length) msg = dataState === "loading" ? "Wczytywanie krajów…" : "Brak krajów do wyświetlenia";
        else if (!list.length) msg = "Żaden kraj nie pasuje do wyszukiwania";
        emptyEl.textContent = msg;
        emptyEl.hidden = !msg;
        listEl.hidden = !list.length;
    }

    function setStatus(state, extra) {
        if (!statusEl) return;
        var t = "";
        if (state === "error") t = "Nie udało się odświeżyć danych — ponowię za chwilę.";
        else if (state === "noworld") t = "Świat „" + extra + "” nie jest dostępny na mapie.";
        statusEl.textContent = t;
        statusEl.hidden = !t;
        updatedEl.textContent = updatedAt ? "Stan z " + fmtDateTime(updatedAt) : "";
        updatedEl.hidden = !updatedAt;
    }

    // ------------------------------------------------------------------
    // Integracja z mapą
    // ------------------------------------------------------------------
    function addToggleControl() {
        var Toggle = Lf.Control.extend({
            options: { position: "topleft" },
            onAdd: function () {
                var bar = Lf.DomUtil.create("div", "leaflet-bar kp-toggle-bar");
                var a = Lf.DomUtil.create("a", "kp-toggle", bar);
                a.href = "#";
                a.setAttribute("role", "button");
                a.setAttribute("aria-controls", "kraje-panel");
                a.setAttribute("aria-label", "Lista krajów");
                a.textContent = "☰";
                Lf.DomEvent.disableClickPropagation(bar);
                Lf.DomEvent.on(a, "click", function (e) {
                    Lf.DomEvent.preventDefault(e);
                    setOpen(!panelOpen);
                });
                toggleBtn = a;
                return bar;
            }
        });
        var ctl = new Toggle();
        map.addControl(ctl);
        // przycisk na samą górę lewego narożnika (nad zoomem)
        var c = ctl.getContainer();
        if (c && c.parentNode && c.parentNode.firstChild !== c) c.parentNode.insertBefore(c, c.parentNode.firstChild);
        setOpen(panelOpen, false);
    }

    function setupMap(m) {
        if (!m.getPane("krajeHighlight")) m.createPane("krajeHighlight").style.zIndex = 450;
        if (!m.getPane("krajeLabels")) {
            var lp = m.createPane("krajeLabels");
            lp.style.zIndex = 520;
            lp.style.pointerEvents = "none";
        }
        labelGroup = Lf.layerGroup().addTo(m);
        if (panelEnabled) addToggleControl();
        m.on("layeradd", function (e) {
            if (e.layer instanceof Lf.TileLayer) setTimeout(checkWorld, 0);
        });
        m.on("zoomend moveend resize", queueLayout);
        worldListeners.push(function () {
            clearHighlight();
            rebuildLabels();
            renderList();
        });
        checkWorld();
    }

    function loadWorlds() {
        fetch("tiles/settings.json", { cache: "no-store" })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (json) {
                if (!json || !Array.isArray(json.worlds)) return;
                json.worlds.forEach(function (w) { if (w && w.name) worldsInfo[w.name] = w; });
                renderList();
            })
            .catch(function () { /* brak */ });
    }

    function urlParam(name) {
        var m = new RegExp("[?&]" + name + "=([^&]*)").exec(window.location.search);
        return m ? decodeURIComponent(m[1]) : null;
    }

    function start() {
        // ?show_kraje=false — całkowicie wyłącza dodatek; ?show_controls=false (tryb osadzenia
        // squaremap, ukrywa lewe kontrolki) — zostają same napisy, bez panelu.
        if (urlParam("show_kraje") === "false") {
            document.documentElement.classList.add("kp-disabled");
            return;
        }
        panelEnabled = urlParam("show_controls") !== "false";
        if (panelEnabled) buildPanel();
        loadWorlds();
        loadData();
        onMap(setupMap);
        document.addEventListener("visibilitychange", function () {
            if (document.visibilityState === "visible" && dataState !== "loading") loadData();
        });
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
    else start();

    // Mały, celowo wąski interfejs do debugowania i testów.
    window.KrajePanel = {
        reload: loadData,
        select: function (name) { var c = byName(name); if (c) selectCountry(c.id, true); },
        open: function (v) { setOpen(v !== false); },
        showLabels: setShowLabels,
        get map() { return map; },
        get world() { return worldState.name; },
        toLatLng: function (x, z) { return toLatLng(x, z); }
    };
})();
