// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.logic.MaterialNeeds;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.service.ConstructionService;
import pl.medivalrpg.ludnosc.service.ProductionStorageService;
import pl.medivalrpg.ludnosc.template.BuildingFunction;

/**
 * {@code /ludnosc budowy} and {@code /ludnosc gospodarka}: what the country produces, where the goods lie, what the
 * granary holds, what the building sites need and wait for, which haulers are on the way and what is missing.
 * Reads only loaded containers (main thread); nothing is changed.
 */
final class FlowCommand {

    private final LudnoscPlugin plugin;

    FlowCommand(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    private Optional<KrajInfo> country(final CommandSender sender, final String[] args) {
        if (args.length > 0 && sender.hasPermission(LudnoscCommand.PERM_ADMIN)) {
            final Optional<KrajInfo> k = this.plugin.kraje().getCountryByName(String.join(" ", args).trim());
            if (k.isEmpty()) {
                sender.sendMessage(Component.text("Nie ma takiego kraju.", NamedTextColor.RED));
            }
            return k;
        }
        if (sender instanceof Player p) {
            final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
            if (own.isEmpty()) {
                sender.sendMessage(Component.text("Nie należysz do żadnego kraju.", NamedTextColor.RED));
            }
            return own;
        }
        sender.sendMessage(Component.text("Podaj nazwę kraju.", NamedTextColor.RED));
        return Optional.empty();
    }

    private static Component header(final String text) {
        return Component.text("— " + text + " —", NamedTextColor.GOLD);
    }

    private static Component line(final String label, final String value, final NamedTextColor color) {
        return Component.text(" " + label + ": ", NamedTextColor.GRAY).append(Component.text(value, color));
    }

    private static String units(final Map<String, ? extends Number> m) {
        final List<String> parts = new ArrayList<>();
        for (final String g : MaterialNeeds.GROUPS) {
            final Number v = m.get(g);
            if (v != null && v.longValue() > 0) {
                parts.add(MaterialNeeds.readable(g, v.longValue()));
            }
        }
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    /** Needs of a site by group, each marked whether the country's stores supply it or the order's coins buy it. */
    private Component needs(final UUID country, final Map<String, Long> needs) {
        Component c = Component.empty();
        boolean first = true;
        for (final String g : MaterialNeeds.GROUPS) {
            final Long n = needs.get(g);
            if (n == null || n <= 0) {
                continue;
            }
            final boolean own = this.plugin.construction().makes(country, g);
            c = c.append(Component.text(first ? "" : ", ", NamedTextColor.GRAY))
                .append(Component.text(MaterialNeeds.readable(g, n), own ? NamedTextColor.YELLOW : NamedTextColor.GOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(own
                        ? "Z magazynów kraju (" + MaterialNeeds.producerName(g) + "); tragarze przynoszą do skrzyni budowy."
                        : "Kraj tego nie wytwarza (brak: " + MaterialNeeds.producerName(g) + ") — budowniczowie kupują za monety zamówienia.",
                        NamedTextColor.GRAY))))
                .append(Component.text(own ? " (magazyn)" : " (monety)", NamedTextColor.DARK_GRAY));
            first = false;
        }
        return c;
    }

    // ------------------------------------------------------------------ /ludnosc budowy

    void sites(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> k = this.country(sender, args);
        if (k.isEmpty()) {
            return;
        }
        final List<ConstructionService.SiteView> views = this.plugin.construction().views(k.get().id());
        sender.sendMessage(header("Place budowy: " + k.get().name() + " (" + views.size() + ")"));
        if (views.isEmpty()) {
            sender.sendMessage(Component.text(" Brak trwających budów. Zamów: /ludnosc buduj <typ>", NamedTextColor.GRAY));
            return;
        }
        for (final ConstructionService.SiteView v : views) {
            final var s = v.site();
            Component head = Component.text(" ⚒ " + v.name() + " [" + s.id.toString().substring(0,8) + "]", NamedTextColor.WHITE)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/ludnosc buduj anuluj " + s.id.toString().substring(0,8)))
                .append(Component.text(" " + v.percent() + "%", NamedTextColor.GREEN))
                .append(Component.text(" • " + v.stage(), NamedTextColor.AQUA))
                .append(Component.text(" • budowniczych " + v.builders() + (v.carrying() > 0 ? " (" + v.carrying() + " po materiał)" : ""),
                    NamedTextColor.GRAY));
            final List<Component> hover = new ArrayList<>();
            hover.add(Component.text("Miejsce: " + s.originX + " " + s.originY + " " + s.originZ, NamedTextColor.GRAY));
            if (s.storage != null) {
                hover.add(Component.text("Skrzynia budowy: " + s.storage.x() + " " + s.storage.y() + " " + s.storage.z(), NamedTextColor.GRAY));
            }
            hover.add(Component.text("Kolejność: niwelacja → fundament → konstrukcja i ściany → stropy → dach → okna i drzwi → wnętrze → detale",
                NamedTextColor.DARK_GRAY));
            head = head.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hover)));
            sender.sendMessage(head);
            if (!v.needs().isEmpty()) {
                sender.sendMessage(Component.text("   materiał do końca: ", NamedTextColor.GRAY).append(this.needs(k.get().id(), v.needs())));
                if (!v.cut().isEmpty() && !units(v.cut()).equals("—")) {
                    sender.sendMessage(line("   przygotowane na placu", units(v.cut()), NamedTextColor.GRAY));
                }
            }
            sender.sendMessage(line("   stan", v.status() == null ? "pracują" : v.status(),
                v.status() == null ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        }
        if (!this.plugin.settings().requireMaterials) {
            sender.sendMessage(Component.text(" Budowy są opłacane wyłącznie monetami (construction.require-materials: false).",
                NamedTextColor.DARK_GRAY));
        }
    }

