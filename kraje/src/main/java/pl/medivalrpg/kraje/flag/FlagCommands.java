// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.flag;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.model.Country;

/**
 * Brigadier subtree {@code /kraj flaga …}. The image itself is uploaded in the panel (a picture cannot be sent from the
 * game chat); here the country sees the state, places the capital banner, goes back to the default flag or restores an
 * older approved one, and moderators work through the queue.
 */
public final class FlagCommands {

    private FlagCommands() {
    }

    /** @param service supplies the live service (null in tree-structure tests or when Kraje is disabled) */
    public static LiteralArgumentBuilder<CommandSourceStack> tree(final Supplier<FlagService> service) {
        return Commands.literal("flaga")
            .executes(run(service, FlagCommands::info))
            .then(Commands.literal("postaw").executes(run(service, (s, sender) -> {
                final Player p = player(sender);
                final Country c = own(s, p);
                ok(s, sender, s.placeCapital(c, p.getLocation(), p.getUniqueId(), s.isModerator(p.getUniqueId(), false)));
            })))
            .then(Commands.literal("auto").executes(run(service, (s, sender) -> {
                final Player p = player(sender);
                ok(s, sender, s.autoCapital(own(s, p), p.getUniqueId(), s.isModerator(p.getUniqueId(), false)));
            })))
            .then(Commands.literal("wycofaj").executes(run(service, (s, sender) -> {
                final Player p = player(sender);
                ok(s, sender, s.withdraw(own(s, p), p.getUniqueId(), p.getName(), s.isModerator(p.getUniqueId(), false)));
            })))
            .then(Commands.literal("domyslna")
                .executes(run(service, (s, sender) -> line(s, sender,
                    "Przywraca domyślną chorągiew w kolorze kraju. Potwierdź: /kraj flaga domyslna potwierdz")))
                .then(Commands.literal("potwierdz").executes(run(service, (s, sender) -> {
                    final Player p = player(sender);
                    ok(s, sender, s.reset(own(s, p), p.getUniqueId(), p.getName(), s.isModerator(p.getUniqueId(), false)));
                }))))
            .then(Commands.literal("historia").executes(run(service, FlagCommands::history)))
            .then(Commands.literal("sztandar")
                .executes(run(service, (s, sender) -> {
                    final Player p = player(sender);
                    s.standards().issueBanner(p, own(s, p), false);
                }))
                .then(Commands.literal("podglad").executes(run(service, FlagCommands::bannerPreview))))
            .then(Commands.literal("przedmiot").executes(run(service, (s, sender) -> {
                final Player p = player(sender);
                s.standards().issueItem(p, own(s, p), false);
            })))
            .then(Commands.literal("przywroc")
                .then(Commands.argument("flaga", StringArgumentType.word())
                    .suggests((ctx, b) -> suggestHistory(service.get(), ctx.getSource().getSender(), b))
                    .executes(ctx -> exec(service, ctx, (s, sender) -> {
                        final Player p = player(sender);
                        ok(s, sender, s.restore(own(s, p), p.getUniqueId(), p.getName(), s.isModerator(p.getUniqueId(), false),
                            StringArgumentType.getString(ctx, "flaga")));
                    }))))
            .then(Commands.literal("admin")
                .requires(src -> {
                    final FlagService s = service.get();
                    final CommandSender sender = src.getSender();
                    if (sender.hasPermission(FlagService.PERMISSION_MODERATE)) {
                        return true;
                    }
                    return s != null && sender instanceof Player p && s.isModerator(p.getUniqueId(), false);
                })
                .executes(run(service, FlagCommands::queue))
                .then(Commands.literal("kolejka").executes(run(service, FlagCommands::queue)))
                .then(Commands.literal("status").executes(run(service, FlagCommands::status)))
                .then(Commands.literal("przebuduj").executes(run(service, (s, sender) ->
                    ok(s, sender, s.forceRebuild(actor(sender), true)))))
                .then(Commands.literal("zatwierdz")
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> suggestQueue(service.get(), b))
                        .executes(ctx -> exec(service, ctx, (s, sender) -> {
                            final Country c = byName(s, StringArgumentType.getString(ctx, "kraj").trim());
                            ok(s, sender, s.approve(c, null, actor(sender), sender.getName(), true));
                        }))))
                .then(Commands.literal("odrzuc")
                    .then(Commands.argument("kraj i powód", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> suggestQueue(service.get(), b))
                        .executes(ctx -> exec(service, ctx, (s, sender) -> {
                            final String raw = StringArgumentType.getString(ctx, "kraj i powód").trim();
                            final int space = raw.indexOf(' ');
                            if (space < 0) {
                                throw new IllegalArgumentException("Użycie: /kraj flaga admin odrzuc <kraj> <powód>");
                            }
                            final Country c = byName(s, raw.substring(0, space));
                            ok(s, sender, s.reject(c, null, raw.substring(space + 1), actor(sender), sender.getName(), true));
                        }))))
                .then(Commands.literal("sztandar")
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .executes(ctx -> exec(service, ctx, (s, sender) ->
                            s.standards().issueBanner(player(sender), byName(s, StringArgumentType.getString(ctx, "kraj").trim()), true)))))
                .then(Commands.literal("przedmiot")
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .executes(ctx -> exec(service, ctx, (s, sender) ->
                            s.standards().issueItem(player(sender), byName(s, StringArgumentType.getString(ctx, "kraj").trim()), true)))))
                .then(Commands.literal("postaw")
                    .then(Commands.argument("kraj", StringArgumentType.greedyString())
                        .executes(ctx -> exec(service, ctx, (s, sender) -> {
                            final Player p = player(sender);
                            final Country c = byName(s, StringArgumentType.getString(ctx, "kraj").trim());
                            ok(s, sender, s.placeCapital(c, p.getLocation(), p.getUniqueId(), true));
                        })))));
    }

    // ------------------------------------------------------------------ actions

    private static void info(final FlagService s, final CommandSender sender) {
        final Player p = player(sender);
        final Country c = own(s, p);
        final FlagBook.Country fc = s.book().get(c.id());
        final String cur = fc == null ? null : fc.current();
        line(s, sender, "Chorągiew kraju " + c.name() + ": " + (cur == null ? "domyślna (kolor kraju)"
            : "własna flaga " + cur + (s.book().live(c.id()) ? " — w grze" : " — czeka na przebudowę paczki" + s.etaText())));
        final boolean manage = s.canManage(c, p.getUniqueId(), s.isModerator(p.getUniqueId(), false));
        if (manage && fc != null && fc.pending() != null) {
            line(s, sender, "Czeka na zatwierdzenie: flaga " + fc.pending().imageId() + " (wgrał " + fc.pending().uploaderName()
                + ", " + time(fc.pending().uploadedAt()) + "). Wycofanie: /kraj flaga wycofaj");
        }
        if (manage && fc != null && fc.lastRejection() != null) {
            line(s, sender, "Ostatnio odrzucona (" + time(fc.lastRejection().at()) + "): " + fc.lastRejection().reason());
        }
        line(s, sender, manage
            ? "Własny obrazek wgrasz w panelu WWW (/panel) → kraj → Chorągiew kraju. Każda flaga przechodzi moderację administracji."
            : "Flagą kraju zarządza Władca" + (s.settings().uploadRank() == pl.medivalrpg.kraje.model.Rank.RULER ? "." : " albo Doradca."));
        if (manage) {
            line(s, sender, "Chorągiew stolicy: /kraj flaga postaw (tutaj) albo /kraj flaga auto; domyślna: /kraj flaga domyslna; historia: /kraj flaga historia");
            final StandardRules.Settings st = s.standards().settings();
            line(s, sender, "Sztandar (wzór z barwników, na tarczę i dla wojska): /kraj flaga sztandar — " + price(s, st.banner())
                + ", dziś zostało " + s.standards().remaining(c, StandardRules.Kind.BANNER) + "; podgląd wzoru: /kraj flaga sztandar podglad");
            line(s, sender, "Przedmiot „Sztandar kraju” z dokładnym obrazkiem flagi: /kraj flaga przedmiot — " + price(s, st.item())
                + ", dziś zostało " + s.standards().remaining(c, StandardRules.Kind.ITEM));
        }
    }

    private static String price(final FlagService s, final StandardRules.Cost cost) {
        final boolean money = s.plugin().treasury().active() && cost.treasuryCost() > 0;
        return StandardRules.describe(cost.materials()) + (money ? " + " + s.plugin().treasury().format(cost.treasuryCost()) + " ze skarbca kraju" : "");
    }

    private static void bannerPreview(final FlagService s, final CommandSender sender) {
        final Player p = player(sender);
        final Country c = own(s, p);
        final BannerFit.Design d = s.designNow(c);
        if (d == null) {
            line(s, sender, "Wzór sztandaru jeszcze się oblicza — spróbuj za chwilę.");
            return;
        }
        line(s, sender, "Sztandar kraju " + c.name() + " (z " + (s.book().current(c.id()) == null ? "flagi domyślnej" : "zatwierdzonej flagi") + "): "
            + d.describe() + ". Zgodność z flagą: średni błąd ΔE " + String.format(Locale.ROOT, "%.1f", d.meanDeltaE())
            + " (jednolity kolor: " + String.format(Locale.ROOT, "%.1f", d.baselineDeltaE()) + "). Podgląd obok obrazka: panel WWW → Chorągiew kraju.");
    }

    private static void history(final FlagService s, final CommandSender sender) {
        final Player p = player(sender);
        final Country c = own(s, p);
        if (!s.canManage(c, p.getUniqueId(), s.isModerator(p.getUniqueId(), false))) {
            throw new IllegalArgumentException("Historię flag widzi Władca" + (s.settings().uploadRank() == pl.medivalrpg.kraje.model.Rank.RULER ? "." : " i Doradcy."));
        }
        final FlagBook.Country fc = s.book().get(c.id());
        if (fc == null || fc.history().isEmpty()) {
            line(s, sender, "Brak historii flag.");
            return;
        }
        line(s, sender, "Historia flag kraju " + c.name() + ":");
        for (final FlagBook.Entry e : fc.history()) {
            Component row = Component.text("• " + time(e.at()) + " " + label(e.status()) + " " + (e.imageId() == null ? "" : e.imageId())
                + " (" + e.by() + ")" + (e.note().isEmpty() ? "" : " — " + e.note()), NamedTextColor.GRAY);
            if (s.book().restorable(c.id(), e.imageId(), s.store()::hasImage)) {
                row = row.append(Component.text(" [Przywróć]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand("/kraj flaga przywroc " + e.imageId()))
                    .hoverEvent(HoverEvent.showText(Component.text("Wróć do tej flagi (bez ponownej moderacji)"))));
            }
            sender.sendMessage(row);
        }
    }

    private static void queue(final FlagService s, final CommandSender sender) {
        final var queue = s.queue();
        if (queue.isEmpty()) {
            line(s, sender, "Kolejka moderacji flag jest pusta.");
        } else {
            line(s, sender, "Flagi do zatwierdzenia (" + queue.size() + "). Podgląd obrazków: panel WWW → Moderacja chorągwi.");
            for (final Country c : queue) {
                final FlagBook.Pending p = s.book().get(c.id()).pending();
                sender.sendMessage(Component.text("• " + c.name() + " — " + p.imageId() + ", wgrał " + p.uploaderName() + " " + time(p.uploadedAt()) + " ",
                        NamedTextColor.GRAY)
                    .append(Component.text("[Zatwierdź]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.suggestCommand("/kraj flaga admin zatwierdz " + c.name())))
                    .append(Component.text(" "))
                    .append(Component.text("[Odrzuć]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.suggestCommand("/kraj flaga admin odrzuc " + c.name() + " "))));
            }
        }
        status(s, sender);
    }

    private static void status(final FlagService s, final CommandSender sender) {
        final var pack = s.packJson();
        line(s, sender, "Paczka zasobów: " + (Boolean.TRUE.equals(pack.get("available")) ? "BetterModel dostępny" : "brak BetterModel")
            + ", czeka flag: " + pack.get("queued") + ", ostatnia przebudowa: "
            + (pack.get("lastRebuildAt") instanceof Long l ? time(l) : "—") + ", następna: "
            + (Boolean.TRUE.equals(pack.get("rebuilding")) ? "trwa" : pack.get("nextRebuildAt") instanceof Long n ? time(n) : "—")
            + ", chorągwie w świecie: " + (s.banners() == null ? 0 : s.banners().activeCount())
            + ", postawione sztandary (wczytane): " + (s.standards() == null ? "—" : s.standards().placedLoaded()) + ".");
    }

    // ------------------------------------------------------------------ helpers

    @FunctionalInterface
    private interface Action {
        void run(FlagService service, CommandSender sender);
    }

    private static Command<CommandSourceStack> run(final Supplier<FlagService> service, final Action action) {
        return ctx -> exec(service, ctx, action);
    }

    private static int exec(final Supplier<FlagService> service, final CommandContext<CommandSourceStack> ctx, final Action action) {
        final CommandSender sender = ctx.getSource().getSender();
        final FlagService s = service.get();
        if (s == null || !s.enabled()) {
            sender.sendMessage(Component.text("[Kraje] Chorągwie krajów są wyłączone.", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        try {
            action.run(s, sender);
        } catch (final IllegalArgumentException e) {
            s.plugin().messages().send(sender, "flag-error", Placeholder.unparsed("text", e.getMessage() == null ? "Błąd." : e.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void ok(final FlagService s, final CommandSender sender, final String text) {
        s.plugin().messages().send(sender, "flag-ok", Placeholder.unparsed("text", text));
    }

    private static void line(final FlagService s, final CommandSender sender, final String text) {
        s.plugin().messages().send(sender, "flag-line", Placeholder.unparsed("text", text));
    }

    private static Player player(final CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        throw new IllegalArgumentException("Tę komendę może wykonać tylko gracz.");
    }

    private static UUID actor(final CommandSender sender) {
        return sender instanceof Player p ? p.getUniqueId() : null;
    }

    private static Country own(final FlagService s, final Player p) {
        final Country c = s.plugin().manager().countryOf(p.getUniqueId());
        if (c == null) {
            throw new IllegalArgumentException("Nie należysz do żadnego kraju.");
        }
        return c;
    }

    private static Country byName(final FlagService s, final String name) {
        final Country c = s.plugin().manager().byName(name);
        if (c == null) {
            throw new IllegalArgumentException("Nie ma kraju " + name + ".");
        }
        return c;
    }

    private static String time(final long millis) {
        return new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(new Date(millis));
    }

    static String label(final FlagBook.Status st) {
        return switch (st) {
            case APPROVED -> "zatwierdzona";
            case REJECTED -> "odrzucona";
            case WITHDRAWN -> "wycofana";
            case REPLACED -> "zastąpiona";
            case RESET -> "domyślna przywrócona";
            case RESTORED -> "przywrócona";
        };
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestHistory(
        final FlagService s, final CommandSender sender, final SuggestionsBuilder b) {
        if (s != null && sender instanceof Player p && org.bukkit.Bukkit.isPrimaryThread()) {
            final Country c = s.plugin().manager().countryOf(p.getUniqueId());
            final FlagBook.Country fc = c == null ? null : s.book().get(c.id());
            if (fc != null) {
                for (final FlagBook.Entry e : fc.history()) {
                    if (e.imageId() != null && e.imageId().startsWith(b.getRemainingLowerCase())
                        && s.book().restorable(c.id(), e.imageId(), s.store()::hasImage)) {
                        b.suggest(e.imageId());
                    }
                }
            }
        }
        return b.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestQueue(
        final FlagService s, final SuggestionsBuilder b) {
        if (s != null && org.bukkit.Bukkit.isPrimaryThread()) {
            for (final Country c : s.queue()) {
                if (c.name().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) {
                    b.suggest(c.name());
                }
            }
        }
        return b.buildFuture();
    }
}
