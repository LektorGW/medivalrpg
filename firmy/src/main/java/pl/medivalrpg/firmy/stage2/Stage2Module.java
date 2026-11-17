// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.medivalrpg.firmy.FirmyPlugin;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.command.FirmaCommand;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Outcome;
import pl.medivalrpg.firmy.gui.FirmGui;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.panel.FirmyPanel;
import pl.medivalrpg.firmy.stage2.Stage2Data.Contract;
import pl.medivalrpg.firmy.stage2.Stage2Data.ContractState;
import pl.medivalrpg.firmy.stage2.Stage2Data.Kind;
import pl.medivalrpg.firmy.stage2.Stage2Data.Loan;
import pl.medivalrpg.firmy.stage2.Stage2Data.LoanState;
import pl.medivalrpg.firmy.stage2.Stage2Data.Party;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;

/**
 * Stage 2 in the game: {@code /firma kontrakt|hurt|kredyt}, physical delivery of goods, the Kontrakty tab and the panel
 * methods for contracts, disputes and loans. Loaded by FirmyPlugin only from a stage-2 jar.
 */
public final class Stage2Module {

    private final FirmyPlugin plugin;
    private final FirmService service;
    private final Stage2 s2;
    private final Stage2Data data;
    private final Path file;
    private final KrajeApi kraje;
    private Stage2Settings settings;

