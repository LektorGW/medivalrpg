// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.panel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import pl.medivalrpg.firmy.bridge.ShopBridge;
import pl.medivalrpg.firmy.core.AltWatch;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmsData;

/**
 * RPC {@code firmy.*} of the web panel (page {@code #/firmy}), called by the Panel plugin through
 * {@code FirmyPlugin.firmyPanel(UUID, String, Map)}. Firmy decides access on every call: public ranking and cards
 * (no balances), full data for members, everything for the owner (WorldSteward). Main thread.
 */
public final class FirmyPanel {

    /** Personal manager of the world (WorldSteward), same constant as Skarbiec's WorldMoney.MANAGER. */
    public static final UUID OWNER = UUID.fromString("ff923cf7-f43c-37b8-85a3-77cbac357c9c");

    private final FirmService service;
    private final ShopBridge shops;
    private final AltWatch alts;
    /** Methods of later stages: name → handler(actor, args). */
    private final Map<String, BiFunction<UUID, Map<String, String>, Object>> extra = new LinkedHashMap<>();

    public FirmyPanel(final FirmService service, final ShopBridge shops, final AltWatch alts) {
        this.service = service;
        this.shops = shops;
        this.alts = alts;
    }

    public void register(final String method, final BiFunction<UUID, Map<String, String>, Object> handler) {
        this.extra.put(method, handler);
    }

    public static boolean owner(final UUID actor) {
        return OWNER.equals(actor);
    }

    public Object invoke(final UUID actor, final String method, final Map<String, String> args) {
        final Map<String, String> a = args == null ? Map.of() : args;
        return switch (method) {
            case "firmy.list" -> this.list(actor, a.getOrDefault("sort", "obrot"));
            case "firmy.get" -> this.get(actor, a.get("id"));
            case "firmy.admin" -> this.admin(actor);
            case "firmy.admin.freeze" -> this.freeze(actor, a);
            default -> {
                final BiFunction<UUID, Map<String, String>, Object> h = this.extra.get(method);
                if (h == null) {
                    throw new IllegalArgumentException("Nieznana metoda firm: " + method);
                }
                yield h.apply(actor, a);
            }
        };
    }

