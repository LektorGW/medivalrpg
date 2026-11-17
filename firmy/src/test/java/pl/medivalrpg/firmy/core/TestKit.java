// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.skarbiec.api.TxResult;

/** In-memory bank, world, clock and store for the company rules. */
final class TestKit {

    static final long POOL = 100_000_000L;

    /** Closed-pool bank: only transfers, the sum of all balances never changes. */
    static final class FakeBank implements Bank {
        final Map<String, Long> balances = new HashMap<>();
        final List<String> log = new ArrayList<>();
        boolean failNext;
        /** Accounts that refuse incoming money (e.g. a broken client account): every transfer to them fails. */
        final java.util.Set<String> rejectTo = new java.util.HashSet<>();

        FakeBank() {
            this.balances.put("world:reserve", POOL);
        }

        void give(final String account, final long amount) {
            this.balances.merge("world:reserve", -amount, Long::sum);
            this.balances.merge(account, amount, Long::sum);
        }

        /** Raw move used by fakes of the World Treasury (owner-approved allocations). */
        boolean transfer2(final String from, final String to, final long amount) {
            if (amount <= 0 || this.balance(from) < amount) {
                return false;
            }
            this.balances.merge(from, -amount, Long::sum);
            this.balances.merge(to, amount, Long::sum);
            return true;
        }

        long sum() {
            return this.balances.values().stream().mapToLong(Long::longValue).sum();
        }

        boolean noNegative() {
            return this.balances.values().stream().allMatch(v -> v >= 0);
        }

        @Override
        public long balance(final String account) {
            return this.balances.getOrDefault(account, 0L);
        }

        @Override
        public TxResult transfer(final String from, final String to, final long amount, final String reason) {
            if (this.failNext) {
                this.failNext = false;
                return TxResult.INVALID_ACCOUNT;
            }
            if (amount <= 0 || from.equals(to)) {
                return TxResult.INVALID_AMOUNT;
            }
            if (from.startsWith("world:") || to.startsWith("world:reserve") || this.rejectTo.contains(to)) {
                return TxResult.INVALID_ACCOUNT;
            }
            if (this.balance(from) < amount) {
                return TxResult.INSUFFICIENT_FUNDS;
            }
            this.balances.merge(from, -amount, Long::sum);
            this.balances.merge(to, amount, Long::sum);
            this.log.add(from + " -> " + to + " " + amount + " " + reason);
            return TxResult.OK;
        }

        @Override
        public TxResult transferAll(final List<Leg> legs) {
            if (this.failNext) {
                this.failNext = false;
                return TxResult.INVALID_ACCOUNT;
            }
            final Map<String, Long> need = new HashMap<>();
            for (final Leg l : legs) {
                if (this.rejectTo.contains(l.to())) {
                    return TxResult.INVALID_ACCOUNT;
                }
                need.merge(l.from(), l.amount(), Long::sum);
            }
            for (final var e : need.entrySet()) {
                if (this.balance(e.getKey()) < e.getValue()) {
                    return TxResult.INSUFFICIENT_FUNDS;
                }
            }
            for (final Leg l : legs) {
                final TxResult r = this.transfer(l.from(), l.to(), l.amount(), l.reason());
                if (!r.ok()) {
                    throw new IllegalStateException("batch leg failed after validation: " + r);
                }
            }
            return TxResult.OK;
        }

        @Override
        public CompletableFuture<Void> durable() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String format(final long copper) {
            return copper + " md";
        }
    }

    static final class FakeWorld implements GameWorld {
        final Map<UUID, Country> countries = new HashMap<>();
        final Map<UUID, UUID> citizenship = new HashMap<>();
        final Map<UUID, Integer> ranks = new HashMap<>();
        final Map<String, Boolean> wars = new HashMap<>();
        final Map<UUID, Long> minutes = new HashMap<>();
        final Map<UUID, Integer> reputation = new HashMap<>();
        final Map<UUID, Integer> eras = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        long firstPlayed = 1L;

