// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.skarbiec.SkarbiecPlugin;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;
import pl.medivalrpg.skarbiec.api.TxResult;
import pl.medivalrpg.skarbiec.config.Messages;
import pl.medivalrpg.skarbiec.config.SkarbiecConfig;
import pl.medivalrpg.skarbiec.ledger.Ledger;
import pl.medivalrpg.skarbiec.ledger.TxRecord;
import pl.medivalrpg.skarbiec.money.Money;
import pl.medivalrpg.skarbiec.npc.NpcManager;
import pl.medivalrpg.skarbiec.npc.NpcRecord;
import pl.medivalrpg.skarbiec.npc.NpcSkins;
import pl.medivalrpg.skarbiec.npc.NpcType;
import pl.medivalrpg.skarbiec.shop.Shop;

/**
 * Brigadier commands: {@code /saldo}, {@code /zaplac}, {@code /monety}, {@code /handel}, {@code /skarbiec}.
 * Polish argument names and suggestion tooltips. Suggestions read Bukkit state on the main thread.
 */
public final class SkarbiecCommands {

    static final String PERM_USE = "skarbiec.use";
    static final String PERM_ADMIN = "skarbiec.admin";
    private static final int LOG_PAGE = 10;
    private static final String[] AMOUNTS = {"5", "1sr", "5sr", "1zl", "5zl", "10zl", "50zl"};

    private final SkarbiecPlugin plugin;

    public SkarbiecCommands(final SkarbiecPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return this.plugin.messages();
    }

    public void register(final Commands commands) {
        WorldTreasury.register(this.plugin,commands);
        commands.register(this.saldo(), "Saldo konta bankowego", List.of("konto"));
        commands.register(this.zaplac(), "Przelew bankowy do gracza", List.of("przelew"));
        commands.register(this.monety(), "Ile masz monet w sakiewce", List.of("sakiewka"));
        commands.register(this.handel(), "Bezpieczny handel z graczem obok", List.of("wymiana"));
        commands.register(this.skarbiec(), "Ekonomia MEDIVALRPG", List.of("ekonomia"));
    }

    // ================================================================== trees

