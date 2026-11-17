// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.hook.WorldEditHook;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules;
import pl.medivalrpg.ludnosc.logic.PlayerBuildingRules.Def;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.OwnBuilding;
import pl.medivalrpg.ludnosc.service.PlayerBuildingService;

/**
 * {@code /ludnosc budynek zglos|typy|info|usun}: the ruler / advisor of a players' country gives a function to a
 * building the players built by hand (selection from {@code /ludnosc kopia pos1/pos2} or WorldEdit). Texts in
 * {@code messages.budynek.*}. Main thread.
 */
public final class PlayerBuildingCommand {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Warsaw"));

    private PlayerBuildingCommand() {
    }

    // ------------------------------------------------------------------ brigadier

    static LiteralArgumentBuilder<CommandSourceStack> node(final LudnoscCommand cmd) {
        final SuggestionProvider<CommandSourceStack> types = provider(cmd, (sender, builder) -> suggestTypes(cmd.plugin(), sender, builder));
        final SuggestionProvider<CommandSourceStack> names = provider(cmd, (sender, builder) -> {
            if (builder.getRemaining().isEmpty()) {
                builder.suggest("<nazwa>", tooltip("opcjonalna nazwa budynku (2–24 znaki)"));
            }
        });
        return Commands.literal("budynek")
            .executes(exec(cmd, "budynek"))
            .then(Commands.literal("typy").executes(exec(cmd, "budynek", "typy")))
            .then(Commands.literal("info").executes(exec(cmd, "budynek", "info")))
            .then(Commands.literal("usun").executes(exec(cmd, "budynek", "usun")))
            .then(Commands.literal("zglos")
                .executes(exec(cmd, "budynek", "zglos"))
                .then(Commands.argument("typ", StringArgumentType.word())
                    .suggests(types)
                    .executes(ctx -> run(cmd, ctx, "budynek", "zglos", StringArgumentType.getString(ctx, "typ")))
                    .then(Commands.argument("nazwa", StringArgumentType.greedyString())
                        .suggests(names)
                        .executes(ctx -> {
                            final String[] name = LudnoscCommandTree.tokens(StringArgumentType.getString(ctx, "nazwa"));
                            final String[] args = new String[3 + name.length];
                            args[0] = "budynek";
                            args[1] = "zglos";
                            args[2] = StringArgumentType.getString(ctx, "typ");
                            System.arraycopy(name, 0, args, 3, name.length);
                            return run(cmd, ctx, args);
                        }))));
    }

    private static Command<CommandSourceStack> exec(final LudnoscCommand cmd, final String... args) {
        return ctx -> run(cmd, ctx, args);
    }

    private static int run(final LudnoscCommand cmd, final CommandContext<CommandSourceStack> ctx, final String... args) {
        cmd.run(ctx.getSource().getSender(), args);
        return Command.SINGLE_SUCCESS;
    }