        UUID country(final String name, final boolean npc) {
            final UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            this.countries.put(id, new Country(id, name, npc, "country:" + id));
            return id;
        }

        UUID player(final String name) {
            final UUID id = UUID.nameUUIDFromBytes(("p" + name).getBytes());
            this.names.put(id, name);
            this.minutes.put(id, 10_000L);
            return id;
        }

        void join(final UUID player, final UUID country, final int rank) {
            this.citizenship.put(player, country);
            this.ranks.put(player, rank);
        }

        @Override
        public Optional<Country> country(final UUID id) {
            return Optional.ofNullable(this.countries.get(id));
        }

        @Override
        public Optional<Country> countryOf(final UUID player) {
            return Optional.ofNullable(this.citizenship.get(player)).map(this.countries::get);
        }

        @Override
        public int rank(final UUID country, final UUID player) {
            return country.equals(this.citizenship.get(player)) ? this.ranks.getOrDefault(player, 1) : 0;
        }

        @Override
        public boolean atWar(final UUID a, final UUID b) {
            return this.wars.getOrDefault(a + ":" + b, false) || this.wars.getOrDefault(b + ":" + a, false);
        }

        @Override
        public long playMinutes(final UUID player) {
            return this.minutes.getOrDefault(player, -1L);
        }

        @Override
        public long firstPlayed(final UUID player) {
            return this.firstPlayed;
        }

        @Override
        public Optional<Integer> reputation(final UUID npcCountry, final UUID player) {
            return Optional.ofNullable(this.reputation.get(player));
        }

        @Override
        public int era(final UUID country) {
            return this.eras.getOrDefault(country, 1);
        }

        @Override
        public String playerName(final UUID player) {
            return this.names.getOrDefault(player, player.toString().substring(0, 8));
        }
    }

    static final class MutableClock extends Clock {
        long millis;
        final ZoneId zone = ZoneId.of("Europe/Warsaw");

        MutableClock(final ZonedDateTime start) {
            this.millis = start.toInstant().toEpochMilli();
        }

        void plusHours(final long h) {
            this.millis += h * 3_600_000L;
        }

        @Override
        public ZoneId getZone() {
            return this.zone;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(this.millis);
        }

        @Override
        public long millis() {
            return this.millis;
        }
    }

    static final class MemStore implements Ctx.Store {
        int saves;
        int syncSaves;
        List<FirmsData.Intent> lastIntents = List.of();

        @Override
        public CompletableFuture<Void> save() {
            this.saves++;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void saveNow() {
            this.syncSaves++;
        }

        @Override
        public void saveIntentsNow(final List<FirmsData.Intent> intents) {
            this.lastIntents = List.copyOf(intents);
        }
    }

    static final class Messages implements Notifier {
        final List<String> sent = new ArrayList<>();

        @Override
        public void player(final UUID player, final String miniMessage) {
            this.sent.add(player + ": " + miniMessage);
        }

        @Override
        public void countryLeaders(final UUID country, final String miniMessage) {
            this.sent.add("leaders " + country + ": " + miniMessage);
        }

        @Override
        public void admin(final String text, final boolean severe) {
            this.sent.add("admin: " + text);
        }
    }

    final FakeBank bank = new FakeBank();
    final FakeWorld world = new FakeWorld();
    final MutableClock clock = new MutableClock(ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneId.of("Europe/Warsaw")));
    final MemStore store = new MemStore();
    final Messages messages = new Messages();
    final FirmsData data = new FirmsData();
    final Settings settings = Settings.defaults();
    final Ctx ctx;
    final FirmService service;

    TestKit() {
        this.ctx = new Ctx(this.data, new ArrayList<>(), this.bank, this.world, this.messages, this.clock, this.store, this.settings);
        this.service = new FirmService(this.ctx);
    }
}
