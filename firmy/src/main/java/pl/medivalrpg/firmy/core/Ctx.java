// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmsData;

/** Shared state and helpers of the company services (pure Java; main thread). */
public final class Ctx {

    /** Persistence of {@code firmy.json} and of the intent journal. */
    public interface Store {
        /** Asynchronous atomic save of the data; completes when the file is on disk. */
        CompletableFuture<Void> save();

        /** Synchronous atomic save (fsync) of the data, before a money operation that depends on a record. */
        void saveNow();

        /** Synchronous save (fsync) of open intents, before a money operation. */
        void saveIntentsNow(List<FirmsData.Intent> intents);

        /** Asynchronous save of open intents (after one was closed). */
        default void saveIntentsLater(final List<FirmsData.Intent> intents) {
            this.saveIntentsNow(intents);
        }
    }

    public static final long DAY = 86_400_000L;
    public static final long HOUR = 3_600_000L;

    public final FirmsData data;
    public final List<FirmsData.Intent> intents;
    public final Bank bank;
    public final GameWorld world;
    public final Notifier notifier;
    public final Clock clock;
    public final Store store;
    public volatile Settings settings;
    /**
     * Where completions of the bank/data futures continue: the server main thread in the plugin (never the save thread,
     * which a synchronous save waits for), directly in tests.
     */
    public volatile java.util.concurrent.Executor main = Runnable::run;

    public Ctx(final FirmsData data, final List<FirmsData.Intent> intents, final Bank bank, final GameWorld world,
               final Notifier notifier, final Clock clock, final Store store, final Settings settings) {
        this.data = data;
        this.intents = new java.util.concurrent.CopyOnWriteArrayList<>(intents == null ? List.of() : intents);
        this.bank = bank;
        this.world = world;
        this.notifier = notifier;
        this.clock = clock;
        this.store = store;
        this.settings = settings;
    }

    public long now() {
        return this.clock.millis();
    }

    public String day(final long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), this.settings.zone).toString();
    }

    public String today() {
        return this.day(this.now());
    }

    /** First day of a window of {@code days} days ending today (inclusive). */
    public String windowStart(final int days) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(this.now()), this.settings.zone).minusDays(days - 1L).toString();
    }

    public static String newId() {
        return String.format(Locale.ROOT, "%010x", ThreadLocalRandom.current().nextLong(0x10000000000L));
    }

    public String fmt(final long copper) {
        return this.bank.format(copper);
    }

    public Optional<Firm> firm(final String idOrName) {
        if (idOrName == null) {
            return Optional.empty();
        }
        final Firm byId = this.data.firms.get(idOrName.toLowerCase(Locale.ROOT));
        if (byId != null) {
            return Optional.of(byId);
        }
        final String n = Names.normalize(idOrName);
        Firm found = null;
        for (final Firm f : this.data.firms.values()) {
            if (Names.normalize(f.name).equals(n)) {
                if (f.active()) {
                    return Optional.of(f);
                }
                found = f;
            }
        }
        return Optional.ofNullable(found);
    }

    /** Active companies the player is a member of. */
    public List<Firm> firmsOf(final UUID player) {
        final List<Firm> out = new ArrayList<>();
        for (final Firm f : this.data.firms.values()) {
            if (f.active() && f.members.containsKey(player.toString())) {
                out.add(f);
            }
        }
        return out;
    }

    public void journal(final Firm f, final String kind, final String text, final long amount, final String actor) {
        f.journal.add(new Firm.Entry(this.now(), kind, text, amount, actor));
        final int max = this.settings.journalSize;
        if (f.journal.size() > max) {
            f.journal.subList(0, f.journal.size() - max).clear();
        }
    }

    /** Notify every member of the company. */
    public void members(final Firm f, final String miniMessage) {
        for (final Firm.Member m : f.members.values()) {
            this.notifier.player(m.id(), miniMessage);
        }
    }

    /** Members with at least the partner role (they see the ledger and get payout warnings). */
    public void partners(final Firm f, final String miniMessage) {
        for (final Firm.Member m : f.members.values()) {
            if (m.role != pl.medivalrpg.firmy.model.Role.CZELADNIK) {
                this.notifier.player(m.id(), miniMessage);
            }
        }
    }

    // ------------------------------------------------------------------ intents

    /** Writes the intent to disk (fsync) before the money moves. */
    public FirmsData.Intent intent(final String firm, final String kind, final String description, final List<Bank.Leg> legs) {
        final FirmsData.Intent i = new FirmsData.Intent();
        i.id = newId();
        i.firm = firm;
        i.kind = kind;
        i.description = description;
        i.createdAt = this.now();
        for (final Bank.Leg l : legs) {
            i.legs.add(l.from() + " -> " + l.to() + " : " + l.amount() + " (" + l.reason() + ")");
        }
        this.intents.add(i);
        this.store.saveIntentsNow(this.intents);
        return i;
    }

    /** Closes the intent once the company data and the bank journal are both on disk. */
    public void settle(final FirmsData.Intent i) {
        final CompletableFuture<Void> data = this.store.save();
        final CompletableFuture<Void> bankDone = this.bank.durable();
        CompletableFuture.allOf(data, bankDone).whenComplete((v, err) -> {
            if (err == null) {
                this.main.execute(() -> this.closeIntent(i));
            }
        });
    }

    /** Intent of an operation that did not move money (refused by the bank): drop it at once. */
    public void drop(final FirmsData.Intent i) {
        if (this.intents.removeIf(x -> x.id.equals(i.id))) {
            this.store.saveIntentsNow(this.intents);
        }
    }

    /** Main thread (see {@link #main}): never on the save thread, which a synchronous save waits for. */
    private void closeIntent(final FirmsData.Intent i) {
        if (this.intents.removeIf(x -> x.id.equals(i.id))) {
            this.store.saveIntentsLater(this.intents);
        }
    }

    public void save() {
        this.store.save();
    }
}