    public Stage2Module(final FirmyPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.service = plugin.service();
        this.file = plugin.getDataFolder().toPath().resolve("kontrakty.json");
        this.data = plugin.store().read(this.file, Stage2Data.class, Stage2Data::new);
        if (this.data.contracts == null) {
            this.data.contracts = new LinkedHashMap<>();
        }
        if (this.data.loans == null) {
            this.data.loans = new LinkedHashMap<>();
        }
        if (this.data.npcSpent == null) {
            this.data.npcSpent = new LinkedHashMap<>();
        }
        this.kraje = Bukkit.getServicesManager().load(KrajeApi.class);
        this.settings = Stage2Settings.from(plugin.getConfig());
        this.s2 = new Stage2(this.service, this.data, new BukkitCourts(this.kraje), this.settings, this::save);
        this.service.addExtension(this.s2);
        Bukkit.getScheduler().runTaskTimer(plugin, this.s2::tick, 400L, 1200L);
        plugin.onDisable(() -> plugin.store().writeNow(this.file, this.data));
        plugin.command().extend(this::commands,
            new String[] {"/firma kontrakt lista", "kontrakty z depozytem: tablica i twoje"},
            new String[] {"/firma kontrakt zamow <wykonawca|wszyscy> <przedmiot> <ilość> <cena> <godziny> [wadium]", "patrząc na skrzynię odbiorczą"},
            new String[] {"/firma kontrakt usluga <wykonawca|wszyscy> <cena> <godziny> <wadium> <opis>", "usługa (potwierdza zleceniodawca)"},
            new String[] {"/firma kontrakt przyjmij|oddaj|potwierdz|odwolaj|spor <id>", "przebieg kontraktu"},
            new String[] {"/firma hurt", "zlecenia hurtowe władców NPC dla firm"},
            new String[] {"/firma kredyt", "kredyty: limit, oferty, spłata z przychodów"});
        plugin.gui().setContracts(new FirmGui.ContractsView() {
            @Override
            public List<ItemStack> items(final Firm f, final UUID viewer) {
                return Stage2Module.this.guiItems(f, viewer);
            }

            @Override
            public List<FirmGui.Button> buttons(final Firm f, final Player viewer) {
                return Stage2Module.this.contractButtons(f, viewer);
            }
        });
        plugin.gui().setBoard(this::board);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onJoin(final org.bukkit.event.player.PlayerJoinEvent event) {
                Stage2Module.this.joinHint(event.getPlayer());
            }
        }, plugin);
        this.registerPanel(plugin.panel());
        plugin.getLogger().info("Firmy etap 2: kontraktów " + this.data.contracts.size() + ", kredytów " + this.data.loans.size());
    }

    public Stage2 stage2() {
        return this.s2;
    }

    private void save() {
        this.plugin.store().writeAsync(this.file, this.data);
    }

    // ------------------------------------------------------------------ commands

    private SuggestionProvider<CommandSourceStack> contractIds() {
        return (ctx, b) -> {
            for (final Contract c : this.data.contracts.values()) {
                if (c.state.open()) {
                    b.suggest(c.id, new com.mojang.brigadier.LiteralMessage(c.title));
                }
            }
            return b.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> loanIds() {
        return (ctx, b) -> {
            for (final Loan l : this.data.loans.values()) {
                if (l.state.live()) {
                    b.suggest(l.id, new com.mojang.brigadier.LiteralMessage(l.firmName + " " + l.lenderName));
                }
            }
            return b.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> materials() {
        return (ctx, b) -> {
            final String rem = b.getRemaining().toUpperCase(Locale.ROOT);
            int n = 0;
            for (final Material m : Material.values()) {
                if (m.isItem() && !m.isAir() && !m.isLegacy() && m.name().startsWith(rem) && n++ < 40) {
                    b.suggest(m.name().toLowerCase(Locale.ROOT));
                }
            }
            return b.buildFuture();
        };
    }

    private FirmaCommand cmd() {
        return this.plugin.command();
    }

    private int contract(final CommandContext<CommandSourceStack> ctx, final java.util.function.BiConsumer<Player, String> action) {
        return FirmaCommand.player(ctx, p -> action.accept(p, StringArgumentType.getString(ctx, "kontrakt")));
    }

    private void commands(final LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("kontrakt")
            .executes(ctx -> FirmaCommand.player(ctx, this::list))
            .then(Commands.literal("lista").executes(ctx -> FirmaCommand.player(ctx, this::list)))
            .then(Commands.literal("info").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.contract(ctx, this::info))))
            .then(this.orderNode("zamow", Party.PLAYER))
            .then(this.orderNode("zamow-firma", Party.FIRM))
            .then(this.orderNode("zamow-kraj", Party.COUNTRY))
            .then(this.serviceNode("usluga", Party.PLAYER))
            .then(this.serviceNode("usluga-firma", Party.FIRM))
            .then(this.serviceNode("usluga-kraj", Party.COUNTRY))
            .then(Commands.literal("przyjmij").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.cmd().withFirm(ctx, (p, f) -> FirmaCommand.result(p,
                    this.s2.contracts.accept(StringArgumentType.getString(ctx, "kontrakt"), p.getUniqueId(), f))))))
            .then(Commands.literal("oddaj").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.contract(ctx, this::deliver))))
            .then(Commands.literal("potwierdz").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.contract(ctx, (p, id) -> FirmaCommand.result(p, this.s2.contracts.confirm(id, p.getUniqueId()))))))
            .then(Commands.literal("odwolaj").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.contract(ctx, (p, id) -> FirmaCommand.result(p, this.s2.contracts.cancel(id, p.getUniqueId(), false))))))
            .then(Commands.literal("spor").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .then(Commands.argument("powod", StringArgumentType.greedyString())
                    .executes(ctx -> this.contract(ctx, (p, id) -> FirmaCommand.result(p,
                        this.s2.contracts.dispute(id, p.getUniqueId(), StringArgumentType.getString(ctx, "powod")))))))));
        root.then(Commands.literal("hurt")
            .executes(ctx -> FirmaCommand.player(ctx, this::wholesale))
            .then(Commands.literal("przyjmij").then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .executes(ctx -> this.cmd().withFirm(ctx, (p, f) -> FirmaCommand.result(p,
                    this.s2.contracts.accept(StringArgumentType.getString(ctx, "kontrakt"), p.getUniqueId(), f)))))));
        root.then(Commands.literal("kredyt")
            .executes(ctx -> FirmaCommand.player(ctx, this::loans))
            .then(this.offerNode("oferuj", false))
            .then(this.offerNode("oferuj-firma", true))
            .then(Commands.literal("przyjmij").then(Commands.argument("kredyt", StringArgumentType.word()).suggests(this.loanIds())
                .executes(ctx -> FirmaCommand.player(ctx, p -> FirmaCommand.result(p, this.s2.loans.accept(StringArgumentType.getString(ctx, "kredyt"), p.getUniqueId()))))))
            .then(Commands.literal("odrzuc").then(Commands.argument("kredyt", StringArgumentType.word()).suggests(this.loanIds())
                .executes(ctx -> FirmaCommand.player(ctx, p -> FirmaCommand.result(p, this.s2.loans.refuse(StringArgumentType.getString(ctx, "kredyt"),
                    p.getUniqueId(), false))))))
            .then(Commands.literal("wycofaj").then(Commands.argument("kredyt", StringArgumentType.word()).suggests(this.loanIds())
                .executes(ctx -> FirmaCommand.player(ctx, p -> FirmaCommand.result(p, this.s2.loans.refuse(StringArgumentType.getString(ctx, "kredyt"),
                    p.getUniqueId(), true))))))
            .then(Commands.literal("skarb")
                .then(Commands.argument("kwota", StringArgumentType.word())
                    .then(Commands.argument("procent", DoubleArgumentType.doubleArg(0, 10))
                        .then(Commands.argument("dni", IntegerArgumentType.integer(1, 365))
                            .then(Commands.argument("cel", StringArgumentType.greedyString())
                                .executes(ctx -> this.cmd().withFirm(ctx, (p, f) -> this.cmd().amount(p, StringArgumentType.getString(ctx, "kwota"))
                                    .ifPresent(a -> FirmaCommand.result(p, this.s2.loans.requestWorld(f, p.getUniqueId(), a,
                                        (int) Math.round(DoubleArgumentType.getDouble(ctx, "procent") * 100), IntegerArgumentType.getInteger(ctx, "dni"),
                                        StringArgumentType.getString(ctx, "cel"))))))))))));
        root.then(Commands.literal("spor")
            .requires(s -> s.getSender().hasPermission(FirmaCommand.PERM_ADMIN))
            .then(Commands.argument("kontrakt", StringArgumentType.word()).suggests(this.contractIds())
                .then(Commands.argument("na-korzysc", StringArgumentType.word()).suggests((c, b) -> {
                    for (final String w : List.of("client", "contractor", "split")) {
                        b.suggest(w);
                    }
                    return b.buildFuture();
                }).executes(ctx -> {
                    FirmaCommand.result(ctx.getSource().getSender(), this.s2.contracts.resolve(StringArgumentType.getString(ctx, "kontrakt"),
                        StringArgumentType.getString(ctx, "na-korzysc"), "", ctx.getSource().getSender().getName()));
                    return 1;
                }))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> orderNode(final String literal, final Party party) {
        return Commands.literal(literal)
            .then(Commands.argument("wykonawca", StringArgumentType.word()).suggests(this.cmd().firmNames())
                .then(Commands.argument("przedmiot", StringArgumentType.word()).suggests(materials())
                    .then(Commands.argument("ilosc", IntegerArgumentType.integer(1, 100_000))
                        .then(Commands.argument("cena", StringArgumentType.word())
                            .then(Commands.argument("godziny", IntegerArgumentType.integer(1, 24 * 120))
                                .executes(ctx -> this.order(ctx, party, "0"))
                                .then(Commands.argument("wadium", StringArgumentType.word())
                                    .executes(ctx -> this.order(ctx, party, StringArgumentType.getString(ctx, "wadium")))))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> serviceNode(final String literal, final Party party) {
        return Commands.literal(literal)
            .then(Commands.argument("wykonawca", StringArgumentType.word()).suggests(this.cmd().firmNames())
                .then(Commands.argument("cena", StringArgumentType.word())
                    .then(Commands.argument("godziny", IntegerArgumentType.integer(1, 24 * 120))
                        .then(Commands.argument("wadium", StringArgumentType.word())
                            .then(Commands.argument("opis", StringArgumentType.greedyString())
                                .executes(ctx -> FirmaCommand.player(ctx, p -> this.create(p, party, Kind.SERVICE,
                                    StringArgumentType.getString(ctx, "opis"), null, 1, StringArgumentType.getString(ctx, "cena"),
                                    StringArgumentType.getString(ctx, "wadium"), IntegerArgumentType.getInteger(ctx, "godziny"), null,
                                    StringArgumentType.getString(ctx, "wykonawca")))))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> offerNode(final String literal, final boolean asFirm) {
        return Commands.literal(literal)
            .then(Commands.argument("firma", StringArgumentType.word()).suggests(this.cmd().firmNames())
                .then(Commands.argument("kwota", StringArgumentType.word())
                    .then(Commands.argument("procent", DoubleArgumentType.doubleArg(0, 10))
                        .then(Commands.argument("dni", IntegerArgumentType.integer(1, 365))
                            .executes(ctx -> FirmaCommand.player(ctx, p -> {
                                final Optional<Firm> borrower = this.service.ctx.firm(StringArgumentType.getString(ctx, "firma"));
                                if (borrower.isEmpty()) {
                                    FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie ma takiej firmy.");
                                    return;
                                }
                                final Optional<Firm> lender = asFirm ? this.cmd().current(p) : Optional.empty();
                                if (asFirm && lender.isEmpty()) {
                                    FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie należysz do firmy.");
                                    return;
                                }
                                this.cmd().amount(p, StringArgumentType.getString(ctx, "kwota")).ifPresent(a -> FirmaCommand.result(p,
                                    this.s2.loans.offer(p.getUniqueId(), lender.orElse(null), borrower.get(), a,
                                        (int) Math.round(DoubleArgumentType.getDouble(ctx, "procent") * 100), IntegerArgumentType.getInteger(ctx, "dni"))));
                            }))))));
    }

    private int order(final CommandContext<CommandSourceStack> ctx, final Party party, final String bond) {
        return FirmaCommand.player(ctx, p -> {
            final Block b = p.getTargetBlockExact(6);
            if (b == null || !(b.getState(false) instanceof Container)) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Spójrz na skrzynię odbiorczą (tam wykonawca dostarczy towar).");
                return;
            }
            final Material m = Material.matchMaterial(StringArgumentType.getString(ctx, "przedmiot"));
            if (m == null || !m.isItem() || m.isAir()) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nieznany przedmiot.");
                return;
            }
            final String chest = b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
            this.create(p, party, Kind.GOODS, "", m.name(), IntegerArgumentType.getInteger(ctx, "ilosc"), StringArgumentType.getString(ctx, "cena"),
                bond, IntegerArgumentType.getInteger(ctx, "godziny"), chest, StringArgumentType.getString(ctx, "wykonawca"));
        });
    }

    private void create(final Player p, final Party party, final Kind kind, final String title, final String item, final int qty,
                        final String priceRaw, final String bondRaw, final int hours, final String chest, final String contractor) {
        final Optional<Long> price = this.cmd().amount(p, priceRaw);
        if (price.isEmpty()) {
            return;
        }
        final Optional<Long> bond = bondRaw.equals("0") ? Optional.of(0L) : this.cmd().amount(p, bondRaw);
        if (bond.isEmpty()) {
            return;
        }
        String reserved = null;
        if (!contractor.equalsIgnoreCase("wszyscy") && !contractor.equals("*")) {
            final Optional<Firm> f = this.service.ctx.firm(contractor);
            if (f.isEmpty()) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie ma firmy " + FirmaCommand.escape(contractor) + " (albo wpisz: wszyscy).");
                return;
            }
            reserved = f.get().id;
        }
        final Contracts.Payer payer;
        switch (party) {
            case FIRM -> {
                final Optional<Firm> f = this.cmd().current(p);
                if (f.isEmpty()) {
                    FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie należysz do firmy.");
                    return;
                }
                payer = new Contracts.Payer(Party.FIRM, f.get().id, f.get().name, f.get().account());
            }
            case COUNTRY -> {
                final Optional<KrajInfo> k = this.kraje.getCountryOf(p.getUniqueId());
                if (k.isEmpty()) {
                    FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie należysz do kraju.");
                    return;
                }
                payer = new Contracts.Payer(Party.COUNTRY, k.get().id().toString(), k.get().name(),
                    k.get().treasuryAccount() == null ? "country:" + k.get().id() : k.get().treasuryAccount());
            }
            default -> payer = new Contracts.Payer(Party.PLAYER, p.getUniqueId().toString(), p.getName(), "player:" + p.getUniqueId());
        }
        FirmaCommand.result(p, this.s2.contracts.create(p.getUniqueId(), payer, kind, title, item, qty, price.get(), bond.get(), hours, chest, reserved));
    }

    // ------------------------------------------------------------------ delivery (real items)

    private void deliver(final Player p, final String id) {
        final Optional<Contract> oc = this.s2.contracts.get(id);
        if (oc.isPresent() && oc.get().kind == Kind.SERVICE) {
            FirmaCommand.result(p, this.s2.contracts.report(id, p.getUniqueId()));
            return;
        }
        final Outcome can = this.s2.contracts.canDeliver(id, p.getUniqueId());
        if (!can.ok()) {
            FirmaCommand.result(p, can);
            return;
        }
        final Contract c = oc.orElseThrow();
        final int need = Integer.parseInt(can.message());
        final Material mat = Material.matchMaterial(c.item);
        if (mat == null) {
            FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nieznany przedmiot kontraktu.");
            return;
        }
        Inventory target = null;
        if (c.client == Party.NPC) {
            final Optional<Courts.Court> court = this.s2.contracts.court(c.clientId);
            if (court.isEmpty() || !p.getWorld().getName().equals(court.get().world())
                || Math.max(Math.abs((p.getLocation().getBlockX() >> 4) - court.get().capitalX()),
                Math.abs((p.getLocation().getBlockZ() >> 4) - court.get().capitalZ())) > this.settings.capitalRadiusChunks) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Towar oddaje się w stolicy " + FirmaCommand.escape(c.clientName)
                    + " (do " + this.settings.capitalRadiusChunks + " chunków od chunka stolicy).");
                return;
            }
        } else {
            final String[] k = c.chest.split(":");
            final World w = Bukkit.getWorld(k[0]);
            final Location loc = w == null ? null : new Location(w, Integer.parseInt(k[1]) + 0.5, Integer.parseInt(k[2]) + 0.5, Integer.parseInt(k[3]) + 0.5);
            if (loc == null || !p.getWorld().equals(w) || p.getLocation().distance(loc) > this.settings.deliveryReach) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Stań przy skrzyni odbiorczej (" + k[1] + " " + k[2] + " " + k[3] + ").");
                return;
            }
            if (!(loc.getBlock().getState(false) instanceof Container cont)) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Skrzyni odbiorczej już nie ma — otwórz spór (/firma kontrakt spor).");
                return;
            }
            target = cont.getInventory();
        }
        // only plain items of the material (no renamed / enchanted / damaged ones), never more than needed
        int moved = 0;
        final ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && moved < need; i++) {
            final ItemStack it = contents[i];
            if (it == null || it.getType() != mat || !plain(it)) {
                continue;
            }
            final int take = Math.min(it.getAmount(), need - moved);
            if (target != null) {
                final ItemStack part = it.asQuantity(take);
                final Map<Integer, ItemStack> rest = target.addItem(part);
                final int notFitting = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
                final int put = take - notFitting;
                if (put <= 0) {
                    break;
                }
                it.setAmount(it.getAmount() - put);
                p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                moved += put;
                if (notFitting > 0) {
                    break;
                }
            } else {
                it.setAmount(it.getAmount() - take);
                p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                moved += take;
            }
        }
        if (moved == 0) {
            FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie masz " + c.item.toLowerCase(Locale.ROOT) + " (zwykłych, bez nazwy i zaklęć)"
                + (target != null ? " albo skrzynia odbiorcza jest pełna" : "") + ".");
            return;
        }
        FirmaCommand.result(p, this.s2.contracts.delivered(id, p.getUniqueId(), moved));
    }

    private static boolean plain(final ItemStack it) {
        if (!it.hasItemMeta()) {
            return true;
        }
        final ItemMeta m = it.getItemMeta();
        return !m.hasDisplayName() && !m.hasEnchants() && !(m instanceof org.bukkit.inventory.meta.Damageable d && d.hasDamage());
    }

    // ------------------------------------------------------------------ views

    private String fmt(final long v) {
        return this.service.ctx.fmt(v);
    }

    private void line(final Player p, final Contract c) {
        FirmaCommand.send(p, " <yellow>" + c.id + "</yellow> <white>" + FirmaCommand.escape(c.title) + "</white> <gray>— " + this.fmt(c.price)
            + (c.bond > 0 ? ", wadium " + this.fmt(c.bond) : "") + ", " + c.state.label() + (c.kind == Kind.GOODS ? ", " + c.delivered + "/" + c.quantity : "")
            + (c.contractorName != null ? ", wykonawca " + FirmaCommand.escape(c.contractorName) : "")
            + "</gray> <click:run_command:'/firma kontrakt info " + c.id + "'><aqua>[Szczegóły]</aqua></click>");
    }

    private void list(final Player p) {
        final Optional<Firm> f = this.cmd().current(p);
        FirmaCommand.send(p, "<gold>━━ Kontrakty ━━");
        final List<Contract> board = this.s2.contracts.board(f.map(x -> x.id).orElse(null)).stream().filter(c -> c.client != Party.NPC).toList();
        FirmaCommand.send(p, "<yellow>Tablica (" + board.size() + "):");
        board.stream().limit(10).forEach(c -> this.line(p, c));
        f.ifPresent(firm -> {
            FirmaCommand.send(p, "<yellow>Twojej firmy:");
            this.s2.contracts.forFirm(firm.id).stream().filter(c -> c.state.open()).limit(10).forEach(c -> this.line(p, c));
        });
        final List<Contract> mine = this.data.contracts.values().stream().filter(c -> c.state.open()
            && p.getUniqueId().toString().equals(c.createdBy)).toList();
        if (!mine.isEmpty()) {
            FirmaCommand.send(p, "<yellow>Twoje zlecenia:");
            mine.forEach(c -> this.line(p, c));
        }
        FirmaCommand.send(p, "<dark_gray>Zamów towar: patrząc na skrzynię /firma kontrakt zamow <firma|wszyscy> <przedmiot> <ilość> <cena> <godziny> [wadium]");
    }

    private void info(final Player p, final String id) {
        final Optional<Contract> oc = this.s2.contracts.get(id);
        if (oc.isEmpty()) {
            FirmaCommand.send(p, "<gold>[Firmy]</gold> <red>Nie ma takiego kontraktu.");
            return;
        }
        final Contract c = oc.get();
        final SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm");
        FirmaCommand.send(p, "<gold>━━ Kontrakt " + c.id + " ━━");
        FirmaCommand.send(p, "<white>" + FirmaCommand.escape(c.title) + "</white> <gray>(" + (c.kind == Kind.GOODS ? "towar" : "usługa") + ")");
        FirmaCommand.send(p, "<gray>Zleceniodawca: <white>" + FirmaCommand.escape(c.clientName) + "</white>, wykonawca: <white>"
            + FirmaCommand.escape(c.contractorName == null ? (c.reservedFor != null ? "tylko wskazana firma" : "każda firma") : c.contractorName));
        FirmaCommand.send(p, "<gray>Zapłata w depozycie: <gold>" + this.fmt(this.s2.contracts.held(c)) + "</gold> (cena " + this.fmt(c.price)
            + (c.bond > 0 ? ", wadium " + this.fmt(c.bond) : "") + ")");
        FirmaCommand.send(p, "<gray>Stan: <white>" + c.state.label() + "</white>" + (c.deadline > 0 ? ", termin " + df.format(new Date(c.deadline))
            : ", oferta do " + df.format(new Date(c.offerUntil))) + (c.kind == Kind.GOODS ? ", dostarczono " + c.delivered + "/" + c.quantity : ""));
        if (c.chest != null) {
            FirmaCommand.send(p, "<gray>Skrzynia odbiorcza: " + c.chest.replace(":", " "));
        }
        if (c.state == ContractState.OPEN) {
            FirmaCommand.send(p, "<click:run_command:'/firma kontrakt przyjmij " + c.id + "'><green>[Przyjmij dla mojej firmy]</green></click>");
        } else if (c.state == ContractState.ACCEPTED) {
            FirmaCommand.send(p, "<click:run_command:'/firma kontrakt oddaj " + c.id + "'><green>[Oddaj / zgłoś wykonanie]</green></click>");
        }
    }

    private void wholesale(final Player p) {
        final List<Contract> list = this.nearest(p, this.s2.contracts.wholesale());
        FirmaCommand.send(p, "<gold>━━ Zlecenia hurtowe dworów NPC (" + list.size() + ", najbliższe pierwsze) ━━");
        int shown = 0;
        for (final Contract c : list) {
            if (shown++ >= 10) {
                break;
            }
            FirmaCommand.send(p, " <white>" + c.quantity + "× " + itemName(c.item) + "</white> <gray>dla " + FirmaCommand.escape(c.clientName)
                + " — <gold>" + this.fmt(c.price) + "</gold> (" + this.fmt(c.price / Math.max(1, c.quantity)) + "/szt.), " + this.where(p, c)
                + "</gray> <click:run_command:'/firma hurt przyjmij " + c.id + "'><green>[Przyjmij]</green></click>");
        }
        if (list.isEmpty()) {
            FirmaCommand.send(p, "<gray>Tablica jest pusta — dwory wystawiają nowe zlecenia w ciągu dnia.");
        }
        FirmaCommand.send(p, "<dark_gray>Płaci skarbiec dworu przez depozyt (limit dzienny skarbca). Towar oddajesz w stolicy dworu: "
            + "/firma kontrakt oddaj <id>. <click:run_command:'/firma zlecenia'><yellow>[Tablica w oknie]</yellow></click>");
    }

    // ------------------------------------------------------------------ board (GUI) and geography of NPC courts

    private static String itemName(final String item) {
        return item == null ? "?" : item.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private Map<String, Courts.Court> courts() {
        final Map<String, Courts.Court> m = new LinkedHashMap<>();
        for (final Courts.Court c : new BukkitCourts(this.kraje).npcCountries()) {
            m.put(c.id().toString(), c);
        }
        return m;
    }

    /** Block distance from the player to the capital of the court (MAX = other world or unknown). */
    private static long distance(final Player p, final Courts.Court c) {
        if (c == null || !p.getWorld().getName().equals(c.world())) {
            return Long.MAX_VALUE;
        }
        final double dx = c.capitalX() * 16 + 8 - p.getLocation().getX();
        final double dz = c.capitalZ() * 16 + 8 - p.getLocation().getZ();
        return Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    private List<Contract> nearest(final Player p, final List<Contract> list) {
        final Map<String, Courts.Court> courts = this.courts();
        final List<Contract> out = new ArrayList<>(list);
        out.sort(java.util.Comparator.comparingLong((Contract c) -> distance(p, courts.get(c.clientId))).thenComparing(c -> -c.price));
        return out;
    }

    private String where(final Player p, final Contract c) {
        final Courts.Court court = this.courts().get(c.clientId);
        if (court == null) {
            return "stolica nieznana";
        }
        final long d = distance(p, court);
        return "stolica " + (court.capitalX() * 16 + 8) + ", " + (court.capitalZ() * 16 + 8) + (d == Long.MAX_VALUE ? " (inny świat)" : " (" + d + " bl.)");
    }

    /** Tablica zleceń: NPC wholesale (nearest first) and open contracts of players, firms and countries. */
    private List<FirmGui.Button> board(final Firm f, final Player p) {
        final List<FirmGui.Button> out = new ArrayList<>();
        final int taxBp = f == null ? -1 : this.service.treasury.taxTarget(f).map(Map.Entry::getValue).orElse(-1);
        final boolean canTake = f != null && f.has(p.getUniqueId(), pl.medivalrpg.firmy.model.Role.WSPOLNIK);
        final SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm");
        for (final Contract c : this.nearest(p, this.s2.contracts.wholesale())) {
            if (out.size() >= 27) {
                break;
            }
            final Material m = Optional.ofNullable(Material.matchMaterial(c.item == null ? "" : c.item)).filter(Material::isItem).orElse(Material.HAY_BLOCK);
            final List<String> lore = new ArrayList<>(List.of(
                "<gray>Dwór: <white>" + FirmaCommand.escape(c.clientName) + "</white> (NPC)",
                "<gray>Zapłata: <gold>" + this.fmt(c.price) + "</gold> <dark_gray>(" + this.fmt(c.price / Math.max(1, c.quantity)) + "/szt.)"));
            if (taxBp >= 0) {
                lore.add("<gray>Na konto firmy: <green>" + this.fmt(c.price - pl.medivalrpg.firmy.core.Treasury.tax(c.price, taxBp))
                    + "</green> <dark_gray>(podatek " + taxBp / 100.0 + " %)");
            }
            lore.add("<gray>Dowóz: " + this.where(p, c));
            lore.add("<gray>Po przyjęciu: " + c.hours + " h · oferta do " + df.format(new Date(c.offerUntil)));
            lore.add("<gray>+" + this.settings.reputation + " reputacji u dworu dla dowożącego");
            lore.add("");
            lore.add(canTake ? "<gold>Kliknij</gold> <gray>– przyjmij dla „" + FirmaCommand.escape(f.name) + "”"
                : f == null ? "<yellow>Załóż firmę, aby przyjmować zlecenia" : "<yellow>Przyjmuje mistrz albo wspólnik");
            out.add(new FirmGui.Button(FirmGui.item(m, "<gold>" + c.quantity + "× " + itemName(c.item), lore),
                canTake ? "firma hurt przyjmij " + c.id : null, null));
        }
        for (final Contract c : this.s2.contracts.board(f == null ? null : f.id)) {
            if (out.size() >= 36) {
                break;
            }
            if (c.client == Party.NPC || f != null && c.client == Party.FIRM && f.id.equals(c.clientId)) {
                continue;
            }
            final Material m = c.kind == Kind.GOODS ? Optional.ofNullable(Material.matchMaterial(c.item == null ? "" : c.item))
                .filter(Material::isItem).orElse(Material.PAPER) : Material.WRITABLE_BOOK;
            final List<String> lore = new ArrayList<>(List.of(
                "<gray>Zleca: <white>" + FirmaCommand.escape(c.clientName) + "</white> (" + (c.client == Party.COUNTRY ? "kraj"
                    : c.client == Party.FIRM ? "firma" : "gracz") + ")",
                "<gray>Zapłata w depozycie: <gold>" + this.fmt(c.price) + (c.bond > 0 ? "</gold>, wadium " + this.fmt(c.bond) : "</gold>"),
                "<gray>Termin: " + c.hours + " h · oferta do " + df.format(new Date(c.offerUntil))));
            if (c.chest != null) {
                lore.add("<gray>Skrzynia odbiorcza: " + c.chest.substring(c.chest.indexOf(':') + 1).replace(':', ' '));
            }
            lore.add("");
            lore.add(canTake ? "<gold>Kliknij</gold> <gray>– przyjmij" : "<yellow>Przyjmuje mistrz albo wspólnik firmy");
            out.add(new FirmGui.Button(FirmGui.item(m, "<yellow>" + FirmaCommand.escape(c.title), lore),
                canTake ? "firma kontrakt przyjmij " + c.id : null, null));
        }
        return out;
    }

    /** Kontrakty tab: contracts of the company with the next step on click (deliver, details). */
    private List<FirmGui.Button> contractButtons(final Firm f, final Player p) {
        final List<FirmGui.Button> out = new ArrayList<>();
        final SimpleDateFormat df = new SimpleDateFormat("dd.MM HH:mm");
        for (final Contract c : this.s2.contracts.forFirm(f.id)) {
            if (!c.state.open()) {
                continue;
            }
            final boolean ours = f.id.equals(c.contractor);
            final Material m = c.client == Party.NPC ? Material.HAY_BLOCK : c.kind == Kind.SERVICE ? Material.WRITABLE_BOOK : Material.PAPER;
            final List<String> lore = new ArrayList<>(List.of(
                "<gray>" + c.state.label() + " · " + this.fmt(c.price) + (c.kind == Kind.GOODS ? " · " + c.delivered + "/" + c.quantity : ""),
                "<gray>" + (ours ? "Zleca: " + FirmaCommand.escape(c.clientName) : "Wykonawca: " + FirmaCommand.escape(c.contractorName == null ? "—" : c.contractorName))));
            if (c.deadline > 0) {
                lore.add("<gray>Termin: <white>" + df.format(new Date(c.deadline)));
            }
            String run = "firma kontrakt info " + c.id;
            if (ours && c.state == ContractState.ACCEPTED) {
                if (c.client == Party.NPC) {
                    lore.add("<gray>Dowóz: " + this.where(p, c));
                } else if (c.chest != null) {
                    lore.add("<gray>Skrzynia odbiorcza: " + c.chest.substring(c.chest.indexOf(':') + 1).replace(':', ' '));
                }
                lore.add("");
                lore.add(c.kind == Kind.GOODS ? "<gold>Kliknij</gold> <gray>– oddaj towar z ekwipunku (stań na miejscu)"
                    : "<gold>Kliknij</gold> <gray>– zgłoś wykonanie usługi");
                run = "firma kontrakt oddaj " + c.id;
            } else {
                lore.add("<dark_gray>Kliknij: szczegóły");
            }
            out.add(new FirmGui.Button(FirmGui.item(m, "<yellow>" + FirmaCommand.escape(c.title), lore), run, null));
        }
        for (final ItemStack it : this.guiItems(f, p.getUniqueId())) {
            if (it.getType() == Material.GOLD_INGOT) {
                out.add(FirmGui.Button.info(it));
            }
        }
        return out;
    }

    /** Once a day, members of a company hear how many wholesale orders wait on the board. */
    private final java.util.Set<String> hinted = new java.util.HashSet<>();

    private void joinHint(final Player p) {
        if (this.service.ctx.firmsOf(p.getUniqueId()).isEmpty()) {
            return;
        }
        final String key = this.service.ctx.today() + p.getUniqueId();
        if (!this.hinted.add(key)) {
            return;
        }
        if (this.hinted.size() > 2_000) {
            this.hinted.clear();
        }
        final int n = this.s2.contracts.wholesale().size();
        if (n == 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            if (p.isOnline()) {
                FirmaCommand.send(p, "<gold>[Firmy]</gold> Na tablicy czeka <yellow>" + n + "</yellow> zleceń hurtowych dworów NPC. "
                    + "<click:run_command:'/firma zlecenia'><green>[Tablica zleceń]</green></click>");
            }
        }, 120L);
    }

    private void loans(final Player p) {
        FirmaCommand.send(p, "<gold>━━ Kredyty firm ━━");
        this.cmd().current(p).ifPresent(f -> {
            final Loans.Limit lim = this.s2.loans.limit(f);
            FirmaCommand.send(p, "<gray>Limit „" + FirmaCommand.escape(f.name) + "”: <gold>" + this.fmt(lim.limit()) + "</gold> (2× kapitał "
                + this.fmt(lim.byCapital()) + ", 50 % obrotu 30 dni " + this.fmt(lim.byIncome()) + ", limit kwotowy " + this.fmt(lim.hardCap()) + ")");
            for (final Loan l : this.s2.loans.of(f)) {
                if (l.state.live() || l.state == LoanState.DEFAULTED) {
                    FirmaCommand.send(p, " <yellow>" + l.id + "</yellow> <gray>" + FirmaCommand.escape(l.lenderName) + ": " + this.fmt(l.principal) + " + "
                        + l.interestBp / 100.0 + " %, " + l.days + " dni — " + l.state.label() + ", do spłaty " + this.fmt(l.outstanding())
                        + (l.state == LoanState.OFFERED ? "</gray> <click:run_command:'/firma kredyt przyjmij " + l.id + "'><green>[Przyjmij]</green></click>" : ""));
                }
            }
        });
        for (final Loan l : this.data.loans.values()) {
            if (l.state.live() && p.getUniqueId().toString().equals(l.lenderId)) {
                FirmaCommand.send(p, " <aqua>Twój kredyt " + l.id + " dla " + FirmaCommand.escape(l.firmName) + "</aqua><gray>: " + l.state.label()
                    + ", spłacono " + this.fmt(l.repaid) + "/" + this.fmt(l.total()) + "</gray>");
            }
        }
        FirmaCommand.send(p, "<dark_gray>Pożycz firmie: /firma kredyt oferuj <firma> <kwota> <procent ≤10> <dni 7–30> (pieniądze czekają w depozycie)");
        FirmaCommand.send(p, "<dark_gray>Skarb Świata (ręczna zgoda właściciela): /firma kredyt skarb <kwota> <procent> <dni> <cel>");
    }

    private List<ItemStack> guiItems(final Firm f, final UUID viewer) {
        final List<ItemStack> out = new ArrayList<>();
        for (final Contract c : this.s2.contracts.forFirm(f.id)) {
            if (!c.state.open()) {
                continue;
            }
            final ItemStack it = new ItemStack(c.client == Party.NPC ? Material.HAY_BLOCK : Material.PAPER);
            final ItemMeta m = it.getItemMeta();
            m.displayName(BukkitNotifier.parse("<yellow>" + FirmaCommand.escape(c.title)));
            m.lore(List.of(BukkitNotifier.parse("<gray>" + c.state.label() + " · " + this.fmt(c.price)),
                BukkitNotifier.parse("<gray>" + (c.kind == Kind.GOODS ? c.delivered + "/" + c.quantity : "usługa") + " · id " + c.id)));
            it.setItemMeta(m);
            out.add(it);
        }
        for (final Loan l : this.s2.loans.of(f)) {
            if (!l.state.live()) {
                continue;
            }
            final ItemStack it = new ItemStack(Material.GOLD_INGOT);
            final ItemMeta m = it.getItemMeta();
            m.displayName(BukkitNotifier.parse("<gold>Kredyt " + l.id));
            m.lore(List.of(BukkitNotifier.parse("<gray>" + FirmaCommand.escape(l.lenderName) + " · " + l.state.label()),
                BukkitNotifier.parse("<gray>do spłaty " + this.fmt(l.outstanding()))));
            it.setItemMeta(m);
            out.add(it);
        }
        if (out.isEmpty()) {
            out.add(new ItemStack(Material.PAPER));
        }
        return out;
    }

    // ------------------------------------------------------------------ panel (firmy.contracts, disputes, loans)

    private Map<String, Object> contractMap(final Contract c) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("title", c.title);
        m.put("kind", c.kind.name());
        m.put("client", c.clientName);
        m.put("clientType", c.client.name());
        m.put("contractor", c.contractorName);
        m.put("status", c.state.name());
        m.put("statusName", c.state.label());
        m.put("price", c.price);
        m.put("deposit", this.s2.contracts.held(c));
        m.put("bond", c.bond);
        m.put("deadline", c.deadline);
        m.put("item", c.item);
        m.put("quantity", c.quantity);
        m.put("delivered", c.delivered);
        m.put("log", c.log);
        return m;
    }

    private Map<String, Object> loanMap(final Loan l) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("firm", l.firm);
        m.put("firmName", l.firmName);
        m.put("lender", l.lender.name());
        m.put("lenderName", l.lenderName);
        m.put("principal", l.principal);
        m.put("interest", l.total() - l.principal);
        m.put("interestPercent", l.interestBp / 100.0);
        m.put("amount", l.principal);
        m.put("days", l.days);
        m.put("due", l.total());
        m.put("repaid", l.repaid);
        m.put("outstanding", l.outstanding());
        m.put("status", l.state.name());
        m.put("statusName", l.state.label());
        m.put("createdAt", l.createdAt);
        m.put("requestedAt", l.createdAt);
        m.put("dueAt", l.dueAt);
        m.put("purpose", l.purpose);
        m.put("log", l.log);
        return m;
    }

    private void registerPanel(final FirmyPanel panel) {
        panel.register("firmy.contracts", (actor, a) -> {
            final Firm f = this.service.ctx.firm(a.get("id")).orElseThrow(() -> new IllegalArgumentException("Nie ma takiej firmy."));
            final boolean full = FirmyPanel.owner(actor) || actor != null && f.members.containsKey(actor.toString());
            final List<Map<String, Object>> list = new ArrayList<>();
            for (final Contract c : this.s2.contracts.forFirm(f.id)) {
                final Map<String, Object> m = this.contractMap(c);
                if (!full) {
                    m.remove("deposit");
                    m.remove("log");
                }
                list.add(m);
            }
            return Map.of("contracts", list);
        });
        panel.register("firmy.disputes", (actor, a) -> {
            if (!FirmyPanel.owner(actor)) {
                throw new IllegalArgumentException("Brak dostępu.");
            }
            final List<Map<String, Object>> list = new ArrayList<>();
            for (final Contract c : this.s2.contracts.disputes()) {
                final Map<String, Object> m = this.contractMap(c);
                m.put("contract", c.id);
                m.put("amount", c.price);
                m.put("reason", c.disputeReason);
                m.put("openedAt", c.disputeAt);
                m.put("openedBy", c.disputeBy);
                list.add(m);
            }
            return Map.of("disputes", list);
        });
        panel.register("firmy.dispute.resolve", (actor, a) -> {
            if (!FirmyPanel.owner(actor)) {
                throw new IllegalArgumentException("Brak dostępu.");
            }
            final String note = a.getOrDefault("note", "");
            if (note.length() > 200) {
                throw new IllegalArgumentException("Za długa notatka.");
            }
            return FirmyPanel.outcome(this.s2.contracts.resolve(a.get("id"), a.getOrDefault("winner", ""), note, "WorldSteward (panel)"));
        });
        panel.register("firmy.loans", (actor, a) -> {
            final Firm f = this.service.ctx.firm(a.get("id")).orElseThrow(() -> new IllegalArgumentException("Nie ma takiej firmy."));
            final boolean full = FirmyPanel.owner(actor) || actor != null && f.members.containsKey(actor.toString());
            if (!full) {
                throw new IllegalArgumentException("Kredyty firmy widzą jej członkowie.");
            }
            final Loans.Limit lim = this.s2.loans.limit(f);
            return Map.of("loans", this.s2.loans.of(f).stream().map(this::loanMap).toList(), "limit", lim.limit(), "limitText", this.fmt(lim.limit()));
        });
        panel.register("firmy.world.loans", (actor, a) -> {
            if (!FirmyPanel.owner(actor)) {
                throw new IllegalArgumentException("Brak dostępu.");
            }
            final List<Map<String, Object>> apps = new ArrayList<>();
            final List<Map<String, Object>> active = new ArrayList<>();
            final List<Map<String, Object>> toReturn = new ArrayList<>();
            for (final Loan l : this.s2.loans.world()) {
                final Map<String, Object> m = this.loanMap(l);
                final Firm f = this.service.ctx.data.firms.get(l.firm);
                if (l.state == LoanState.REQUESTED) {
                    if (f != null) {
                        final Loans.Limit lim = this.s2.loans.limit(f);
                        m.put("limit", lim.limit());
                        m.put("income30", lim.income30());
                        m.put("capital", lim.capital());
                    }
                    apps.add(m);
                } else if (l.state == LoanState.ACTIVE) {
                    active.add(m);
                }
                final long held = this.service.bankBalance(l.account());
                if (held > 0 && l.state != LoanState.REQUESTED) {
                    toReturn.add(Map.of("id", l.id, "firmName", l.firmName, "amount", held));
                }
            }
            return Map.of("applications", apps, "active", active, "toReturn", toReturn);
        });
        panel.register("firmy.world.loanDecide", (actor, a) -> {
            final String note = a.getOrDefault("note", "");
            if (note.length() > 200) {
                throw new IllegalArgumentException("Za długa notatka.");
            }
            return FirmyPanel.outcome(this.s2.loans.decideWorld(a.get("id"), actor, Boolean.parseBoolean(a.getOrDefault("approve", "false")), note));
        });
        panel.register("firmy.world.loanSweep", (actor, a) -> FirmyPanel.outcome(this.s2.loans.sweepWorld(a.get("id"), actor)));
    }
}
