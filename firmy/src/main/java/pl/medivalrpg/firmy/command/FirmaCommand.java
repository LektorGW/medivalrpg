// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.bridge.ShopBridge;
import pl.medivalrpg.firmy.core.AltWatch;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Names;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.core.People;
import pl.medivalrpg.firmy.core.Warehouse;
import pl.medivalrpg.firmy.gui.FirmGui;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.model.Role;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/** {@code /firma} (alias {@code /kompania}): every player and admin action of companies, with Polish argument names. */
public final class FirmaCommand {

    public static final String PERM_USE = "firmy.use";
    public static final String PERM_ADMIN = "firmy.admin";

    private final FirmService service;
    private final ShopBridge shops;
    private final FirmGui gui;
    private final KrajeApi kraje;
    private final SkarbiecApi skarbiec;
    private final AltWatch alts;
    private final Runnable reload;
    /** Company chosen with /firma wybierz when a player belongs to several. */
    private final Map<UUID, String> chosen = new HashMap<>();
    private final List<Consumer<LiteralArgumentBuilder<CommandSourceStack>>> extensions = new ArrayList<>();
    private final List<String[]> helpLines = new ArrayList<>();

    public FirmaCommand(final FirmService service, final ShopBridge shops, final FirmGui gui, final KrajeApi kraje,
                        final SkarbiecApi skarbiec, final AltWatch alts, final Runnable reload) {
        this.service = service;
        this.shops = shops;
        this.gui = gui;
        this.kraje = kraje;
        this.skarbiec = skarbiec;
        this.alts = alts;
        this.reload = reload;
    }

    /** Later stages add their subcommands (kontrakt, kredyt, robotnik...) before {@link #build}. */
    public void extend(final Consumer<LiteralArgumentBuilder<CommandSourceStack>> e, final String[]... help) {
        this.extensions.add(e);
        for (final String[] h : help) {
            this.helpLines.add(h);
        }
    }

    // ------------------------------------------------------------------ helpers (public for the extensions)

    public static void send(final CommandSender s, final String miniMessage) {
        s.sendMessage(BukkitNotifier.parse(miniMessage));
    }

    public static void result(final CommandSender s, final Outcome o) {
        send(s, "<gold>[Firmy]</gold> " + (o.ok() ? "<green>" : "<red>") + escape(o.message()));
    }

    /** Escapes MiniMessage tags in plain texts coming from the rules. */
    public static String escape(final String s) {
        return Names.plain(s);
    }

    public static int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            send(ctx.getSource().getSender(), "<red>Tylko dla graczy.");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The company the player acts for: chosen, else the one they lead, else their first. */
    public Optional<Firm> current(final Player p) {
        final String c = this.chosen.get(p.getUniqueId());
        if (c != null) {
            final Firm f = this.service.ctx.data.firms.get(c);
            if (f != null && f.active() && f.members.containsKey(p.getUniqueId().toString())) {
                return Optional.of(f);
            }
        }
        return this.service.primary(p.getUniqueId());
    }

    public int withFirm(final CommandContext<CommandSourceStack> ctx, final BiConsumer<Player, Firm> action) {
        return player(ctx, p -> {
            final Optional<Firm> f = this.current(p);
            if (f.isEmpty()) {
                send(p, "<gold>[Firmy]</gold> <red>Nie należysz do żadnej firmy. Załóż ją: <white>/firma zaloz <typ> <nazwa></white>.");
                return;
            }
            action.accept(p, f.get());
        });
    }

    public Optional<Long> amount(final CommandSender s, final String raw) {
        final Optional<Long> v = this.skarbiec.parse(raw.replace(" ", ""));
        if (v.isEmpty() || v.get() <= 0) {
            send(s, "<gold>[Firmy]</gold> <red>Nieprawidłowa kwota: <white>" + escape(raw) + "</white> (np. 150, 3zl, 1zl5sr).");
            return Optional.empty();
        }
        return v;
    }

    /** UUID of a player by name: online, member of the given company, or cached offline. */
    public Optional<UUID> playerId(final String name, final Firm f) {
        final Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        if (f != null) {
            for (final Firm.Member m : f.members.values()) {
                if (m.name.equalsIgnoreCase(name)) {
                    return Optional.of(m.id());
                }
            }
        }
        final OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        return off == null ? Optional.empty() : Optional.of(off.getUniqueId());
    }

    private UUID countryHere(final Player p) {
        return this.kraje.getCountryAt(p.getLocation()).map(KrajInfo::id).orElse(null);
    }

