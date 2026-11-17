// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
// Panel — chorągiew kraju (#/kraj/<id>/flaga): obecna flaga ze statusem, obrazek czekający na zatwierdzenie, ostatnie
// odrzucenie, wgrywanie (PNG/JPEG, kontrola i pomniejszenie w przeglądarce), przywracanie domyślnej i wersji z historii.
// Uprawnienia (`access`) rozstrzyga Kraje przy każdym wywołaniu; tutaj sterują tylko widocznością przycisków.
// Odczyty idą przez api.rpc (bez kopii w sessionStorage i bez trybu offline — obrazki są duże i zmienne).
import { h, add, icon, modal, confirmDanger, skeleton } from "../dom.js";
import { card, btn, act, table, countryName, empty } from "../components.js";
import * as api from "../api.js";
import { fmtDate, fmtAgo, safeColor } from "../util.js";
import {
  normLimits, checkFile, sniffFormat, uploadPlan, coverCrop, safeImage, currentStatus, historyStatus, packSentence, originalText,
  formatBytes, MAX_DATA_CHARS, RESIZE_MAX, bannerPreviewState, deltaEText,
} from "../flag-model.js";

const clock = (ts) => new Intl.DateTimeFormat("pl-PL", { hour: "2-digit", minute: "2-digit" }).format(new Date(ts));

/** Pikselowy podgląd flagi; brak/niebezpieczny obrazek → pole w kolorze kraju. */
export function flagImage(src, { color, size = "lg", alt = "Chorągiew" } = {}) {
  const ok = safeImage(src);
  if (ok) return h("img.flag-img.flag-" + size, { src: ok, alt, width: "64", height: "40", decoding: "async" });
  const box = h("div.flag-img.flag-" + size + ".flag-blank", { role: "img", "aria-label": alt + " (brak podglądu)" });
  box.style.setProperty("--c", safeColor(color));
  return box;
}

export function statusBadge(st) {
  return h("span.flag-status.flag-status-" + st.cls, st.key === "WAITING_PACK" ? icon("clock") : st.key === "LIVE" ? icon("check") : null, h("span", st.label));
}

function who(by, at) {
  return [by ? h("strong", by) : "—", at ? [" · ", h("span", { title: fmtDate(at) }, fmtAgo(at))] : null];
}

function base64(buf) {
  const bytes = new Uint8Array(buf);
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Nie udało się odczytać obrazka — plik może być uszkodzony."));
    img.src = src;
  });
}

/** Przybliżony podgląd po stronie serwera: wycinek ze środka w docelowym rozmiarze (64×40). */
function serverPreview(img, limits) {
  const c = h("canvas.flag-img.flag-lg", { width: String(limits.width), height: String(limits.height), "aria-label": "Przybliżony podgląd w grze" });
  const g = c.getContext("2d");
  const r = coverCrop(img.naturalWidth, img.naturalHeight, limits.width, limits.height);
  g.imageSmoothingQuality = "high";
  g.drawImage(img, r.sx, r.sy, r.sw, r.sh, 0, 0, limits.width, limits.height);
  return c;
}

/** Wczytuje plik i przygotowuje dane do wysłania: {data, filename, img, plan, format, bytes}. */
async function prepare(file, limits) {
  const first = checkFile(file, limits);
  if (!first.ok) throw new Error(first.error);
  const buf = await file.arrayBuffer();
  const real = sniffFormat(new Uint8Array(buf, 0, Math.min(16, buf.byteLength)));
  if (!real || !limits.formats.includes(real)) throw new Error(`Plik nie jest prawdziwym obrazkiem ${limits.formats.join(" ani ")}.`);
  const original = `data:image/${real === "JPEG" ? "jpeg" : "png"};base64,` + base64(buf);
  const img = await loadImage(original);
  const plan = uploadPlan({ format: real, bytes: file.size, width: img.naturalWidth, height: img.naturalHeight }, limits);
  if (!plan.ok) throw new Error(plan.error);
  let data = original;
  let shown = img;
  if (plan.resize) {
    const c = document.createElement("canvas");
    c.width = plan.width;
    c.height = plan.height;
    const g = c.getContext("2d");
    g.imageSmoothingQuality = "high";
    g.drawImage(img, 0, 0, plan.width, plan.height);
    data = c.toDataURL("image/png");
    shown = await loadImage(data);
  }
  if (data.length > MAX_DATA_CHARS) throw new Error(`Obrazek po przygotowaniu jest nadal za duży (${formatBytes(Math.round((data.length * 3) / 4))}). Wybierz prostszy obrazek.`);
  const name = String(file.name || "flaga").replace(/[^\p{L}\p{N}._ -]/gu, "_").slice(0, 120);
  return { data, filename: name, img: shown, plan, format: real, bytes: file.size, width: img.naturalWidth, height: img.naturalHeight };
}

