// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.geometry.AreaPlanner;
import pl.medivalrpg.kraje.geometry.AreaPlanner.Plan;
import pl.medivalrpg.kraje.geometry.AreaPlanner.Skip;
import pl.medivalrpg.kraje.integration.WorldEditSelection;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.util.ChunkKeys;
import pl.medivalrpg.kraje.util.Format;

/**
 * Admin area tools: {@code /kraj admin zajmij <kraj> promien <n>|zaznaczenie}, {@code /kraj admin porzuc <kraj>
 * [promien <n>|zaznaczenie]} and {@code /kraj admin przylacz <kraj> [pierścienie]}. Everything uses
 * {@link ClaimCause#ADMIN} (no limits, no costs) and the usual per-chunk events. Operations above
 * {@link AreaPlanner#PREVIEW_THRESHOLD} chunks, and releases that would split the country, first show a preview
 * (counts, skipped chunks with reasons, particles on the chunk borders near the admin) and need {@code potwierdz};
 * the confirmed plan is executed {@link AreaPlanner#CHUNKS_PER_TICK} chunks per tick, every chunk re-checked.
 * Main thread only.
 */
final class AdminTerritory {

    enum Op { CLAIM, UNCLAIM, RINGS }

    enum Mode { HERE, RADIUS, SELECTION, RINGS }

    /** Parsed arguments (after the subcommand). {@code amount} = radius or number of rings. */
    record Request(Op op, Mode mode, String country, String firstWord, int amount, boolean confirm) {

        /** Text after {@code <kraj>} that repeats this request (for the clickable confirmation). */
        String areaArgs() {
            return switch (this.mode) {
                case HERE -> "";
                case RADIUS -> " promien " + this.amount;
                case SELECTION -> " zaznaczenie";
                case RINGS -> " " + this.amount;
            };
        }
    }

    static final String RADIUS = "promien";
    static final String SELECTION = "zaznaczenie";
    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final double PREVIEW_DISTANCE = 48.0;
    private static final int PREVIEW_MAX_PARTICLES = 1500;
    private static final long PREVIEW_PERIOD_TICKS = 10L;
    private static final long PREVIEW_MAX_SECONDS = 20L;

    /** What a {@code potwierdz} will execute: exactly the previewed chunks. */
    private record Pending(Request request, UUID country, String world, List<Long> chunks, long expiresAt) {
    }

    private final KrajePlugin plugin;
    private final KrajCommand root;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, BukkitTask> running = new HashMap<>();
    private final Map<UUID, BukkitTask> previews = new HashMap<>();

    AdminTerritory(final KrajePlugin plugin, final KrajCommand root) {
        this.plugin = plugin;
        this.root = root;
    }

    // ------------------------------------------------------------------ parsing (pure)