    private static SuggestionProvider<CommandSourceStack> provider(final LudnoscCommand cmd,
                                                                   final BiConsumer<CommandSender, SuggestionsBuilder> fill) {
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
                }, Bukkit.getScheduler().getMainThreadExecutor(cmd.plugin()));
            } catch (final RuntimeException e) {
                return Suggestions.empty();
            }
        };
    }

    private static com.mojang.brigadier.Message tooltip(final String text) {
        return MessageComponentSerializer.message().serialize(Component.text(text, NamedTextColor.GRAY));
    }

    private static void suggestTypes(final LudnoscPlugin plugin, final CommandSender sender, final SuggestionsBuilder builder) {
        final PlayerBuildingService service = plugin.playerBuildings();
        if (service == null) {
            return;
        }
        final UUID country = sender instanceof Player p ? plugin.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null) : null;
        final String typed = builder.getRemainingLowerCase();
        for (final Def d : service.types()) {
            if (!d.key().startsWith(typed)) {
                continue;
            }
            String status = "";
            if (country != null) {
                final List<String> problems = service.requirementProblems(country, d);
                status = problems.isEmpty() ? " • ✔ dostępny" : " • ✘ " + problems.getFirst();
            }
            builder.suggest(d.key(), tooltip(d.name() + " — wymaga: " + needs(d) + status));
        }
    }

    // ------------------------------------------------------------------ dispatch

    /** {@code args} = words after {@code budynek}. */
    static void run(final LudnoscPlugin plugin, final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        final PlayerBuildingService service = plugin.playerBuildings();
        if (service == null || !service.enabled()) {
            plugin.messages().send(p, "budynek.disabled");
            return;
        }
        final String action = args.length == 0 ? "pomoc" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (action) {
            case "zglos" -> register(plugin, p, rest);
            case "typy" -> types(plugin, p);
            case "info" -> info(plugin, p);
            case "usun" -> remove(plugin, p);
            default -> help(plugin, p);
        }
    }

    private static void help(final LudnoscPlugin plugin, final Player p) {
        plugin.messages().send(p, "budynek.help-header");
        for (final String[] h : new String[][] {
            {"/ludnosc budynek zglos <typ> [nazwa]", "budynek.help-zglos", "/ludnosc budynek zglos "},
            {"/ludnosc budynek typy", "budynek.help-typy", "/ludnosc budynek typy"},
            {"/ludnosc budynek info", "budynek.help-info", "/ludnosc budynek info"},
            {"/ludnosc budynek usun", "budynek.help-usun", "/ludnosc budynek usun"}}) {
            p.sendMessage(plugin.messages().get("budynek.help-line", Placeholder.unparsed("syntax", h[0]),
                    Placeholder.component("text", plugin.messages().get(h[1])))
                .clickEvent(ClickEvent.suggestCommand(h[2]))
                .hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby wpisać", NamedTextColor.GRAY))));
        }
        plugin.messages().send(p, "budynek.help-hint");
    }

    private static boolean hasRank(final LudnoscPlugin plugin, final Player p) {
        if (p.hasPermission(LudnoscCommand.PERM_ADMIN)) {
            return true;
        }
        final Rank need = plugin.settings().buildRank;
        if (plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(need)).orElse(false)) {
            return true;
        }
        plugin.messages().send(p, "rank-too-low", Placeholder.unparsed("rank", need.displayName()));
        return false;
    }

    private static String needs(final Def d) {
        final List<String> parts = new ArrayList<>();
        for (final PlayerBuildingRules.Need n : d.needs()) {
            parts.add(n.count() > 1 ? n.count() + " × " + n.label() : n.label());
        }
        return String.join(" + ", parts);
    }

    private static BlockBox selection(final LudnoscPlugin plugin, final Player p) {
        if (plugin.blueprints() != null) {
            return plugin.blueprints().selection(p);
        }
        if (Hooks.worldEdit()) {
            try {
                return WorldEditHook.selection(p);
            } catch (final RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ actions

    private static void register(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        final PlayerBuildingService service = plugin.playerBuildings();
        if (rest.length == 0) {
            plugin.messages().send(p, "budynek.usage", Placeholder.unparsed("types",
                String.join(", ", service.types().stream().map(Def::key).toList())));
            return;
        }
        final Def d = service.type(rest[0]);
        if (d == null) {
            plugin.messages().send(p, "budynek.unknown-type", Placeholder.unparsed("type", rest[0]),
                Placeholder.unparsed("types", String.join(", ", service.types().stream().map(Def::key).toList())));
            return;
        }
        final boolean admin = p.hasPermission(LudnoscCommand.PERM_ADMIN);
        final Optional<KrajInfo> own = plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty() && !admin) {
            plugin.messages().send(p, "no-country");
            return;
        }
        if (!hasRank(plugin, p)) {
            return;
        }
        final BlockBox box = selection(plugin, p);
        if (box == null) {
            plugin.messages().send(p, "budynek.no-selection");
            return;
        }
        // an admin registers for the country owning the land; everyone else only for their own country
        final UUID country = admin ? Optional.ofNullable(plugin.buildings().ownerOf(box)).orElse(own.map(KrajInfo::id).orElse(null))
            : own.get().id();
        if (country == null) {
            plugin.messages().send(p, "budynek.refused-header", Placeholder.unparsed("type", d.name()));
            plugin.messages().send(p, "budynek.refused-line", Placeholder.unparsed("problem", "obrys nie leży na ziemi żadnego kraju"));
            return;
        }
        final String name = rest.length > 1 ? String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)) : null;
        final PlayerBuildingService.Outcome out = service.register(country, p.getName(), p.getUniqueId(), box, d, name);
        if (out.building() == null) {
            plugin.messages().send(p, "budynek.refused-header", Placeholder.unparsed("type", d.name()));
            for (final String problem : out.problems()) {
                plugin.messages().send(p, "budynek.refused-line", Placeholder.unparsed("problem", problem));
            }
            plugin.messages().send(p, "budynek.refused-hint");
            plugin.plans().show(p, box, box.minY() - 1, 10);
            return;
        }
        final Building b = out.building();
        plugin.messages().send(p, "budynek.registered", Placeholder.parsed("name", service.label(b)),
            Placeholder.unparsed("country", plugin.countryName(country)), Placeholder.unparsed("box", box.toString()),
            Placeholder.unparsed("effect", d.effect()));
        if (d.function().housing() || d.function() == pl.medivalrpg.ludnosc.template.BuildingFunction.BARRACKS) {
            plugin.messages().send(p, "budynek.registered-beds", Placeholder.unparsed("beds", Integer.toString(b.beds())));
        }
        plugin.messages().send(p, "budynek.registered-hint");
    }

    private static void types(final LudnoscPlugin plugin, final Player p) {
        final PlayerBuildingService service = plugin.playerBuildings();
        final UUID country = plugin.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null);
        plugin.messages().send(p, "budynek.types-header");
        for (final Def d : service.types()) {
            final List<String> problems = country == null ? List.of() : service.requirementProblems(country, d);
            final Component status = country == null ? Component.empty()
                : problems.isEmpty() ? plugin.messages().get("budynek.types-ok")
                : plugin.messages().get("budynek.types-no", Placeholder.unparsed("reason", String.join("; ", problems)));
            final var template = plugin.templates().get(d.template());
            final int max = template == null ? 0 : template.meta().maxPerCountry();
            final String limit = (max > 0 ? "najwyżej " + max + " na kraj" : "bez limitu szablonu")
                + (d.maxRegistered() > 0 ? ", zgłoszonych najwyżej " + d.maxRegistered() : "");
            p.sendMessage(plugin.messages().get("budynek.types-line", Placeholder.unparsed("name", d.name()),
                    Placeholder.unparsed("key", d.key()), Placeholder.unparsed("needs", needs(d)),
                    Placeholder.unparsed("effect", d.effect()), Placeholder.component("status", status))
                .hoverEvent(HoverEvent.showText(plugin.messages().get("budynek.types-hover", Placeholder.unparsed("limit", limit),
                    Placeholder.unparsed("volume", Integer.toString(d.minVolume())))))
                .clickEvent(ClickEvent.suggestCommand("/ludnosc budynek zglos " + d.key() + " ")));
        }
        final PlayerBuildingRules.Limits l = service.limits();
        plugin.messages().send(p, "budynek.types-footer", Placeholder.unparsed("min", l.minSide() + "×" + l.minSide() + "×" + l.minHeight()),
            Placeholder.unparsed("max", Long.toString(l.maxVolume())));
    }

    private static Building here(final LudnoscPlugin plugin, final Player p) {
        final Location l = p.getLocation();
        Building b = plugin.data().buildingAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (b == null) {
            final Block target = p.getTargetBlockExact(6);
            if (target != null) {
                b = plugin.data().buildingAt(target.getWorld().getName(), target.getX(), target.getY(), target.getZ());
            }
        }
        return b;
    }

    private static void info(final LudnoscPlugin plugin, final Player p) {
        final Building b = here(plugin, p);
        if (b == null) {
            plugin.messages().send(p, "budynek.none-here");
            return;
        }
        final PlayerBuildingService service = plugin.playerBuildings();
        final boolean own = PlayerBuildingService.isPlayer(b);
        Building current = b;
        if (own) {
            service.recheck(b, true);
            current = plugin.data().building(b.id()) == null ? b : plugin.data().building(b.id());
        }
        final String origin = current.countryId() == null ? null
            : Optional.ofNullable(plugin.data().stateIfPresent(current.countryId())).map(st -> st.origins().get(b.id().toString())).orElse(null);
        plugin.messages().send(p, "budynek.info-header", Placeholder.parsed("name", own ? service.label(current)
            : plugin.construction().displayName(current.type())), Placeholder.unparsed("id", current.id().toString().substring(0, 8)));
        plugin.messages().send(p, "budynek.info-country", Placeholder.unparsed("country", plugin.countryName(current.countryId())),
            Placeholder.unparsed("box", current.box().toString()));
        if (own) {
            final OwnBuilding meta = service.meta(current);
            final Def d = service.def(current);
            plugin.messages().send(p, "budynek.info-own",
                Placeholder.unparsed("author", meta != null && meta.author() != null ? meta.author() : origin == null ? "?" : origin),
                Placeholder.unparsed("date", DATE.format(Instant.ofEpochMilli(current.createdAt()))));
            if (d == null) {
                plugin.messages().send(p, "budynek.info-unknown-type");
            } else if (service.working(current)) {
                plugin.messages().send(p, "budynek.info-working", Placeholder.unparsed("function", d.function().displayName()),
                    Placeholder.unparsed("effect", d.effect()));
            } else {
                plugin.messages().send(p, "budynek.info-broken", Placeholder.unparsed("function", d.function().displayName()),
                    Placeholder.unparsed("missing", meta == null || meta.problem() == null ? needs(d) : meta.problem()));
            }
            if (d != null && (d.function().housing() || d.function() == pl.medivalrpg.ludnosc.template.BuildingFunction.BARRACKS)) {
                plugin.messages().send(p, "budynek.registered-beds", Placeholder.unparsed("beds", Integer.toString(current.beds())));
            }
            plugin.messages().send(p, "budynek.info-own-hint");
        } else {
            plugin.messages().send(p, "budynek.info-template", Placeholder.unparsed("function", current.function().displayName()),
                Placeholder.unparsed("origin", origin == null || origin.isEmpty() ? (current.registered() ? "administrator" : "?") : origin));
            if (plugin.construction().repairing(current.id())) {
                plugin.messages().send(p, "budynek.info-repairing");
            } else {
                final var dmg = plugin.repairs().damageOf(current.id());
                if (dmg != null && dmg.percent() > 0) {
                    plugin.messages().send(p, "budynek.info-damaged", Placeholder.unparsed("percent", Integer.toString(dmg.percent())));
                }
            }
        }
        plugin.plans().show(p, current.box(), current.box().minY() - 1, 8);
    }

    private static void remove(final LudnoscPlugin plugin, final Player p) {
        final Building b = here(plugin, p);
        if (b == null) {
            plugin.messages().send(p, "budynek.none-here");
            return;
        }
        if (!PlayerBuildingService.isPlayer(b)) {
            plugin.messages().send(p, "budynek.not-player-building");
            return;
        }
        final boolean admin = p.hasPermission(LudnoscCommand.PERM_ADMIN);
        final UUID own = plugin.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null);
        if (!admin && (own == null || !own.equals(b.countryId()))) {
            plugin.messages().send(p, "budynek.not-own");
            return;
        }
        if (!hasRank(plugin, p)) {
            return;
        }
        final String label = plugin.playerBuildings().label(b);
        plugin.playerBuildings().unregister(b);
        plugin.messages().send(p, "budynek.removed", Placeholder.parsed("name", label));
    }
}
