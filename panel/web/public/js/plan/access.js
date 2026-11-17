// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// UI capabilities are hints; every operation is authorized again by the server.
export function planAccess(country, rank, roles = {}) {
  const access = country?.raw?.access || country?.access || {};
  const admin = !!roles.admin;
  const npcManager = !!country?.npc && !!roles.canNpcCountry?.(country.id);
  const adviser = rank === "ADVISOR" || rank === "RULER";
  const read = access.planRead ?? (admin || npcManager || adviser);
  return {
    read: !!read,
    write: !!(access.planWrite ?? (admin || npcManager || rank === "RULER")),
    level: !!(access.planLevel ?? (admin || npcManager || adviser)),
    territory: !!country?.npc && !!(access.planTerritory ?? (admin || npcManager)),
    free: admin,
  };
}

export function planMethods(playerCountry = false) {
  return playerCountry ? {
    get: "country.plan.get", buildings: "country.plan.buildings", map: "country.plan.mapRender",
    set: "country.plan.set", clear: "country.plan.clear", preview: "country.plan.preview",
  } : {
    get: "admin.npc.getPlan", buildings: "admin.npc.buildings", map: "admin.npc.mapRender",
    set: "admin.npc.setPlan", clear: "admin.npc.clearPlan", preview: "admin.npc.planPreview",
  };
}

/** Execution feedback is scoped to orders of this saved plan; global planner failures are deliberately ignored. */
export function planExecution(preview) {
  const orders = Array.isArray(preview?.orders) ? preview.orders.filter(o => o && typeof o === "object").map(o => ({
    ...o,
    reason: typeof o.reason === "string" ? o.reason.trim() : "",
    retryAt: Number.isSafeInteger(o.retryAt) && o.retryAt > 0 ? o.retryAt : null,
  })) : [];
  const blocked = orders.filter(o => ["odrzucony", "zablokowany", "wstrzymany"].includes(o.status) || o.status === "czeka" && o.reason);
  return { orders, blocked, ordered: orders.filter(o => o.status === "zlecony").length,
    waiting: orders.filter(o => o.status !== "zlecony" && !blocked.includes(o)).length };
}

/** Inclusive rectangle in block coordinates; territory chunk selection is a different tool. */
export function levelRectangle(a, b) {
  const values = [a?.x, a?.z, b?.x, b?.z];
  if (!values.every(Number.isSafeInteger)) throw new Error("Nieprawidłowe współrzędne zaznaczenia.");
  const r = { minX: Math.min(a.x,b.x), minZ: Math.min(a.z,b.z), maxX: Math.max(a.x,b.x), maxZ: Math.max(a.z,b.z) };
  if (values.some(v => Math.abs(v) > 29_999_000)) throw new Error("Zaznaczenie leży poza granicą świata.");
  if ((r.maxX-r.minX+1)*(r.maxZ-r.minZ+1) > 4096) throw new Error("Zaznacz najwyżej 4096 bloków terenu naraz.");
  return r;
}

export function levelSelectionError(rect, land, protectedAreas = []) {
  if (!rect) return "Przeciągnij po mapie, aby zaznaczyć teren do wyrównania.";
  for (let cx=Math.floor(rect.minX/16); cx<=Math.floor(rect.maxX/16); cx++) {
    for (let cz=Math.floor(rect.minZ/16); cz<=Math.floor(rect.maxZ/16); cz++) {
      if (!land?.has(cx+","+cz)) return "Całe zaznaczenie musi leżeć na ziemi tego państwa.";
      if (protectedAreas.some(a=>cx>=a.minCX && cx<=a.maxCX && cz>=a.minCZ && cz<=a.maxCZ)) return "Zaznaczenie obejmuje obszar chroniony.";
    }
  }
  return null;
}
