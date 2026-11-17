// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.firmy.stage3.Manufactures;
import pl.medivalrpg.firmy.stage3.Production;
import pl.medivalrpg.firmy.stage3.Stage3Data;

/** Manufactories: real items only, full output stops work, citizens hired and returned, wages to citizens. */
class Stage3Test {

    /** A chest: items with a slot capacity (64 per slot, 27 slots). */
    static final class MapBox implements Production.Box {
        final Map<String, Integer> items = new HashMap<>();
        int slots = 27;

        @Override
        public int count(final String item) {
            return this.items.getOrDefault(item, 0);
        }

        @Override
        public boolean remove(final String item, final int amount) {
            if (this.count(item) < amount) {
                return false;
            }
            this.items.merge(item, -amount, Integer::sum);
            return true;
        }

        private int used() {
            return this.items.values().stream().mapToInt(v -> (v + 63) / 64).sum();
        }

        @Override
        public boolean fits(final String item, final int amount) {
            final int have = this.count(item);
            final int extraSlots = (have + amount + 63) / 64 - (have + 63) / 64;
            return this.used() + extraSlots <= this.slots;
        }

        @Override
        public void add(final String item, final int amount) {
            this.items.merge(item, amount, Integer::sum);
        }
    }

    private final TestKit k = new TestKit();
    private final UUID country = this.k.world.country("Florencja", true);
    private final UUID jan = this.k.world.player("Jan");
    private final List<String> hired = new ArrayList<>();
    private final List<String> returned = new ArrayList<>();
    private boolean citizensLeft = true;
    private final Manufactures.Labor labor = new Manufactures.Labor() {
        @Override
        public Optional<String> hire(final UUID c, final Stage3Data.Workshop w, final Stage3Data.Worker worker) {
            if (!Stage3Test.this.citizensLeft) {
                return Optional.empty();
            }
            worker.name = "Anna";
            Stage3Test.this.hired.add(w.id);
            return Optional.of("minecraft:farmer");
        }

        @Override
        public void dismiss(final UUID c, final Stage3Data.Workshop w, final Stage3Data.Worker worker) {
            Stage3Test.this.returned.add(worker.profession);
        }

        @Override
        public String why() {
            return "brak wolnych obywateli";
        }

        @Override
        public String citizensAccount(final UUID c) {
            return "ludnosc:obywatele_" + c;
        }
    };
    private final Stage3Data data = new Stage3Data();
    private final Manufactures m = new Manufactures(this.k.service, this.data, this.labor, new Manufactures.Settings(), () -> { });
    private final Map<String, MapBox> boxes = new HashMap<>();

    Stage3Test() {
        this.k.service.addExtension(this.m);
        this.k.bank.give("player:" + this.jan, 1_000_000);
        this.k.world.eras.put(this.country, 2);
    }

    private Firm manufactory() {
        final Outcome o = this.k.service.registry.found(this.jan, "Jan", "Piekarnia", FirmType.MANUFAKTURA, this.country, 50_000, false);
        assertTrue(o.ok(), o.message());
        return this.k.ctx.firm(o.id()).orElseThrow();
    }

    private Stage3Data.Workshop workshop(final Firm f, final String recipe, final String station) {
        final Outcome o = this.m.create(f, this.jan, recipe, "world", 0, 64, 0, station, this.country);
        assertTrue(o.ok(), o.message());
        final Stage3Data.Workshop w = this.m.get(o.id()).orElseThrow();
        assertTrue(this.m.chest(f, this.jan, w.id, true, "world", 1, 64, 0, this.country).ok());
        assertTrue(this.m.chest(f, this.jan, w.id, false, "world", -1, 64, 0, this.country).ok());
        this.boxes.put(w.input, new MapBox());
        this.boxes.put(w.output, new MapBox());
        return w;
    }

    private void work(final long ms, final boolean loaded) {
        this.m.work(ms, w -> loaded, this.boxes::get);
    }

