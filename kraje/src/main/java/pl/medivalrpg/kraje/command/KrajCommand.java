// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.TreasuryService;
import pl.medivalrpg.kraje.manager.CountryManager;
import pl.medivalrpg.kraje.manager.KrajeActions;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.manager.RequestManager;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.siege.SiegeManager;
import pl.medivalrpg.kraje.util.Colors;
import pl.medivalrpg.kraje.util.Format;

/**
 * {@code /kraj} (aliases {@code /k}, {@code /panstwo}): game logic of all subcommands. The Brigadier tree
 * ({@link KrajCommandTree}) only parses arguments and calls {@link #run(CommandSender, String...)}. Rules shared
 * with the API (costs, limits, diplomacy) live in {@link KrajeActions}.
 */
public final class KrajCommand {

    static final String CONFIRM = "potwierdz";

    private final KrajePlugin plugin;
    private final InfoViews views;
    private final AdminCommands admin;
    private final DiplomacyCommands diplomacy;
    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();

    public KrajCommand(final KrajePlugin plugin) {
        this.plugin = plugin;
        this.views = new InfoViews(plugin);
        this.admin = new AdminCommands(plugin, this);
        this.diplomacy = new DiplomacyCommands(this);
    }

    // ------------------------------------------------------------------ helpers

    KrajePlugin plugin() {
        return this.plugin;
    }

    Messages messages() {
        return this.plugin.messages();
    }

    CountryManager manager() {
        return this.plugin.manager();
    }

    RequestManager requests() {
        return this.plugin.requests();
    }

    KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    KrajeActions actions() {
        return this.plugin.actions();
    }

    TreasuryService treasury() {
        return this.plugin.treasury();
    }

    static TagResolver country(final Country c) {
        return Notify.country(c);
    }

