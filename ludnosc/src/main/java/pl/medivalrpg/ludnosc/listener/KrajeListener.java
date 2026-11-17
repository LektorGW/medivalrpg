// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.listener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.event.ChunkOwnerChangeEvent;
import pl.medivalrpg.kraje.api.event.CountryCreateEvent;
import pl.medivalrpg.kraje.api.event.CountryDisbandEvent;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.api.LudnoscApi.BuildOrderResult;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.service.ConstructionService;
import pl.medivalrpg.ludnosc.template.Placement;
import pl.medivalrpg.ludnosc.template.TemplateLibrary;

/** Reactions to Kraje events: NPC country start-up, disbanding, chunks changing owner. */
public final class KrajeListener implements Listener {

    private final LudnoscPlugin plugin;

    public KrajeListener(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreate(final CountryCreateEvent event) {
        if (!event.npc()) {
            return;
        }
        final UUID id = event.countryId();
        // one tick later: Kraje has finished its own bookkeeping
        Bukkit.getScheduler().runTask(this.plugin, () -> this.setupNpcCountry(id));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(final CountryDisbandEvent event) {
        final UUID id = event.countryId();
        this.plugin.construction().countryRemoved(id);
        this.plugin.citizens().removeCountry(id);
        this.plugin.buildings().countryRemoved(id);
        this.plugin.granary().countryRemoved(id);
        this.plugin.getLogger().info("Kraj " + event.name() + " rozwiązany — obywatele NPC: "
            + this.plugin.settings().onDisband.name().toLowerCase(java.util.Locale.ROOT) + ", budynki bez właściciela.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkOwner(final ChunkOwnerChangeEvent event) {
        if(this.plugin.royalRebuild()!=null&&(this.plugin.royalRebuild().blocks(event.oldOwner())||this.plugin.royalRebuild().blocks(event.newOwner())))return;
        final String world = event.world();
        this.plugin.construction().chunkChanged(world, event.cx(), event.cz(), event.oldOwner());
        this.plugin.buildings().chunkChanged(world, event.cx(), event.cz(), event.newOwner());
        this.plugin.granary().chunkChanged(world, event.cx(), event.cz(), event.newOwner());
        this.plugin.citizens().chunkChanged(world, event.cx(), event.cz(), event.oldOwner(), event.newOwner());
    }

    // ------------------------------------------------------------------ NPC countries

    private void setupNpcCountry(final UUID id) {
        final KrajInfo k = this.plugin.kraje().getCountry(id).orElse(null);
        if (k == null) {
            return;
        }
        final World w = Bukkit.getWorld(k.capitalWorld());
        if (w == null) {
            return;
        }
        w.getChunkAtAsync(k.capitalX(), k.capitalZ()).thenAccept(chunk -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (this.plugin.kraje().getCountry(id).isEmpty()) {
                return;
            }
            this.plugin.granary().setFood(id, this.plugin.granary().food(id) + this.plugin.settings().npcInitialFood);
            final List<BlockBox> reserved = new ArrayList<>();
            for (final String type : this.plugin.settings().npcStartingBuildings) {
                final BlockBox box = this.orderStarting(id, type, w, k.capitalX(), k.capitalZ());
                if (box != null) {
                    reserved.add(box);
                }
            }
            final Location base = this.settlerSpot(w, k.capitalX(), k.capitalZ(), reserved);
            for (int i = 0; i < this.plugin.settings().npcInitialSettlers; i++) {
                this.plugin.citizens().spawn(id, base.clone().add((i % 3) - 1, 0, (i / 3) % 3 - 1));
            }
            this.plugin.getLogger().info("Kraj NPC " + k.name() + ": " + this.plugin.settings().npcInitialSettlers
                + " osadników, budynki startowe: " + reserved.size());
        }));
    }

    private record Candidate(int x, int y, int z, int q, int score) {
    }

    /** Finds the flattest free spot on own loaded land around the capital and orders the building for free. */
    private BlockBox orderStarting(final UUID country, final String type, final World w, final int cx, final int cz) {
        final ConstructionService cs = this.plugin.construction();
        final List<Candidate> candidates = new ArrayList<>();
        for (int q = 0; q < 4; q += 1) {
            final TemplateLibrary.Runtime rt = this.plugin.templates().runtime(type, q);
            if (rt == null) {
                this.plugin.getLogger().warning("Budynek startowy " + type + " nie istnieje.");
                return null;
            }
            for (int x = cx * 16 - 16; x < cx * 16 + 32; x += 2) {
                for (int z = cz * 16 - 16; z < cz * 16 + 32; z += 2) {
                    final Placement probe = Placement.centered(w.getName(), rt.template(), x, 0, z);
                    final BlockBox box = probe.box();
                    if (!ConstructionService.chunksLoaded(w, box) || !cs.onOwnLand(country, box)) {
                        continue;
                    }
                    final int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                    int score = 0;
                    for (final int[] c : new int[][] {{box.minX(), box.minZ()}, {box.maxX(), box.minZ()},
                        {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}}) {
                        score += Math.abs(w.getHighestBlockYAt(c[0], c[1], HeightMap.MOTION_BLOCKING_NO_LEAVES) - y);
                    }
                    candidates.add(new Candidate(x, y, z, q, score));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score));
        int tries = 0;
        for (final Candidate c : candidates) {
            if (++tries > 60) {
                break;
            }
            final BuildOrderResult r = cs.order(country, type, w, c.x(), c.y(), c.z(), c.q(),
                this.plugin.settings().npcStartingFree, "NPC");
            if (r == BuildOrderResult.OK) {
                return Placement.centered(w.getName(), this.plugin.templates().runtime(type, c.q()).template(), c.x(), c.y(), c.z()).box();
            }
            if (r == BuildOrderResult.TOO_MANY_ORDERS || r == BuildOrderResult.INSUFFICIENT_FUNDS) {
                break;
            }
        }
        this.plugin.getLogger().warning("Nie znaleziono miejsca na budynek startowy " + type + " kraju NPC.");
        return null;
    }

    private Location settlerSpot(final World w, final int cx, final int cz, final List<BlockBox> reserved) {
        for (int r = 0; r < 8; r++) {
            for (final int[] d : new int[][] {{0, 0}, {r, 0}, {-r, 0}, {0, r}, {0, -r}, {r, r}, {-r, -r}}) {
                final int x = cx * 16 + 8 + d[0];
                final int z = cz * 16 + 8 + d[1];
                boolean free = true;
                for (final BlockBox b : reserved) {
                    if (x >= b.minX() - 1 && x <= b.maxX() + 1 && z >= b.minZ() - 1 && z <= b.maxZ() + 1) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
                }
            }
        }
        final int x = cx * 16 + 8;
        final int z = cz * 16 + 8;
        return new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
    }
}
