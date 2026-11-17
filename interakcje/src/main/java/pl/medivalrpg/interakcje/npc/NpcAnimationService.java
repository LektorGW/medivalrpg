// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.interakcje.InterakcjePlugin;
import pl.medivalrpg.interakcje.anim.AnimationBackend;
import pl.medivalrpg.interakcje.anim.AnimationCatalog;
import pl.medivalrpg.interakcje.api.WorkAnimations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Work animations of NPC Mannequins ({@link WorkAnimations}, registered in the ServicesManager).
 *
 * <p>Modules state what an NPC does ({@link #play}); every {@code scan-interval-ticks} this service
 * picks the NPCs to render with {@link NpcSelection} (a viewer with the resource pack within range,
 * nearest first, global limit) and starts / stops BetterModel trackers within a time budget. An NPC
 * nobody sees costs nothing: no tracker exists for it (BetterModel would not tick it anyway).
 * Main thread only.
 */
public final class NpcAnimationService implements WorkAnimations {
    private final InterakcjePlugin plugin;
    private final NamespacedKey keyWork;
    private final NamespacedKey keyTest;
    private NpcSettings settings = NpcSettings.from(null);
    private final Map<UUID, Npc> npcs = new HashMap<>();
    /** Mojang profile name / uuid -> textures (resolved once per server run). */
    private final Map<String, String> skinCache = new HashMap<>();
    private final Set<String> skinPending = new HashSet<>();
    private final Set<String> skinFailed = new HashSet<>();
    private BukkitTask task;
    private final Stats stats = new Stats();
    private boolean warmScan;

    /** Diagnostics for {@code /interakcje npc status} and the Paper test. */
    public static final class Stats {
        public long scans;
        public double lastScanMs;
        public double maxScanMs;
        public long started;
        public long stopped;
        public long deferredByBudget;
        public long deferredByLimit;
        /** The very first tracker of a server run (BetterModel warms up: class loading, skin, packets). */
        public double firstStartMs = -1;
        /** Slowest single start after the first one. */
        public double maxStartMs;
        /** Slowest scan after the first start (the steady state the budget is about). */
        public double maxScanMsWarm;
        /** Moving average of one start / stop (BetterModel creates or removes the display entities for the viewers). */
        public double avgStartMs = 2.0;
        public double avgStopMs = 1.0;
    }

    private static final class Npc {
        final UUID id;
        String loopClip;      // looping work animation (API name or clip), null = none
        String shownClip;     // what is rendered right now
        boolean shownLoop;
        AnimationBackend.Handle handle;

        Npc(UUID id) {
            this.id = id;
        }
    }

    public NpcAnimationService(InterakcjePlugin plugin) {
        this.plugin = plugin;
        this.keyWork = new NamespacedKey(plugin, "work");
        this.keyTest = new NamespacedKey(plugin, "test_npc");
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        settings = NpcSettings.from(plugin.getConfig().getConfigurationSection("npc-animations"));
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 40L, settings.scanTicks());
        int found = 0;
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (adopt(e)) {
                    found++;
                }
            }
        }
        if (found > 0) {
            plugin.getLogger().info("Animacje pracy NPC: wczytano zapisany stan " + found + " NPC.");
        }
    }

    /** {@code /interakcje reload}: new limits; running trackers stay. */
    public void reloadSettings() {
        NpcSettings next = NpcSettings.from(plugin.getConfig().getConfigurationSection("npc-animations"));
        boolean newPeriod = next.scanTicks() != settings.scanTicks();
        settings = next;
        if (newPeriod && task != null) {
            task.cancel();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 20L, settings.scanTicks());
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Npc n : npcs.values()) {
            hide(n);
        }
        npcs.clear();
        removeTestNpcs();
    }

    public NpcSettings settings() {
        return settings;
    }

    public Stats stats() {
        return stats;
    }

    // ------------------------------------------------------------------ API

    @Override
    public boolean play(Entity entity, String animation, boolean loop) {
        if (entity == null || !allowed(entity)) {
            return false;
        }
        AnimationCatalog.Clip clip = catalog().workClip(animation);
        if (clip == null) {
            return false;
        }
        String name = animation.trim().toLowerCase(Locale.ROOT);
        Npc n = npcs.computeIfAbsent(entity.getUniqueId(), Npc::new);
        if (loop) {
            boolean changed = !name.equals(n.loopClip);
            n.loopClip = name;
            if (settings.persist()) {
                entity.getPersistentDataContainer().set(keyWork, PersistentDataType.STRING, name);
            }
            if (changed && n.handle != null) {
                hide(n); // the next scan shows the new animation
            }
            return true;
        }
        // a one-off gesture: only when somebody can see it now
        if (!settings.enabled() || !plugin.animations().active() || !(entity instanceof LivingEntity le)) {
            return false;
        }
        double d2 = nearestViewerSq(entity);
        double r = settings.range();
        if (d2 > r * r || (n.handle == null && shownCount() >= settings.maxActive())) {
            return false;
        }
        String raw = skin(le);
        if (raw == null && !skinOptional(le)) {
            return false;
        }
        return show(n, le, clip, false, raw);
    }

    @Override
    public void stop(Entity entity) {
        if (entity == null) {
            return;
        }
        Npc n = npcs.remove(entity.getUniqueId());
        if (n != null) {
            hide(n);
        }
        entity.getPersistentDataContainer().remove(keyWork);
    }

    @Override
    public String current(Entity entity) {
        Npc n = entity == null ? null : npcs.get(entity.getUniqueId());
        return n == null ? null : n.loopClip;
    }

    @Override
    public boolean isShown(Entity entity) {
        Npc n = entity == null ? null : npcs.get(entity.getUniqueId());
        return n != null && n.handle != null;
    }

    @Override
    public Set<String> animations() {
        return new LinkedHashSet<>(catalog().work().keySet());
    }

    // ------------------------------------------------------------------ entities

    /** Chunk load / startup: picks up an NPC with a stored work animation. */
    public boolean adopt(Entity e) {
        if (!allowed(e)) {
            return false;
        }
        String stored = e.getPersistentDataContainer().get(keyWork, PersistentDataType.STRING);
        if (stored == null || catalog().workClip(stored) == null) {
            return false;
        }
        npcs.computeIfAbsent(e.getUniqueId(), Npc::new).loopClip = stored;
        return true;
    }

    /** Chunk unload / removal: drop the tracker (the stored state stays on the entity). */
    public void forget(Entity e) {
        Npc n = npcs.remove(e.getUniqueId());
        if (n != null) {
            hide(n);
        }
    }

    private boolean allowed(Entity e) {
        return e instanceof LivingEntity && !(e instanceof Player)
                && settings.entityTypes().contains(e.getType().name());
    }

    // ------------------------------------------------------------------ scheduling

    private void scan() {
        long t0 = System.nanoTime();
        if (!settings.enabled() || !plugin.animations().active()) {
            if (shownCount() > 0) {
                npcs.values().forEach(this::hide);
            }
            return;
        }
        List<NpcSelection.Candidate> candidates = new ArrayList<>(npcs.size());
        Set<UUID> shown = new HashSet<>();
        for (Npc n : new ArrayList<>(npcs.values())) {
            Entity e = Bukkit.getEntity(n.id);
            if (e == null || !e.isValid()) {
                hide(n);
                npcs.remove(n.id);
                continue;
            }
            if (n.handle != null) {
                shown.add(n.id);
            }
            if (n.loopClip == null && n.handle == null) {
                continue;
            }
            if (n.handle != null && !n.shownLoop) {
                candidates.add(new NpcSelection.Candidate(n.id, 0.0, true)); // a running gesture finishes
                continue;
            }
            boolean ready = n.loopClip != null && (skin((LivingEntity) e) != null || skinOptional((LivingEntity) e));
            candidates.add(new NpcSelection.Candidate(n.id, ready ? nearestViewerSq(e) : Double.POSITIVE_INFINITY,
                    ready));
        }
        NpcSelection.Plan plan = NpcSelection.plan(candidates, shown, settings.range(), settings.hysteresis(),
                settings.maxActive());
        // The budget covers stops and starts; each one is predicted from the moving average, and at least one
        // change happens per scan so the plan always progresses (one change may overrun the budget).
        double budgetMs = settings.budgetMs();
        int changes = 0;
        for (UUID id : plan.stop()) {
            Npc n = npcs.get(id);
            if (n == null) {
                continue;
            }
            if (changes > 0 && elapsedMs(t0) + stats.avgStopMs > budgetMs) {
                break; // a far-away NPC keeps its tracker a little longer: nobody near sees it anyway
            }
            long s0 = System.nanoTime();
            hide(n);
            stats.avgStopMs = 0.8 * stats.avgStopMs + 0.2 * elapsedMs(s0);
            changes++;
        }
        int starts = 0;
        for (UUID id : plan.start()) {
            if (starts >= settings.maxStartsPerScan()
                    || (changes > 0 && elapsedMs(t0) + stats.avgStartMs > budgetMs)) {
                stats.deferredByBudget += plan.start().size() - starts;
                break;
            }
            Npc n = npcs.get(id);
            Entity e = n == null ? null : Bukkit.getEntity(id);
            if (n == null || !(e instanceof LivingEntity le)) {
                continue;
            }
            AnimationCatalog.Clip clip = catalog().workClip(n.loopClip);
            if (clip != null && show(n, le, clip, true, skin(le))) {
                starts++;
                changes++;
            }
        }
        int beyondLimit = 0;
        for (NpcSelection.Candidate c : candidates) {
            if (c.ready() && !plan.wanted().contains(c.id()) && c.nearestViewerSq() <= settings.range() * settings.range()) {
                beyondLimit++;
            }
        }
        stats.deferredByLimit += beyondLimit;
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        stats.scans++;
        stats.lastScanMs = ms;
        stats.maxScanMs = Math.max(stats.maxScanMs, ms);
        if (!warmScan) {
            stats.maxScanMsWarm = Math.max(stats.maxScanMsWarm, ms);
        }
        warmScan = false;
    }

    private boolean show(Npc n, LivingEntity e, AnimationCatalog.Clip clip, boolean loop, String raw) {
        hide(n);
        AnimationBackend backend = plugin.animations().backend();
        long t0 = System.nanoTime();
        String name = e instanceof Mannequin m && m.getProfile().name() != null ? m.getProfile().name() : "NPC";
        AnimationBackend.Handle h;
        try {
            h = backend.playEntity(e, clip.model(), clip.name(), raw, name, loop, viewers(),
                    () -> onMain(() -> gestureEnded(n.id)));
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Animacja NPC " + e.getUniqueId() + " nie wystartowała", ex);
            return false;
        }
        double took = (System.nanoTime() - t0) / 1_000_000.0;
        if (stats.firstStartMs < 0) {
            stats.firstStartMs = took;
            warmScan = true;
        } else {
            stats.maxStartMs = Math.max(stats.maxStartMs, took);
            stats.avgStartMs = 0.8 * stats.avgStartMs + 0.2 * took;
        }
        if (h == null) {
            return false;
        }
        n.handle = h;
        n.shownClip = clip.name();
        n.shownLoop = loop;
        stats.started++;
        return true;
    }

    private static double elapsedMs(long since) {
        return (System.nanoTime() - since) / 1_000_000.0;
    }

    private void gestureEnded(UUID id) {
        Npc n = npcs.get(id);
        if (n == null || n.shownLoop) {
            return;
        }
        hide(n);
        if (n.loopClip == null) {
            npcs.remove(id);
        }
        // a looping work animation comes back with the next scan
    }

    private void hide(Npc n) {
        if (n.handle != null) {
            AnimationBackend.Handle h = n.handle;
            n.handle = null;
            n.shownClip = null;
            h.stop();
            stats.stopped++;
        }
    }

    private int shownCount() {
        int c = 0;
        for (Npc n : npcs.values()) {
            if (n.handle != null) {
                c++;
            }
        }
        return c;
    }

    public int count() {
        return npcs.size();
    }

    public int shown() {
        return shownCount();
    }

    private Predicate<UUID> viewers() {
        boolean onlyPack = plugin.animations().settings().onlyForPackUsers();
        return viewer -> !onlyPack || plugin.resourcePack().hasPack(viewer);
    }

    private double nearestViewerSq(Entity e) {
        Location l = e.getLocation();
        boolean onlyPack = plugin.animations().settings().onlyForPackUsers();
        double best = Double.POSITIVE_INFINITY;
        for (Player p : e.getWorld().getPlayers()) {
            if (onlyPack && !plugin.resourcePack().hasPack(p.getUniqueId())) {
                continue;
            }
            best = Math.min(best, p.getLocation().distanceSquared(l));
        }
        return best;
    }

    private AnimationCatalog catalog() {
        return plugin.animations().catalog();
    }

    private void onMain(Runnable r) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    // ------------------------------------------------------------------ skins

    /** Raw Mojang textures of the NPC's profile, or {@code null} (unknown yet / none / failed). */
    private String skin(LivingEntity e) {
        if (!(e instanceof Mannequin m)) {
            return null;
        }
        ResolvableProfile rp = m.getProfile();
        for (ProfileProperty p : rp.properties()) {
            if ("textures".equals(p.getName())) {
                return p.getValue();
            }
        }
        if (rp.name() == null) {
            // no name: the vanilla default look (a random UUID picks one of the default skins); never ask
            // Mojang about random UUIDs of soldiers and rulers
            return null;
        }
        String key = "n:" + rp.name().toLowerCase(Locale.ROOT);
        String cached = skinCache.get(key);
        if (cached != null || skinFailed.contains(key) || !skinPending.add(key)) {
            return cached;
        }
        rp.resolve().whenComplete((PlayerProfile pp, Throwable err) -> onMain(() -> {
            skinPending.remove(key);
            String raw = null;
            if (err == null && pp != null) {
                for (ProfileProperty p : pp.getProperties()) {
                    if ("textures".equals(p.getName())) {
                        raw = p.getValue();
                    }
                }
            }
            if (raw == null) {
                skinFailed.add(key);
                plugin.getLogger().info("Animacje NPC: brak skina dla profilu " + key.substring(2)
                        + (settings.animateWithoutSkin() ? " – używam zapasowego." : " – NPC bez animacji."));
            } else {
                skinCache.put(key, raw);
            }
        }));
        return null;
    }

    /** May the NPC be animated without its own skin (default profile, or failed lookup with the fallback allowed)? */
    private boolean skinOptional(LivingEntity e) {
        if (!(e instanceof Mannequin m)) {
            return settings.animateWithoutSkin();
        }
        ResolvableProfile rp = m.getProfile();
        if (rp.name() == null && rp.properties().isEmpty()) {
            return true; // vanilla default look: BetterModel's fallback skin is as good
        }
        if (rp.name() == null) {
            return settings.animateWithoutSkin();
        }
        String key = "n:" + rp.name().toLowerCase(Locale.ROOT);
        return settings.animateWithoutSkin() && skinFailed.contains(key);
    }

    // ------------------------------------------------------------------ test NPCs (admin)

    /** Spawns a temporary (non-persistent) Mannequin with the player's skin in front of them. */
    public Mannequin spawnTest(Player p, String animation) {
        Location eye = p.getLocation();
        Location at = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(2.5));
        at.setYaw(eye.getYaw() + 180f);
        at.setPitch(0f);
        PlayerProfile profile = p.getPlayerProfile();
        Mannequin m = p.getWorld().spawn(at, Mannequin.class, mq -> {
            mq.setPersistent(false);
            mq.setImmovable(true);
            mq.setInvulnerable(true);
            mq.getPersistentDataContainer().set(keyTest, PersistentDataType.BYTE, (byte) 1);
            mq.setProfile(ResolvableProfile.resolvableProfile(profile));
            mq.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand().clone());
        });
        AnimationCatalog.Clip clip = catalog().workClip(animation);
        boolean loop = clip != null && clip.loop();
        play(m, animation, loop);
        return m;
    }

    public int removeTestNpcs() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) {
            Collection<Mannequin> all = w.getEntitiesByClass(Mannequin.class);
            for (Mannequin m : all) {
                if (m.getPersistentDataContainer().has(keyTest, PersistentDataType.BYTE)) {
                    forget(m);
                    m.remove();
                    n++;
                }
            }
        }
        return n;
    }

    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("NPC z animacją pracy: " + npcs.size() + ", pokazywane teraz: " + shownCount() + " / "
                + settings.maxActive() + " (zasięg " + (int) settings.range() + " bl.)");
        out.add(String.format(Locale.ROOT, "Skan co %d t: ostatni %.3f ms, maks. %.3f ms (budżet %.1f ms); start %d, stop %d",
                settings.scanTicks(), stats.lastScanMs, stats.maxScanMs, settings.budgetMs(), stats.started, stats.stopped));
        out.add(String.format(Locale.ROOT, "Start trackera: pierwszy %.1f ms (rozgrzewka), potem śr. %.2f / maks. %.2f ms; "
                + "stop śr. %.2f ms; skan po rozgrzewce maks. %.3f ms", stats.firstStartMs, stats.avgStartMs,
                stats.maxStartMs, stats.avgStopMs, stats.maxScanMsWarm));
        out.add("Odłożone (budżet / limit): " + stats.deferredByBudget + " / " + stats.deferredByLimit
                + "; skiny: " + skinCache.size() + " OK, " + skinPending.size() + " w toku, " + skinFailed.size() + " brak");
        return out;
    }
}