    /**
     * Parses {@code <kraj> [promien <n>|zaznaczenie] [potwierdz]} ({@link Op#CLAIM}/{@link Op#UNCLAIM}) or
     * {@code <kraj> [pierścienie] [potwierdz]} ({@link Op#RINGS}, default 1 ring). The country name may contain
     * spaces (NPC countries created with force): it is everything before the area keywords.
     */
    static Request parse(final Op op, final String[] args) {
        final String usage = switch (op) {
            case CLAIM -> "usage-admin-zajmij";
            case UNCLAIM -> "usage-admin-porzuc";
            case RINGS -> "usage-admin-przylacz";
        };
        final List<String> tokens = new ArrayList<>(Arrays.asList(args));
        tokens.removeIf(String::isBlank);
        boolean confirm = false;
        if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(KrajCommand.CONFIRM)) {
            confirm = true;
            tokens.removeLast();
        }
        Mode mode;
        int amount = 0;
        if (op == Op.RINGS) {
            mode = Mode.RINGS;
            amount = 1;
            if (tokens.size() >= 2) {
                final int n = KrajCommand.parseInt(tokens.getLast(), Integer.MIN_VALUE);
                if (n != Integer.MIN_VALUE) {
                    amount = n;
                    tokens.removeLast();
                }
            }
            if (amount < 1 || amount > AreaPlanner.MAX_RINGS) {
                throw new KrajeException("admin-area-rings-range", KrajCommand.unparsed("max", AreaPlanner.MAX_RINGS));
            }
        } else if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(SELECTION)) {
            mode = Mode.SELECTION;
            tokens.removeLast();
        } else if (!tokens.isEmpty() && tokens.getLast().equalsIgnoreCase(RADIUS)) {
            throw new KrajeException(usage);
        } else if (tokens.size() >= 2 && tokens.get(tokens.size() - 2).equalsIgnoreCase(RADIUS)) {
            mode = Mode.RADIUS;
            amount = KrajCommand.parseInt(tokens.getLast(), -1);
            if (amount < 0 || amount > AreaPlanner.MAX_RADIUS) {
                throw new KrajeException("admin-area-radius-range", KrajCommand.unparsed("max", AreaPlanner.MAX_RADIUS));
            }
            tokens.removeLast();
            tokens.removeLast();
        } else {
            mode = Mode.HERE;
        }
        if (tokens.isEmpty()) {
            throw new KrajeException(usage);
        }
        return new Request(op, mode, String.join(" ", tokens), tokens.getFirst(), amount, confirm);
    }

    // ------------------------------------------------------------------ entry

    void handle(final CommandSender sender, final Request request) {
        final Country c = this.resolveCountry(request);
        final UUID senderId = sender instanceof Player p ? p.getUniqueId() : CONSOLE;
        if (this.running.containsKey(senderId)) {
            throw new KrajeException("admin-area-busy");
        }
        if (request.confirm()) {
            final Pending p = this.pending.get(senderId);
            if (p != null && p.request().equals(request) && p.country().equals(c.id())
                && p.expiresAt() >= System.currentTimeMillis()) {
                this.pending.remove(senderId);
                this.execute(sender, senderId, c, request, p.world(), p.chunks(), Map.of(), Map.of());
                return;
            }
        }

        final String world = this.worldFor(sender, c, request);
        final AreaPlanner.Land land = this.land(world);
        final Plan plan;
        int splits = 0;
        switch (request.op()) {
            case CLAIM -> plan = AreaPlanner.claim(c.id(), this.area(sender, request), land);
            case UNCLAIM -> {
                final Long capitalKey = c.capital().world().equals(world) ? c.capital().key() : null;
                plan = AreaPlanner.unclaim(c.id(), this.area(sender, request), capitalKey, land);
                splits = AreaPlanner.splitsInto(c.claimsIn(world), plan.chunks());
            }
            case RINGS -> plan = AreaPlanner.rings(c.id(), c.claimsIn(world), request.amount(), land);
            default -> throw new IllegalStateException();
        }
        if (plan.count() == 0) {
            this.plugin.messages().send(sender, "admin-area-nothing", KrajCommand.country(c),
                Placeholder.component("action", this.actionName(request.op())),
                Placeholder.component("reasons", this.reasons(plan)));
            return;
        }
        if (plan.needsPreview() || splits > 0) {
            if (request.confirm()) {
                // No matching (or an expired) preview: show a fresh one instead of executing something unseen.
                this.plugin.messages().send(sender, "admin-area-confirm-stale");
            }
            this.preview(sender, senderId, c, request, world, plan, splits);
            return;
        }
        this.execute(sender, senderId, c, request, world, plan.chunks(), plan.skipCounts(), plan.foreign());
    }

    private Country resolveCountry(final Request request) {
        final Country whole = this.plugin.manager().byName(request.country());
        return whole != null ? whole : this.root.requireCountryArg(request.firstWord());
    }

    /** Player's world for the player modes; for rings the admin's world if the country has land there, else the capital's. */
    private String worldFor(final CommandSender sender, final Country c, final Request request) {
        if (request.mode() == Mode.RINGS) {
            if (sender instanceof Player p && !c.claimsIn(p.getWorld().getName()).isEmpty()) {
                return p.getWorld().getName();
            }
            return c.capital().world();
        }
        return KrajCommand.requirePlayer(sender).getWorld().getName();
    }

    private List<Long> area(final CommandSender sender, final Request request) {
        final Player player = KrajCommand.requirePlayer(sender);
        final ChunkPos here = ChunkPos.of(player.getLocation());
        return switch (request.mode()) {
            case HERE -> List.of(here.key());
            case RADIUS -> AreaPlanner.square(here.x(), here.z(), request.amount());
            case SELECTION -> {
                if (!Bukkit.getPluginManager().isPluginEnabled(WorldEditSelection.PLUGIN)) {
                    throw new KrajeException("admin-area-no-worldedit");
                }
                yield AreaPlanner.sortedFrom(WorldEditSelection.chunks(player, AreaPlanner.MAX_SELECTION_CHUNKS),
                    here.x(), here.z());
            }
            case RINGS -> throw new IllegalStateException();
        };
    }

    private AreaPlanner.Land land(final String world) {
        return new AreaPlanner.Land() {
            @Override
            public UUID ownerAt(final long key) {
                return AdminTerritory.this.plugin.manager().ownerAt(world, key);
            }

            @Override
            public boolean isProtected(final long key) {
                return AdminTerritory.this.plugin.protectedAreas().isProtected(world, ChunkKeys.x(key), ChunkKeys.z(key));
            }
        };
    }

    // ------------------------------------------------------------------ preview

    private void preview(final CommandSender sender, final UUID senderId, final Country c, final Request request,
                         final String world, final Plan plan, final int splits) {
        final long expiry = this.plugin.cfg().confirmExpiryMillis;
        final Request confirmed = new Request(request.op(), request.mode(), request.country(), request.firstWord(),
            request.amount(), true);
        this.pending.put(senderId, new Pending(confirmed, c.id(), world, plan.chunks(), System.currentTimeMillis() + expiry));
        final Messages m = this.plugin.messages();
        final String command = "/kraj admin " + subcommand(request.op()) + " " + c.name() + request.areaArgs() + " " + KrajCommand.CONFIRM;
        m.send(sender, "admin-area-preview", KrajCommand.country(c),
            Placeholder.component("action", this.actionName(request.op())),
            KrajCommand.unparsed("count", Format.number(plan.count())),
            KrajCommand.unparsed("area", Format.area(plan.count())));
        if (!plan.skipped().isEmpty()) {
            m.send(sender, "admin-area-preview-skipped", KrajCommand.unparsed("skipped", Format.number(plan.skipped().size())),
                Placeholder.component("reasons", this.reasons(plan)));
        }
        if (splits > 0) {
            m.send(sender, "admin-area-preview-split", KrajCommand.country(c), KrajCommand.unparsed("parts", splits));
        }
        final long seconds = Math.min(expiry / 1000, PREVIEW_MAX_SECONDS);
        if (sender instanceof Player player && player.getWorld().getName().equals(world)) {
            this.showParticles(player, plan, request.op(), seconds);
            m.send(sender, "admin-area-preview-particles", KrajCommand.unparsed("seconds", seconds));
        }
        m.send(sender, "admin-area-preview-confirm", KrajCommand.unparsed("seconds", expiry / 1000),
            KrajCommand.unparsed("command", command),
            Placeholder.styling("confirm", ClickEvent.runCommand(command)));
    }

    private static String subcommand(final Op op) {
        return switch (op) {
            case CLAIM -> "zajmij";
            case UNCLAIM -> "porzuc";
            case RINGS -> "przylacz";
        };
    }

    private Component actionName(final Op op) {
        return this.plugin.messages().get("admin-area-action-" + op.name().toLowerCase(Locale.ROOT));
    }

    /** "12 cudzych (Norvik ×8, Aldoria ×4), 3 na ziemi neutralnej" — or the "none" text. */
    private Component reasons(final Plan plan) {
        return this.reasons(plan.skipCounts(), plan.foreign());
    }

    private Component reasons(final Map<Skip, Integer> counts, final Map<UUID, Integer> foreign) {
        final Messages m = this.plugin.messages();
        if (counts.isEmpty()) {
            return m.get("admin-area-skip-none");
        }
        final List<Component> parts = new ArrayList<>();
        for (final Map.Entry<Skip, Integer> e : counts.entrySet()) {
            if (e.getKey() == Skip.FOREIGN) {
                final List<String> names = new ArrayList<>();
                foreign.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(f -> {
                        final Country other = this.plugin.manager().get(f.getKey());
                        names.add((other == null ? "?" : other.name()) + " ×" + f.getValue());
                    });
                if (foreign.size() > 5) {
                    names.add("…");
                }
                parts.add(m.get("admin-area-skip-foreign", KrajCommand.unparsed("count", e.getValue()),
                    KrajCommand.unparsed("countries", String.join(", ", names))));
            } else {
                parts.add(m.get("admin-area-skip-" + e.getKey().name().toLowerCase(Locale.ROOT),
                    KrajCommand.unparsed("count", e.getValue())));
            }
        }
        return Component.join(JoinConfiguration.separator(m.get("admin-area-skip-separator")), parts);
    }

    /**
     * Dust particles on the outline of the changed chunks (green = claim, orange = release) and of the skipped
     * foreign / protected chunks or the capital (red), only near the player and only for them.
     */
    private void showParticles(final Player player, final Plan plan, final Op op, final long seconds) {
        this.cancel(this.previews.remove(player.getUniqueId()));
        final List<AreaPlanner.Edge> changed = AreaPlanner.outline(new HashSet<>(plan.chunks()));
        final Set<Long> blockedKeys = new HashSet<>();
        plan.skipped().forEach((key, skip) -> {
            if (skip == Skip.FOREIGN || skip == Skip.PROTECTED || skip == Skip.CAPITAL) {
                blockedKeys.add(key);
            }
        });
        final List<AreaPlanner.Edge> blocked = AreaPlanner.outline(blockedKeys);
        final Particle.DustOptions main = new Particle.DustOptions(
            op == Op.UNCLAIM ? Color.fromRGB(0xFF9800) : Color.fromRGB(0x43A047), 1.6f);
        final Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(0xE53935), 1.6f);
        final long pulses = Math.max(1, seconds * 20 / PREVIEW_PERIOD_TICKS);
        final UUID id = player.getUniqueId();
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, new Runnable() {
            private long left = pulses;

            @Override
            public void run() {
                if (--this.left < 0 || !player.isOnline()) {
                    AdminTerritory.this.cancel(AdminTerritory.this.previews.remove(id));
                    return;
                }
                final int[] budget = {PREVIEW_MAX_PARTICLES};
                draw(player, changed, main, budget);
                draw(player, blocked, red, budget);
            }
        }, 0L, PREVIEW_PERIOD_TICKS);
        this.previews.put(id, task);
    }

    static void draw(final Player player, final List<AreaPlanner.Edge> edges, final Particle.DustOptions dust,
                             final int[] budget) {
        final double px = player.getLocation().getX();
        final double pz = player.getLocation().getZ();
        final double y = player.getLocation().getY() + 1.2;
        final double max = PREVIEW_DISTANCE * PREVIEW_DISTANCE;
        for (final AreaPlanner.Edge e : edges) {
            for (int i = 0; i <= 16 && budget[0] > 0; i += 2) {
                final double x = e.x1() + (e.x2() - e.x1()) * i / 16.0;
                final double z = e.z1() + (e.z2() - e.z1()) * i / 16.0;
                final double dx = x - px;
                final double dz = z - pz;
                if (dx * dx + dz * dz <= max) {
                    player.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
                    budget[0]--;
                }
            }
            if (budget[0] <= 0) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ execution

    private void execute(final CommandSender sender, final UUID senderId, final Country c, final Request request,
                         final String world, final List<Long> chunks, final Map<Skip, Integer> plannedSkips,
                         final Map<UUID, Integer> foreign) {
        this.cancel(this.previews.remove(senderId));
        final Job job = new Job(sender, senderId, c, request.op(), world, List.copyOf(chunks), plannedSkips, foreign);
        if (chunks.size() <= AreaPlanner.CHUNKS_PER_TICK) {
            job.step();
            return;
        }
        this.plugin.messages().send(sender, "admin-area-started", KrajCommand.country(c),
            Placeholder.component("action", this.actionName(request.op())),
            KrajCommand.unparsed("count", Format.number(chunks.size())),
            KrajCommand.unparsed("seconds", Math.max(1, (chunks.size() + AreaPlanner.CHUNKS_PER_TICK - 1)
                / AreaPlanner.CHUNKS_PER_TICK / 20)));
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, job::step, 1L, 1L);
        job.task = task;
        this.running.put(senderId, task);
    }

    /** A running operation: {@link AreaPlanner#CHUNKS_PER_TICK} chunks per {@link #step()}. */
    private final class Job {
        private final CommandSender sender;
        private final UUID senderId;
        private final Country country;
        private final Op op;
        private final String world;
        private final List<Long> chunks;
        private final Map<Skip, Integer> plannedSkips;
        private final Map<UUID, Integer> foreign;
        private int next;
        private int done;
        private int changedMeanwhile;
        private BukkitTask task;

        Job(final CommandSender sender, final UUID senderId, final Country country, final Op op, final String world,
            final List<Long> chunks, final Map<Skip, Integer> plannedSkips, final Map<UUID, Integer> foreign) {
            this.sender = sender;
            this.senderId = senderId;
            this.country = country;
            this.op = op;
            this.world = world;
            this.chunks = chunks;
            this.plannedSkips = plannedSkips;
            this.foreign = foreign;
        }

        void step() {
            final var manager = AdminTerritory.this.plugin.manager();
            if (manager.get(this.country.id()) == null) {
                this.finish("admin-area-aborted");
                return;
            }
            final int end = Math.min(this.chunks.size(), this.next + AreaPlanner.CHUNKS_PER_TICK);
            final List<Long> batch = this.chunks.subList(this.next, end);
            this.next = end;
            if (this.op == Op.UNCLAIM) {
                final ChunkPos capital = this.country.capital();
                final List<ChunkPos> release = new ArrayList<>();
                for (final long key : batch) {
                    final ChunkPos pos = new ChunkPos(this.world, ChunkKeys.x(key), ChunkKeys.z(key));
                    if (!this.country.id().equals(manager.ownerAt(this.world, key)) || pos.equals(capital)) {
                        this.changedMeanwhile++;
                    } else {
                        release.add(pos);
                    }
                }
                manager.unclaimAll(this.country, release, ClaimCause.ADMIN);
                this.done += release.size();
            } else {
                final List<Long> free = new ArrayList<>();
                for (final long key : batch) {
                    if (manager.ownerAt(this.world, key) != null || AdminTerritory.this.plugin.protectedAreas()
                        .isProtected(this.world, ChunkKeys.x(key), ChunkKeys.z(key))) {
                        this.changedMeanwhile++;
                    } else {
                        free.add(key);
                    }
                }
                final int claimed = manager.claimAll(this.country, this.world, free, ClaimCause.ADMIN).size();
                this.changedMeanwhile += free.size() - claimed;
                this.done += claimed;
            }
            if (this.next >= this.chunks.size()) {
                this.finish(switch (this.op) {
                    case CLAIM -> "admin-area-done-claim";
                    case UNCLAIM -> "admin-area-done-unclaim";
                    case RINGS -> "admin-area-done-rings";
                });
            }
        }

        private void finish(final String key) {
            if (this.task != null) {
                this.task.cancel();
            }
            AdminTerritory.this.running.remove(this.senderId);
            final CommandSender target = this.sender instanceof Player p ? Bukkit.getPlayer(p.getUniqueId()) : this.sender;
            if (target == null) {
                return;
            }
            final Messages m = AdminTerritory.this.plugin.messages();
            m.send(target, key, KrajCommand.country(this.country),
                KrajCommand.unparsed("count", Format.number(this.done)),
                KrajCommand.unparsed("total", Format.number(this.country.claimCount())));
            if (!this.plannedSkips.isEmpty()) {
                final int skipped = this.plannedSkips.values().stream().mapToInt(Integer::intValue).sum();
                m.send(target, "admin-area-preview-skipped", KrajCommand.unparsed("skipped", Format.number(skipped)),
                    Placeholder.component("reasons", AdminTerritory.this.reasons(this.plannedSkips, this.foreign)));
            }
            if (this.changedMeanwhile > 0) {
                m.send(target, "admin-area-changed-meanwhile", KrajCommand.unparsed("count", this.changedMeanwhile));
            }
        }
    }

    private void cancel(final BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    /** Plugin disable / reload: stop previews (running jobs are cancelled with the plugin's tasks). */
    void shutdown() {
        this.previews.values().forEach(BukkitTask::cancel);
        this.previews.clear();
        this.running.values().forEach(BukkitTask::cancel);
        this.running.clear();
        this.pending.clear();
    }
}
