// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import pl.medivalrpg.firmy.core.Ctx;
import pl.medivalrpg.firmy.core.Extension;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Manufactories (stage 3): workshops on the seat country's land with an input and an output chest, NPC workers taken
 * from the seat country's citizens (Ludnosc) for a daily wage paid to the citizens' account, processing real items with
 * vanilla ratios. An unpaid worker, an insolvent or liquidated company sends the workers back to the country. Pure Java
 * (the world is behind {@link Labor} and {@link Production.Box}), main thread.
 */
public final class Manufactures implements Extension {

    /** Citizens of Ludnosc and worker bodies in the world. */
    public interface Labor {
        /** Takes one adult citizen of the country for the workshop; its profession, or empty with a reason via {@link #why()}. */
        Optional<String> hire(UUID country, Stage3Data.Workshop w, Stage3Data.Worker worker);

        /** Gives the citizen back to the country (a villager near the workshop) and removes the worker body. */
        void dismiss(UUID country, Stage3Data.Workshop w, Stage3Data.Worker worker);

        /** Why the last hire failed (Polish). */
        String why();

        /** Skarbiec account of the country's citizens (wages go there). */
        String citizensAccount(UUID country);
    }

    public static final class Settings {
        public int maxWorkshops = 4;
        public int workersPerWorkshop = 2;
        /** 30-day income thresholds (copper) → total worker limit of the company. */
        public long[] tierIncome = {0, 100_000, 500_000};
        public int[] tierWorkers = {2, 4, 8};
        public long wage = 300;
        public long catchUpMs = 60L * 60_000L;
        public int batchesPerTick = 16;
        public int chestReach = 6;
        public int era = 2;
        public Map<String, Recipe> recipes = Recipe.defaults();
        /**
         * Decision of the user 2026-10-31: a guild (cech) may run ONE workshop with medieval recipes already in era 1.
         * Same workers, wages and limits as a manufactory; the full manufactory stays era 2.
         */
        public boolean guildWorkshops = true;
        public int guildMaxWorkshops = 1;
        public int guildEra = 1;
        public List<String> guildRecipes = List.of("chleb", "deski_debowe", "deski_swierkowe", "deski_brzozowe", "welna",
            "wegiel_drzewny", "cegly", "wedzona_wolowina", "wedzona_wieprzowina");

        public static Settings from(final ConfigurationSection root) {
            final Settings s = new Settings();
            if (root == null) {
                return s;
            }
            final ConfigurationSection m = root.getConfigurationSection("manufaktura");
            if (m == null) {
                return s;
            }
            s.era = Math.max(1, Math.min(4, m.getInt("epoka", s.era)));
            s.maxWorkshops = Math.max(1, Math.min(50, m.getInt("max-warsztatow", s.maxWorkshops)));
            s.workersPerWorkshop = Math.max(1, Math.min(10, m.getInt("robotnikow-na-warsztat", s.workersPerWorkshop)));
            s.wage = Math.max(0, m.getLong("pensja-robotnika", s.wage));
            s.catchUpMs = Math.max(0, m.getLong("nadrabianie-minut", 60)) * 60_000L;
            s.batchesPerTick = Math.max(1, Math.min(256, m.getInt("partii-na-takt", s.batchesPerTick)));
            s.chestReach = Math.max(2, Math.min(16, m.getInt("zasieg-skrzyn", s.chestReach)));
            final List<Integer> workers = m.getIntegerList("limit-robotnikow.liczba");
            final List<Long> incomes = m.getLongList("limit-robotnikow.obrot-30-dni");
            if (!workers.isEmpty() && workers.size() == incomes.size()) {
                s.tierWorkers = workers.stream().mapToInt(Integer::intValue).toArray();
                s.tierIncome = incomes.stream().mapToLong(Long::longValue).toArray();
            }
            s.recipes = Recipe.from(m.getConfigurationSection("przepisy"));
            final ConfigurationSection g = m.getConfigurationSection("cech");
            if (g != null) {
                s.guildWorkshops = g.getBoolean("wlaczone", s.guildWorkshops);
                s.guildMaxWorkshops = Math.max(0, Math.min(10, g.getInt("max-warsztatow", s.guildMaxWorkshops)));
                s.guildEra = Math.max(1, Math.min(4, g.getInt("epoka", s.guildEra)));
                if (g.isList("przepisy")) {
                    s.guildRecipes = g.getStringList("przepisy").stream().map(x -> x.toLowerCase(Locale.ROOT)).toList();
                }
            }
            return s;
        }
    }

