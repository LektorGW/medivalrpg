// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.ludnosc.model.AvoidSpot;
import pl.medivalrpg.ludnosc.model.ChunkRef;

/**
 * Rules of the construction fixes of 2026-11-03 (pure, unit tested): chunk tickets only for sites that can work,
 * players' orders first; roads and walls load only the chunks of their cells; the variant typed by the player is the
 * one built; leaves and snow in a plot are cleared, not an obstacle; the planner remembers refused plots; a site that
 * has not started yet is cancelled with a full refund; the real reason of a refused repair; mines dig through the
 * blocks they (and the cellars) put underground themselves.
 */
public final class BuildFixes {

    private BuildFixes() {
    }

    // ------------------------------------------------------------------ 1. chunk tickets

    /** Can a site use its chunks now. */
    public enum Readiness {
        /** Places blocks as soon as its chunks are loaded. */
        WORKING,
        /** Waits for a material: tickets only from what is left (haulers need both ends loaded). */
        WAITING,
        /** Cannot work at all now (daily levelling limit, repair during a siege, outside working hours): no ticket. */
        IDLE
    }

    public record TicketCandidate(UUID site, long createdAt, boolean playerCountry, Readiness readiness) {
    }

    /** Ticket order: working sites of players' countries, working NPC sites, then waiting ones; oldest first in each. */
    public static List<UUID> ticketOrder(final Collection<TicketCandidate> candidates) {
        final List<TicketCandidate> list = new ArrayList<>();
        for (final TicketCandidate c : candidates) {
            if (c.readiness() != Readiness.IDLE) {
                list.add(c);
            }
        }
        list.sort(Comparator.comparingInt((TicketCandidate c) -> c.readiness() == Readiness.WORKING ? 0 : 1)
            .thenComparingInt(c -> c.playerCountry() ? 0 : 1)
            .thenComparingLong(TicketCandidate::createdAt));
        final List<UUID> out = new ArrayList<>();
        for (final TicketCandidate c : list) {
            out.add(c.site());
        }
        return out;
    }

    /** A levelling site that reached the daily limit of its country cannot dig until tomorrow. */
    public static boolean levelLimitReached(final long today, final long levelDay, final int levelBlocks, final int perDay,
                                            final boolean forcedPlan) {
        return !forcedPlan && levelDay == today && levelBlocks >= perDay;
    }

    // ------------------------------------------------------------------ 2. chunks of road / wall cells

    /** Chunks of the cells ({x, z}) and of {@code margin} blocks around each (margin < 16), not their rectangle. */
    public static Set<ChunkRef> cellChunks(final String world, final Collection<int[]> cells, final int margin) {
        final Set<ChunkRef> out = new LinkedHashSet<>();
        for (final int[] c : cells) {
            for (final int dx : new int[] {-margin, margin}) {
                for (final int dz : new int[] {-margin, margin}) {
                    out.add(new ChunkRef(world, (c[0] + dx) >> 4, (c[1] + dz) >> 4));
                }
            }
            out.add(new ChunkRef(world, c[0] >> 4, c[1] >> 4));
        }
        return out;
    }

    // ------------------------------------------------------------------ 3. variants

    /**
     * The template to plan: a base type typed by the player gets the variant of the plot (same plot = same choice);
     * a variant typed explicitly ({@code chata_zrebowa}) is built as typed.
     */
    public static String chooseVariant(final String requested, final String base, final List<String> family, final String world,
                                       final int x, final int z) {
        if (family.size() < 2 || !family.contains(requested) || !requested.equals(base)) {
            return requested;
        }
        return family.get(VariantPick.start(family.size(), world, x, z));
    }

    // ------------------------------------------------------------------ 5. leaves and snow

    /**
     * Blocks the builders simply replace wherever they are in the plot (tree crowns, snow, scaffolding — a fallen
     * scaffold of an earlier site blocked the Golden Horde at 3363 46 -199): never an obstacle.
     */
    public static boolean clearable(final String material) {
        if (material == null) {
            return false;
        }
        final String m = material.toUpperCase(Locale.ROOT);
        return m.endsWith("_LEAVES") || m.equals("SNOW") || m.equals("SNOW_BLOCK") || m.equals("POWDER_SNOW") || m.equals("VINE")
            || m.endsWith("_SAPLING") || m.equals("MANGROVE_PROPAGULE") || m.equals("GLOW_LICHEN") || m.equals("CAVE_VINES")
            || m.equals("CAVE_VINES_PLANT") || m.equals("MOSS_CARPET") || m.equals("PALE_MOSS_CARPET")
            || m.equals("PALE_HANGING_MOSS") || m.equals("SCAFFOLDING");
    }