    static TagResolver unparsed(final String key, final Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    static Player requirePlayer(final CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new KrajeException("players-only");
    }

    /**
     * Country the player's commands act on: his own country, or — for the shared ruler of a personal union who switched
     * with {@code /kraj unia rzadz} — the union partner.
     */
    Country requireCountry(final Player player) {
        final Country c = this.manager().countryOf(player.getUniqueId());
        if (c == null) {
            throw new KrajeException("not-in-country");
        }
        return this.plugin.unions() == null ? c : this.plugin.unions().actingCountry(player.getUniqueId(), c);
    }

    static Rank rankOf(final Country c, final UUID player) {
        final Member m = c.member(player);
        return m == null ? Rank.CITIZEN : m.rank();
    }

    static void requireRank(final Country c, final Player player, final Rank needed) {
        if (!hasRank(c, player.getUniqueId(), needed)) {
            throw new KrajeException("rank-required", Placeholder.unparsed("rank", needed.displayName()));
        }
    }

    static boolean hasRank(final Country c, final UUID player, final Rank needed) {
        final Member member = c.member(player);
        return member != null && member.rank().atLeast(needed);
    }

    Country requireCountryArg(final String name) {
        final Country c = this.manager().byName(name);
        if (c == null) {
            throw new KrajeException("country-not-found", Placeholder.unparsed("name", name));
        }
        return c;
    }

    static Member requireMember(final Country c, final String name) {
        for (final Member m : c.members()) {
            if (m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        throw new KrajeException("member-not-found", Placeholder.unparsed("player", name));
    }

    static void requireArgs(final String[] args, final int count, final String usageKey) {
        if (args.length < count) {
            throw new KrajeException(usageKey);
        }
    }

    /** Sends a message to all online members of a country. */
    void notifyMembers(final Country c, final String key, final TagResolver... resolvers) {
        Notify.members(this.messages(), c, key, resolvers);
    }

    /** Sends a message to the online ruler and advisors. */
    void notifyLeaders(final Country c, final String key, final TagResolver... resolvers) {
        Notify.leaders(this.messages(), c, key, resolvers);
    }

    void broadcast(final String key, final TagResolver... resolvers) {
        Notify.broadcast(this.messages(), key, resolvers);
    }

    String money(final long amount) {
        return this.treasury().format(amount);
    }

    // ------------------------------------------------------------------ dispatch

    /** Brigadier node of {@code /kraj} (argument names, suggestions with tooltips). */
    public LiteralCommandNode<CommandSourceStack> tree() {
        return new KrajCommandTree(this).build();
    }

    /** Runs {@code /kraj <args...>}; error messages ({@link KrajeException}) are sent to the sender. */
    void run(final CommandSender sender, final String... args) {
        try {
            this.dispatch(sender, args);
        } catch (final KrajeException e) {
            this.messages().send(sender, e.messageKey(), e.resolvers());
        }
    }

    private void dispatch(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            this.help(sender, null);
            return;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        final String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "pomoc", "help" -> this.help(sender, rest.length > 0 ? String.join(" ", rest) : null);
            case "zaloz" -> this.found(requirePlayer(sender), rest);
            case "zajmij" -> this.claim(requirePlayer(sender));
            case "porzuc" -> this.unclaim(requirePlayer(sender));
            case "zapros" -> this.invite(requirePlayer(sender), rest);
            case "dolacz" -> this.join(requirePlayer(sender), rest);
            case "odrzuc" -> this.refuseInvite(requirePlayer(sender), rest);
            case "opusc" -> this.leave(requirePlayer(sender));
            case "wyrzuc" -> this.kick(requirePlayer(sender), rest);
            case "awansuj" -> this.promote(requirePlayer(sender), rest);
            case "degraduj" -> this.demote(requirePlayer(sender), rest);
            case "przekaz" -> this.transfer(requirePlayer(sender), rest);
            case "rozwiaz", "usun" -> this.disband(requirePlayer(sender), rest);
            case "kolor" -> this.color(requirePlayer(sender), rest);
            case "opis" -> this.description(requirePlayer(sender), rest);
            case "stolica" -> this.capital(requirePlayer(sender));
            case "spawn" -> this.spawn(requirePlayer(sender));
            case "info" -> this.info(sender, rest);
            case "lista" -> this.views.list(sender, rest.length > 0 ? parseInt(rest[0], 1) : 1);
            case "mapa" -> this.views.map(requirePlayer(sender));
            case "sojusz" -> this.alliance(requirePlayer(sender), rest);
            case "wojna" -> this.war(requirePlayer(sender), rest);
            case "pokoj" -> this.peace(requirePlayer(sender), rest);
            case "zerwij" -> this.breakAlliance(requirePlayer(sender), rest);
            case "kapitulacja" -> this.capitulation(requirePlayer(sender), rest);
            case "wasale" -> this.vassals(sender, rest);
            case "wykup" -> this.buyout(requirePlayer(sender), rest);
            case "zwolnij" -> this.release(requirePlayer(sender), rest);
            case "unia" -> this.diplomacy.union(sender, rest);
            case "ambasada" -> this.diplomacy.embassy(sender, rest);
            case "oblezenie" -> this.siege(requirePlayer(sender));
            case "desant" -> this.landing(requirePlayer(sender));
            case "oblezenia" -> this.views.sieges(sender);
            case "skarbiec" -> this.treasuryInfo(sender, rest);
            case "wplac" -> this.deposit(requirePlayer(sender), rest);
            case "wyplac" -> this.withdraw(requirePlayer(sender), rest);
            case "podatek" -> this.tax(requirePlayer(sender), rest);
            case "czat" -> this.toggleChat(requirePlayer(sender));
            case "admin" -> {
                if (!sender.hasPermission("kraje.admin")) {
                    throw new KrajeException("no-permission");
                }
                this.admin.dispatch(sender, rest);
            }
            default -> this.messages().send(sender, "unknown-subcommand", unparsed("sub", args[0]));
        }
    }

    private void help(final CommandSender sender, final String page) {
        for (final Component line : HelpBook.render(this.messages(), sender.hasPermission(KrajCommandTree.PERM_ADMIN), page)) {
            sender.sendMessage(line);
        }
    }

    static int parseInt(final String s, final int def) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ founding & territory

    private void found(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-zaloz");
        final String name = args[0];
        final Country c = this.actions().found(player, name);
        this.messages().send(player, "founded", country(c));
        if (this.treasury().active() && this.cfg().foundCost > 0) {
            this.messages().send(player, "founded-paid", unparsed("cost", this.money(this.cfg().foundCost)));
        }
        this.broadcast("founded-broadcast", country(c), unparsed("player", player.getName()));
    }

    private void claim(final Player player) {
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.ADVISOR);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        final long cost = this.actions().claim(c, pos, ClaimCause.PLAYER, player.getName());
        final int limit = this.actions().limitOf(c).total();
        this.messages().send(player, "claimed", country(c),
            unparsed("count", c.claimCount()), unparsed("limit", limit), unparsed("area", Format.area(c.claimCount())));
        if (cost > 0) {
            this.messages().send(player, "claim-paid", unparsed("cost", this.money(cost)),
                unparsed("balance", this.money(this.treasury().balance(c))),
                unparsed("next", this.money(this.treasury().claimCost(c))));
        }
    }

    private void unclaim(final Player player) {
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.ADVISOR);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        this.actions().unclaim(c, pos, ClaimCause.PLAYER);
        this.messages().send(player, "unclaimed", country(c), unparsed("count", c.claimCount()),
            unparsed("limit", this.actions().limitOf(c).total()));
    }