    public static Map<String, Object> outcome(final Outcome o) {
        if (!o.ok()) {
            throw new IllegalArgumentException(o.message());
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("message", o.message());
        if (o.id() != null) {
            m.put("id", o.id());
        }
        return m;
    }

    // ------------------------------------------------------------------ public

    private Map<String, Object> summary(final Firm f, final boolean money) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id);
        m.put("name", f.name);
        m.put("type", f.type.key());
        m.put("typeName", f.type.displayName());
        m.put("seat", f.seatCountry);
        m.put("seatName", f.seatCountryName);
        m.put("color", f.color);
        m.put("banner", Map.of("base", f.bannerBase == null ? "white" : f.bannerBase, "patterns", f.bannerPatterns));
        m.put("members", f.members.size());
        m.put("master", f.master().map(x -> x.name).orElse(null));
        m.put("income30", f.income(this.service.ctx.windowStart(30)));
        m.put("contracts", f.contractsDone);
        m.put("state", f.active() ? (f.dormantSince != 0 ? "DORMANT" : this.service.treasury.insolvent(f) ? "INSOLVENT" : "ACTIVE") : "LIQUIDATED");
        m.put("createdAt", f.createdAt);
        if (money) {
            final long bal = this.service.treasury.balance(f);
            m.put("balance", bal);
            m.put("balanceText", this.service.ctx.fmt(bal));
        }
        return m;
    }

    private Map<String, Object> list(final UUID actor, final String sort) {
        final List<Map<String, Object>> firms = new ArrayList<>();
        int rank = 1;
        for (final Firm f : this.service.ranking(sort)) {
            final Map<String, Object> m = this.summary(f, owner(actor));
            m.put("rank", rank++);
            m.put("mine", actor != null && f.members.containsKey(actor.toString()));
            firms.add(m);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("sort", sort);
        out.put("firms", firms);
        out.put("owner", owner(actor));
        out.put("carrier", Map.of("name", "Królewski przewoźnik", "note", "Kolej Korony — osobna firma WorldSteward, poza rankingiem"));
        return out;
    }

    private Map<String, Object> get(final UUID actor, final String id) {
        final Optional<Firm> of = this.service.ctx.firm(id);
        if (of.isEmpty()) {
            throw new IllegalArgumentException("Nie ma takiej firmy.");
        }
        final Firm f = of.get();
        final boolean member = actor != null && f.members.containsKey(actor.toString());
        final boolean full = member || owner(actor);
        final Map<String, Object> m = this.summary(f, full);
        m.put("access", Map.of("member", member, "owner", owner(actor)));
        final List<Map<String, Object>> people = new ArrayList<>();
        for (final Firm.Member x : f.members.values()) {
            final Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", x.name);
            p.put("role", x.role.key());
            p.put("roleName", x.role.displayName());
            if (full) {
                p.put("uuid", x.uuid);
                p.put("share", x.shareBp / 100.0);
                p.put("salary", x.salary);
                p.put("arrears", f.arrears.getOrDefault(x.uuid, 0L));
            }
            people.add(p);
        }
        m.put("people", people);
        if (full) {
            m.put("taxPercent", this.service.treasury.taxTarget(f).map(t -> t.getValue() / 100.0).orElse(null));
            m.put("income7", f.income(this.service.ctx.windowStart(7)));
            m.put("tax30", f.tax(this.service.ctx.windowStart(30)));
            m.put("incomeTotal", f.incomeTotal);
            m.put("taxTotal", f.taxTotal);
            m.put("incomeByDay", f.incomeByDay);
            m.put("arrears", f.totalArrears());
            m.put("unpaidDays", f.unpaidDays);
            m.put("frozen", f.frozen);
            m.put("frozenReason", f.frozenReason);
            m.put("payouts", f.payouts);
            final List<Firm.Entry> journal = new ArrayList<>(f.journal);
            java.util.Collections.reverse(journal);
            m.put("journal", journal.subList(0, Math.min(200, journal.size())));
            m.put("shops", this.shops.shopsOf(f));
            m.put("warehouse", f.warehouse);
            final List<Firm.ChestLog> logs = new ArrayList<>(this.service.warehouse.recent(f, 100));
            java.util.Collections.reverse(logs);
            m.put("warehouseLog", logs);
        }
        if (owner(actor)) {
            m.put("sharedAddresses", this.alts.sharedAddresses(f));
        }
        return m;
    }

    // ------------------------------------------------------------------ owner

    private void requireOwner(final UUID actor) {
        if (!owner(actor)) {
            throw new IllegalArgumentException("Brak dostępu.");
        }
    }

    private Map<String, Object> admin(final UUID actor) {
        this.requireOwner(actor);
        final List<Map<String, Object>> firms = new ArrayList<>();
        long total = 0;
        for (final Firm f : this.service.ctx.data.firms.values()) {
            final Map<String, Object> m = this.summary(f, true);
            m.put("tax30", f.tax(this.service.ctx.windowStart(30)));
            m.put("arrears", f.totalArrears());
            m.put("frozen", f.frozen);
            m.put("frozenReason", f.frozenReason);
            m.put("sharedAddresses", this.alts.sharedAddresses(f));
            m.put("liquidationReason", f.liquidationReason);
            firms.add(m);
            if (f.active()) {
                total += this.service.treasury.balance(f);
            }
        }
        final List<Map<String, Object>> apps = new ArrayList<>();
        for (final FirmsData.Application a : this.service.ctx.data.applications.values()) {
            apps.add(Map.of("id", a.id, "name", a.name, "type", a.type.displayName(), "country", a.countryName, "founder", a.founderName,
                "held", this.service.bankBalance(a.account()), "expiresAt", a.expiresAt, "state", a.state));
        }
        final List<Map<String, Object>> intents = new ArrayList<>();
        for (final FirmsData.Intent i : this.service.ctx.intents) {
            intents.add(Map.of("id", i.id, "firm", String.valueOf(i.firm), "kind", i.kind, "description", i.description,
                "createdAt", i.createdAt, "legs", i.legs));
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("firms", firms);
        out.put("applications", apps);
        out.put("uncertain", intents);
        out.put("totalBalance", total);
        out.put("totalBalanceText", this.service.ctx.fmt(total));
        return out;
    }

    private Map<String, Object> freeze(final UUID actor, final Map<String, String> a) {
        this.requireOwner(actor);
        final Firm f = this.service.ctx.firm(a.get("id")).orElseThrow(() -> new IllegalArgumentException("Nie ma takiej firmy."));
        final boolean frozen = Boolean.parseBoolean(a.getOrDefault("frozen", "true"));
        final String reason = a.getOrDefault("reason", "decyzja właściciela");
        if (reason.length() > 200) {
            throw new IllegalArgumentException("Za długi powód.");
        }
        return outcome(this.service.treasury.freeze(f, frozen, "panel: " + reason));
    }
}
