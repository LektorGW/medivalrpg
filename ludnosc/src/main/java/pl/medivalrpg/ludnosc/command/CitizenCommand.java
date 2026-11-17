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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.LifeTexts;
import pl.medivalrpg.ludnosc.logic.DailyRoutine;
import pl.medivalrpg.ludnosc.logic.Economy;
import pl.medivalrpg.ludnosc.logic.Family;
import pl.medivalrpg.ludnosc.logic.Workplaces;
import pl.medivalrpg.ludnosc.model.CitizenLife;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.service.LifeService;
import pl.medivalrpg.ludnosc.storage.LifeStore;

/**
 * {@code /ludnosc obywatel [imię]} (card of one citizen: home, work, day rhythm, wages, savings, family) and
 * {@code /ludnosc obywatele [kraj]} (statistics) / {@code /ludnosc obywatele chatki wl|wyl|limit <n>} (Doradca+).
 * Texts: {@link LifeTexts}. Main thread.
 */
public final class CitizenCommand {

    private static final Map<String, String> PROFESSIONS = Map.ofEntries(
        Map.entry("none", "bez zawodu"), Map.entry("nitwit", "próżniak"), Map.entry("farmer", "rolnik"),
        Map.entry("fisherman", "rybak"), Map.entry("shepherd", "pasterz"), Map.entry("fletcher", "łuczarz"),
        Map.entry("librarian", "bibliotekarz"), Map.entry("cartographer", "kartograf"), Map.entry("cleric", "duchowny"),
        Map.entry("armorer", "płatnerz"), Map.entry("weaponsmith", "miecznik"), Map.entry("toolsmith", "narzędziowiec"),
        Map.entry("butcher", "rzeźnik"), Map.entry("leatherworker", "garbarz"), Map.entry("mason", "kamieniarz"));

    private CitizenCommand() {
    }

    // ------------------------------------------------------------------ Brigadier