function uploadCard(d, ctx) {
  const limits = normLimits(d.limits);
  const main = document.getElementById("main");
  const input = h("input.input.flag-file#flag-file", { type: "file", accept: "image/png,image/jpeg,.png,.jpg,.jpeg", "aria-describedby": "flag-file-help" });
  const out = h("div.flag-upload-out", { "aria-live": "polite" });
  let ready = null;
  const send = btn("Wyślij do zatwierdzenia", { kind: "primary", icon: "upload" }, (_, b) => {
    if (!ready) return;
    act(b, () => api.mutate("country.flag.upload", { id: d.id, data: ready.data, filename: ready.filename }), (r) => (r && r.message) || "Obrazek wysłany — czeka na zatwierdzenie moderatora", ctx.reload);
  });
  send.disabled = true;
  const reset = () => {
    ready = null;
    send.disabled = true;
    out.replaceChildren();
    if (main) delete main.dataset.keep;
  };
  input.addEventListener("change", async () => {
    reset();
    const file = input.files && input.files[0];
    if (!file) return;
    if (main) main.dataset.keep = "1"; // automatyczne odświeżanie nie kasuje wybranego pliku
    try {
      ready = await prepare(file, limits);
    } catch (e) {
      add(out, h("div.alert.alert-err", icon("warn"), h("span", e.message || String(e))));
      return;
    }
    add(
      out,
      h(
        "div.flag-compare",
        h("figure.flag-fig", h("div.flag-frame.flag-frame-src", ready.img.cloneNode()), h("figcaption", `Wysyłany obrazek · ${ready.img.naturalWidth}×${ready.img.naturalHeight} px`)),
        h("figure.flag-fig", h("div.flag-frame", serverPreview(ready.img, limits)), h("figcaption", `Przybliżenie w grze · ${limits.width}×${limits.height} px`)),
      ),
      ready.plan.notes.map((n) => h("p.muted.small", n)),
      h("p.muted.small", `Serwer przytnie obrazek do ${limits.width}×${limits.height} px (środek kadru) i ograniczy paletę do ${limits.maxColors} kolorów — szczegóły mogą zniknąć.`),
    );
    send.disabled = false;
  });
  return card(
    "Wgraj nową chorągiew",
    h(
      "div.alert.alert-warn.flag-moderation-note",
      icon("eye"),
      h("span", "Każdy obrazek sprawdza moderator. Dopiero po zatwierdzeniu trafia do gry — przy najbliższej przebudowie paczki zasobów. Nie wgrywaj treści obraźliwych ani cudzych herbów."),
    ),
    h(
      "label.field",
      h("span.field-label", "Plik z obrazkiem"),
      input,
      h("span.field-help#flag-file-help", `${limits.formats.join(" lub ")}, do ${formatBytes(limits.maxBytes)} (większy pomniejszę do ${RESIZE_MAX.width}×${RESIZE_MAX.height} px), najmniej ${limits.minSourceWidth}×${limits.minSourceHeight} px. Najlepiej proporcje ${limits.width}:${limits.height}.`),
    ),
    out,
    h("div.form-actions", btn("Wyczyść", { mut: false }, () => ((input.value = ""), reset())), send),
  );
}

/**
 * Wzór A: zwykły sztandar (tło + do 6 wzorów z krosna) dobrany z obecnej flagi — obok zatwierdzonego obrazka.
 * Starszy Kraje nie przysyła pól `banner*` → czytelny komunikat zamiast podglądu.
 */
