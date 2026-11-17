// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.logic.HaulPlan;
import pl.medivalrpg.ludnosc.logic.MaterialNeeds;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * Haulers ("tragarze"): the missing links of the physical economy. A visible NPC carries real items between the
 * registered containers of a country:
 * <ul>
 *   <li>wood and stone from the stores to the chest of a construction site that still needs them,</li>
 *   <li>harvest and fish from fields / harbours (or the warehouse) to the granary, where they become the food stock
 *       that the daily meal and the population growth eat,</li>
 *   <li>the output of a filling sawmill / quarry / mine store into the country's warehouse (so the work goes on),</li>
 *   <li>the inputs of the workshops: raw iron and coal to the smithy, coal / charcoal (or logs to burn into charcoal)
 *       to a kiln that has sand or clay, wheat to the sheepfold (hay bales, lambs); their finished goods (glass, bricks,
 *       terracotta, wool, hay, iron, torches) go to the building sites like wood and stone, or to the warehouse.</li>
 * </ul>
 * The transfer is one main-thread step on arrival: both containers are re-validated, the exact amount is taken from the
 * source and put into the target (stack limits respected). Nothing is ever "in transit" in saved data, so a restart or
 * an unloaded chunk only cancels the walk; items never appear or vanish. Both ends must be loaded.
 */
public final class LogisticsService {

    /** Service period in game ticks. */
    public static final long PERIOD = 10L;
    /** Version of the {@link #flowData} structure: 2 = + version, workplaces[].daily, sites[].waitingSince/waitingFor, workshops, chains. */
    public static final int FLOW_VERSION = 2;
    private static final int RECENT = 8;

    /** A delivery target / source: a registered store (by its parts) or a plain container position. */
    private record End(String label, BlockPos pos, List<BlockPos> parts) {
        boolean store() {
            return this.parts != null;
        }
    }

    private static final class Haul {
        final UUID id = UUID.randomUUID();
        UUID country;
        String kind;
        End from;
        End to;
        Material item;
        int amount;
        long deadline;
        boolean granary;
        final BuilderCrew crew = new BuilderCrew();
    }

    private final LudnoscPlugin plugin;
    private final Map<UUID, Haul> hauls = new LinkedHashMap<>();
    private final Map<UUID, Deque<String>> recent = new HashMap<>();
    private final Map<String, String> hintCache = new HashMap<>();
    private long hintAt;
    private long ticks;
    private long turn;

    public LogisticsService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private LudnoscConfig.Logistics cfg() {
        return this.plugin.settings().logistics;
    }

    // ------------------------------------------------------------------ ticking

    /** Every {@link #PERIOD} ticks (main thread). */
    public void tick() {
        this.ticks++;
        final long now = System.currentTimeMillis();
        for (final var it = this.hauls.values().iterator(); it.hasNext();) {
            final Haul h = it.next();
            if (this.advance(h, now)) {
                h.crew.remove();
                it.remove();
            }
        }
        if (!this.cfg().enabled()) {
            return;
        }
        if (this.ticks % 4 == 0) {
            for (final UUID country : List.copyOf(this.plugin.data().states().keySet())) {
                this.absorbGranaries(country);
            }
        }
        if (this.ticks % 8 == 0) {
            final List<ConstructionService.MaterialDemand> demands = this.plugin.construction().materialDemands();
            for (final UUID country : List.copyOf(this.plugin.data().states().keySet())) {
                if (this.plugin.royalRebuild() != null && this.plugin.royalRebuild().blocks(country)) {
                    continue;
                }
                try {
                    this.plan(country, demands);
                } catch (final RuntimeException e) {
                    this.plugin.getLogger().warning("Tragarze kraju " + country + ": " + e);
                }
            }
        }
    }

    /** Every two ticks, with the builders. */
    public void move(final double step) {
        final double s = this.cfg().walkSpeed() * 0.1;
        for (final Haul h : this.hauls.values()) {
            h.crew.move(s);
        }
    }

