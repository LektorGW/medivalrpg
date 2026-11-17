// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.armia.config.ArmiaConfig;
import pl.medivalrpg.armia.config.Messages;
import pl.medivalrpg.armia.config.UnitType;
import pl.medivalrpg.armia.service.Services;
import pl.medivalrpg.armia.soldier.ArmyService;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;

/** {@code /armia} (alias {@code /wojsko}): players recruit and command their country's soldiers. Brigadier tree. */
public final class ArmiaCommand {

    static final String PERM_USE = "armia.use";
    static final String PERM_ADMIN = "armia.admin";

    static final HelpBook HELP = new HelpBook("help-armia", "/armia",
        List.of("wojsko", "dowodzenie", "informacje", "admin"),
        List.of(
            new HelpBook.Entry("rekrutuj", "wojsko", "/armia rekrutuj <typ> [ilość]", "/armia rekrutuj ", false),
            new HelpBook.Entry("rozpusc", "wojsko", "/armia rozpusc potwierdz", "/armia rozpusc", false),
            new HelpBook.Entry("bulawa", "wojsko", "/armia bulawa", "/armia bulawa", false),
            new HelpBook.Entry("przezbroj", "wojsko", "/armia przezbroj [typ] [ilość]", "/armia przezbroj ", false),
            new HelpBook.Entry("najmij", "wojsko", "/armia najmij <typ> [ilość]", "/armia najmij ", false),
            new HelpBook.Entry("najemnicy", "wojsko", "/armia najemnicy", "/armia najemnicy", false),
            new HelpBook.Entry("przedluz", "wojsko", "/armia przedluz [id|wszystkich]", "/armia przedluz ", false),
            new HelpBook.Entry("zwolnij-najemnikow", "wojsko", "/armia zwolnij-najemnikow", "/armia zwolnij-najemnikow", false),
            new HelpBook.Entry("zamna", "dowodzenie", "/armia zamna", "/armia zamna", false),
            new HelpBook.Entry("stoj", "dowodzenie", "/armia stoj", "/armia stoj", false),
            new HelpBook.Entry("wroc", "dowodzenie", "/armia wroc", "/armia wroc", false),
            new HelpBook.Entry("straz", "dowodzenie", "/armia straz [promień]", "/armia straz ", false),
            new HelpBook.Entry("patrol", "dowodzenie", "/armia patrol dodaj|start|wyczysc", "/armia patrol ", false),
            new HelpBook.Entry("atakuj", "dowodzenie", "/armia atakuj", "/armia atakuj", false),
            new HelpBook.Entry("ostrzal", "dowodzenie", "/armia ostrzal", "/armia ostrzal", false),
            new HelpBook.Entry("taranuj", "dowodzenie", "/armia taranuj", "/armia taranuj", false),
            new HelpBook.Entry("amunicja", "dowodzenie", "/armia amunicja <kamien|kula|kartacz>", "/armia amunicja ", false),
            new HelpBook.Entry("flota", "dowodzenie", "/armia flota lista", "/armia flota lista", false),
            new HelpBook.Entry("trybut", "informacje", "/armia trybut przyjmij|odrzuc <kraj>", "/armia trybut ", false),
            new HelpBook.Entry("info", "informacje", "/armia info [kraj]", "/armia info", false),
            new HelpBook.Entry("lista", "informacje", "/armia lista [strona]", "/armia lista", false),
            new HelpBook.Entry("pomoc", "informacje", "/armia pomoc [strona]", "/armia pomoc ", false),
            new HelpBook.Entry("przeladuj", "admin", "/armia przeladuj", "/armia przeladuj", true),
            new HelpBook.Entry("npckraj", "admin", "/npckraj pomoc", "/npckraj pomoc", true)));

    private final Plugin plugin;
    private final ArmyService army;
    private final pl.medivalrpg.armia.ai.NpcAiService ai;
    private final Services services;
    private final Supplier<ArmiaConfig> config;
    private final Supplier<Messages> messages;
    private final Runnable reload;