    private static Predicate<CommandSourceStack> perm(final String p) {
        return s -> s.getSender().hasPermission(p);
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> saldo() {
        return Commands.literal("saldo")
            .requires(perm(PERM_USE))
            .executes(ctx -> this.run(ctx, () -> this.balance(ctx.getSource().getSender(), null)))
            .then(Commands.argument("gracz", StringArgumentType.word())
                .requires(perm(PERM_ADMIN))
                .suggests(this.provider(this::suggestPlayers))
                .executes(ctx -> this.run(ctx, () -> this.balance(ctx.getSource().getSender(), arg(ctx, "gracz")))))
            .build();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> zaplac() {
        return Commands.literal("zaplac")
            .requires(perm(PERM_USE))
            .executes(ctx -> this.usage(ctx, "usage-zaplac"))
            .then(Commands.argument("gracz", StringArgumentType.word())
                .suggests(this.provider(this::suggestOtherPlayers))
                .executes(ctx -> this.usage(ctx, "usage-zaplac"))
                .then(Commands.argument("kwota", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestAmounts))
                    .executes(ctx -> this.run(ctx, () -> this.pay(ctx.getSource().getSender(), arg(ctx, "gracz"), arg(ctx, "kwota"))))))
            .build();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> monety() {
        return Commands.literal("monety")
            .requires(perm(PERM_USE))
            .executes(ctx -> this.run(ctx, () -> this.coins(ctx.getSource().getSender())))
            .build();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> handel() {
        return Commands.literal("handel")
            .requires(perm(PERM_USE))
            .executes(ctx -> this.usage(ctx, "usage-handel"))
            .then(Commands.argument("gracz", StringArgumentType.word())
                .suggests(this.provider(this::suggestNearbyPlayers))
                .executes(ctx -> this.run(ctx, () -> this.trade(ctx.getSource().getSender(), arg(ctx, "gracz")))))
            .build();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> skarbiec() {
        final Predicate<CommandSourceStack> admin = perm(PERM_ADMIN);
        return Commands.literal("skarbiec")
            .requires(perm(PERM_USE))
            .executes(ctx -> this.run(ctx, () -> this.help(ctx.getSource().getSender(), null)))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.run(ctx, () -> this.help(ctx.getSource().getSender(), null)))
                .then(Commands.argument("strona", StringArgumentType.word())
                    .suggests(this.provider(this::suggestHelpPages))
                    .executes(ctx -> this.run(ctx, () -> this.help(ctx.getSource().getSender(), arg(ctx, "strona"))))))
            .then(Commands.literal("sklepy")
                .executes(ctx -> this.run(ctx, () -> this.listShops(ctx.getSource().getSender()))))
            .then(Commands.literal("kupcy")
                .executes(ctx -> this.run(ctx, () -> this.merchants(ctx.getSource().getSender()))))
            .then(Commands.literal("admin")
                .requires(admin)
                .executes(ctx -> this.usage(ctx, "usage-admin"))
                .then(this.adminOp("daj"))
                .then(this.adminOp("zabierz"))
                .then(this.adminOp("ustaw"))
                .then(Commands.literal("monety")
                    .executes(ctx -> this.usage(ctx, "usage-admin-monety"))
                    .then(Commands.argument("gracz", StringArgumentType.word())
                        .suggests(this.provider(this::suggestPlayers))
                        .executes(ctx -> this.usage(ctx, "usage-admin-monety"))
                        .then(Commands.argument("kwota", StringArgumentType.greedyString())
                            .suggests(this.provider(this::suggestAmounts))
                            .executes(ctx -> this.run(ctx, () -> this.adminCoins(ctx.getSource().getSender(),
                                arg(ctx, "gracz"), arg(ctx, "kwota"))))))))
            .then(Commands.literal("npc")
                .requires(admin)
                .executes(ctx -> this.usage(ctx, "usage-npc"))
                .then(Commands.literal("stworz")
                    .executes(ctx -> this.usage(ctx, "usage-npc"))
                    .then(this.npcCreate("kupiec", NpcType.MERCHANT))
                    .then(this.npcCreate("bankier", NpcType.BANKER)))
                .then(Commands.literal("usun")
                    .executes(ctx -> this.run(ctx, () -> this.npcRemove(ctx.getSource().getSender(), null)))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider(this::suggestNpcIds))
                        .executes(ctx -> this.run(ctx, () -> this.npcRemove(ctx.getSource().getSender(), arg(ctx, "id"))))))
                .then(Commands.literal("skin")
                    .executes(ctx -> this.usage(ctx, "usage-npc"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider((s, b) -> {
                            add(b, "najblizszy", this.m().get("suggest-npc-nearest"));
                            this.suggestNpcIds(s, b);
                        }))
                        .executes(ctx -> this.usage(ctx, "usage-npc"))
                        .then(Commands.argument("skin", StringArgumentType.word())
                            .suggests(this.provider((s, b) -> {
                                add(b, NpcSkins.RANDOM, this.m().get("suggest-skin-random"));
                                this.suggestSkins(s, b);
                            }))
                            .executes(ctx -> this.run(ctx, () -> this.npcSkin(ctx.getSource().getSender(),
                                arg(ctx, "id"), arg(ctx, "skin")))))))
                .then(Commands.literal("typ")
                    .executes(ctx -> this.usage(ctx, "usage-npc"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider((s, b) -> {
                            add(b, "najblizszy", this.m().get("suggest-npc-nearest"));
                            this.suggestNpcIds(s, b);
                        }))
                        .executes(ctx -> this.usage(ctx, "usage-npc"))
                        .then(Commands.argument("typ", StringArgumentType.word())
                            .suggests(this.provider(this::suggestMerchantTypes))
                            .executes(ctx -> this.run(ctx, () -> this.npcMerchantType(ctx.getSource().getSender(),
                                arg(ctx, "id"), arg(ctx, "typ")))))))
                .then(Commands.literal("lista")
                    .executes(ctx -> this.run(ctx, () -> this.npcList(ctx.getSource().getSender())))))
            .then(Commands.literal("kram")
                .requires(admin)
                .executes(ctx -> this.run(ctx, () -> this.stallStatus(ctx.getSource().getSender())))
                .then(Commands.literal("postaw")
                    .executes(ctx -> this.run(ctx, () -> this.stallPlace(ctx.getSource().getSender(), "najblizszy")))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider(this::suggestMerchantIds))
                        .executes(ctx -> this.run(ctx, () -> this.stallPlace(ctx.getSource().getSender(), arg(ctx, "id"))))))
                .then(Commands.literal("przesun")
                    .executes(ctx -> this.usage(ctx, "usage-kram"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider(this::suggestMerchantIds))
                        .executes(ctx -> this.usage(ctx, "usage-kram"))
                        .then(Commands.argument("dx dy dz", StringArgumentType.greedyString())
                            .suggests(this.provider((s, b) -> add(b, "0 0 1", this.m().get("suggest-stall-shift"))))
                            .executes(ctx -> this.run(ctx, () -> this.stallMove(ctx.getSource().getSender(), arg(ctx, "id"), arg(ctx, "dx dy dz")))))))
                .then(Commands.literal("obroc")
                    .executes(ctx -> this.usage(ctx, "usage-kram"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider(this::suggestMerchantIds))
                        .executes(ctx -> this.usage(ctx, "usage-kram"))
                        .then(Commands.argument("stopnie", StringArgumentType.word())
                            .suggests(this.provider((s, b) -> {
                                add(b, "90", this.m().get("suggest-stall-rotate"));
                                add(b, "180", this.m().get("suggest-stall-rotate"));
                                add(b, "-90", this.m().get("suggest-stall-rotate"));
                            }))
                            .executes(ctx -> this.run(ctx, () -> this.stallRotate(ctx.getSource().getSender(), arg(ctx, "id"), arg(ctx, "stopnie")))))))
                .then(Commands.literal("usun")
                    .executes(ctx -> this.run(ctx, () -> this.stallRemove(ctx.getSource().getSender(), "najblizszy")))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .suggests(this.provider(this::suggestMerchantIds))
                        .executes(ctx -> this.run(ctx, () -> this.stallRemove(ctx.getSource().getSender(), arg(ctx, "id")))))))
            .then(Commands.literal("log")
                .requires(admin)
                .executes(ctx -> this.usage(ctx, "usage-log"))
                .then(Commands.argument("gracz|konto", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestTargets))
                    .executes(ctx -> this.run(ctx, () -> this.log(ctx.getSource().getSender(), arg(ctx, "gracz|konto"))))))
            .then(Commands.literal("info")
                .requires(admin)
                .executes(ctx -> this.run(ctx, () -> this.info(ctx.getSource().getSender()))))
            .then(Commands.literal("reload")
                .requires(admin)
                .executes(ctx -> this.run(ctx, () -> {
                    this.plugin.reload();
                    this.m().send(ctx.getSource().getSender(), "reloaded");
                })))
            .build();
    }

    /** {@code /skarbiec admin daj|zabierz|ustaw <gracz|konto> <kwota>} (greedy: target is the first word). */
    private LiteralArgumentBuilder<CommandSourceStack> adminOp(final String op) {
        return Commands.literal(op)
            .executes(ctx -> this.usage(ctx, "usage-admin"))
            .then(Commands.argument("gracz|konto kwota", StringArgumentType.greedyString())
                .suggests(this.provider(this::suggestTargetThenAmount))
                .executes(ctx -> this.run(ctx, () -> this.adminAccount(ctx.getSource().getSender(), op,
                    arg(ctx, "gracz|konto kwota")))));
    }

    /** {@code /skarbiec npc stworz <typ> ["nazwa"] [skin]}; the name may be quoted to hold spaces/Polish letters. */
    private LiteralArgumentBuilder<CommandSourceStack> npcCreate(final String literal, final NpcType type) {
        return Commands.literal(literal)
            .executes(ctx -> this.run(ctx, () -> this.npcCreate(ctx.getSource().getSender(), type, null, null)))
            .then(Commands.argument("nazwa", StringArgumentType.string())
                .suggests(this.provider((s, b) -> add(b, "\"" + (type == NpcType.BANKER ? "Bankier" : "Kupiec") + "\"",
                    this.m().get("suggest-npc-name"))))
                .executes(ctx -> this.run(ctx, () -> this.npcCreate(ctx.getSource().getSender(), type, arg(ctx, "nazwa"), null)))
                .then(Commands.argument("skin", StringArgumentType.word())
                    .suggests(this.provider((s, b) -> {
                        add(b, NpcSkins.RANDOM, this.m().get("suggest-skin-random"));
                        this.suggestSkins(s, b);
                    }))
                    .executes(ctx -> this.run(ctx, () -> this.npcCreate(ctx.getSource().getSender(), type,
                        arg(ctx, "nazwa"), arg(ctx, "skin"))))
                    .then(Commands.argument("typ", StringArgumentType.word())
                        .requires(src -> type == NpcType.MERCHANT)
                        .suggests(this.provider(this::suggestMerchantTypes))
                        .executes(ctx -> this.run(ctx, () -> this.npcCreate(ctx.getSource().getSender(), type,
                            arg(ctx, "nazwa"), arg(ctx, "skin"), arg(ctx, "typ")))))));
    }

    // ================================================================== plumbing

    private int run(final CommandContext<CommandSourceStack> ctx, final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(java.util.logging.Level.SEVERE, "Błąd komendy Skarbca", e);
            this.m().send(ctx.getSource().getSender(), "error.internal");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSourceStack> ctx, final String key) {
        this.m().send(ctx.getSource().getSender(), key);
        return Command.SINGLE_SUCCESS;
    }

    private static String arg(final CommandContext<CommandSourceStack> ctx, final String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private Player requirePlayer(final CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        this.m().send(sender, "players-only");
        return null;
    }

    /** Player known to the server (online or played before) by exact name, or null. */
    private static OfflinePlayer known(final String name) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        final OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && (cached.hasPlayedBefore() || cached.isOnline()) ? cached : null;
    }

    /** {@code gracz} | {@code namespace:id} | {@code kraj:<nazwa>} → account id, or null (message sent). */
    private String resolveAccount(final CommandSender sender, final String target) {
        if (target.toLowerCase(Locale.ROOT).startsWith("kraj:")) {
            final Optional<String> acc = this.plugin.territory().countryAccount(target.substring(5));
            if (acc.isEmpty()) {
                this.m().send(sender, "unknown-country", Placeholder.unparsed("name", target.substring(5)));
                return null;
            }
            return acc.get();
        }
        if (target.indexOf(':') >= 0) {
            if (!Ledger.validAccount(target)) {
                this.m().send(sender, "invalid-account", Placeholder.unparsed("account", target));
                return null;
            }
            return target;
        }
        final OfflinePlayer p = known(target);
        if (p == null) {
            this.m().send(sender, "unknown-player", Placeholder.unparsed("player", target));
            return null;
        }
        return SkarbiecApi.playerAccount(p.getUniqueId());
    }

    /** Readable account name: player name, country name or the raw id. */
    private String describeAccount(final String account) {
        if (account == null) {
            return "-";
        }
        try {
            if (account.startsWith("player:")) {
                return SkarbiecPlugin.nameOf(UUID.fromString(account.substring(7)));
            }
            if (account.startsWith("country:")) {
                final UUID id = UUID.fromString(account.substring(8));
                return this.plugin.territory().countryName(id).map(n -> this.m().raw("account-country").replace("<name>", n))
                    .orElse(account);
            }
        } catch (final IllegalArgumentException ignored) {
            // not a uuid → raw id
        }
        return account;
    }

    private Optional<Long> amount(final CommandSender sender, final String text) {
        final Optional<Long> a = Money.parse(text);
        if (a.isEmpty() || a.get() <= 0) {
            this.m().send(sender, "invalid-amount", Placeholder.unparsed("input", text));
            return Optional.empty();
        }
        return a;
    }

    // ================================================================== actions

    private void help(final CommandSender sender, final String page) {
        for (final Component line : HelpBook.render(this.m(), sender.hasPermission(PERM_ADMIN), page)) {
            sender.sendMessage(line);
        }
    }

    private void balance(final CommandSender sender, final String other) {
        if (other == null) {
            final Player p = this.requirePlayer(sender);
            if (p == null) {
                return;
            }
            this.m().send(p, "balance", SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(SkarbiecPlugin.account(p))),
                SkarbiecPlugin.amount("coins", this.plugin.coins().countCoins(p)));
            return;
        }
        final String account = this.resolveAccount(sender, other);
        if (account != null) {
            this.m().send(sender, "balance-other", Placeholder.unparsed("account", this.describeAccount(account)),
                SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(account)));
        }
    }

    private void coins(final CommandSender sender) {
        final Player p = this.requirePlayer(sender);
        if (p == null) {
            return;
        }
        final long[] c = this.plugin.coins().countByDenomination(p);
        this.m().send(p, "coins", SkarbiecPlugin.amount("total", this.plugin.coins().countCoins(p)),
            Placeholder.unparsed("n_copper", Long.toString(c[0])), Placeholder.unparsed("n_silver", Long.toString(c[1])),
            Placeholder.unparsed("n_gold", Long.toString(c[2])));
    }

    private void pay(final CommandSender sender, final String targetName, final String amountText) {
        final Player p = this.requirePlayer(sender);
        if (p == null) {
            return;
        }
        final SkarbiecConfig cfg = this.plugin.cfg();
        if (cfg.payMode == SkarbiecConfig.PayMode.BANKER && !p.hasPermission(PERM_ADMIN)
            && this.plugin.npcs().nearest(p, cfg.bankerRadius, NpcType.BANKER) == null) {
            this.m().send(p, "pay.need-banker", Placeholder.unparsed("radius", Integer.toString((int) cfg.bankerRadius)));
            return;
        }
        final OfflinePlayer target = known(targetName);
        if (target == null) {
            this.m().send(p, "unknown-player", Placeholder.unparsed("player", targetName));
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            this.m().send(p, "pay.self");
            return;
        }
        final Optional<Long> amount = this.amount(p, amountText);
        if (amount.isEmpty()) {
            return;
        }
        if (amount.get() < cfg.payMin) {
            this.m().send(p, "pay.too-small", SkarbiecPlugin.amount(cfg.payMin));
            return;
        }
        final String name = target.getName() == null ? targetName : target.getName();
        final String from = SkarbiecPlugin.account(p);
        final TxResult r = this.plugin.ledger().transfer(from, SkarbiecApi.playerAccount(target.getUniqueId()), amount.get(),
            "przelew: " + p.getName() + " -> " + name);
        switch (r) {
            case OK -> {
                this.m().send(p, "pay.sent", SkarbiecPlugin.amount(amount.get()), Placeholder.unparsed("player", name),
                    SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(from)));
                final Player online = target.getPlayer();
                if (online != null) {
                    this.m().send(online, "pay.received", SkarbiecPlugin.amount(amount.get()), Placeholder.unparsed("player", p.getName()));
                }
            }
            case INSUFFICIENT_FUNDS -> this.m().send(p, "pay.insufficient", SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(from)));
            default -> this.m().send(p, "error.transaction", Placeholder.unparsed("reason", r.name()));
        }
    }

    private void trade(final CommandSender sender, final String targetName) {
        final Player p = this.requirePlayer(sender);
        if (p == null) {
            return;
        }
        final Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            this.m().send(p, "unknown-online-player", Placeholder.unparsed("player", targetName));
            return;
        }
        this.plugin.trades().request(p, target);
    }

    private void listShops(final CommandSender sender) {
        final Player p = this.requirePlayer(sender);
        if (p == null) {
            return;
        }
        final List<Shop> own = this.plugin.shops().ownedBy(p.getUniqueId());
        if (own.isEmpty()) {
            this.m().send(p, "shop.none");
            return;
        }
        this.m().send(p, "shop.list-header", Placeholder.unparsed("count", Integer.toString(own.size())));
        for (final Shop s : own) {
            final var chest = s.chestBlock();
            this.m().send(p, s.type() == Shop.Type.SELL ? "shop.list-line-sell" : "shop.list-line-buy",
                Placeholder.unparsed("count", Integer.toString(s.amount())),
                Placeholder.component("item", s.item().effectiveName()), SkarbiecPlugin.amount(s.price()),
                Placeholder.unparsed("where", chest == null ? s.world() : s.world() + " " + chest.getX() + " " + chest.getY() + " " + chest.getZ()),
                Placeholder.unparsed("stock", s.chunkLoaded() ? Integer.toString(this.plugin.shops().stock(s)) : "?"));
        }
    }

    private void adminAccount(final CommandSender sender, final String op, final String rest) {
        if(!worldAdmin(sender))return;
        final String trimmed = rest.trim();
        final int space = trimmed.indexOf(' ');
        if (space < 0) {
            this.m().send(sender, "usage-admin");
            return;
        }
        final String account = this.resolveAccount(sender, trimmed.substring(0, space));
        if (account == null) {
            return;
        }
        final String amountText = trimmed.substring(space + 1).trim();
        final Optional<Long> parsed = Money.parse(amountText);
        if (parsed.isEmpty() || (parsed.get() <= 0 && !op.equals("ustaw"))) {
            this.m().send(sender, "invalid-amount", Placeholder.unparsed("input", amountText));
            return;
        }
        final long amount = parsed.get();
        final String reason = "admin " + op + " (" + sender.getName() + ")";
        final TxResult r = switch (op) {
            case "daj" -> this.plugin.ledger().deposit(account, amount, reason);
            case "zabierz" -> this.plugin.ledger().withdraw(account, amount, reason);
            default -> this.plugin.ledger().set(account, amount, reason);
        };
        if (r == TxResult.INSUFFICIENT_FUNDS) {
            this.m().send(sender, "admin.insufficient", Placeholder.unparsed("account", this.describeAccount(account)),
                SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(account)));
            return;
        }
        if (!r.ok()) {
            this.m().send(sender, "error.transaction", Placeholder.unparsed("reason", r.name()));
            return;
        }
        this.m().send(sender, "admin.done", Placeholder.unparsed("op", op),
            Placeholder.unparsed("account", this.describeAccount(account)), SkarbiecPlugin.amount(amount),
            SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(account)));
    }

    private void adminCoins(final CommandSender sender, final String playerName, final String amountText) {
        if(!worldAdmin(sender))return;
        final Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            this.m().send(sender, "unknown-online-player", Placeholder.unparsed("player", playerName));
            return;
        }
        final Optional<Long> amount = this.amount(sender, amountText);
        if (amount.isEmpty()) {
            return;
        }
        if (amount.get() > 100_000_00L) {
            this.m().send(sender, "admin.too-many-coins");
            return;
        }
        if(!this.plugin.coins().payFromAccount(target,pl.medivalrpg.skarbiec.ledger.WorldMoney.PAYMENTS,amount.get(),"Admin: wypłata monet "+sender.getName()).ok()){
            sender.sendMessage(Component.text("Brak środków w budżecie operacyjnym. Użyj /skarbswiata budzet <kwota>."));return;
        }
        this.plugin.store().audit("ADMIN_COINS\t" + target.getUniqueId() + "\t" + target.getName() + "\t+" + amount.get()
            + "\tprzez " + sender.getName());
        this.m().send(sender, "admin.coins-given", Placeholder.unparsed("player", target.getName()), SkarbiecPlugin.amount(amount.get()));
    }

    private void npcCreate(final CommandSender sender, final NpcType type, final String name, final String skin) {
        this.npcCreate(sender, type, name, skin, null);
    }

    private void npcCreate(final CommandSender sender, final NpcType type, final String name, final String skin, final String merchantType) {
        final Player p = this.requirePlayer(sender);
        if (p == null) {
            return;
        }
        if (merchantType != null && !this.plugin.cfg().merchantTypes.containsKey(merchantType.toLowerCase(java.util.Locale.ROOT))) {
            this.m().send(p, "npc.unknown-merchant-type", Placeholder.unparsed("type", merchantType),
                Placeholder.unparsed("types", String.join(", ", this.plugin.cfg().merchantTypes.keySet())));
            return;
        }
        final SkarbiecConfig cfg = this.plugin.cfg();
        final String displayName = name == null || name.isBlank()
            ? (type == NpcType.BANKER ? cfg.bankerName : cfg.merchantName)
            : "<gold>" + Messages.mini().escapeTags(name.replace('_', ' '));
        String skinName = skin != null ? skin : (type == NpcType.BANKER ? cfg.bankerSkin : cfg.merchantSkin);
        if (NpcSkins.isRandomKeyword(skinName)) {
            skinName = "";
        }
        if (skinName != null && !skinName.isBlank() && !NpcManager.validSkin(skinName)) {
            this.m().send(p, "npc.bad-skin", Placeholder.unparsed("skin", skinName));
            return;
        }
        final Location at = p.getLocation();
        final NpcRecord r = this.plugin.npcs().create(type, displayName, skinName, at,
            merchantType == null ? null : merchantType.toLowerCase(java.util.Locale.ROOT));
        this.m().send(p, "npc.created", Placeholder.unparsed("type", this.npcTypeLabel(r)),
            Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)));
    }

    private boolean worldAdmin(CommandSender sender){
        if(!plugin.ledger().closedSupply() || sender instanceof Player p && pl.medivalrpg.skarbiec.ledger.WorldMoney.MANAGER.equals(p.getUniqueId()))return true;
        sender.sendMessage(Component.text("W zamkniętym obiegu korekty wykonuje wyłącznie WorldSteward."));return false;
    }

    private void npcRemove(final CommandSender sender, final String id) {
        NpcRecord r = null;
        if (id != null) {
            r = this.plugin.npcs().byIdPrefix(id);
        } else if (sender instanceof Player p) {
            r = this.plugin.npcs().nearest(p, 5, null);
        }
        if (r == null) {
            this.m().send(sender, "npc.not-found");
            return;
        }
        this.plugin.npcs().remove(r);
        this.m().send(sender, "npc.removed", Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)));
    }

    private void npcSkin(final CommandSender sender, final String id, final String skin) {
        NpcRecord r = null;
        if (id.equalsIgnoreCase("najblizszy") || id.equalsIgnoreCase("nearest")) {
            if (sender instanceof Player p) {
                r = this.plugin.npcs().nearest(p, 5, null);
            }
        } else {
            r = this.plugin.npcs().byIdPrefix(id);
        }
        if (r == null) {
            this.m().send(sender, "npc.not-found");
            return;
        }
        if (!this.plugin.npcs().setSkin(r, skin)) {
            this.m().send(sender, "npc.bad-skin", Placeholder.unparsed("skin", skin));
            return;
        }
        this.m().send(sender, "npc.skin-changed", Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)),
            Placeholder.unparsed("skin", r.skin().isBlank() ? this.m().raw("npc.skin-random") : r.skin()));
    }

    /** {@code kupiec (kowal)} for merchants, {@code bankier} for bankers. */
    private String npcTypeLabel(final NpcRecord r) {
        return r.type() == NpcType.MERCHANT ? r.type().id() + " (" + this.plugin.npcs().merchantType(r).id() + ")" : r.type().id();
    }

    /** {@code /skarbiec npc typ <id|najblizszy> <typ>}: merchant type of an existing merchant NPC. */
    private void npcMerchantType(final CommandSender sender, final String id, final String typeId) {
        NpcRecord r = null;
        if (id.equalsIgnoreCase("najblizszy") || id.equalsIgnoreCase("nearest")) {
            if (sender instanceof Player p) {
                r = this.plugin.npcs().nearest(p, 5, NpcType.MERCHANT);
            }
        } else {
            r = this.plugin.npcs().byIdPrefix(id);
        }
        if (r == null) {
            this.m().send(sender, "npc.not-found");
            return;
        }
        if (r.type() != NpcType.MERCHANT) {
            this.m().send(sender, "npc.not-merchant");
            return;
        }
        final String key = typeId.toLowerCase(java.util.Locale.ROOT);
        final var t = this.plugin.cfg().merchantTypes.get(key);
        if (t == null) {
            this.m().send(sender, "npc.unknown-merchant-type", Placeholder.unparsed("type", typeId),
                Placeholder.unparsed("types", String.join(", ", this.plugin.cfg().merchantTypes.keySet())));
            return;
        }
        this.plugin.npcs().setMerchantType(r, key);
        this.plugin.stalls().refresh(r);
        this.m().send(sender, "npc.merchant-type-changed", Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)), Placeholder.parsed("type", t.name()),
            Placeholder.unparsed("typeid", t.id()));
    }

    /** {@code /skarbiec kupcy}: merchant types, what they have now and when the next delivery comes. */
    private void merchants(final CommandSender sender) {
        final var svc = this.plugin.merchant();
        final var cfg = this.plugin.cfg();
        if (!svc.assortmentOn()) {
            this.m().send(sender, "merchants.fixed", Placeholder.unparsed("count", Integer.toString(cfg.offers.size())));
            return;
        }
        final long minutes = Math.max(1, svc.millisToDelivery() / 60_000);
        this.m().send(sender, "merchants.header", Placeholder.unparsed("hours", Long.toString(minutes / 60)),
            Placeholder.unparsed("minutes", Long.toString(minutes % 60)),
            Placeholder.unparsed("catalog", Integer.toString(cfg.offers.size())));
        svc.season().ifPresent(season -> this.m().send(sender, "merchants.season", Placeholder.unparsed("season", season.name())));
        final java.util.Map<String, Long> placed = new java.util.HashMap<>();
        for (final NpcRecord r : this.plugin.npcs().all()) {
            if (r.type() == NpcType.MERCHANT) {
                placed.merge(this.plugin.npcs().merchantType(r).id(), 1L, Long::sum);
            }
        }
        for (final var t : cfg.merchantTypes.values()) {
            final var pick = svc.pick(t);
            this.m().send(sender, "merchants.line", Placeholder.parsed("type", t.name()),
                Placeholder.unparsed("count", Integer.toString(pick.offers().size())),
                Placeholder.unparsed("core", Integer.toString(pick.core().size())),
                Placeholder.unparsed("delivery", Integer.toString(pick.delivery().size())),
                Placeholder.unparsed("npcs", Long.toString(placed.getOrDefault(t.id(), 0L))));
            final java.util.List<Component> wanted = new java.util.ArrayList<>();
            for (final var o : pick.offers()) {
                if (pick.featured().contains(o.key())) {
                    wanted.add(Component.translatable(o.material()));
                }
            }
            if (!wanted.isEmpty()) {
                this.m().send(sender, "merchants.featured", Placeholder.component("items",
                    Component.join(net.kyori.adventure.text.JoinConfiguration.commas(true), wanted)));
            }
        }
    }

    // ------------------------------------------------------------------ market stalls (BetterModel)

    private void suggestMerchantIds(final CommandSender sender, final SuggestionsBuilder builder) {
        add(builder, "najblizszy", this.m().get("suggest-npc-nearest"));
        for (final NpcRecord r : this.plugin.npcs().all()) {
            if (r.type() == NpcType.MERCHANT) {
                add(builder, r.id().toString().substring(0, 8), Messages.mini().deserialize(r.name()));
            }
        }
    }

    /** Merchant by id prefix or the nearest one within 8 blocks ({@code najblizszy}); sends "not found" itself. */
    private NpcRecord merchantNpc(final CommandSender sender, final String id) {
        NpcRecord r = null;
        if (id == null || id.equalsIgnoreCase("najblizszy") || id.equalsIgnoreCase("nearest")) {
            if (sender instanceof Player p) {
                r = this.plugin.npcs().nearest(p, 8, NpcType.MERCHANT);
            }
        } else {
            r = this.plugin.npcs().byIdPrefix(id);
        }
        if (r == null) {
            this.m().send(sender, "npc.not-found");
            return null;
        }
        if (r.type() != NpcType.MERCHANT) {
            this.m().send(sender, "npc.not-merchant");
            return null;
        }
        return r;
    }

    private void stallSaved(final CommandSender sender, final NpcRecord r, final String key) {
        this.plugin.stalls().refresh(r);
        final NpcRecord.Stall s = r.stall();
        final String typeId = this.plugin.npcs().merchantType(r).id();
        this.m().send(sender, key, Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)),
            Placeholder.unparsed("model", this.plugin.stalls().modelFor(typeId)),
            Placeholder.unparsed("where", s == null ? "-" : String.format(Locale.ROOT, "%.2f %.2f %.2f, %.0f°", s.x(), s.y(), s.z(), s.yaw())));
        if (s != null && !this.plugin.stalls().modelLoaded(typeId)) {
            this.m().send(sender, "stall.no-model", Placeholder.unparsed("model", this.plugin.stalls().modelFor(typeId)));
        }
    }

    /** {@code /skarbiec kram postaw [id|najblizszy]}: stall at the merchant's feet, facing where he faces. */
    private void stallPlace(final CommandSender sender, final String id) {
        final NpcRecord r = this.merchantNpc(sender, id);
        if (r == null) {
            return;
        }
        final Location at = r.location();
        if (at == null) {
            this.m().send(sender, "npc.not-found");
            return;
        }
        this.plugin.npcs().setStall(r, new NpcRecord.Stall(at.getX(), at.getY(), at.getZ(), at.getYaw()));
        this.stallSaved(sender, r, "stall.placed");
    }

    /** {@code /skarbiec kram przesun <id> <dx> <dy> <dz>} in blocks (at most 8 per call). */
    private void stallMove(final CommandSender sender, final String id, final String delta) {
        final NpcRecord r = this.merchantNpc(sender, id);
        if (r == null) {
            return;
        }
        if (r.stall() == null) {
            this.m().send(sender, "stall.none");
            return;
        }
        final String[] parts = delta.trim().split("\\s+");
        final double[] d = new double[3];
        try {
            if (parts.length != 3) {
                throw new NumberFormatException();
            }
            for (int i = 0; i < 3; i++) {
                d[i] = Double.parseDouble(parts[i].replace(',', '.'));
                if (!Double.isFinite(d[i]) || Math.abs(d[i]) > 8) {
                    throw new NumberFormatException();
                }
            }
        } catch (final NumberFormatException e) {
            this.m().send(sender, "usage-kram");
            return;
        }
        final NpcRecord.Stall s = r.stall();
        this.plugin.npcs().setStall(r, new NpcRecord.Stall(s.x() + d[0], s.y() + d[1], s.z() + d[2], s.yaw()));
        this.stallSaved(sender, r, "stall.moved");
    }

    /** {@code /skarbiec kram obroc <id> <stopnie>}: adds degrees to the stall's yaw. */
    private void stallRotate(final CommandSender sender, final String id, final String degrees) {
        final NpcRecord r = this.merchantNpc(sender, id);
        if (r == null) {
            return;
        }
        if (r.stall() == null) {
            this.m().send(sender, "stall.none");
            return;
        }
        final float add;
        try {
            add = Float.parseFloat(degrees.replace(',', '.'));
            if (!Float.isFinite(add)) {
                throw new NumberFormatException();
            }
        } catch (final NumberFormatException e) {
            this.m().send(sender, "usage-kram");
            return;
        }
        final NpcRecord.Stall s = r.stall();
        float yaw = (s.yaw() + add) % 360f;
        if (yaw > 180f) {
            yaw -= 360f;
        } else if (yaw <= -180f) {
            yaw += 360f;
        }
        this.plugin.npcs().setStall(r, new NpcRecord.Stall(s.x(), s.y(), s.z(), yaw));
        this.stallSaved(sender, r, "stall.rotated");
    }

    private void stallRemove(final CommandSender sender, final String id) {
        final NpcRecord r = this.merchantNpc(sender, id);
        if (r == null) {
            return;
        }
        if (r.stall() == null) {
            this.m().send(sender, "stall.none");
            return;
        }
        this.plugin.npcs().setStall(r, null);
        this.plugin.stalls().refresh(r);
        this.m().send(sender, "stall.removed", Placeholder.component("name", Messages.mini().deserialize(r.name())),
            Placeholder.unparsed("id", r.id().toString().substring(0, 8)));
    }

    /** {@code /skarbiec kram}: BetterModel state and every placed stall. */
    private void stallStatus(final CommandSender sender) {
        final var stalls = this.plugin.stalls();
        this.m().send(sender, stalls.available() ? "stall.status" : "stall.status-off",
            Placeholder.unparsed("shown", Integer.toString(stalls.shown())));
        for (final NpcRecord r : this.plugin.npcs().all()) {
            final NpcRecord.Stall s = r.stall();
            if (s == null) {
                continue;
            }
            final String typeId = this.plugin.npcs().merchantType(r).id();
            this.m().send(sender, "stall.line", Placeholder.unparsed("id", r.id().toString().substring(0, 8)),
                Placeholder.component("name", Messages.mini().deserialize(r.name())),
                Placeholder.unparsed("model", stalls.modelFor(typeId)),
                Placeholder.unparsed("loaded", this.m().raw(stalls.modelLoaded(typeId) ? "stall.loaded-yes" : "stall.loaded-no")),
                Placeholder.unparsed("where", String.format(Locale.ROOT, "%.1f %.1f %.1f, %.0f°", s.x(), s.y(), s.z(), s.yaw())));
        }
        this.m().send(sender, "usage-kram");
    }

    private void suggestMerchantTypes(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final var t : this.plugin.cfg().merchantTypes.values()) {
            add(builder, t.id(), Messages.mini().deserialize(t.name()));
        }
    }

    private void npcList(final CommandSender sender) {
        final var all = this.plugin.npcs().all();
        if (all.isEmpty()) {
            this.m().send(sender, "npc.none");
            return;
        }
        this.m().send(sender, "npc.list-header", Placeholder.unparsed("count", Integer.toString(all.size())));
        for (final NpcRecord r : all) {
            final Location l = r.location();
            this.m().send(sender, "npc.list-line", Placeholder.unparsed("id", r.id().toString().substring(0, 8)),
                Placeholder.unparsed("type", this.npcTypeLabel(r)), Placeholder.component("name", Messages.mini().deserialize(r.name())),
                Placeholder.unparsed("where", l == null ? r.world() : r.world() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()),
                Placeholder.unparsed("skin", r.skin().isBlank() ? this.m().raw("npc.skin-random") : r.skin()));
        }
    }

    private void log(final CommandSender sender, final String rest) {
        final String[] parts = rest.trim().split(" +");
        final String account = this.resolveAccount(sender, parts[0]);
        if (account == null) {
            return;
        }
        int page = 1;
        if (parts.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(parts[1]));
            } catch (final NumberFormatException ignored) {
                // page 1
            }
        }
        final int pageNo = page;
        final String needle = account.startsWith("player:") ? account.substring(7) : account;
        final String title = this.describeAccount(account);
        // Reading the log files happens off the main thread; the answer is sent back on it.
        this.plugin.files().execute(() -> {
            final List<TxRecord> tx = this.plugin.store().history(account, pageNo * LOG_PAGE);
            final List<String> audit = this.plugin.store().auditHistory(needle, LOG_PAGE);
            Bukkit.getScheduler().runTask(this.plugin, () -> this.sendLog(sender, account, title, pageNo, tx, audit));
        });
    }

    private void sendLog(final CommandSender sender, final String account, final String title, final int page,
                         final List<TxRecord> tx, final List<String> audit) {
        final Messages m = this.m();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(this.plugin.cfg().zone);
        m.send(sender, "log.header", Placeholder.unparsed("account", title), Placeholder.unparsed("page", Integer.toString(page)),
            SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(account)));
        final int from = (page - 1) * LOG_PAGE;
        if (tx.size() <= from) {
            m.send(sender, "log.empty");
        }
        for (int i = from; i < tx.size() && i < from + LOG_PAGE; i++) {
            final TxRecord r = tx.get(i);
            final String sign;
            final String other;
            final Long after;
            if (r.type() == TxRecord.Type.SET) {
                sign = "=";
                other = "-";
                after = r.toAfter();
            } else if (account.equals(r.to())) {
                sign = "+";
                other = this.describeAccount(r.from());
                after = r.toAfter();
            } else {
                sign = "-";
                other = this.describeAccount(r.to());
                after = r.fromAfter();
            }
            m.send(sender, "log.line", Placeholder.unparsed("time", fmt.format(Instant.ofEpochMilli(r.time()))),
                Placeholder.unparsed("sign", sign), SkarbiecPlugin.amount(r.amount()), Placeholder.unparsed("other", other),
                Placeholder.unparsed("reason", r.reason()), SkarbiecPlugin.amount("after", after == null ? 0 : after),
                Placeholder.unparsed("seq", Long.toString(r.seq())));
        }
        if (page == 1 && !audit.isEmpty()) {
            m.send(sender, "log.audit-header");
            for (final String line : audit) {
                m.send(sender, "log.audit-line", Placeholder.unparsed("line", shortAudit(line, this.plugin.cfg().zone)));
            }
        }
    }

    private static String shortAudit(final String line, final ZoneId zone) {
        final String[] p = line.split("\t");
        if (p.length < 2) {
            return line;
        }
        try {
            final String time = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(Instant.parse(p[0]));
            final StringBuilder sb = new StringBuilder(time).append(' ').append(p[1]);
            for (int i = 4; i < p.length; i++) {
                sb.append(' ').append(p[i]);
            }
            return sb.toString();
        } catch (final RuntimeException e) {
            return line;
        }
    }

    private void info(final CommandSender sender) {
        final Ledger l = this.plugin.ledger();
        this.m().send(sender, "info", Placeholder.unparsed("accounts", Integer.toString(l.balances().size())),
            SkarbiecPlugin.amount("total", l.totalMoney()), Placeholder.unparsed("seq", Long.toString(l.seq())),
            Placeholder.unparsed("shops", Integer.toString(this.plugin.shops().count())),
            Placeholder.unparsed("npcs", Integer.toString(this.plugin.npcs().all().size())),
            Placeholder.unparsed("offers", Integer.toString(this.plugin.cfg().offers.size())));
    }

    // ================================================================== suggestions

    private SuggestionProvider<CommandSourceStack> provider(final BiConsumer<CommandSender, SuggestionsBuilder> fill) {
        return (ctx, builder) -> {
            final CommandSender sender = ctx.getSource().getSender();
            if (Bukkit.isPrimaryThread()) {
                fill.accept(sender, builder);
                return builder.buildFuture();
            }
            try {
                return CompletableFuture.supplyAsync(() -> {
                    fill.accept(sender, builder);
                    return builder.build();
                }, Bukkit.getScheduler().getMainThreadExecutor(this.plugin));
            } catch (final RuntimeException e) {
                return Suggestions.empty();
            }
        };
    }

    private static void add(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(text, MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    private Component playerTooltip(final Player p) {
        return this.m().get("suggest-player", SkarbiecPlugin.amount("balance", this.plugin.ledger().balance(SkarbiecPlugin.account(p))));
    }

    private void suggestPlayers(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            add(builder, p.getName(), this.playerTooltip(p));
        }
    }

    private void suggestOtherPlayers(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(sender)) {
                add(builder, p.getName(), this.m().get("suggest-pay-player"));
            }
        }
    }

    private void suggestNearbyPlayers(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!(sender instanceof Player self)) {
            return;
        }
        final double r = this.plugin.cfg().tradeRange;
        for (final Player p : self.getWorld().getPlayers()) {
            if (!p.equals(self) && p.getLocation().distanceSquared(self.getLocation()) <= r * r) {
                add(builder, p.getName(), this.m().get("suggest-trade-player",
                    Placeholder.unparsed("distance", Integer.toString((int) p.getLocation().distance(self.getLocation())))));
            }
        }
    }

    private void addAmounts(final SuggestionsBuilder builder) {
        for (final String a : AMOUNTS) {
            add(builder, a, this.m().get("suggest-amount", SkarbiecPlugin.amount(Money.parse(a).orElse(0L))));
        }
    }

    private void suggestAmounts(final CommandSender sender, final SuggestionsBuilder builder) {
        if (builder.getRemaining().indexOf(' ') < 0) {
            this.addAmounts(builder);
        }
    }

    private void suggestTargets(final CommandSender sender, final SuggestionsBuilder builder) {
        if (builder.getRemaining().indexOf(' ') >= 0) {
            return;
        }
        this.suggestPlayers(sender, builder);
        add(builder, "kraj:", this.m().get("suggest-country-account"));
    }

    /** First word: player / account; second: amount. */
    private void suggestTargetThenAmount(final CommandSender sender, final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int space = remaining.indexOf(' ');
        if (space < 0) {
            this.suggestTargets(sender, builder);
            return;
        }
        if (remaining.indexOf(' ', space + 1) >= 0) {
            return;
        }
        final SuggestionsBuilder second = builder.createOffset(builder.getStart() + space + 1);
        this.addAmounts(second);
        builder.add(second);
    }

    private void suggestHelpPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final var pages = HelpBook.pages(sender.hasPermission(PERM_ADMIN));
        for (int i = 0; i < pages.size(); i++) {
            add(builder, Integer.toString(i + 1), this.m().get("help-page-hover",
                Placeholder.parsed("category", this.m().raw("help-categories." + pages.get(i).key()))));
        }
    }

    private void suggestNpcIds(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final NpcRecord r : this.plugin.npcs().all()) {
            add(builder, r.id().toString().substring(0, 8), Messages.mini().deserialize(r.name()));
        }
    }

    private void suggestSkins(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            add(builder, p.getName(), this.m().get("suggest-skin"));
        }
    }
}
