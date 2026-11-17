// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.planner.RepairRules;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.Placement;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;

/**
 * Automatic repairs of template buildings. Buildings are compared with their template a few hundred blocks at a
 * time within a per-tick time budget, only in loaded chunks (a building whose chunk loads moves to the front of the
 * scan queue). Damage above the threshold (or a missing key block: bed, door, roof over a bed) creates a repair
 * order at the front of the build queue (NPC countries, or players with {@code repair.auto-for-players}); player
 * countries otherwise get a message with a "repair" button / {@code /ludnosc napraw}. Ruins are rebuilt or removed.
 */
public final class RepairService {

    private static final long NOTIFY_MILLIS = 30 * 60_000L;

    /** Last scan result of a building. */
    public record Damage(int missing, int checked, boolean keyMissing, long time) {

        public int percent() {
            return RepairRules.percent(this.missing, this.checked);
        }
    }

    private final LudnoscPlugin plugin;
    private final Map<UUID, Damage> damage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notified = new HashMap<>();
    private final ArrayDeque<UUID> priority = new ArrayDeque<>();
    private final Map<String, Set<Integer>> keyCache = new HashMap<>();
    private Scan scan;
    private int rotate;

    private static final class Scan {
        private final Building building;
        private final TemplateLibrary.Runtime tpl;
        private final Placement placement;
        private final Set<Integer> keys;
        private int cursor;
        private int missing;
        private int checked;
        private boolean keyMissing;
        private boolean burning;

        private Scan(final Building b, final TemplateLibrary.Runtime tpl, final Placement p, final Set<Integer> keys) {
            this.building = b;
            this.tpl = tpl;
            this.placement = p;
            this.keys = keys;
        }
    }

    public RepairService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    public Damage damageOf(final UUID building) {
        return this.damage.get(building);
    }

    private Set<String> firePending(final UUID country) {
        final var state=this.plugin.data().state(country);
        if (state.fireRepairs==null) state.fireRepairs=new java.util.LinkedHashSet<>();
        return state.fireRepairs;
    }

    /** Accepts recovery durably; expensive scans remain inside the ordinary per-tick repair budget. */
    public int requestFireRecovery(final UUID country, final String world, final int minX, final int minZ,
                                   final int maxX, final int maxZ) {
        if (country==null || world==null || minX>maxX || minZ>maxZ
            || (long)maxX-minX>256 || (long)maxZ-minZ>256 || this.plugin.kraje().getCountry(country).isEmpty()) return -1;
        int accepted=0;
        final Set<String> pending=this.firePending(country);
        for (final Building b:this.plugin.data().buildingsOf(country)) {
            final BlockBox box=b.box();
            if (b.registered() || this.plugin.templates().get(b.type())==null || !world.equals(box.world())
                || box.maxX()<minX || box.minX()>maxX || box.maxZ()<minZ || box.minZ()>maxZ
                || !this.plugin.construction().onOwnLand(country,box)) continue;
            if (this.plugin.construction().repairing(b.id())) {accepted++; continue;}
            pending.add(b.id().toString());
            this.damage.remove(b.id());
            if (!this.priority.contains(b.id())) this.priority.addLast(b.id());
            accepted++;
        }
        if (accepted>0) this.plugin.data().markDirty();
        return accepted;
    }

    public void repaired(final UUID building) {
        this.damage.remove(building);
        this.notified.remove(building);
    }

    /** A chunk loaded: its buildings are checked soon. */
    public void onChunkLoad(final Chunk chunk) {
        if (!this.plugin.settings().repairEnabled) {
            return;
        }
        for (final Building b : this.plugin.data().buildingsIn(new ChunkRef(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()))) {
            if (!b.registered() && !this.priority.contains(b.id())) {
                this.priority.addLast(b.id());
            }
        }
    }

    private boolean due(final Building b) {
        final Damage d = this.damage.get(b.id());
        final boolean fire=b.countryId()!=null && this.firePending(b.countryId()).contains(b.id().toString());
        return d == null || System.currentTimeMillis() - d.time() > (fire ? 30_000L : this.plugin.settings().repairIntervalMinutes * 60_000L);
    }

