// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.BlueprintRules;
import pl.medivalrpg.ludnosc.logic.MaterialNeeds;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.Blueprint;
import pl.medivalrpg.ludnosc.service.BlueprintService;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * {@code /ludnosc kopia ...}: builders copy a player building from the country's land and build it again elsewhere
 * through the normal {@code /ludnosc buduj} preview (rotate / confirm). Texts are Polish MiniMessage constants that can
 * be overridden with {@code messages.kopia.<klucz>} in config.yml. Main thread.
 */
public final class BlueprintCommand {

    private static final List<String[]> HELP = List.of(
        new String[] {"pos1", "/ludnosc kopia pos1", "pierwszy narożnik (blok pod celownikiem albo twoja pozycja)"},
        new String[] {"pos2", "/ludnosc kopia pos2", "drugi narożnik"},
        new String[] {"zaznaczenie", "/ludnosc kopia zaznaczenie", "pokaż zaznaczenie (lub zaznaczenie WorldEdit)"},
        new String[] {"zapisz", "/ludnosc kopia zapisz <nazwa> [przód]", "zapisz budowlę jako szablon kraju (Doradca+)"},
        new String[] {"lista", "/ludnosc kopia lista", "kopie twojego kraju"},
        new String[] {"info", "/ludnosc kopia info <nazwa>", "rozmiar, koszt i materiały"},
        new String[] {"buduj", "/ludnosc kopia buduj <nazwa>", "podgląd i zamówienie budowy (Doradca+)"},
        new String[] {"nazwa", "/ludnosc kopia nazwa <stara> <nowa>", "zmień nazwę (Doradca+)"},
        new String[] {"usun", "/ludnosc kopia usun <nazwa>", "usuń kopię (Doradca+)"},
        new String[] {"wyczysc", "/ludnosc kopia wyczysc", "wyczyść narożniki"});

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
        Map.entry("header", "<prefix><gold>Kopie budowli</gold> <gray>— budowniczowie odtworzą budowlę z twojego kraju</gray>"),
        Map.entry("help-line", " <yellow><syntax></yellow> <gray>— <text></gray>"),
        Map.entry("help-hint", " <gray>Zaznacz budowlę od podłogi do dachu; najniższa warstwa stanie na gruncie. Kopiowane są "
            + "tylko bloki — bez zawartości skrzyń, bez cieczy i bloków niedozwolonych.</gray>"),
        Map.entry("players-only", "<prefix><red>Tylko dla graczy.</red>"),
        Map.entry("refused", "<prefix><red>Nie można: <reason>.</red>"),
        Map.entry("corner", "<prefix><green>Narożnik <n>:</green> <white><pos></white>"),
        Map.entry("selection", "<prefix>Zaznaczenie <gray>(<source>)</gray>: <white><size></white> <gray>(szer.×gł.×wys.), "
            + "<volume> pól</gray> <status>"),
        Map.entry("selection-none", "<prefix><yellow>Brak zaznaczenia.</yellow> <gray>Użyj /ludnosc kopia pos1 i pos2 "
            + "albo zaznacz WorldEditem.</gray>"),
        Map.entry("selection-cleared", "<prefix><gray>Narożniki wyczyszczone.</gray>"),
        Map.entry("saved", "<prefix><green>Zapisano kopię <name>.</green> <gray>Rozmiar <size>, bloków: <blocks>, koszt budowy: "
            + "<cost>.</gray>"),
        Map.entry("saved-skipped", "<prefix><yellow>Pominięto <forbidden> niedozwolonych bloków i <liquids> bloków cieczy "
            + "(zostaną puste).</yellow>"),
        Map.entry("list-header", "<prefix><gold>Kopie kraju <country></gold> <gray>(<count>/<max>)</gray>"),
        Map.entry("list-line", " <gold><name></gold> <gray>• <size> • <cost><beds></gray> "),
        Map.entry("list-empty", "<prefix><gray>Twój kraj nie ma jeszcze kopii. Zacznij od /ludnosc kopia pos1.</gray>"),
        Map.entry("not-found", "<prefix><red>Nie ma kopii „<name>”.</red> <gray>/ludnosc kopia lista</gray>"),
        Map.entry("deleted", "<prefix><green>Usunięto kopię <name>.</green>"),
        Map.entry("deleted-kept", "<prefix><green>Usunięto kopię <name>.</green> <gray>Stojące budynki z tej kopii nadal "
            + "będą naprawiane.</gray>"),
        Map.entry("renamed", "<prefix><green>Zmieniono nazwę kopii na <name>.</green>"),
        Map.entry("build-start", "<prefix><gray>Plan kopii <name> — obróć i potwierdź jak zwykłą budowę.</gray>"),
        Map.entry("info-header", "<prefix><gold>Kopia <name></gold> <gray>(autor <author>, <date>)</gray>"),
        Map.entry("info-size", " <gray>Rozmiar:</gray> <white><size></white> <gray>(szer.×gł.×wys.), bloków:</gray> <white><blocks></white>"),
        Map.entry("info-function", " <gray>Funkcja:</gray> <white><function></white>"),
        Map.entry("info-cost", " <gray>Koszt budowy:</gray> <white><cost></white> <gray>ze skarbca kraju</gray>"),
        Map.entry("info-store", " <gray>Z magazynu kraju:</gray> <white><store></white> <gray>(reszta materiałów w koszcie)</gray>"),
        Map.entry("info-materials", " <gray>Materiały:</gray> <materials>"),
        Map.entry("info-skipped", " <gray>Pominięte przy kopiowaniu: <forbidden> niedozwolonych, <liquids> cieczy</gray>"));

    private BlueprintCommand() {
    }

    // ------------------------------------------------------------------ brigadier

    /** {@code kopia} node of the {@code /ludnosc} tree; execution goes through {@link LudnoscCommand#run}. */
    static LiteralArgumentBuilder<CommandSourceStack> node(final LudnoscCommand cmd) {
        return Commands.literal("kopia")
            .executes(ctx -> {
                cmd.run(ctx.getSource().getSender(), "kopia");
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("akcja", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggestions(cmd, ctx.getSource().getSender(), builder))
                .executes(ctx -> {
                    final String[] tokens = LudnoscCommandTree.tokens(StringArgumentType.getString(ctx, "akcja"));
                    final String[] args = new String[tokens.length + 1];
                    args[0] = "kopia";
                    System.arraycopy(tokens, 0, args, 1, tokens.length);
                    cmd.run(ctx.getSource().getSender(), args);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static CompletableFuture<Suggestions> suggestions(final LudnoscCommand cmd, final CommandSender sender,
                                                              final SuggestionsBuilder builder) {
        if (Bukkit.isPrimaryThread()) {
            fill(cmd.plugin(), sender, builder);
            return builder.buildFuture();
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                fill(cmd.plugin(), sender, builder);
                return builder.build();
            }, Bukkit.getScheduler().getMainThreadExecutor(cmd.plugin()));
        } catch (final RuntimeException e) {
            return Suggestions.empty();
        }
    }

    private static void fill(final LudnoscPlugin plugin, final CommandSender sender, final SuggestionsBuilder builder) {
        final String rem = builder.getRemaining();
        final int lastSpace = rem.lastIndexOf(' ');
        final String[] words = rem.trim().isEmpty() ? new String[0] : rem.trim().split(" +");
        final int index = lastSpace < 0 ? 0 : (rem.endsWith(" ") ? words.length : words.length - 1);
        final SuggestionsBuilder sub = builder.createOffset(builder.getStart() + lastSpace + 1);
        final String typed = lastSpace < 0 ? rem.toLowerCase(Locale.ROOT) : rem.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        if (index == 0) {
            for (final String[] h : HELP) {
                suggest(sub, typed, h[0], h[2]);
            }
        } else {
            final String action = words[0].toLowerCase(Locale.ROOT);
            if (index == 1 && List.of("info", "usun", "buduj", "nazwa").contains(action) && sender instanceof Player p
                && plugin.blueprints() != null) {
                plugin.kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
                    for (final Blueprint b : plugin.blueprints().list(k.id())) {
                        suggest(sub, typed, b.name(), size(b.template()) + " • " + plugin.formatMoney(b.template().meta().cost()));
                    }
                });
            } else if (index == 1 && action.equals("zapisz")) {
                suggest(sub, typed, "<nazwa>", "2–24 znaki: litery, cyfry, _ -");
            } else if (index == 2 && action.equals("zapisz")) {
                for (final String d : List.of("poludnie", "polnoc", "wschod", "zachod")) {
                    suggest(sub, typed, d, "strona z wejściem (domyślnie wykrywana po drzwiach)");
                }
            } else if (index == 2 && action.equals("nazwa")) {
                suggest(sub, typed, "<nowa>", "nowa nazwa");
            }
        }
        builder.add(sub);
    }

    private static void suggest(final SuggestionsBuilder b, final String typed, final String text, final String tooltip) {
        if (text.toLowerCase(Locale.ROOT).startsWith(typed)) {
            b.suggest(text, MessageComponentSerializer.message().serialize(Component.text(tooltip, NamedTextColor.GRAY)));
        }
    }

    // ------------------------------------------------------------------ dispatch

    /** {@code args} = words after {@code kopia}. */
    static void run(final LudnoscPlugin plugin, final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            send(plugin, sender, "players-only");
            return;
        }
        final BlueprintService service = plugin.blueprints();
        if (service == null) {
            send(plugin, p, "refused", Placeholder.unparsed("reason", "kopie nie są dostępne"));
            return;
        }
        final String action = args.length == 0 ? "pomoc" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (action) {
            case "pos1", "pos2" -> {
                final int[] pos = service.setCorner(p, action.equals("pos1") ? 1 : 2);
                send(plugin, p, "corner", Placeholder.unparsed("n", action.substring(3)),
                    Placeholder.unparsed("pos", pos[0] + " " + pos[1] + " " + pos[2]));
                if (service.ownCorners(p)) {
                    showSelection(plugin, p);
                }
            }
            case "zaznaczenie", "pokaz" -> showSelection(plugin, p);
            case "wyczysc" -> {
                service.clearSelection(p);
                send(plugin, p, "selection-cleared");
            }
            case "zapisz" -> save(plugin, p, rest);
            case "lista" -> list(plugin, p);
            case "info" -> info(plugin, p, rest);
            case "usun" -> delete(plugin, p, rest);
            case "nazwa" -> rename(plugin, p, rest);
            case "buduj" -> build(plugin, p, rest);
            default -> help(plugin, p);
        }
    }

    private static void help(final LudnoscPlugin plugin, final Player p) {
        send(plugin, p, "header");
        for (final String[] h : HELP) {
            final int a = h[1].indexOf(" <");
            final int o = h[1].indexOf(" [");
            final int cut = a < 0 ? o : o < 0 ? a : Math.min(a, o);
            final String typed = cut < 0 ? h[1] : h[1].substring(0, cut + 1);
            p.sendMessage(text(plugin, "help-line", Placeholder.unparsed("syntax", h[1]), Placeholder.unparsed("text", h[2]))
                .clickEvent(ClickEvent.suggestCommand(typed))
                .hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby wpisać", NamedTextColor.GRAY))));
        }
        send(plugin, p, "help-hint");
    }

    private static void showSelection(final LudnoscPlugin plugin, final Player p) {
        final BlueprintService service = plugin.blueprints();
        final BlockBox box = service.selection(p);
        if (box == null) {
            send(plugin, p, "selection-none");
            return;
        }
        final int sx = box.maxX() - box.minX() + 1;
        final int sy = box.maxY() - box.minY() + 1;
        final int sz = box.maxZ() - box.minZ() + 1;
        final String problem = BlueprintRules.sizeProblem(sx, sy, sz, service.limits());
        send(plugin, p, "selection", Placeholder.unparsed("source", service.ownCorners(p) ? "narożniki" : "WorldEdit"),
            Placeholder.unparsed("size", sx + "×" + sz + "×" + sy), Placeholder.unparsed("volume", Long.toString(box.volume())),
            Placeholder.component("status", problem == null ? Component.text("✔ rozmiar w limicie", NamedTextColor.GREEN)
                : Component.text("✘ " + problem, NamedTextColor.RED)));
        plugin.plans().show(p, box, box.minY() - 1, 20);
    }

    // ------------------------------------------------------------------ actions

    /** Player's own country with the build rank (admins always pass); messages on failure. */
    private static Optional<KrajInfo> country(final LudnoscPlugin plugin, final Player p, final boolean needRank) {
        final Optional<KrajInfo> own = plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            plugin.messages().send(p, "no-country");
            return own;
        }
        if (!needRank) {
            return own;
        }
        final Rank need = plugin.settings().buildRank;
        final boolean ok = p.hasPermission(LudnoscCommand.PERM_ADMIN)
            || plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(need)).orElse(false);
        if (!ok) {
            plugin.messages().send(p, "rank-too-low", Placeholder.unparsed("rank", need.displayName()));
            return Optional.empty();
        }
        return own;
    }

    private static void save(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        if (rest.length == 0) {
            refuse(plugin, p, "podaj nazwę: /ludnosc kopia zapisz <nazwa> [przód]");
            return;
        }
        String front = null;
        if (rest.length > 1) {
            front = BlueprintRules.direction(rest[1]);
            if (front == null) {
                refuse(plugin, p, "przód to polnoc, wschod, poludnie albo zachod");
                return;
            }
        }
        final String name = rest[0];
        final String frontArg = front;
        country(plugin, p, true).ifPresent(k -> {
            final BlueprintService.SaveResult r = plugin.blueprints().save(p, k.id(), name, frontArg,
                p.hasPermission(LudnoscCommand.PERM_ADMIN));
            if (r.error() != null) {
                refuse(plugin, p, r.error());
                return;
            }
            final Blueprint b = r.blueprint();
            send(plugin, p, "saved", Placeholder.unparsed("name", b.name()), Placeholder.unparsed("size", size(b.template())),
                Placeholder.unparsed("blocks", Integer.toString(b.template().solidCount())),
                Placeholder.unparsed("cost", plugin.formatMoney(b.template().meta().cost())));
            if (b.forbidden() > 0 || b.liquids() > 0) {
                send(plugin, p, "saved-skipped", Placeholder.unparsed("forbidden", Integer.toString(b.forbidden())),
                    Placeholder.unparsed("liquids", Integer.toString(b.liquids())));
            }
            p.sendMessage(buttons(b));
        });
    }

    private static void list(final LudnoscPlugin plugin, final Player p) {
        country(plugin, p, false).ifPresent(k -> {
            final List<Blueprint> all = plugin.blueprints().list(k.id());
            if (all.isEmpty()) {
                send(plugin, p, "list-empty");
                return;
            }
            send(plugin, p, "list-header", Placeholder.unparsed("country", k.name()),
                Placeholder.unparsed("count", Integer.toString(all.size())),
                Placeholder.unparsed("max", Integer.toString(plugin.blueprints().limits().perCountry())));
            for (final Blueprint b : all) {
                final BuildingTemplate t = b.template();
                p.sendMessage(text(plugin, "list-line", Placeholder.unparsed("name", b.name()), Placeholder.unparsed("size", size(t)),
                        Placeholder.unparsed("cost", plugin.formatMoney(t.meta().cost())),
                        Placeholder.unparsed("beds", t.bedCount() > 0 ? " • łóżka: " + t.bedCount() : ""))
                    .hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby zobaczyć szczegóły", NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.runCommand("/ludnosc kopia info " + b.name()))
                    .append(buttons(b)));
            }
        });
    }

    private static Optional<Blueprint> named(final LudnoscPlugin plugin, final Player p, final KrajInfo k, final String[] rest) {
        if (rest.length == 0) {
            refuse(plugin, p, "podaj nazwę kopii");
            return Optional.empty();
        }
        final Optional<Blueprint> b = plugin.blueprints().find(k.id(), rest[0]);
        if (b.isEmpty()) {
            send(plugin, p, "not-found", Placeholder.unparsed("name", rest[0]));
        }
        return b;
    }

    private static void info(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        country(plugin, p, false).flatMap(k -> named(plugin, p, k, rest)).ifPresent(b -> {
            final BuildingTemplate t = b.template();
            send(plugin, p, "info-header", Placeholder.unparsed("name", b.name()),
                Placeholder.unparsed("author", b.author().isEmpty() ? "?" : b.author()),
                Placeholder.unparsed("date", b.createdAt() <= 0 ? "?" : Instant.ofEpochMilli(b.createdAt())
                    .atZone(ZoneId.systemDefault()).toLocalDate().toString()));
            send(plugin, p, "info-size", Placeholder.unparsed("size", size(t)),
                Placeholder.unparsed("blocks", Integer.toString(t.solidCount())));
            send(plugin, p, "info-function", Placeholder.unparsed("function", t.meta().function() == BuildingFunction.HOUSING
                ? "mieszkanie (łóżka: " + t.bedCount() + ")" : "budowla bez funkcji (dekoracyjna)"));
            send(plugin, p, "info-cost", Placeholder.unparsed("cost", plugin.formatMoney(t.meta().cost())));
            final Map<String, Long> store = BlueprintRules.storeNeeds(t);
            final List<String> parts = new ArrayList<>();
            for (final Map.Entry<String, Long> e : store.entrySet()) {
                parts.add(MaterialNeeds.displayName(e.getKey()) + " " + e.getValue());
            }
            send(plugin, p, "info-store", Placeholder.unparsed("store", parts.isEmpty() ? "nic" : String.join(", ", parts)));
            final List<Component> mats = new ArrayList<>();
            final List<Map.Entry<String, Integer>> all = BlueprintRules.materials(t);
            for (int i = 0; i < Math.min(12, all.size()); i++) {
                final Map.Entry<String, Integer> e = all.get(i);
                mats.add(Component.translatable("block.minecraft." + e.getKey(), NamedTextColor.WHITE)
                    .append(Component.text(" ×" + e.getValue(), NamedTextColor.GRAY)));
            }
            if (all.size() > 12) {
                mats.add(Component.text("i " + (all.size() - 12) + " innych", NamedTextColor.GRAY));
            }
            send(plugin, p, "info-materials", Placeholder.component("materials",
                Component.join(JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)), mats)));
            if (b.forbidden() > 0 || b.liquids() > 0) {
                send(plugin, p, "info-skipped", Placeholder.unparsed("forbidden", Integer.toString(b.forbidden())),
                    Placeholder.unparsed("liquids", Integer.toString(b.liquids())));
            }
            p.sendMessage(buttons(b));
        });
    }

    private static void delete(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        country(plugin, p, true).ifPresent(k -> {
            if (rest.length == 0) {
                refuse(plugin, p, "podaj nazwę kopii");
                return;
            }
            final Optional<Blueprint> removed = plugin.blueprints().delete(k.id(), rest[0]);
            if (removed.isEmpty()) {
                send(plugin, p, "not-found", Placeholder.unparsed("name", rest[0]));
                return;
            }
            final boolean kept = plugin.templates().get(removed.get().templateId()) != null;
            send(plugin, p, kept ? "deleted-kept" : "deleted", Placeholder.unparsed("name", removed.get().name()));
        });
    }

    private static void rename(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        country(plugin, p, true).ifPresent(k -> {
            if (rest.length < 2) {
                refuse(plugin, p, "użycie: /ludnosc kopia nazwa <stara> <nowa>");
                return;
            }
            final String problem = plugin.blueprints().rename(k.id(), rest[0], rest[1]);
            if (problem != null) {
                refuse(plugin, p, problem);
                return;
            }
            send(plugin, p, "renamed", Placeholder.unparsed("name", rest[1]));
        });
    }

    private static void build(final LudnoscPlugin plugin, final Player p, final String[] rest) {
        country(plugin, p, true).flatMap(k -> named(plugin, p, k, rest).map(b -> Map.entry(k, b))).ifPresent(e -> {
            final Blueprint b = e.getValue();
            // only the owning country, never a deleted copy (ConstructionService checks the same on confirm)
            if (!plugin.blueprints().mayOrder(e.getKey().id(), b.templateId())) {
                refuse(plugin, p, "tej kopii nie można zamówić");
                return;
            }
            send(plugin, p, "build-start", Placeholder.unparsed("name", b.name()));
            plugin.plans().create(p, e.getKey().id(), b.templateId());
        });
    }

    // ------------------------------------------------------------------ helpers

    private static Component buttons(final Blueprint b) {
        return Component.text(" ")
            .append(button("[Buduj]", NamedTextColor.GREEN, "/ludnosc kopia buduj " + b.name(), "Podgląd budowy z obrotem", true))
            .append(Component.text(" "))
            .append(button("[Info]", NamedTextColor.AQUA, "/ludnosc kopia info " + b.name(), "Rozmiar, koszt, materiały", true))
            .append(Component.text(" "))
            .append(button("[Nazwa]", NamedTextColor.YELLOW, "/ludnosc kopia nazwa " + b.name() + " ", "Zmień nazwę", false))
            .append(Component.text(" "))
            .append(button("[Usuń]", NamedTextColor.RED, "/ludnosc kopia usun " + b.name(), "Wpisz komendę usunięcia", false));
    }

    private static Component button(final String label, final NamedTextColor color, final String command, final String hover,
                                    final boolean run) {
        return Component.text(label, color)
            .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)))
            .clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command));
    }

    static String size(final BuildingTemplate t) {
        return t.width() + "×" + t.depth() + "×" + t.height();
    }

    private static String raw(final LudnoscPlugin plugin, final String key) {
        final String full = "kopia." + key;
        return plugin.messages().has(full) ? plugin.messages().raw(full) : DEFAULTS.getOrDefault(key, key);
    }

    private static Component text(final LudnoscPlugin plugin, final String key, final TagResolver... resolvers) {
        return plugin.messages().parse(raw(plugin, key), resolvers);
    }

    private static void send(final LudnoscPlugin plugin, final CommandSender to, final String key, final TagResolver... resolvers) {
        to.sendMessage(text(plugin, key, resolvers));
    }

    private static void refuse(final LudnoscPlugin plugin, final CommandSender to, final String reason) {
        send(plugin, to, "refused", Placeholder.unparsed("reason", reason));
    }
}