    public SuggestionProvider<CommandSourceStack> firmNames() {
        return (ctx, b) -> {
            final String rem = b.getRemaining().toLowerCase(Locale.ROOT);
            for (final Firm f : this.service.ctx.data.firms.values()) {
                if (f.active() && f.name.toLowerCase(Locale.ROOT).startsWith(rem)) {
                    b.suggest(f.name);
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> members() {
        return (ctx, b) -> {
            if (ctx.getSource().getSender() instanceof Player p) {
                this.current(p).ifPresent(f -> f.members.values().forEach(m -> {
                    if (m.name.toLowerCase(Locale.ROOT).startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
                        b.suggest(m.name);
                    }
                }));
            }
            return b.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> online() {
        return (ctx, b) -> {
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
                    b.suggest(p.getName());
                }
            }
            return b.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> words(final String... words) {
        return (ctx, b) -> {
            for (final String w : words) {
                if (w.startsWith(b.getRemaining().toLowerCase(Locale.ROOT))) {
                    b.suggest(w);
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> applications() {
        return (ctx, b) -> {
            for (final FirmsData.Application a : this.service.ctx.data.applications.values()) {
                b.suggest(a.id, new com.mojang.brigadier.LiteralMessage(a.name + " (" + a.countryName + ")"));
            }
            return b.buildFuture();
        };
    }

    private static Block looked(final Player p) {
        return p.getTargetBlockExact(6);
    }

    // ------------------------------------------------------------------ tree

    public LiteralCommandNode<CommandSourceStack> build() {
        final LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("firma")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> player(ctx, p -> {
                final Optional<Firm> f = this.current(p);
                if (f.isPresent()) {
                    this.gui.open(p, f.get(), FirmGui.Tab.KONTO);
                } else {
                    this.gui.openStart(p, this.countryHere(p));
                }
            }))
            .then(Commands.literal("pomoc").executes(ctx -> {
                this.help(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("start").executes(ctx -> player(ctx, p -> this.gui.openStart(p, this.countryHere(p)))))
            .then(Commands.literal("zlecenia").executes(ctx -> player(ctx, p -> {
                if (!this.gui.hasBoard()) {
                    send(p, "<gold>[Firmy]</gold> <red>Tablica zleceń jest niedostępna (brak etapu kontraktów).");
                    return;
                }
                final Optional<Firm> f = this.current(p);
                if (f.isPresent()) {
                    this.gui.open(p, f.get(), FirmGui.Tab.ZLECENIA);
                } else {
                    this.gui.openBoard(p);
                }
            })))
            .then(this.gatheringNode())
            .then(Commands.literal("zaloz")
                .executes(ctx -> player(ctx, this::foundingGuide))
                .then(Commands.argument("typ", StringArgumentType.word()).suggests(words("cech", "kompania", "manufaktura"))
                    .executes(ctx -> player(ctx, p -> {
                        final Optional<FirmType> t = FirmType.parse(StringArgumentType.getString(ctx, "typ"));
                        if (t.isEmpty() || !t.get().foundable()) {
                            send(p, "<gold>[Firmy]</gold> <red>Typ: cech, kompania albo manufaktura.");
                            return;
                        }
                        send(p, "<gold>[Firmy]</gold> <yellow>Podaj jeszcze nazwę firmy (3–24 znaki): "
                            + "<click:suggest_command:'/firma zaloz " + t.get().key() + " '><white>/firma zaloz " + t.get().key()
                            + " <nazwa></white></click>");
                    }))
                    .then(Commands.argument("nazwa", StringArgumentType.greedyString())
                        .executes(ctx -> player(ctx, p -> {
                            final Optional<FirmType> t = FirmType.parse(StringArgumentType.getString(ctx, "typ"));
                            if (t.isEmpty()) {
                                send(p, "<gold>[Firmy]</gold> <red>Typ: cech, kompania albo manufaktura.");
                                return;
                            }
                            result(p, this.service.registry.found(p.getUniqueId(), p.getName(), StringArgumentType.getString(ctx, "nazwa"),
                                t.get(), this.countryHere(p), this.service.ctx.settings.minCapital, false));
                        })))))
            .then(Commands.literal("wniosek")
                .executes(ctx -> player(ctx, this::myApplication))
                .then(Commands.literal("wycofaj").executes(ctx -> player(ctx, p -> result(p, this.service.registry.withdraw(p.getUniqueId()))))))
            .then(Commands.literal("info")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> this.card(p, f)))
                .then(Commands.argument("firma", StringArgumentType.greedyString()).suggests(this.firmNames())
                    .executes(ctx -> {
                        final Optional<Firm> f = this.service.ctx.firm(StringArgumentType.getString(ctx, "firma"));
                        if (f.isEmpty()) {
                            send(ctx.getSource().getSender(), "<gold>[Firmy]</gold> <red>Nie ma takiej firmy.");
                        } else {
                            this.card(ctx.getSource().getSender(), f.get());
                        }
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("lista").executes(ctx -> {
                this.ranking(ctx.getSource().getSender(), "obrot", true);
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("ranking")
                .executes(ctx -> {
                    this.ranking(ctx.getSource().getSender(), "obrot", false);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("wedlug", StringArgumentType.word()).suggests(words("obrot", "majatek", "ludzie", "kontrakty"))
                    .executes(ctx -> {
                        this.ranking(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "wedlug"), false);
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("wybierz")
                .then(Commands.argument("firma", StringArgumentType.greedyString()).suggests(this.firmNames())
                    .executes(ctx -> player(ctx, p -> {
                        final Optional<Firm> f = this.service.ctx.firm(StringArgumentType.getString(ctx, "firma"));
                        if (f.isEmpty() || !f.get().members.containsKey(p.getUniqueId().toString())) {
                            send(p, "<gold>[Firmy]</gold> <red>Nie należysz do takiej firmy.");
                            return;
                        }
                        this.chosen.put(p.getUniqueId(), f.get().id);
                        send(p, "<gold>[Firmy]</gold> <green>Komendy /firma działają teraz dla „" + escape(f.get().name) + "”.");
                    }))))
            .then(Commands.literal("wplac")
                .then(Commands.argument("kwota", StringArgumentType.greedyString())
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.amount(p, StringArgumentType.getString(ctx, "kwota"))
                        .ifPresent(a -> result(p, this.service.treasury.contribute(f, p.getUniqueId(), a)))))))
            .then(Commands.literal("dywidenda")
                .then(Commands.literal("anuluj").executes(ctx -> this.withFirm(ctx, (p, f) ->
                    result(p, this.service.treasury.cancelPayout(f, p.getUniqueId(), false, null)))))
                .then(Commands.argument("kwota", StringArgumentType.greedyString())
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.amount(p, StringArgumentType.getString(ctx, "kwota"))
                        .ifPresent(a -> result(p, this.service.treasury.dividend(f, p.getUniqueId(), a)))))))
            .then(Commands.literal("pensja")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this.members())
                    .then(Commands.argument("kwota", StringArgumentType.greedyString())
                        .executes(ctx -> this.withFirm(ctx, (p, f) -> {
                            final String raw = StringArgumentType.getString(ctx, "kwota");
                            final Optional<Long> a = raw.strip().equals("0") ? Optional.of(0L) : this.amount(p, raw);
                            final Optional<UUID> t = this.playerId(StringArgumentType.getString(ctx, "gracz"), f);
                            if (a.isPresent() && t.isPresent()) {
                                result(p, this.service.people.setSalary(f, p.getUniqueId(), t.get(), a.get()));
                            } else if (t.isEmpty()) {
                                send(p, "<gold>[Firmy]</gold> <red>Nie znam takiego gracza.");
                            }
                        })))))
            .then(Commands.literal("zapros")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(online())
                    .then(Commands.argument("rola", StringArgumentType.word()).suggests(words("wspolnik", "czeladnik"))
                        .executes(ctx -> this.withFirm(ctx, (p, f) -> {
                            final String name = StringArgumentType.getString(ctx, "gracz");
                            final Optional<UUID> t = this.playerId(name, f);
                            final Optional<Role> r = Role.parse(StringArgumentType.getString(ctx, "rola"));
                            if (t.isEmpty()) {
                                send(p, "<gold>[Firmy]</gold> <red>Nie znam gracza " + escape(name) + ".");
                            } else {
                                result(p, this.service.people.invite(f, p.getUniqueId(), t.get(), name, r.orElse(null)));
                            }
                        })))))
            .then(Commands.literal("przyjmij")
                .executes(ctx -> player(ctx, p -> result(p, this.service.people.acceptInvite(p.getUniqueId(), p.getName(), null))))
                .then(Commands.argument("firma", StringArgumentType.greedyString())
                    .executes(ctx -> player(ctx, p -> result(p, this.service.people.acceptInvite(p.getUniqueId(), p.getName(),
                        StringArgumentType.getString(ctx, "firma")))))))
            .then(Commands.literal("odrzuc")
                .executes(ctx -> player(ctx, p -> result(p, this.service.people.refuseInvite(p.getUniqueId()))))
                .then(Commands.argument("firma", StringArgumentType.greedyString())
                    .executes(ctx -> player(ctx, p -> result(p, this.service.people.refuseInvite(p.getUniqueId()))))))
            .then(Commands.literal("wyrzuc")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this.members())
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.playerId(StringArgumentType.getString(ctx, "gracz"), f)
                        .ifPresentOrElse(t -> result(p, this.service.people.kick(f, p.getUniqueId(), t)),
                            () -> send(p, "<gold>[Firmy]</gold> <red>Nie znam takiego gracza."))))))
            .then(Commands.literal("opusc")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> send(p, "<gold>[Firmy]</gold> <yellow>Na pewno odejść z „" + escape(f.name)
                    + "”? Twoje udziały przejdą na mistrza. <click:run_command:'/firma opusc potwierdz'><red>[Odchodzę]</red></click>")))
                .then(Commands.literal("potwierdz").executes(ctx -> this.withFirm(ctx, (p, f) -> result(p, this.service.people.leave(f, p.getUniqueId()))))))
            .then(Commands.literal("rola")
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this.members())
                    .then(Commands.argument("rola", StringArgumentType.word()).suggests(words("wspolnik", "czeladnik"))
                        .executes(ctx -> this.withFirm(ctx, (p, f) -> {
                            final Optional<UUID> t = this.playerId(StringArgumentType.getString(ctx, "gracz"), f);
                            final Optional<Role> r = Role.parse(StringArgumentType.getString(ctx, "rola"));
                            if (t.isEmpty() || r.isEmpty()) {
                                send(p, "<gold>[Firmy]</gold> <red>Podaj członka firmy i rolę: wspolnik albo czeladnik.");
                            } else {
                                result(p, this.service.people.setRole(f, p.getUniqueId(), t.get(), r.get()));
                            }
                        })))))
            .then(Commands.literal("udzialy")
                .executes(ctx -> this.withFirm(ctx, this::shares))
                .then(Commands.literal("przyjmij").executes(ctx -> player(ctx, p -> result(p, this.service.people.acceptShares(p.getUniqueId())))))
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this.members())
                    .then(Commands.argument("procent", DoubleArgumentType.doubleArg(0.01, 100))
                        .executes(ctx -> this.withFirm(ctx, (p, f) -> this.playerId(StringArgumentType.getString(ctx, "gracz"), f)
                            .ifPresentOrElse(t -> result(p, this.service.people.offerShares(f, p.getUniqueId(), t,
                                    (int) Math.round(DoubleArgumentType.getDouble(ctx, "procent") * 100))),
                                () -> send(p, "<gold>[Firmy]</gold> <red>Nie znam takiego gracza.")))))))
            .then(Commands.literal("mistrz")
                .then(Commands.literal("przyjmij").executes(ctx -> player(ctx, p -> result(p, this.service.people.acceptMaster(p.getUniqueId())))))
                .then(Commands.argument("gracz", StringArgumentType.word()).suggests(this.members())
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.playerId(StringArgumentType.getString(ctx, "gracz"), f)
                        .ifPresentOrElse(t -> result(p, this.service.people.offerMaster(f, p.getUniqueId(), t)),
                            () -> send(p, "<gold>[Firmy]</gold> <red>Nie znam takiego gracza."))))))
            .then(Commands.literal("sklep")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> {
                    final Block b = looked(p);
                    if (b == null) {
                        send(p, "<gold>[Firmy]</gold> <red>Spójrz na tabliczkę [Sklep] albo skrzynię sklepu.");
                        return;
                    }
                    result(p, this.shops.attach(f, p.getUniqueId(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
                }))
                .then(Commands.literal("usun").executes(ctx -> this.withFirm(ctx, (p, f) -> {
                    final Block b = looked(p);
                    if (b == null) {
                        send(p, "<gold>[Firmy]</gold> <red>Spójrz na sklep firmy.");
                        return;
                    }
                    result(p, this.shops.detach(f, p.getUniqueId(), false, b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
                }))))
            .then(Commands.literal("sklepy").executes(ctx -> this.withFirm(ctx, (p, f) -> this.gui.open(p, f, FirmGui.Tab.SKLEPY))))
            .then(Commands.literal("magazyn")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> {
                    final Block b = looked(p);
                    if (b == null || !(b.getState(false) instanceof org.bukkit.block.Container)) {
                        send(p, "<gold>[Firmy]</gold> <red>Spójrz na skrzynię albo beczkę, która ma być magazynem.");
                        return;
                    }
                    final UUID country = this.kraje.getCountryAt(b.getLocation()).map(KrajInfo::id).orElse(null);
                    final boolean isShop = this.shops.shopAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()) != null;
                    result(p, this.service.warehouse.add(f, p.getUniqueId(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), country, isShop));
                }))
                .then(Commands.literal("usun").executes(ctx -> this.withFirm(ctx, (p, f) -> {
                    final Block b = looked(p);
                    if (b == null) {
                        send(p, "<gold>[Firmy]</gold> <red>Spójrz na skrzynię magazynu.");
                        return;
                    }
                    result(p, this.service.warehouse.remove(f, p.getUniqueId(), false, Warehouse.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())));
                })))
                .then(Commands.literal("dziennik").executes(ctx -> this.withFirm(ctx, (p, f) -> this.gui.open(p, f, FirmGui.Tab.MAGAZYN)))))
            .then(Commands.literal("ksiega")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> this.ledger(p, f, 1)))
                .then(Commands.argument("strona", IntegerArgumentType.integer(1, 999))
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.ledger(p, f, IntegerArgumentType.getInteger(ctx, "strona"))))))
            .then(Commands.literal("herb").executes(ctx -> this.withFirm(ctx, this::banner)))
            .then(Commands.literal("kolor")
                .then(Commands.argument("kolor", StringArgumentType.word())
                    .executes(ctx -> this.withFirm(ctx, (p, f) -> this.color(p, f, StringArgumentType.getString(ctx, "kolor"))))))
            .then(Commands.literal("siedziba").executes(ctx -> this.withFirm(ctx, (p, f) ->
                result(p, this.service.treasury.moveSeat(f, p.getUniqueId(), this.countryHere(p))))))
            .then(Commands.literal("awans").executes(ctx -> this.withFirm(ctx, (p, f) -> result(p, this.service.treasury.promote(f, p.getUniqueId())))))
            .then(Commands.literal("likwiduj")
                .executes(ctx -> this.withFirm(ctx, (p, f) -> send(p, "<gold>[Firmy]</gold> <yellow>Likwidacja „" + escape(f.name)
                    + "”: majątek po pensjach trafi do udziałowców. Potrzeba głosów ponad 50 % udziałów. "
                    + "<click:run_command:'/firma likwiduj " + f.id + "'><red>[Głosuję za likwidacją]</red></click>")))
                .then(Commands.argument("firma", StringArgumentType.word())
                    .executes(ctx -> player(ctx, p -> this.service.ctx.firm(StringArgumentType.getString(ctx, "firma"))
                        .ifPresentOrElse(f -> result(p, this.service.treasury.voteLiquidation(f, p.getUniqueId())),
                            () -> send(p, "<gold>[Firmy]</gold> <red>Nie ma takiej firmy."))))))
            .then(this.countryNode())
            .then(this.adminNode());
        for (final Consumer<LiteralArgumentBuilder<CommandSourceStack>> e : this.extensions) {
            e.accept(root);
        }
        return root.build();
    }

    // ------------------------------------------------------------------ /firma kraj

    private LiteralArgumentBuilder<CommandSourceStack> countryNode() {
        return Commands.literal("kraj")
            .executes(ctx -> player(ctx, this::countryOverview))
            .then(Commands.literal("zatwierdz").then(Commands.argument("wniosek", StringArgumentType.word()).suggests(this.applications())
                .executes(ctx -> player(ctx, p -> result(p, this.service.registry.decide(StringArgumentType.getString(ctx, "wniosek"),
                    p.getUniqueId(), false, true))))))
            .then(Commands.literal("odrzuc").then(Commands.argument("wniosek", StringArgumentType.word()).suggests(this.applications())
                .executes(ctx -> player(ctx, p -> result(p, this.service.registry.decide(StringArgumentType.getString(ctx, "wniosek"),
                    p.getUniqueId(), false, false))))))
            .then(Commands.literal("podatek").then(Commands.argument("procent", DoubleArgumentType.doubleArg(0, 100))
                .executes(ctx -> player(ctx, p -> this.kraje.getCountryOf(p.getUniqueId()).ifPresentOrElse(
                    k -> result(p, this.service.registry.setTax(k.id(), p.getUniqueId(), DoubleArgumentType.getDouble(ctx, "procent"), false)),
                    () -> send(p, "<gold>[Firmy]</gold> <red>Nie należysz do kraju."))))))
            .then(Commands.literal("zawies").then(Commands.argument("firma", StringArgumentType.greedyString()).suggests(this.firmNames())
                .executes(ctx -> player(ctx, p -> this.service.ctx.firm(StringArgumentType.getString(ctx, "firma")).ifPresentOrElse(
                    f -> result(p, this.service.treasury.warSuspend(f, p.getUniqueId(), false, true)),
                    () -> send(p, "<gold>[Firmy]</gold> <red>Nie ma takiej firmy."))))))
            .then(Commands.literal("odwies").then(Commands.argument("firma", StringArgumentType.greedyString()).suggests(this.firmNames())
                .executes(ctx -> player(ctx, p -> this.service.ctx.firm(StringArgumentType.getString(ctx, "firma")).ifPresentOrElse(
                    f -> result(p, this.service.treasury.warSuspend(f, p.getUniqueId(), false, false)),
                    () -> send(p, "<gold>[Firmy]</gold> <red>Nie ma takiej firmy."))))))
            .then(Commands.literal("przyjmij").then(Commands.argument("firma", StringArgumentType.word())
                .executes(ctx -> player(ctx, p -> result(p, this.service.treasury.acceptSeat(StringArgumentType.getString(ctx, "firma"),
                    p.getUniqueId(), false))))));
    }

    private void countryOverview(final Player p) {
        final Optional<KrajInfo> k = this.kraje.getCountryOf(p.getUniqueId());
        if (k.isEmpty()) {
            send(p, "<gold>[Firmy]</gold> <red>Nie należysz do kraju.");
            return;
        }
        final UUID id = k.get().id();
        send(p, "<gold>[Firmy]</gold> <yellow>Firmy z siedzibą w " + escape(k.get().name()) + "</yellow> <gray>(podatek od przychodu: "
            + this.service.registry.taxBp(id) / 100.0 + " %)");
        for (final Firm f : this.service.ctx.data.firms.values()) {
            if (f.active() && id.toString().equals(f.seatCountry)) {
                send(p, " <white>" + escape(f.name) + "</white> <gray>" + f.type.displayName() + ", ludzi: " + f.members.size()
                    + ", przychód 30 dni: " + this.service.ctx.fmt(f.income(this.service.ctx.windowStart(30))) + (f.warSuspended ? " <red>(zawieszona)" : ""));
            }
        }
        final List<FirmsData.Application> apps = this.service.registry.applicationsFor(id).stream().filter(a -> !a.gathering()).toList();
        if (!apps.isEmpty()) {
            send(p, "<yellow>Wnioski:");
            for (final FirmsData.Application a : apps) {
                send(p, " <white>" + escape(a.name) + "</white> <gray>(" + a.type.displayName() + ", " + escape(a.founderName) + ") "
                    + "<click:run_command:'/firma kraj zatwierdz " + a.id + "'><green>[Zatwierdź]</green></click> "
                    + "<click:run_command:'/firma kraj odrzuc " + a.id + "'><red>[Odrzuć]</red></click>");
            }
        }
        send(p, "<dark_gray>Władca: /firma kraj podatek <0–" + this.service.ctx.settings.maxTaxBp / 100 + ">, /firma kraj zawies <firma> (w wojnie)");
    }

    // ------------------------------------------------------------------ /firma admin

    private LiteralArgumentBuilder<CommandSourceStack> adminNode() {
        return Commands.literal("admin")
            .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
            .then(Commands.literal("info").then(Commands.argument("firma", StringArgumentType.greedyString()).suggests(this.firmNames())
                .executes(ctx -> this.adminFirm(ctx, (s, f) -> {
                    this.card(s, f);
                    send(s, "<gray>Saldo: <gold>" + this.service.ctx.fmt(this.service.treasury.balance(f)) + "</gold>, zaległości: "
                        + this.service.ctx.fmt(f.totalArrears()) + ", zamrożona: " + f.frozen + " " + escape(f.frozenReason));
                    for (final var i : this.service.ctx.intents) {
                        if (f.id.equals(i.firm)) {
                            send(s, "<red>Niepotwierdzona operacja: " + escape(i.kind + " " + i.description + " " + i.legs));
                        }
                    }
                }))))
            .then(Commands.literal("zamroz").then(Commands.argument("firma", StringArgumentType.word()).suggests(this.firmNames())
                .then(Commands.argument("powod", StringArgumentType.greedyString())
                    .executes(ctx -> this.adminFirm(ctx, (s, f) -> result(s, this.service.treasury.freeze(f, true,
                        StringArgumentType.getString(ctx, "powod"))))))))
            .then(Commands.literal("odmroz").then(Commands.argument("firma", StringArgumentType.word()).suggests(this.firmNames())
                .executes(ctx -> this.adminFirm(ctx, (s, f) -> result(s, this.service.treasury.freeze(f, false, "decyzja administratora"))))))
            .then(Commands.literal("likwiduj").then(Commands.argument("firma", StringArgumentType.word()).suggests(this.firmNames())
                .then(Commands.literal("potwierdz")
                    .executes(ctx -> this.adminFirm(ctx, (s, f) -> result(s, this.service.treasury.liquidate(f, "decyzja administratora", s.getName())))))))
            .then(Commands.literal("anuluj").then(Commands.argument("firma", StringArgumentType.word()).suggests(this.firmNames())
                .executes(ctx -> this.adminFirm(ctx, (s, f) -> result(s, this.service.treasury.cancelPayout(f, null, true, null))))))
            .then(Commands.literal("zatwierdz").then(Commands.argument("wniosek", StringArgumentType.word()).suggests(this.applications())
                .executes(ctx -> {
                    result(ctx.getSource().getSender(), this.service.registry.decide(StringArgumentType.getString(ctx, "wniosek"), null, true, true));
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("odrzuc").then(Commands.argument("wniosek", StringArgumentType.word()).suggests(this.applications())
                .executes(ctx -> {
                    result(ctx.getSource().getSender(), this.service.registry.decide(StringArgumentType.getString(ctx, "wniosek"), null, true, false));
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("zrzutki").executes(ctx -> {
                final CommandSender s = ctx.getSource().getSender();
                final List<FirmsData.Application> list = this.service.registry.coFounded();
                send(s, "<gold>[Firmy]</gold> Zrzutki i wnioski z wieloma wkładami: " + list.size()
                    + " <dark_gray>(przed cofnięciem wersji Firm zamknij je: odrzucenie zwraca każdemu jego wkład)");
                for (final FirmsData.Application a : list) {
                    final List<String> parts = new ArrayList<>();
                    a.contributions.forEach((u, v) -> parts.add(a.contributorNames.getOrDefault(u, u.substring(0, 8)) + " " + this.service.ctx.fmt(v)));
                    send(s, " <yellow>" + a.id + "</yellow> <white>" + escape(a.name) + "</white> <gray>" + a.state
                        + (a.pending != null ? " (wstrzymana: " + escape(a.pending) + ")" : "") + ", depozyt "
                        + this.service.ctx.fmt(this.service.bankBalance(a.account())) + ": " + escape(String.join(", ", parts)) + "</gray> "
                        + "<click:suggest_command:'/firma admin odrzuc " + a.id + "'><red>[Zwróć wkłady]</red></click>");
                }
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("alty").executes(ctx -> {
                final CommandSender s = ctx.getSource().getSender();
                int n = 0;
                for (final Firm f : this.service.ctx.data.firms.values()) {
                    if (!f.active()) {
                        continue;
                    }
                    for (final List<String> g : this.alts.sharedAddresses(f)) {
                        send(s, "<yellow>" + escape(f.name) + "</yellow>: wspólny adres — " + escape(String.join(", ", g)));
                        n++;
                    }
                }
                send(s, "<gold>[Firmy]</gold> Ostrzeżeń o wspólnym adresie: " + n + " (bez automatycznych kar).");
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("sklep").then(Commands.literal("usun").executes(ctx -> player(ctx, p -> {
                final Block b = looked(p);
                if (b == null) {
                    return;
                }
                final Map<String, Object> shop = this.shops.shopAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
                final Optional<Firm> f = shop == null || !(shop.get("account") instanceof String a) ? Optional.empty()
                    : this.service.ctx.data.firms.values().stream().filter(x -> x.account().equals(a)).findFirst();
                if (f.isEmpty()) {
                    send(p, "<gold>[Firmy]</gold> <red>To nie jest sklep firmy.");
                    return;
                }
                result(p, this.shops.detach(f.get(), p.getUniqueId(), true, b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
            }))))
            .then(Commands.literal("przeladuj").executes(ctx -> {
                this.reload.run();
                send(ctx.getSource().getSender(), "<gold>[Firmy]</gold> <green>Wczytano config.yml.");
                return Command.SINGLE_SUCCESS;
            }));
    }

    // ------------------------------------------------------------------ founding guide and co-founding (zrzutka)

    /** {@code /firma zaloz} without arguments: the checklist here and the founding commands (was a dead end before). */
    private void foundingGuide(final Player p) {
        final UUID here = this.countryHere(p);
        send(p, "<gold>━━ Załóż firmę ━━");
        for (final var c : this.service.registry.readiness(p.getUniqueId(), here)) {
            send(p, (c.ok() ? " <green>✔ " : " <red>✘ ") + "<gray>" + escape(c.text()));
        }
        final var s = this.service.ctx.settings;
        send(p, "<gray>Kliknij typ i dopisz nazwę: "
            + "<click:suggest_command:'/firma zaloz cech '><yellow>[Cech]</yellow></click> "
            + "<click:suggest_command:'/firma zaloz kompania '><yellow>[Kompania]</yellow></click> "
            + "<click:suggest_command:'/firma zaloz manufaktura '><yellow>[Manufaktura]</yellow></click>");
        if (s.gatheringEnabled) {
            send(p, "<gray>Za mało pieniędzy? <click:suggest_command:'/firma zrzutka zaloz cech 5zl '><gold>[Zrzutka]</gold></click> "
                + "— zacznij od " + this.service.ctx.fmt(s.gatheringMinContribution) + ", inni dokładają i dostają udziały.");
        }
        send(p, "<dark_gray>Okno z tym wszystkim: /firma start");
    }

    private SuggestionProvider<CommandSourceStack> gatheringIds() {
        return (ctx, b) -> {
            for (final FirmsData.Application a : this.service.registry.gatherings()) {
                b.suggest(a.id, new com.mojang.brigadier.LiteralMessage(a.name + " (" + a.countryName + ")"));
            }
            return b.buildFuture();
        };
    }

    private LiteralArgumentBuilder<CommandSourceStack> gatheringNode() {
        return Commands.literal("zrzutka")
            .executes(ctx -> player(ctx, this::gatheringList))
            .then(Commands.literal("zaloz")
                .then(Commands.argument("typ", StringArgumentType.word()).suggests(words("cech", "kompania", "manufaktura"))
                    .then(Commands.argument("wklad", StringArgumentType.word())
                        .then(Commands.argument("nazwa", StringArgumentType.greedyString())
                            .executes(ctx -> player(ctx, p -> {
                                final Optional<FirmType> t = FirmType.parse(StringArgumentType.getString(ctx, "typ"));
                                if (t.isEmpty()) {
                                    send(p, "<gold>[Firmy]</gold> <red>Typ: cech, kompania albo manufaktura.");
                                    return;
                                }
                                this.amount(p, StringArgumentType.getString(ctx, "wklad")).ifPresent(a -> result(p,
                                    this.service.registry.gather(p.getUniqueId(), p.getName(), StringArgumentType.getString(ctx, "nazwa"),
                                        t.get(), this.countryHere(p), a)));
                            }))))))
            .then(Commands.literal("dolacz")
                .then(Commands.argument("zrzutka", StringArgumentType.word()).suggests(this.gatheringIds())
                    .then(Commands.argument("kwota", StringArgumentType.greedyString())
                        .executes(ctx -> player(ctx, p -> this.amount(p, StringArgumentType.getString(ctx, "kwota")).ifPresent(a ->
                            result(p, this.service.registry.contribute(StringArgumentType.getString(ctx, "zrzutka"), p.getUniqueId(),
                                p.getName(), a))))))))
            .then(Commands.literal("wycofaj").executes(ctx -> player(ctx, p -> result(p, this.service.registry.leaveGathering(p.getUniqueId())))));
    }

    private void gatheringList(final Player p) {
        final List<FirmsData.Application> list = this.service.registry.gatherings();
        final long target = this.service.registry.gatheringTarget();
        send(p, "<gold>━━ Zrzutki na firmy (" + list.size() + ") ━━");
        for (final FirmsData.Application a : list) {
            send(p, " <yellow>" + escape(a.name) + "</yellow> <gray>(" + a.type.displayName() + ", " + escape(a.countryName) + ") — "
                + this.service.ctx.fmt(a.collected()) + " z " + this.service.ctx.fmt(target) + ", wspólnicy: "
                + escape(String.join(", ", a.contributorNames.values())) + "</gray> "
                + "<click:suggest_command:'/firma zrzutka dolacz " + a.id + " '><green>[Dołóż]</green></click>");
        }
        if (list.isEmpty()) {
            send(p, "<gray>Nikt teraz nie zbiera na firmę.");
        }
        send(p, "<dark_gray>Nowa: /firma zrzutka zaloz <typ> <wkład> <nazwa> (od "
            + this.service.ctx.fmt(this.service.ctx.settings.gatheringMinContribution) + "), wycofanie wkładu: /firma zrzutka wycofaj");
    }

    private int adminFirm(final CommandContext<CommandSourceStack> ctx, final BiConsumer<CommandSender, Firm> action) {
        final Optional<Firm> f = this.service.ctx.firm(StringArgumentType.getString(ctx, "firma"));
        if (f.isEmpty()) {
            send(ctx.getSource().getSender(), "<gold>[Firmy]</gold> <red>Nie ma takiej firmy.");
        } else {
            action.accept(ctx.getSource().getSender(), f.get());
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ views

    private void myApplication(final Player p) {
        for (final FirmsData.Application a : this.service.ctx.data.applications.values()) {
            if (a.founder.equals(p.getUniqueId().toString()) && a.gathering()) {
                send(p, "<gold>[Firmy]</gold> Zrzutka na „" + escape(a.name) + "” (" + a.type.displayName() + ", " + escape(a.countryName)
                    + "): zebrano " + this.service.ctx.fmt(a.collected()) + " z " + this.service.ctx.fmt(this.service.registry.gatheringTarget())
                    + ", do " + new SimpleDateFormat("dd.MM HH:mm").format(new Date(a.expiresAt)) + ". Inni dołączają: /firma zrzutka dolacz "
                    + a.id + " <kwota>. <click:run_command:'/firma wniosek wycofaj'><red>[Odwołaj zrzutkę]</red></click>");
                return;
            }
            if (a.founder.equals(p.getUniqueId().toString())) {
                send(p, "<gold>[Firmy]</gold> Wniosek o „" + escape(a.name) + "” (" + a.type.displayName() + ") w kraju " + escape(a.countryName)
                    + " czeka na decyzję do " + new SimpleDateFormat("dd.MM HH:mm").format(new Date(a.expiresAt)) + ". W depozycie: "
                    + this.service.ctx.fmt(a.fee + a.capital) + ". <click:run_command:'/firma wniosek wycofaj'><red>[Wycofaj]</red></click>");
                return;
            }
        }
        send(p, "<gold>[Firmy]</gold> <gray>Nie masz oczekującego wniosku.");
    }

    /** Public card: no balance (only the turnover), people count, type, seat. */
    public void card(final CommandSender s, final Firm f) {
        final String month = this.service.ctx.windowStart(30);
        send(s, "<gold>━━ <color:" + f.color + "><bold>" + escape(f.name) + "</bold></color> <gold>━━");
        send(s, "<gray>" + f.type.displayName() + " · siedziba: <white>" + escape(f.seatCountryName == null ? "—" : f.seatCountryName)
            + "</white> · ludzi: <white>" + f.members.size() + "</white>" + (f.active() ? "" : " · <red>zlikwidowana"));
        send(s, "<gray>Mistrz: <white>" + escape(f.master().map(m -> m.name).orElse("—")) + "</white> · obrót 30 dni: <green>"
            + this.service.ctx.fmt(f.income(month)) + "</green> · kontraktów: " + f.contractsDone);
        if (s instanceof Player p && f.members.containsKey(p.getUniqueId().toString())) {
            send(s, "<gray>Saldo: <gold>" + this.service.ctx.fmt(this.service.treasury.balance(f)) + "</gold> <click:run_command:'/firma'><yellow>[Otwórz firmę]</yellow></click>");
        }
    }

    private void ranking(final CommandSender s, final String sort, final boolean list) {
        final List<Firm> all = this.service.ranking(sort);
        final String month = this.service.ctx.windowStart(30);
        send(s, "<gold>━━ " + (list ? "Firmy" : "Ranking firm") + " (" + escape(sort) + ") ━━");
        int i = 1;
        for (final Firm f : all) {
            if (i > 15) {
                break;
            }
            final String val = switch (sort) {
                case "majatek" -> "majątek " + this.service.ctx.fmt(this.service.treasury.balance(f));
                case "ludzie" -> "ludzi " + f.members.size();
                case "kontrakty" -> "kontraktów " + f.contractsDone;
                default -> "obrót " + this.service.ctx.fmt(f.income(month));
            };
            send(s, "<yellow>" + i++ + ".</yellow> <color:" + f.color + ">" + escape(f.name) + "</color> <gray>(" + f.type.displayName() + ", "
                + escape(f.seatCountryName == null ? "—" : f.seatCountryName) + ") — " + val);
        }
        if (all.isEmpty()) {
            send(s, "<gray>Nie ma jeszcze firm. Załóż pierwszą: /firma zaloz <typ> <nazwa>");
        }
        send(s, "<dark_gray>Królewski przewoźnik kolejowy jest osobną firmą Korony (poza rankingiem).");
    }

    private void shares(final Player p, final Firm f) {
        send(p, "<gold>[Firmy]</gold> Udziały w „" + escape(f.name) + "”:");
        for (final Firm.Member m : f.members.values()) {
            if (m.shareBp > 0) {
                send(p, " <white>" + escape(m.name) + "</white> <gray>" + People.pct(m.shareBp));
            }
        }
        send(p, "<dark_gray>Przekaż: /firma udzialy <gracz> <procent> (druga strona musi przyjąć; zapłatę rozliczacie przelewem)");
    }

    private void ledger(final Player p, final Firm f, final int page) {
        final List<Firm.Entry> e = new ArrayList<>(f.journal);
        java.util.Collections.reverse(e);
        final int per = 10;
        final int pages = Math.max(1, (e.size() + per - 1) / per);
        final int pg = Math.min(page, pages);
        final SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm");
        send(p, "<gold>━━ Księga „" + escape(f.name) + "” (" + pg + "/" + pages + ") ━━");
        for (int i = (pg - 1) * per; i < Math.min(e.size(), pg * per); i++) {
            final Firm.Entry x = e.get(i);
            final String amount = x.amount > 0 ? "<green>+" + this.service.ctx.fmt(x.amount) : x.amount < 0 ? "<red>−" + this.service.ctx.fmt(-x.amount) : "<gray>·";
            send(p, "<dark_gray>" + df.format(new Date(x.time)) + "</dark_gray> " + amount + " <white>" + escape(x.text)
                + (x.actor == null ? "" : " <dark_gray>(" + escape(x.actor) + ")"));
        }
        if (pg < pages) {
            send(p, "<click:run_command:'/firma ksiega " + (pg + 1) + "'><yellow>[Następna strona]</yellow></click>");
        }
    }

    private void banner(final Player p, final Firm f) {
        if (!f.has(p.getUniqueId(), Role.MISTRZ)) {
            send(p, "<gold>[Firmy]</gold> <red>Herb ustala mistrz.");
            return;
        }
        final ItemStack hand = p.getInventory().getItemInMainHand();
        if (!(hand.getItemMeta() instanceof BannerMeta meta) || !hand.getType().name().endsWith("_BANNER")) {
            send(p, "<gold>[Firmy]</gold> <red>Weź sztandar do ręki (zwykły sztandar z wzorami).");
            return;
        }
        f.banner = Base64.getEncoder().encodeToString(hand.asOne().serializeAsBytes());
        f.bannerBase = hand.getType().name().replace("_BANNER", "").toLowerCase(Locale.ROOT);
        f.bannerPatterns = new ArrayList<>();
        for (final Pattern pt : meta.getPatterns()) {
            final DyeColor c = pt.getColor();
            final org.bukkit.NamespacedKey key = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BANNER_PATTERN).getKey(pt.getPattern());
            f.bannerPatterns.add((key == null ? "?" : key.getKey()) + ":" + c.name().toLowerCase(Locale.ROOT));
        }
        this.service.ctx.journal(f, "herb", "Nowy herb firmy", 0, p.getName());
        this.service.ctx.save();
        send(p, "<gold>[Firmy]</gold> <green>Herb firmy zapisany (sztandar zostaje w twojej ręce).");
    }

    private void color(final Player p, final Firm f, final String raw) {
        if (!f.has(p.getUniqueId(), Role.MISTRZ)) {
            send(p, "<gold>[Firmy]</gold> <red>Barwę ustala mistrz.");
            return;
        }
        TextColor c = TextColor.fromHexString(raw.startsWith("#") ? raw : "#" + raw);
        if (c == null) {
            c = NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT));
        }
        if (c == null) {
            send(p, "<gold>[Firmy]</gold> <red>Kolor: #RRGGBB albo nazwa (red, gold, dark_green...).");
            return;
        }
        f.color = c.asHexString().toUpperCase(Locale.ROOT);
        this.service.ctx.save();
        send(p, "<gold>[Firmy]</gold> <green>Barwa firmy: <color:" + f.color + ">" + f.color + "</color>.");
    }

    public void help(final CommandSender s) {
        send(s, "<gold>━━ Firmy — pomoc ━━");
        final String[][] lines = {
            {"/firma", "okno twojej firmy; bez firmy: jak zacząć (lista warunków)"},
            {"/firma zaloz <cech|kompania|manufaktura> <nazwa>", "na ziemi kraju siedziby; 25 zł opłaty dla kraju + 25 zł kapitału"},
            {"/firma zrzutka zaloz <typ> <wkład> <nazwa>", "załóżcie firmę razem: udziały według wkładu"},
            {"/firma zrzutka dolacz <zrzutka> <kwota>", "dołóż się do cudzej zrzutki (zostaniesz wspólnikiem)"},
            {"/firma zlecenia", "tablica zleceń: hurt dworów NPC i kontrakty graczy"},
            {"/firma info [firma]", "karta firmy"}, {"/firma ranking [obrot|majatek|ludzie|kontrakty]", "ranking firm"},
            {"/firma wplac <kwota>", "wpłata z twojego konta do firmy"},
            {"/firma dywidenda <kwota>", "mistrz: wypłata dla udziałowców według udziałów"},
            {"/firma pensja <gracz> <kwota>", "mistrz: pensja dzienna (do 50 zł)"},
            {"/firma zapros <gracz> <wspolnik|czeladnik>", "mistrz: zaproszenie"}, {"/firma udzialy <gracz> <procent>", "przekazanie udziałów (za zgodą)"},
            {"/firma sklep", "patrząc na własny [Sklep]: sklep firmy"}, {"/firma magazyn", "patrząc na skrzynię: magazyn z dziennikiem"},
            {"/firma ksiega", "księga firmy"}, {"/firma herb | kolor | siedziba | awans | likwiduj", "mistrz"},
            {"/firma kraj", "Władca/Doradca: firmy i wnioski w kraju, podatek"}};
        for (final String[] l : lines) {
            send(s, "<click:suggest_command:'" + l[0].replaceAll(" [<\\[].*", " ").replace("'", "") + "'><yellow>" + escape(l[0])
                + "</yellow></click> <gray>— " + escape(l[1]));
        }
        for (final String[] l : this.helpLines) {
            send(s, "<click:suggest_command:'" + l[0].replaceAll(" [<\\[].*", " ").replace("'", "") + "'><yellow>" + escape(l[0])
                + "</yellow></click> <gray>— " + escape(l[1]));
        }
    }
}