    private Scan start(final Building b) {
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(b.countryId()))return null;
        if (b.registered() || b.countryId() == null || this.plugin.construction().repairing(b.id())) {
            return null;
        }
        final World w = Bukkit.getWorld(b.box().world());
        if (w == null || !ConstructionService.chunksLoaded(w, b.box())) {
            return null;
        }
        final TemplateLibrary.Runtime tpl = this.plugin.templates().runtime(b.type(), b.rotation(), b.dye(),
            pl.medivalrpg.ludnosc.template.MaterialPalette.decode(b.level()));
        if (tpl == null) {
            return null;
        }
        final Placement p = new Placement(b.box().world(), tpl.template(), b.box().minX(), b.box().minY(), b.box().minZ());
        final Set<Integer> keys = this.keyCache.computeIfAbsent(b.type() + "#" + b.rotation() + "#" + b.level(),
            k -> RepairRules.keyCells(tpl.template()));
        return new Scan(b, tpl, p, keys);
    }

    /** Main thread, every tick. */
    public void tick() {
        if (!this.plugin.settings().repairEnabled && this.plugin.data().allBuildings().stream()
            .noneMatch(b -> b.countryId()!=null && this.firePending(b.countryId()).contains(b.id().toString()))) {
            return;
        }
        final long deadline = System.nanoTime() + (long) (this.plugin.settings().repairBudgetMs * 1_000_000);
        if (this.scan == null) {
            this.scan = this.pickNext();
            if (this.scan == null) {
                return;
            }
        }
        final Scan s = this.scan;
        final World w = Bukkit.getWorld(s.building.box().world());
        if (w == null || !ConstructionService.chunksLoaded(w, s.building.box())) {
            this.scan = null;
            return;
        }
        final BuildingTemplate t = s.tpl.template();
        while (s.cursor < t.volume()) {
            if ((s.cursor & 63)==0 && System.nanoTime()>deadline) return;
            final int i = s.cursor++;
            final String expected = t.state(i);
            final var block=w.getBlockAt(s.placement.x(i),s.placement.y(i),s.placement.z(i));
            if (block.getType()==org.bukkit.Material.FIRE || block.getType()==org.bukkit.Material.SOUL_FIRE) s.burning=true;
            if (RepairRules.ignoredCell(expected)) {
                continue;
            }
            s.checked++;
            final String actual = block.getBlockData().getAsString();
            if (!RepairRules.equivalent(expected, actual)) {
                s.missing++;
                if (s.keys.contains(i)) {
                    s.keyMissing = true;
                }
            }
            if ((s.cursor & 63) == 0 && System.nanoTime() > deadline) {
                return;
            }
        }
        this.scan = null;
        final Damage d = new Damage(s.missing, s.checked, s.keyMissing, System.currentTimeMillis());
        this.damage.put(s.building.id(), d);
        if (!s.burning) this.act(s.building, d);
    }

    private Scan pickNext() {
        while (!this.priority.isEmpty()) {
            final Building b = this.plugin.data().building(this.priority.pollFirst());
            if (b != null) {
                final Scan s = this.start(b);
                if (s != null) {
                    return s;
                }
            }
        }
        final List<Building> all = new ArrayList<>(this.plugin.data().allBuildings());
        if (all.isEmpty()) {
            return null;
        }
        for (int k = 0; k < Math.min(all.size(), 16); k++) {
            final Building b = all.get(Math.floorMod(this.rotate++, all.size()));
            if (this.due(b) && (this.plugin.settings().repairEnabled
                || b.countryId()!=null && this.firePending(b.countryId()).contains(b.id().toString()))) {
                final Scan s = this.start(b);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    public long cost(final Building b, final Damage d) {
        final BuildingTemplate t = this.plugin.templates().get(b.type());
        return t == null ? 0 : RepairRules.cost(t.meta().cost(), d.missing(), d.checked(), this.plugin.settings().repairCostMultiplier);
    }

    private void act(final Building b, final Damage d) {
        if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(b.countryId()))return;
        final boolean fireRecovery=b.countryId()!=null && this.firePending(b.countryId()).contains(b.id().toString());
        final RepairRules.Verdict v = RepairRules.verdict(d.missing(), d.checked(), d.keyMissing(),
            this.plugin.settings().repairThreshold, this.plugin.settings().ruinThreshold);
        if (v == RepairRules.Verdict.OK && (!fireRecovery || d.missing()==0)) {
            if (fireRecovery && this.firePending(b.countryId()).remove(b.id().toString())) this.plugin.data().markDirty();
            return;
        }
        final KrajInfo k = this.plugin.kraje().getCountry(b.countryId()).orElse(null);
        if (k == null) {
            return;
        }
        if (k.npc() && v == RepairRules.Verdict.RUIN && !this.plugin.settings().ruinRebuild && !fireRecovery) {
            this.plugin.buildings().remove(b);
            this.damage.remove(b.id());
            this.plugin.notifyCountry(b.countryId(), "repair-ruin-removed", Placeholder.parsed("name",
                this.plugin.construction().displayName(b.type())));
            return;
        }
        final boolean auto = k.npc();
        final long cost = this.cost(b, d);
        if (auto) {
            if (this.plugin.construction().orderRepair(b, cost, v == RepairRules.Verdict.RUIN, fireRecovery ? "odbudowa-po-pozarze" : "AI")) {
                if (fireRecovery && this.firePending(b.countryId()).remove(b.id().toString())) this.plugin.data().markDirty();
                this.plugin.notifyCountry(b.countryId(), "repair-ordered", Placeholder.parsed("name",
                    this.plugin.construction().displayName(b.type())), Placeholder.unparsed("cost", this.plugin.formatMoney(cost)));
            }
            return; // no money: tried again after the next scan
        }
        final long now = System.currentTimeMillis();
        if (now - this.notified.getOrDefault(b.id(), 0L) < NOTIFY_MILLIS) {
            return;
        }
        this.notified.put(b.id(), now);
        final String id = b.id().toString().substring(0, 8);
        final Component button = this.plugin.messages().get("repair-button")
            .hoverEvent(HoverEvent.showText(this.plugin.messages().get("repair-button-hover")))
            .clickEvent(ClickEvent.runCommand("/ludnosc napraw " + id));
        this.plugin.notifyCountry(b.countryId(), "repair-needed", Placeholder.parsed("name", this.plugin.construction().displayName(b.type())),
            Placeholder.unparsed("percent", Integer.toString(d.percent())), Placeholder.unparsed("cost", this.plugin.formatMoney(cost)),
            Placeholder.component("button", button));
    }

    /**
     * {@code /ludnosc napraw}: orders the repair of one damaged building (id prefix) or all of the country's.
     * Returns the number of repairs ordered (−1 = not enough money for the first one).
     */
    public int repairCommand(final Player p, final UUID country, final String arg) {
        int ordered = 0;
        int refused = 0;
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (!"wszystko".equalsIgnoreCase(arg) && !b.id().toString().startsWith(arg.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (!this.plugin.automaticBuildingAllowed(country) && this.firePending(country).contains(b.id().toString())
                && !p.hasPermission("ludnosc.admin") && !this.plugin.kraje().getRank(p.getUniqueId())
                    .map(rank -> rank == pl.medivalrpg.kraje.model.Rank.RULER).orElse(false)) {
                p.sendMessage(net.kyori.adventure.text.Component.text("Odbudowę po pożarze zleca władca kraju: /ludnosc napraw <id|wszystko>."));
                continue;
            }
            final Damage d = this.damage.get(b.id());
            if (d == null || d.missing() == 0) {
                continue;
            }
            final String name = this.plugin.construction().displayName(b.type());
            if (this.plugin.construction().repairing(b.id())) {
                this.plugin.messages().send(p, "repair-busy", Placeholder.parsed("name", name));
                continue;
            }
            final long cost = this.cost(b, d);
            final boolean ruin = RepairRules.verdict(d.missing(), d.checked(), d.keyMissing(), 0, this.plugin.settings().ruinThreshold)
                == RepairRules.Verdict.RUIN;
            // the real reason of a refusal, and "napraw wszystko" goes on with the other buildings
            final String why = this.plugin.construction().repairRefusal(b, cost);
            if (why != null || !this.plugin.construction().orderRepair(b, cost, ruin, p.getName())) {
                this.plugin.messages().send(p, "repair-refused", Placeholder.parsed("name", name),
                    Placeholder.unparsed("reason", why != null ? why : "płatność ze skarbca kraju została odrzucona"),
                    Placeholder.unparsed("cost", this.plugin.formatMoney(cost)));
                refused++;
                continue;
            }
            this.plugin.messages().send(p, "repair-ordered", Placeholder.parsed("name", name),
                Placeholder.unparsed("cost", this.plugin.formatMoney(cost)));
            ordered++;
        }
        if (ordered == 0 && refused == 0) {
            this.plugin.messages().send(p, "repair-none");
        }
        return ordered == 0 && refused > 0 ? -1 : ordered;
    }

    // ------------------------------------------------------------------ modernisation

    /** The building's materials are older than the settlement level (and its template has material slots). */
    public boolean outdated(final Building b) {
        final BuildingTemplate t = this.plugin.templates().get(b.type());
        return b.countryId() != null && t != null
            && RepairRules.outdated(t.usesMaterials(), b.level(), this.plugin.settlement().style(b.countryId()));
    }

    /** Price of modernising the building to the current settlement level. */
    public long upgradeCost(final Building b) {
        final BuildingTemplate t = this.plugin.templates().get(b.type());
        return t == null ? 0 : RepairRules.upgradeCost(t.meta().cost(), t.slotCount(), t.solidCount(),
            this.plugin.settings().upgradeCostMultiplier);
    }

    /** {@code /ludnosc modernizuj <id|wszystko>}: number of orders, -1 when the money ran out. */
    public int upgradeCommand(final Player p, final UUID country, final String arg) {
        int ordered = 0;
        final int level = this.plugin.settlement().style(country);
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (!"wszystko".equalsIgnoreCase(arg) && !b.id().toString().startsWith(arg.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (!this.outdated(b)) {
                continue;
            }
            final String name = this.plugin.construction().displayName(b.type());
            if (this.plugin.construction().repairing(b.id())) {
                this.plugin.messages().send(p, "upgrade-busy", Placeholder.parsed("name", name));
                continue;
            }
            final long cost = this.upgradeCost(b);
            if (!this.plugin.construction().orderUpgrade(b, cost, level, p.getName())) {
                this.plugin.messages().send(p, "upgrade-funds", Placeholder.parsed("name", name),
                    Placeholder.unparsed("cost", this.plugin.formatMoney(cost)));
                return ordered == 0 ? -1 : ordered;
            }
            this.plugin.messages().send(p, "upgrade-ordered", Placeholder.parsed("name", name),
                Placeholder.unparsed("cost", this.plugin.formatMoney(cost)));
            ordered++;
        }
        if (ordered == 0) {
            this.plugin.messages().send(p, "upgrade-none");
        }
        return ordered;
    }

    /** Admin: modernises every outdated building of the country ({@code free}: the treasury does not pay). */
    public int forceModernize(final UUID country, final boolean free) {
        int n = 0;
        final int level = this.plugin.settlement().style(country);
        for (final Building b : new ArrayList<>(this.plugin.data().buildingsOf(country))) {
            if (this.outdated(b) && !this.plugin.construction().repairing(b.id())
                && this.plugin.construction().orderUpgrade(b, free ? 0 : this.upgradeCost(b), level, "admin")) {
                n++;
            }
        }
        return n;
    }

    /**
     * Planner: modernises the most valuable outdated building that fits the budget (one at a time, only when no other
     * repair/modernisation of the country is pending). True when ordered.
     */
    public boolean autoUpgrade(final UUID country, final long budget) {
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (this.plugin.construction().repairing(b.id())) {
                return false;
            }
        }
        Building best = null;
        long bestCost = 0;
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (!this.outdated(b)) {
                continue;
            }
            final long cost = this.upgradeCost(b);
            final BuildingTemplate t = this.plugin.templates().get(b.type());
            if (cost <= budget && (best == null || t.meta().cost() > this.plugin.templates().get(best.type()).meta().cost())) {
                best = b;
                bestCost = cost;
            }
        }
        return best != null && this.plugin.construction().orderUpgrade(best, bestCost, this.plugin.settlement().style(country), "AI");
    }

    /** Box of a building (for messages). */
    static String pos(final BlockBox b) {
        return b.centerX() + " " + b.minY() + " " + b.centerZ();
    }
}