    @Test
    void onlyAManufactoryInTheRenaissanceWithTheRightStation() {
        // a company (kompania) has no workshops; a guild has one (see guildRunsOneMedievalWorkshopFromEraOne)
        final Outcome cech = this.k.service.registry.found(this.jan, "Jan", "Kompania", FirmType.KOMPANIA, this.country, 5_000, false);
        final Firm c = this.k.ctx.firm(cech.id()).orElseThrow();
        assertFalse(this.m.create(c, this.jan, "chleb", "world", 0, 64, 0, "CRAFTING_TABLE", this.country).ok());
        this.k.service.treasury.liquidate(c, "test", "admin");
        final Firm f = this.manufactory();
        assertFalse(this.m.create(f, this.jan, "chleb", "world", 0, 64, 0, "FURNACE", this.country).ok(), "bread needs a crafting table");
        assertFalse(this.m.create(f, this.jan, "chleb", "world", 0, 64, 0, "CRAFTING_TABLE", UUID.randomUUID()).ok(), "seat land only");
        this.k.world.eras.put(this.country, 1);
        assertFalse(this.m.create(f, this.jan, "chleb", "world", 0, 64, 0, "CRAFTING_TABLE", this.country).ok(), "era 2");
    }

    @Test
    void guildRunsOneMedievalWorkshopFromEraOne() {
        // decision of the user 2026-10-31: a cech may run ONE workshop with medieval recipes already in era 1
        this.k.world.eras.put(this.country, 1);
        final Outcome o = this.k.service.registry.found(this.jan, "Jan", "Cech Piekarzy", FirmType.CECH, this.country, 5_000, false);
        assertTrue(o.ok(), o.message());
        final Firm f = this.k.ctx.firm(o.id()).orElseThrow();
        final Outcome iron = this.m.create(f, this.jan, "sztabki_zelaza", "world", 5, 64, 5, "FURNACE", this.country);
        assertFalse(iron.ok(), "iron smelting stays with the manufactory");
        assertTrue(iron.message().contains("chleb"), iron.message());
        assertTrue(this.m.recipesFor(f).contains("chleb") && !this.m.recipesFor(f).contains("szklo"));
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        assertFalse(this.m.create(f, this.jan, "deski_debowe", "world", 9, 64, 9, "CRAFTING_TABLE", this.country).ok(), "one workshop");
        assertTrue(this.m.hire(f, this.jan, w.id).ok());
        this.boxes.get(w.input).add("WHEAT", 6);
        this.work(60_000, true);
        assertEquals(2, this.boxes.get(w.output).count("BREAD"), "real wheat to real bread");
        final long citizens0 = this.k.bank.balance("ludnosc:obywatele_" + this.country);
        this.m.wages();
        assertEquals(300, this.k.bank.balance("ludnosc:obywatele_" + this.country) - citizens0, "wage to the citizens, as in a manufactory");
        final Outcome manu = this.k.service.registry.found(this.k.world.player("Ola"), "Ola", "Manufaktura Oli", FirmType.MANUFAKTURA, this.country, 5_000, true);
        assertFalse(manu.ok(), "the full manufactory still needs the Renaissance");
        assertEquals(TestKit.POOL, this.k.bank.sum());
        assertTrue(this.k.bank.noNegative());
    }

