// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.planner.Underground;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.MaterialPalette;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Underground parts of buildings ({@link Underground}): cellars under houses, dungeons under the town hall, dungeon
 * with an escape tunnel under the castle, catacombs under the cathedral. Started automatically when such a building
 * is finished ({@code underground.features}), for older buildings by {@code /ludnosc podziemia kop} or the planner of
 * NPC countries. Dug a few blocks every {@link #PERIOD} ticks during work hours while the chunks are loaded; the plan
 * is deterministic, so only its progress index is stored ({@link CountryState#underground}). Main thread only.
 */
public final class UndergroundService {

    public static final long PERIOD = 10L;

    /** Record: {kind, hatchX, hatchY, hatchZ, dir, param, cursor, state (0 digging, 1 done)}. */
    static final int KIND = 0;
    static final int HX = 1;
    static final int HY = 2;
    static final int HZ = 3;
    static final int DIR = 4;
    static final int PARAM = 5;
    static final int CURSOR = 6;
    static final int STATE = 7;

    private final LudnoscPlugin plugin;
    /** Plans by building id (recomputed after a restart; deterministic). */
    private final Map<String, List<Underground.Op>> plans = new HashMap<>();

    public UndergroundService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    /** Feature configured for a building type, or null. */
    public Underground.Kind featureOf(final String type) {
        String k = this.plugin.settings().undergroundFeatures.get(type);
        if (k == null && type != null) { // look variants (variant-of) get the feature of their base type
            k = this.plugin.settings().undergroundFeatures.get(this.plugin.templates().baseType(type));
        }
        if (k == null) {
            return null;
        }
        try {
            return Underground.Kind.valueOf(k.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public static String kindName(final Underground.Kind k) {
        return switch (k) {
            case CELLAR -> "piwnica";
            case DUNGEON -> "lochy";
            case TUNNEL -> "lochy z tunelem ucieczkowym";
            case CATACOMBS -> "katakumby";
        };
    }

    /** The record of a building, or null. */
    public int[] record(final Building b) {
        final CountryState st = b.countryId() == null ? null : this.data().stateIfPresent(b.countryId());
        return st == null ? null : st.underground().get(b.id().toString());
    }

    /** Called when a building is finished. */
    public void onBuilt(final Building b) {
        if (this.plugin.settings().undergroundEnabled && this.featureOf(b.type()) != null) {
            this.start(b, this.featureOf(b.type()));
        }
    }

    /** Result of a start. */
    public enum Start { STARTED, NOT_SUPPORTED, EXISTS, NO_PLACE, FUNDS }

    /** Plans and starts the feature of a building (no payment). */
    public Start start(final Building b, final Underground.Kind wanted) {
        if (b.countryId() == null || wanted == null) {
            return Start.NOT_SUPPORTED;
        }
        if (this.record(b) != null) {
            return Start.EXISTS;
        }
        final World w = Bukkit.getWorld(b.box().world());
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(b.type(), b.rotation(), b.dye(),
            MaterialPalette.decode(b.level()));
        if (w == null || rt == null) {
            return Start.NOT_SUPPORTED;
        }
        final BuildingTemplate t = rt.template();
        final int g = t.meta().groundLayer();
        final int[] h = Underground.hatch((x, y, z) -> t.inside(x, y, z) ? t.state(x, y, z) : null, t.width(), t.depth(), g);
        if (h == null) {
            return this.fail(b, wanted);
        }
        final int hx = b.box().minX() + h[0];
        final int hy = b.box().minY() + g;
        final int hz = b.box().minZ() + h[1];
        if (hy - 12 <= w.getMinHeight()) {
            return this.fail(b, wanted);
        }
        // the preferred direction first (towards the centre), then the others
        for (int k = 0; k < 4; k++) {
            final int dir = Math.floorMod(h[2] + k, 4);
            Underground.Kind kind = wanted;
            int param = 0;
            if (kind == Underground.Kind.TUNNEL) {
                final int[] exit = Underground.tunnelExit(hx, hz, dir);
                final boolean own = this.owned(b.countryId(), w, exit[0], exit[1]);
                final int top = w.isChunkLoaded(exit[0] >> 4, exit[1] >> 4)
                    ? w.getHighestBlockYAt(exit[0], exit[1], HeightMap.MOTION_BLOCKING_NO_LEAVES) : Integer.MIN_VALUE;
                if (!own || top < hy - 2 || top > hy + 24 || this.plugin.data().buildingAt(w.getName(), exit[0], top, exit[1]) != null) {
                    kind = Underground.Kind.DUNGEON; // no safe exit this way: the dungeon alone
                } else {
                    param = top;
                }
            }
            final List<Underground.Op> ops = Underground.plan(kind, hx, hy, hz, dir, param);
            if (this.allOwned(b.countryId(), w, ops)) {
                final int[] rec = {kind.ordinal(), hx, hy, hz, dir, param, 0, 0};
                this.data().state(b.countryId()).underground().put(b.id().toString(), rec);
                this.plans.put(b.id().toString(), ops);
                this.data().markDirty();
                return Start.STARTED;
            }
        }
        return this.fail(b, wanted);
    }

    /** Remembers that the building has no room for the feature (not retried; state 2). */
    private Start fail(final Building b, final Underground.Kind kind) {
        this.data().state(b.countryId()).underground().put(b.id().toString(), new int[] {kind.ordinal(), 0, 0, 0, 0, 0, 0, 2});
        this.data().markDirty();
        return Start.NO_PLACE;
    }

    private boolean owned(final UUID country, final World w, final int x, final int z) {
        return this.plugin.kraje().getCountryAt(w.getName(), x >> 4, z >> 4).map(KrajInfo::id).map(country::equals).orElse(false);
    }

    private boolean allOwned(final UUID country, final World w, final List<Underground.Op> ops) {
        final Set<Long> seen = new HashSet<>();
        for (final Underground.Op op : ops) {
            final long c = ((long) (op.x() >> 4) << 32) | ((op.z() >> 4) & 0xFFFFFFFFL);
            if (seen.add(c) && !this.owned(country, w, op.x(), op.z())) {
                return false;
            }
        }
        return true;
    }

    private List<Underground.Op> plan(final String id, final int[] rec) {
        return this.plans.computeIfAbsent(id, k -> Underground.plan(Underground.Kind.values()[rec[KIND]], rec[HX], rec[HY], rec[HZ],
            rec[DIR], rec[PARAM]));
    }

    /** Digging (every {@link #PERIOD} ticks). */
    public void tick() {
        if (!this.plugin.settings().undergroundEnabled) {
            return;
        }
        for (final Map.Entry<UUID, CountryState> ce : this.data().states().entrySet()) {
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(ce.getKey()))continue;
            if (ce.getValue().underground == null || ce.getValue().underground.isEmpty()) {
                continue;
            }
            for (final var it = ce.getValue().underground.entrySet().iterator(); it.hasNext();) {
                final var e = it.next();
                final int[] rec = e.getValue();
                if (rec[STATE] != 0) {
                    continue;
                }
                final Building b = this.building(e.getKey());
                if (b == null || !ce.getKey().equals(b.countryId())) {
                    it.remove();
                    this.plans.remove(e.getKey());
                    this.data().markDirty();
                    continue;
                }
                final World w = Bukkit.getWorld(b.box().world());
                if (w == null || !this.plugin.settings().workTime(w.getTime())) {
                    continue;
                }
                this.step(ce.getKey(), b, rec, w, this.plan(e.getKey(), rec));
            }
        }
    }

    private Building building(final String id) {
        try {
            return this.data().building(UUID.fromString(id));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private void step(final UUID country, final Building b, final int[] rec, final World w, final List<Underground.Op> ops) {
        int budget = this.plugin.settings().undergroundOpsPerStep;
        Block last = null;
        while (budget > 0 && rec[CURSOR] < ops.size()) {
            final Underground.Op op = ops.get(rec[CURSOR]);
            if (!w.isChunkLoaded(op.x() >> 4, op.z() >> 4)) {
                break; // waits for the chunk
            }
            final Block block = w.getBlockAt(op.x(), op.y(), op.z());
            rec[CURSOR]++;
            switch (op.type()) {
                case SEAL -> {
                    final Material m = block.getType();
                    if (block.isPassable() || block.isLiquid() || m.hasGravity()) {
                        block.setType(Material.COBBLESTONE, false);
                        budget--;
                    }
                }
                case DIG -> {
                    if (!block.getType().isAir() && !(block.getState() instanceof TileState)) {
                        block.setType(Material.AIR, false);
                        budget--;
                        last = block;
                    }
                }
                case PLACE -> {
                    final BlockData data;
                    try {
                        data = Bukkit.createBlockData(op.state());
                    } catch (final IllegalArgumentException ex) {
                        continue;
                    }
                    block.setBlockData(data, false);
                    if (op.state().contains("bars")) {
                        WorldOps.fixConnections(block);
                    }
                    budget--;
                    last = block;
                }
            }
        }
        this.data().markDirty();
        if (last != null && this.plugin.settings().effects) {
            final Location at = last.getLocation().add(0.5, 0.5, 0.5);
            w.playSound(at, Sound.BLOCK_STONE_BREAK, 0.6f, 0.9f);
            w.spawnParticle(Particle.BLOCK, at, 8, 0.3, 0.3, 0.3, 0, Material.STONE.createBlockData());
        }
        if (rec[CURSOR] >= ops.size()) {
            rec[STATE] = 1;
            this.plans.remove(b.id().toString());
            this.plugin.notifyCountry(country, "underground-done",
                Placeholder.unparsed("feature", kindName(Underground.Kind.values()[rec[KIND]])),
                Placeholder.parsed("name", this.plugin.construction().displayName(b.type())));
        }
    }

    /** Puts the hatch back after the building was modernised or repaired over it. */
    public void restoreHatch(final Building b) {
        final int[] rec = this.record(b);
        final World w = Bukkit.getWorld(b.box().world());
        if (rec == null || rec[STATE] != 1 || w == null || !w.isChunkLoaded(rec[HX] >> 4, rec[HZ] >> 4)) {
            return;
        }
        final Block hatch = w.getBlockAt(rec[HX], rec[HY], rec[HZ]);
        if (!hatch.getType().name().endsWith("_TRAPDOOR")) {
            final String face = new String[] {"north", "east", "south", "west"}[Math.floorMod(rec[DIR], 4)];
            hatch.setBlockData(Bukkit.createBlockData("minecraft:spruce_trapdoor[facing=" + face
                + ",half=top,open=false,powered=false,waterlogged=false]"), false);
        }
    }

    /** Bounding boxes of the underground works being dug: liquids may not flow in. */
    public List<pl.medivalrpg.ludnosc.model.BlockBox> guardBoxes() {
        final List<pl.medivalrpg.ludnosc.model.BlockBox> out = new ArrayList<>();
        for (final Map.Entry<UUID, CountryState> ce : this.data().states().entrySet()) {
            if (ce.getValue().underground == null) {
                continue;
            }
            for (final Map.Entry<String, int[]> e : ce.getValue().underground.entrySet()) {
                final int[] rec = e.getValue();
                final Building b = rec[STATE] == 0 ? this.building(e.getKey()) : null;
                if (b == null) {
                    continue;
                }
                int minX = rec[HX];
                int minY = rec[HY];
                int minZ = rec[HZ];
                int maxX = rec[HX];
                int maxY = rec[HY];
                int maxZ = rec[HZ];
                for (final Underground.Op op : this.plan(e.getKey(), rec)) {
                    minX = Math.min(minX, op.x());
                    minY = Math.min(minY, op.y());
                    minZ = Math.min(minZ, op.z());
                    maxX = Math.max(maxX, op.x());
                    maxY = Math.max(maxY, op.y());
                    maxZ = Math.max(maxZ, op.z());
                }
                out.add(new pl.medivalrpg.ludnosc.model.BlockBox(b.box().world(), minX, minY, minZ, maxX, maxY, maxZ));
            }
        }
        return out;
    }

    /** Finished cellars of a country (each adds {@code underground.cellar-capacity} to the granary). */
    public int cellars(final UUID country) {
        final CountryState st = this.data().stateIfPresent(country);
        if (st == null || st.underground == null) {
            return 0;
        }
        int n = 0;
        for (final var e : st.underground.entrySet()) {
            if (e.getValue()[STATE] == 1 && e.getValue()[KIND] == Underground.Kind.CELLAR.ordinal() && this.building(e.getKey()) != null) {
                n++;
            }
        }
        return n;
    }

    /** Progress in percent of a record. */
    public int percent(final String buildingId, final int[] rec) {
        if (rec[STATE] == 1) {
            return 100;
        }
        final int size = this.plan(buildingId, rec).size();
        return size == 0 ? 100 : Math.min(99, rec[CURSOR] * 100 / size);
    }

    /** Price of the feature for an existing building. */
    public long cost(final Underground.Kind k) {
        return this.plugin.settings().undergroundCost.getOrDefault(k.name().toLowerCase(Locale.ROOT), 1000L);
    }

    /** Paid start for an existing building ({@code /ludnosc podziemia kop}, planner). */
    public Start order(final Building b) {
        final Underground.Kind kind = this.featureOf(b.type());
        if (kind == null) {
            return Start.NOT_SUPPORTED;
        }
        if (this.record(b) != null) {
            return Start.EXISTS;
        }
        final long cost = this.cost(kind);
        final SkarbiecApi eco = this.plugin.skarbiec();
        if (cost > 0 && (eco == null || !eco.withdraw(SkarbiecApi.countryAccount(b.countryId()), cost,
            "Ludnosc: " + kindName(kind)).ok())) {
            return Start.FUNDS;
        }
        Start s;
        try {
            s = this.start(b, kind);
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(java.util.logging.Level.WARNING, "Podziemia: start " + kindName(kind) + " przerwany", e);
            s = Start.NOT_SUPPORTED; // paid already: refunded below
        }
        if (s != Start.STARTED && cost > 0
            && !eco.deposit(SkarbiecApi.countryAccount(b.countryId()), cost, "Ludnosc: zwrot — " + kindName(kind)).ok()) {
            this.plugin.getLogger().severe("Podziemia: NIE zwrócono " + cost + " md krajowi " + b.countryId());
        }
        return s;
    }

    /** Planner: one underground feature for an older building that lacks it, within the budget. True when started. */
    public boolean autoOrder(final UUID country, final long budget) {
        if (!this.plugin.settings().undergroundEnabled) {
            return false;
        }
        for (final Building b : this.data().buildingsOf(country)) {
            final int[] rec = this.record(b);
            if (rec != null && rec[STATE] == 0) {
                return false; // one at a time
            }
        }
        for (final Building b : new ArrayList<>(this.data().buildingsOf(country))) {
            final Underground.Kind k = this.featureOf(b.type());
            if (k != null && this.record(b) == null && this.cost(k) <= budget && this.order(b) == Start.STARTED) {
                return true;
            }
        }
        return false;
    }
}
