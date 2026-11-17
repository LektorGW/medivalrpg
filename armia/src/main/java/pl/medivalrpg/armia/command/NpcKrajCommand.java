// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.ai.NpcAiService;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.logic.DecisionLog;
import pl.medivalrpg.armia.logic.Personality;
import pl.medivalrpg.armia.model.NpcState;
import pl.medivalrpg.armia.model.Pos;
import pl.medivalrpg.armia.ruler.RulerService;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.armia.soldier.Soldier;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.skarbiec.api.TxResult;

/** {@code /npckraj} (alias {@code /nk}): admin tools for NPC countries. Brigadier tree, Polish arguments. */
public final class NpcKrajCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    /** Polish colour names → RGB. */
    static final Map<String, Integer> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("czerwony", 0xC0392B);
        COLORS.put("bordowy", 0x7B1E1E);
        COLORS.put("pomaranczowy", 0xE67E22);
        COLORS.put("zolty", 0xF1C40F);
        COLORS.put("zielony", 0x27AE60);
        COLORS.put("ciemnozielony", 0x1E5631);
        COLORS.put("turkusowy", 0x1ABC9C);
        COLORS.put("niebieski", 0x2980B9);
        COLORS.put("granatowy", 0x1F3A93);
        COLORS.put("fioletowy", 0x8E44AD);
        COLORS.put("rozowy", 0xE84393);
        COLORS.put("brazowy", 0x8B5A2B);
        COLORS.put("szary", 0x7F8C8D);
        COLORS.put("czarny", 0x2C2C2C);
        COLORS.put("bialy", 0xECF0F1);
    }

    static final HelpBook HELP = new HelpBook("help-nk", "/npckraj",
        List.of("kraje", "ai", "dyplomacja", "zasoby"),
        List.of(
            new HelpBook.Entry("stworz", "kraje", "/nk stworz <nazwa> <kolor> [władca]", "/nk stworz ", true),
            new HelpBook.Entry("usun", "kraje", "/nk usun <kraj> potwierdz", "/nk usun ", true),
            new HelpBook.Entry("wladca", "kraje", "/nk wladca <kraj> <imię> [skin]", "/nk wladca ", true),
            new HelpBook.Entry("info", "kraje", "/nk info <kraj>", "/nk info ", true),
            new HelpBook.Entry("lista", "kraje", "/nk lista", "/nk lista", true),
            new HelpBook.Entry("tp", "kraje", "/nk tp <kraj>", "/nk tp ", true),
            new HelpBook.Entry("wioska", "kraje", "/nk wioska [nazwa] [kolor] [władca] | potwierdz", "/nk wioska", true),
            new HelpBook.Entry("scenariusz", "kraje", "/nk scenariusz lista|pokaz|zaladuj <id> [potwierdz]", "/nk scenariusz ", true),
            new HelpBook.Entry("ustaw", "ai", "/nk ustaw <kraj> <parametr> <wartość>", "/nk ustaw ", true),
            new HelpBook.Entry("pauza", "ai", "/nk pauza <kraj|wszystkie>", "/nk pauza ", true),
            new HelpBook.Entry("wznow", "ai", "/nk wznow <kraj|wszystkie>", "/nk wznow ", true),
            new HelpBook.Entry("tick", "ai", "/nk tick <kraj>", "/nk tick ", true),
            new HelpBook.Entry("log", "ai", "/nk log <kraj> [ile]", "/nk log ", true),
            new HelpBook.Entry("desant", "ai", "/nk desant <kraj> [cel] | status | anuluj | teraz", "/nk desant ", true),
            new HelpBook.Entry("wojny", "dyplomacja", "/nk wojny [kraj]", "/nk wojny", true),
            new HelpBook.Entry("wojna", "dyplomacja", "/nk wojna <kraj A> <kraj B>", "/nk wojna ", true),
            new HelpBook.Entry("pokoj", "dyplomacja", "/nk pokoj <kraj A> <kraj B>", "/nk pokoj ", true),
            new HelpBook.Entry("sojusz", "dyplomacja", "/nk sojusz <kraj A> <kraj B>", "/nk sojusz ", true),
            new HelpBook.Entry("skarbiec", "zasoby", "/nk skarbiec <kraj> daj|zabierz <kwota>", "/nk skarbiec ", true),
            new HelpBook.Entry("armia", "zasoby", "/nk armia <kraj> dodaj <typ> <n>", "/nk armia ", true),
            new HelpBook.Entry("armia-rozpusc", "zasoby", "/nk armia <kraj> rozpusc", "/nk armia ", true)));

    private final Plugin plugin;
    private final Services services;
    private final ArmyService army;
    private final NpcAiService ai;
    private final RulerService rulers;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;

    public NpcKrajCommand(final Plugin plugin, final Services services, final ArmyService army, final NpcAiService ai,
                          final RulerService rulers, final Supplier<ArmiaConfig> config, final Supplier<Messages> messages) {
        this.plugin = plugin;
        this.services = services;
        this.army = army;
        this.ai = ai;
        this.rulers = rulers;
        this.config = config;
        this.messages = messages;
    }

    private Messages m() {
        return this.messages.get();
    }

    // ------------------------------------------------------------------ tree

    public LiteralCommandNode<CommandSourceStack> build() {
        final SuggestionProvider<CommandSourceStack> npc = this.countries(true, false);
        final SuggestionProvider<CommandSourceStack> any = this.countries(false, false);
        final SuggestionProvider<CommandSourceStack> npcOrAll = this.countries(true, true);
        return Commands.literal("npckraj")
            .requires(s -> s.getSender().hasPermission(ArmiaCommand.PERM_ADMIN))
            .executes(ctx -> this.help(ctx, null))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.help(ctx, null))
                .then(Commands.argument("strona", StringArgumentType.greedyString())
                    .executes(ctx -> this.help(ctx, StringArgumentType.getString(ctx, "strona")))))
            .then(Commands.literal("stworz")
                .executes(ctx -> this.usage(ctx, "stworz"))
                .then(Commands.argument("nazwa", StringArgumentType.string())
                    .then(Commands.argument("kolor", StringArgumentType.word())
                        .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                            for (final Map.Entry<String, Integer> e : COLORS.entrySet()) {
                                CommandSupport.add(b, e.getKey(), Component.text("██████ " + e.getKey(), net.kyori.adventure.text.format.TextColor.color(e.getValue())));
                            }
                            CommandSupport.add(b, "A83232", this.m().get("suggest-hex"));
                        }))
                        .executes(ctx -> this.create(ctx, null))
                        .then(Commands.argument("władca", StringArgumentType.greedyString())
                            .executes(ctx -> this.create(ctx, StringArgumentType.getString(ctx, "władca")))))))
            .then(Commands.literal("usun")
                .executes(ctx -> this.usage(ctx, "usun"))
                .then(this.country("kraj", npc)
                    .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.m().send(s, "nk-delete-confirm",
                        Placeholder.unparsed("name", c.name()), Messages.command("/nk usun " + CommandSupport.quoted(c.name()) + " potwierdz"))))
                    .then(Commands.literal("potwierdz").executes(ctx -> this.withNpc(ctx, "kraj", this::delete)))))
            .then(Commands.literal("ustaw")
                .executes(ctx -> this.usage(ctx, "ustaw"))
                .then(this.country("kraj", npc)
                    .then(Commands.argument("parametr", StringArgumentType.word())
                        .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                            for (final String p : Personality.PARAMS) {
                                CommandSupport.add(b, p, this.m().get("nk-param." + p));
                            }
                            CommandSupport.add(b, Personality.HONOR, this.m().get("nk-param.honor"));
                        }))
                        .then(Commands.argument("wartość", StringArgumentType.word())
                            .suggests(CommandSupport.provider(this.plugin, this::suggestValues))
                            .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.set(s, c,
                                StringArgumentType.getString(ctx, "parametr"), StringArgumentType.getString(ctx, "wartość"))))))))
            .then(Commands.literal("wladca")
                .executes(ctx -> this.usage(ctx, "wladca"))
                .then(this.country("kraj", npc)
                    .then(Commands.argument("imię", StringArgumentType.string())
                        .executes(ctx -> this.ruler(ctx, null))
                        .then(Commands.argument("skin", StringArgumentType.word())
                            .executes(ctx -> this.ruler(ctx, StringArgumentType.getString(ctx, "skin")))))))
            .then(Commands.literal("skarbiec")
                .executes(ctx -> this.usage(ctx, "skarbiec"))
                .then(this.country("kraj", any)
                    .then(this.money("daj", true))
                    .then(this.money("zabierz", false))))
            .then(Commands.literal("armia")
                .executes(ctx -> this.usage(ctx, "armia"))
                .then(this.country("kraj", any)
                    .then(Commands.literal("dodaj")
                        .then(Commands.argument("typ", StringArgumentType.word())
                            .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                                for (final UnitType u : this.config.get().units.values()) {
                                    CommandSupport.add(b, u.id(), this.m().parse(u.name()));
                                }
                            }))
                            .then(Commands.argument("n", IntegerArgumentType.integer(1, 100))
                                .executes(this::addSoldiers))))
                    .then(Commands.literal("rozpusc").executes(ctx -> this.withCountry(ctx, "kraj", (s, c) -> {
                        final int n = this.army.registry().ofCountry(c.id()).size();
                        this.army.removeCountry(c.id());
                        this.m().send(s, "nk-army-cleared", Placeholder.unparsed("count", Integer.toString(n)), Placeholder.unparsed("name", c.name()));
                    })))))
            .then(this.relation("wojna", Relation.WAR, any))
            .then(this.relation("pokoj", Relation.NEUTRAL, any))
            .then(this.relation("sojusz", Relation.ALLIANCE, any))
            .then(Commands.literal("pauza")
                .executes(ctx -> this.usage(ctx, "pauza"))
                .then(this.country("kraj", npcOrAll).executes(ctx -> this.pause(ctx, true))))
            .then(Commands.literal("wznow")
                .executes(ctx -> this.usage(ctx, "wznow"))
                .then(this.country("kraj", npcOrAll).executes(ctx -> this.pause(ctx, false))))
            .then(Commands.literal("info")
                .executes(ctx -> this.usage(ctx, "info"))
                .then(this.country("kraj", npc).executes(ctx -> this.withNpc(ctx, "kraj", this::info))))
            .then(Commands.literal("lista").executes(this::list))
            .then(Commands.literal("wojny")
                .executes(ctx -> this.wars(ctx.getSource().getSender(), null))
                .then(this.country("kraj", any).executes(ctx -> this.withCountry(ctx, "kraj", (s, c) -> this.wars(s, c.id())))))
            .then(Commands.literal("tp")
                .executes(ctx -> this.usage(ctx, "tp"))
                .then(this.country("kraj", npc).executes(ctx -> this.withNpc(ctx, "kraj", this::teleport))))
            .then(this.scenarioTree())
            .then(Commands.literal("wioska")
                .executes(ctx -> this.village(ctx, null, null, null))
                .then(Commands.literal("potwierdz").executes(ctx -> {
                    if (this.villages != null) {
                        this.villages.confirm(ctx.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.argument("nazwa", StringArgumentType.string())
                    .executes(ctx -> this.village(ctx, StringArgumentType.getString(ctx, "nazwa"), null, null))
                    .then(Commands.argument("kolor", StringArgumentType.word())
                        .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                            for (final Map.Entry<String, Integer> e : COLORS.entrySet()) {
                                CommandSupport.add(b, e.getKey(), Component.text("██████ " + e.getKey(), net.kyori.adventure.text.format.TextColor.color(e.getValue())));
                            }
                        }))
                        .executes(ctx -> this.village(ctx, StringArgumentType.getString(ctx, "nazwa"), StringArgumentType.getString(ctx, "kolor"), null))
                        .then(Commands.argument("władca", StringArgumentType.greedyString())
                            .executes(ctx -> this.village(ctx, StringArgumentType.getString(ctx, "nazwa"), StringArgumentType.getString(ctx, "kolor"),
                                StringArgumentType.getString(ctx, "władca")))))))
            .then(Commands.literal("tick")
                .executes(ctx -> this.usage(ctx, "tick"))
                .then(this.country("kraj", npc).executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> {
                    this.ai.tick(c.id(), true);
                    this.m().send(s, "nk-ticked", Placeholder.unparsed("name", c.name()));
                    this.printLog(s, c, 6);
                }))))
            .then(Commands.literal("desant")
                .executes(ctx -> this.usage(ctx, "desant"))
                .then(this.country("kraj", npc)
                    .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.landing(s, c, null)))
                    .then(Commands.literal("status").executes(ctx -> this.withNpc(ctx, "kraj", this::landingStatus)))
                    .then(Commands.literal("anuluj").executes(ctx -> this.withNpc(ctx, "kraj", (s, c) ->
                        this.landingResult(s, c, this.ai.naval() == null ? "flota niedostępna" : this.ai.naval().cancel(c.id())))))
                    .then(Commands.literal("teraz").executes(ctx -> this.withNpc(ctx, "kraj", (s, c) ->
                        this.landingResult(s, c, this.ai.naval() == null ? "flota niedostępna" : this.ai.naval().hurry(c.id())))))
                    .then(this.country("cel", any)
                        .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.landing(s, c, StringArgumentType.getString(ctx, "cel")))))))
            .then(Commands.literal("log")
                .executes(ctx -> this.usage(ctx, "log"))
                .then(this.country("kraj", npc)
                    .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.printLog(s, c, 15)))
                    .then(Commands.argument("ile", IntegerArgumentType.integer(1, 200))
                        .executes(ctx -> this.withNpc(ctx, "kraj", (s, c) -> this.printLog(s, c, IntegerArgumentType.getInteger(ctx, "ile")))))))
            .build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> country(final String name, final SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument(name, StringArgumentType.string()).suggests(suggestions);
    }

    private SuggestionProvider<CommandSourceStack> countries(final boolean npcOnly, final boolean withAll) {
        return CommandSupport.provider(this.plugin, (ctx, b) -> {
            if (withAll) {
                CommandSupport.add(b, "wszystkie", this.m().get("suggest-all-npc"));
            }
            for (final KrajInfo c : this.services.kraje().getCountries()) {
                if (npcOnly && !c.npc()) {
                    continue;
                }
                CommandSupport.add(b, CommandSupport.quoted(c.name()), this.m().get(c.npc() ? "suggest-npc-country" : "suggest-country",
                    Placeholder.component("country", Component.text(c.name(), c.color())),
                    Placeholder.unparsed("soldiers", Integer.toString(this.army.registry().count(c.id()))),
                    Placeholder.unparsed("chunks", Integer.toString(c.chunkCount())),
                    Placeholder.unparsed("status", this.status(c.id()))));
            }
        });
    }

    private void suggestValues(final CommandContext<CommandSourceStack> ctx, final SuggestionsBuilder b) {
        final String param = CommandSupport.optStr(ctx, "parametr").orElse("").toLowerCase(Locale.ROOT);
        final List<String> values = switch (param) {
            case "aktywna" -> List.of("tak", "nie");
            case "max-chunki" -> List.of("10", "20", "40", "80");
            case "max-zolnierze" -> List.of("5", "10", "20", "40");
            case "honor" -> List.of("auto", "20", "50", "80");
            default -> List.of("0", "25", "50", "75", "100");
        };
        if (Personality.HONOR.equals(param)) {
            CommandSupport.optStr(ctx, "kraj").flatMap(n -> CommandSupport.country(this.services.kraje(), n)).flatMap(c -> this.ai.state(c.id()))
                .ifPresent(st -> CommandSupport.add(b, st.honor == null ? "auto" : Integer.toString(st.honor()), this.m().get("suggest-current")));
            for (final String v : values) {
                CommandSupport.add(b, v, null);
            }
            return;
        }
        CommandSupport.optStr(ctx, "kraj").flatMap(n -> CommandSupport.country(this.services.kraje(), n)).flatMap(c -> this.ai.state(c.id()))
            .ifPresent(st -> CommandSupport.add(b, st.personality().get(param), this.m().get("suggest-current")));
        for (final String v : values) {
            CommandSupport.add(b, v, null);
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> money(final String literal, final boolean give) {
        return Commands.literal(literal)
            .then(Commands.argument("kwota", StringArgumentType.greedyString())
                .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                    for (final String s : new String[] {"50", "1zł", "10zł", "100zł"}) {
                        CommandSupport.add(b, s, this.m().get("suggest-money"));
                    }
                }))
                .executes(ctx -> this.withCountry(ctx, "kraj", (s, c) -> {
                    final String raw = StringArgumentType.getString(ctx, "kwota");
                    final Optional<Long> amount = this.services.skarbiec().parse(raw);
                    if (amount.isEmpty() || amount.get() <= 0) {
                        this.m().send(s, "bad-amount", Placeholder.unparsed("input", raw));
                        return;
                    }
                    final String account = this.services.treasury(c.id());
                    final TxResult r = give
                        ? this.services.skarbiec().deposit(account, amount.get(), "Admin " + s.getName())
                        : this.services.skarbiec().withdraw(account, amount.get(), "Admin " + s.getName());
                    this.m().send(s, r.ok() ? "nk-treasury" : "nk-treasury-failed",
                        Placeholder.unparsed("name", c.name()), Placeholder.unparsed("result", r.name()),
                        Placeholder.unparsed("balance", this.services.money(this.services.skarbiec().balance(account))));
                })));
    }

    private LiteralArgumentBuilder<CommandSourceStack> relation(final String literal, final Relation relation, final SuggestionProvider<CommandSourceStack> any) {
        return Commands.literal(literal)
            .executes(ctx -> this.usage(ctx, literal))
            .then(this.country("a", any)
                .then(this.country("b", any)
                    .executes(ctx -> this.withCountry(ctx, "a", (s, a) -> {
                        final Optional<KrajInfo> b = CommandSupport.country(this.services.kraje(), StringArgumentType.getString(ctx, "b"));
                        if (b.isEmpty()) {
                            this.m().send(s, "unknown-country", Placeholder.unparsed("name", StringArgumentType.getString(ctx, "b")));
                            return;
                        }
                        final KrajeResult r = this.services.kraje().setRelation(a.id(), b.get().id(), relation);
                        this.m().send(s, r.ok() ? "nk-relation" : "kraje-failed", Placeholder.unparsed("a", a.name()), Placeholder.unparsed("b", b.get().name()),
                            Placeholder.unparsed("relation", relation.displayName()), Placeholder.unparsed("reason", r.status() + " " + r.message()));
                        if (r.ok()) {
                            this.ai.log(a.id(), "Admin: relacja z " + b.get().name() + " → " + relation.displayName());
                        }
                    }))));
    }

    // ------------------------------------------------------------------ actions

    private int create(final CommandContext<CommandSourceStack> ctx, final String rulerName) {
        final CommandSender s = ctx.getSource().getSender();
        // Console (or another non-player) creates the country at the source location — the main world's spawn for the console.
        final org.bukkit.Location where = s instanceof Player p ? p.getLocation() : ctx.getSource().getLocation();
        final String name = StringArgumentType.getString(ctx, "nazwa");
        final Integer rgb = parseColor(StringArgumentType.getString(ctx, "kolor"));
        if (rgb == null) {
            this.m().send(s, "bad-color", Placeholder.unparsed("input", StringArgumentType.getString(ctx, "kolor")));
            return Command.SINGLE_SUCCESS;
        }
        final List<String> names = this.config.get().rulerNames;
        final String ruler = rulerName == null || rulerName.isBlank() ? names.get(ThreadLocalRandom.current().nextInt(names.size())) : rulerName.trim();
        final KrajeResult r = this.services.kraje().createNpcCountry(name, rgb, where, ruler, this.config.get().npcCreateForce);
        if (!r.ok() || r.countryId() == null) {
            this.m().send(s, "kraje-failed", Placeholder.unparsed("reason", r.status() + " " + r.message()));
            return Command.SINGLE_SUCCESS;
        }
        final NpcState st = this.ai.ensure(r.countryId());
        st.nextTickAt = System.currentTimeMillis() + 30_000L;
        this.rulers.place(r.countryId(), ruler, this.config.get().rulerDefaultSkin, where);
        this.ai.log(r.countryId(), "Utworzono kraj NPC (admin " + s.getName() + "), władca " + ruler);
        this.m().send(s, "nk-created", Placeholder.unparsed("name", name), Placeholder.unparsed("ruler", ruler));
        return Command.SINGLE_SUCCESS;
    }

    static Integer parseColor(final String input) {
        final String in = HelpBook.normalize(input).replace("#", "");
        if (COLORS.containsKey(in)) {
            return COLORS.get(in);
        }
        if (in.matches("[0-9a-f]{6}")) {
            return Integer.parseInt(in, 16);
        }
        return null;
    }

    private void delete(final CommandSender s, final KrajInfo c) {
        final KrajeResult r = this.services.kraje().deleteCountry(c.id());
        if (r.ok()) {
            // CountryDisbandEvent also cleans up; do it here too in case the event is not fired.
            this.army.removeCountry(c.id());
            this.rulers.remove(c.id());
            this.ai.forget(c.id());
        }
        this.m().send(s, r.ok() ? "nk-deleted" : "kraje-failed", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("reason", r.status() + " " + r.message()));
    }

    private void set(final CommandSender s, final KrajInfo c, final String param, final String value) {
        final NpcState st = this.ai.ensure(c.id());
        try {
            if (Personality.HONOR.equalsIgnoreCase(param)) {
                st.honor = Personality.honorValue(value);
                final String shown = st.honor() + (st.honor == null ? " (auto)" : "");
                this.ai.log(c.id(), "Admin: honor = " + shown);
                this.m().send(s, "nk-set", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("param", Personality.HONOR),
                    Placeholder.unparsed("value", shown));
                return;
            }
            final Personality p = st.personality().with(param, value);
            st.apply(p);
            this.ai.log(c.id(), "Admin: " + param + " = " + p.get(param.toLowerCase(Locale.ROOT)));
            this.m().send(s, "nk-set", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("param", param),
                Placeholder.unparsed("value", p.get(param.toLowerCase(Locale.ROOT))));
        } catch (final IllegalArgumentException e) {
            this.m().send(s, "nk-set-failed", Placeholder.unparsed("reason", e.getMessage()));
        }
    }

    private int ruler(final CommandContext<CommandSourceStack> ctx, final String skin) {
        return this.withNpc(ctx, "kraj", (s, c) -> {
            if (!(s instanceof Player p)) {
                this.m().send(s, "players-only");
                return;
            }
            final String name = StringArgumentType.getString(ctx, "imię");
            this.rulers.place(c.id(), name, skin == null ? this.config.get().rulerDefaultSkin : skin, p.getLocation());
            this.m().send(s, "nk-ruler", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("ruler", name));
        });
    }

    private int addSoldiers(final CommandContext<CommandSourceStack> ctx) {
        return this.withCountry(ctx, "kraj", (s, c) -> {
            if (!(s instanceof Player p)) {
                this.m().send(s, "players-only");
                return;
            }
            final UnitType u = this.config.get().unit(StringArgumentType.getString(ctx, "typ"));
            if (u == null) {
                this.m().send(s, "unknown-unit", Placeholder.unparsed("type", StringArgumentType.getString(ctx, "typ")),
                    Placeholder.unparsed("types", String.join(", ", this.config.get().units.keySet())));
                return;
            }
            final int n = IntegerArgumentType.getInteger(ctx, "n");
            final int added = this.army.addSoldiers(c.id(), u, n, Pos.of(p.getLocation()), this.config.get().guardRadiusDefault, false);
            this.m().send(s, "nk-army-added", Placeholder.unparsed("count", Integer.toString(added)), Placeholder.parsed("unit", u.name()),
                Placeholder.unparsed("name", c.name()));
        });
    }

    private int pause(final CommandContext<CommandSourceStack> ctx, final boolean paused) {
        final CommandSender s = ctx.getSource().getSender();
        final String arg = StringArgumentType.getString(ctx, "kraj");
        if (arg.equalsIgnoreCase("wszystkie")) {
            this.ai.setGlobalPaused(paused);
            this.m().send(s, paused ? "nk-paused-all" : "nk-resumed-all");
            return Command.SINGLE_SUCCESS;
        }
        return this.withNpc(ctx, "kraj", (sender, c) -> {
            final NpcState st = this.ai.ensure(c.id());
            st.paused = paused;
            if (!paused) {
                st.nextTickAt = Math.min(st.nextTickAt, System.currentTimeMillis() + 10_000L);
            }
            this.ai.log(c.id(), paused ? "Admin: pauza" : "Admin: wznowiono");
            this.m().send(sender, paused ? "nk-paused" : "nk-resumed", Placeholder.unparsed("name", c.name()));
        });
    }

    private String status(final UUID country) {
        final Optional<NpcState> st = this.ai.state(country);
        if (st.isEmpty()) {
            return this.m().plain("status-none");
        }
        if (!this.config.get().aiEnabled || this.ai.globalPaused()) {
            return this.m().plain("status-global-paused");
        }
        if (!st.get().active) {
            return this.m().plain("status-inactive");
        }
        return st.get().paused ? this.m().plain("status-paused") : this.m().plain("status-active");
    }

    private void info(final CommandSender s, final KrajInfo c) {
        final NpcState st = this.ai.ensure(c.id());
        final Personality p = st.personality();
        final List<Soldier> soldiers = this.army.registry().ofCountry(c.id());
        final long loaded = soldiers.stream().filter(Soldier::attached).count();
        final long next = Math.max(0, st.nextTickAt - System.currentTimeMillis()) / 60_000L;
        final Messages m = this.m();
        m.send(s, "nk-info-header", Placeholder.component("country", Component.text(c.name(), c.color())),
            Placeholder.unparsed("ruler", c.rulerName() == null ? "?" : c.rulerName()), Placeholder.unparsed("status", this.status(c.id())));
        m.send(s, "nk-info-traits", Placeholder.unparsed("agresja", Integer.toString(p.aggression())), Placeholder.unparsed("ekspansja", Integer.toString(p.expansion())),
            Placeholder.unparsed("dyplomacja", Integer.toString(p.diplomacy())), Placeholder.unparsed("bogactwo", Integer.toString(p.wealth())),
            Placeholder.unparsed("maxchunks", Integer.toString(p.maxChunks())), Placeholder.unparsed("maxsoldiers", Integer.toString(p.maxSoldiers())));
        m.send(s, "nk-info-state", Placeholder.unparsed("chunks", Integer.toString(c.chunkCount())),
            Placeholder.unparsed("soldiers", Integer.toString(soldiers.size())), Placeholder.unparsed("loaded", Long.toString(loaded)),
            Placeholder.unparsed("cap", Integer.toString(this.army.countryCap(c))),
            Placeholder.unparsed("treasury", this.services.money(this.services.skarbiec().balance(this.services.treasury(c.id())))),
            Placeholder.unparsed("population", Integer.toString(this.services.population().population(c.id()))),
            Placeholder.unparsed("housing", Integer.toString(this.services.population().housing(c.id()))),
            Placeholder.unparsed("strength", String.format(Locale.ROOT, "%.1f", this.ai.strength(c.id()))),
            Placeholder.unparsed("next", Long.toString(next)));
        m.send(s, "nk-info-relations", Placeholder.unparsed("allies", this.names(c.allies())), Placeholder.unparsed("enemies", this.names(c.enemies())));
        this.printLog(s, c, 5);
    }

    private String names(final java.util.Set<UUID> ids) {
        if (ids.isEmpty()) {
            return "-";
        }
        return String.join(", ", ids.stream().map(id -> this.services.kraje().getCountry(id).map(KrajInfo::name).orElse("?")).toList());
    }

    /** {@code /nk wojny [kraj]}: wars of NPC countries with background offensives, progress and exhaustion. */
    private int wars(final CommandSender sender, final java.util.UUID only) {
        if (this.ai.conquest() == null || this.ai.conquest().state() == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Wojny w tle nie są gotowe."));
            return 0;
        }
        this.ai.conquest().describe(sender, only);
        return 1;
    }

    private int list(final CommandContext<CommandSourceStack> ctx) {
        final CommandSender s = ctx.getSource().getSender();
        final List<KrajInfo> npcs = this.services.kraje().getCountries().stream().filter(KrajInfo::npc).toList();
        this.m().send(s, "nk-list-header", Placeholder.unparsed("count", Integer.toString(npcs.size())),
            Placeholder.unparsed("global", this.ai.globalPaused() ? this.m().plain("status-global-paused") : this.m().plain("status-active")));
        for (final KrajInfo c : npcs) {
            this.m().send(s, "nk-list-line", Placeholder.component("country", Component.text(c.name(), c.color())),
                Placeholder.unparsed("chunks", Integer.toString(c.chunkCount())),
                Placeholder.unparsed("soldiers", Integer.toString(this.army.registry().count(c.id()))),
                Placeholder.unparsed("treasury", this.services.money(this.services.skarbiec().balance(this.services.treasury(c.id())))),
                Placeholder.unparsed("status", this.status(c.id())),
                Messages.command("/nk info " + CommandSupport.quoted(c.name())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private void printLog(final CommandSender s, final KrajInfo c, final int n) {
        final List<DecisionLog.Entry> entries = this.ai.log(c.id()).last(n);
        this.m().send(s, "nk-log-header", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("count", Integer.toString(entries.size())));
        for (final DecisionLog.Entry e : entries) {
            this.m().send(s, "nk-log-line", Placeholder.unparsed("time", TIME.format(Instant.ofEpochMilli(e.time()))), Placeholder.unparsed("text", e.text()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private int withCountry(final CommandContext<CommandSourceStack> ctx, final String arg, final BiConsumer<CommandSender, KrajInfo> action) {
        final CommandSender s = ctx.getSource().getSender();
        final String name = StringArgumentType.getString(ctx, arg);
        final Optional<KrajInfo> c = CommandSupport.country(this.services.kraje(), name);
        if (c.isEmpty()) {
            this.m().send(s, "unknown-country", Placeholder.unparsed("name", name));
        } else {
            action.accept(s, c.get());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int withNpc(final CommandContext<CommandSourceStack> ctx, final String arg, final BiConsumer<CommandSender, KrajInfo> action) {
        return this.withCountry(ctx, arg, (s, c) -> {
            if (!c.npc()) {
                this.m().send(s, "not-npc", Placeholder.unparsed("name", c.name()));
                return;
            }
            action.accept(s, c);
        });
    }

    // ------------------------------------------------------------------ teleport & scenarios

    private pl.medivalrpg.armia.scenario.ScenarioService scenarios;
    private pl.medivalrpg.armia.api.ArmiaApi api;

    /** Wiring after construction (the API object and scenarios need the command-independent services). */
    public void setExtras(final pl.medivalrpg.armia.scenario.ScenarioService scenarios, final pl.medivalrpg.armia.api.ArmiaApi api) {
        this.scenarios = scenarios;
        this.api = api;
    }

    /** {@code /nk tp <kraj>}: next to the ruler in the capital, on a safe block (chunk loaded asynchronously). */
    private void teleport(final CommandSender s, final KrajInfo c) {
        if (!(s instanceof org.bukkit.entity.Player p)) {
            this.m().send(s, "players-only");
            return;
        }
        final org.bukkit.World w = org.bukkit.Bukkit.getWorld(c.capitalWorld());
        if (w == null || this.api == null) {
            this.m().send(s, "nk-tp-failed", Placeholder.unparsed("name", c.name()));
            return;
        }
        final java.util.Optional<pl.medivalrpg.armia.model.RulerRecord> ruler = this.rulers.of(c.id());
        final int cx = ruler.isPresent() && ruler.get().pos != null ? ruler.get().pos.chunkX() : c.capitalX();
        final int cz = ruler.isPresent() && ruler.get().pos != null ? ruler.get().pos.chunkZ() : c.capitalZ();
        w.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> org.bukkit.Bukkit.getScheduler().runTask(this.plugin, () -> {
            final java.util.Optional<org.bukkit.Location> spot = error == null ? this.api.npcCapitalSpot(c.id()) : java.util.Optional.empty();
            if (spot.isEmpty() || !p.isOnline()) {
                this.m().send(p, "nk-tp-failed", Placeholder.unparsed("name", c.name()));
                return;
            }
            p.teleportAsync(spot.get(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (ok) {
                    this.m().send(p, "nk-tp-done", Placeholder.component("country", Component.text(c.name(), c.color())));
                }
            });
        }));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> scenarioTree() {
        final SuggestionProvider<CommandSourceStack> ids = CommandSupport.provider(this.plugin, (ctx, b) -> {
            if (this.scenarios != null) {
                for (final String id : this.scenarios.ids()) {
                    CommandSupport.add(b, id, null);
                }
            }
        });
        return Commands.literal("scenariusz")
            .executes(ctx -> this.usage(ctx, "scenariusz"))
            .then(Commands.literal("lista").executes(ctx -> {
                this.scenarioList(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            }))
            .then(Commands.literal("pokaz")
                .executes(ctx -> this.usage(ctx, "scenariusz"))
                .then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
                    .executes(ctx -> this.scenario(ctx, (s, sc) -> this.scenarios.show(s, sc)))))
            .then(Commands.literal("zaladuj")
                .executes(ctx -> this.usage(ctx, "scenariusz"))
                .then(Commands.argument("id", StringArgumentType.word()).suggests(ids)
                    .executes(ctx -> this.scenario(ctx, (s, sc) -> {
                        this.scenarios.show(s, sc);
                        this.m().send(s, "nk-scenario-confirm", Placeholder.unparsed("id", sc.id()),
                            Messages.command("/nk scenariusz zaladuj " + sc.id() + " potwierdz"));
                    }))
                    .then(Commands.literal("potwierdz")
                        .executes(ctx -> this.scenario(ctx, (s, sc) -> {
                            final List<String> errors = this.scenarios.validate(sc);
                            if (!errors.isEmpty()) {
                                this.m().send(s, "nk-scenario-invalid", Placeholder.unparsed("errors", String.join("; ", errors)));
                                return;
                            }
                            if (!this.scenarios.start(s, sc)) {
                                this.m().send(s, "nk-scenario-busy");
                            }
                        })))));
    }

    private void scenarioList(final CommandSender s) {
        if (this.scenarios == null || this.scenarios.ids().isEmpty()) {
            this.m().send(s, "nk-scenario-none");
            return;
        }
        this.m().send(s, "nk-scenario-list-header");
        for (final String id : this.scenarios.ids()) {
            String name = id;
            int count = 0;
            try {
                final java.util.Optional<pl.medivalrpg.armia.logic.Scenario> sc = this.scenarios.load(id);
                if (sc.isPresent()) {
                    name = sc.get().name();
                    count = sc.get().countries().size();
                }
            } catch (final IllegalArgumentException e) {
                name = id + " (błąd: " + e.getMessage() + ")";
            }
            this.m().send(s, "nk-scenario-list-line", Placeholder.unparsed("id", id), Placeholder.unparsed("name", name),
                Placeholder.unparsed("count", Integer.toString(count)), Messages.command("/nk scenariusz pokaz " + id));
        }
    }

    private int scenario(final CommandContext<CommandSourceStack> ctx, final BiConsumer<CommandSender, pl.medivalrpg.armia.logic.Scenario> action) {
        final CommandSender s = ctx.getSource().getSender();
        final String id = StringArgumentType.getString(ctx, "id");
        if (this.scenarios == null) {
            this.m().send(s, "nk-scenario-none");
            return Command.SINGLE_SUCCESS;
        }
        try {
            final java.util.Optional<pl.medivalrpg.armia.logic.Scenario> sc = this.scenarios.load(id);
            if (sc.isEmpty()) {
                this.m().send(s, "nk-scenario-unknown", Placeholder.unparsed("id", id));
            } else {
                action.accept(s, sc.get());
            }
        } catch (final IllegalArgumentException e) {
            this.m().send(s, "nk-scenario-invalid", Placeholder.unparsed("errors", e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private pl.medivalrpg.armia.village.VillageService villages;

    public void setVillages(final pl.medivalrpg.armia.village.VillageService villages) {
        this.villages = villages;
    }

    /** {@code /nk wioska [nazwa] [kolor] [władca]}: preview of the village where the admin stands. */
    private int village(final CommandContext<CommandSourceStack> ctx, final String name, final String color, final String ruler) {
        final CommandSender s = ctx.getSource().getSender();
        if (!(s instanceof org.bukkit.entity.Player p)) {
            this.m().send(s, "players-only");
            return Command.SINGLE_SUCCESS;
        }
        Integer rgb = null;
        if (color != null) {
            rgb = parseColor(color);
            if (rgb == null) {
                this.m().send(s, "bad-color", Placeholder.unparsed("input", color));
                return Command.SINGLE_SUCCESS;
            }
        }
        if (this.villages != null) {
            this.villages.preview(s, p.getLocation(), name, rgb, ruler);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(final CommandContext<CommandSourceStack> ctx, final String page) {
        for (final Component line : HELP.render(this.m(), true, page)) {
            ctx.getSource().getSender().sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ long-range landing (/nk desant)

    /** Orders an immediate long-range expedition of {@code c} against {@code targetName} (default: siege target / an enemy). */
    private void landing(final CommandSender s, final KrajInfo c, final String targetName) {
        final pl.medivalrpg.armia.ai.NavalAiService naval = this.ai.naval();
        if (naval == null) {
            this.landingResult(s, c, "flota niedostępna");
            return;
        }
        KrajInfo target = null;
        if (targetName != null) {
            target = CommandSupport.country(this.services.kraje(), targetName).orElse(null);
            if (target == null) {
                this.m().send(s, "unknown-country", Placeholder.unparsed("name", targetName));
                return;
            }
        } else {
            final NpcState st = this.ai.ensure(c.id());
            final java.util.List<UUID> order = this.ai.siegeOrder(c, st);
            if (st.siegeTarget != null && c.enemies().contains(st.siegeTarget)) {
                target = this.services.kraje().getCountry(st.siegeTarget).orElse(null);
            }
            if (target == null && !order.isEmpty()) {
                target = this.services.kraje().getCountry(order.get(0)).orElse(null);
            }
        }
        if (target == null) {
            this.m().send(s, "nk-desant-no-target", Placeholder.unparsed("name", c.name()));
            return;
        }
        final pl.medivalrpg.armia.ai.NavalAiService.LongResult r = naval.order(c, target);
        this.m().send(s, r.reason() == pl.medivalrpg.armia.logic.NavalPlanner.Reason.OK ? "nk-desant-started" : "nk-desant-refused",
            Placeholder.unparsed("name", c.name()), Placeholder.unparsed("target", target.name()), Placeholder.unparsed("detail", r.detail()));
    }

    private void landingStatus(final CommandSender s, final KrajInfo c) {
        final pl.medivalrpg.armia.ai.NavalAiService naval = this.ai.naval();
        if (naval == null) {
            this.landingResult(s, c, "flota niedostępna");
            return;
        }
        for (final String line : naval.status(c.id())) {
            this.landingResult(s, c, line);
        }
    }

    private void landingResult(final CommandSender s, final KrajInfo c, final String detail) {
        this.m().send(s, "nk-desant-result", Placeholder.unparsed("name", c.name()), Placeholder.unparsed("detail", detail));
    }

    private int usage(final CommandContext<CommandSourceStack> ctx, final String id) {
        final String syntax = HELP.entries().stream().filter(e -> e.id().equals(id)).map(HelpBook.Entry::syntax).findFirst().orElse("/nk pomoc");
        this.m().send(ctx.getSource().getSender(), "usage", Placeholder.unparsed("syntax", syntax));
        return Command.SINGLE_SUCCESS;
    }
}