    @Test
    void workersProcessRealInputsAndAFullOutputStopsWork() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        assertTrue(this.m.hire(f, this.jan, w.id).ok());
        assertEquals(1, w.workers.size());
        final MapBox in = this.boxes.get(w.input);
        final MapBox out = this.boxes.get(w.output);
        in.add("WHEAT", 10);
        this.work(30_000 * 4, true);
        assertEquals(3, out.count("BREAD"), "3 batches of 3 wheat (10 wheat)");
        assertEquals(1, in.count("WHEAT"));
        assertEquals("brak surowców w skrzyni wejścia", w.status);
        in.add("WHEAT", 300);
        out.slots = 1;
        out.items.put("BREAD", 63);
        this.work(30_000 * 3, true);
        assertEquals(64, out.count("BREAD"), "only what fits");
        assertEquals("pełna skrzynia wyjścia", w.status);
        assertEquals(298, in.count("WHEAT"), "nothing taken when the output is full");
    }

    @Test
    void workReportsChangesSoAnIdleStageThreeIsNotSavedEveryFiveSeconds() {
        assertFalse(this.m.work(5_000, w -> true, this.boxes::get), "no workshops: nothing to save");
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        assertFalse(this.m.work(5_000, x -> true, this.boxes::get), "a workshop without workers does not work");
        this.m.hire(f, this.jan, w.id);
        this.boxes.get(w.input).add("WHEAT", 3);
        assertTrue(this.m.work(30_000, x -> true, this.boxes::get), "a batch was produced");
        assertTrue(this.m.work(30_000, x -> true, this.boxes::get), "status changes to 'no inputs'");
        assertEquals("brak surowców w skrzyni wejścia", w.status);
        assertFalse(this.m.work(30_000, x -> true, this.boxes::get), "stopped and unchanged: no save");
        assertTrue(this.m.work(5_000, x -> false, this.boxes::get), "unloaded: banked time changed");
    }

    @Test
    void smeltingBurnsRealCoalEightBatchesPerPiece() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "sztabki_zelaza", "FURNACE");
        this.m.hire(f, this.jan, w.id);
        final MapBox in = this.boxes.get(w.input);
        in.add("RAW_IRON", 10);
        this.work(20_000 * 10, true);
        assertEquals(0, this.boxes.get(w.output).count("IRON_INGOT"), "no fuel, no smelting");
        assertEquals("brak węgla w skrzyni wejścia", w.status);
        in.add("COAL", 1);
        this.work(20_000 * 10, true);
        assertEquals(8, this.boxes.get(w.output).count("IRON_INGOT"));
        assertEquals(0, in.count("COAL"));
        assertEquals(2, in.count("RAW_IRON"));
    }

    @Test
    void unloadedWorkshopBanksTimeUpToTheCap() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "deski_debowe", "CRAFTING_TABLE");
        this.m.hire(f, this.jan, w.id);
        this.boxes.get(w.input).add("OAK_LOG", 64);
        this.work(10 * 60_000L, false);
        assertEquals(0, this.boxes.get(w.output).count("OAK_PLANKS"));
        this.work(0, true);
        assertEquals(16 * 4, this.boxes.get(w.output).count("OAK_PLANKS"), "catch-up limited to 16 batches per tick");
        this.work(0, true);
        assertEquals(30 * 4, this.boxes.get(w.output).count("OAK_PLANKS"), "10 min / 20 s = 30 batches in total");
    }

    @Test
    void wagesGoToTheCitizensAndAnUnpaidWorkerGoesHome() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        this.m.hire(f, this.jan, w.id);
        this.m.wages();
        assertEquals(300, this.k.bank.balance("ludnosc:obywatele_" + this.country));
        this.k.bank.transfer(f.account(), "klient:x", this.k.bank.balance(f.account()), "test");
        this.m.wages();
        assertTrue(w.workers.isEmpty());
        assertEquals(List.of("minecraft:farmer"), this.returned);
        assertEquals(TestKit.POOL, this.k.bank.sum());
    }

    @Test
    void limitsAndNoCitizensLeft() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        assertTrue(this.m.hire(f, this.jan, w.id).ok());
        assertTrue(this.m.hire(f, this.jan, w.id).ok());
        assertFalse(this.m.hire(f, this.jan, w.id).ok(), "2 per workshop");
        final Stage3Data.Workshop w2 = this.m.get(this.m.create(f, this.jan, "papier", "world", 5, 64, 5, "CRAFTING_TABLE", this.country).id())
            .orElseThrow();
        this.m.chest(f, this.jan, w2.id, true, "world", 6, 64, 5, this.country);
        this.m.chest(f, this.jan, w2.id, false, "world", 4, 64, 5, this.country);
        assertFalse(this.m.hire(f, this.jan, w2.id).ok(), "company limit 2 without income");
        this.m.dismiss(f, this.jan, w.id);
        this.citizensLeft = false;
        final Outcome none = this.m.hire(f, this.jan, w2.id);
        assertFalse(none.ok());
        assertEquals("brak wolnych obywateli", none.message());
    }

    @Test
    void insolvencyAndLiquidationSendWorkersHome() {
        final Firm f = this.manufactory();
        final Stage3Data.Workshop w = this.workshop(f, "chleb", "CRAFTING_TABLE");
        this.m.hire(f, this.jan, w.id);
        this.k.service.people.invite(f, this.jan, this.k.world.player("Ola"), "Ola", Role.CZELADNIK);
        this.m.onInsolvent(f);
        assertTrue(w.workers.isEmpty());
        this.m.hire(f, this.jan, w.id);
        this.k.service.treasury.liquidate(f, "test", "admin");
        assertTrue(this.m.of(f).isEmpty());
        assertEquals(2, this.returned.size());
        assertEquals(TestKit.POOL, this.k.bank.sum());
    }
}
