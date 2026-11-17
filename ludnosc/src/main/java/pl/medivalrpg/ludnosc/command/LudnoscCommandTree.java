// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * Brigadier tree of {@code /ludnosc} with Polish argument names and suggestion tooltips. Country names may contain
 * Polish letters, so country arguments are greedy strings; execution rebuilds the word array for
 * {@link LudnoscCommand#run}. Suggestions read live data on the main thread.
 */
final class LudnoscCommandTree {

    private final LudnoscCommand cmd;

    LudnoscCommandTree(final LudnoscCommand cmd) {
        this.cmd = cmd;
    }

    LiteralCommandNode<CommandSourceStack> build() {
        final Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(LudnoscCommand.PERM_ADMIN);
        final SuggestionProvider<CommandSourceStack> countries = this.provider(this::suggestCountries);
        final SuggestionProvider<CommandSourceStack> countryThenNumber = this.provider(this::suggestCountryThenNumber);
        return Commands.literal("ludnosc")
            .requires(s -> s.getSender().hasPermission(LudnoscCommand.PERM_USE))
            .executes(this.exec())
            .then(this.greedy("pomoc", "strona", this.provider(this::suggestPages)))
            .then(this.greedy("info", "kraj", countries))
            .then(this.greedy("budynki", "kraj", countries))
            .then(Commands.literal("typy").executes(this.exec("typy")))
            .then(Commands.literal("spichlerz")
                .executes(this.exec("spichlerz"))
                .then(Commands.literal("oznacz").executes(this.exec("spichlerz", "oznacz")))
                .then(Commands.literal("usun").executes(this.exec("spichlerz", "usun"))))
            .then(Commands.literal("buduj")
                .executes(this.exec("buduj"))
                .then(Commands.literal("obroc").executes(this.exec("buduj", "obroc")))
                .then(Commands.literal("wariant").executes(this.exec("buduj", "wariant")))
                .then(Commands.literal("auto").executes(this.exec("buduj", "auto")))
                .then(Commands.literal("potwierdz").executes(this.exec("buduj", "potwierdz")))
                .then(Commands.literal("anuluj").executes(this.exec("buduj", "anuluj"))
                    .then(Commands.argument("id", StringArgumentType.word()).suggests(this.provider(this::suggestOwnSites))
                        .executes(ctx -> this.run(ctx, "buduj", "anuluj", arg(ctx, "id")))))
                .then(Commands.argument("typ", StringArgumentType.word())
                    .suggests(this.provider(this::suggestTypes))
                    .executes(ctx -> this.run(ctx, "buduj", arg(ctx, "typ")))))
            .then(BlueprintCommand.node(this.cmd))
            .then(PlayerBuildingCommand.node(this.cmd))
            .then(Commands.literal("plan").executes(this.exec("plan"))
                .then(Commands.literal("krolestwo").executes(this.exec("plan", "krolestwo"))))
            .then(Commands.literal("napraw")
                .executes(this.exec("napraw"))
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(this.provider(this::suggestDamaged))
                    .executes(ctx -> this.run(ctx, "napraw", arg(ctx, "id")))))
            .then(Commands.literal("modernizuj")
                .executes(this.exec("modernizuj"))
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests(this.provider(this::suggestOutdated))
                    .executes(ctx -> this.run(ctx, "modernizuj", arg(ctx, "id")))))
            .then(Commands.literal("przebuduj").executes(this.exec("przebuduj")))
            .then(Commands.literal("magazyn").executes(this.exec("magazyn"))
                .then(Commands.argument("akcja",StringArgumentType.greedyString())
                    .suggests(this.provider((sender,builder) -> { for(String v:new String[]{"status","powiaz ","domyslny","audyt "}) add(builder,v,Component.text("Magazyn fizyczny produkcji")); }))
                    .executes(ctx -> this.run(ctx,tokens("magazyn "+arg(ctx,"akcja"))))))
            .then(Commands.literal("pora").executes(this.exec("pora")))
            .then(this.greedy("budowy", "kraj", countries))
            .then(this.greedy("gospodarka", "kraj", countries))
            .then(this.greedy("zaklady", "kraj", countries))
            .then(this.greedy("praca", "kraj", countries))
            .then(CitizenCommand.node(this.cmd, "obywatel")).then(CitizenCommand.node(this.cmd, "obywatele"))
            .then(Commands.literal("przebudowa")
                .executes(this.exec("przebudowa"))
                .then(Commands.literal("potwierdz").executes(this.exec("przebudowa", "potwierdz"))))
            .then(Commands.literal("podziemia")
                .executes(this.exec("podziemia"))
                .then(Commands.literal("kop")
                    .executes(this.exec("podziemia", "kop"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> this.run(ctx, "podziemia", "kop", arg(ctx, "id"))))))
            .then(Commands.literal("kopalnia")
                .executes(this.exec("kopalnia"))
                .then(Commands.literal("rozbuduj")
                    .executes(this.exec("kopalnia", "rozbuduj"))
                    .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> this.run(ctx, "kopalnia", "rozbuduj", arg(ctx, "id"))))))
            .then(Commands.literal("kolejka")
                .executes(this.exec("kolejka"))
                .then(this.queueAction("anuluj"))
                .then(this.queueAction("wyzej"))
                .then(this.queueAction("nizej"))
                .then(this.queueAction("pokaz")))
            .then(Commands.literal("dom")
                .requires(admin)
                .executes(this.exec("dom"))
                .then(Commands.literal("zglos").executes(this.exec("dom", "zglos")))
                .then(Commands.literal("usun").executes(this.exec("dom", "usun"))))
            .then(Commands.literal("admin")
                .requires(admin)
                .executes(this.exec("admin"))
                .then(this.royalCommand("krolestwo"))
                .then(this.royalCommand("krolewstwo"))
                .then(this.greedy("dodaj", "kraj i liczba", countryThenNumber, "admin"))
                .then(this.greedy("usun", "kraj i liczba", countryThenNumber, "admin"))
                .then(this.greedy("jedzenie", "kraj i ilość", countryThenNumber, "admin"))
                .then(this.greedy("kolejka", "kraj [wyczysc]", this.provider(this::suggestCountryThenClear), "admin"))
                .then(this.greedy("przebudowa", "kraj [podglad|teraz|anuluj|reset] [darmowo]",
                    this.provider((s, b) -> this.suggestCountryThenWords(b, "podglad", "teraz", "anuluj", "reset", "darmowo")), "admin"))
                .then(this.greedy("drogi", "kraj [darmowo]", this.provider((s, b) -> this.suggestCountryThenWords(b, "darmowo")), "admin"))
                .then(this.greedy("modernizuj", "kraj [wszystko] [darmowo]",
                    this.provider((s, b) -> this.suggestCountryThenWords(b, "wszystko", "darmowo")), "admin"))
                .then(Commands.literal("budynek")
                    .executes(this.exec("admin", "budynek"))
                    .then(Commands.literal("usun").executes(this.exec("admin", "budynek", "usun")))
                    .then(Commands.literal("anuluj")
                        .executes(this.exec("admin", "budynek", "anuluj"))
                        .then(Commands.argument("id", StringArgumentType.word())
                            .suggests(this.provider(this::suggestSites))
                            .executes(ctx -> this.run(ctx, "admin", "budynek", "anuluj", arg(ctx, "id"))))))
                .then(Commands.literal("pora")
                    .executes(this.exec("admin", "pora"))
                    .then(Commands.literal("info").executes(this.exec("admin", "pora", "info")))
                    .then(Commands.literal("ustaw")
                        .executes(this.exec("admin", "pora", "ustaw"))
                        .then(Commands.argument("pora", StringArgumentType.word())
                            .suggests(this.provider(this::suggestSeasons))
                            .executes(ctx -> this.run(ctx, "admin", "pora", "ustaw", arg(ctx, "pora")))
                            .then(Commands.argument("dzien", StringArgumentType.word())
                                .suggests(this.provider((s, b) -> {
                                    for (final String n : new String[] {"1", "3", "5", "7"}) {
                                        add(b, n, Component.text("dzień pory roku", NamedTextColor.GRAY));
                                    }
                                }))
                                .executes(ctx -> this.run(ctx, "admin", "pora", "ustaw", arg(ctx, "pora"), arg(ctx, "dzien")))))))
                .then(this.greedy("nieurodzaj", "kraj [dni|koniec]",
                    this.provider((s, b) -> this.suggestCountryThenWords(b, "koniec", "3", "5")), "admin"))
                .then(Commands.literal("praca").executes(this.exec("admin", "praca")))
                .then(Commands.literal("dzien").executes(this.exec("admin", "dzien")))
                .then(Commands.literal("wzrost").executes(this.exec("admin", "wzrost")))
                .then(Commands.literal("reload").executes(this.exec("admin", "reload"))))
            .then(Commands.argument("komenda", StringArgumentType.greedyString())
                .executes(ctx -> this.run(ctx, tokens(arg(ctx, "komenda")))))
            .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> royalCommand(final String name) {
        return Commands.literal(name).executes(this.exec("admin",name))
            .then(Commands.literal("preview").executes(this.exec("admin",name,"preview")))
            .then(Commands.literal("start").executes(this.exec("admin",name,"start")))
            .then(Commands.literal("status").executes(this.exec("admin",name,"status")))
            .then(Commands.literal("resume").executes(this.exec("admin",name,"resume")));
    }

    private LiteralArgumentBuilder<CommandSourceStack> greedy(final String name, final String argName,
                                                             final SuggestionProvider<CommandSourceStack> suggestions,
                                                             final String... parents) {
        final String[] base = concat(parents, name);
        return Commands.literal(name)
            .executes(this.exec(base))
            .then(Commands.argument(argName, StringArgumentType.greedyString())
                .suggests(suggestions)
                .executes(ctx -> this.run(ctx, concat(base, tokens(arg(ctx, argName))))));
    }

    private LiteralArgumentBuilder<CommandSourceStack> queueAction(final String action) {
        return Commands.literal(action)
            .executes(this.exec("kolejka", action))
            .then(Commands.argument("nr", StringArgumentType.word())
                .suggests(this.provider(this::suggestQueueNumbers))
                .executes(ctx -> this.run(ctx, "kolejka", action, arg(ctx, "nr"))));
    }

    private Command<CommandSourceStack> exec(final String... args) {
        return ctx -> this.run(ctx, args);
    }

    private int run(final CommandContext<CommandSourceStack> ctx, final String... args) {
        this.cmd.run(ctx.getSource().getSender(), args);
        return Command.SINGLE_SUCCESS;
    }

    private static String arg(final CommandContext<CommandSourceStack> ctx, final String name) {
        return StringArgumentType.getString(ctx, name);
    }

    static String[] tokens(final String greedy) {
        final String trimmed = greedy.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split(" +");
    }

    private static String[] concat(final String[] head, final String... tail) {
        final String[] out = new String[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    // ------------------------------------------------------------------ suggestions

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
                }, Bukkit.getScheduler().getMainThreadExecutor(this.cmd.plugin()));
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

    private void suggestCountries(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final KrajInfo k : this.cmd.plugin().kraje().getCountries()) {
            add(builder, k.name(), Component.text("Ludność: " + this.cmd.plugin().data().population(k.id())
                + (k.npc() ? " (NPC)" : ""), NamedTextColor.GRAY));
        }
    }

    /** First the country, then (after a full country name and a space) the given keywords. */
    private void suggestCountryThenWords(final SuggestionsBuilder builder, final String... words) {
        final String rem = builder.getRemaining();
        for (final KrajInfo k : this.cmd.plugin().kraje().getCountries()) {
            if (rem.toLowerCase(Locale.ROOT).startsWith(k.name().toLowerCase(Locale.ROOT) + " ")) {
                final String after = rem.substring(k.name().length() + 1);
                final int lastSpace = after.lastIndexOf(' ');
                final SuggestionsBuilder sub = builder.createOffset(builder.getStart() + k.name().length() + 1 + lastSpace + 1);
                final String typed = after.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
                for (final String w : words) {
                    if (w.startsWith(typed) && !after.contains(w)) {
                        sub.suggest(w);
                    }
                }
                builder.add(sub);
                return;
            }
        }
        for (final KrajInfo k : this.cmd.plugin().kraje().getCountries()) {
            add(builder, k.name(), Component.text(k.npc() ? "kraj NPC" : "kraj graczy", NamedTextColor.GRAY));
        }
    }

    /** First the country, then (after a space following a full country name) a number. */
    private void suggestCountryThenNumber(final CommandSender sender, final SuggestionsBuilder builder) {
        final String rem = builder.getRemaining();
        for (final KrajInfo k : this.cmd.plugin().kraje().getCountries()) {
            if (rem.toLowerCase(Locale.ROOT).startsWith(k.name().toLowerCase(Locale.ROOT) + " ")) {
                final SuggestionsBuilder sub = builder.createOffset(builder.getStart() + k.name().length() + 1);
                for (final String n : new String[] {"1", "5", "10", "100"}) {
                    sub.suggest(n, MessageComponentSerializer.message().serialize(Component.text("<liczba>")));
                }
                builder.add(sub);
                return;
            }
        }
        this.suggestCountries(sender, builder);
    }

    /** Numbers of the sender's country queue with the building as tooltip. */
    private void suggestQueueNumbers(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            return;
        }
        this.cmd.plugin().kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
            final var queue = this.cmd.plugin().data().queue(k.id());
            for (int i = 0; i < queue.size(); i++) {
                final var o = queue.get(i);
                add(builder, Integer.toString(i + 1), this.cmd.messages().parse(this.cmd.plugin().construction().displayName(o.type))
                    .append(Component.text(" • " + o.originX + " " + o.originY + " " + o.originZ, NamedTextColor.GRAY)));
            }
        });
    }

    private void suggestOutdated(final CommandSender sender, final SuggestionsBuilder builder) {
        add(builder, "wszystko", Component.text("wszystkie budynki do modernizacji", NamedTextColor.GRAY));
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            return;
        }
        this.cmd.plugin().kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
            for (final var b : this.cmd.plugin().data().buildingsOf(k.id())) {
                if (this.cmd.plugin().repairs().outdated(b)) {
                    add(builder, b.id().toString().substring(0, 8), this.cmd.messages().parse(this.cmd.plugin().construction()
                        .displayName(b.type())).append(Component.text(" • " + this.cmd.plugin().formatMoney(
                            this.cmd.plugin().repairs().upgradeCost(b)), NamedTextColor.GRAY)));
                }
            }
        });
    }

    private void suggestDamaged(final CommandSender sender, final SuggestionsBuilder builder) {
        add(builder, "wszystko", Component.text("wszystkie uszkodzone budynki", NamedTextColor.GRAY));
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            return;
        }
        this.cmd.plugin().kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
            for (final var b : this.cmd.plugin().data().buildingsOf(k.id())) {
                final var d = this.cmd.plugin().repairs().damageOf(b.id());
                if (d != null && d.percent() > 0) {
                    add(builder, b.id().toString().substring(0, 8), this.cmd.messages().parse(this.cmd.plugin().construction()
                        .displayName(b.type())).append(Component.text(" • " + d.percent() + "%", NamedTextColor.RED)));
                }
            }
        });
    }

    private void suggestCountryThenClear(final CommandSender sender, final SuggestionsBuilder builder) {
        final String rem = builder.getRemaining();
        for (final KrajInfo k : this.cmd.plugin().kraje().getCountries()) {
            if (rem.toLowerCase(Locale.ROOT).startsWith(k.name().toLowerCase(Locale.ROOT) + " ")) {
                final SuggestionsBuilder sub = builder.createOffset(builder.getStart() + k.name().length() + 1);
                sub.suggest("wyczysc", MessageComponentSerializer.message().serialize(
                    Component.text("usuń całą kolejkę ze zwrotem 100%", NamedTextColor.GRAY)));
                builder.add(sub);
                return;
            }
        }
        this.suggestCountries(sender, builder);
    }

    private void suggestTypes(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final BuildingTemplate t : this.cmd.catalog(sender)) {
            add(builder, t.id(), this.cmd.messages().parse(t.meta().displayName())
                .append(Component.text(" • " + this.cmd.plugin().formatMoney(t.meta().cost()) + " • łóżka: " + t.bedCount(),
                    NamedTextColor.GRAY)));
        }
    }

    private void suggestOwnSites(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!(sender instanceof org.bukkit.entity.Player p)) return;
        add(builder, "potwierdz", Component.text("anuluj plac, na którym stoisz (albo jedyny plac / jedyne zlecenie)", NamedTextColor.GRAY));
        this.cmd.plugin().kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
            for (final var s : this.cmd.plugin().data().sitesOf(k.id()))
                if (!s.is(ConstructionSite.RESTORE)) add(builder, s.id.toString().substring(0,8), Component.text(s.type));
        });
    }

    private void suggestSites(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final ConstructionSite s : this.cmd.plugin().data().allSites()) {
            add(builder, s.id.toString().substring(0, 8), Component.text(s.type + " • " + this.cmd.plugin().countryName(s.countryId)
                + " • " + s.originX + " " + s.originY + " " + s.originZ, NamedTextColor.GRAY));
        }
    }

    private void suggestSeasons(final CommandSender sender, final SuggestionsBuilder builder) {
        final var rules = this.cmd.plugin().settings().work.seasons;
        for (final pl.medivalrpg.ludnosc.logic.Season s : pl.medivalrpg.ludnosc.logic.Season.values()) {
            add(builder, s.id(), Component.text(s.displayName() + " • plony " + pl.medivalrpg.ludnosc.service.SeasonService.times(
                rules.foodMultiplier(s)), NamedTextColor.GRAY));
        }
    }

    private void suggestPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final var pages = HelpBook.pages(sender.hasPermission(LudnoscCommand.PERM_ADMIN));
        for (int i = 0; i < pages.size(); i++) {
            add(builder, Integer.toString(i + 1), this.cmd.messages().parse(HelpBook.categoryName(this.cmd.messages(), pages.get(i))));
        }
    }
}
