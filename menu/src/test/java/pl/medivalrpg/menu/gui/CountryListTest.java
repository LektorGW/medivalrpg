// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.DyeColor;
import org.junit.jupiter.api.Test;
import pl.medivalrpg.menu.integration.KrajeHook;

/** "Państwa" menu: list generated from fake {@code KrajInfo} data, order, paging and banner colours. */
public class CountryListTest {

    /** Same accessor names as {@code pl.medivalrpg.kraje.api.KrajInfo} (Menu reads them by reflection). */
    public record FakeInfo(UUID id, String name, TextColor color, String rulerName, Map<UUID, String> members,
                           int chunkCount, boolean npc) {
        public int memberCount() {
            return this.members.size();
        }
    }

    /** An older/foreign object without most accessors. */
    public record OnlyName(String name) {
    }

    private static FakeInfo info(final String name, final int rgb, final String ruler, final int members,
                                 final int chunks, final boolean npc) {
        final Map<UUID, String> m = new java.util.HashMap<>();
        for (int i = 0; i < members; i++) {
            m.put(UUID.randomUUID(), "gracz" + i);
        }
        return new FakeInfo(UUID.randomUUID(), name, TextColor.color(rgb), ruler, m, chunks, npc);
    }

    @Test
    void entriesFromFakeKrajeData() {
        final List<CountryList.Entry> entries = KrajeHook.entries(List.of(
            info("Aldoria", 0xE53935, "Maciej", 3, 24, false),
            info("Księstwo_Mazowsza", 0x3C44AA, "Bolesław", 0, 12, true),
            new OnlyName("Stary"),
            "nie kraj"));
        assertEquals(List.of(
            new CountryList.Entry("Aldoria", 0xE53935, false, "Maciej", 24, 3),
            new CountryList.Entry("Księstwo_Mazowsza", 0x3C44AA, true, "Bolesław", 12, 0),
            new CountryList.Entry("Stary", 0xFFFFFF, false, "?", -1, -1)), entries);
    }

    @Test
    void npcCountriesFirstThenPolishAlphabeticalOrder() {
        final List<CountryList.Entry> sorted = CountryList.sorted(List.of(
            entry("Żmudź", false), entry("Łęczyca", true), entry("aldoria", false), entry("Bory", false),
            entry("Lubusz", true), entry("Mazowsze", true), entry("Lechia", false)));
        assertEquals(List.of("Lubusz", "Łęczyca", "Mazowsze", "aldoria", "Bory", "Lechia", "Żmudź"),
            sorted.stream().map(CountryList.Entry::name).toList());
    }

    private static CountryList.Entry entry(final String name, final boolean npc) {
        return new CountryList.Entry(name, 0xFFFFFF, npc, "Władca", 1, npc ? 0 : 1);
    }

    @Test
    void pagingOf28PerPage() {
        final List<CountryList.Entry> all = new ArrayList<>();
        for (int i = 0; i < 61; i++) {
            all.add(entry(String.format("Kraj%02d", i), i % 5 == 0));
        }
        final List<CountryList.Entry> sorted = CountryList.sorted(all);

        final Pages.Page<CountryList.Entry> first = Pages.of(sorted, 0, 28);
        assertEquals(3, first.pages());
        assertEquals(28, first.items().size());
        assertFalse(first.hasPrevious());
        assertTrue(first.hasNext());
        assertFalse(first.canMove(-1));
        assertTrue(first.canMove(1));
        assertTrue(first.items().getFirst().npc(), "13 NPC countries open the first page");
        assertEquals("Kraj00", first.items().getFirst().name());

        final Pages.Page<CountryList.Entry> last = Pages.of(sorted, 2, 28);
        assertEquals(5, last.items().size());
        assertTrue(last.hasPrevious());
        assertFalse(last.hasNext());
        assertEquals("Kraj59", last.items().getLast().name());

        // out of range → clamped
        assertEquals(2, Pages.of(sorted, 99, 28).index());
        assertEquals(0, Pages.of(sorted, -3, 28).index());
    }

    @Test
    void pagingEdgeCases() {
        assertEquals(1, Pages.of(List.of(), 0, 28).pages());
        assertTrue(Pages.of(List.of(), 0, 28).items().isEmpty());
        final List<Integer> exactly28 = java.util.stream.IntStream.range(0, 28).boxed().toList();
        assertEquals(1, Pages.of(exactly28, 0, 28).pages());
        assertFalse(Pages.of(exactly28, 0, 28).hasNext());
        assertEquals(2, Pages.of(java.util.stream.IntStream.range(0, 29).boxed().toList(), 1, 28).pages());
        assertEquals(List.of(28), Pages.of(java.util.stream.IntStream.range(0, 29).boxed().toList(), 1, 28).items());
        assertEquals(1, Pages.of(exactly28, 0, 0).pages(), "no content slots");
    }

    @Test
    void everyDyeColourMapsToItself() {
        for (final DyeColor dye : DyeColor.values()) {
            assertEquals(dye, CountryList.nearestDye(dye.getColor().asRGB()), dye.name());
        }
    }

    @Test
    void countryColoursMapToTheNearestDye() {
        assertEquals(DyeColor.RED, CountryList.nearestDye(0xE53935));
        assertEquals(DyeColor.RED, CountryList.nearestDye(0xAA0000));
        assertEquals(DyeColor.WHITE, CountryList.nearestDye(0xFFFFFF));
        assertEquals(DyeColor.BLACK, CountryList.nearestDye(0x000000));
        assertEquals(DyeColor.LIME, CountryList.nearestDye(0x00FF00));
        assertEquals(DyeColor.BLUE, CountryList.nearestDye(0x0000FF));
        assertEquals(DyeColor.YELLOW, CountryList.nearestDye(0xFFFF55));
        assertEquals(DyeColor.ORANGE, CountryList.nearestDye(0xFF8800));
        assertEquals(DyeColor.PURPLE, CountryList.nearestDye(0x8E24AA));
        assertEquals(DyeColor.GREEN, CountryList.nearestDye(0x2E7D32));
    }

    @Test
    void bannerMaterialAndMark() {
        assertEquals("RED_BANNER", CountryList.bannerMaterial(DyeColor.RED));
        assertEquals("LIGHT_BLUE_BANNER", CountryList.bannerMaterial(DyeColor.LIGHT_BLUE));
        for (final DyeColor dye : DyeColor.values()) {
            assertTrue(org.bukkit.Material.matchMaterial(CountryList.bannerMaterial(dye)) != null, dye.name());
        }
        assertEquals(DyeColor.WHITE, CountryList.markColor(DyeColor.BLACK));
        assertEquals(DyeColor.WHITE, CountryList.markColor(DyeColor.BLUE));
        assertEquals(DyeColor.BLACK, CountryList.markColor(DyeColor.WHITE));
        assertEquals(DyeColor.BLACK, CountryList.markColor(DyeColor.YELLOW));
    }

    @Test
    void polishChunkPlurals() {
        assertEquals("1 chunk", CountryList.chunks(1));
        assertEquals("2 chunki", CountryList.chunks(2));
        assertEquals("4 chunki", CountryList.chunks(4));
        assertEquals("5 chunków", CountryList.chunks(5));
        assertEquals("12 chunków", CountryList.chunks(12));
        assertEquals("14 chunków", CountryList.chunks(14));
        assertEquals("22 chunki", CountryList.chunks(22));
        assertEquals("0 chunków", CountryList.chunks(0));
        assertEquals("?", CountryList.chunks(-1));
    }
}
