// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Fałszywy plugin — wydajność serwera (admin.health, PANEL.md „Wydajność serwera”): realistyczne serie TPS / MSPT /
// graczy / NPC z jednym spadkiem TPS sprzed ok. 40 min, aktywne ostrzeżenie NPC Armii i historia epizodów.

const MIN = 60 * 1000;
const RANGES = { "1h": { len: 60 * MIN, step: 10 * 1000 }, "6h": { len: 360 * MIN, step: MIN }, "24h": { len: 1440 * MIN, step: 2 * MIN } };
const PLUGINS = [
  { id: "ludnosc", name: "Ludność" },
  { id: "armia", name: "Armia" },
  { id: "skarbiec", name: "Skarbiec" },
  { id: "wladcy", name: "Władcy" },
  { id: "zycie", name: "Życie" },
  { id: "kolej", name: "Kolej" },
  { id: "szlaki", name: "Szlaki" },
];
const TH = { tps: 18, tpsCritical: 15, mspt: 45, msptCritical: 50, npc: 2000, npcCritical: 3000, entities: 8000, entitiesCritical: 12000, npcPlugin: { armia: 1000 }, sustain: 2 };

/** Deterministyczny „szum” (bez Math.random — zrzuty są powtarzalne). */
const wave = (t, period, amp) => Math.sin((t / period) * Math.PI * 2) * amp;
const r2 = (v) => Math.round(v * 100) / 100;

/** Stan serwera w chwili t (ago = ile ms temu). */
function sampleAt(t, now) {
  const ago = now - t;
  const dip = ago > 34 * MIN && ago < 40 * MIN; // spadek TPS: 40–34 min temu
  const hourOfDay = new Date(t).getHours();
  const players = Math.max(0, Math.round(6 + wave(t, 6 * 3600 * 1000, 4) + (hourOfDay >= 18 && hourOfDay <= 22 ? 5 : 0)));
  const armia = Math.round(1040 - Math.min(140, ago / (10 * MIN)) + wave(t, 17 * MIN, 6));
  const ludnosc = Math.round(612 + wave(t, 47 * MIN, 9) - ago / (40 * MIN));
  const npc = { ludnosc, armia, skarbiec: 6, wladcy: 0, zycie: Math.max(0, Math.round(4 + wave(t, 23 * MIN, 4))), kolej: 2, szlaki: 3 };
  const npcTotal = Object.values(npc).reduce((s, v) => s + v, 0);
  const tps = dip ? 13.6 + wave(t, 3 * MIN, 1.2) : Math.min(20, 19.85 + wave(t, 11 * MIN, 0.15));
  const mspt = dip ? 68 + wave(t, 3 * MIN, 6) : 24 + wave(t, 13 * MIN, 5) + players * 0.6;
  const entities = 3100 + npcTotal + players * 40 + Math.round(wave(t, 29 * MIN, 120));
  const warn = [];
  if (dip) warn.push("TPS", "MSPT");
  if (armia > 1000) warn.push("NPC_ARMIA");
  return { tps: r2(tps), tpsMin: r2(dip ? tps - 1.1 : tps - 0.05), mspt: r2(mspt), msptMax: r2(mspt + (dip ? 14 : 3)), players, chunks: 1450 + players * 120, entities, npcTotal, npc, helpers: Math.round(npc.armia * 0.96 + 18), warn };
}

export function healthMethods(W, { err, okd }) {
  return {
    "admin.health": (u, p) => {
      if (!u.admin) return err("Brak uprawnień");
      const range = p.range === undefined || p.range === "" ? "1h" : String(p.range);
      if (!RANGES[range]) return err("Nieprawidłowy parametr range (dozwolone: 1h, 6h, 24h)");
      const { len, step } = RANGES[range];
      const now = Date.now();
      const to = now;
      const from = now - len;
      const points = [];
      for (let t = Math.ceil(from / step) * step; t <= to; t += step) points.push({ t, ...sampleAt(t, now) });
      const c = sampleAt(now, now);
      const current = {
        t: now - 4000,
        tps: c.tps,
        tps5: 19.7,
        tps15: 18.9,
        mspt: c.mspt,
        players: c.players,
        maxPlayers: 40,
        chunks: c.chunks,
        entities: c.entities,
        npcScan: true,
        npcTotal: c.npcTotal,
        npc: c.npc,
        kinds: { citizens: c.npc.ludnosc - 9, builders: 9, soldiers: c.npc.armia - 7, rulers: 7, merchants: 6, wladcy: 0, onDuty: c.npc.zycie, railBuilders: 1, trainDrivers: 1, freightDrivers: 2, freightAnimals: 1, helpers: c.helpers },
        helpers: c.helpers,
        worlds: [
          { name: "world", environment: "NORMAL", chunks: c.chunks - 210, entities: c.entities - 260, players: c.players - 1, npc: c.npcTotal - 3 },
          { name: "world_nether", environment: "NETHER", chunks: 160, entities: 210, players: 1, npc: 3 },
          { name: "world_the_end", environment: "THE_END", chunks: 50, entities: 50, players: 0, npc: 0 },
        ],
        countries: [
          { id: "npc-mazowsze", name: "Księstwo Mazowsze", color: "#5E7C16", npc: true, exists: true, total: 702, citizens: 241, soldiers: 460, rulers: 1 },
          { id: "c-polanie", name: "Królestwo Polan", color: "#B02E26", npc: false, exists: true, total: 515, citizens: 214, soldiers: 300, rulers: 1 },
          { id: "c-wislanie", name: "Wiślanie", color: "#3C44AA", npc: false, exists: true, total: 290, citizens: 148, soldiers: 140, rulers: 2 },
          { id: "00000000-0000-0000-0000-0000000000ff", name: null, color: "#808080", npc: false, exists: false, total: 4, citizens: 0, soldiers: 1, rulers: 3 },
        ],
        countriesTotal: 4,
        scanMs: 3.42,
        scannedAt: now - 4000,
      };
      const warnings = c.npc.armia > 1000 ? [{ code: "NPC_ARMIA", level: "WARN", text: `Dużo NPC (Armia): ${c.npc.armia} (próg 1000)`, value: c.npc.armia, threshold: 1000, since: now - 9 * MIN }] : [];
      const episodes = [
        { code: "NPC_ARMIA", level: "WARN", text: "Dużo NPC (Armia): 1046 (próg 1000)", start: now - 9 * MIN, end: null, worst: 1046 },
        { code: "MSPT", level: "CRITICAL", text: "Długi tick: 79,6 ms (próg 50,0 ms)", start: now - 40 * MIN, end: now - 34 * MIN, worst: 79.6 },
        { code: "TPS", level: "CRITICAL", text: "Niski TPS: 12,4 (próg 15,0)", start: now - 40 * MIN, end: now - 34 * MIN, worst: 12.4 },
        { code: "TPS", level: "WARN", text: "Niski TPS: 17,2 (próg 18,0)", start: now - 7 * 60 * MIN, end: now - 7 * 60 * MIN + 3 * MIN, worst: 17.2 },
      ];
      return okd({ enabled: true, intervalMs: 10000, npcScan: true, range, resolutionMs: step, from, to, points, current, plugins: PLUGINS, thresholds: TH, warnings, episodes });
    },
  };
}