    private final FirmService service;
    private final Stage3Data data;
    private final Labor labor;
    private volatile Settings s;
    private final Runnable save;

    public Manufactures(final FirmService service, final Stage3Data data, final Labor labor, final Settings settings, final Runnable save) {
        this.service = service;
        this.data = data;
        this.labor = labor;
        this.s = settings;
        this.save = save;
    }

    public Settings settings() {
        return this.s;
    }

    public void settings(final Settings settings) {
        this.s = settings;
    }

    private Ctx ctx() {
        return this.service.ctx;
    }

    public List<Stage3Data.Workshop> of(final Firm f) {
        return this.data.workshops.values().stream().filter(w -> f.id.equals(w.firm)).toList();
    }

    public Optional<Stage3Data.Workshop> get(final String id) {
        return Optional.ofNullable(id == null ? null : this.data.workshops.get(id.toLowerCase(Locale.ROOT)));
    }

    public int workers(final Firm f) {
        return this.of(f).stream().mapToInt(w -> w.workers.size()).sum();
    }

    public int workerLimit(final Firm f) {
        final long income = f.income(this.ctx().windowStart(30));
        int limit = 0;
        for (int i = 0; i < this.s.tierIncome.length; i++) {
            if (income >= this.s.tierIncome[i]) {
                limit = this.s.tierWorkers[i];
            }
        }
        return limit;
    }

    /** A guild with its single medieval workshop (decision 2026-10-31). */
    public boolean guild(final Firm f) {
        return f.type == FirmType.CECH && this.s.guildWorkshops && this.s.guildMaxWorkshops > 0;
    }

    /** Recipes the company may use in a new workshop. */
    public List<String> recipesFor(final Firm f) {
        if (f.type == FirmType.MANUFAKTURA) {
            return List.copyOf(this.s.recipes.keySet());
        }
        return this.guild(f) ? this.s.guildRecipes.stream().filter(this.s.recipes::containsKey).toList() : List.of();
    }

    /** Most workshops of the company (manufactory: config; guild: 1; others: 0). */
    public int maxWorkshops(final Firm f) {
        return f.type == FirmType.MANUFAKTURA ? this.s.maxWorkshops : this.guild(f) ? this.s.guildMaxWorkshops : 0;
    }