    // ------------------------------------------------------------------ production chains

    /** Workshops (status, last step, digging today, fuel, flock) and every material group: made here or bought. */
    private void chains(final CommandSender sender, final UUID country) {
        sender.sendMessage(Component.text("Łańcuchy produkcji (zakład → skrzynia → tragarze → budowy):", NamedTextColor.YELLOW));
        final pl.medivalrpg.ludnosc.service.WorkshopService shops = this.plugin.workshops();
        int n = 0;
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (shops == null || !shops.handles(b)) {
                continue;
            }
            n++;
            final int[] c = shops.counters(b);
            final String status = shops.status(b);
            final boolean ok = !status.contains("brak") && !status.contains("wstrzymana") && !status.contains("pełny");
            final List<String> extra = new ArrayList<>();
            if (shops.quota(b) > 0) {
                extra.add((b.function() == BuildingFunction.GLASSWORKS ? "piasek" : "glina") + " dziś " + c[0] + "/" + shops.quota(b));
            }
            if (b.function() != BuildingFunction.SHEEPFOLD) {
                extra.add("paliwo " + c[1] + " wytopów");
            } else if (c[2] >= 0) {
                extra.add("stado " + c[2]);
            }
            final String last = shops.last(b);
            sender.sendMessage(Component.text(" • " + b.function().displayName(), NamedTextColor.WHITE)
                .append(Component.text(" " + b.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY))
                .append(Component.text(" — " + status, ok ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                .append(Component.text(extra.isEmpty() ? "" : " (" + String.join(", ", extra) + ")", NamedTextColor.GRAY))
                .hoverEvent(HoverEvent.showText(Component.text("Budynek " + b.box().centerX() + " " + b.box().minY() + " " + b.box().centerZ()
                    + "\nWytwarza: " + String.join(", ", pl.medivalrpg.ludnosc.logic.Workshops.groups(b.function()).stream()
                        .map(MaterialNeeds::displayName).toList())
                    + (last == null ? "" : "\nOstatnio: " + last)
                    + "\nSkrzynia: /ludnosc magazyn powiaz " + b.id().toString().substring(0, 8), NamedTextColor.GRAY))));
        }
        if (n == 0) {
            sender.sendMessage(Component.text("  brak zakładów — zbuduj hutę szkła, cegielnię, owczarnię albo kuźnię (/ludnosc buduj)",
                NamedTextColor.GRAY));
        }
        final List<Component> made = new ArrayList<>();
        final List<Component> bought = new ArrayList<>();
        for (final String g : MaterialNeeds.GROUPS) {
            final boolean own = this.plugin.construction().makes(country, g);
            final Component c = Component.text(MaterialNeeds.displayName(g), own ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                .hoverEvent(HoverEvent.showText(Component.text("Wytwarza: " + MaterialNeeds.producerName(g)
                    + (own ? "\nBudowy czekają na tragarzy (najwyżej " + this.plugin.settings().materialWaitMinutes + " min)."
                    : "\nBrak działającego zakładu — budowy kupują za monety zamówienia."), NamedTextColor.GRAY)));
            (own ? made : bought).add(c);
        }
        final JoinConfiguration comma = JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  z własnych zakładów: ", NamedTextColor.GRAY)
            .append(made.isEmpty() ? Component.text("—", NamedTextColor.GRAY) : Component.join(comma, made)));
        sender.sendMessage(Component.text("  kupowane za monety zamówień: ", NamedTextColor.GRAY)
            .append(bought.isEmpty() ? Component.text("—", NamedTextColor.GRAY) : Component.join(comma, bought)));
    }

    /** {@code /ludnosc zaklady [kraj]}: only the production chains. */
    void workshops(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> k = this.country(sender, args);
        if (k.isEmpty()) {
            return;
        }
        sender.sendMessage(header("Zakłady: " + k.get().name()));
        this.chains(sender, k.get().id());
        sender.sendMessage(Component.text(" Huta szkła: piasek z brzegów → szkło; cegielnia: glina znad wody → cegły i terakota; "
            + "owczarnia: wełna i bele siana z pszenicy; kuźnia: surowe żelazo → sztabki, węgiel + drewno → pochodnie. "
            + "Paliwo: węgiel albo węgiel drzewny z kłód.", NamedTextColor.DARK_GRAY));
    }

    // ------------------------------------------------------------------ /ludnosc gospodarka

    void flow(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> k = this.country(sender, args);
        if (k.isEmpty()) {
            return;
        }
        final UUID country = k.get().id();
        final ProductionStorageService production = this.plugin.production();
        sender.sendMessage(header("Gospodarka: " + k.get().name()));

        // production
        sender.sendMessage(Component.text("Produkcja (miejsca pracy → skrzynie):", NamedTextColor.YELLOW));
        int workplaces = 0;
        final List<String> blocked = new ArrayList<>();
        for (final Building b : this.plugin.data().buildingsOf(country)) {
            if (!ProductionStorageService.productive(b)) {
                continue;
            }
            workplaces++;
            final String status = production.status(b);
            final boolean ok = !status.contains("wstrzymana") && !status.contains("pełny");
            if (!ok) {
                blocked.add(b.function().displayName() + " " + b.id().toString().substring(0, 8) + ": " + status);
            }
            sender.sendMessage(Component.text(" • " + b.function().displayName(), NamedTextColor.WHITE)
                .append(Component.text(" " + b.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY))
                .append(Component.text(" — " + status, ok ? NamedTextColor.GREEN : NamedTextColor.RED))
                .hoverEvent(HoverEvent.showText(Component.text("Budynek " + b.box().centerX() + " " + b.box().minY() + " "
                    + b.box().centerZ() + "\nPowiąż skrzynię: /ludnosc magazyn powiaz " + b.id().toString().substring(0, 8)))));
        }
        if (workplaces == 0) {
            sender.sendMessage(Component.text("  brak pól, tartaków, kamieniołomów, kopalni i przystani", NamedTextColor.GRAY));
        }

        // stores
        sender.sendMessage(Component.text("Gdzie leży towar (wczytane skrzynie):", NamedTextColor.YELLOW));
        final Map<String, Long> stockUnits = new HashMap<>();
        final List<ProductionStorageService.StoreView> views = production.views(country);
        for (final ProductionStorageService.StoreView v : views) {
            final Building owner = production.key(v.key());
            final String name = owner == null ? "Magazyn domyślny" : owner.function().displayName();
            int used = 0;
            final Map<Material, Integer> items = new HashMap<>();
            for (final ItemStack i : v.inventory().getStorageContents()) {
                if (i == null || i.getType().isAir()) {
                    continue;
                }
                used++;
                if (!i.hasItemMeta()) {
                    items.merge(i.getType(), i.getAmount(), Integer::sum);
                    for (final String g : MaterialNeeds.GROUPS) {
                        stockUnits.merge(g, (long) i.getAmount() * MaterialNeeds.supply(i.getType().name(), g), Long::sum);
                    }
                }
            }
            final int slots = v.inventory().getStorageContents().length;
            Component c = Component.text(" • " + name + " (" + v.at().x() + " " + v.at().y() + " " + v.at().z() + ") ", NamedTextColor.WHITE)
                .append(Component.text(used + "/" + slots + " slotów", used >= slots ? NamedTextColor.RED : NamedTextColor.GRAY));
            final List<Map.Entry<Material, Integer>> top = new ArrayList<>(items.entrySet());
            top.sort(Map.Entry.<Material, Integer>comparingByValue().reversed());
            for (int i = 0; i < Math.min(4, top.size()); i++) {
                c = c.append(Component.text(i == 0 ? ": " : ", ", NamedTextColor.GRAY))
                    .append(Component.text(top.get(i).getValue() + "× ", NamedTextColor.WHITE))
                    .append(Component.translatable(top.get(i).getKey().translationKey(), NamedTextColor.WHITE));
            }
            sender.sendMessage(c);
        }
        if (views.isEmpty()) {
            sender.sendMessage(Component.text("  brak wczytanych, powiązanych skrzyń (Doradca: /ludnosc magazyn powiaz ID)", NamedTextColor.GRAY));
        }

        // production chains: workshops and who makes what
        this.chains(sender, country);

        // granary and growth
        final long food = this.plugin.granary().food(country);
        final long capacity = this.plugin.granary().capacity(country);
        final int population = this.plugin.data().population(country);
        final int housing = this.plugin.data().housing(country);
        final long meal = (long) population * this.plugin.settings().rules.foodPerCitizen();
        final boolean granaryBuilding = this.plugin.data().buildingsOf(country).stream().anyMatch(b -> b.function() == BuildingFunction.GRANARY)
            || this.plugin.granary().containerCount(country) > 0;
        sender.sendMessage(Component.text("Spichlerz i ludność:", NamedTextColor.YELLOW));
        sender.sendMessage(line("  zapas", food + "/" + capacity + " pkt (posiłek dzienny: " + meal + ")", food >= meal ? NamedTextColor.GREEN : NamedTextColor.RED));
        final String growth = housing <= population ? "wstrzymany — brak wolnych łóżek (" + population + "/" + housing + ")"
            : food < this.plugin.settings().foodPerSettler ? "wstrzymany — za mało jedzenia w spichlerzu (osadnik: " + this.plugin.settings().foodPerSettler + " pkt)"
            : "możliwy (" + (housing - population) + " wolnych łóżek)";
        sender.sendMessage(line("  wzrost", growth, growth.startsWith("możliwy") ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        if (!granaryBuilding) {
            sender.sendMessage(Component.text("  Brak spichlerza: plony zostają w skrzyniach pól i idą tylko na dzienny posiłek. Zbuduj spichlerz.",
                NamedTextColor.GOLD));
        }

        // building sites
        final List<ConstructionService.SiteView> sites = this.plugin.construction().views(country);
        final Map<String, Long> needed = new HashMap<>();
        sender.sendMessage(Component.text("Budowy (" + sites.size() + "):", NamedTextColor.YELLOW));
        for (final ConstructionService.SiteView v : sites) {
            v.needs().forEach((g, n) -> needed.merge(g, n, Long::sum));
            sender.sendMessage(Component.text(" • " + v.name() + " " + v.percent() + "% — " + v.stage(), NamedTextColor.WHITE)
                .append(Component.text(v.status() == null ? "" : " (" + v.status() + ")", NamedTextColor.YELLOW)));
        }

        // haulers
        final List<String> walking = this.plugin.logistics().walking(country);
        sender.sendMessage(Component.text("Tragarze w drodze (" + walking.size() + "):", NamedTextColor.YELLOW));
        for (final String w : walking) {
            sender.sendMessage(Component.text("  " + w, NamedTextColor.GRAY));
        }
        final List<String> recent = this.plugin.logistics().recent(country);
        if (!recent.isEmpty()) {
            sender.sendMessage(Component.text("Ostatnie przeniesienia:", NamedTextColor.YELLOW));
            for (final String r : recent) {
                sender.sendMessage(Component.text("  " + r, NamedTextColor.DARK_GRAY));
            }
        }

        // shortages
        final List<String> missing = new ArrayList<>();
        if (this.plugin.settings().requireMaterials) {
            needed.forEach((g, n) -> {
                final long have = stockUnits.getOrDefault(g, 0L);
                if (have < n && this.plugin.construction().makes(country, g)) {
                    missing.add(MaterialNeeds.displayName(g) + ": budowy potrzebują " + MaterialNeeds.readable(g, n).replaceFirst("^\\S+ ", "")
                        + ", we wczytanych magazynach " + MaterialNeeds.readable(g, have).replaceFirst("^\\S+ ", ""));
                }
            });
        }
        if (food < meal) {
            missing.add("jedzenie: brakuje " + (meal - food) + " pkt do jutrzejszego posiłku");
        }
        missing.addAll(blocked);
        sender.sendMessage(Component.text("Braki:", NamedTextColor.YELLOW));
        if (missing.isEmpty()) {
            sender.sendMessage(Component.text("  brak — wszystko płynie", NamedTextColor.GREEN));
        }
        for (final String m : missing) {
            sender.sendMessage(Component.text("  ⚠ " + m, NamedTextColor.RED));
        }
    }
}
