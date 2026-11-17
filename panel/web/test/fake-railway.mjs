// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
export function railwayMethods(W, { err, okd }) {
  W.railwayManager = W.U.maciej.uuid;
  W.railway = {
    company: "Królewska Kolej", balance: 12345,
    works: [{ id: "baza", status: "DONE", reason: "", done: 100, total: 100, cost: 100 }, { id: "tor", status: "BLOCKED", reason: "Kontener gracza <script>bad()</script>", done: 25, total: 100, cost: 500 }],
    stations: [{ id: "baza", name: "Baza przewoźnika", x: 4842, y: 110, z: 1198 }, { id: "spawn", name: "Spawn Centralny", x: 272, y: 70, z: -103 }],
    lines: [{ id: "krolewska", stops: ["baza", "spawn"], active: false, fare: 10, timetable: "co 30 min" }],
    trains: [{ id: "parowoz", node: "baza", line: "", status: "Postój", running: false, seats: 16 }],
    crews: [{ id: "m1", name: "Jan", train: "", paid: true }], permits: [{ id: "kraj", name: "Królestwo" }],
    requests: [],
    network: { nodes: [
      { id: "baza", name: "Baza przewoźnika", type: "DEPOT", world: "world", x: 4842, y: 110, z: 1198 },
      { id: "spawn", name: "Spawn Centralny", type: "STATION", world: "world", x: 272, y: 70, z: -103 },
      { id: "nether", name: "Nether", type: "STATION", world: "world_nether", x: 0, y: 70, z: 0 },
    ], segments: [{ id: "tor", from: "baza", to: "spawn", world: "world", status: "BUILDING", points: [[4842, 110, 1198], [2000, 90, 1198], [272, 70, -103]], sampled: false }] },
  };
  W.railway.stations.forEach(n => n.world = "world");
  const methods = {};
  const treasury={cap:100000000,total:100000000,reserve:70000000,railway:0,cash:10000,payments:1000000,merchant:2000000,households:3000000};
  methods["world.get"]=(u)=>u.uuid===W.railwayManager?okd({...treasury}):err("Tylko WorldSteward");
  for(const method of ["world.allocate","world.return"])methods[method]=(u,p)=>{
    if(u.uuid!==W.railwayManager)return err("Tylko WorldSteward");
    const key={"world:merchant":"merchant","world:households":"households","world:payments":"payments","kolej:przewoznik":"railway"}[p.account];
    const amount=Number(p.amount);if(!key || !Number.isSafeInteger(amount) || amount<=0)return err("Nieprawidłowa kwota");
    const from=method==="world.allocate"?"reserve":key,to=method==="world.allocate"?key:"reserve";
    if(treasury[from]<amount)return err("Brak środków");treasury[from]-=amount;treasury[to]+=amount;return okd({ok:true});
  };
  for (const method of ["railway.get", "railway.request.create", "railway.request.quote", "railway.request.confirm", "railway.request.cancel", "railway.work.resume", "railway.work.cancel", "railway.line.create", "railway.line.configure", "railway.train.assign", "railway.crew.assign"]) {
    methods[method] = (u, p) => {
      if (u.uuid !== W.railwayManager) return err("Tylko osobisty zarządca kolei");
      if (method === "railway.get") return okd(structuredClone(W.railway));
      if (method.startsWith("railway.request.")) {
        let r = W.railway.requests.find(r => r.id === p.id);
        if (method === "railway.request.create") {
          if (r || !/^[a-z0-9_-]{1,24}$/.test(p.id) || !["station", "rail", "road"].includes(p.kind)) return err("Nieprawidłowy wniosek");
          r = { id: p.id, kind: p.kind, world: "world", position: [280, 70, -103], from: p.from || "", to: p.to || "", state: "REQUESTED", reason: "", job: "", token: "", price: 0, blocks: 0, expires: 0 };
          W.railway.requests.push(r);
        } else {
          if (!r) return err("Brak wniosku");
          if (["SUBMITTED", "CANCELLED"].includes(r.state)) return err("Wniosek jest zamknięty");
          if (method === "railway.request.quote") Object.assign(r, { state: "QUOTED", price: 1234, blocks: 150, expires: Date.now() + 180000, token: "server-quote-token" });
          if (method === "railway.request.cancel") Object.assign(r, { state: "CANCELLED", token: "" });
          if (method === "railway.request.confirm") {
            if (r.state !== "QUOTED" || p.token !== r.token || r.expires < Date.now()) return err("Wycena wygasła");
            Object.assign(r, { state: "SUBMITTED", token: "", job: r.id });
            W.railway.works.push({ id: r.id, status: "QUEUED", reason: "", done: 0, total: r.blocks, cost: r.price });
          }
        }
      }
      if (method === "railway.work.resume") W.railway.works.find(j => j.id === p.id).status = "QUEUED";
      if (method === "railway.work.cancel") W.railway.works.find(j => j.id === p.id).status = "CANCELLED";
      if (method === "railway.line.configure") Object.assign(W.railway.lines.find(l => l.id === p.id), { fare: Number(p.fare), active: p.active === "true", ...(p.interval ? { timetable: `co ${p.interval} min` } : {}) });
      if (method === "railway.line.create") W.railway.lines.push({ id: p.id, stops: p.stops.split(","), active: false, fare: 10, timetable: "co 30 min" });
      if (method === "railway.train.assign") W.railway.trains.find(t => t.id === p.train).line = p.line;
      if (method === "railway.crew.assign") W.railway.crews.find(c => c.id === p.id).train = p.train;
      return okd({ ok: true });
    };
  }
  return methods;
}
