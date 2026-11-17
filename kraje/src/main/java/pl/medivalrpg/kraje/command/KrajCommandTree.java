// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.command;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.geometry.AreaPlanner;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.util.Colors;
import pl.medivalrpg.kraje.util.Format;

/**
 * Brigadier tree of {@code /kraj} (registered through {@code LifecycleEvents.COMMANDS}). Every subcommand is a
 * literal and every argument has a Polish name, so the client shows e.g. {@code /kraj zapros <gracz>}.
 * Executing a node only rebuilds the old argument array and calls {@link KrajCommand#run}, so game logic and
 * messages stay the same.
 *
 * <p>Country names may contain Polish letters, and {@code StringArgumentType.word()} only accepts ASCII, so
 * arguments that can hold a country name (or {@code #hex}) are greedy; the old code also used only the first
 * word of them. A greedy fallback {@code <komenda>} at the end passes anything else to the old dispatcher
 * (unknown subcommand message, upper-case input).
 *
 * <p>Suggestion providers read Bukkit and country data; they always do that on the main thread (directly when
 * already on it, otherwise through the scheduler's main-thread executor).
 */
final class KrajCommandTree {

    static final String PERM_USE = "kraje.use";
    static final String PERM_ADMIN = "kraje.admin";

    private final KrajCommand cmd;

    KrajCommandTree(final KrajCommand cmd) {
        this.cmd = cmd;
    }

    // ------------------------------------------------------------------ tree

