// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.config.Messages;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.Placement;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;

/**
 * {@code /ludnosc buduj}: per-player building plans shown with particles (outline of the box, footprint at floor
 * level, door side highlighted), with rotate / confirm / cancel buttons in chat.
 */
public final class PlanService {

    private static final Particle.DustOptions OK = new Particle.DustOptions(Color.fromRGB(80, 220, 90), 1.0f);
    private static final Particle.DustOptions BAD = new Particle.DustOptions(Color.fromRGB(230, 60, 50), 1.0f);
    private static final Particle.DustOptions FRONT = new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.3f);

    private final LudnoscPlugin plugin;
    private static final Particle.DustOptions TAKEN = new Particle.DustOptions(Color.fromRGB(150, 20, 20), 0.9f);
    private static final Particle.DustOptions SHOW = new Particle.DustOptions(Color.fromRGB(70, 150, 255), 1.1f);

    private final Map<UUID, Plan> plans = new HashMap<>();
    /** Temporary outlines shown with {@code /ludnosc kolejka pokaz}: player → (box, ground y, expiry). */
    private final Map<UUID, View> views = new HashMap<>();

    private record View(BlockBox box, int groundY, long expiresAt) {
    }

    private record TownView(List<BlockBox> plots, List<Particle.DustOptions> colors, List<Long> roads, long expiresAt) {
    }

    private static final Particle.DustOptions ROAD = new Particle.DustOptions(Color.fromRGB(150, 110, 70), 1.0f);
    private final Map<UUID, TownView> towns = new HashMap<>();

    private static final class Plan {
        private final UUID country;
        private String type;
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private int rotation;
        private long expiresAt;
        private BuildOrderResult status = BuildOrderResult.OK;
        /** Last refusal written to the log (one line per distinct refusal). */
        private String lastLogged;
        /** Street from the planner (auto proposals), {x, z} cells. */
        private List<int[]> road = List.of();

        private Plan(final UUID country, final String type, final String world, final int x, final int y, final int z,
                     final int rotation) {
            this.country = country;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rotation = rotation;
        }
    }

    public PlanService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    /** Footprints of the players' live previews (earthworks of other sites keep off them). Main thread. */
    public List<pl.medivalrpg.ludnosc.model.BlockBox> previewBoxes() {
        final List<pl.medivalrpg.ludnosc.model.BlockBox> out = new java.util.ArrayList<>();
        for (final Plan plan : this.plans.values()) {
            final pl.medivalrpg.ludnosc.model.BlockBox box = this.plugin.construction().footprint(plan.type, plan.world, plan.x, plan.y,
                plan.z, plan.rotation);
            if (box != null) {
                out.add(box);
            }
        }
        return out;
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    /** Clockwise quarter turns so that the front (door) faces the player looking in {@code facing}. */
    static int rotationFacingPlayer(final BlockFace facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    public void create(final Player player, final UUID country, final String type) {
        Block target = player.getTargetBlockExact(12);
        if (target == null) {
            this.messages().send(player, "build-no-target");
            return;
        }
        // plants, snow layers... → the solid ground under them
        while (target.getY() > target.getWorld().getMinHeight() && (target.isPassable() || target.isReplaceable())
            && !target.isLiquid()) {
            target = target.getRelative(BlockFace.DOWN);
        }
        final int q = rotationFacingPlayer(player.getFacing());
        // a base type with variants: the variant of this plot (same plot = same choice), others via /ludnosc buduj wariant
        final java.util.List<String> family = this.variants(country, type);
        // only a base type gets the variant of the plot; a variant typed by the player is built as typed
        final String chosen = pl.medivalrpg.ludnosc.logic.BuildFixes.chooseVariant(type, this.plugin.templates().baseType(type), family,
            target.getWorld().getName(), target.getX(), target.getZ());
        final Plan plan = new Plan(country, chosen, target.getWorld().getName(), target.getX(), target.getY(), target.getZ(), q);
        this.plans.put(player.getUniqueId(), plan);
        this.refresh(player, plan);
        this.describe(player, plan);
        this.announceVariant(player, plan, family);
    }

    /** What the planned building does (description + bonus), once per chosen type and not on every rotation. */
    private void describe(final Player player, final Plan plan) {
        final BuildingTemplate t = this.plugin.templates().get(plan.type);
        if (t == null || !this.plans.containsKey(player.getUniqueId())) {
            return;
        }
        final String description = this.plugin.templates().texts().description(t);
        if (!description.isBlank()) {
            this.messages().send(player, "build-plan-info", Placeholder.unparsed("description", description));
        }
        final String bonus = this.plugin.bonusText(t);
        if (!bonus.isEmpty()) {
            this.messages().send(player, "build-plan-bonus", Placeholder.unparsed("bonus", bonus));
        }
    }

    /** Variants of a type this country may order (epoch and discovery), base first; one element = no choice. */
    private java.util.List<String> variants(final UUID country, final String type) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (final String id : this.plugin.templates().family(type)) {
            final BuildingTemplate t = this.plugin.templates().get(id);
            if (t != null && this.plugin.buildingAllowed(country, id) && !pl.medivalrpg.ludnosc.logic.BlueprintRules.isBlueprintId(id) && t.meta().era() <= this.plugin.postep().era(country)
                && (t.meta().discovery().isEmpty() || this.plugin.postep().hasDiscovery(country, t.meta().discovery()))) {
                out.add(id);
            }
        }
        return out;
    }

    private void announceVariant(final Player player, final Plan plan, final java.util.List<String> family) {
        if (family.size() < 2) {
            return;
        }
        final BuildingTemplate t = this.plugin.templates().get(plan.type);
        player.sendMessage(net.kyori.adventure.text.Component.text("Wariant " + (family.indexOf(plan.type) + 1) + "/" + family.size() + ": ",
                net.kyori.adventure.text.format.NamedTextColor.GRAY)
            .append(this.messages().parse(t == null ? plan.type : t.meta().displayName()))
            .append(net.kyori.adventure.text.Component.text(" (" + plan.type + ") — inny: /ludnosc buduj wariant",
                net.kyori.adventure.text.format.NamedTextColor.GRAY)));
    }

    /** {@code /ludnosc buduj wariant}: the next variant of the planned building type, same place and rotation. */
    public boolean nextVariant(final Player player) {
        final Plan plan = this.plans.get(player.getUniqueId());
        if (plan == null) {
            this.messages().send(player, "build-no-plan");
            return false;
        }
        final java.util.List<String> family = this.variants(plan.country, plan.type);
        if (family.size() < 2) {
            player.sendMessage(net.kyori.adventure.text.Component.text("Ten budynek nie ma innych wariantów.",
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return false;
        }
        plan.type = family.get((Math.max(0, family.indexOf(plan.type)) + 1) % family.size());
        this.refresh(player, plan);
        this.describe(player, plan);
        this.announceVariant(player, plan, family);
        return true;
    }

    public boolean rotate(final Player player) {
        final Plan plan = this.plans.get(player.getUniqueId());
        if (plan == null) {
            this.messages().send(player, "build-no-plan");
            return false;
        }
        plan.rotation = (plan.rotation + 1) % 4;
        this.refresh(player, plan);
        return true;
    }

    public boolean hasPlan(final Player player) { return this.plans.containsKey(player.getUniqueId()); }

    public void cancel(final Player player) {
        if (this.plans.remove(player.getUniqueId()) != null) {
            this.messages().send(player, "build-plan-cancelled");
        } else {
            this.messages().send(player, "build-no-plan");
        }
    }

    /** {@code /ludnosc buduj auto}: the planner's proposal as a plan (preview + buttons). */
    public void createProposal(final Player player, final UUID country, final pl.medivalrpg.ludnosc.planner.TownPlanner.Proposal p) {
        final Plan plan = new Plan(country, p.type(), p.box().world(), p.spot().x(), p.spot().y(), p.spot().z(), p.spot().rotation());
        plan.road = p.road();
        this.plans.put(player.getUniqueId(), plan);
        this.refresh(player, plan);
        this.describe(player, plan);
    }

    /** {@code /ludnosc plan}: streets and plots of the player's town for {@code seconds}. */
    public void showTown(final Player player, final List<BlockBox> plots, final List<Particle.DustOptions> colors,
                         final java.util.Collection<Long> roads, final int seconds) {
        this.towns.put(player.getUniqueId(), new TownView(List.copyOf(plots), List.copyOf(colors), List.copyOf(roads),
            System.currentTimeMillis() + seconds * 1000L));
    }

    public void forget(final UUID player) {
        this.towns.remove(player);
        this.plans.remove(player);
        this.views.remove(player);
    }

    /** Shows an outline (e.g. a queued order) to the player for a few seconds. */
    public void show(final Player player, final BlockBox box, final int groundY, final int seconds) {
        this.views.put(player.getUniqueId(), new View(box, groundY, System.currentTimeMillis() + seconds * 1000L));
    }

    public void confirm(final Player player) {
        final Plan plan = this.plans.get(player.getUniqueId());
        if (plan == null) {
            this.messages().send(player, "build-no-plan");
            return;
        }
        final World w = Bukkit.getWorld(plan.world);
        if (w == null) {
            this.plans.remove(player.getUniqueId());
            return;
        }
        final BuildingTemplate t = this.plugin.templates().get(plan.type);
        final ConstructionService.OrderOutcome outcome = this.plugin.construction().orderDetailed(plan.country, plan.type, w,
            plan.x, plan.y, plan.z, plan.rotation, false, player.getName(),
            plan.road.isEmpty() ? null : this.plugin.settlement().roadCells(plan.world, plan.country, plan.road, plan.y));
        final BuildOrderResult result = outcome.result();
        if (result == BuildOrderResult.OK) {
            this.plans.remove(player.getUniqueId());
            this.messages().send(player, outcome.queued() ? "build-queued" : "build-ordered",
                Placeholder.parsed("name", t == null ? plan.type : t.meta().displayName()),
                Placeholder.unparsed("cost", this.plugin.formatMoney(t == null ? 0 : t.meta().cost())),
                Placeholder.unparsed("position", Integer.toString(outcome.position())));
        } else {
            plan.status = result;
            this.messages().send(player, "build-failed", Placeholder.component("status", this.status(result, t)));
            final String why = this.plugin.construction().lastCheckReason(plan.country);
            if (why != null && result != BuildOrderResult.INSUFFICIENT_FUNDS) {
                this.messages().send(player, "build-failure-reason", Placeholder.unparsed("reason", why));
            }
            this.plugin.getLogger().info("Odmowa budowy " + plan.type + " gracza " + player.getName() + " w " + plan.x + " " + plan.y + " "
                + plan.z + ": " + result + (why == null ? "" : " — " + why));
        }
    }

    public Component status(final BuildOrderResult result, final BuildingTemplate t) {
        return switch (result) {
            case OK -> this.messages().get("build-status-ok");
            case NOT_ON_OWN_LAND -> this.messages().get("build-result-not-own-land");
            case AREA_OBSTRUCTED -> this.messages().get("build-result-obstructed");
            case INSUFFICIENT_FUNDS -> this.messages().get("build-result-funds",
                Placeholder.unparsed("cost", this.plugin.formatMoney(t == null ? 0 : t.meta().cost())));
            case TOO_MANY_ORDERS -> this.messages().get("build-result-too-many",
                Placeholder.unparsed("max", Integer.toString(this.plugin.settings().maxSitesCap)),
                Placeholder.unparsed("queue", Integer.toString(this.plugin.settings().queueSize)));
            case UNKNOWN_BUILDING -> this.messages().get("build-result-unknown");
        };
    }

    private void refresh(final Player player, final Plan plan) {
        plan.expiresAt = System.currentTimeMillis() + this.plugin.settings().previewSeconds * 1000L;
        final World w = Bukkit.getWorld(plan.world);
        final BuildingTemplate t = this.plugin.templates().get(plan.type);
        if (w == null || t == null) {
            this.plans.remove(player.getUniqueId());
            return;
        }
        plan.status = this.plugin.construction().check(plan.country, plan.type, w, plan.x, plan.y, plan.z, plan.rotation, null);
        if (plan.status == BuildOrderResult.OK && t.meta().cost() > 0 && this.plugin.skarbiec() != null
            && !this.plugin.skarbiec().has(pl.medivalrpg.skarbiec.api.SkarbiecApi.countryAccount(plan.country), t.meta().cost())) {
            plan.status = BuildOrderResult.INSUFFICIENT_FUNDS;
        }
        final BuildingTemplate r = t.rotated(plan.rotation);
        final String front = r.front();
        Component status = this.status(plan.status, t);
        if (plan.status == BuildOrderResult.OK
            && this.plugin.construction().admission(plan.country) == pl.medivalrpg.ludnosc.logic.QueueRules.Admission.QUEUE) {
            status = this.messages().get("build-status-queued",
                Placeholder.unparsed("position", Integer.toString(this.plugin.data().queueSize(plan.country) + 1)));
        }
        this.messages().send(player, "build-plan",
            Placeholder.parsed("name", t.meta().displayName()),
            Placeholder.unparsed("size", r.width() + "×" + r.depth() + "×" + r.height()),
            Placeholder.unparsed("cost", this.plugin.formatMoney(t.meta().cost())),
            Placeholder.component("facing", this.messages().get("facing-" + front)),
            Placeholder.component("status", status));
        if (plan.status != BuildOrderResult.OK) {
            // the preview says WHY it is red (the obstruction with its coordinates), not only that it is
            final String why = plan.status == BuildOrderResult.INSUFFICIENT_FUNDS
                ? "skarbiec kraju ma mniej niż " + this.plugin.formatMoney(t.meta().cost())
                : this.plugin.construction().lastCheckReason(plan.country);
            if (why != null) {
                this.messages().send(player, "build-failure-reason", Placeholder.unparsed("reason", why));
                player.sendActionBar(net.kyori.adventure.text.Component.text(why, net.kyori.adventure.text.format.NamedTextColor.RED));
            }
            final String line = plan.type + "@" + plan.x + "," + plan.y + "," + plan.z + "/" + plan.rotation + ":" + plan.status + ":" + why;
            if (!line.equals(plan.lastLogged)) {
                plan.lastLogged = line;
                this.plugin.getLogger().info("Podgląd budowy " + plan.type + " gracza " + player.getName() + " w " + plan.x + " " + plan.y
                    + " " + plan.z + " (obrót " + plan.rotation + "): " + plan.status + (why == null ? "" : " — " + why));
            }
        }
        player.sendMessage(this.messages().get("build-buttons",
            Placeholder.component("rotate", this.button("build-button-rotate", "/ludnosc buduj obroc")),
            Placeholder.component("confirm", this.button("build-button-confirm", "/ludnosc buduj potwierdz")),
            Placeholder.component("cancel", this.button("build-button-cancel", "/ludnosc buduj anuluj"))));
        this.draw(player, plan);
    }

    private Component button(final String key, final String command) {
        return this.messages().get(key)
            .hoverEvent(HoverEvent.showText(this.messages().get(key + "-hover")))
            .clickEvent(ClickEvent.runCommand(command));
    }

    /** Called every 10 ticks. */
    public void tick() {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<UUID, Plan>> it = this.plans.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Plan> e = it.next();
            final Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (now > e.getValue().expiresAt) {
                it.remove();
                this.messages().send(p, "build-plan-expired");
                continue;
            }
            this.draw(p, e.getValue());
        }
        final Iterator<Map.Entry<UUID, TownView>> tit = this.towns.entrySet().iterator();
        while (tit.hasNext()) {
            final Map.Entry<UUID, TownView> e = tit.next();
            final Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || now > e.getValue().expiresAt()) {
                tit.remove();
                continue;
            }
            this.drawTown(p, e.getValue());
        }
        final Iterator<Map.Entry<UUID, View>> vit = this.views.entrySet().iterator();
        while (vit.hasNext()) {
            final Map.Entry<UUID, View> e = vit.next();
            final Player p = Bukkit.getPlayer(e.getKey());
            final View v = e.getValue();
            if (p == null || now > v.expiresAt()) {
                vit.remove();
                continue;
            }
            if (p.getWorld().getName().equals(v.box().world())) {
                this.outline(p, v.box(), v.groundY() + 1, SHOW);
            }
        }
    }

    private void drawTown(final Player p, final TownView v) {
        final org.bukkit.Location me = p.getLocation();
        for (int i = 0; i < v.plots().size(); i++) {
            final BlockBox b = v.plots().get(i);
            if (!b.world().equals(p.getWorld().getName()) || Math.abs(b.centerX() - me.getBlockX()) > 64
                || Math.abs(b.centerZ() - me.getBlockZ()) > 64) {
                continue;
            }
            this.rect(p, b.minX(), b.minZ(), b.maxX() + 1, b.maxZ() + 1, b.minY() + 1.2, v.colors().get(i));
        }
        int n = 0;
        for (final long k : v.roads()) {
            final int x = pl.medivalrpg.ludnosc.planner.RoadPlanner.keyX(k);
            final int z = pl.medivalrpg.ludnosc.planner.RoadPlanner.keyZ(k);
            if (Math.abs(x - me.getBlockX()) > 48 || Math.abs(z - me.getBlockZ()) > 48 || ((x + z) & 1) != 0) {
                continue;
            }
            if (++n > 400) {
                break;
            }
            final int y = p.getWorld().getHighestBlockYAt(x, z) + 1;
            this.dot(p, x + 0.5, y + 0.2, z + 0.5, ROAD);
        }
    }

    /** Bottom (at {@code y0}) and top rectangles plus vertical corner edges of a box. */
    private void outline(final Player p, final BlockBox b, final double y0, final Particle.DustOptions color) {
        final double x0 = b.minX();
        final double x1 = b.maxX() + 1;
        final double z0 = b.minZ();
        final double z1 = b.maxZ() + 1;
        final double y1 = b.maxY() + 1;
        this.rect(p, x0, z0, x1, z1, y0, color);
        this.rect(p, x0, z0, x1, z1, y1, color);
        for (final double[] c : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            for (double y = y0; y <= y1; y += 1) {
                this.dot(p, c[0], y, c[1], color);
            }
        }
    }

    private void draw(final Player player, final Plan plan) {
        if (!player.getWorld().getName().equals(plan.world)) {
            return;
        }
        final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(plan.type, plan.rotation);
        if (rt == null) {
            return;
        }
        final Placement placement = Placement.centered(plan.world, rt.template(), plan.x, plan.y, plan.z);
        final BlockBox b = placement.box();
        for (int i = 0; i < plan.road.size(); i += 2) {
            final int[] c = plan.road.get(i);
            this.dot(player, c[0] + 0.5, player.getWorld().getHighestBlockYAt(c[0], c[1]) + 1.2, c[1] + 0.5, ROAD);
        }
        final Particle.DustOptions color = plan.status == BuildOrderResult.OK ? OK : BAD;
        // areas already taken nearby (buildings, sites, queued orders) in dark red
        for (final BlockBox taken : this.plugin.construction().takenNear(plan.world, plan.x, plan.z, 40, 8)) {
            this.rect(player, taken.minX(), taken.minZ(), taken.maxX() + 1, taken.maxZ() + 1, taken.minY() + 1.1, TAKEN);
            this.rect(player, taken.minX(), taken.minZ(), taken.maxX() + 1, taken.maxZ() + 1, taken.maxY() + 1, TAKEN);
        }
        final double x0 = b.minX();
        final double x1 = b.maxX() + 1;
        final double z0 = b.minZ();
        final double z1 = b.maxZ() + 1;
        final double y0 = placement.groundY() + 1;
        final double y1 = b.maxY() + 1;
        // footprint at floor level and the vertical edges + top rectangle
        this.rect(player, x0, z0, x1, z1, y0, color);
        this.rect(player, x0, z0, x1, z1, y1, color);
        for (final double[] c : new double[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            for (double y = y0; y <= y1; y += 1) {
                this.dot(player, c[0], y, c[1], color);
            }
        }
        // door side
        final int[] v = BlockStates.vector(rt.template().front());
        if (v[1] != 0) {
            final double z = v[1] > 0 ? z1 : z0;
            for (double x = x0; x <= x1; x += 0.5) {
                this.dot(player, x, y0 + 0.2, z, FRONT);
            }
        } else {
            final double x = v[0] > 0 ? x1 : x0;
            for (double z = z0; z <= z1; z += 0.5) {
                this.dot(player, x, y0 + 0.2, z, FRONT);
            }
        }
    }

    private void rect(final Player p, final double x0, final double z0, final double x1, final double z1, final double y,
                      final Particle.DustOptions color) {
        for (double x = x0; x <= x1; x += 1) {
            this.dot(p, x, y, z0, color);
            this.dot(p, x, y, z1, color);
        }
        for (double z = z0; z <= z1; z += 1) {
            this.dot(p, x0, y, z, color);
            this.dot(p, x1, y, z, color);
        }
    }

    private void dot(final Player p, final double x, final double y, final double z, final Particle.DustOptions color) {
        p.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, color);
    }
}