    private Optional<String> manufactory(final Firm f, final UUID actor) {
        if (!f.active()) {
            return Optional.of("Firma jest zlikwidowana.");
        }
        if (f.type != FirmType.MANUFAKTURA && !this.guild(f)) {
            return Optional.of("Warsztaty z robotnikami NPC ma manufaktura, a cech — jeden warsztat z przepisami średniowiecznymi.");
        }
        if (!f.has(actor, Role.WSPOLNIK)) {
            return Optional.of("Warsztatami zarządza mistrz albo wspólnik.");
        }
        if (f.seatCountry == null || f.seatLostAt != 0) {
            return Optional.of("Firma bez kraju siedziby nie prowadzi warsztatów.");
        }
        final int era = this.ctx().world.era(UUID.fromString(f.seatCountry));
        if (f.type == FirmType.MANUFAKTURA && era < this.s.era) {
            return Optional.of("Manufaktura wymaga epoki Renesansu w kraju siedziby.");
        }
        if (f.type == FirmType.CECH && era < this.s.guildEra) {
            return Optional.of("Warsztat cechu wymaga epoki " + this.s.guildEra + " w kraju siedziby.");
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ workshops

    public Outcome create(final Firm f, final UUID actor, final String recipeId, final String world, final int x, final int y, final int z,
                          final String stationMaterial, final UUID landCountry) {
        final Optional<String> p = this.manufactory(f, actor);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        final Recipe r = this.s.recipes.get(recipeId == null ? "" : recipeId.toLowerCase(Locale.ROOT));
        final List<String> allowed = this.recipesFor(f);
        if (r == null || !allowed.contains(r.id())) {
            return Outcome.fail((r == null ? "Nieznany przepis." : "Ten przepis ma tylko manufaktura (Renesans).") + " Dostępne dla "
                + f.type.displayName().toLowerCase(Locale.ROOT) + ": " + String.join(", ", allowed) + ".");
        }
        if (!r.station().equals(stationMaterial)) {
            return Outcome.fail("Przepis „" + r.name() + "” wymaga stanowiska " + r.station().toLowerCase(Locale.ROOT) + " (patrzysz na "
                + String.valueOf(stationMaterial).toLowerCase(Locale.ROOT) + ").");
        }
        if (landCountry == null || !landCountry.toString().equals(f.seatCountry)) {
            return Outcome.fail("Warsztat musi stać na ziemi kraju siedziby.");
        }
        if (this.of(f).size() >= this.maxWorkshops(f)) {
            return Outcome.fail((f.type == FirmType.CECH ? "Cech może mieć " : "Manufaktura ma już najwięcej warsztatów: ")
                + this.maxWorkshops(f) + (f.type == FirmType.CECH ? " warsztat (więcej i pełna lista przepisów: manufaktura w Renesansie)." : "."));
        }
        final String key = world + ":" + x + ":" + y + ":" + z;
        if (this.data.workshops.values().stream().anyMatch(w -> w.key().equals(key))) {
            return Outcome.fail("To stanowisko jest już warsztatem.");
        }
        final Stage3Data.Workshop w = new Stage3Data.Workshop();
        w.id = Ctx.newId();
        w.firm = f.id;
        w.recipe = r.id();
        w.world = world;
        w.x = x;
        w.y = y;
        w.z = z;
        w.createdAt = this.ctx().now();
        w.status = "brak skrzyń";
        this.data.workshops.put(w.id, w);
        this.ctx().journal(f, "warsztat", "Nowy warsztat " + w.id + ": " + r.name(), 0, this.ctx().world.playerName(actor));
        this.save.run();
        this.ctx().save();
        return Outcome.ok("Warsztat " + w.id + " („" + r.name() + "”). Teraz spójrz na skrzynię wejścia: /firma warsztat wejscie " + w.id
            + ", potem wyjścia: /firma warsztat wyjscie " + w.id + ".", w.id);
    }

    /** Input or output chest within reach of the station; it also becomes a protected warehouse chest with a log. */
    public Outcome chest(final Firm f, final UUID actor, final String workshopId, final boolean input, final String world, final int x,
                         final int y, final int z, final UUID landCountry) {
        final Optional<Stage3Data.Workshop> ow = this.get(workshopId);
        if (ow.isEmpty() || !f.id.equals(ow.get().firm)) {
            return Outcome.fail("Nie ma takiego warsztatu w tej firmie.");
        }
        final Stage3Data.Workshop w = ow.get();
        if (!world.equals(w.world) || Math.abs(x - w.x) > this.s.chestReach || Math.abs(y - w.y) > this.s.chestReach
            || Math.abs(z - w.z) > this.s.chestReach) {
            return Outcome.fail("Skrzynia musi stać najwyżej " + this.s.chestReach + " bloków od stanowiska.");
        }
        final String key = world + ":" + x + ":" + y + ":" + z;
        if (key.equals(input ? w.output : w.input)) {
            return Outcome.fail("Skrzynia wejścia i wyjścia muszą być różne.");
        }
        if (this.service.warehouse.owner(key).map(o -> !o.id.equals(f.id)).orElse(false)) {
            return Outcome.fail("To skrzynia innej firmy.");
        }
        if (this.service.warehouse.owner(key).isEmpty()) {
            final Outcome add = this.service.warehouse.add(f, actor, world, x, y, z, landCountry, false);
            if (!add.ok()) {
                return add;
            }
        }
        if (input) {
            w.input = key;
        } else {
            w.output = key;
        }
        w.status = w.input != null && w.output != null ? (w.workers.isEmpty() ? "brak robotników" : "gotowy") : "brak skrzyń";
        this.save.run();
        return Outcome.ok("Skrzynia " + (input ? "wejścia" : "wyjścia") + " warsztatu " + w.id + " ustawiona (chroniona, z dziennikiem).");
    }

    public Outcome remove(final Firm f, final UUID actor, final String workshopId) {
        final Optional<Stage3Data.Workshop> ow = this.get(workshopId);
        if (ow.isEmpty() || !f.id.equals(ow.get().firm) || !f.has(actor, Role.WSPOLNIK)) {
            return Outcome.fail("Nie ma takiego warsztatu albo brak uprawnień.");
        }
        this.dismissAll(f, ow.get(), "warsztat zamknięty");
        this.data.workshops.remove(ow.get().id);
        this.save.run();
        return Outcome.ok("Warsztat " + ow.get().id + " zamknięty, robotnicy wrócili do kraju.");
    }

    // ------------------------------------------------------------------ workers

    public Outcome hire(final Firm f, final UUID actor, final String workshopId) {
        final Optional<String> p = this.manufactory(f, actor);
        if (p.isPresent()) {
            return Outcome.fail(p.get());
        }
        final Optional<Stage3Data.Workshop> ow = this.get(workshopId);
        if (ow.isEmpty() || !f.id.equals(ow.get().firm)) {
            return Outcome.fail("Nie ma takiego warsztatu w tej firmie.");
        }
        final Stage3Data.Workshop w = ow.get();
        if (w.input == null || w.output == null) {
            return Outcome.fail("Najpierw ustaw skrzynie wejścia i wyjścia warsztatu.");
        }
        if (f.frozen || this.service.treasury.insolvent(f)) {
            return Outcome.fail("Zamrożona albo niewypłacalna firma nie zatrudnia.");
        }
        if (w.workers.size() >= this.s.workersPerWorkshop) {
            return Outcome.fail("Warsztat ma już " + w.workers.size() + " robotników (najwyżej " + this.s.workersPerWorkshop + ").");
        }
        final int limit = this.workerLimit(f);
        if (this.workers(f) >= limit) {
            return Outcome.fail("Limit robotników manufaktury: " + limit + " (rośnie z obrotem z 30 dni).");
        }
        if (this.service.treasury.balance(f) < this.s.wage) {
            return Outcome.fail("Firma musi mieć na koncie co najmniej dzienną pensję robotnika (" + this.ctx().fmt(this.s.wage) + ").");
        }
        final Stage3Data.Worker worker = new Stage3Data.Worker();
        worker.id = Ctx.newId();
        worker.hiredAt = this.ctx().now();
        worker.wage = this.s.wage;
        final Optional<String> prof = this.labor.hire(UUID.fromString(f.seatCountry), w, worker);
        if (prof.isEmpty()) {
            return Outcome.fail(this.labor.why());
        }
        worker.profession = prof.get();
        w.workers.add(worker);
        w.status = "gotowy";
        this.ctx().journal(f, "robotnik", "Zatrudniono robotnika " + (worker.name == null ? "" : worker.name) + " w warsztacie " + w.id
            + " (pensja " + this.ctx().fmt(worker.wage) + " dziennie dla obywateli)", 0, this.ctx().world.playerName(actor));
        this.save.run();
        this.ctx().save();
        return Outcome.ok("Zatrudniono obywatela " + (worker.name == null ? "" : worker.name + " ") + "w warsztacie " + w.id + ". Pensja "
            + this.ctx().fmt(worker.wage) + " dziennie trafia do puli obywateli kraju siedziby.");
    }

    public Outcome dismiss(final Firm f, final UUID actor, final String workshopId) {
        final Optional<Stage3Data.Workshop> ow = this.get(workshopId);
        if (ow.isEmpty() || !f.id.equals(ow.get().firm) || !f.has(actor, Role.WSPOLNIK)) {
            return Outcome.fail("Nie ma takiego warsztatu albo brak uprawnień.");
        }
        final Stage3Data.Workshop w = ow.get();
        if (w.workers.isEmpty()) {
            return Outcome.fail("W warsztacie nie ma robotników.");
        }
        final Stage3Data.Worker worker = w.workers.getLast();
        this.dismissOne(f, w, worker, "zwolniony przez " + this.ctx().world.playerName(actor));
        this.save.run();
        return Outcome.ok("Robotnik wrócił do ludności kraju.");
    }

    private void dismissOne(final Firm f, final Stage3Data.Workshop w, final Stage3Data.Worker worker, final String why) {
        if (f.seatCountry != null) {
            this.labor.dismiss(UUID.fromString(f.seatCountry), w, worker);
        }
        w.workers.remove(worker);
        if (w.workers.isEmpty()) {
            w.status = "brak robotników";
        }
        this.ctx().journal(f, "robotnik", "Robotnik " + (worker.name == null ? "" : worker.name + " ") + "wraca do kraju (" + why + ")", 0, "system");
    }

    private void dismissAll(final Firm f, final Stage3Data.Workshop w, final String why) {
        for (final Stage3Data.Worker worker : List.copyOf(w.workers)) {
            this.dismissOne(f, w, worker, why);
        }
    }

    /** Daily wages: company → citizens of the seat country; an unpaid worker goes back to the country. */
    public void wages() {
        for (final Stage3Data.Workshop w : List.copyOf(this.data.workshops.values())) {
            final Firm f = this.ctx().data.firms.get(w.firm);
            if (f == null || !f.active() || f.seatCountry == null) {
                continue;
            }
            final String to = this.labor.citizensAccount(UUID.fromString(f.seatCountry));
            for (final Stage3Data.Worker worker : List.copyOf(w.workers)) {
                if (worker.wage <= 0) {
                    continue;
                }
                final TxResult r = f.frozen ? TxResult.INSUFFICIENT_FUNDS : this.ctx().bank.transfer(f.account(), to, worker.wage,
                    "Firmy: pensja robotnika NPC manufaktury " + f.name + " (warsztat " + w.id + ")");
                if (r.ok()) {
                    this.ctx().journal(f, "robotnik", "Pensja robotnika " + (worker.name == null ? "" : worker.name) + " dla obywateli", -worker.wage, "system");
                } else {
                    this.dismissOne(f, w, worker, "brak pieniędzy na pensję");
                    this.ctx().partners(f, "<gold>[Firmy]</gold> <red>Robotnik manufaktury „" + f.name + "” odszedł: brak pieniędzy na pensję.");
                }
            }
        }
        this.save.run();
        this.ctx().save();
    }

    // ------------------------------------------------------------------ work

    /**
     * Advances every workshop by {@code elapsedMs} of work per worker. Workshops whose chunk is not loaded bank the time
     * (capped) and catch up when it loads.
     *
     * @return whether any workshop changed (status, banked time, production), i.e. whether the data needs saving
     */
    public boolean work(final long elapsedMs, final Predicate<Stage3Data.Workshop> loaded, final Function<String, Production.Box> boxes) {
        boolean changed = false;
        for (final Stage3Data.Workshop w : this.data.workshops.values()) {
            if (w.workers.isEmpty() || w.input == null || w.output == null) {
                continue;
            }
            final String status0 = w.status;
            final long pending0 = w.pendingMs;
            final long produced0 = w.produced;
            final int fuel0 = w.fuelLeft;
            this.workOne(w, elapsedMs, loaded, boxes);
            changed |= !Objects.equals(status0, w.status) || pending0 != w.pendingMs || produced0 != w.produced || fuel0 != w.fuelLeft;
        }
        return changed;
    }

    private void workOne(final Stage3Data.Workshop w, final long elapsedMs, final Predicate<Stage3Data.Workshop> loaded,
                         final Function<String, Production.Box> boxes) {
        final Firm f = this.ctx().data.firms.get(w.firm);
        if (f == null || !f.active() || f.frozen || f.seatLostAt != 0) {
            w.status = "firma nie pracuje";
            return;
        }
        final Recipe r = this.s.recipes.get(w.recipe);
        if (r == null) {
            w.status = "nieznany przepis";
            return;
        }
        final long budget = elapsedMs * w.workers.size();
        if (!loaded.test(w)) {
            w.pendingMs = Math.min(this.s.catchUpMs, w.pendingMs + budget);
            return;
        }
        long total = w.pendingMs + budget;
        final long per = r.seconds() * 1000L;
        final Production.Box in = boxes.apply(w.input);
        final Production.Box out = boxes.apply(w.output);
        if (in == null || out == null) {
            w.status = "brak skrzyni";
            w.pendingMs = 0;
            return;
        }
        int done = 0;
        Production.Result last = Production.Result.DONE;
        while (total >= per && done < this.s.batchesPerTick) {
            last = Production.batch(r, w, in, out);
            if (last != Production.Result.DONE) {
                total = 0; // stopped: no banking of idle time
                break;
            }
            total -= per;
            done++;
        }
        w.pendingMs = Math.min(this.s.catchUpMs, Math.max(0, total));
        w.status = last.label();
    }

    // ------------------------------------------------------------------ life cycle hooks

    @Override
    public void onInsolvent(final Firm firm) {
        for (final Stage3Data.Workshop w : this.of(firm)) {
            this.dismissAll(firm, w, "firma niewypłacalna");
        }
        this.save.run();
    }

    @Override
    public void afterLiquidation(final Firm firm) {
        for (final Stage3Data.Workshop w : List.copyOf(this.of(firm))) {
            this.dismissAll(firm, w, "likwidacja firmy");
            this.data.workshops.remove(w.id);
        }
        this.save.run();
    }

    @Override
    public void daily(final long now) {
        final String today = this.ctx().today();
        if (!today.equals(this.data.lastWages)) {
            this.data.lastWages = today;
            this.wages();
        }
    }

    /** Admin (before a rollback of stage 3): every worker of every company goes back to its country. */
    public int dismissEverybody(final String why) {
        int n = 0;
        for (final Stage3Data.Workshop w : this.data.workshops.values()) {
            final Firm f = this.ctx().data.firms.get(w.firm);
            if (f == null) {
                continue;
            }
            n += w.workers.size();
            this.dismissAll(f, w, why);
        }
        this.save.run();
        this.ctx().save();
        return n;
    }

    public List<Stage3Data.Workshop> all() {
        return new ArrayList<>(this.data.workshops.values());
    }
}