    public boolean isHauler(final Entity e) {
        for (final Haul h : this.hauls.values()) {
            if (h.crew.contains(e.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        for (final Haul h : this.hauls.values()) {
            h.crew.remove();
        }
        this.hauls.clear();
    }

    /** True when the haul is over (delivered, cancelled or timed out). */
    private boolean advance(final Haul h, final long now) {
        if (h.crew.bodies.isEmpty()) {
            return true;
        }
        h.crew.prune();
        final BuilderCrew.Body body = h.crew.bodies.isEmpty() ? null : h.crew.bodies.getFirst();
        if (body == null) {
            return true; // the chunk under him unloaded: the goods never left the source
        }
        if (now > h.deadline) {
            this.log(h.country, "Tragarz nie doszedł do celu (" + h.to.label() + ") — towar został w " + h.from.label());
            return true;
        }
        if (body.target == null || body.pos.distanceSquared(body.target) > 2.5 * 2.5) {
            return false;
        }
        if (this.storeBusy(h.from) || this.storeBusy(h.to)) {
            return false; // a production commit is pending on a store: wait next to it (until the deadline)
        }
        final int moved = this.transfer(h);
        final World w = body.pos.getWorld();
        if (moved > 0) {
            h.crew.swing(body);
            if (this.plugin.settings().effects) {
                w.playSound(body.pos, Sound.BLOCK_BARREL_CLOSE, 0.6f, 1f);
            }
            this.log(h.country, moved + "× " + itemName(h.item) + ": " + h.from.label() + " → " + h.to.label());
        }
        return true;
    }

    // ------------------------------------------------------------------ planning

    private int active(final UUID country) {
        int n = 0;
        for (final Haul h : this.hauls.values()) {
            if (h.country.equals(country)) {
                n++;
            }
        }
        return n;
    }

    private boolean busyEnd(final BlockPos pos) {
        for (final Haul h : this.hauls.values()) {
            if (h.to.pos().equals(pos) || h.from.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private String storeLabel(final ProductionStorageService.StoreView v) {
        final Building b = this.plugin.production().key(v.key());
        final String what = b == null ? "magazyn domyślny" : b.function().displayName();
        return what + " (" + v.at().x() + " " + v.at().y() + " " + v.at().z() + ")";
    }

    private BuildingFunction function(final ProductionStorageService.StoreView v) {
        final Building b = this.plugin.production().key(v.key());
        return b == null ? null : b.function();
    }

    private static double dist(final BlockPos a, final BlockPos b) {
        return a.world().equals(b.world()) ? Math.hypot(a.x() - b.x(), a.z() - b.z()) + Math.abs(a.y() - b.y()) : Double.MAX_VALUE;
    }

    /** Plans at most one new trip per country and pass: site supply first, then food, then emptying full stores. */
    private void plan(final UUID country, final List<ConstructionService.MaterialDemand> demands) {
        if (this.hauls.size() >= this.cfg().total() || this.active(country) >= this.cfg().perCountry()) {
            return;
        }
        final List<ProductionStorageService.StoreView> views = this.plugin.production().views(country);
        if (this.planSites(country, views, demands)) {
            return;
        }
        // food and the workshops' inputs take turns, so a field that always has a harvest never starves a kiln
        final boolean inputsFirst = (this.turn++ & 1) == 0;
        if (inputsFirst ? this.planInputs(country, views) || this.planFood(country, views)
            : this.planFood(country, views) || this.planInputs(country, views)) {
            return;
        }
        this.planUnload(country, views);
    }

    private boolean planSites(final UUID country, final List<ProductionStorageService.StoreView> views,
                              final List<ConstructionService.MaterialDemand> demands) {
        for (final ConstructionService.MaterialDemand d : demands) {
            if (!d.country().equals(country) || this.busyEnd(d.chest())) {
                continue;
            }
            ProductionStorageService.StoreView best = null;
            Material item = null;
            int have = 0;
            double bestD = Double.MAX_VALUE;
            for (final ProductionStorageService.StoreView v : views) {
                final double distance = dist(v.at(), d.chest());
                if (distance > this.cfg().maxDistance() || distance >= bestD) {
                    continue;
                }
                final Map<Material, Integer> counts = new HashMap<>();
                final BuildingFunction vf = this.function(v);
                final boolean shop = this.workshopStore(v); // a workshop's inputs (logs for charcoal, sand) stay there
                for (final ItemStack i : v.inventory().getStorageContents()) {
                    if (i != null && !i.hasItemMeta() && MaterialNeeds.supply(i.getType().name(), d.group()) > 0
                        && (!shop || pl.medivalrpg.ludnosc.logic.Workshops.output(vf, i.getType().name()))) {
                        counts.merge(i.getType(), i.getAmount(), Integer::sum);
                    }
                }
                final var top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
                if (top != null) {
                    best = v;
                    item = top.getKey();
                    have = top.getValue();
                    bestD = distance;
                }
            }
            if (best == null) {
                continue;
            }
            final int per = MaterialNeeds.supply(item.name(), d.group());
            final int amount = HaulPlan.amount(d.units(), per, have, Math.min(this.cfg().load(), item.getMaxStackSize()));
            if (amount > 0 && this.start(country, "budowa", new End(this.storeLabel(best), best.at(), best.binding().parts()),
                new End("plac: " + d.name(), d.chest(), null), item, amount, false)) {
                return true;
            }
        }
        return false;
    }

    private boolean planFood(final UUID country, final List<ProductionStorageService.StoreView> views) {
        final GranaryService granary = this.plugin.granary();
        final long room = granary.capacity(country) - granary.food(country);
        if (room <= 0) {
            return false;
        }
        // granary targets: stores of granary buildings, then marked granary chests
        final List<End> targets = new ArrayList<>();
        for (final ProductionStorageService.StoreView v : views) {
            if (this.function(v) == BuildingFunction.GRANARY) {
                targets.add(new End(this.storeLabel(v), v.at(), v.binding().parts()));
            }
        }
        final CountryState st = this.plugin.data().stateIfPresent(country);
        if (st != null) {
            for (final BlockPos p : st.chests()) {
                targets.add(new End("spichlerz (" + p.x() + " " + p.y() + " " + p.z() + ")", p, null));
            }
        }
        if (targets.isEmpty()) {
            return false;
        }
        for (final ProductionStorageService.StoreView v : views) {
            if (this.function(v) == BuildingFunction.GRANARY || this.busyEnd(v.at())) {
                continue;
            }
            for (final ItemStack i : v.inventory().getStorageContents()) {
                if (i == null || i.hasItemMeta()) {
                    continue;
                }
                final int value = this.plugin.settings().foodValue(i.getType());
                if (value <= 0) {
                    continue;
                }
                final End to = targets.stream().filter(t -> !t.pos().equals(v.at()) && dist(t.pos(), v.at()) <= this.cfg().maxDistance())
                    .min(Comparator.comparingDouble(t -> dist(t.pos(), v.at()))).orElse(null);
                if (to == null) {
                    return false;
                }
                final int amount = (int) Math.min(Math.min(this.cfg().load(), i.getMaxStackSize()), Math.min(i.getAmount(), room / value));
                if (amount > 0 && this.start(country, "jedzenie", new End(this.storeLabel(v), v.at(), v.binding().parts()), to,
                    i.getType(), amount, true)) {
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** A store bound to a workshop (glassworks, brickworks, sheepfold, smithy): its inputs are never taken away. */
    private boolean workshopStore(final ProductionStorageService.StoreView v) {
        return pl.medivalrpg.ludnosc.logic.Workshops.workshop(this.function(v));
    }

    /** Brings what a workshop is missing (raw iron, coal / charcoal, logs, wheat) from another store of the country. */
    private boolean planInputs(final UUID country, final List<ProductionStorageService.StoreView> views) {
        final WorkshopService shops = this.plugin.workshops();
        if (shops == null) {
            return false;
        }
        // fuel priority: the kilns (glassworks, brickworks) first, then the sheepfold, the smithy last
        final List<Building> order = new ArrayList<>();
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (shops.handles(b)) {
                order.add(b);
            }
        }
        order.sort(Comparator.comparingInt(LogisticsService::inputRank));
        for (final Building b : order) {
            final ProductionStorageService.StoreView target = this.plugin.production().storeOf(b);
            if (target == null || this.busyEnd(target.at())) {
                continue;
            }
            for (final pl.medivalrpg.ludnosc.logic.Workshops.Want want : shops.wants(b, target.inventory())) {
                ProductionStorageService.StoreView best = null;
                Material item = null;
                int have = 0;
                double bestD = Double.MAX_VALUE;
                for (final ProductionStorageService.StoreView v : views) {
                    final double distance = dist(v.at(), target.at());
                    if (v.binding().parts().equals(target.binding().parts()) || this.busyEnd(v.at())
                        || distance > this.cfg().maxDistance() || distance >= bestD) {
                        continue;
                    }
                    final BuildingFunction vf = this.function(v);
                    final boolean shop = this.workshopStore(v); // from another workshop only its products (smithy's charcoal)
                    final Map<Material, Integer> counts = new HashMap<>();
                    for (final ItemStack i : v.inventory().getStorageContents()) {
                        if (i != null && !i.hasItemMeta() && want.input().matches(i.getType().name())
                            && (!shop || pl.medivalrpg.ludnosc.logic.Workshops.output(vf, i.getType().name()))) {
                            counts.merge(i.getType(), i.getAmount(), Integer::sum);
                        }
                    }
                    final var top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
                    if (top != null) {
                        best = v;
                        item = top.getKey();
                        have = top.getValue();
                        bestD = distance;
                    }
                }
                if (best == null) {
                    continue;
                }
                final int amount = Math.min(Math.min(want.amount(), have), Math.min(this.cfg().load(), item.getMaxStackSize()));
                if (amount > 0 && this.start(country, "zakład", new End(this.storeLabel(best), best.at(), best.binding().parts()),
                    new End(this.storeLabel(target), target.at(), target.binding().parts()), item, amount, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int inputRank(final Building b) {
        return switch (b.function()) {
            case GLASSWORKS, BRICKWORKS -> 0;
            case SHEEPFOLD -> 1;
            default -> 2;
        };
    }

    private void planUnload(final UUID country, final List<ProductionStorageService.StoreView> views) {
        final List<ProductionStorageService.StoreView> stores = new ArrayList<>();
        for (final ProductionStorageService.StoreView v : views) {
            final BuildingFunction f = this.function(v);
            if (f == null || f == BuildingFunction.WAREHOUSE) {
                stores.add(v);
            }
        }
        for (final ProductionStorageService.StoreView v : views) {
            final BuildingFunction f = this.function(v);
            final boolean shop = pl.medivalrpg.ludnosc.logic.Workshops.workshop(f);
            if (f != BuildingFunction.SAWMILL && f != BuildingFunction.QUARRY && f != BuildingFunction.MINE && !shop || this.busyEnd(v.at())) {
                continue;
            }
            final ItemStack[] items = v.inventory().getStorageContents();
            int used = 0;
            final Map<Material, Integer> counts = new HashMap<>();
            for (final ItemStack i : items) {
                if (i != null && !i.getType().isAir()) {
                    used++;
                    if (!i.hasItemMeta() && (shop ? pl.medivalrpg.ludnosc.logic.Workshops.output(f, i.getType().name())
                        : i.getType() != Material.TORCH && i.getType() != Material.SOUL_TORCH)) {
                        counts.merge(i.getType(), i.getAmount(), Integer::sum); // a workshop keeps its inputs and fuel
                    }
                }
            }
            if (!HaulPlan.shouldUnload(used, items.length, this.cfg().unloadFill()) || counts.isEmpty()) {
                continue;
            }
            final var top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            final ProductionStorageService.StoreView to = stores.stream()
                .filter(s -> !s.binding().parts().equals(v.binding().parts()) && dist(s.at(), v.at()) <= this.cfg().maxDistance()
                    && ProductionStorageService.plan(s.inventory().getStorageContents(), List.of(new ItemStack(top.getKey(), 1)), List.of(),
                        s.inventory().getMaxStackSize()) != null)
                .min(Comparator.comparingDouble(s -> dist(s.at(), v.at()))).orElse(null);
            if (to == null) {
                continue;
            }
            final int amount = Math.min(top.getValue(), Math.min(this.cfg().load(), top.getKey().getMaxStackSize()));
            if (this.start(country, "magazyn", new End(this.storeLabel(v), v.at(), v.binding().parts()),
                new End(this.storeLabel(to), to.at(), to.binding().parts()), top.getKey(), amount, false)) {
                return;
            }
        }
    }

    /** Spawns the hauler at the source with the goods in hand, walking to the target. */
    private boolean start(final UUID country, final String kind, final End from, final End to, final Material item, final int amount,
                          final boolean granary) {
        final World w = Bukkit.getWorld(from.pos().world());
        if (w == null || !from.pos().world().equals(to.pos().world()) || !w.isChunkLoaded(from.pos().x() >> 4, from.pos().z() >> 4)
            || !w.isChunkLoaded(to.pos().x() >> 4, to.pos().z() >> 4)) {
            return false;
        }
        final Haul h = new Haul();
        h.country = country;
        h.kind = kind;
        h.from = from;
        h.to = to;
        h.item = item;
        h.amount = amount;
        h.granary = granary;
        final Block src = w.getBlockAt(from.pos().x(), from.pos().y(), from.pos().z());
        final Block dst = w.getBlockAt(to.pos().x(), to.pos().y(), to.pos().z());
        final Location spawn = WorldOps.standSpot(w, src, null);
        h.crew.ensure(this.plugin, h.id, spawn, 1, "Tragarz — " + kind);
        if (h.crew.bodies.isEmpty()) {
            return false;
        }
        final BuilderCrew.Body body = h.crew.bodies.getFirst();
        if (body.entity() instanceof LivingEntity le) {
            le.customName(Component.text("Tragarz", NamedTextColor.GRAY).append(Component.text(" " + this.plugin.citizens().nextName(),
                NamedTextColor.WHITE)));
            if (le.getEquipment() != null && item.isItem()) {
                le.getEquipment().setItemInMainHand(new ItemStack(item));
            }
        }
        body.held = item;
        body.target = WorldOps.standSpot(w, dst, spawn);
        body.look = dst.getLocation().add(0.5, 0.5, 0.5);
        h.deadline = System.currentTimeMillis()
            + HaulPlan.deadlineMillis(spawn.distance(body.target), this.cfg().walkSpeed());
        this.hauls.put(h.id, h);
        if (this.plugin.settings().effects) {
            w.playSound(src.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.1f);
        }
        return true;
    }

    // ------------------------------------------------------------------ transfer

    private boolean storeBusy(final End end) {
        return end.store() && this.plugin.production().busy(new pl.medivalrpg.ludnosc.model.ProductionBinding(null, end.parts()));
    }

    private Inventory inventory(final UUID country, final End end) {
        if (end.store()) {
            for (final ProductionStorageService.StoreView v : this.plugin.production().views(country)) {
                if (v.binding().parts().equals(end.parts())) {
                    return v.inventory();
                }
            }
            return null;
        }
        final World w = Bukkit.getWorld(end.pos().world());
        if (w == null || !w.isChunkLoaded(end.pos().x() >> 4, end.pos().z() >> 4)
            || this.plugin.supply() != null && this.plugin.supply().locked(end.pos())) {
            return null;
        }
        final Block b = w.getBlockAt(end.pos().x(), end.pos().y(), end.pos().z());
        if (!(b.getState(false) instanceof Container c) || !this.plugin.kraje().getCountryAt(w.getName(), b.getX() >> 4, b.getZ() >> 4)
            .map(k -> k.id().equals(country)).orElse(false)) {
            return null;
        }
        return c.getInventory();
    }

    /** Moves the goods (re-validated, exact, stack limits respected); returns the number of items moved. */
    private int transfer(final Haul h) {
        final Inventory src = this.inventory(h.country, h.from);
        final Inventory dst = this.inventory(h.country, h.to);
        if (src == null || dst == null || src.equals(dst) || this.plugin.supply() != null
            && (this.plugin.supply().locked(src) || this.plugin.supply().locked(dst))) {
            this.log(h.country, "Tragarz zawrócił: " + (src == null ? h.from.label() : h.to.label()) + " niedostępny");
            return 0;
        }
        final int moved = move(src, dst, h.item, h.amount);
        if (moved <= 0) {
            this.log(h.country, "Tragarz nie przeniósł " + itemName(h.item) + ": brak towaru lub miejsca w " + h.to.label());
            return 0;
        }
        if (h.granary) {
            this.plugin.granary().absorbStore(h.country, dst);
        }
        this.plugin.production().touched(h.country);
        return moved;
    }

    /** Exact move of up to {@code amount} plain items: both inventories change together or not at all. */
    static int move(final Inventory src, final Inventory dst, final Material item, final int amount) {
        int available = 0;
        for (final ItemStack i : src.getStorageContents()) {
            if (i != null && i.getType() == item && !i.hasItemMeta()) {
                available += i.getAmount();
            }
        }
        int n = Math.min(amount, Math.min(available, item.getMaxStackSize()));
        while (n > 0) {
            final ItemStack[] srcAfter = ProductionStorageService.plan(src.getStorageContents(), List.of(),
                List.of(new ItemStack(item, n)), src.getMaxStackSize());
            final ItemStack[] dstAfter = ProductionStorageService.plan(dst.getStorageContents(), List.of(new ItemStack(item, n)),
                List.of(), dst.getMaxStackSize());
            if (srcAfter != null && dstAfter != null) {
                src.setStorageContents(srcAfter);
                dst.setStorageContents(dstAfter);
                return n;
            }
            n = n / 2;
        }
        return 0;
    }

    /** Leftovers of a finished site's chest go into the nearest store of the country with room (main thread). */
    public void returnLeftovers(final UUID country, final Inventory chest, final Location at) {
        final BlockPos here = new BlockPos(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        final List<ProductionStorageService.StoreView> views = new ArrayList<>(this.plugin.production().views(country));
        views.sort(Comparator.comparingDouble(v -> dist(v.at(), here)));
        int moved = 0;
        for (final ItemStack item : chest.getStorageContents()) {
            if (item == null || item.getType().isAir() || item.hasItemMeta()) {
                continue;
            }
            int left = item.getAmount();
            for (final ProductionStorageService.StoreView v : views) {
                if (left <= 0 || dist(v.at(), here) > this.cfg().maxDistance()) {
                    break;
                }
                final int m = move(chest, v.inventory(), item.getType(), left);
                left -= m;
                moved += m;
            }
        }
        if (moved > 0) {
            this.plugin.production().touched(country);
            this.log(country, "Resztki z placu budowy (" + moved + " szt.) odniesione do magazynu");
        }
    }

    /** Food lying in the granary's containers becomes the granary's food stock (feeds meals and growth). */
    private void absorbGranaries(final UUID country) {
        final List<Inventory> invs = new ArrayList<>(this.plugin.granary().granaryInventories(country));
        for (final ProductionStorageService.StoreView v : this.plugin.production().views(country)) {
            if (this.function(v) == BuildingFunction.GRANARY) {
                invs.add(v.inventory());
            }
        }
        long added = 0;
        for (final Inventory inv : invs) {
            if (!this.plugin.production().busy(inv)) {
                added += this.plugin.granary().absorbStore(country, inv);
            }
        }
        if (added > 0) {
            this.plugin.production().touched(country);
            this.log(country, "Spichlerz przyjął " + added + " pkt jedzenia");
        }
    }

    // ------------------------------------------------------------------ status

    private void log(final UUID country, final String line) {
        final Deque<String> d = this.recent.computeIfAbsent(country, k -> new ArrayDeque<>());
        final String stamp = java.time.LocalTime.now().withNano(0).toString();
        d.addFirst(stamp + " " + line);
        while (d.size() > RECENT) {
            d.removeLast();
        }
    }

    /** Where the haulers will take a group from (a loaded store that has it), or null. Cached for 5 s. */
    public String sourceHint(final UUID country, final String group) {
        final long now = System.currentTimeMillis();
        if (now - this.hintAt > 5000) {
            this.hintCache.clear();
            this.hintAt = now;
        }
        final String key = country + "|" + group;
        if (this.hintCache.containsKey(key)) {
            return this.hintCache.get(key);
        }
        String hint = null;
        if (this.cfg().enabled()) {
            for (final ProductionStorageService.StoreView v : this.plugin.production().views(country)) {
                for (final ItemStack i : v.inventory().getStorageContents()) {
                    if (i != null && !i.hasItemMeta() && MaterialNeeds.supply(i.getType().name(), group) > 0) {
                        hint = this.storeLabel(v);
                        break;
                    }
                }
                if (hint != null) {
                    break;
                }
            }
        }
        this.hintCache.put(key, hint);
        return hint;
    }

    /** Hauls of a country now on the way (Polish lines). */
    public List<String> walking(final UUID country) {
        final List<String> out = new ArrayList<>();
        for (final Haul h : this.hauls.values()) {
            if (h.country.equals(country)) {
                out.add(h.amount + "× " + itemName(h.item) + ": " + h.from.label() + " → " + h.to.label());
            }
        }
        return out;
    }

    public List<String> recent(final UUID country) {
        return List.copyOf(this.recent.getOrDefault(country, new ArrayDeque<>()));
    }

    public int activeCount() {
        return this.hauls.size();
    }

    /**
     * Immutable snapshot of the material flow of a country for the web panel (main thread only): workplaces with
     * their status, loaded stores with fill and goods, the granary, building sites with stage / needs / waiting reason,
     * haulers on the way and the recent transfers. Keys and values are plain strings, numbers, lists and maps.
     */
    public Map<String, Object> flowData(final UUID country) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("flowData requires the main thread");
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", FLOW_VERSION);
        final List<Map<String, Object>> workplaces = new ArrayList<>();
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (ProductionStorageService.productive(b)) {
                // daily: items per material really put into the store in the last 24 h (persistent hourly ledger)
                workplaces.add(Map.of("id", b.id().toString(), "function", b.function().displayName(),
                    "status", this.plugin.production().status(b), "x", b.box().centerX(), "z", b.box().centerZ(),
                    "daily", Map.copyOf(this.plugin.production().lastDay(b))));
            }
        }
        out.put("workplaces", workplaces);
        final List<Map<String, Object>> stores = new ArrayList<>();
        for (final ProductionStorageService.StoreView v : this.plugin.production().views(country)) {
            final Map<String, Integer> goods = new LinkedHashMap<>();
            int used = 0;
            for (final ItemStack i : v.inventory().getStorageContents()) {
                if (i != null && !i.getType().isAir()) {
                    used++;
                    if (!i.hasItemMeta()) {
                        goods.merge(i.getType().getKey().toString(), i.getAmount(), Integer::sum);
                    }
                }
            }
            stores.add(Map.of("label", this.storeLabel(v), "used", used, "slots", v.inventory().getStorageContents().length,
                "goods", Map.copyOf(goods), "world", v.at().world(), "x", v.at().x(), "y", v.at().y(), "z", v.at().z()));
        }
        out.put("stores", stores);
        out.put("granary", Map.of("food", this.plugin.granary().food(country), "capacity", this.plugin.granary().capacity(country),
            "meal", (long) this.plugin.data().population(country) * this.plugin.settings().rules.foodPerCitizen(),
            "population", this.plugin.data().population(country), "housing", this.plugin.data().housing(country)));
        final List<Map<String, Object>> sites = new ArrayList<>();
        for (final ConstructionService.SiteView v : this.plugin.construction().views(country)) {
            final Map<String, Object> site = new LinkedHashMap<>();
            site.put("id", v.site().id.toString());
            site.put("name", v.name());
            site.put("stage", v.stage());
            site.put("percent", v.percent());
            site.put("builders", v.builders());
            site.put("status", v.status() == null ? "" : v.status());
            site.put("needs", Map.copyOf(v.needs()));
            site.put("x", v.site().originX);
            site.put("y", v.site().originY);
            site.put("z", v.site().originZ);
            site.put("waitingSince", this.plugin.construction().waitingSince(v.site().id)); // epoch ms, null = not waiting
            site.put("waitingFor", this.plugin.construction().waitingFor(v.site().id));
            sites.add(java.util.Collections.unmodifiableMap(site));
        }
        out.put("sites", sites);
        out.put("haulers", this.walking(country));
        out.put("recent", this.recent(country));
        out.put("requireMaterials", this.plugin.settings().requireMaterials);
        // production chains (2026-10-29, compatible extension): workshops and every material group
        out.put("workshops", this.workshopRows(country));
        out.put("chains", this.chainRows(country, stores));
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Workshops of a country for the panel: id, function, status, last finished step, sand / clay dug today and its
     * limit, fuel smelts left, flock size (-1 unknown), centre x / z. Main thread.
     */
    public List<Map<String, Object>> workshopRows(final UUID country) {
        final List<Map<String, Object>> rows = new ArrayList<>();
        final WorkshopService shops = this.plugin.workshops();
        if (shops == null) {
            return rows;
        }
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (!shops.handles(b)) {
                continue;
            }
            final int[] c = shops.counters(b);
            final String last = shops.last(b);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.id().toString());
            row.put("type", b.type());
            row.put("function", b.function().displayName());
            row.put("status", shops.status(b));
            row.put("last", last == null ? "" : last);
            row.put("dugToday", c[0]);
            row.put("dugLimit", shops.quota(b));
            row.put("fuel", c[1]);
            row.put("flock", c[2]);
            row.put("makes", List.copyOf(pl.medivalrpg.ludnosc.logic.Workshops.groups(b.function())));
            row.put("x", b.box().centerX());
            row.put("z", b.box().centerZ());
            rows.add(java.util.Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }

    /**
     * Every material group for the panel: name, who makes it, whether the country makes it now (otherwise the sites
     * buy it with the order's coins), units in loaded stores, units the sites still need. Main thread.
     */
    public List<Map<String, Object>> chainRows(final UUID country, final List<Map<String, Object>> storeRows) {
        final Map<String, Long> stock = new HashMap<>();
        for (final ProductionStorageService.StoreView v : this.plugin.production().views(country)) {
            for (final ItemStack i : v.inventory().getStorageContents()) {
                if (i != null && !i.hasItemMeta()) {
                    for (final String g : MaterialNeeds.GROUPS) {
                        stock.merge(g, (long) i.getAmount() * MaterialNeeds.supply(i.getType().name(), g), Long::sum);
                    }
                }
            }
        }
        final Map<String, Long> needed = new HashMap<>();
        for (final ConstructionService.SiteView v : this.plugin.construction().views(country)) {
            v.needs().forEach((g, n) -> needed.merge(g, n, Long::sum));
        }
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final String g : MaterialNeeds.GROUPS) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", g);
            row.put("name", MaterialNeeds.displayName(g));
            row.put("producer", MaterialNeeds.producerName(g));
            row.put("produced", this.plugin.construction().makes(country, g));
            row.put("stockUnits", stock.getOrDefault(g, 0L));
            row.put("neededUnits", needed.getOrDefault(g, 0L));
            row.put("unitsPerItem", MaterialNeeds.unitsPerItem(g));
            rows.add(java.util.Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }

    /** Polish-ish readable item name for chat/log lines (the client translates the components in GUIs). */
    static String itemName(final Material m) {
        return switch (m) {
            case OAK_LOG -> "kłoda dębowa";
            case SPRUCE_LOG -> "kłoda świerkowa";
            case BIRCH_LOG -> "kłoda brzozowa";
            case DARK_OAK_LOG -> "kłoda ciemnego dębu";
            case JUNGLE_LOG -> "kłoda tropikalna";
            case ACACIA_LOG -> "kłoda akacjowa";
            case COBBLESTONE -> "bruk";
            case COBBLED_DEEPSLATE -> "bruk łupkowy";
            case STONE -> "kamień";
            case WHEAT -> "pszenica";
            case CARROT -> "marchew";
            case POTATO -> "ziemniak";
            case BEETROOT -> "burak";
            case BREAD -> "chleb";
            case COD -> "dorsz";
            case SALMON -> "łosoś";
            case RAW_IRON -> "surowe żelazo";
            case RAW_COPPER -> "surowa miedź";
            case RAW_GOLD -> "surowe złoto";
            case COAL -> "węgiel";
            case TORCH -> "pochodnia";
            case CHARCOAL -> "węgiel drzewny";
            case SAND -> "piasek";
            case CLAY_BALL -> "grudka gliny";
            case GLASS -> "szkło";
            case BRICK -> "cegła";
            case TERRACOTTA -> "terakota";
            case WHITE_WOOL -> "biała wełna";
            case HAY_BLOCK -> "bela siana";
            case IRON_INGOT -> "sztabka żelaza";
            default -> m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }
}
