// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.geometry.AreaPlanner;
import pl.medivalrpg.kraje.geometry.RegenPlanner;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Batch;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Plan;
import pl.medivalrpg.kraje.geometry.RegenPlanner.Skip;
import pl.medivalrpg.kraje.integration.WorldEditRegen;
import pl.medivalrpg.kraje.integration.WorldEditSelection;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.util.ChunkKeys;
import pl.medivalrpg.kraje.util.Format;
import pl.medivalrpg.kraje.util.SafeSpot;

/**
 * Terrain restore: {@code /kraj admin przywroc <kraj> [promien <n>|zaznaczenie] [potwierdz]},
 * {@code /kraj admin przywroc teren promien <n>|zaznaczenie [potwierdz]}, {@code /kraj admin przywroc stop} and
 * {@code /kraj admin usun <kraj> przywroc [potwierdz]}. Chunks are regenerated from the world seed through WorldEdit
 * ({@link WorldEditRegen}); chunks of protected areas (the spawn) are never touched. Always a preview first (count,
 * time estimate, warning, particles near the admin), then the confirmed plan runs as one global job: one batch of
 * {@code restore.chunks-per-batch} chunks every {@code restore.pause-ticks} ticks, chunks loaded asynchronously first,
 * every non-player entity of the batch removed, players standing in it moved to the new surface. Main thread only.
 */
final class AdminRestore {

    enum Kind { COUNTRY, WILD, STOP }

    enum Mode { ALL, RADIUS, SELECTION }

    /** Parsed arguments. {@code delete} = {@code /kraj admin usun <kraj> przywroc}. */
    record Request(Kind kind, Mode mode, String country, String firstWord, int amount, boolean confirm, boolean delete) {

        String areaArgs() {
            return switch (this.mode) {
                case ALL -> "";
                case RADIUS -> " " + RADIUS + " " + this.amount;
                case SELECTION -> " " + SELECTION;
            };
        }

        /** Same operation regardless of how the country name was typed (the country itself is compared by id). */
        boolean sameOperation(final Request other) {
            return this.kind == other.kind && this.mode == other.mode && this.amount == other.amount
                && this.delete == other.delete;
        }

        Request confirmed() {
            return new Request(this.kind, this.mode, this.country, this.firstWord, this.amount, true, this.delete);
        }
    }

    static final String RESTORE = "przywroc";
    static final String STOP = "stop";
    static final String WILD = "teren";
    static final String RADIUS = AdminTerritory.RADIUS;
    static final String SELECTION = AdminTerritory.SELECTION;
    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final long PREVIEW_PERIOD_TICKS = 10L;
    private static final long PREVIEW_MAX_SECONDS = 20L;
    private static final int PREVIEW_MAX_PARTICLES = 1500;

    private record Pending(Request request, UUID country, Plan plan, long expiresAt) {
    }

    private final KrajePlugin plugin;
    private final KrajCommand root;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, BukkitTask> previews = new HashMap<>();
    private Job job;
    /** Measured main-thread time of one chunk (0 = not measured yet → config estimate). */
    private double msPerChunk;

    AdminRestore(final KrajePlugin plugin, final KrajCommand root) {
        this.plugin = plugin;
        this.root = root;
    }

    // ------------------------------------------------------------------ parsing (pure)