    LiteralCommandNode<CommandSourceStack> build() {
        final Predicate<CommandSourceStack> admin = s -> s.getSender().hasPermission(PERM_ADMIN);
        final SuggestionProvider<CommandSourceStack> members = this.provider(this::suggestOwnMembers);
        final SuggestionProvider<CommandSourceStack> otherCountries = this.provider(this::suggestOtherCountries);

        return Commands.literal("kraj")
            .requires(s -> s.getSender().hasPermission(PERM_USE))
            .executes(this.exec())
            .then(this.pages("pomoc"))
            .then(this.pages("help"))
            // Teren
            .then(this.withGreedy("zaloz", "nazwa", null))
            .then(this.simple("zajmij"))
            .then(this.simple("porzuc"))
            .then(this.simple("stolica"))
            .then(this.simple("spawn"))
            // Ludzie
            .then(this.withWord("zapros", "gracz", this.provider(this::suggestFreePlayers)))
            .then(this.withGreedy("dolacz", "kraj", this.provider(this::suggestInvites)))
            .then(this.withGreedy("odrzuc", "kraj", this.provider(this::suggestInvites)))
            .then(this.simple("opusc"))
            .then(this.withWord("wyrzuc", "gracz", members))
            .then(this.withWord("awansuj", "gracz", members))
            .then(this.withWord("degraduj", "gracz", members))
            .then(Commands.literal("przekaz")
                .executes(this.exec("przekaz"))
                .then(Commands.argument("gracz", StringArgumentType.word())
                    .suggests(members)
                    .executes(ctx -> this.run(ctx, "przekaz", arg(ctx, "gracz")))
                    .then(Commands.literal(KrajCommand.CONFIRM)
                        .executes(ctx -> this.run(ctx, "przekaz", arg(ctx, "gracz"), KrajCommand.CONFIRM)))))
            .then(this.disband("rozwiaz"))
            .then(this.disband("usun"))
            // Wygląd
            .then(this.withGreedy("kolor", "kolor", this.provider(this::suggestColors)))
            .then(Commands.literal("opis")
                .executes(this.exec("opis"))
                .then(Commands.argument("tekst", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestDescription))
                    .executes(ctx -> this.run(ctx, "opis", arg(ctx, "tekst")))))
            // Informacje
            .then(this.withGreedy("info", "kraj", this.provider(this::suggestAllCountries)))
            .then(Commands.literal("lista")
                .executes(this.exec("lista"))
                .then(Commands.argument("strona", StringArgumentType.word())
                    .suggests(this.provider(this::suggestListPages))
                    .executes(ctx -> this.run(ctx, "lista", arg(ctx, "strona")))))
            .then(this.simple("mapa"))
            // Dyplomacja
            .then(this.withGreedy("sojusz", "kraj", otherCountries))
            .then(this.withGreedy("wojna", "kraj", otherCountries))
            .then(this.withGreedy("pokoj", "kraj", otherCountries))
            .then(this.withGreedy("zerwij", "kraj", otherCountries))
            // Kapitulacja i wasale
            .then(Commands.literal("kapitulacja")
                .executes(this.exec("kapitulacja"))
                .then(this.withGreedy("przyjmij", "kraj", this.provider(this::suggestCapitulations), "kapitulacja"))
                .then(this.withGreedy("odrzuc", "kraj", this.provider(this::suggestCapitulations), "kapitulacja"))
                .then(Commands.argument("wróg i dni", StringArgumentType.greedyString())
                    .suggests(this.provider(this::suggestEnemiesThenDays))
                    .executes(ctx -> this.run(ctx, concat(new String[] {"kapitulacja"}, tokens(arg(ctx, "wróg i dni")))))))
            .then(this.withGreedy("wasale", "kraj", this.provider(this::suggestAllCountries)))
            .then(Commands.literal("wykup")
                .executes(this.exec("wykup"))
                .then(Commands.literal(KrajCommand.CONFIRM).executes(this.exec("wykup", KrajCommand.CONFIRM))))
            .then(this.withGreedy("zwolnij", "wasal", this.provider(this::suggestOwnVassals)))
            // Unia personalna i ambasady
            .then(Commands.literal("unia")
                .executes(this.exec("unia"))
                .then(this.withGreedy("zaproponuj", "kraj", otherCountries, "unia"))
                .then(this.withGreedy("przyjmij", "kraj", this.provider(this::suggestUnionProposals), "unia"))
                .then(this.withGreedy("odrzuc", "kraj", this.provider(this::suggestUnionProposals), "unia"))
                .then(Commands.literal("zerwij")
                    .executes(this.exec("unia", "zerwij"))
                    .then(Commands.literal(KrajCommand.CONFIRM).executes(this.exec("unia", "zerwij", KrajCommand.CONFIRM))))
                .then(this.withGreedy("info", "kraj", this.provider(this::suggestAllCountries), "unia"))
                .then(this.simple("rzadz", "unia")))
            .then(Commands.literal("ambasada")
                .executes(this.exec("ambasada"))
                .then(this.withGreedy("otworz", "kraj", this.provider(this::suggestEmbassyHostHere), "ambasada"))
                .then(this.withGreedy("przyjmij", "kraj", this.provider(this::suggestEmbassyRequests), "ambasada"))
                .then(this.withGreedy("odrzuc", "kraj", this.provider(this::suggestEmbassyRequests), "ambasada"))
                .then(this.withGreedy("zamknij", "kraj", this.provider(this::suggestOwnEmbassyHosts), "ambasada"))
                .then(this.withGreedy("wydal", "kraj", this.provider(this::suggestEmbassyGuests), "ambasada"))
                .then(this.withGreedy("lista", "kraj", this.provider(this::suggestAllCountries), "ambasada"))
                .then(this.withGreedy("tp", "kraj", this.provider(this::suggestOwnEmbassyHosts), "ambasada")))
            // Wojna
            .then(this.simple("oblezenie"))
            .then(this.simple("desant"))
            .then(this.simple("oblezenia"))
            // Skarbiec
            .then(this.withGreedy("skarbiec", "kraj", this.provider(this::suggestCountriesForAdmin)))
            .then(this.withGreedy("wplac", "kwota", this.provider(this::suggestDeposit)))
            .then(this.withGreedy("wyplac", "kwota", this.provider(this::suggestAmounts)))
            .then(this.withGreedy("podatek", "kwota", this.provider(this::suggestTax)))
            // Czat
            .then(this.simple("czat"))
            // Chorągiew kraju (own subtree, see flag/FlagCommands)
            .then(pl.medivalrpg.kraje.flag.FlagCommands.tree(() -> this.cmd == null || this.cmd.plugin() == null ? null : this.cmd.plugin().flags()))
            // Admin (hidden from the client without kraje.admin)
            .then(Commands.literal("admin")
                .requires(admin)
                .executes(this.exec("admin"))
                .then(this.withGreedy("usun", "kraj", this.provider((s, b) -> this.suggestDelete(b)), "admin"))
                .then(this.withGreedy("wladca", "kraj i gracz", this.provider(this::suggestCountryThenPlayer), "admin"))
                .then(this.withGreedy("zajmij", "kraj i obszar", this.provider((s, b) -> this.suggestArea(b, false)), "admin"))
                .then(this.withGreedy("porzuc", "kraj i obszar", this.provider((s, b) -> this.suggestArea(b, true)), "admin"))
                .then(this.withGreedy("przylacz", "kraj i pierścienie", this.provider((s, b) -> this.suggestRings(b)), "admin"))
                .then(this.withGreedy("przywroc", "kraj i obszar", this.provider((s, b) -> this.suggestRestore(b, false)), "admin")
                    .then(this.simple(AdminRestore.STOP, "admin", "przywroc"))
                    .then(this.withGreedy(AdminRestore.WILD, "obszar", this.provider((s, b) -> this.suggestRestore(b, true)),
                        "admin", "przywroc")))
                .then(this.withGreedy("tp", "kraj", this.provider(this::suggestTeleportTargets), "admin"))
                .then(this.simple("bypass", "admin"))
                .then(this.simple("spy", "admin"))
                .then(this.simple("reload", "admin"))
                .then(this.simple("tick", "admin"))
                .then(this.simple("oblezenie", "admin")))
            // Anything else → old dispatcher ("Nieznana komenda ...", case-insensitive subcommands).
            .then(Commands.argument("komenda", StringArgumentType.greedyString())
                .executes(ctx -> this.run(ctx, tokens(arg(ctx, "komenda")))))
            .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> simple(final String name, final String... parents) {
        return Commands.literal(name).executes(this.exec(concat(parents, name)));
    }

