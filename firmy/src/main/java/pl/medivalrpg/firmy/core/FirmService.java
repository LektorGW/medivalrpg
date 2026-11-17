// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;

/** Facade of the company rules (pure Java, main thread): registry, people, money, warehouse and the clock. */
public final class FirmService {

    public final Ctx ctx;
    public final Registry registry;
    public final People people;
    public final Treasury treasury;
    public final Warehouse warehouse;
    private final List<Extension> extensions = new CopyOnWriteArrayList<>();
    private long lastHourly;

    public FirmService(final Ctx ctx) {
        this.ctx = ctx;
        this.registry = new Registry(ctx);
        this.people = new People(ctx, this.registry);
        this.treasury = new Treasury(ctx, this.registry, this.extensions);
        this.warehouse = new Warehouse(ctx);
    }

    public long bankBalance(final String account) {
        return this.ctx.bank.balance(account);
    }

    public void addExtension(final Extension e) {
        this.extensions.add(e);
    }

    public List<Extension> extensions() {
        return List.copyOf(this.extensions);
    }

    /** Called every minute: daily settlement once a day after the configured hour, hourly payouts and expiries. */
    public void tick() {
        final long now = this.ctx.now();
        final ZonedDateTime z = Instant.ofEpochMilli(now).atZone(this.ctx.settings.zone);
        final String today = z.toLocalDate().toString();
        if (now - this.lastHourly >= Ctx.HOUR) {
            this.lastHourly = now;
            this.registry.expire();
            this.treasury.hourly();
        }
        if (z.getHour() >= this.ctx.settings.dailyHour && !today.equals(this.ctx.data.lastDaily)) {
            this.ctx.data.lastDaily = today;
            this.ctx.store.saveNow(); // the day is marked before paying: a crash never pays the same day twice
            this.treasury.daily();
        }
    }

    /** The company a player acts for by default: the one they lead, else the first one they belong to. */
    public Optional<Firm> primary(final UUID player) {
        final List<Firm> mine = this.ctx.firmsOf(player);
        return mine.stream().filter(f -> f.has(player, Role.MISTRZ)).findFirst().or(() -> mine.stream().findFirst());
    }

    /** Active companies sorted for the ranking: obrot (30-day income), majatek (balance), ludzie, kontrakty. */
    public List<Firm> ranking(final String sort) {
        final List<Firm> list = new ArrayList<>();
        for (final Firm f : this.ctx.data.firms.values()) {
            if (f.active()) {
                list.add(f);
            }
        }
        final String from = this.ctx.windowStart(30);
        final Comparator<Firm> c = switch (sort == null ? "obrot" : sort) {
            case "majatek" -> Comparator.comparingLong(f -> -this.treasury.balance(f));
            case "ludzie" -> Comparator.comparingInt(f -> -f.members.size());
            case "kontrakty" -> Comparator.comparingInt(f -> -f.contractsDone);
            default -> Comparator.comparingLong(f -> -f.income(from));
        };
        list.sort(c.thenComparing(f -> f.name));
        return list;
    }

    /** Applications/firms reconciliation and audit freeze of uncertain operations after a restart. */
    public List<String> startup() {
        final List<String> notes = new ArrayList<>(this.registry.reconcile());
        for (final var i : this.ctx.intents) {
            final Firm f = i.firm == null ? null : this.ctx.data.firms.get(i.firm);
            if (f != null && !f.frozen) {
                f.frozen = true;
                f.frozenReason = "audyt: niepotwierdzona operacja „" + i.kind + "” z " + this.ctx.day(i.createdAt);
                this.ctx.journal(f, "admin", "Zamrożenie do audytu: niepotwierdzona operacja " + i.kind + " (" + i.description + ")", 0, "system");
            }
            notes.add("Niepotwierdzona operacja " + i.kind + " firmy " + i.firm + ": " + i.description + " " + i.legs);
        }
        if (!notes.isEmpty()) {
            this.ctx.save();
        }
        return notes;
    }
}