export function bannerPreview(cur) {
  const b = bannerPreviewState(cur);
  const head = h("div.flag-banner-title", icon("shield"), h("strong", "Sztandar w grze"));
  if (b.key === "UNSUPPORTED") {
    return h(
      "div.flag-banner.flag-banner-off",
      head,
      h("p.muted", "Podgląd wzoru sztandaru pojawi się po aktualizacji serwera."),
    );
  }
  if (b.key === "PENDING") {
    return h("div.flag-banner.flag-banner-off", head, h("p.muted", "Wzór sztandaru jest właśnie dobierany z flagi — odśwież za chwilę."));
  }
  const de = deltaEText(b.deltaE);
  return h(
    "figure.flag-banner",
    h("img.flag-banner-img", { src: b.src, alt: "Sztandar kraju w grze: przybliżony wzór z barwników", width: "80", height: "160", decoding: "async" }),
    h(
      "figcaption",
      head,
      b.text ? h("p", b.text) : null,
      de ? h("p.muted", de) : null,
      h("p.muted", "Zwykły sztandar: tło i do 6 wzorów z krosna. W grze: ", h("code", "/kraj flaga sztandar")),
    ),
  );
}

function currentCard(d, ctx) {
  const cur = d.current || {};
  const st = currentStatus(cur);
  const canReset = d.access && d.access.manage && cur.source === "UPLOAD";
  return card(
    "Obecna chorągiew",
    h(
      "div.flag-hero",
      flagImage(cur.png, { color: d.color, alt: "Chorągiew kraju " + (d.name || "") }),
      bannerPreview(cur),
      h("div.flag-hero-side", statusBadge(st)),
    ),
    st.key === "WAITING_PACK" ? h("div.alert.alert-warn", icon("clock"), h("span", packSentence(d.pack && d.pack.nextRebuildAt, Date.now(), clock))) : null,
    st.key === "DEFAULT" ? h("p.muted", "Domyślna chorągiew powstaje z koloru kraju i działa w grze od razu.") : null,
    cur.source === "UPLOAD"
      ? h(
          "dl.facts",
          h("dt", "Zatwierdził"),
          h("dd", who(cur.approvedBy, cur.approvedAt)),
          cur.liveSince ? [h("dt", "W grze od"), h("dd", fmtDate(cur.liveSince))] : null,
        )
      : null,
    canReset
      ? h(
          "div.form-actions",
          btn("Przywróć domyślną", { kind: "danger", icon: "undo" }, async (_, b) => {
            const ok = await confirmDanger({ title: "Przywrócić domyślną chorągiew?", text: `Chorągiew kraju ${d.name} wróci do wersji z koloru kraju. Obecny obrazek zostanie w historii i będzie można go przywrócić.`, confirmLabel: "Przywróć domyślną" });
            if (ok) act(b, () => api.mutate("country.flag.reset", { id: d.id }), (r) => (r && r.message) || "Przywrócono domyślną chorągiew", ctx.reload);
          }),
        )
      : null,
  );
}

function pendingCard(d, ctx) {
  const p = d.pending;
  return card(
    "Oczekuje na zatwierdzenie",
    h("div.flag-hero", flagImage(p.png, { color: d.color, alt: "Chorągiew czekająca na zatwierdzenie" }), h("div.flag-hero-side", h("span.flag-status.flag-status-warn", icon("clock"), h("span", "Oczekuje na moderatora")))),
    h(
      "dl.facts",
      h("dt", "Wgrał"),
      h("dd", who(p.uploadedBy, p.uploadedAt)),
      h("dt", "Oryginał"),
      h("dd", originalText(p.original)),
      p.colors != null ? [h("dt", "Kolory po przetworzeniu"), h("dd", String(p.colors))] : null,
    ),
    h("p.muted.small", "Podgląd pokazuje obrazek już po przetworzeniu przez serwer — tak będzie wyglądał w grze."),
    d.access && d.access.upload
      ? h(
          "div.form-actions",
          btn("Wycofaj", { icon: "trash" }, async (_, b) => {
            const ok = await modal({
              title: "Wycofać obrazek z kolejki?",
              body: h("p", "Moderator nie zobaczy już tego obrazka. Obecna chorągiew się nie zmieni."),
              actions: [{ label: "Anuluj", value: null }, { label: "Wycofaj", kind: "danger", value: true }],
            });
            if (ok) act(b, () => api.mutate("country.flag.withdraw", { id: d.id }), (r) => (r && r.message) || "Obrazek wycofany", ctx.reload);
          }),
        )
      : null,
  );
}

