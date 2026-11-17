// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.interakcje.InteractionDef;
import pl.medivalrpg.interakcje.InterakcjePlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Character animations of interactions, poses, dances and emotes: places two players for a pair
 * animation, starts both clips in the same server tick, ends them when someone walks away or
 * sneaks, and keeps loops (sitting, lying, holding hands) running. Main thread only.
 *
 * <p>Everything degrades gracefully: without BetterModel, with animations disabled or with a clip
 * missing, the callers get {@code null} and play the classic particle / sound effects instead.
 */
public final class AnimationService {
    public static final String RESOURCE_DIR = "bettermodel";
    /** Pair animations start one tick after the alignment teleport (so both players are in place). */
    static final int START_DELAY_TICKS = 1;
    private static final int STILL_TICKS_FOR_RESUME = 20;
    private static final double MAX_VERTICAL_MOVE = 0.6;

    private final InterakcjePlugin plugin;
    private AnimationSettings settings = AnimationSettings.from(null);
    private AnimationCatalog catalog = AnimationCatalog.EMPTY;
    private AnimationBackend backend = AnimationBackend.NONE;
    private BetterModelBackend betterModel;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> lastMove = new HashMap<>();
    private boolean ownTeleport;
    private int reloadAttempts;
    private Runnable onBackendReload = () -> { };

    /** An animation was started; the effects of the interaction belong to these ticks (from now). */
    public record Started(List<Integer> impactTicks) {
        public int firstImpact() {
            return impactTicks.isEmpty() ? 0 : impactTicks.getFirst();
        }
    }

    public enum Kind {
        INTERACTION, HOLD, POSE, SOLO
    }

    private final class Session {
        final UUID player;
        final Kind kind;
        final String animation;
        final String clip;
        final boolean loop;
        final boolean lock;
        final Float yaw;
        final UUID partner;
        Location anchor;
        AnimationBackend.Handle handle;
        BukkitTask timeout;

        Session(UUID player, Kind kind, String animation, String clip, boolean loop, boolean lock, Float yaw,
                UUID partner, Location anchor) {
            this.player = player;
            this.kind = kind;
            this.animation = animation;
            this.clip = clip;
            this.loop = loop;
            this.lock = lock;
            this.yaw = yaw;
            this.partner = partner;
            this.anchor = anchor;
        }
    }