    // ------------------------------------------------------------------ 5b. soil (Minecraft 26.x tags)

    /**
     * Natural soil. Since Minecraft 26.x the {@code #minecraft:dirt} tag holds only dirt, coarse and rooted dirt —
     * grass, podzol and mycelium moved to {@code #minecraft:grass_blocks} — so {@code Tag.DIRT} alone made every
     * grass block a "player structure": a player's order on a meadow was refused with "Blok GRASS_BLOCK ... blokuje".
     */
    public static final Set<String> SOIL = Set.of("DIRT", "COARSE_DIRT", "ROOTED_DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM",
        "MUD", "MUDDY_MANGROVE_ROOTS", "MOSS_BLOCK", "PALE_MOSS_BLOCK", "DIRT_PATH");

    public static boolean soil(final String material) {
        return material != null && SOIL.contains(material.toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ 6. refused plots of the planner

    /** Active (not expired) refused plots, each type+place once (the latest kept), at most {@code max}, newest last. */
    public static List<AvoidSpot> remember(final List<AvoidSpot> old, final AvoidSpot spot, final long now, final int max) {
        final List<AvoidSpot> out = new ArrayList<>();
        if (old != null) {
            for (final AvoidSpot a : old) {
                if (a != null && a.until() > now && !a.samePlace(spot)) {
                    out.add(a);
                }
            }
        }
        out.add(spot);
        while (out.size() > Math.max(1, max)) {
            out.removeFirst();
        }
        return out;
    }

    public static List<AvoidSpot> active(final List<AvoidSpot> list, final String world, final long now) {
        final List<AvoidSpot> out = new ArrayList<>();
        if (list != null) {
            for (final AvoidSpot a : list) {
                if (a != null && a.until() > now && Objects.equals(world, a.world())) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 7. cancelling

    /** Nothing was done on the site yet (no earthworks column, no block): the whole price goes back. */
    public static boolean untouched(final int phase, final int cursor, final int terrainPhase) {
        return phase <= terrainPhase && cursor <= 0;
    }

    public static double cancelRefund(final boolean untouched, final double configured) {
        return untouched ? 1.0 : Math.max(0, Math.min(1, configured));
    }

    // ------------------------------------------------------------------ 8. repairs

    /** Why a repair cannot be ordered (Polish), null = it can. */
    public static String repairRefusal(final boolean reservedForKingdom, final boolean royalRebuild, final boolean noCountry,
                                       final boolean busy, final boolean noTemplate, final boolean noMoney, final String cost) {
        if (reservedForKingdom) {
            return "budynek jest zastrzeżony dla KRÓLESTWA";
        }
        if (royalRebuild) {
            return "trwa królewska przebudowa kraju";
        }
        if (noCountry) {
            return "budynek nie należy do żadnego kraju";
        }
        if (busy) {
            return "budynek jest już w naprawie albo w kolejce do naprawy";
        }
        if (noTemplate) {
            return "budynek nie ma szablonu budowniczych (postawiony przez graczy albo zgłoszony) — naprawcie go sami";
        }
        if (noMoney) {
            return "brak pieniędzy w skarbcu kraju (koszt " + cost + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------ 9. mines

    /** Materials the miners put into their own galleries (floor, walls, ceiling, posts, rails, light) and cellar seals. */
    public static final Set<String> GALLERY_BLOCKS = Set.of("COBBLESTONE", "COBBLESTONE_STAIRS", "COBBLED_DEEPSLATE", "OAK_LOG",
        "OAK_PLANKS", "RAIL", "TORCH", "WALL_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH");

    /** Natural rock the old rule refused although it is plain ground (desert mines). */
    public static final Set<String> NATURAL_EXTRA = Set.of("SANDSTONE", "RED_SANDSTONE");

    /**
     * A block in the way of the next gallery step that the miners may still dig: natural sandstone, or — deep under
     * the surface and outside any registered building — a block of the plugin's own galleries and cellar seals.
     */
    public static boolean mineMayDig(final String material, final int y, final int surfaceY, final boolean inOtherBuilding) {
        if (material == null) {
            return false;
        }
        final String m = material.toUpperCase(Locale.ROOT);
        if (NATURAL_EXTRA.contains(m)) {
            return true;
        }
        return GALLERY_BLOCKS.contains(m) && !inOtherBuilding && y < surfaceY - 2;
    }
}