    /** {@code <name> <arg>} with a single-word (ASCII) argument, e.g. a player name. */
    private LiteralArgumentBuilder<CommandSourceStack> withWord(final String name, final String argName,
                                                               final SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.literal(name)
            .executes(this.exec(name))
            .then(Commands.argument(argName, StringArgumentType.word())
                .suggests(suggestions)
                .executes(ctx -> this.run(ctx, name, arg(ctx, argName))));
    }

    /** {@code <name> <arg>} with a greedy argument split into words like the old {@code String[] args}. */
    private LiteralArgumentBuilder<CommandSourceStack> withGreedy(final String name, final String argName,
                                                                 final SuggestionProvider<CommandSourceStack> suggestions,
                                                                 final String... parents) {
        final var argument = Commands.argument(argName, StringArgumentType.greedyString())
            .executes(ctx -> this.run(ctx, concat(concat(parents, name), tokens(arg(ctx, argName)))));
        if (suggestions != null) {
            argument.suggests(suggestions);
        }
        return Commands.literal(name).executes(this.exec(concat(parents, name))).then(argument);
    }

    private LiteralArgumentBuilder<CommandSourceStack> disband(final String name) {
        return Commands.literal(name)
            .executes(this.exec(name))
            .then(Commands.literal(KrajCommand.CONFIRM).executes(this.exec(name, KrajCommand.CONFIRM)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> pages(final String name) {
        return Commands.literal(name)
            .executes(this.exec(name))
            .then(Commands.argument("strona", StringArgumentType.greedyString())
                .suggests(this.provider(this::suggestHelpPages))
                .executes(ctx -> this.run(ctx, name, arg(ctx, "strona"))));
    }

    // ------------------------------------------------------------------ execution

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

    /** Splits a greedy argument into words (like the old {@code String[] args}). */
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

    /** Fills suggestions on the main thread (Bukkit API and live country data are main-thread only). */
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
            } catch (final RuntimeException e) { // plugin disabled → scheduler rejects the task
                return Suggestions.empty();
            }
        };
    }