    static LiteralArgumentBuilder<CommandSourceStack> node(final LudnoscCommand cmd, final String literal) {
        return Commands.literal(literal)
            .executes(ctx -> {
                cmd.run(ctx.getSource().getSender(), literal);
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument(literal.equals("obywatel") ? "imie" : "kraj_lub_chatki", StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggestions(cmd, literal, ctx.getSource().getSender(), builder))
                .executes(ctx -> {
                    final String[] tokens = LudnoscCommandTree.tokens(StringArgumentType.getString(ctx,
                        literal.equals("obywatel") ? "imie" : "kraj_lub_chatki"));
                    final String[] args = new String[tokens.length + 1];
                    args[0] = literal;
                    System.arraycopy(tokens, 0, args, 1, tokens.length);
                    cmd.run(ctx.getSource().getSender(), args);
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static CompletableFuture<Suggestions> suggestions(final LudnoscCommand cmd, final String literal,
                                                              final CommandSender sender, final SuggestionsBuilder builder) {
        if (Bukkit.isPrimaryThread()) {
            fill(cmd.plugin(), literal, sender, builder);
            return builder.buildFuture();
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                fill(cmd.plugin(), literal, sender, builder);
                return builder.build();
            }, Bukkit.getScheduler().getMainThreadExecutor(cmd.plugin()));
        } catch (final RuntimeException e) {
            return Suggestions.empty();
        }
    }

    private static void fill(final LudnoscPlugin plugin, final String literal, final CommandSender sender,
                             final SuggestionsBuilder builder) {
        final String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (literal.equals("obywatel")) {
            if (sender instanceof Player p) {
                plugin.kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
                    int n = 0;
                    for (final CitizenRecord r : plugin.data().citizensOf(k.id())) {
                        if (n < 40 && r.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                            suggest(builder, r.name(), "obywatel " + k.name());
                            n++;
                        }
                    }
                });
            }
            return;
        }
        for (final String s : List.of("chatki wl", "chatki wyl", "chatki limit ")) {
            if (s.startsWith(typed)) {
                suggest(builder, s, "samodzielne chaty obywateli (Doradca+)");
            }
        }
        for (final KrajInfo k : plugin.kraje().getCountries()) {
            if (k.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                suggest(builder, k.name(), "statystyki obywateli kraju");
            }
        }
    }

    private static void suggest(final SuggestionsBuilder b, final String text, final String tooltip) {
        b.suggest(text, MessageComponentSerializer.message().serialize(Component.text(tooltip, NamedTextColor.GRAY)));
    }

    // ------------------------------------------------------------------ dispatch

    /** {@code sub}: obywatel | obywatele. */
    static void run(final LudnoscPlugin plugin, final CommandSender sender, final String sub, final String[] rest) {
        final LifeService life = plugin.life();
        if (life == null || !life.enabled()) {
            send(plugin, sender, "disabled");
            return;
        }
        if (sub.equals("obywatel")) {
            card(plugin, life, sender, rest);
        } else if (rest.length > 0 && rest[0].equalsIgnoreCase("chatki")) {
            huts(plugin, life, sender, rest);
        } else {
            stats(plugin, life, sender, rest);
        }
    }

    // ------------------------------------------------------------------ one citizen

    private static void card(final LudnoscPlugin plugin, final LifeService life, final CommandSender sender, final String[] rest) {
        CitizenRecord rec = null;
        if (rest.length == 0) {
            if (!(sender instanceof Player p)) {
                send(plugin, sender, "players-only");
                return;
            }
            final Entity target = p.getTargetEntity(8);
            if (target instanceof Villager v) {
                rec = plugin.data().citizen(v.getUniqueId());
            }
            if (rec == null) {
                send(plugin, sender, "look-at");
                return;
            }
        } else {
            final String name = String.join(" ", rest).trim();
            rec = find(plugin, sender, name);
            if (rec == null) {
                send(plugin, sender, "not-found", Placeholder.unparsed("name", name));
                return;
            }
        }
        if (life.life(rec.entityId()) == null) {
            life.assign(rec.countryId());
        }
        final CitizenLife lf = life.life(rec.entityId());
        final String prof = PROFESSIONS.getOrDefault(Economy.professionKey(rec.profession()), Economy.professionKey(rec.profession()));
        send(plugin, sender, "card-header", Placeholder.unparsed("name", rec.name()),
            Placeholder.unparsed("country", plugin.countryName(rec.countryId())),
            Placeholder.unparsed("age", rec.adult() ? "dorosły" : "dziecko"), Placeholder.unparsed("profession", prof));
        if (lf != null && lf.home != null) {
            send(plugin, sender, "card-home", Placeholder.unparsed("home", life.describePlace(lf.home)
                + (lf.bed >= 0 ? ", łóżko " + (lf.bed + 1) : "")));
        } else {
            send(plugin, sender, "card-homeless");
        }
        final Workplaces.Kind kind = lf == null ? null : Workplaces.Kind.byKey(lf.work);
        if (kind != null) {
            send(plugin, sender, "card-work", Placeholder.unparsed("work", kind.title()),
                Placeholder.unparsed("place", life.describePlace(lf.workplace)),
                Placeholder.unparsed("wage", plugin.formatMoney(life.settings().wageOf(kind))));
        } else {
            send(plugin, sender, "card-jobless", Placeholder.unparsed("work", rec.adult() ? "bezrobotny" : "dziecko — nie pracuje"));
        }
        final World w = Bukkit.getWorld(rec.world());
        final long time = w == null ? 6000 : w.getFullTime();
        final DailyRoutine routine = life.settings().routine();
        final boolean loaded = Bukkit.getEntity(rec.entityId()) != null;
        send(plugin, sender, "card-routine", Placeholder.unparsed("clock", DailyRoutine.clock(time)),
            Placeholder.unparsed("phase", routine.phase(time).title()),
            Placeholder.unparsed("where", life.routinePlace(rec).title()),
            Placeholder.unparsed("loaded", loaded ? "" : " (poza zasięgiem — życie liczone w rejestrze)"));
        if (lf != null) {
            send(plugin, sender, "card-money", Placeholder.unparsed("savings", plugin.formatMoney(lf.savings)),
                Placeholder.unparsed("last", plugin.formatMoney(lf.lastWage)), Placeholder.unparsed("due", plugin.formatMoney(lf.lastDue)),
                Placeholder.unparsed("unpaid", lf.unpaidDays > 0 ? " (bez pełnej pensji: " + lf.unpaidDays + " dni)" : ""),
                Placeholder.unparsed("earned", plugin.formatMoney(lf.earned)), Placeholder.unparsed("spent", plugin.formatMoney(lf.spent)));
            send(plugin, sender, "card-family", Placeholder.unparsed("family", family(plugin, rec, lf)));
            if (lf.lifeStart > 0) {
                send(plugin, sender, "card-age", Placeholder.unparsed("age",
                    Long.toString(LifeService.ageDays(lf, System.currentTimeMillis()))),
                    Placeholder.unparsed("lifespan", Long.toString(lf.lifespan)));
            }
        }
    }

    private static String family(final LudnoscPlugin plugin, final CitizenRecord rec, final CitizenLife l) {
        final List<String> parts = new ArrayList<>();
        if (l.partner != null) {
            final CitizenRecord p = plugin.data().citizen(l.partner);
            if (p != null) {
                parts.add((Family.female(p.name()) ? "żona " : "mąż ") + p.name());
            }
        }
        final List<String> kids = new ArrayList<>();
        final LifeService life = plugin.life();
        for (final CitizenRecord r : plugin.data().citizensOf(rec.countryId())) {
            final CitizenLife cl = life.life(r.entityId());
            if (cl != null && (rec.entityId().equals(cl.mother) || rec.entityId().equals(cl.father))) {
                kids.add(r.name() + (r.adult() ? "" : " (dziecko)"));
            }
        }
        if (!kids.isEmpty()) {
            parts.add("dzieci: " + String.join(", ", kids));
        }
        final List<String> parents = new ArrayList<>();
        for (final UUID id : new UUID[] {l.mother, l.father}) {
            if (id != null) {
                final CitizenRecord p = plugin.data().citizen(id);
                parents.add(p == null ? "†" : p.name());
            }
        }
        if (!parents.isEmpty()) {
            parts.add("rodzice: " + String.join(" i ", parents));
        }
        return parts.isEmpty() ? (rec.adult() ? "stanu wolnego" : "—") : String.join("; ", parts);
    }

    /** Citizen by name: the sender's country first, nearest to the sender. */
    private static CitizenRecord find(final LudnoscPlugin plugin, final CommandSender sender, final String name) {
        final String wanted = name.toLowerCase(Locale.ROOT);
        final Optional<KrajInfo> own = sender instanceof Player p ? plugin.kraje().getCountryOf(p.getUniqueId()) : Optional.empty();
        final List<CitizenRecord> found = new ArrayList<>();
        final List<CitizenRecord> pool = own.isPresent() ? plugin.data().citizensOf(own.get().id()) : List.copyOf(plugin.data().allCitizens());
        for (final CitizenRecord r : pool) {
            if (r.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                found.add(r);
            }
        }
        if (found.isEmpty() && own.isPresent()) {
            for (final CitizenRecord r : plugin.data().allCitizens()) {
                if (r.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                    found.add(r);
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        if (found.size() > 1) {
            send(plugin, sender, "ambiguous", Placeholder.unparsed("name", name));
            if (sender instanceof Player p) {
                final Location at = p.getLocation();
                found.sort(java.util.Comparator.comparingDouble(r -> r.world().equals(at.getWorld().getName())
                    ? Math.pow(r.x() - at.getX(), 2) + Math.pow(r.z() - at.getZ(), 2) : Double.MAX_VALUE));
            }
        }
        return found.get(0);
    }

    // ------------------------------------------------------------------ statistics

    private static void stats(final LudnoscPlugin plugin, final LifeService life, final CommandSender sender, final String[] rest) {
        final KrajInfo k;
        if (rest.length > 0) {
            final String name = String.join(" ", rest).trim();
            k = plugin.kraje().getCountryByName(name).orElse(null);
            if (k == null) {
                plugin.messages().send(sender, "country-not-found", Placeholder.unparsed("name", name));
                return;
            }
        } else if (sender instanceof Player p && plugin.kraje().getCountryOf(p.getUniqueId()).isPresent()) {
            k = plugin.kraje().getCountryOf(p.getUniqueId()).get();
        } else {
            send(plugin, sender, "no-country");
            return;
        }
        life.assign(k.id());
        final LifeService.Stats s = life.stats(k.id());
        final LifeStore.CountryLife cl = life.countryLife(k.id());
        send(plugin, sender, "stats-header", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("age", Long.toString(Math.max(0, (System.currentTimeMillis() - s.time()) / 1000))));
        send(plugin, sender, "stats-people", Placeholder.unparsed("population", Integer.toString(s.population())),
            Placeholder.unparsed("adults", Integer.toString(s.adults())), Placeholder.unparsed("children", Integer.toString(s.children())),
            Placeholder.unparsed("couples", Integer.toString(s.couples())));
        send(plugin, sender, "stats-homes", Placeholder.unparsed("housed", Integer.toString(s.housed())),
            Placeholder.unparsed("homeless", Integer.toString(s.homeless())),
            Placeholder.styling("homeless_color", s.homeless() > 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        send(plugin, sender, "stats-work", Placeholder.unparsed("employed", Integer.toString(s.employed())),
            Placeholder.unparsed("unemployed", Integer.toString(s.unemployed())));
        send(plugin, sender, "stats-money", Placeholder.unparsed("savings", plugin.formatMoney(s.savings())),
            Placeholder.unparsed("average", plugin.formatMoney(s.average())),
            Placeholder.unparsed("paid", plugin.formatMoney(cl.lastWagesPaid)), Placeholder.unparsed("due", plugin.formatMoney(cl.lastWagesDue)),
            Placeholder.unparsed("meals", plugin.formatMoney(cl.lastMeals)), Placeholder.unparsed("unpaid", Integer.toString(cl.lastUnpaid)),
            Placeholder.unparsed("income", plugin.formatMoney(cl.lastIncome)),
            Placeholder.unparsed("tax_paid", plugin.formatMoney(cl.lastTaxPaid)), Placeholder.unparsed("tax_due", plugin.formatMoney(cl.lastTaxDue)));
        final long cost = life.hutCost();
        final String reason = life.hutReason(k.id());
        send(plugin, sender, "stats-huts", Placeholder.unparsed("state", life.hutsEnabled(k.id()) ? "włączone" : "wyłączone"),
            Placeholder.unparsed("limit", Integer.toString(life.hutLimit(k.id()))),
            Placeholder.unparsed("progress", Integer.toString(life.hutsInProgress(k.id()))),
            Placeholder.unparsed("total", Integer.toString(cl.hutsOrdered)),
            Placeholder.unparsed("cost", cost < 0 ? "?" : plugin.formatMoney(cost)),
            Placeholder.unparsed("reason", reason == null ? "jeszcze nie sprawdzano" : reason),
            Placeholder.unparsed("wood", life.fellReason(k.id()) == null ? "—" : life.fellReason(k.id())));
    }

    // ------------------------------------------------------------------ huts setting

    private static void huts(final LudnoscPlugin plugin, final LifeService life, final CommandSender sender, final String[] rest) {
        if (!(sender instanceof Player p)) {
            send(plugin, sender, "players-only");
            return;
        }
        final KrajInfo k = plugin.kraje().getCountryOf(p.getUniqueId()).orElse(null);
        if (k == null) {
            send(plugin, sender, "no-country");
            return;
        }
        final boolean allowed = p.hasPermission(LudnoscCommand.PERM_ADMIN)
            || plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(Rank.ADVISOR)).orElse(false);
        if (!allowed) {
            send(plugin, sender, "no-rank");
            return;
        }
        if (rest.length < 2) {
            send(plugin, sender, "huts-usage");
            return;
        }
        switch (rest[1].toLowerCase(Locale.ROOT)) {
            case "wl", "wlacz", "on" -> life.setHuts(k.id(), true, null);
            case "wyl", "wylacz", "off" -> life.setHuts(k.id(), false, null);
            case "limit" -> {
                final int n;
                try {
                    n = rest.length > 2 ? Integer.parseInt(rest[2]) : -1;
                } catch (final NumberFormatException e) {
                    send(plugin, sender, "huts-usage");
                    return;
                }
                if (n < 0) {
                    send(plugin, sender, "huts-usage");
                    return;
                }
                life.setHuts(k.id(), null, n);
            }
            default -> {
                send(plugin, sender, "huts-usage");
                return;
            }
        }
        send(plugin, sender, "huts-set", Placeholder.unparsed("state", life.hutsEnabled(k.id()) ? "włączone" : "wyłączone"),
            Placeholder.unparsed("limit", Integer.toString(life.hutLimit(k.id()))));
    }

    private static void send(final LudnoscPlugin plugin, final CommandSender to, final String key, final TagResolver... resolvers) {
        if (!LifeTexts.raw(plugin.messages(), key).isEmpty()) {
            to.sendMessage(LifeTexts.text(plugin.messages(), key, resolvers));
        }
    }
}