    public ArmiaCommand(final Plugin plugin, final ArmyService army, final pl.medivalrpg.armia.ai.NpcAiService ai, final Services services, final Supplier<ArmiaConfig> config,
                        final Supplier<Messages> messages, final Runnable reload) {
        this.plugin = plugin;
        this.army = army;
        this.ai = ai;
        this.services = services;
        this.config = config;
        this.messages = messages;
        this.reload = reload;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("armia")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(ctx -> this.help(ctx, null))
            .then(Commands.literal("pomoc")
                .executes(ctx -> this.help(ctx, null))
                .then(Commands.argument("strona", StringArgumentType.greedyString())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                        final boolean admin = ctx.getSource().getSender().hasPermission(PERM_ADMIN);
                        final List<String> pages = HELP.pages(admin);
                        for (int i = 0; i < pages.size(); i++) {
                            CommandSupport.add(b, Integer.toString(i + 1), this.messages.get().parse(HELP.categoryName(this.messages.get(), pages.get(i))));
                        }
                    }))
                    .executes(ctx -> this.help(ctx, StringArgumentType.getString(ctx, "strona")))))
            .then(Commands.literal("rekrutuj")
                .executes(ctx -> this.usage(ctx, "rekrutuj"))
                .then(Commands.argument("typ", StringArgumentType.word())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> this.suggestUnits(b)))
                    .executes(ctx -> this.player(ctx, p -> this.army.recruit(p, StringArgumentType.getString(ctx, "typ"), 1)))
                    .then(Commands.argument("ilość", IntegerArgumentType.integer(1, 64))
                        .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> this.suggestAmounts(ctx, b)))
                        .executes(ctx -> this.player(ctx, p -> this.army.recruit(p, StringArgumentType.getString(ctx, "typ"),
                            IntegerArgumentType.getInteger(ctx, "ilość")))))))
            .then(Commands.literal("lista")
                .executes(ctx -> this.player(ctx, p -> this.army.list(p, 1)))
                .then(Commands.argument("strona", IntegerArgumentType.integer(1))
                    .executes(ctx -> this.player(ctx, p -> this.army.list(p, IntegerArgumentType.getInteger(ctx, "strona"))))))
            .then(Commands.literal("zamna").executes(ctx -> this.player(ctx, this.army::gather)))
            .then(Commands.literal("stoj").executes(ctx -> this.player(ctx, this.army::hold)))
            .then(Commands.literal("wroc").executes(ctx -> this.player(ctx, this.army::returnToPost)))
            .then(Commands.literal("straz")
                .executes(ctx -> this.player(ctx, p -> this.army.guardHere(p, this.config.get().guardRadiusDefault)))
                .then(Commands.argument("promień", IntegerArgumentType.integer(1, 64))
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                        for (final int r : new int[] {4, 8, 16, (int) this.config.get().guardRadiusMax}) {
                            CommandSupport.add(b, Integer.toString(r), this.messages.get().get("suggest-radius", Placeholder.unparsed("radius", Integer.toString(r))));
                        }
                    }))
                    .executes(ctx -> this.player(ctx, p -> this.army.guardHere(p, IntegerArgumentType.getInteger(ctx, "promień"))))))
            .then(Commands.literal("patrol")
                .executes(ctx -> this.usage(ctx, "patrol"))
                .then(Commands.literal("dodaj").executes(ctx -> this.player(ctx, this.army::patrolAdd)))
                .then(Commands.literal("start").executes(ctx -> this.player(ctx, this.army::patrolStart)))
                .then(Commands.literal("wyczysc").executes(ctx -> this.player(ctx, this.army::patrolClear))))
            .then(Commands.literal("atakuj").executes(ctx -> this.player(ctx, this.army::attackLook)))
            .then(FleetCommand.build(this.army.fleet()))
            .then(Commands.literal("ostrzal").executes(ctx -> this.player(ctx, this.army::bombardLook)))
            .then(Commands.literal("taranuj").executes(ctx -> this.player(ctx, this.army::ramLook)))
            .then(Commands.literal("amunicja")
                .executes(ctx -> this.usage(ctx,"amunicja"))
                .then(Commands.argument("typ",StringArgumentType.word())
                    .suggests(CommandSupport.provider(this.plugin,(ctx,b)->{
                        for (final var ammo:pl.medivalrpg.armia.logic.SiegeRules.Ammo.values())
                            CommandSupport.add(b,ammo.id(),null);
                    }))
                    .executes(ctx -> this.player(ctx,p->this.army.ammunition(p,StringArgumentType.getString(ctx,"typ"))))))
            .then(Commands.literal("rozpusc")
                .executes(ctx -> this.player(ctx, p -> {
                    final int n = this.army.squad(p).size();
                    this.messages.get().send(p, n == 0 ? "squad-empty" : "dismiss-confirm", Placeholder.unparsed("count", Integer.toString(n)));
                }))
                .then(Commands.literal("potwierdz").executes(ctx -> this.player(ctx, this.army::dismissSquad))))
            .then(Commands.literal("bulawa").executes(ctx -> this.player(ctx, this.army::giveMace)))
            .then(Commands.literal("info")
                .executes(ctx -> this.player(ctx, p -> this.army.context(p, Rank.CITIZEN).ifPresent(c -> this.army.info(p, c))))
                .then(Commands.argument("kraj", StringArgumentType.string())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                        for (final KrajInfo c : this.services.kraje().getCountries()) {
                            CommandSupport.add(b, CommandSupport.quoted(c.name()), this.messages.get().get("suggest-country",
                                Placeholder.component("country", Component.text(c.name(), c.color())),
                                Placeholder.unparsed("soldiers", Integer.toString(this.army.registry().count(c.id())))));
                        }
                    }))
                    .executes(ctx -> {
                        final CommandSender s = ctx.getSource().getSender();
                        final Optional<KrajInfo> c = CommandSupport.country(this.services.kraje(), StringArgumentType.getString(ctx, "kraj"));
                        if (c.isEmpty()) {
                            this.messages.get().send(s, "unknown-country", Placeholder.unparsed("name", StringArgumentType.getString(ctx, "kraj")));
                        } else {
                            this.army.info(s, c.get());
                        }
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(Commands.literal("przezbroj")
                .executes(ctx -> this.player(ctx, p -> this.army.rearm(p, null, this.config.get().maxPerCommand)))
                .then(Commands.argument("typ", StringArgumentType.word())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                        if (ctx.getSource().getSender() instanceof Player p) {
                            this.services.kraje().getCountryOf(p.getUniqueId()).ifPresent(c -> {
                                for (final String type : this.army.registry().countByType(c.id()).keySet()) {
                                    this.army.upgradeOf(c.id(), type).ifPresent(up -> CommandSupport.add(b, type, this.messages.get().get("suggest-rearm",
                                        Placeholder.parsed("unit", up.name()),
                                        Placeholder.unparsed("cost", this.services.money(pl.medivalrpg.armia.logic.Eras.rearmCost(this.config.get().eraRules, up.cost()))))));
                                }
                            });
                        }
                    }))
                    .executes(ctx -> this.player(ctx, p -> this.army.rearm(p, StringArgumentType.getString(ctx, "typ"), this.config.get().maxPerCommand)))
                    .then(Commands.argument("ilość", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> this.player(ctx, p -> this.army.rearm(p, StringArgumentType.getString(ctx, "typ"),
                            IntegerArgumentType.getInteger(ctx, "ilość")))))))
            .then(Commands.literal("najmij")
                .executes(ctx -> this.usage(ctx, "najmij"))
                .then(Commands.argument("typ", StringArgumentType.word())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> this.suggestMercenaries(b)))
                    .executes(ctx -> this.player(ctx, p -> this.army.hire(p, StringArgumentType.getString(ctx, "typ"), 1)))
                    .then(Commands.argument("ilość", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> this.player(ctx, p -> this.army.hire(p, StringArgumentType.getString(ctx, "typ"),
                            IntegerArgumentType.getInteger(ctx, "ilość")))))))
            .then(Commands.literal("najemnicy").executes(ctx -> this.player(ctx, this.army::listMercenaries)))
            .then(Commands.literal("przedluz")
                .executes(ctx -> this.player(ctx, p -> this.army.extendMercenaries(p, "wszystkich")))
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                        CommandSupport.add(b, "wszystkich", this.messages.get().get("suggest-all-mercenaries"));
                        if (ctx.getSource().getSender() instanceof Player p) {
                            this.services.kraje().getCountryOf(p.getUniqueId()).ifPresent(c -> {
                                for (final pl.medivalrpg.armia.soldier.Soldier s : this.army.mercenaries(c.id())) {
                                    CommandSupport.add(b, s.id().toString().substring(0, 8), null);
                                }
                            });
                        }
                    }))
                    .executes(ctx -> this.player(ctx, p -> this.army.extendMercenaries(p, StringArgumentType.getString(ctx, "id"))))))
            .then(Commands.literal("zwolnij-najemnikow").executes(ctx -> this.player(ctx, this.army::dismissMercenaries)))
            .then(Commands.literal("trybut")
                .executes(ctx -> this.usage(ctx, "trybut"))
                .then(this.tributeAnswer("przyjmij", true))
                .then(this.tributeAnswer("odrzuc", false)))
            .then(Commands.literal("przeladuj")
                .requires(s -> s.getSender().hasPermission(PERM_ADMIN))
                .executes(ctx -> {
                    this.reload.run();
                    this.messages.get().send(ctx.getSource().getSender(), "reloaded");
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    /** {@code /armia trybut przyjmij|odrzuc <kraj>}: answer to a tribute demand of an NPC country (clickable in chat). */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tributeAnswer(final String literal, final boolean accept) {
        return Commands.literal(literal)
            .executes(ctx -> this.usage(ctx, "trybut"))
            .then(Commands.argument("kraj", StringArgumentType.string())
                .suggests(CommandSupport.provider(this.plugin, (ctx, b) -> {
                    for (final KrajInfo c : this.services.kraje().getCountries()) {
                        if (c.npc()) {
                            CommandSupport.add(b, CommandSupport.quoted(c.name()), Component.text(c.name(), c.color()));
                        }
                    }
                }))
                .executes(ctx -> this.player(ctx, p -> {
                    final String name = StringArgumentType.getString(ctx, "kraj");
                    final Optional<KrajInfo> c = CommandSupport.country(this.services.kraje(), name);
                    if (c.isEmpty() || !c.get().npc()) {
                        this.messages.get().send(p, "unknown-country", Placeholder.unparsed("name", name));
                        return;
                    }
                    this.ai.answerTributeDemand(p, c.get(), accept);
                })));
    }

    private void suggestMercenaries(final com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        for (final UnitType u : this.config.get().mercenaries.values()) {
            CommandSupport.add(b, u.id(), this.messages.get().get("suggest-mercenary",
                Placeholder.parsed("unit", u.name()),
                Placeholder.parsed("description", u.description()),
                Placeholder.unparsed("cost", this.services.money(u.cost())),
                Placeholder.unparsed("wage", this.services.money(u.wage())),
                Placeholder.unparsed("hours", Long.toString(this.config.get().mercRules.contractHours()))));
        }
    }

    private void suggestUnits(final com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        for (final UnitType u : this.config.get().units.values()) {
            CommandSupport.add(b, u.id(), this.messages.get().get("suggest-unit",
                Placeholder.parsed("unit", u.name()),
                Placeholder.parsed("description", u.description()),
                Placeholder.unparsed("cost", this.services.money(u.cost())),
                Placeholder.unparsed("wage", this.services.money(u.wage())),
                Placeholder.unparsed("hp", Integer.toString((int) u.brainHealth())),
                Placeholder.unparsed("damage", Integer.toString((int) Math.round(u.damage())))));
        }
    }

    private void suggestAmounts(final CommandContext<CommandSourceStack> ctx, final com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        final UnitType u = this.config.get().unit(StringArgumentType.getString(ctx, "typ"));
        for (final int n : new int[] {1, 3, 5, 10}) {
            if (n > this.config.get().maxPerCommand) {
                continue;
            }
            CommandSupport.add(b, Integer.toString(n), u == null ? null : this.messages.get().get("suggest-amount",
                Placeholder.unparsed("cost", this.services.money(u.cost() * n)),
                Placeholder.unparsed("wage", this.services.money(u.wage() * n))));
        }
    }

    private int help(final CommandContext<CommandSourceStack> ctx, final String page) {
        final CommandSender s = ctx.getSource().getSender();
        for (final Component line : HELP.render(this.messages.get(), s.hasPermission(PERM_ADMIN), page)) {
            s.sendMessage(line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int usage(final CommandContext<CommandSourceStack> ctx, final String id) {
        final HelpBook.Entry e = HELP.entries().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        this.messages.get().send(ctx.getSource().getSender(), "usage", Placeholder.unparsed("syntax", e.syntax()));
        return Command.SINGLE_SUCCESS;
    }

    private int player(final CommandContext<CommandSourceStack> ctx, final Consumer<Player> action) {
        if (ctx.getSource().getSender() instanceof Player p) {
            action.accept(p);
        } else {
            this.messages.get().send(ctx.getSource().getSender(), "players-only");
        }
        return Command.SINGLE_SUCCESS;
    }
}