    private Messages messages() {
        return this.cmd.messages();
    }

    private static void add(final SuggestionsBuilder builder, final String text, final Component tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) {
            builder.suggest(text, MessageComponentSerializer.message().serialize(tooltip));
        }
    }

    /** Greedy single-word arguments: suggest only while the first word is being typed. */
    private static boolean typingFirstWord(final SuggestionsBuilder builder) {
        return builder.getRemaining().indexOf(' ') < 0;
    }

    private Component countryTooltip(final Country c) {
        return this.messages().get("suggest-country", KrajCommand.country(c),
            Placeholder.unparsed("ruler", c.rulerName()),
            Placeholder.unparsed("members", Integer.toString(c.memberCount())),
            Placeholder.unparsed("area", Format.area(c.claimCount())));
    }

    private void addCountries(final SuggestionsBuilder builder, final Iterable<Country> countries) {
        for (final Country c : countries) {
            add(builder, c.name(), this.countryTooltip(c));
        }
    }

    private Component playerTooltip(final Player p) {
        final Country c = this.cmd.manager().countryOf(p.getUniqueId());
        if (c == null) {
            return this.messages().get("suggest-player-free");
        }
        return this.messages().get("suggest-player-citizen", KrajCommand.country(c),
            Placeholder.unparsed("rank", KrajCommand.rankOf(c, p.getUniqueId()).displayName()));
    }

    private Component memberTooltip(final Member m) {
        final boolean online = Bukkit.getPlayer(m.uuid()) != null;
        return this.messages().get(online ? "suggest-member-online" : "suggest-member-offline",
            Placeholder.unparsed("rank", m.rank().displayName()));
    }

    private Country ownCountry(final CommandSender sender) {
        return sender instanceof Player p ? this.cmd.manager().countryOf(p.getUniqueId()) : null;
    }

    private void suggestFreePlayers(final CommandSender sender, final SuggestionsBuilder builder) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.cmd.manager().countryOf(p.getUniqueId()) == null && !p.equals(sender)) {
                add(builder, p.getName(), this.playerTooltip(p));
            }
        }
    }

    private void suggestOwnMembers(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.ownCountry(sender);
        if (own == null) {
            return;
        }
        for (final Member m : own.members()) {
            if (!m.uuid().equals(own.ruler())) {
                add(builder, m.name(), this.memberTooltip(m));
            }
        }
    }

    private void suggestInvites(final CommandSender sender, final SuggestionsBuilder builder) {
        if (sender instanceof Player p && typingFirstWord(builder)) {
            final java.util.LinkedHashSet<Country> countries = new java.util.LinkedHashSet<>();
            this.cmd.requests().invitesOf(p.getUniqueId()).stream()
                .map(this.cmd.manager()::get).filter(Objects::nonNull).forEach(countries::add);
            this.cmd.manager().storedInvitesOf(p.getUniqueId()).stream()
                .map(i -> this.cmd.manager().get(i.country())).filter(Objects::nonNull).forEach(countries::add);
            this.addCountries(builder, countries);
        }
    }

    /** {@code /kraj kapitulacja przyjmij|odrzuc <kraj>}: countries that offered to capitulate to the sender's country. */
    private void suggestCapitulations(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.ownCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.requests().capitulationsTo(own.id()).stream()
                .map(o -> this.cmd.manager().get(o.from())).filter(Objects::nonNull).toList());
        }
    }

    /** {@code /kraj kapitulacja <wróg> [dni]}: enemies first, then the allowed lengths. */
    private void suggestEnemiesThenDays(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.ownCountry(sender);
        if (own == null) {
            return;
        }
        final String remaining = builder.getRemaining();
        final int space = remaining.indexOf(' ');
        if (space < 0) {
            this.addCountries(builder, this.cmd.manager().countries().stream()
                .filter(c -> c != own && own.relationTo(c.id()) == pl.medivalrpg.kraje.model.Relation.WAR).toList());
            return;
        }
        if (remaining.indexOf(' ', space + 1) >= 0) {
            return;
        }
        final SuggestionsBuilder second = builder.createOffset(builder.getStart() + space + 1);
        final var cfg = this.cmd.cfg();
        for (final int d : new int[] {cfg.vassalMinDays, cfg.vassalDefaultDays, cfg.vassalMaxDays}) {
            add(second, Integer.toString(d), this.messages().get(d == cfg.vassalDefaultDays ? "suggest-days-default" : "suggest-days",
                Placeholder.unparsed("days", Integer.toString(d))));
        }
        builder.add(second);
    }

    /** {@code /kraj unia przyjmij|odrzuc <kraj>}: countries that proposed a union to the sender's country. */
    private void suggestUnionProposals(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.actingCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.plugin().unions().proposalsTo(own.id()).stream()
                .map(this.cmd.manager()::get).filter(Objects::nonNull).toList());
        }
    }

    /** {@code /kraj ambasada otworz <kraj>}: the country owning the chunk the player stands on. */
    private void suggestEmbassyHostHere(final CommandSender sender, final SuggestionsBuilder builder) {
        if (sender instanceof Player p && typingFirstWord(builder)) {
            final Country here = this.cmd.manager().countryAt(p.getLocation());
            if (here != null && here != this.actingCountry(sender)) {
                this.addCountries(builder, List.of(here));
            }
        }
    }

    private void suggestEmbassyRequests(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.actingCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.plugin().embassies().requestsTo(own.id()).stream()
                .map(this.cmd.manager()::get).filter(Objects::nonNull).toList());
        }
    }

    /** Hosts of the sender's embassies ({@code zamknij}, {@code tp}). */
    private void suggestOwnEmbassyHosts(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.actingCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.plugin().embassies().hostsOf(own));
        }
    }

    /** Guests with an embassy in the sender's country ({@code wydal}). */
    private void suggestEmbassyGuests(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.actingCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.manager().embassyGuestsIn(own.id()));
        }
    }

    /** Country the sender's commands act on (union partner while switched with /kraj unia rzadz). */
    private Country actingCountry(final CommandSender sender) {
        final Country own = this.ownCountry(sender);
        return own == null || !(sender instanceof Player p) ? own : this.cmd.plugin().unions().actingCountry(p.getUniqueId(), own);
    }

    private void suggestOwnVassals(final CommandSender sender, final SuggestionsBuilder builder) {
        final Country own = this.ownCountry(sender);
        if (own != null && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.manager().vassalsOf(own.id()));
        }
    }

    private void suggestAllCountries(final CommandSender sender, final SuggestionsBuilder builder) {
        if (typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.manager().countries());
        }
    }

    private void suggestOtherCountries(final CommandSender sender, final SuggestionsBuilder builder) {
        if (typingFirstWord(builder)) {
            final Country own = this.ownCountry(sender);
            this.addCountries(builder, this.cmd.manager().countries().stream().filter(c -> c != own).toList());
        }
    }

    /** {@code /kraj admin wladca <kraj> <gracz>}: country first, then its members and online players. */
    private void suggestCountryThenPlayer(final CommandSender sender, final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int space = remaining.indexOf(' ');
        if (space < 0) {
            this.addCountries(builder, this.cmd.manager().countries());
            return;
        }
        if (remaining.indexOf(' ', space + 1) >= 0) {
            return;
        }
        final SuggestionsBuilder second = builder.createOffset(builder.getStart() + space + 1);
        final Country c = this.cmd.manager().byName(remaining.substring(0, space));
        final List<String> seen = new ArrayList<>();
        if (c != null) {
            for (final Member m : c.members()) {
                seen.add(m.name().toLowerCase(Locale.ROOT));
                add(second, m.name(), this.memberTooltip(m));
            }
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (!seen.contains(p.getName().toLowerCase(Locale.ROOT))) {
                add(second, p.getName(), this.playerTooltip(p));
            }
        }
        builder.add(second);
    }

    /** {@code /kraj admin tp <kraj>}: every country, tooltip with ruler, area and the NPC mark. */
    private void suggestTeleportTargets(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!sender.hasPermission(PERM_ADMIN) || !typingFirstWord(builder)) {
            return;
        }
        this.cmd.manager().countries().stream()
            .sorted(java.util.Comparator.comparing((Country c) -> !c.npc())
                .thenComparing(c -> c.name().toLowerCase(Locale.ROOT)))
            .forEach(c -> add(builder, c.name(), this.messages().get("suggest-country-tp", KrajCommand.country(c),
                Placeholder.unparsed("ruler", c.rulerName()),
                Placeholder.component("npc", c.npc() ? this.messages().get("npc-suffix") : Component.empty()),
                Placeholder.unparsed("area", Format.area(c.claimCount())))));
    }

    /**
     * {@code /kraj admin zajmij|porzuc <kraj> [promien <n>|zaznaczenie] [potwierdz]}: country, then the area
     * keyword, the radius, and {@code potwierdz}.
     */
    private void suggestArea(final SuggestionsBuilder builder, final boolean release) {
        final String remaining = builder.getRemaining();
        final int space = remaining.lastIndexOf(' ');
        if (space < 0) {
            this.addCountries(builder, this.cmd.manager().countries());
            return;
        }
        final String[] previous = tokens(remaining.substring(0, space));
        final String last = previous.length == 0 ? "" : previous[previous.length - 1].toLowerCase(Locale.ROOT);
        final SuggestionsBuilder next = builder.createOffset(builder.getStart() + space + 1);
        if (last.equals(AdminTerritory.RADIUS)) {
            for (final int r : new int[] {1, 2, 3, 5, 8, 12, AreaPlanner.MAX_RADIUS}) {
                final int side = 2 * r + 1;
                add(next, Integer.toString(r), this.messages().get("suggest-area-radius-value",
                    Placeholder.unparsed("radius", Integer.toString(r)), Placeholder.unparsed("side", Integer.toString(side)),
                    Placeholder.unparsed("chunks", Format.chunks(side * side))));
            }
        } else if (last.equals(KrajCommand.CONFIRM)) {
            return;
        } else if (last.equals(AdminTerritory.SELECTION)
            || previous.length >= 2 && previous[previous.length - 2].equalsIgnoreCase(AdminTerritory.RADIUS)) {
            add(next, KrajCommand.CONFIRM, this.messages().get("suggest-area-confirm"));
        } else {
            add(next, AdminTerritory.RADIUS, this.messages().get(release ? "suggest-area-radius-release" : "suggest-area-radius"));
            add(next, AdminTerritory.SELECTION, this.messages().get(release ? "suggest-area-selection-release" : "suggest-area-selection"));
            if (release) {
                add(next, KrajCommand.CONFIRM, this.messages().get("suggest-area-confirm"));
            }
        }
        builder.add(next);
    }

    /** {@code /kraj admin usun <kraj> [przywroc [potwierdz]]}. */
    private void suggestDelete(final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int space = remaining.lastIndexOf(' ');
        if (space < 0) {
            this.addCountries(builder, this.cmd.manager().countries());
            return;
        }
        final String[] previous = tokens(remaining.substring(0, space));
        final String last = previous.length == 0 ? "" : previous[previous.length - 1].toLowerCase(Locale.ROOT);
        final SuggestionsBuilder next = builder.createOffset(builder.getStart() + space + 1);
        if (last.equals(AdminRestore.RESTORE)) {
            add(next, KrajCommand.CONFIRM, this.messages().get("suggest-area-confirm"));
        } else if (!last.equals(KrajCommand.CONFIRM)) {
            add(next, AdminRestore.RESTORE, this.messages().get("suggest-delete-restore"));
        }
        builder.add(next);
    }

    /**
     * {@code /kraj admin przywroc <kraj> [promien <n>|zaznaczenie] [potwierdz]} ({@code wild = false}: the first word
     * is the country) and {@code /kraj admin przywroc teren promien <n>|zaznaczenie [potwierdz]} ({@code wild}: the
     * text after the {@code teren} literal). {@code stop} and {@code teren} themselves are literals of the tree.
     */
    private void suggestRestore(final SuggestionsBuilder builder, final boolean wild) {
        final String remaining = builder.getRemaining();
        final int space = remaining.lastIndexOf(' ');
        if (space < 0 && !wild) {
            this.addCountries(builder, this.cmd.manager().countries());
            return;
        }
        final String[] previous = space < 0 ? new String[0] : tokens(remaining.substring(0, space));
        final String last = previous.length == 0 ? "" : previous[previous.length - 1].toLowerCase(Locale.ROOT);
        final SuggestionsBuilder next = space < 0 ? builder : builder.createOffset(builder.getStart() + space + 1);
        if (last.equals(KrajCommand.CONFIRM)) {
            return;
        }
        if (last.equals(AdminRestore.RADIUS)) {
            for (final int r : new int[] {0, 1, 2, 3, 5, 8, AreaPlanner.MAX_RADIUS}) {
                final int side = 2 * r + 1;
                add(next, Integer.toString(r), this.messages().get("suggest-area-radius-value",
                    Placeholder.unparsed("radius", Integer.toString(r)), Placeholder.unparsed("side", Integer.toString(side)),
                    Placeholder.unparsed("chunks", Format.chunks(side * side))));
            }
        } else if (last.equals(AdminRestore.SELECTION)
            || previous.length >= 2 && previous[previous.length - 2].equalsIgnoreCase(AdminRestore.RADIUS)) {
            add(next, KrajCommand.CONFIRM, this.messages().get("suggest-area-confirm"));
        } else {
            add(next, AdminRestore.RADIUS, this.messages().get(wild ? "suggest-restore-wild-radius" : "suggest-restore-radius"));
            add(next, AdminRestore.SELECTION, this.messages().get(wild ? "suggest-restore-wild-selection" : "suggest-restore-selection"));
            if (!wild) {
                add(next, KrajCommand.CONFIRM, this.messages().get("suggest-restore-confirm"));
            }
        }
        if (next != builder) {
            builder.add(next);
        }
    }

    /** {@code /kraj admin przylacz <kraj> [pierścienie] [potwierdz]}. */
    private void suggestRings(final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int space = remaining.lastIndexOf(' ');
        if (space < 0) {
            this.addCountries(builder, this.cmd.manager().countries());
            return;
        }
        final String[] previous = tokens(remaining.substring(0, space));
        final SuggestionsBuilder next = builder.createOffset(builder.getStart() + space + 1);
        if (previous.length == 1) {
            for (final int r : new int[] {1, 2, 3, 5, 8, AreaPlanner.MAX_RINGS}) {
                add(next, Integer.toString(r), this.messages().get("suggest-area-rings",
                    Placeholder.unparsed("rings", Integer.toString(r))));
            }
        }
        if (previous.length >= 1 && !previous[previous.length - 1].equalsIgnoreCase(KrajCommand.CONFIRM)) {
            add(next, KrajCommand.CONFIRM, this.messages().get("suggest-area-confirm"));
        }
        builder.add(next);
    }

    private void suggestCountriesForAdmin(final CommandSender sender, final SuggestionsBuilder builder) {
        if (sender.hasPermission(PERM_ADMIN) && typingFirstWord(builder)) {
            this.addCountries(builder, this.cmd.manager().countries());
        }
    }

    private static final String[] AMOUNTS = {"50", "1zl", "5zl", "10zl", "50zl", "100zl"};

    private void addAmounts(final SuggestionsBuilder builder) {
        for (final String amount : AMOUNTS) {
            final long copper = this.cmd.treasury().parseAmount(amount);
            add(builder, amount, this.messages().get("suggest-amount", Placeholder.unparsed("amount", this.cmd.money(copper))));
        }
    }

    private void suggestAmounts(final CommandSender sender, final SuggestionsBuilder builder) {
        if (typingFirstWord(builder)) {
            this.addAmounts(builder);
        }
    }

    /** {@code /kraj wplac <kwota> [konto|monety]}. */
    private void suggestDeposit(final CommandSender sender, final SuggestionsBuilder builder) {
        final String remaining = builder.getRemaining();
        final int space = remaining.lastIndexOf(' ');
        if (space < 0) {
            this.addAmounts(builder);
            return;
        }
        final SuggestionsBuilder next = builder.createOffset(builder.getStart() + space + 1);
        add(next, "konto", this.messages().get("suggest-source-account"));
        add(next, "monety", this.messages().get("suggest-source-coins"));
        builder.add(next);
    }

    private void suggestTax(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!typingFirstWord(builder)) {
            return;
        }
        add(builder, "0", this.messages().get("suggest-tax-none"));
        for (final String amount : new String[] {"5", "10", "20", "50", "1zl"}) {
            final long copper = this.cmd.treasury().parseAmount(amount);
            if (copper <= this.cmd.cfg().taxMax) {
                add(builder, amount, this.messages().get("suggest-amount", Placeholder.unparsed("amount", this.cmd.money(copper))));
            }
        }
    }

    private void suggestColors(final CommandSender sender, final SuggestionsBuilder builder) {
        if (!typingFirstWord(builder)) {
            return;
        }
        for (final String name : Colors.names()) {
            final int rgb = Colors.parse(name);
            add(builder, name, this.messages().get("suggest-color",
                Placeholder.unparsed("name", name), colorTags(rgb)));
        }
        for (final int rgb : this.cmd.cfg().defaultColors) {
            add(builder, String.format(Locale.ROOT, "#%06X", rgb), this.messages().get("suggest-color-hex", colorTags(rgb)));
        }
    }

    private static TagResolver colorTags(final int rgb) {
        return TagResolver.resolver(Placeholder.styling("color", TextColor.color(rgb)),
            Placeholder.unparsed("hex", String.format(Locale.ROOT, "#%06X", rgb)));
    }

    private void suggestDescription(final CommandSender sender, final SuggestionsBuilder builder) {
        add(builder, "-", this.messages().get("suggest-description-clear"));
    }

    private void suggestListPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final int count = this.cmd.manager().countries().size();
        final int pages = Math.max(1, (count + InfoViews.PAGE_SIZE - 1) / InfoViews.PAGE_SIZE);
        for (int i = 1; i <= pages; i++) {
            add(builder, Integer.toString(i), this.messages().get("suggest-list-page",
                Placeholder.unparsed("page", Integer.toString(i)), Placeholder.unparsed("pages", Integer.toString(pages))));
        }
    }

    private void suggestHelpPages(final CommandSender sender, final SuggestionsBuilder builder) {
        final List<HelpBook.Category> pages = HelpBook.pages(sender.hasPermission(PERM_ADMIN));
        for (int i = 0; i < pages.size(); i++) {
            add(builder, Integer.toString(i + 1), this.messages().get("suggest-help-page",
                Placeholder.unparsed("page", Integer.toString(i + 1)),
                Placeholder.parsed("category", HelpBook.categoryName(this.messages(), pages.get(i)))));
        }
    }
}