    public AnimationService(InterakcjePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    /** onEnable: settings, catalogue, BetterModel backend, model files. */
    public void enable() {
        settings = AnimationSettings.from(plugin.getConfig().getConfigurationSection("animations"));
        catalog = loadCatalog();
        plugin.getLogger().info("Katalog animacji: " + catalog.summary());
        if (!settings.enabled()) {
            plugin.getLogger().info("Animacje postaci wyłączone (animations.enabled: false) – używam efektów.");
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            plugin.getLogger().info("Brak BetterModel – interakcje używają cząsteczek i dźwięków.");
            return;
        }
        try {
            betterModel = new BetterModelBackend(plugin, catalog.models());
            backend = betterModel;
            betterModel.onReload(this::backendReloaded);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "BetterModel jest, ale jego API nie działa – używam efektów.", e);
            betterModel = null;
            backend = AnimationBackend.NONE;
            return;
        }
        boolean changed = false;
        if (settings.installModels()) {
            try {
                changed = betterModel.installModels(RESOURCE_DIR + "/players");
                if (changed) {
                    plugin.getLogger().info("Zainstalowano modele animacji w plugins/BetterModel/players/ ("
                            + String.join(", ", catalog.models()) + ").");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Nie udało się skopiować modeli do plugins/BetterModel/players/", e);
            }
        }
        reloadAttempts = 0;
        // BetterModel loads its models asynchronously; check a bit later whether ours are in
        Bukkit.getScheduler().runTaskLater(plugin, this::ensureModelsLoaded, 60L);
        plugin.getLogger().info("Animacje postaci: " + backend.name() + (changed ? " (nowe modele)" : ""));
    }

    /** {@code /interakcje reload}: settings only (the backend and the catalogue stay). */
    public void reloadSettings() {
        settings = AnimationSettings.from(plugin.getConfig().getConfigurationSection("animations"));
    }

    /** Called (main thread) after every successful BetterModel reload. */
    public void onBackendReload(Runnable listener) {
        this.onBackendReload = listener;
    }

    private void ensureModelsLoaded() {
        if (betterModel == null || !plugin.isEnabled()) {
            return;
        }
        if (modelsComplete()) {
            return;
        }
        if (!settings.reloadBetterModel()) {
            plugin.getLogger().warning("Modele animacji nie są jeszcze wczytane przez BetterModel – wpisz /bm reload.");
            return;
        }
        if (reloadAttempts++ >= 5) {
            plugin.getLogger().warning("BetterModel nie wczytał modeli animacji – sprawdź plugins/BetterModel/players/.");
            return;
        }
        boolean started = betterModel.reload();
        plugin.getLogger().info(started ? "Przeładowuję BetterModel, żeby wczytał modele animacji…"
                : "BetterModel jeszcze się ładuje – spróbuję ponownie za chwilę.");
        Bukkit.getScheduler().runTaskLater(plugin, this::ensureModelsLoaded, 200L);
    }

    /** Every model of the catalogue is loaded and contains every clip. */
    public boolean modelsComplete() {
        if (!backend.ready()) {
            return false;
        }
        for (AnimationCatalog.Clip c : catalog.clips().values()) {
            if (!backend.hasClip(c.model(), c.name())) {
                return false;
            }
        }
        return true;
    }

    private void backendReloaded() {
        // BetterModel rebuilt its trackers: restart the loops, drop the one-shot animations
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) {
                sessions.remove(s.player);
                continue;
            }
            if (s.loop) {
                stopSession(s);
                begin(p, s.kind, s.animation, s.clip, s.lock, s.yaw, s.partner);
            } else {
                stop(s.player, false);
            }
        }
        onBackendReload.run();
    }

    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            stopSession(s);
        }
        sessions.clear();
        backend.shutdown();
    }

    private AnimationCatalog loadCatalog() {
        AnimationCatalog base = AnimationCatalog.EMPTY;
        try (InputStream in = plugin.getResource(RESOURCE_DIR + "/animations.json")) {
            if (in == null) {
                plugin.getLogger().warning("Brak bettermodel/animations.json w jarze – animacje wyłączone.");
            } else {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    base = AnimationCatalog.parse(r, w -> plugin.getLogger().warning("animations.json: " + w));
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się wczytać katalogu animacji", e);
        }
        String custom = settings.customCatalog();
        if (custom != null && !custom.isBlank()) {
            File f = new File(plugin.getDataFolder(), custom);
            if (f.isFile()) {
                try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                    AnimationCatalog extra = AnimationCatalog.parse(r, w -> plugin.getLogger().warning(custom + ": " + w));
                    base = AnimationCatalog.merge(base, extra);
                    plugin.getLogger().info("Dodatkowe animacje z " + custom + ": " + extra.summary());
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Nie udało się wczytać " + custom, e);
                }
            }
        }
        return base;
    }

    // ------------------------------------------------------------------ state

    /** Animations can be played right now. */
    public boolean active() {
        return settings.enabled() && backend.ready() && !catalog.isEmpty();
    }

    public AnimationCatalog catalog() {
        return catalog;
    }

    public AnimationSettings settings() {
        return settings;
    }

    /** The animation engine (BetterModel or the no-op one). */
    public AnimationBackend backend() {
        return backend;
    }

    /** The player has not moved for at least {@code ticks}. */
    public boolean stillFor(Player p, int ticks) {
        return Bukkit.getCurrentTick() - lastMove.getOrDefault(p.getUniqueId(), 0) >= ticks;
    }

    public boolean isAnimating(UUID player) {
        return sessions.containsKey(player);
    }

    /** The animation id the player is playing as {@code kind}, or {@code null}. */
    public String current(UUID player, Kind kind) {
        Session s = sessions.get(player);
        return s != null && s.kind == kind ? s.animation : null;
    }

    /** Short status for {@code /interakcje status}. */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("Silnik: " + backend.name() + (settings.enabled() ? "" : " (wyłączone w configu)"));
        out.add("Modele wczytane: " + (backend.ready() ? (modelsComplete() ? "tak" : "częściowo – brakuje klipów") : "nie"));
        out.add("Katalog: " + catalog.summary());
        out.add("Aktywne animacje: " + sessions.size());
        return out;
    }

    // ------------------------------------------------------------------ interactions

    /**
     * Starts the animation of an interaction.
     *
     * @return what was started, or {@code null} when the caller should use the plain effects
     */
    public Started playInteraction(InteractionDef def, Player sender, Player target) {
        String animationId = def.animationFor(target != null);
        if (!active() || animationId == null) {
            return null;
        }
        AnimationCatalog.Animation anim = catalog.animation(animationId);
        if (anim == null) {
            return null;
        }
        Kind kind = def.hold() != null ? Kind.HOLD : Kind.INTERACTION;
        if (anim.pair()) {
            return target == null ? null : startPair(anim, sender, target, kind, settings.alignMaxShift());
        }
        return startSolo(anim, sender, target, Kind.INTERACTION);
    }

    /** Dances and emotes. {@code lookAt} may be {@code null}. */
    public Started playSolo(String animationId, Player player, Player lookAt) {
        if (!active()) {
            return null;
        }
        AnimationCatalog.Animation anim = catalog.animation(animationId);
        if (anim == null || anim.pair()) {
            return null;
        }
        return startSolo(anim, player, lookAt, Kind.SOLO);
    }

    private Started startSolo(AnimationCatalog.Animation anim, Player p, Player lookAt, Kind kind) {
        if (!canAnimate(p)) {
            return null;
        }
        AnimationCatalog.RoleClip role = anim.role(AnimationCatalog.Role.SENDER);
        AnimationCatalog.Clip clip = catalog.clip(role.clip());
        if (clip == null || !backend.hasClip(clip.model(), clip.name())) {
            return null;
        }
        float yaw = p.getBodyYaw();
        if (anim.arrangement() == AnimationCatalog.Arrangement.LOOK && lookAt != null
                && lookAt.getWorld().equals(p.getWorld())) {
            Location a = p.getLocation();
            Location b = lookAt.getLocation();
            yaw = PairPlacement.yaw(b.getX() - a.getX(), b.getZ() - a.getZ());
            p.lookAt(lookAt, io.papermc.paper.entity.LookAnchor.EYES, io.papermc.paper.entity.LookAnchor.EYES);
        }
        stop(p.getUniqueId(), true);
        Session s = begin(p, kind, anim.id(), clip.name(), role.lock(), yaw, null);
        return s == null ? null : new Started(clip.impactTicks());
    }

    private Started startPair(AnimationCatalog.Animation anim, Player a, Player b, Kind kind, double maxShift) {
        if (!canAnimate(a) || !canAnimate(b) || !a.getWorld().equals(b.getWorld())) {
            return null;
        }
        Location la = a.getLocation();
        Location lb = b.getLocation();
        if (Math.abs(la.getY() - lb.getY()) > settings.maxHeightDifference()) {
            return null;
        }
        AnimationCatalog.RoleClip ra = anim.role(AnimationCatalog.Role.SENDER);
        AnimationCatalog.RoleClip rb = anim.role(AnimationCatalog.Role.TARGET);
        String clipA = ra.clip();
        String clipB = rb.clip();
        Float yawA = a.getBodyYaw();
        Float yawB = b.getBodyYaw();
        Location ta = null;
        Location tb = null;
        AnimationCatalog.Arrangement arrangement = anim.arrangement();
        if (arrangement == AnimationCatalog.Arrangement.FACE || arrangement == AnimationCatalog.Arrangement.SIDE) {
            PairPlacement.Result r;
            if (arrangement == AnimationCatalog.Arrangement.FACE) {
                r = PairPlacement.face(la.getX(), la.getZ(), lb.getX(), lb.getZ(), anim.distance(), la.getYaw());
            } else {
                boolean canMirror = anim.mirror().containsKey(AnimationCatalog.Role.SENDER)
                        && anim.mirror().containsKey(AnimationCatalog.Role.TARGET);
                r = PairPlacement.side(la.getX(), la.getZ(), lb.getX(), lb.getZ(), anim.distance(),
                        la.getYaw(), lb.getYaw(), canMirror);
                if (!r.targetOnRight()) {
                    clipA = anim.mirror().get(AnimationCatalog.Role.SENDER);
                    clipB = anim.mirror().get(AnimationCatalog.Role.TARGET);
                }
            }
            yawA = r.sender().yaw();
            yawB = r.target().yaw();
            double shift = r.maxShift(la.getX(), la.getZ(), lb.getX(), lb.getZ());
            if (settings.alignPairs()) {
                if (shift > maxShift) {
                    return null; // too far apart for a believable pose
                }
                Location sa = spot(la, r.sender());
                Location sb = spot(lb, r.target());
                if (shift > 0.03 && safe(sa) && safe(sb)) {
                    ta = sa;
                    tb = sb;
                } else if (Math.abs(la.distance(lb) - anim.distance()) > 0.45) {
                    return null; // cannot move them and they do not stand right
                }
            } else if (Math.abs(horizontal(la, lb) - anim.distance()) > 0.6) {
                return null;
            }
        }
        AnimationCatalog.Clip ca = catalog.clip(clipA);
        AnimationCatalog.Clip cb = catalog.clip(clipB);
        if (ca == null || cb == null || !backend.hasClip(ca.model(), ca.name()) || !backend.hasClip(cb.model(), cb.name())) {
            return null;
        }
        stop(a.getUniqueId(), true);
        stop(b.getUniqueId(), true);
        if (ta != null) {
            teleport(a, ta);
            teleport(b, tb);
        }
        UUID ida = a.getUniqueId();
        UUID idb = b.getUniqueId();
        Float fa = yawA;
        Float fb = yawB;
        String finalA = ca.name();
        String finalB = cb.name();
        // both clips start in the same tick, one tick after the teleport
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player pa = Bukkit.getPlayer(ida);
            Player pb = Bukkit.getPlayer(idb);
            if (pa == null || pb == null || plugin.poses().isPosing(ida) || plugin.poses().isPosing(idb)) {
                return;
            }
            Session sa = begin(pa, kind, anim.id(), finalA, ra.lock(), fa, idb);
            Session sb = begin(pb, kind, anim.id(), finalB, rb.lock(), fb, ida);
            if (sa == null || sb == null) {
                stop(ida, false);
                stop(idb, false);
            }
        }, START_DELAY_TICKS);
        List<Integer> impacts = new ArrayList<>();
        for (int t : ca.impactTicks()) {
            impacts.add(t + START_DELAY_TICKS);
        }
        return new Started(impacts);
    }

    // ------------------------------------------------------------------ holding hands

    /** The hold session ended: stop the hand-holding loop of both. */
    public void stopHold(UUID a, UUID b) {
        for (UUID id : new UUID[]{a, b}) {
            Session s = sessions.get(id);
            if (s != null && s.kind == Kind.HOLD) {
                stop(id, false);
            }
        }
    }

    /** Every half second of a hold session: restart the loop when both stand still again. */
    public void maintainHold(InteractionDef def, Player a, Player b) {
        if (!settings.resumeHold() || !active() || def.animation() == null
                || sessions.containsKey(a.getUniqueId()) || sessions.containsKey(b.getUniqueId())) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        if (now - lastMove.getOrDefault(a.getUniqueId(), 0) < STILL_TICKS_FOR_RESUME
                || now - lastMove.getOrDefault(b.getUniqueId(), 0) < STILL_TICKS_FOR_RESUME) {
            return;
        }
        if (!a.getWorld().equals(b.getWorld()) || horizontal(a.getLocation(), b.getLocation()) > 1.6) {
            return;
        }
        AnimationCatalog.Animation anim = catalog.animation(def.animation());
        if (anim != null && anim.pair()) {
            startPair(anim, a, b, Kind.HOLD, 0.7);
        }
    }

    // ------------------------------------------------------------------ poses

    /** Sitting / lying loop. {@code yaw} = the direction the body faces. */
    public boolean startPose(Player p, String animationId, float yaw) {
        if (!active()) {
            return false;
        }
        AnimationCatalog.Animation anim = catalog.animation(animationId);
        if (anim == null) {
            return false;
        }
        AnimationCatalog.RoleClip role = anim.role(AnimationCatalog.Role.SENDER);
        stop(p.getUniqueId(), true);
        return begin(p, Kind.POSE, anim.id(), role.clip(), false, yaw, null) != null;
    }

    public void stopPose(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.kind == Kind.POSE) {
            stop(p.getUniqueId(), false);
        }
    }

    // ------------------------------------------------------------------ sessions

    private Session begin(Player p, Kind kind, String animation, String clipName, boolean lock, Float yaw, UUID partner) {
        AnimationCatalog.Clip clip = catalog.clip(clipName);
        if (clip == null) {
            return null;
        }
        Session s = new Session(p.getUniqueId(), kind, animation, clip.name(), clip.loop(), lock, yaw, partner,
                p.getLocation());
        AnimationBackend.PlayOptions options = new AnimationBackend.PlayOptions(clip.loop(),
                settings.fixBodyYaw() ? yaw : null, clip.blendTicks() >= 0 ? clip.blendTicks() : settings.blendInTicks(),
                viewers(p));
        AnimationBackend.Handle handle = backend.play(p, clip.model(), clip.name(), options,
                () -> onMain(() -> finished(s)));
        if (handle == null) {
            return null;
        }
        s.handle = handle;
        if (!clip.props().isEmpty()) {
            plugin.ceremonies().startProps(p, clip, yaw != null ? yaw : p.getBodyYaw(), partner);
        }
        Session old = sessions.put(p.getUniqueId(), s);
        if (old != null && old != s) {
            cancel(old);
        }
        if (!clip.loop()) {
            // safety net in case the end callback never arrives
            s.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> finished(s), clip.lengthTicks() + 10L);
        }
        return s;
    }

    private Predicate<UUID> viewers(Player p) {
        UUID self = p.getUniqueId();
        boolean showSelf = settings.showToSelf();
        boolean onlyPack = settings.onlyForPackUsers();
        return viewer -> (showSelf || !viewer.equals(self)) && (!onlyPack || plugin.resourcePack().hasPack(viewer));
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

    private void finished(Session s) {
        if (sessions.get(s.player) == s) {
            sessions.remove(s.player);
        }
        stopSession(s);
    }

    private void cancel(Session s) {
        if (s.timeout != null) {
            s.timeout.cancel();
            s.timeout = null;
        }
    }

    private void stopSession(Session s) {
        cancel(s);
        if (s.handle != null) {
            s.handle.stop();
        }
    }

    /** Ends the player's animation (and, with {@code partnerToo}, the partner's matching one). */
    public void stop(UUID player, boolean partnerToo) {
        Session s = sessions.remove(player);
        if (s == null) {
            return;
        }
        stopSession(s);
        if (partnerToo && s.partner != null) {
            Session other = sessions.get(s.partner);
            if (other != null && player.equals(other.partner)) {
                sessions.remove(s.partner);
                stopSession(other);
            }
        }
    }

    // ------------------------------------------------------------------ events

    public void onMove(Player p, Location to) {
        UUID id = p.getUniqueId();
        lastMove.put(id, Bukkit.getCurrentTick());
        Session s = sessions.get(id);
        if (s == null || !s.lock || to == null) {
            return;
        }
        Location a = s.anchor;
        if (!a.getWorld().equals(to.getWorld())) {
            stop(id, true);
            return;
        }
        double dx = to.getX() - a.getX();
        double dz = to.getZ() - a.getZ();
        double tol = settings.moveTolerance();
        if (dx * dx + dz * dz > tol * tol || Math.abs(to.getY() - a.getY()) > MAX_VERTICAL_MOVE) {
            stop(id, true);
        }
    }

    public void onSneak(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.lock) {
            stop(p.getUniqueId(), true);
        }
    }

    public void onDamage(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.lock && settings.stopOnDamage()) {
            stop(p.getUniqueId(), true);
        }
    }

    public void onTeleport(Player p, Location to) {
        if (ownTeleport) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.kind == Kind.POSE) { // poses end through PoseManager (dismount / stand up)
            return;
        }
        // Small teleports (anticheat setbacks, lag corrections) must not end the animation — only real moves away.
        Location a = s.anchor;
        if (to != null && a != null && a.getWorld().equals(to.getWorld())) {
            double dx = to.getX() - a.getX();
            double dz = to.getZ() - a.getZ();
            double tol = Math.max(settings.moveTolerance(), 1.5);
            if (dx * dx + dz * dz <= tol * tol && Math.abs(to.getY() - a.getY()) <= MAX_VERTICAL_MOVE) {
                return;
            }
        }
        stop(p.getUniqueId(), true);
    }

    /** Quit, death, world change. */
    public void onGone(Player p) {
        stop(p.getUniqueId(), true);
        lastMove.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation") // isOnGround is client-reported; good enough to refuse obviously airborne players
    private boolean canAnimate(Player p) {
        return p.isOnline() && !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && !p.isInsideVehicle()
                && !p.isGliding() && !p.isFlying() && !p.isSwimming() && !p.isSleeping()
                && !plugin.poses().isPosing(p.getUniqueId()) && p.isOnGround();
    }

    private static Location spot(Location current, PairPlacement.Spot s) {
        Location l = current.clone();
        l.setX(s.x());
        l.setZ(s.z());
        l.setYaw(s.yaw());
        return l;
    }

    /** Feet and head free, something to stand on. */
    private static boolean safe(Location l) {
        World w = l.getWorld();
        Block feet = w.getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() + 0.01), l.getBlockZ());
        Block head = feet.getRelative(0, 1, 0);
        Block below = w.getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.05), l.getBlockZ());
        boolean feetOk = feet.isPassable() && !feet.isLiquid() || feet.equals(below);
        return feetOk && head.isPassable() && !head.isLiquid() && !below.isPassable();
    }

    private static double horizontal(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private void teleport(Player p, Location to) {
        ownTeleport = true;
        try {
            p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            ownTeleport = false;
        }
    }
}