    /** {@code /kraj admin przywroc ...} (arguments after the subcommand). */
    static Request parse(final String[] args) {
        final List<String> tokens = new ArrayList<>(Arrays.asList(args));
        tokens.removeIf(String::isBlank);
        if (tokens.size() == 1 && tokens.getFirst().equalsIgnoreCase(STOP)) {
            return new Request(Kind.STOP, Mode.ALL, "", STOP, 0, false, false);
        }
        boolean confirm = false;
        if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(KrajCommand.CONFIRM)) {
            confirm = true;
            tokens.removeLast();
        }
        Mode mode = Mode.ALL;
        int amount = 0;
        if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(SELECTION)) {
            mode = Mode.SELECTION;
            tokens.removeLast();
        } else if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(RADIUS)) {
            throw new KrajeException("usage-admin-przywroc");
        } else if (tokens.size() >= 2 && tokens.get(tokens.size() - 2).equalsIgnoreCase(RADIUS)) {
            mode = Mode.RADIUS;
            amount = KrajCommand.parseInt(tokens.getLast(), -1);
            if (amount < 0 || amount > AreaPlanner.MAX_RADIUS) {
                throw new KrajeException("admin-area-radius-range", KrajCommand.unparsed("max", AreaPlanner.MAX_RADIUS));
            }
            tokens.removeLast();
            tokens.removeLast();
        }
        if (tokens.isEmpty()) {
            throw new KrajeException("usage-admin-przywroc");
        }
        if (tokens.size() == 1 && tokens.getFirst().equalsIgnoreCase(WILD) && mode != Mode.ALL) {
            return new Request(Kind.WILD, mode, "", WILD, amount, confirm, false);
        }
        return new Request(Kind.COUNTRY, mode, String.join(" ", tokens), tokens.getFirst(), amount, confirm, false);
    }

    /** {@code true} when the arguments of {@code /kraj admin usun} ask for the restore ({@code <kraj> przywroc [potwierdz]}). */
    static boolean isDeleteRestore(final String[] args) {
        final List<String> tokens = new ArrayList<>(Arrays.asList(args));
        tokens.removeIf(String::isBlank);
        if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(KrajCommand.CONFIRM)) {
            tokens.removeLast();
        }
        return tokens.size() >= 2 && tokens.getLast().equalsIgnoreCase(RESTORE);
    }

    /** {@code /kraj admin usun <kraj> przywroc [potwierdz]} (arguments after {@code usun}). */
    static Request parseDelete(final String[] args) {
        final List<String> tokens = new ArrayList<>(Arrays.asList(args));
        tokens.removeIf(String::isBlank);
        boolean confirm = false;
        if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(KrajCommand.CONFIRM)) {
            confirm = true;
            tokens.removeLast();
        }
        if (tokens.size() < 2 || !tokens.getLast().equalsIgnoreCase(RESTORE)) {
            throw new KrajeException("usage-admin-usun");
        }
        tokens.removeLast();
        return new Request(Kind.COUNTRY, Mode.ALL, String.join(" ", tokens), tokens.getFirst(), 0, confirm, true);
    }

    // ------------------------------------------------------------------ entry

    void handle(final CommandSender sender, final Request request) {
        if (request.kind() == Kind.STOP) {
            this.stop(sender);
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled(WorldEditSelection.PLUGIN)) {
            throw new KrajeException("admin-restore-no-worldedit");
        }
        if (this.job != null) {
            throw new KrajeException("admin-restore-busy", KrajCommand.unparsed("done", Format.number(this.job.processed())),
                KrajCommand.unparsed("total", Format.number(this.job.total)));
        }
        final Country c = request.kind() == Kind.COUNTRY ? this.resolveCountry(request) : null;
        final UUID senderId = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        if (request.confirm()) {
            final Pending p = this.pending.get(senderId);
            if (p != null && p.request().sameOperation(request) && Objects.equals(p.country(), c == null ? null : c.id())
                && p.expiresAt() >= System.currentTimeMillis()) {
                this.pending.remove(senderId);
                this.cancel(this.previews.remove(senderId));
                if (request.delete()) {
                    this.deleteAndRestore(sender, c);
                } else {
                    this.start(sender, this.target(c), p.plan(), c, 1L);
                }
                return;
            }
        }
        final Plan plan = this.plan(sender, request, c);
        if (plan.count() > RegenPlanner.MAX_CHUNKS) {
            throw new KrajeException("admin-restore-too-large", KrajCommand.unparsed("count", Format.number(plan.count())),
                KrajCommand.unparsed("max", Format.number(RegenPlanner.MAX_CHUNKS)));
        }
        if (plan.count() == 0) {
            this.plugin.messages().send(sender, "admin-restore-nothing", Placeholder.component("target", this.target(c)));
            this.sendSkips(sender, plan);
            return;
        }
        if (request.confirm()) {
            this.plugin.messages().send(sender, "admin-area-confirm-stale");
        }
        this.preview(sender, senderId, c, request, plan);
    }

    private Country resolveCountry(final Request request) {
        final Country whole = this.plugin.manager().byName(request.country());
        if (whole != null) {
            return whole;
        }
        if (request.firstWord().equalsIgnoreCase(WILD) && this.plugin.manager().byName(WILD) == null) {
            throw new KrajeException("usage-admin-przywroc");
        }
        return this.root.requireCountryArg(request.firstWord());
    }

    private Plan plan(final CommandSender sender, final Request request, final Country c) {
        if (request.kind() == Kind.COUNTRY && request.mode() == Mode.ALL) {
            return this.wholeCountry(c);
        }
        final Player player = KrajCommand.requirePlayer(sender);
        final String world = player.getWorld().getName();
        final ChunkPos here = ChunkPos.of(player.getLocation());
        final List<Long> area = switch (request.mode()) {
            case RADIUS -> AreaPlanner.square(here.x(), here.z(), request.amount());
            case SELECTION -> AreaPlanner.sortedFrom(
                WorldEditSelection.chunks(player, AreaPlanner.MAX_SELECTION_CHUNKS), here.x(), here.z());
            case ALL -> throw new IllegalStateException();
        };
        if (c != null) {
            return RegenPlanner.countryPart(world, area, c.claimsIn(world), this.protectedIn(world));
        }
        final LongPredicate prot = this.protectedIn(world);
        return RegenPlanner.wilderness(world, area, new RegenPlanner.Land() {
            @Override
            public boolean claimed(final long key) {
                return AdminRestore.this.plugin.manager().ownerAt(world, key) != null;
            }

            @Override
            public boolean isProtected(final long key) {
                return prot.test(key);
            }
        });
    }

    private Plan wholeCountry(final Country c) {
        final Map<String, Set<Long>> claims = new LinkedHashMap<>();
        c.claimsMap().forEach((w, keys) -> claims.put(w, new HashSet<>(keys)));
        return RegenPlanner.country(claims, this::protectedIn);
    }

    private LongPredicate protectedIn(final String world) {
        return key -> this.plugin.protectedAreas().isProtected(world, ChunkKeys.x(key), ChunkKeys.z(key));
    }

    private Component target(final Country c) {
        final Messages m = this.plugin.messages();
        return c == null ? m.get("admin-restore-target-wild") : m.get("admin-restore-target-country", KrajCommand.country(c));
    }

    // ------------------------------------------------------------------ preview

    private void preview(final CommandSender sender, final UUID senderId, final Country c, final Request request,
                         final Plan plan) {
        final long expiry = this.plugin.cfg().confirmExpiryMillis;
        this.pending.put(senderId, new Pending(request.confirmed(), c == null ? null : c.id(), plan,
            System.currentTimeMillis() + expiry));
        final Messages m = this.plugin.messages();
        final String command = request.delete()
            ? "/kraj admin usun " + c.name() + " " + RESTORE + " " + KrajCommand.CONFIRM
            : "/kraj admin " + RESTORE + " " + (c == null ? WILD : c.name()) + request.areaArgs() + " " + KrajCommand.CONFIRM;
        final long millis = this.estimate(plan.count());
        m.send(sender, "admin-restore-preview", Placeholder.component("target", this.target(c)),
            KrajCommand.unparsed("count", Format.number(plan.count())),
            KrajCommand.unparsed("area", Format.area(plan.count())),
            KrajCommand.unparsed("time", Format.duration(millis)),
            KrajCommand.unparsed("worlds", String.join(", ", plan.chunks().keySet())));
        this.sendSkips(sender, plan);
        m.send(sender, "admin-restore-preview-warning");
        if (request.delete()) {
            m.send(sender, "admin-restore-preview-delete", KrajCommand.country(c),
                KrajCommand.unparsed("seconds", Math.max(1, this.plugin.cfg().restoreDeleteDelayTicks / 20)));
        } else if (c != null) {
            m.send(sender, "admin-restore-preview-alive", KrajCommand.country(c));
        }
        final long seconds = Math.min(expiry / 1000, PREVIEW_MAX_SECONDS);
        if (sender instanceof Player player && plan.chunks().containsKey(player.getWorld().getName())) {
            this.showParticles(player, new HashSet<>(plan.chunks().get(player.getWorld().getName())), seconds);
            m.send(sender, "admin-restore-preview-particles", KrajCommand.unparsed("seconds", seconds));
        }
        m.send(sender, "admin-restore-preview-confirm", KrajCommand.unparsed("seconds", expiry / 1000),
            KrajCommand.unparsed("command", command),
            Placeholder.styling("confirm", ClickEvent.runCommand(command)));
    }

    private void sendSkips(final CommandSender sender, final Plan plan) {
        final Messages m = this.plugin.messages();
        for (final Map.Entry<Skip, Integer> e : plan.skipped().entrySet()) {
            m.send(sender, "admin-restore-skip-" + e.getKey().name().toLowerCase(Locale.ROOT),
                KrajCommand.unparsed("count", Format.number(e.getValue())));
        }
    }

    private long estimate(final int chunks) {
        final double perChunk = this.msPerChunk > 0 ? this.msPerChunk : this.plugin.cfg().restoreEstimatedMsPerChunk;
        return RegenPlanner.estimateMillis(chunks, this.plugin.cfg().restoreChunksPerBatch, perChunk,
            this.plugin.cfg().restorePauseTicks);
    }

    /** Purple dust on the outline of the chunks to regenerate, only near the admin and only for them. */
    private void showParticles(final Player player, final Set<Long> chunks, final long seconds) {
        this.cancel(this.previews.remove(player.getUniqueId()));
        final List<AreaPlanner.Edge> edges = AreaPlanner.outline(chunks);
        final Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0xAB47BC), 1.6f);
        final long pulses = Math.max(1, seconds * 20 / PREVIEW_PERIOD_TICKS);
        final UUID id = player.getUniqueId();
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, new Runnable() {
            private long left = pulses;

            @Override
            public void run() {
                if (--this.left < 0 || !player.isOnline()) {
                    AdminRestore.this.cancel(AdminRestore.this.previews.remove(id));
                    return;
                }
                AdminTerritory.draw(player, edges, dust, new int[] {PREVIEW_MAX_PARTICLES});
            }
        }, 0L, PREVIEW_PERIOD_TICKS);
        this.previews.put(id, task);
    }

    // ------------------------------------------------------------------ execution

    /** Captures the chunk list, deletes the country (other plugins clean up on the disband event), then restores. */
    private void deleteAndRestore(final CommandSender sender, final Country c) {
        final Plan plan = this.wholeCountry(c);
        this.root.disbandCountry(c, ClaimCause.ADMIN);
        this.plugin.messages().send(sender, "admin-deleted", KrajCommand.country(c));
        this.root.notifyMembers(c, "admin-deleted-members", KrajCommand.country(c));
        this.plugin.getLogger().info("/kraj admin usun " + c.name() + " przywroc: usunięto kraj, przywracanie "
            + plan.count() + " chunków za " + this.plugin.cfg().restoreDeleteDelayTicks + " ticków");
        if (plan.count() == 0) {
            this.sendSkips(sender, plan);
            return;
        }
        // After the deletion the land is wilderness: regenerate only chunks nobody claimed in the meantime.
        this.start(sender, this.target(c), plan, null, this.plugin.cfg().restoreDeleteDelayTicks);
    }

    /**
     * Starts the global job.
     *
     * @param owner country that must still own each chunk; {@code null} = the chunk must be wilderness
     */
    private void start(final CommandSender sender, final Component target, final Plan plan, final Country owner,
                       final long delayTicks) {
        final UUID ownerId = owner == null ? null : owner.id();
        final List<Batch> batches = RegenPlanner.batches(plan.chunks(), this.plugin.cfg().restoreChunksPerBatch);
        this.job = new Job(sender, target, batches, plan.count(), plan.skippedCount(), ownerId);
        this.plugin.messages().send(sender, "admin-restore-started", Placeholder.component("target", target),
            KrajCommand.unparsed("count", Format.number(plan.count())),
            KrajCommand.unparsed("time", Format.duration(this.estimate(plan.count()) + delayTicks * 50L)));
        this.plugin.getLogger().info("Przywracanie terenu: start, " + plan.count() + " chunków w " + batches.size() + " porcjach");
        this.job.schedule(delayTicks);
    }

    private void stop(final CommandSender sender) {
        final Job running = this.job;
        if (running == null) {
            throw new KrajeException("admin-restore-not-running");
        }
        running.stop();
        this.job = null;
        final TagResolverSet tags = running.tags();
        this.plugin.messages().send(sender, "admin-restore-stopped", tags.target, tags.done, tags.total);
        final CommandSender owner = running.owner();
        if (owner != null && owner != sender && !(owner instanceof Player p && sender instanceof Player s
            && p.getUniqueId().equals(s.getUniqueId()))) {
            this.plugin.messages().send(owner, "admin-restore-stopped", tags.target, tags.done, tags.total);
        }
        this.plugin.getLogger().info("Przywracanie terenu: zatrzymane przez " + sender.getName() + " po "
            + running.done + " z " + running.total + " chunków");
    }

    private record TagResolverSet(TagResolver target,
                                  TagResolver done,
                                  TagResolver total) {
    }

    /** The running restore: one batch per step, the next step {@code restore.pause-ticks} after the previous one. */
    private final class Job {
        private final CommandSender sender;
        private final UUID senderId;
        private final Component target;
        private final List<Batch> batches;
        private final int total;
        private final int plannedSkips;
        private final UUID owner;
        private final long startedAt = System.currentTimeMillis();
        private int next;
        private int done;
        private int skippedMeanwhile;
        private int failed;
        private int entities;
        private int moved;
        private int lastMilestone;
        private boolean stopped;
        private BukkitTask task;

        Job(final CommandSender sender, final Component target, final List<Batch> batches, final int total,
            final int plannedSkips, final UUID owner) {
            this.sender = sender;
            this.senderId = sender instanceof Player p ? p.getUniqueId() : null;
            this.target = target;
            this.batches = batches;
            this.total = total;
            this.plannedSkips = plannedSkips;
            this.owner = owner;
        }

        int processed() {
            return this.done + this.skippedMeanwhile + this.failed;
        }

        /** The admin who started the job (a player only while online). */
        CommandSender owner() {
            return this.senderId == null ? this.sender : Bukkit.getPlayer(this.senderId);
        }

        TagResolverSet tags() {
            return new TagResolverSet(Placeholder.component("target", this.target),
                KrajCommand.unparsed("done", Format.number(this.done)),
                KrajCommand.unparsed("total", Format.number(this.total)));
        }

        void schedule(final long delay) {
            this.task = Bukkit.getScheduler().runTaskLater(AdminRestore.this.plugin, this::step, Math.max(1L, delay));
        }

        void stop() {
            this.stopped = true;
            if (this.task != null) {
                this.task.cancel();
            }
        }

        private boolean valid(final String world, final long key) {
            final KrajePlugin p = AdminRestore.this.plugin;
            if (p.protectedAreas().isProtected(world, ChunkKeys.x(key), ChunkKeys.z(key))) {
                return false;
            }
            return Objects.equals(p.manager().ownerAt(world, key), this.owner);
        }

        void step() {
            this.task = null;
            if (this.stopped || AdminRestore.this.job != this) {
                return;
            }
            if (this.next >= this.batches.size()) {
                this.finish();
                return;
            }
            final Batch batch = this.batches.get(this.next++);
            final World world = Bukkit.getWorld(batch.world());
            if (world == null) {
                this.failed += batch.chunks().size();
                this.afterBatch();
                return;
            }
            final List<Long> keys = new ArrayList<>();
            for (final long key : batch.chunks()) {
                if (this.valid(batch.world(), key)) {
                    keys.add(key);
                } else {
                    this.skippedMeanwhile++;
                }
            }
            if (keys.isEmpty()) {
                this.afterBatch();
                return;
            }
            final List<CompletableFuture<Chunk>> loads = new ArrayList<>(keys.size());
            for (final long key : keys) {
                loads.add(world.getChunkAtAsync(ChunkKeys.x(key), ChunkKeys.z(key), true));
            }
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenCompleteAsync((ignored, error) -> {
                if (this.stopped || AdminRestore.this.job != this) {
                    return;
                }
                if (error != null) {
                    AdminRestore.this.plugin.getLogger().warning("Przywracanie terenu: nie udało się wczytać chunków: " + error);
                    this.failed += keys.size();
                } else {
                    // ownership may have changed while the chunks were loading: check again right before regen
                    final List<Long> still = new ArrayList<>(keys.size());
                    for (final long key : keys) {
                        if (this.valid(batch.world(), key)) {
                            still.add(key);
                        } else {
                            this.skippedMeanwhile++;
                        }
                    }
                    if (!still.isEmpty()) {
                        this.regenerate(world, still);
                    }
                }
                this.afterBatch();
            }, Bukkit.getScheduler().getMainThreadExecutor(AdminRestore.this.plugin));
        }

        private void regenerate(final World world, final List<Long> keys) {
            final Set<Long> set = new HashSet<>(keys);
            final List<Player> inside = new ArrayList<>();
            for (final Player p : world.getPlayers()) {
                final Location l = p.getLocation();
                if (set.contains(ChunkKeys.key(l.getBlockX() >> 4, l.getBlockZ() >> 4))) {
                    if (p.isInsideVehicle()) {
                        p.leaveVehicle();
                    }
                    inside.add(p);
                }
            }
            for (final long key : keys) {
                this.entities += clearEntities(world.getChunkAt(ChunkKeys.x(key), ChunkKeys.z(key)));
            }
            final long t0 = System.nanoTime();
            boolean ok;
            try {
                ok = WorldEditRegen.regenerate(world, keys);
            } catch (final RuntimeException | LinkageError e) {
                AdminRestore.this.plugin.getLogger().log(Level.SEVERE, "Przywracanie terenu: błąd WorldEdit przy chunkach "
                    + describe(keys) + " w świecie " + world.getName(), e);
                ok = false;
            }
            final double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (ok) {
                this.done += keys.size();
                AdminRestore.this.msPerChunk = RegenPlanner.smooth(AdminRestore.this.msPerChunk, ms / keys.size());
            } else {
                this.failed += keys.size();
                AdminRestore.this.plugin.getLogger().warning("Przywracanie terenu: WorldEdit nie zregenerował chunków "
                    + describe(keys) + " w świecie " + world.getName());
            }
            // Leftovers of the old terrain (items, falling blocks, popped item frames).
            for (final long key : keys) {
                this.entities += clearEntities(world.getChunkAt(ChunkKeys.x(key), ChunkKeys.z(key)));
            }
            for (final Player p : inside) {
                if (p.isOnline()) {
                    this.rescue(p);
                }
            }
        }

        /** Moves a player who stood in a regenerated chunk to the new surface (nearest safe block). */
        private void rescue(final Player player) {
            final Location at = player.getLocation();
            final World world = at.getWorld();
            final int y = world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + 1;
            final Location top = new Location(world, at.getBlockX() + 0.5, y, at.getBlockZ() + 0.5, at.getYaw(), at.getPitch());
            final Location dest = AdminCommands.safe(top, SafeSpot.DEFAULT_RADIUS).orElse(top);
            player.setFallDistance(0f);
            player.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
            this.moved++;
            AdminRestore.this.plugin.messages().send(player, "admin-restore-player-moved");
        }

        private void afterBatch() {
            final int step = AdminRestore.this.plugin.cfg().restoreProgressStepPercent;
            final int milestone = RegenPlanner.milestone(this.processed(), this.total, step, this.lastMilestone);
            if (milestone > 0) {
                this.lastMilestone = milestone;
                final long left = AdminRestore.this.estimate(this.total - this.processed());
                final CommandSender to = this.owner();
                if (to != null) {
                    AdminRestore.this.plugin.messages().send(to, "admin-restore-progress",
                        Placeholder.component("target", this.target),
                        KrajCommand.unparsed("percent", milestone),
                        KrajCommand.unparsed("done", Format.number(this.processed())),
                        KrajCommand.unparsed("total", Format.number(this.total)),
                        KrajCommand.unparsed("time", Format.duration(left)));
                }
                AdminRestore.this.plugin.getLogger().info("Przywracanie terenu: " + milestone + "% (" + this.processed()
                    + "/" + this.total + String.format(Locale.ROOT, ", %.0f ms/chunk)", AdminRestore.this.msPerChunk));
            }
            if (this.next >= this.batches.size()) {
                this.finish();
            } else {
                this.schedule(AdminRestore.this.plugin.cfg().restorePauseTicks);
            }
        }

        private void finish() {
            if (AdminRestore.this.job == this) {
                AdminRestore.this.job = null;
            }
            final long elapsed = System.currentTimeMillis() - this.startedAt;
            AdminRestore.this.plugin.getLogger().info("Przywracanie terenu: koniec, zregenerowano " + this.done + "/"
                + this.total + " chunków (pominięto " + this.skippedMeanwhile + ", błędy " + this.failed + ", usunięto bytów "
                + this.entities + ", przeniesiono graczy " + this.moved + ") w " + Format.duration(elapsed)
                + String.format(Locale.ROOT, ", %.0f ms/chunk", AdminRestore.this.msPerChunk));
            final CommandSender to = this.owner();
            if (to == null) {
                return;
            }
            final Messages m = AdminRestore.this.plugin.messages();
            m.send(to, "admin-restore-done", Placeholder.component("target", this.target),
                KrajCommand.unparsed("count", Format.number(this.done)),
                KrajCommand.unparsed("total", Format.number(this.total)),
                KrajCommand.unparsed("entities", Format.number(this.entities)),
                KrajCommand.unparsed("time", Format.duration(elapsed)));
            if (this.plannedSkips > 0) {
                m.send(to, "admin-restore-skip-planned", KrajCommand.unparsed("count", Format.number(this.plannedSkips)));
            }
            if (this.skippedMeanwhile > 0) {
                m.send(to, "admin-area-changed-meanwhile", KrajCommand.unparsed("count", this.skippedMeanwhile));
            }
            if (this.failed > 0) {
                m.send(to, "admin-restore-failed", KrajCommand.unparsed("count", Format.number(this.failed)));
            }
        }
    }

    /** Removes every non-player entity of a chunk (NPCs, mannequins, displays, armour stands, animals, items…). */
    static int clearEntities(final Chunk chunk) {
        int removed = 0;
        for (final Entity e : chunk.getEntities()) {
            if (e instanceof Player) {
                continue;
            }
            if (!e.getPassengers().isEmpty()) {
                e.eject();
            }
            e.remove();
            removed++;
        }
        return removed;
    }

    private static String describe(final List<Long> keys) {
        final List<String> parts = new ArrayList<>();
        for (final long key : keys) {
            parts.add(ChunkKeys.x(key) + "," + ChunkKeys.z(key));
        }
        return "[" + String.join(" ", parts) + "]";
    }

    private void cancel(final BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** Plugin disable / reload: stop previews and the running job (logged with its progress). */
    void shutdown() {
        this.previews.values().forEach(BukkitTask::cancel);
        this.previews.clear();
        this.pending.clear();
        if (this.job != null) {
            this.plugin.getLogger().warning("Przywracanie terenu przerwane (wyłączenie pluginu) po " + this.job.done
                + " z " + this.job.total + " chunków");
            this.job.stop();
            this.job = null;
        }
    }
}