    /** Kept for the admin commands: connectivity rule of {@code /kraj porzuc}. */
    boolean staysConnected(final Country c, final ChunkPos removed) {
        return this.actions().staysConnected(c, removed);
    }

    private void capital(final Player player) {
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        if (this.manager().countryAt(pos) != c) {
            throw new KrajeException("chunk-not-own");
        }
        if (this.plugin.sieges().siegeAt(pos) != null) {
            throw new KrajeException("capital-sieged");
        }
        this.manager().setCapital(c, pos, SpawnPoint.of(player.getLocation()));
        this.messages().send(player, "capital-moved", country(c),
            unparsed("x", player.getLocation().getBlockX()), unparsed("z", player.getLocation().getBlockZ()));
    }

    private void spawn(final Player player) {
        if (!this.cfg().spawnEnabled) {
            throw new KrajeException("spawn-disabled");
        }
        final Country c = this.requireCountry(player);
        final UUID id = c.id();
        this.teleportWithWarmup(player, () -> {
            final Country current = this.manager().get(id);
            final boolean allowed = current != null && (current.isMember(player.getUniqueId()));
            return allowed ? current.spawn().toLocation() : null;
        }, "spawn-teleported", country(c));
    }

    /**
     * Teleport after {@code spawn.warmup-seconds} (moving cancels; admins and warmup 0 go at once). {@code target} is
     * evaluated now and again when the warmup ends; null = "spawn-unavailable".
     */
    void teleportWithWarmup(final Player player, final java.util.function.Supplier<Location> target, final String doneKey,
                            final TagResolver resolver) {
        if (target.get() == null) {
            throw new KrajeException("spawn-unavailable");
        }
        final int warmup = this.cfg().spawnWarmupSeconds;
        final BukkitTask old = this.pendingTeleports.remove(player.getUniqueId());
        if (old != null) {
            old.cancel();
        }
        if (warmup <= 0 || player.hasPermission("kraje.admin")) {
            player.teleportAsync(target.get());
            this.messages().send(player, doneKey, resolver);
            return;
        }
        final Location start = player.getLocation();
        this.messages().send(player, "spawn-warmup", unparsed("seconds", warmup));
        final BukkitTask task = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.pendingTeleports.remove(player.getUniqueId());
            if (!player.isOnline()) {
                return;
            }
            final Location now = player.getLocation();
            if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 1.0) {
                this.messages().send(player, "spawn-cancelled");
                return;
            }
            final Location dest = target.get();
            if (dest == null) {
                this.messages().send(player, "spawn-unavailable");
                return;
            }
            player.teleportAsync(dest);
            this.messages().send(player, doneKey, resolver);
        }, warmup * 20L);
        this.pendingTeleports.put(player.getUniqueId(), task);
    }

    public void cancelTeleports() {
        this.pendingTeleports.values().forEach(BukkitTask::cancel);
        this.pendingTeleports.clear();
        this.admin.shutdown();
    }

    // ------------------------------------------------------------------ members

    private void invite(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-zapros");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.ADVISOR);
        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            throw new KrajeException("player-not-found", unparsed("player", args[0]));
        }
        if (target.equals(player)) {
            throw new KrajeException("cannot-target-self");
        }
        if (this.manager().countryOf(target.getUniqueId()) != null) {
            throw new KrajeException("target-in-country", unparsed("player", target.getName()));
        }
        this.requests().invite(target.getUniqueId(), c.id(), this.cfg().inviteExpiryMillis);
        final long minutes = Math.max(1, this.cfg().inviteExpiryMillis / 60_000L);
        this.messages().send(player, "invite-sent", country(c), unparsed("player", target.getName()));
        this.messages().send(target, "invite-received", country(c), unparsed("player", player.getName()),
            unparsed("minutes", minutes), Placeholder.styling("join", ClickEvent.runCommand("/kraj dolacz " + c.name())));
    }

    private void join(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-dolacz");
        if (this.manager().countryOf(player.getUniqueId()) != null) {
            throw new KrajeException("already-in-country");
        }
        final Country c = this.requireCountryArg(args[0]);
        // Persistent invitation (citizens of a fallen country invited by the conqueror), valid also after a restart.
        final boolean stored = this.manager().hasStoredInvite(player.getUniqueId(), c.id());
        if (c.npc()) {
            // NPC countries have nobody who could invite: joining is either open (config) or impossible.
            if (!this.cfg().npcPlayersCanJoin && !stored) {
                throw new KrajeException("npc-no-join", country(c));
            }
        } else if (!stored && !this.requests().hasInvite(player.getUniqueId(), c.id())) {
            throw new KrajeException("no-invite", country(c));
        }
        this.notifyMembers(c, "member-joined", country(c), unparsed("player", player.getName()));
        this.manager().addMember(c, player.getUniqueId(), player.getName(), Rank.CITIZEN); // also drops stored invitations
        this.requests().clearInvites(player.getUniqueId());
        this.messages().send(player, "joined", country(c));
    }

    /** {@code /kraj odrzuc <kraj>}: refuses an invitation (short-lived or persistent). */
    private void refuseInvite(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-odrzuc");
        final Country c = this.requireCountryArg(args[0]);
        final boolean stored = this.manager().hasStoredInvite(player.getUniqueId(), c.id());
        final boolean normal = this.requests().removeInvite(player.getUniqueId(), c.id());
        if (!stored && !normal) {
            throw new KrajeException("no-invite", country(c));
        }
        this.manager().removeStoredInvite(player.getUniqueId(), c.id());
        this.messages().send(player, "invite-refused", country(c));
        this.notifyLeaders(c, "invite-refused-notify", country(c), unparsed("player", player.getName()));
    }

    private void leave(final Player player) {
        final Country c = this.requireCountry(player);
        if (player.getUniqueId().equals(c.ruler())) {
            throw new KrajeException("ruler-cannot-leave");
        }
        this.plugin.unions().requireCanLoseMember(c, player.getUniqueId());
        this.manager().removeMember(c, player.getUniqueId());
        this.plugin.chat().disableCountryChat(player.getUniqueId());
        this.messages().send(player, "left", country(c));
        this.notifyMembers(c, "member-left", country(c), unparsed("player", player.getName()));
    }

    private void kick(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-wyrzuc");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.ADVISOR);
        final Member target = requireMember(c, args[0]);
        if (target.uuid().equals(player.getUniqueId())) {
            throw new KrajeException("cannot-target-self");
        }
        if (target.rank().weight() >= rankOf(c, player.getUniqueId()).weight()) {
            throw new KrajeException("cannot-kick-higher", unparsed("player", target.name()));
        }
        this.plugin.unions().requireCanLoseMember(c, target.uuid());
        final boolean forTaxes = this.treasury().active() && this.treasury().canKickForTaxes(target);
        final long debt = target.taxDebt();
        this.manager().removeMember(c, target.uuid());
        this.plugin.chat().disableCountryChat(target.uuid());
        final Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            this.messages().send(online, forTaxes ? "kicked-target-taxes" : "kicked-target", country(c),
                unparsed("player", player.getName()), unparsed("debt", this.money(debt)));
        }
        this.notifyMembers(c, forTaxes ? "member-kicked-taxes" : "member-kicked", country(c), unparsed("player", target.name()),
            unparsed("by", player.getName()), unparsed("debt", this.money(debt)), unparsed("days", target.debtDays()));
    }

    private void promote(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-awansuj");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final Member target = requireMember(c, args[0]);
        if (target.rank() != Rank.CITIZEN) {
            throw new KrajeException("already-max-rank", unparsed("player", target.name()));
        }
        this.manager().setRank(c, target.uuid(), Rank.ADVISOR);
        this.notifyMembers(c, "member-promoted", country(c), unparsed("player", target.name()),
            unparsed("rank", Rank.ADVISOR.displayName()));
    }

    private void demote(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-degraduj");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final Member target = requireMember(c, args[0]);
        if (target.rank() != Rank.ADVISOR) {
            throw new KrajeException("already-min-rank", unparsed("player", target.name()));
        }
        this.manager().setRank(c, target.uuid(), Rank.CITIZEN);
        this.notifyMembers(c, "member-demoted", country(c), unparsed("player", target.name()),
            unparsed("rank", Rank.CITIZEN.displayName()));
    }

    private void transfer(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-przekaz");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final Member target = requireMember(c, args[0]);
        if (target.uuid().equals(player.getUniqueId())) {
            throw new KrajeException("cannot-target-self");
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase(CONFIRM)) {
            this.requests().requestConfirmation(player.getUniqueId(), "przekaz", target.uuid(), this.cfg().confirmExpiryMillis);
            this.messages().send(player, "transfer-confirm", country(c), unparsed("player", target.name()),
                unparsed("seconds", this.cfg().confirmExpiryMillis / 1000),
                Placeholder.styling("confirm", ClickEvent.runCommand("/kraj przekaz " + target.name() + " " + CONFIRM)));
            return;
        }
        if (!this.requests().confirm(player.getUniqueId(), "przekaz", target.uuid())) {
            throw new KrajeException("no-pending-confirmation");
        }
        this.manager().transferRule(c, target.uuid());
        this.notifyMembers(c, "ruler-changed", country(c), unparsed("player", target.name()));
    }

    private void disband(final Player player, final String[] args) {
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        if (args.length < 1 || !args[0].equalsIgnoreCase(CONFIRM)) {
            this.requests().requestConfirmation(player.getUniqueId(), "rozwiaz", c.id(), this.cfg().confirmExpiryMillis);
            this.messages().send(player, "disband-confirm", country(c),
                unparsed("seconds", this.cfg().confirmExpiryMillis / 1000),
                Placeholder.styling("confirm", ClickEvent.runCommand("/kraj rozwiaz " + CONFIRM)));
            return;
        }
        if (!this.requests().confirm(player.getUniqueId(), "rozwiaz", c.id())) {
            throw new KrajeException("no-pending-confirmation");
        }
        this.disbandCountry(c, ClaimCause.PLAYER);
        this.broadcast("disbanded-broadcast", country(c), unparsed("player", player.getName()));
    }

    void disbandCountry(final Country c, final ClaimCause cause) {
        this.actions().disband(c, cause);
    }

    // ------------------------------------------------------------------ appearance

    private void color(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-kolor");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final Integer color = Colors.parse(args[0]);
        if (color == null) {
            throw new KrajeException("invalid-color", unparsed("input", args[0]));
        }
        this.manager().setColor(c, color);
        this.messages().send(player, "color-changed", country(c), unparsed("hex", c.hexColor()));
    }

    private void description(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-opis");
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.ADVISOR);
        String text = String.join(" ", args).replaceAll("[\\p{Cntrl}§]", "").trim();
        if (text.equals("-")) {
            text = "";
        }
        if (text.length() > this.cfg().descriptionMaxLength) {
            throw new KrajeException("description-too-long", unparsed("max", this.cfg().descriptionMaxLength));
        }
        this.manager().setDescription(c, text);
        this.messages().send(player, "description-changed", country(c));
    }

    // ------------------------------------------------------------------ info

    private void info(final CommandSender sender, final String[] args) {
        if (args.length > 0) {
            this.views.info(sender, this.requireCountryArg(args[0]));
            return;
        }
        final Player player = requirePlayer(sender);
        Country c = this.manager().countryOf(player.getUniqueId());
        if (c == null) {
            c = this.manager().countryAt(player.getLocation());
        }
        if (c == null) {
            throw new KrajeException("usage-info");
        }
        this.views.info(sender, c);
    }

    // ------------------------------------------------------------------ diplomacy & war

    private Country[] diplomacyPair(final Player player, final String[] args, final String usage) {
        requireArgs(args, 1, usage);
        final Country own = this.requireCountry(player);
        requireRank(own, player, Rank.RULER);
        final Country other = this.requireCountryArg(args[0]);
        if (other == own) {
            throw new KrajeException("cannot-target-own-country");
        }
        return new Country[] {own, other};
    }

    private void alliance(final Player player, final String[] args) {
        final Country[] pair = this.diplomacyPair(player, args, "usage-sojusz");
        if (this.actions().proposeAlliance(pair[0], pair[1]) == KrajeActions.Outcome.PROPOSED) {
            this.messages().send(player, pair[1].npc() ? "alliance-proposed-npc" : "alliance-proposed", country(pair[1]));
        }
    }

    private void war(final Player player, final String[] args) {
        final Country[] pair = this.diplomacyPair(player, args, "usage-wojna");
        final long cost = this.actions().declareWar(pair[0], pair[1], player.getName());
        if (this.treasury().active() && cost > 0) {
            this.messages().send(player, "war-paid", unparsed("cost", this.money(cost)),
                unparsed("time", Format.duration(this.cfg().warMinDurationMillis)));
        }
    }

    private void peace(final Player player, final String[] args) {
        final Country[] pair = this.diplomacyPair(player, args, "usage-pokoj");
        if (this.actions().proposePeace(pair[0], pair[1]) == KrajeActions.Outcome.PROPOSED) {
            this.messages().send(player, pair[1].npc() ? "peace-proposed-npc" : "peace-proposed", country(pair[1]));
        }
    }

    private void breakAlliance(final Player player, final String[] args) {
        final Country[] pair = this.diplomacyPair(player, args, "usage-zerwij");
        this.actions().breakAlliance(pair[0], pair[1]);
    }

    // ------------------------------------------------------------------ capitulation & vassals

    /** {@code /kraj kapitulacja <wróg> [dni]} and {@code /kraj kapitulacja przyjmij|odrzuc <kraj>} (Ruler). */
    private void capitulation(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-kapitulacja");
        final String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("przyjmij") || first.equals("odrzuc")) {
            requireArgs(args, 2, "usage-kapitulacja");
            final Country own = this.requireCountry(player);
            requireRank(own, player, Rank.RULER);
            final Country loser = this.requireCountryArg(args[1]);
            if (loser == own) {
                throw new KrajeException("cannot-target-own-country");
            }
            final boolean accept = first.equals("przyjmij");
            this.plugin.vassals().respond(own, loser, accept);
            if (!accept) {
                this.messages().send(player, "capitulation-refused-sent", country(loser));
            }
            return;
        }
        final Country own = this.requireCountry(player);
        requireRank(own, player, Rank.RULER);
        final Country enemy = this.requireCountryArg(args[0]);
        if (enemy == own) {
            throw new KrajeException("cannot-target-own-country");
        }
        int days = 0;
        if (args.length > 1) {
            days = parseInt(args[1], -1);
            if (days <= 0) {
                throw new KrajeException("capitulation-days-range", unparsed("min", this.cfg().vassalMinDays),
                    unparsed("max", this.cfg().vassalMaxDays));
            }
        }
        final var offer = this.plugin.vassals().offer(own, enemy, days);
        this.messages().send(player, enemy.npc() ? "capitulation-sent-npc" : "capitulation-sent", country(enemy),
            this.plugin.vassals().terms(offer, this.plugin.vassals().offerEstimate(own)));
    }

    /** {@code /kraj wasale [kraj]}: overlord and vassals of a country. */
    private void vassals(final CommandSender sender, final String[] args) {
        final Country c;
        if (args.length > 0) {
            c = this.requireCountryArg(args[0]);
        } else {
            c = this.requireCountry(requirePlayer(sender));
        }
        this.views.vassals(sender, c);
    }

    /** {@code /kraj wykup [potwierdz]} (Ruler of a vassal). */
    private void buyout(final Player player, final String[] args) {
        final Country own = this.requireCountry(player);
        requireRank(own, player, Rank.RULER);
        if (own.vassalage() == null) {
            throw new KrajeException("buyout-not-vassal");
        }
        this.treasury().requireActive();
        final Country overlord = this.manager().get(own.overlord());
        if (args.length < 1 || !args[0].equalsIgnoreCase(CONFIRM)) {
            final var v = own.vassalage();
            final long daily = this.plugin.vassals().estimateTribute(own);
            final long days = pl.medivalrpg.kraje.economy.TributeMath.remainingDays(System.currentTimeMillis(), v.until());
            this.requests().requestConfirmation(player.getUniqueId(), "wykup", own.id(), this.cfg().confirmExpiryMillis);
            this.messages().send(player, "buyout-confirm", overlord == null ? country(own) : country(overlord),
                unparsed("cost", this.money(this.plugin.vassals().buyoutCost(own))),
                unparsed("daily", this.money(daily)), unparsed("days", days),
                unparsed("multiplier", String.valueOf(this.cfg().buyoutMultiplier)),
                unparsed("debt", this.money(v.debt())),
                unparsed("balance", this.money(this.treasury().balance(own))),
                unparsed("seconds", this.cfg().confirmExpiryMillis / 1000),
                Placeholder.styling("confirm", ClickEvent.runCommand("/kraj wykup " + CONFIRM)));
            return;
        }
        if (!this.requests().confirm(player.getUniqueId(), "wykup", own.id())) {
            throw new KrajeException("no-pending-confirmation");
        }
        this.plugin.vassals().buyout(own);
    }

    /** {@code /kraj zwolnij <wasal>} (Ruler of the overlord). */
    private void release(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-zwolnij");
        final Country own = this.requireCountry(player);
        requireRank(own, player, Rank.RULER);
        final Country vassal = this.requireCountryArg(args[0]);
        this.plugin.vassals().release(own, vassal);
    }

    private void landing(final Player player) {
        this.siege(player,true);
    }

    private void siege(final Player player) {
        this.siege(player,false);
    }

    private void siege(final Player player,final boolean landing) {
        final Country own = this.requireCountry(player);
        requireRank(own, player, this.cfg().siegeMinRank);
        final ChunkPos pos = ChunkPos.of(player.getLocation());
        final SiegeManager.Siege running = this.plugin.sieges().siegeAt(pos);
        if (running != null) {
            throw new KrajeException("siege-chunk-busy");
        }
        if (landing) this.plugin.sieges().startLanding(own,pos,player.getName());
        else this.plugin.sieges().start(own, pos, player.getName());
        final long cost=this.cfg().siegeCost+(landing ? this.cfg().landingExtraCost : 0);
        if (this.treasury().active() && cost > 0) {
            this.messages().send(player, "siege-paid", unparsed("cost", this.money(cost)));
        }
    }

    // ------------------------------------------------------------------ treasury

    private void treasuryInfo(final CommandSender sender, final String[] args) {
        this.treasury().requireActive();
        final Country c;
        if (args.length > 0 && sender.hasPermission(KrajCommandTree.PERM_ADMIN)) {
            c = this.requireCountryArg(args[0]);
        } else {
            c = this.requireCountry(requirePlayer(sender));
        }
        this.views.treasury(sender, c);
    }

    /** {@code /kraj wplac <kwota> [konto|monety]}. */
    private void deposit(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-wplac");
        this.treasury().requireActive();
        final Country c = this.requireCountry(player);
        TreasuryService.Source source = TreasuryService.Source.AUTO;
        List<String> words = List.of(args);
        final String last = words.get(words.size() - 1).toLowerCase(Locale.ROOT);
        if (words.size() > 1 && (last.equals("konto") || last.equals("monety"))) {
            source = last.equals("konto") ? TreasuryService.Source.ACCOUNT : TreasuryService.Source.COINS;
            words = words.subList(0, words.size() - 1);
        }
        final long amount = this.treasury().parseAmount(String.join(" ", words));
        final TreasuryService.Source used = this.treasury().deposit(player, c, amount, source);
        this.messages().send(player, used == TreasuryService.Source.COINS ? "deposited-coins" : "deposited", country(c),
            unparsed("amount", this.money(amount)), unparsed("balance", this.money(this.treasury().balance(c))));
        for (final Member m : c.members()) {
            if (m.rank() == Rank.RULER && !m.uuid().equals(player.getUniqueId())) {
                final Player ruler = Bukkit.getPlayer(m.uuid());
                if (ruler != null) {
                    this.messages().send(ruler, "deposited-notify", country(c), unparsed("player", player.getName()),
                        unparsed("amount", this.money(amount)));
                }
            }
        }
    }

    /** {@code /kraj wyplac <kwota>} (Ruler, to the ruler's bank account). */
    private void withdraw(final Player player, final String[] args) {
        requireArgs(args, 1, "usage-wyplac");
        this.treasury().requireActive();
        final Country c = this.requireCountry(player);
        requireRank(c, player, Rank.RULER);
        final long amount = this.treasury().parseAmount(String.join(" ", args));
        this.treasury().withdraw(player, c, amount);
        this.messages().send(player, "withdrawn", country(c),
            unparsed("amount", this.money(amount)), unparsed("balance", this.money(this.treasury().balance(c))));
    }

    /** {@code /kraj podatek} shows the tax and debtors; {@code /kraj podatek <kwota>} sets it (Ruler). */
    private void tax(final Player player, final String[] args) {
        this.treasury().requireActive();
        final Country c = this.requireCountry(player);
        if (args.length == 0) {
            this.views.taxes(player, c);
            return;
        }
        requireRank(c, player, Rank.RULER);
        final String input = String.join(" ", args).trim();
        final long amount = input.equals("0") || input.equals("-") || input.equalsIgnoreCase("brak")
            ? 0 : this.treasury().parseAmount(input);
        if (amount > this.cfg().taxMax) {
            throw new KrajeException("tax-too-high", unparsed("max", this.money(this.cfg().taxMax)));
        }
        this.manager().setTax(c, amount);
        if (amount == 0) {
            this.notifyMembers(c, "tax-cleared", country(c), unparsed("player", player.getName()));
        } else {
            this.notifyMembers(c, "tax-set", country(c), unparsed("player", player.getName()),
                unparsed("amount", this.money(amount)),
                unparsed("time", this.cfg().dailyTickTime.toString()));
        }
    }

    private void toggleChat(final Player player) {
        if (!this.cfg().chatEnabled) {
            throw new KrajeException("chat-disabled");
        }
        this.requireCountry(player);
        final boolean on = this.plugin.chat().toggleCountryChat(player.getUniqueId());
        this.messages().send(player, on ? "country-chat-on" : "country-chat-off");
    }
}