function rejectionCard(d) {
  const r = d.lastRejection;
  return card(
    "Ostatnie odrzucenie",
    h("div.alert.alert-err.flag-reason", icon("warn"), h("div", h("strong", "Powód: "), h("span", r.reason || "—"))),
    h("p.muted.small", "Odrzucił: ", who(r.by, r.at)),
  );
}

function historyCard(d, ctx) {
  const rows = Array.isArray(d.history) ? d.history : [];
  const canRestore = !!(d.access && d.access.manage);
  return card(
    "Historia chorągwi",
    table({
      columns: [
        { key: "png", label: "Obrazek", render: (r) => (r.png ? flagImage(r.png, { color: d.color, size: "sm", alt: "Chorągiew z historii" }) : h("span.muted", "—")), sort: false },
        { key: "status", label: "Zdarzenie", render: (r) => { const s = historyStatus(r.status); return h("span.flag-status.flag-status-" + s.cls, s.label); } },
        { key: "at", label: "Kiedy", render: (r) => (r.at ? h("span", { title: fmtDate(r.at) }, fmtDate(r.at)) : "—"), sort: (r) => Number(r.at) || 0 },
        { key: "by", label: "Kto", render: (r) => r.by || "—" },
        { key: "note", label: "Uwagi", render: (r) => r.note || "" },
        {
          key: "act",
          label: "",
          cls: "col-actions",
          sort: false,
          render: (r) =>
            canRestore && r.restorable && r.imageId
              ? btn("Przywróć", { small: true, icon: "undo" }, async (_, b) => {
                  const ok = await modal({
                    title: "Przywrócić tę chorągiew?",
                    body: h("div.flag-restore", flagImage(r.png, { color: d.color, alt: "Przywracana chorągiew" }), h("p", "Ten obrazek był już zatwierdzony. Wróci do gry przy najbliższej przebudowie paczki zasobów.")),
                    actions: [{ label: "Anuluj", value: null }, { label: "Przywróć", kind: "primary", value: true }],
                  });
                  if (ok) act(b, () => api.mutate("country.flag.restore", { id: d.id, imageId: r.imageId }), (x) => (x && x.message) || "Chorągiew przywrócona", ctx.reload);
                })
              : null,
        },
      ],
      rows,
      sort: { key: "at", dir: "desc" },
      emptyText: "Brak wcześniejszych chorągwi.",
      cls: "flag-history",
    }),
  );
}

export const flagView = {
  title: (r) => (r && r.data && r.data.name ? "Chorągiew · " + r.data.name : "Chorągiew kraju"),
  skeleton: () => h("div.grid-cards", h("div.card", skeleton(6)), h("div.card", skeleton(6))),
  async load(ctx) {
    try {
      return { data: await api.rpc("country.flag.get", { id: ctx.params[0] }) };
    } catch (e) {
      if (e.app) return { error: e.message || String(e) };
      throw e;
    }
  },
  draw(main, r, ctx) {
    const id = ctx.params[0];
    const back = h("a.link-more", { href: "#/kraj/" + encodeURIComponent(id) }, "← Strona kraju");
    if (r.error) {
      add(main, h("div.page-head", h("div", h("h1", "Chorągiew kraju"), back)), card("Nie można wyświetlić chorągwi", h("p", r.error)));
      return;
    }
    const d = r.data || {};
    const access = d.access || {};
    const full = !!(access.upload || access.moderate);
    add(
      main,
      h(
        "div.page-head",
        h("div", h("h1", "Chorągiew kraju"), h("p.flag-country", countryName({ id: d.id, name: d.name, color: d.color, npc: d.npc })), back),
        h("div.actions", access.moderate ? h("a.btn", { href: "#/flagi" }, icon("eye"), h("span", "Kolejka moderacji")) : null, btn("Odśwież", { mut: false, icon: "rotate" }, () => ctx.forceReload())),
      ),
    );
    const grid = h("div.grid-cards.flag-grid", currentCard(d, ctx), full && d.pending ? pendingCard(d, ctx) : null, full && d.lastRejection ? rejectionCard(d) : null);
    add(main, grid);
    if (!full) {
      add(main, h("p.muted.flag-note", "Chorągiew zmieniają Władca i Doradcy kraju."));
      return;
    }
    if (access.upload) add(main, uploadCard(d, ctx));
    else add(main, card("Wgrywanie", empty("Chorągiew zmieniają Władca i Doradcy kraju.", "lock")));
    add(main, historyCard(d, ctx));
  },
};
