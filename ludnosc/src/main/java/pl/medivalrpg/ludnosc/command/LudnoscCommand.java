// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.Messages;
import pl.medivalrpg.ludnosc.hook.Hooks;
import pl.medivalrpg.ludnosc.hook.WorldEditHook;
import pl.medivalrpg.ludnosc.model.BlockBox;
import pl.medivalrpg.ludnosc.model.BlockPos;
import pl.medivalrpg.ludnosc.model.Building;
import pl.medivalrpg.ludnosc.model.ConstructionSite;
import pl.medivalrpg.ludnosc.model.CountryState;
import pl.medivalrpg.ludnosc.model.QueuedOrder;
import pl.medivalrpg.ludnosc.service.BuildingService;
import pl.medivalrpg.ludnosc.service.GranaryService;
import pl.medivalrpg.ludnosc.template.BuildingFunction;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/** {@code /ludnosc} (alias {@code /lud}): logic behind the Brigadier tree ({@link LudnoscCommandTree}). Main thread. */
public final class LudnoscCommand {

    public static final String PERM_USE = "ludnosc.use";
    public static final String PERM_ADMIN = "ludnosc.admin";

    private final LudnoscPlugin plugin;

    public LudnoscCommand(final LudnoscPlugin plugin) {
        this.plugin = plugin;
    }

    LudnoscPlugin plugin() {
        return this.plugin;
    }

    Messages messages() {
        return this.plugin.messages();
    }

    public LiteralCommandNode<CommandSourceStack> tree() {
        return new LudnoscCommandTree(this).build();
    }

    // ------------------------------------------------------------------ dispatch

    void run(final CommandSender sender, final String... args) {
        if (!sender.hasPermission(PERM_USE)) {
            this.messages().send(sender, "no-permission");
            return;
        }
        if (args.length == 0) {
            this.help(sender, null);
            return;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        final String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "pomoc", "help" -> this.help(sender, rest.length == 0 ? null : String.join(" ", rest));
            case "info" -> this.info(sender, rest);
            case "budynki" -> this.buildings(sender, rest);
            case "typy" -> this.types(sender);
            case "spichlerz" -> this.granary(sender, rest);
            case "buduj" -> this.build(sender, rest);
            case "dom" -> this.house(sender, rest);
            case "budynek" -> PlayerBuildingCommand.run(this.plugin, sender, rest);
            case "kopia" -> BlueprintCommand.run(this.plugin, sender, rest);
            case "kolejka" -> this.queue(sender, rest);
            case "napraw" -> this.repair(sender, rest);
            case "modernizuj" -> this.upgrade(sender, rest);
            case "kopalnia" -> this.mine(sender, rest);
            case "podziemia" -> this.underground(sender, rest);
            case "przebuduj" -> this.rebuildRoads(sender);
            case "magazyn" -> this.store(sender, rest);
            case "budowy" -> new FlowCommand(this.plugin).sites(sender, rest);
            case "gospodarka", "przeplyw" -> new FlowCommand(this.plugin).flow(sender, rest);
            case "zaklady", "lancuchy" -> new FlowCommand(this.plugin).workshops(sender, rest);
            case "pora" -> this.season(sender);
            case "praca" -> this.work(sender, rest);
            case "obywatel", "obywatele" -> CitizenCommand.run(this.plugin, sender, sub, rest);
            case "przebudowa" -> this.redesign(sender, rest);
            case "plan" -> {
                if (rest.length > 0 && rest[0].equalsIgnoreCase("krolestwo")) {
                    this.kingdomMap(sender);
                } else {
                    this.townPlan(sender);
                }
            }
            case "admin" -> this.admin(sender, rest);
            default -> this.messages().send(sender, "unknown-command");
        }
    }

    private void help(final CommandSender sender, final String page) {
        for (final Component line : HelpBook.render(this.messages(), sender.hasPermission(PERM_ADMIN), page)) {
            sender.sendMessage(line);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Optional<KrajInfo> countryByName(final CommandSender sender, final String[] tokens) {
        final String name = String.join(" ", tokens).trim();
        final Optional<KrajInfo> k = this.plugin.kraje().getCountryByName(name);
        if (k.isEmpty()) {
            this.messages().send(sender, "country-not-found", Placeholder.unparsed("name", name));
        }
        return k;
    }

    /** Country from arguments, or the sender's own country. */
    private Optional<KrajInfo> targetCountry(final CommandSender sender, final String[] args) {
        if (args.length > 0) {
            return this.countryByName(sender, args);
        }
        if (sender instanceof Player p) {
            final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
            if (own.isEmpty()) {
                this.messages().send(sender, "no-country");
            }
            return own;
        }
        this.messages().send(sender, "players-only");
        return Optional.empty();
    }

    /** Player's own country with at least the configured rank (admins always pass). */
    private Optional<KrajInfo> ownCountryWithRank(final Player p) {
        final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            this.messages().send(p, "no-country");
            return own;
        }
        final Rank need = this.plugin.settings().buildRank;
        final boolean ok = p.hasPermission(PERM_ADMIN)
            || this.plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(need)).orElse(false);
        if (!ok) {
            this.messages().send(p, "rank-too-low", Placeholder.unparsed("rank", need.displayName()));
            return Optional.empty();
        }
        return own;
    }

    private static String pos(final BlockBox b) {
        return b.centerX() + " " + b.minY() + " " + b.centerZ();
    }

    private static Long parseLong(final String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private Component list(final List<Component> items) {
        if (items.isEmpty()) {
            return this.messages().get("info-none");
        }
        return Component.join(JoinConfiguration.commas(true), items);
    }

    // ------------------------------------------------------------------ info

    private void info(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> target = this.targetCountry(sender, args);
        if (target.isEmpty()) {
            return;
        }
        final KrajInfo k = target.get();
        final UUID id = k.id();
        final Messages m = this.messages();
        final int population = this.plugin.data().population(id);
        final int housing = this.plugin.data().housing(id);
        final CountryState st = this.plugin.data().stateIfPresent(id);
        Component header = m.get("info-header", Placeholder.unparsed("country", k.name()));
        if (k.npc()) {
            header = header.append(m.get("info-npc"));
        }
        sender.sendMessage(header);
        final int lvl = this.plugin.settlement().levelIndex(id);
        final var levels = this.plugin.settings().levels;
        final var next = levels.next(lvl);
        sender.sendMessage(m.get("info-level", Placeholder.unparsed("level", levels.level(lvl).name()),
            Placeholder.component("next", next == null ? m.get("info-level-max") : m.get("info-level-next",
                Placeholder.unparsed("name", next.name()), Placeholder.unparsed("population", Integer.toString(next.population())),
                Placeholder.unparsed("treasury", this.plugin.formatMoney(next.treasury()))))));
        sender.sendMessage(m.get("info-era", Placeholder.unparsed("era",
            pl.medivalrpg.ludnosc.hook.PostepHook.eraName(this.plugin.postep().era(id)))));
        sender.sendMessage(m.get("info-population", Placeholder.unparsed("population", Integer.toString(population)),
            Placeholder.unparsed("housing", Integer.toString(housing))));
        final long balance = this.plugin.economy().expectedFoodBalance(id);
        sender.sendMessage(m.get("info-food", Placeholder.unparsed("food", Long.toString(this.plugin.granary().food(id))),
            Placeholder.unparsed("capacity", Long.toString(this.plugin.granary().capacity(id))),
            Placeholder.unparsed("balance", (balance >= 0 ? "+" : "") + balance)));
        sender.sendMessage(m.get("info-tax", Placeholder.unparsed("tax", this.plugin.formatMoney(this.plugin.economy().expectedTax(id)))));
        if (this.plugin.seasons().enabled()) {
            sender.sendMessage(this.plugin.seasons().line("info-season"));
            if (this.plugin.seasons().blight(id)) {
                sender.sendMessage(m.get("info-blight", Placeholder.unparsed("until", this.plugin.seasons().when(st.blightUntil)),
                    Placeholder.unparsed("factor", pl.medivalrpg.ludnosc.service.SeasonService.times(
                        this.plugin.settings().work.seasons.blightFactor()))));
            }
        }
        final var ws = this.plugin.work().summary(id);
        sender.sendMessage(m.get("info-work",
            Placeholder.unparsed("farmers", Integer.toString(ws.workers().get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.FARMER))),
            Placeholder.unparsed("miners", Integer.toString(ws.workers().get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.MINER))),
            Placeholder.unparsed("quarry", Integer.toString(ws.workers().get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.QUARRYMAN))),
            Placeholder.unparsed("wood", Integer.toString(ws.workers().get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.WOODCUTTER))),
            Placeholder.unparsed("fish", Integer.toString(ws.workers().get(pl.medivalrpg.ludnosc.logic.JobBoard.Job.FISHER)))));
        final boolean hungry = st != null && st.hungryDays > 0;
        final boolean homeless = population > housing;
        if (hungry) {
            sender.sendMessage(m.get("info-status-hungry", Placeholder.unparsed("days", Integer.toString(st.hungryDays))));
        }
        if (homeless) {
            sender.sendMessage(m.get("info-status-homeless", Placeholder.unparsed("missing", Integer.toString(population - housing)),
                Placeholder.unparsed("days", Integer.toString(st == null ? 0 : st.homelessDays))));
        }
        if (!hungry && !homeless) {
            sender.sendMessage(m.get("info-status-ok"));
        }
        final Map<String, Integer> counts = new TreeMap<>();
        for (final Building b : this.plugin.data().buildingsOf(id)) {
            counts.merge(b.type(), 1, Integer::sum);
        }
        final List<Component> items = new ArrayList<>();
        counts.forEach((type, n) -> items.add(m.parse(this.typeName(type)).append(Component.text(" ×" + n))));
        sender.sendMessage(m.get("info-buildings", Placeholder.component("list", this.list(items))));
        final List<Component> sites = new ArrayList<>();
        for (final ConstructionSite s : this.plugin.data().sitesOf(id)) {
            sites.add(m.parse(this.typeName(s.type)).append(Component.text(" " + this.plugin.construction().percent(s) + "%")));
        }
        sender.sendMessage(m.get("info-sites", Placeholder.component("list", this.list(sites))));
        sender.sendMessage(m.get("info-slots", Placeholder.unparsed("n", Integer.toString(this.plugin.construction().sitesInUse(id))),
            Placeholder.unparsed("max", Integer.toString(this.plugin.construction().maxSites(id)))));
        sender.sendMessage(m.get("info-queue", Placeholder.unparsed("n", Integer.toString(this.plugin.data().queueSize(id))),
            Placeholder.unparsed("max", Integer.toString(this.plugin.settings().queueSize))));
    }

    private String typeName(final String type) {
        if (BuildingService.REGISTERED_TYPE.equals(type)) {
            return "dom (zgłoszony)";
        }
        return this.plugin.construction().displayName(type);
    }

    private void buildings(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> target = this.targetCountry(sender, args);
        if (target.isEmpty()) {
            return;
        }
        final Messages m = this.messages();
        final UUID id = target.get().id();
        sender.sendMessage(m.get("buildings-header", Placeholder.unparsed("country", target.get().name())));
        final List<Building> list = this.plugin.data().buildingsOf(id);
        final List<ConstructionSite> sites = this.plugin.data().sitesOf(id);
        final List<QueuedOrder> queue = this.plugin.data().queue(id);
        if (list.isEmpty() && sites.isEmpty() && queue.isEmpty()) {
            sender.sendMessage(m.get("buildings-empty"));
            return;
        }
        for (final Building b : list) {
            final boolean own = pl.medivalrpg.ludnosc.service.PlayerBuildingService.isPlayer(b) && this.plugin.playerBuildings() != null;
            Component line = m.get("buildings-line", Placeholder.parsed("name", own ? this.plugin.playerBuildings().label(b)
                    : this.typeName(b.type())),
                Placeholder.unparsed("function", b.function().displayName()),
                Placeholder.unparsed("pos", pos(b.box())),
                Placeholder.unparsed("beds", Integer.toString(b.beds())));
            if (own) {
                final var meta = this.plugin.playerBuildings().meta(b);
                line = line.append(this.plugin.playerBuildings().working(b) ? m.get("budynek.list-working")
                    : m.get("budynek.list-broken", Placeholder.unparsed("missing",
                        meta == null || meta.problem() == null ? "kluczowych bloków" : meta.problem())));
            } else if (this.plugin.construction().repairing(b.id())) {
                line = line.append(m.get("buildings-repairing"));
            } else {
                final var dmg = this.plugin.repairs().damageOf(b.id());
                if (dmg != null && dmg.percent() > 0) {
                    line = line.append(m.get("buildings-damaged", Placeholder.unparsed("percent", Integer.toString(dmg.percent()))));
                } else if (this.plugin.repairs().outdated(b)) {
                    line = line.append(m.get("buildings-outdated"));
                }
                if (this.plugin.redesign().toDemolish(b)) {
                    line = line.append(m.get("info-redesign"));
                }
            }
            sender.sendMessage(line.hoverEvent(HoverEvent.showText(Component.text("id: " + b.id().toString().substring(0, 8))))
                .clickEvent(ClickEvent.suggestCommand("/ludnosc napraw " + b.id().toString().substring(0, 8))));
        }
        for (final ConstructionSite s : sites) {
            sender.sendMessage(m.get("buildings-site-line", Placeholder.parsed("name", this.typeName(s.type)),
                    Placeholder.unparsed("pos", s.originX + " " + s.originY + " " + s.originZ),
                    Placeholder.unparsed("percent", Integer.toString(this.plugin.construction().percent(s))))
                .hoverEvent(HoverEvent.showText(Component.text("id: " + s.id)))
                .clickEvent(ClickEvent.copyToClipboard(s.id.toString())));
        }
        for (int i = 0; i < queue.size(); i++) {
            final QueuedOrder o = queue.get(i);
            sender.sendMessage(m.get("buildings-queue-line", Placeholder.unparsed("nr", Integer.toString(i + 1)),
                Placeholder.parsed("name", this.typeName(o.type)),
                Placeholder.unparsed("pos", o.originX + " " + o.originY + " " + o.originZ)));
        }
    }

    // ------------------------------------------------------------------ repairs & town plan

    private void repair(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.ownCountryWithRank(p);
        if (own.isEmpty()) {
            return;
        }
        this.plugin.repairs().repairCommand(p, own.get().id(), args.length == 0 ? "wszystko" : args[0]);
    }

    private void upgrade(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.ownCountryWithRank(p);
        if (own.isEmpty()) {
            return;
        }
        this.plugin.repairs().upgradeCommand(p, own.get().id(), args.length == 0 ? "wszystko" : args[0]);
    }

    private void mine(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Messages m = this.messages();
        if (args.length > 0 && args[0].equalsIgnoreCase("rozbuduj")) {
            final Optional<KrajInfo> own = this.ownCountryWithRank(p);
            if (own.isEmpty()) {
                return;
            }
            final var r = this.plugin.mines().extend(own.get().id(), args.length > 1 ? args[1] : null, true);
            final String cost = this.plugin.formatMoney(this.plugin.settings().mineExtensionCost);
            m.send(p, switch (r) {
                case ORDERED -> "mine-ordered";
                case NO_MINE -> "mine-none";
                case BUSY -> "mine-busy";
                case MAX -> "mine-max";
                case FUNDS -> "mine-funds";
            }, Placeholder.unparsed("cost", cost));
            return;
        }
        final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            m.send(p, "no-country");
            return;
        }
        final UUID id = own.get().id();
        final var mines = this.plugin.mines().mines(id);
        if (mines.isEmpty()) {
            m.send(p, "mine-none");
            return;
        }
        p.sendMessage(m.get("mine-header", Placeholder.unparsed("income", this.plugin.formatMoney(this.plugin.economy().mineIncome(id)))));
        p.sendMessage(Component.text("Rzeczywisty urobek trafia do skrzyń. Brak magazynu, pojemności lub pochodni wstrzymuje pracę."));
        for (final var e : mines) {
            p.sendMessage(Component.text(this.plugin.production().status(e.getKey())));
            final int[] rec = e.getValue();
            final Component state = rec[5] > 0
                ? m.get("mine-digging", Placeholder.unparsed("remaining", Integer.toString(rec[5]))) : Component.empty();
            final String shortId = e.getKey().id().toString().substring(0, 8);
            p.sendMessage(m.get("mine-line", Placeholder.unparsed("id", shortId),
                    Placeholder.unparsed("length", Integer.toString(rec[4])),
                    Placeholder.unparsed("max", Integer.toString(this.plugin.settings().mineMaxLength)),
                    Placeholder.unparsed("y", Integer.toString(rec[1]))).append(state)
                .append(m.get("mine-line-level", Placeholder.unparsed("level", Integer.toString(rec.length > 8 ? rec[8] : 1)),
                    Placeholder.unparsed("floors", Integer.toString(rec.length > 9 ? rec[9] : 1)),
                    Placeholder.unparsed("ores", Integer.toString(rec.length > 10 ? rec[10] : 0))))
                .append(m.get("mine-line-miners",
                    Placeholder.unparsed("miners", Integer.toString(this.plugin.mines().miners(id, e.getKey().id()))),
                    Placeholder.unparsed("slots", Integer.toString(this.plugin.settings().work.mineYield.slots(rec.length > 8 ? rec[8] : 1)))))
                .append(pl.medivalrpg.ludnosc.service.MineService.blockedUntil(rec) * 60_000L > System.currentTimeMillis()
                    ? m.get("mine-line-blocked") : Component.empty())
                .clickEvent(ClickEvent.suggestCommand("/ludnosc kopalnia rozbuduj " + shortId)));
        }
    }

    private void redesign(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.ownCountryWithRank(p);
        if (own.isEmpty()) {
            return;
        }
        if (!own.get().npc()) { p.sendMessage(Component.text("Planista i przebudowa miasta są wyłączone w krajach graczy. Budujecie miasto samodzielnie.")); return; }
        final UUID id = own.get().id();
        if (args.length > 0 && args[0].equalsIgnoreCase("potwierdz")) {
            final boolean ruler = p.hasPermission(PERM_ADMIN)
                || this.plugin.kraje().getRank(p.getUniqueId()).map(r -> r == pl.medivalrpg.kraje.model.Rank.RULER).orElse(false);
            if (!ruler) {
                this.messages().send(p, "redesign-ruler");
                return;
            }
            if (this.plugin.redesign().inProgress(id)) {
                this.messages().send(p, "admin-redesign-busy", Placeholder.unparsed("country", own.get().name()));
                this.redesignStatus(p, own.get());
                return;
            }
            final var plan = this.plugin.redesign().compute(id);
            if (plan == null || plan.newRoads().isEmpty() && plan.demolish().isEmpty() && plan.quarters().isEmpty()) {
                this.messages().send(p, "redesign-none");
                return;
            }
            this.plugin.redesign().activate(id, plan);
            this.messages().send(p, "redesign-confirmed");
            return;
        }
        final var plan = this.plugin.redesign().preview(p, id);
        if (plan == null || plan.newRoads().isEmpty() && plan.demolish().isEmpty() && plan.quarters().isEmpty()) {
            this.messages().send(p, "redesign-none");
            return;
        }
        final long[] est = this.plugin.redesign().estimate(plan);
        final long quarters = plan.quarters().stream().filter(q -> q.free() && !q.green()).count();
        this.messages().send(p, "redesign-preview", Placeholder.unparsed("roads", Integer.toString(plan.newRoads().size())),
            Placeholder.unparsed("demolish", Integer.toString(plan.demolish().size())),
            Placeholder.unparsed("quarters", Long.toString(quarters)),
            Placeholder.unparsed("cost", this.plugin.formatMoney(est[0])),
            Placeholder.unparsed("time", est[1] >= 120 ? est[1] / 60 + " h" : est[1] + " min"));
    }

    private void kingdomMap(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            this.messages().send(p, "no-country");
            return;
        }
        final String[] rows = this.plugin.kingdom().map(own.get().id());
        if (rows.length == 0) {
            this.messages().send(p, "kingdom-none");
            return;
        }
        p.sendMessage(this.messages().get("kingdom-header"));
        for (final String row : rows) {
            p.sendMessage(Component.text(row, net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .font(net.kyori.adventure.key.Key.key("minecraft", "uniform")));
        }
    }

    private void store(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = p.hasPermission(PERM_ADMIN)
            ? this.plugin.kraje().getCountryAt(p.getWorld().getName(),p.getLocation().getBlockX() >> 4,p.getLocation().getBlockZ() >> 4)
                .or(() -> this.plugin.kraje().getCountryOf(p.getUniqueId()))
            : this.plugin.kraje().getCountryOf(p.getUniqueId());
        if(own.isEmpty()) { p.sendMessage(Component.text("Brak kraju.")); return; }
        final UUID country=own.get().id();
        final Messages m = this.messages();
        if(args.length>0 && !args[0].equalsIgnoreCase("status")) {
            boolean allowed=p.hasPermission(PERM_ADMIN) || this.plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(Rank.ADVISOR)).orElse(false);
            if(!allowed) { p.sendMessage(Component.text("Wymagana ranga Doradca lub Władca.")); return; }
            try {
                if(args[0].equalsIgnoreCase("domyslny")) {
                    Block target=p.getTargetBlockExact(6); if(target==null) throw new IllegalArgumentException("Wskaż skrzynię lub beczkę do 6 bloków.");
                    this.plugin.production().bind(country,null,target);
                    m.send(p,"magazyn.linked-default",Placeholder.unparsed("pos",target.getX()+" "+target.getY()+" "+target.getZ()));
                } else if(args[0].equalsIgnoreCase("powiaz") && args.length>=3) {
                    final Building workplace=this.storageBuilding(country,args[1]);
                    final Building warehouse=this.storageBuilding(country,args[2]);
                    this.plugin.production().linkWarehouse(country,workplace,warehouse);
                    m.send(p,"magazyn.linked-warehouse",Placeholder.unparsed("name",workplace.function().displayName()),
                        Placeholder.unparsed("id",warehouse.id().toString().substring(0,8)));
                } else if(args[0].equalsIgnoreCase("powiaz")) {
                    // the chest the player looks at; without an ID the building it stands in (warehouse, granary, workplace)
                    final Block target=p.getTargetBlockExact(6);
                    if(target==null || target.getType()!=org.bukkit.Material.CHEST && target.getType()!=org.bukkit.Material.BARREL) {
                        final String example=this.plugin.data().buildingsOf(country).stream()
                            .filter(pl.medivalrpg.ludnosc.service.ProductionStorageService::productive).findFirst()
                            .map(x -> x.id().toString().substring(0,8)).orElse("ID");
                        m.send(p,"magazyn.link-look",Placeholder.unparsed("example",example));
                        return;
                    }
                    final Building holder=args.length==2 ? this.storageBuilding(country,args[1]) : this.plugin.production().holder(country,target);
                    if(holder==null) { m.send(p,"magazyn.link-outside"); return; }
                    this.plugin.production().bind(country,holder,target);
                    m.send(p,"magazyn.linked",Placeholder.unparsed("pos",target.getX()+" "+target.getY()+" "+target.getZ()),
                        Placeholder.unparsed("name",holder.function().displayName()),
                        Placeholder.unparsed("id",holder.id().toString().substring(0,8)));
                } else if(args[0].equalsIgnoreCase("audyt") && args.length==3 && args[2].equalsIgnoreCase("potwierdz") && p.hasPermission(PERM_ADMIN)) {
                    this.plugin.production().audit(country,this.storageBuilding(country,args[1]).id());
                    p.sendMessage(Component.text("Zapisano audyt magazynu."));
                } else m.send(p,"magazyn.usage");
            } catch(IllegalArgumentException ex) { m.send(p,"magazyn.error",Placeholder.unparsed("reason",String.valueOf(ex.getMessage()))); }
            return;
        }
        // the warehouses first: what they are, how many chests count, how full they are
        p.sendMessage(m.get("magazyn.header"));
        final var warehouses=this.plugin.production().warehouses(country);
        for(final var v:warehouses) {
            final Building b=v.building();
            p.sendMessage(m.get(v.loaded()==0 ? "magazyn.warehouse-unloaded" : "magazyn.warehouse",
                Placeholder.unparsed("name",b.function().displayName()),
                Placeholder.unparsed("id",b.id().toString().substring(0,8)),
                Placeholder.unparsed("pos",b.box().centerX()+" "+b.box().minY()+" "+b.box().centerZ()),
                Placeholder.unparsed("containers",Integer.toString(v.loaded())),
                Placeholder.unparsed("used",Integer.toString(v.usedSlots())),
                Placeholder.unparsed("slots",Integer.toString(v.slots()))));
        }
        m.send(p,warehouses.isEmpty() ? "magazyn.none" : "magazyn.hint");
        boolean first=true;
        for(final Building building:this.plugin.data().buildingsOf(country)) if(pl.medivalrpg.ludnosc.service.ProductionStorageService.productive(building)) {
            if(first) { p.sendMessage(m.get("magazyn.workplaces")); first=false; }
            p.sendMessage(m.get("magazyn.workplace",Placeholder.unparsed("name",building.function().displayName()),
                Placeholder.unparsed("id",building.id().toString().substring(0,8)),
                Placeholder.unparsed("status",this.plugin.production().status(building))));
        }
        final var stock = this.plugin.resources().stock(own.get().id());
        if (stock.isEmpty() || stock.values().stream().allMatch(v -> v == 0)) {
            this.messages().send(p, "resources-empty");
            return;
        }
        p.sendMessage(this.messages().get("resources-header"));
        for (final var e : stock.entrySet()) {
            p.sendMessage(this.messages().get("resources-line",
                Placeholder.unparsed("name", pl.medivalrpg.ludnosc.service.ResourceService.name(e.getKey())),
                Placeholder.unparsed("amount", Long.toString(e.getValue()))));
        }
    }

    private Building storageBuilding(UUID country,String prefix) {
        final var matches=this.plugin.data().buildingsOf(country).stream().filter(b -> b.id().toString().startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
        if(matches.size()!=1) throw new IllegalArgumentException("Podaj jednoznaczny ID budynku własnego kraju.");
        return matches.getFirst();
    }

    private void rebuildRoads(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.ownCountryWithRank(p);
        if (own.isEmpty()) {
            return;
        }
        final var r = this.plugin.settlement().resurfaceRoads(own.get().id(), this.plugin.settlement().treasury(own.get().id()), p.getName());
        this.messages().send(p, switch (r) {
            case OK -> "rebuild-roads";
            case INSUFFICIENT_FUNDS -> "rebuild-roads-funds";
            default -> "rebuild-roads-none";
        });
    }

    private void underground(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Messages m = this.messages();
        final var ug = this.plugin.underground();
        if (args.length > 0 && args[0].equalsIgnoreCase("kop")) {
            final Optional<KrajInfo> own = this.ownCountryWithRank(p);
            if (own.isEmpty()) {
                return;
            }
            final String prefix = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            for (final Building b : this.plugin.data().buildingsOf(own.get().id())) {
                final var kind = ug.featureOf(b.type());
                if (kind == null || !b.id().toString().startsWith(prefix) || ug.record(b) != null) {
                    continue;
                }
                final var r = ug.order(b);
                m.send(p, switch (r) {
                    case STARTED -> "underground-started";
                    case EXISTS -> "underground-exists";
                    case NO_PLACE -> "underground-noplace";
                    case FUNDS -> "underground-funds";
                    case NOT_SUPPORTED -> "underground-unsupported";
                }, Placeholder.unparsed("feature", pl.medivalrpg.ludnosc.service.UndergroundService.kindName(kind)),
                    Placeholder.parsed("name", this.plugin.construction().displayName(b.type())),
                    Placeholder.unparsed("cost", this.plugin.formatMoney(ug.cost(kind))));
                return;
            }
            m.send(p, "underground-none");
            return;
        }
        final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            m.send(p, "no-country");
            return;
        }
        p.sendMessage(m.get("underground-header"));
        int shown = 0;
        for (final Building b : this.plugin.data().buildingsOf(own.get().id())) {
            final var kind = ug.featureOf(b.type());
            if (kind == null) {
                continue;
            }
            final int[] rec = ug.record(b);
            final String shortId = b.id().toString().substring(0, 8);
            final String state = rec == null ? m.raw("underground-state-none")
                : rec[7] == 2 ? m.raw("underground-state-noplace")
                : rec[7] == 1 ? m.raw("underground-state-done")
                : m.raw("underground-state-digging").replace("<percent>", Integer.toString(ug.percent(b.id().toString(), rec)));
            Component line = m.get("underground-line", Placeholder.unparsed("id", shortId),
                Placeholder.parsed("name", this.plugin.construction().displayName(b.type())),
                Placeholder.unparsed("feature", pl.medivalrpg.ludnosc.service.UndergroundService.kindName(kind)),
                Placeholder.parsed("state", state == null ? "" : state));
            if (rec == null) {
                line = line.clickEvent(ClickEvent.suggestCommand("/ludnosc podziemia kop " + shortId));
            }
            p.sendMessage(line);
            shown++;
        }
        if (shown == 0) {
            m.send(p, "underground-none");
        }
    }

    private void townPlan(final CommandSender sender) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) {
            this.messages().send(p, "no-country");
            return;
        }
        final UUID id = own.get().id();
        final List<BlockBox> plots = new ArrayList<>();
        final List<org.bukkit.Particle.DustOptions> colors = new ArrayList<>();
        for (final Building b : this.plugin.data().buildingsOf(id)) {
            plots.add(b.box());
            final var t = this.plugin.templates().get(b.type());
            colors.add(zoneColor(t == null ? "inner" : t.meta().zone()));
        }
        for (final ConstructionSite s : this.plugin.data().sitesOf(id)) {
            final BlockBox box = this.plugin.construction().box(s);
            if (box != null) {
                plots.add(box);
                colors.add(new org.bukkit.Particle.DustOptions(org.bukkit.Color.YELLOW, 1.2f));
            }
        }
        for (final QueuedOrder o : this.plugin.data().queue(id)) {
            final BlockBox box = this.plugin.construction().box(o);
            if (box != null) {
                plots.add(box);
                colors.add(new org.bukkit.Particle.DustOptions(org.bukkit.Color.AQUA, 1.2f));
            }
        }
        this.plugin.plans().showTown(p, plots, colors, this.plugin.data().state(id).roads(), 20);
        this.messages().send(p, "plan-shown", Placeholder.unparsed("plots", Integer.toString(plots.size())),
            Placeholder.unparsed("roads", Integer.toString(this.plugin.data().state(id).roads().size())),
            Placeholder.unparsed("level", this.plugin.settlement().level(id).name()));
    }

    private static org.bukkit.Particle.DustOptions zoneColor(final String zone) {
        final org.bukkit.Color c = switch (zone) {
            case "center" -> org.bukkit.Color.fromRGB(255, 200, 40);
            case "outer" -> org.bukkit.Color.fromRGB(150, 100, 60);
            case "farm" -> org.bukkit.Color.fromRGB(150, 220, 60);
            case "border" -> org.bukkit.Color.fromRGB(150, 150, 160);
            default -> org.bukkit.Color.fromRGB(230, 120, 60);
        };
        return new org.bukkit.Particle.DustOptions(c, 1.2f);
    }

    // ------------------------------------------------------------------ construction queue

    private void queue(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final Optional<KrajInfo> own = this.cancelCountry(p);
        if (own.isEmpty()) {
            return;
        }
        final UUID id = own.get().id();
        if (args.length == 0) {
            this.listQueue(p, own.get(), false);
            return;
        }
        final String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("anuluj", "wyzej", "nizej", "pokaz").contains(action)) {
            this.messages().send(p, "unknown-command");
            return;
        }
        final List<QueuedOrder> q = this.plugin.data().queue(id);
        final Long nr = args.length > 1 ? parseLong(args[1]) : null;
        if (nr == null || nr < 1 || nr > q.size()) {
            this.messages().send(p, "queue-bad-number", Placeholder.unparsed("max", Integer.toString(q.size())));
            return;
        }
        final int index = (int) (nr - 1);
        final QueuedOrder o = q.get(index);
        switch (action) {
            case "anuluj" -> {
                final long refund = this.plugin.construction().cancelQueued(o, null);
                this.messages().send(p, "queue-cancelled", Placeholder.parsed("name", this.typeName(o.type)),
                    Placeholder.unparsed("refund", this.plugin.formatMoney(refund)));
                this.listQueue(p, own.get(), false);
            }
            case "wyzej", "nizej" -> {
                if (this.plugin.data().moveQueued(id, index, action.equals("wyzej") ? -1 : 1)) {
                    this.messages().send(p, "queue-moved", Placeholder.parsed("name", this.typeName(o.type)));
                }
                this.listQueue(p, own.get(), false);
            }
            default -> {
                final BlockBox box = this.plugin.construction().box(o);
                if (box == null) {
                    this.messages().send(p, "build-result-unknown");
                    return;
                }
                this.plugin.plans().show(p, box, o.originY, 10);
                this.messages().send(p, "queue-showing", Placeholder.parsed("name", this.typeName(o.type)),
                    Placeholder.unparsed("pos", o.originX + " " + o.originY + " " + o.originZ));
            }
        }
    }

    private Component button(final String key, final String command) {
        return this.messages().get(key).hoverEvent(HoverEvent.showText(this.messages().get(key + "-hover")))
            .clickEvent(ClickEvent.runCommand(command));
    }

    private void listQueue(final CommandSender sender, final KrajInfo k, final boolean adminView) {
        final Messages m = this.messages();
        final List<QueuedOrder> q = this.plugin.data().queue(k.id());
        sender.sendMessage(m.get("queue-header", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("n", Integer.toString(q.size())),
            Placeholder.unparsed("max", Integer.toString(this.plugin.settings().queueSize))));
        if (q.isEmpty()) {
            sender.sendMessage(m.get("queue-empty"));
            return;
        }
        for (int i = 0; i < q.size(); i++) {
            final QueuedOrder o = q.get(i);
            final String nr = Integer.toString(i + 1);
            Component line = m.get("queue-line", Placeholder.unparsed("nr", nr),
                Placeholder.parsed("name", this.typeName(o.type)),
                Placeholder.unparsed("pos", o.originX + " " + o.originY + " " + o.originZ),
                Placeholder.unparsed("cost", this.plugin.formatMoney(o.cost)),
                Placeholder.unparsed("by", o.orderedBy == null || o.orderedBy.isEmpty() ? "?" : o.orderedBy));
            if (!adminView) {
                line = line.append(Component.space())
                    .append(this.button("queue-button-up", "/ludnosc kolejka wyzej " + nr)).append(Component.space())
                    .append(this.button("queue-button-down", "/ludnosc kolejka nizej " + nr)).append(Component.space())
                    .append(this.button("queue-button-show", "/ludnosc kolejka pokaz " + nr)).append(Component.space())
                    .append(this.messages().get("queue-button-cancel")
                        .hoverEvent(HoverEvent.showText(this.messages().get("queue-button-cancel-hover")))
                        .clickEvent(ClickEvent.suggestCommand("/ludnosc kolejka anuluj " + nr)));
            }
            sender.sendMessage(line);
        }
    }

    private void adminQueue(final CommandSender sender, final String[] args) {
        if (args.length == 0) {
            this.messages().send(sender, "admin-usage", Placeholder.unparsed("usage", "/ludnosc admin kolejka <kraj> [wyczysc]"));
            return;
        }
        final boolean clear = args.length > 1 && args[args.length - 1].equalsIgnoreCase("wyczysc");
        final Optional<KrajInfo> k = this.countryByName(sender, clear ? Arrays.copyOf(args, args.length - 1) : args);
        if (k.isEmpty()) {
            return;
        }
        if (!clear) {
            this.listQueue(sender, k.get(), true);
            return;
        }
        long refund = 0;
        int n = 0;
        for (final QueuedOrder o : this.plugin.data().queue(k.get().id())) {
            refund += this.plugin.construction().cancelQueued(o, null);
            n++;
        }
        this.messages().send(sender, "admin-queue-cleared", Placeholder.unparsed("n", Integer.toString(n)),
            Placeholder.unparsed("country", k.get().name()), Placeholder.unparsed("refund", this.plugin.formatMoney(refund)));
    }

    private void types(final CommandSender sender) {
        final Messages m = this.messages();
        sender.sendMessage(m.get("types-header"));
        for (final BuildingTemplate t : this.catalog(sender)) {
            final var meta = t.meta();
            Component hover = m.get("types-hover", Placeholder.parsed("name", meta.displayName()),
                Placeholder.unparsed("description", this.plugin.templates().texts().description(t)),
                Placeholder.unparsed("function", meta.function().displayName()));
            final String bonus = this.plugin.bonusText(t);
            if (!bonus.isEmpty()) {
                hover = hover.append(m.get("types-bonus", Placeholder.unparsed("bonus", bonus)));
            }
            Component line = m.get("types-line", Placeholder.parsed("name", meta.displayName()),
                    Placeholder.unparsed("id", t.id()),
                    Placeholder.unparsed("size", t.width() + "×" + t.depth() + "×" + t.height()),
                    Placeholder.unparsed("beds", Integer.toString(t.bedCount())),
                    Placeholder.unparsed("cost", this.plugin.formatMoney(meta.cost())));
            // what the building does, readable without hovering (a separate key: an older config.yml keeps its line)
            final String effect = this.plugin.templates().texts().effect(t);
            if (!effect.isEmpty()) {
                line = line.append(m.get("types-line-effect", Placeholder.unparsed("effect", effect)));
            }
            sender.sendMessage(line.hoverEvent(HoverEvent.showText(hover))
                .clickEvent(ClickEvent.suggestCommand("/ludnosc buduj " + t.id())));
        }
    }

    // ------------------------------------------------------------------ granary

    private void granary(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        if (args.length == 0) {
            final Optional<KrajInfo> own = this.plugin.kraje().getCountryOf(p.getUniqueId());
            if (own.isEmpty()) {
                this.messages().send(p, "no-country");
                return;
            }
            final UUID id = own.get().id();
            this.messages().send(p, "granary-status", Placeholder.unparsed("food", Long.toString(this.plugin.granary().food(id))),
                Placeholder.unparsed("capacity", Long.toString(this.plugin.granary().capacity(id))),
                Placeholder.unparsed("chests", Integer.toString(this.plugin.granary().containerCount(id))));
            this.messages().send(p, "granary-hint");
            return;
        }
        final String action = args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("oznacz") && !action.equals("usun")) {
            this.messages().send(p, "unknown-command");
            return;
        }
        final Optional<KrajInfo> own = this.ownCountryWithRank(p);
        if (own.isEmpty()) {
            return;
        }
        final Block b = p.getTargetBlockExact(6);
        if (b == null || !(b.getState(false) instanceof Chest || b.getState(false) instanceof Barrel)) {
            this.messages().send(p, "granary-not-container");
            return;
        }
        final UUID id = own.get().id();
        if (action.equals("oznacz")) {
            if (!this.plugin.kraje().getCountryAt(b.getLocation()).map(KrajInfo::id).map(id::equals).orElse(false)) {
                this.messages().send(p, "granary-not-own-land");
                return;
            }
            switch (this.plugin.granary().mark(id, b)) {
                case OK -> this.messages().send(p, "granary-marked");
                case ALREADY -> this.messages().send(p, "granary-already");
                case LIMIT -> this.messages().send(p, "granary-limit",
                    Placeholder.unparsed("max", Integer.toString(this.plugin.settings().maxMarkedChests)));
                default -> {
                }
            }
        } else {
            if (this.plugin.granary().unmark(id, b) == GranaryService.MarkResult.OK) {
                this.messages().send(p, "granary-unmarked");
            } else {
                this.messages().send(p, "granary-not-marked");
            }
        }
    }

    // ------------------------------------------------------------------ build

    private void build(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final String types = String.join(", ", this.catalog(sender).stream().map(BuildingTemplate::id).toList());
        if (args.length == 0) {
            this.messages().send(p, "build-usage", Placeholder.unparsed("types", types));
            return;
        }
        final String a = args[0].toLowerCase(Locale.ROOT);
        switch (a) {
            case "obroc" -> this.plugin.plans().rotate(p);
            case "wariant" -> this.plugin.plans().nextVariant(p);
            case "auto" -> this.ownCountryWithRank(p).ifPresent(k -> {
                if (!k.npc()) { p.sendMessage(Component.text("Automatyczne budowanie w krajach graczy jest wyłączone. Wybierz budynek i miejsce samodzielnie.")); return; }
                final long treasury = this.plugin.settlement().treasury(k.id());
                final var r = this.plugin.settlement().plan(k.id(), treasury, null);
                if (r.proposal() != null) {
                    this.messages().send(p, "build-auto-proposal", Placeholder.parsed("name", this.typeName(r.proposal().type())),
                        Placeholder.component("reason", this.messages().get("reason-" + r.reason().name().toLowerCase(Locale.ROOT)
                            .replace('_', '-'))));
                    this.plugin.plans().createProposal(p, k.id(), r.proposal());
                } else if (r.noSpot()) {
                    this.messages().send(p, "build-auto-nospot");
                    if (r.failure() != null) {
                        this.messages().send(p, "build-failure-reason", Placeholder.unparsed("reason", r.failure()));
                    }
                } else if (r.reason() == pl.medivalrpg.ludnosc.planner.BuildingChooser.Reason.NO_FUNDS) {
                    this.messages().send(p, "build-auto-funds");
                } else {
                    this.messages().send(p, "build-auto-none", Placeholder.component("reason",
                        this.messages().get("reason-" + r.reason().name().toLowerCase(Locale.ROOT).replace('_', '-'))));
                }
            });
            case "potwierdz" -> {
                if (this.ownCountryWithRank(p).isPresent()) {
                    this.plugin.plans().confirm(p);
                }
            }
            case "anuluj" -> this.cancelBuild(p, args);
            default -> {
                if (this.catalog(sender).stream().noneMatch(t -> t.id().equals(a))) {
                    this.messages().send(p, "build-unknown-type", Placeholder.unparsed("type", args[0]),
                        Placeholder.unparsed("types", types));
                    return;
                }
                this.ownCountryWithRank(p).ifPresent(k -> this.plugin.plans().create(p, k.id(), a));
            }
        }
    }

    java.util.List<BuildingTemplate> catalog(final CommandSender sender) {
        final UUID country = sender instanceof Player p ? this.plugin.kraje().getCountryOf(p.getUniqueId()).map(KrajInfo::id).orElse(null) : null;
        return this.plugin.catalog(country);
    }

    private Optional<KrajInfo> cancelCountry(final Player p) {
        final var own = this.plugin.kraje().getCountryOf(p.getUniqueId());
        if (own.isEmpty()) { this.messages().send(p, "no-country"); return own; }
        if (!this.plugin.kraje().getRank(p.getUniqueId()).map(r -> r.atLeast(Rank.ADVISOR)).orElse(false)
            && !p.hasPermission(PERM_ADMIN)) { p.sendMessage(Component.text("Budowę może anulować władca lub doradca kraju.")); return Optional.empty(); }
        return own;
    }

    /**
     * {@code /ludnosc buduj anuluj}: without arguments only the preview is cancelled (or the list of what could be
     * cancelled is shown — an expired preview never cancels a site any more); {@code anuluj <id>} cancels that site,
     * {@code anuluj potwierdz} the site you stand on / the only site / the only queued order. A site where nothing was
     * done yet gets the whole price back, a started one {@code building.refund-on-cancel}.
     */
    private void cancelBuild(final Player p, final String[] args) {
        if (args.length == 1 && this.plugin.plans().hasPlan(p)) { this.plugin.plans().cancel(p); return; }
        final var own = this.cancelCountry(p);
        if (own.isEmpty()) return;
        final UUID country = own.get().id();
        final var sites = this.plugin.data().sitesOf(country).stream().filter(s -> !s.is(ConstructionSite.RESTORE)).toList();
        final var queue = this.plugin.data().queue(country);
        final String arg = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null;
        ConstructionSite site = null;
        if ("potwierdz".equals(arg)) {
            final ConstructionSite here = this.plugin.construction().siteAt(p.getLocation());
            if (here != null && sites.contains(here)) site = here;
            else if (sites.size() == 1 && queue.isEmpty()) site = sites.getFirst();
            else if (sites.isEmpty() && queue.size() == 1) {
                final long back = this.plugin.construction().cancelQueued(queue.getFirst(), null);
                p.sendMessage(Component.text("Anulowano zlecenie z kolejki: " + this.plain(this.typeName(queue.getFirst().type))
                    + ". Zwrot: " + this.plugin.formatMoney(back) + " (100%).", NamedTextColor.YELLOW));
                return;
            }
        } else if (arg != null && arg.length() >= 8) {
            final var match = sites.stream().filter(s -> s.id.toString().startsWith(arg)).toList();
            if (match.size() == 1) site = match.getFirst();
        }
        if (site == null) {
            if (args.length == 1) {
                p.sendMessage(Component.text("Nie masz podglądu budowy do anulowania — nic nie zostało anulowane.", NamedTextColor.GRAY));
            } else {
                p.sendMessage(Component.text("Nie wskazano jednoznacznie placu budowy.", NamedTextColor.RED));
            }
            if (sites.isEmpty() && queue.isEmpty()) {
                p.sendMessage(Component.text("Twój kraj nie ma placów budowy ani zleceń w kolejce.", NamedTextColor.GRAY));
                return;
            }
            p.sendMessage(Component.text("Anulowanie placu: /ludnosc buduj anuluj <id> (kliknij poniżej) albo /ludnosc buduj anuluj potwierdz "
                + "(plac, na którym stoisz). Kolejka: /ludnosc kolejka anuluj <nr>.", NamedTextColor.GRAY));
            for (final var s : sites) {
                final String id = s.id.toString().substring(0, 8);
                final boolean untouched = pl.medivalrpg.ludnosc.logic.BuildFixes.untouched(s.phase, s.cursor, 0);
                p.sendMessage(Component.text(" " + id + " — " + this.plain(this.typeName(s.type)) + " — " + s.originX + " " + s.originY + " "
                        + s.originZ + " — " + this.plugin.construction().percent(s) + "% — zwrot "
                        + Math.round(pl.medivalrpg.ludnosc.logic.BuildFixes.cancelRefund(untouched, this.plugin.settings().refundOnCancel) * 100)
                        + "%", NamedTextColor.YELLOW)
                    .hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby wpisać /ludnosc buduj anuluj " + id)))
                    .clickEvent(ClickEvent.suggestCommand("/ludnosc buduj anuluj " + id)));
            }
            return;
        }
        final boolean untouched = pl.medivalrpg.ludnosc.logic.BuildFixes.untouched(site.phase, site.cursor, 0);
        final double fraction = pl.medivalrpg.ludnosc.logic.BuildFixes.cancelRefund(untouched, this.plugin.settings().refundOnCancel);
        final long back = this.plugin.construction().cancel(site, fraction, null);
        this.plugin.getLogger().info(p.getName() + " anulował budowę " + site.type + " (" + site.id.toString().substring(0, 8) + ") kraju "
            + own.get().name() + ", zwrot " + back + " md (" + Math.round(fraction * 100) + "%)");
        p.sendMessage(Component.text("Anulowano budowę " + this.plain(this.typeName(site.type)) + ". Zwrot: " + this.plugin.formatMoney(back)
            + " (" + Math.round(fraction * 100) + "%" + (untouched ? ", prace jeszcze się nie zaczęły" : "")
            + "). Niewykorzystane materiały wracają do magazynu lub pozostają na placu.", NamedTextColor.YELLOW));
    }

    private String plain(final String mini) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(this.messages().parse(mini));
    }

    // ------------------------------------------------------------------ houses (admin)

    private void house(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            this.messages().send(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player p)) {
            this.messages().send(sender, "players-only");
            return;
        }
        final String a = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (a.equals("usun")) {
            this.removeBuildingHere(p);
            return;
        }
        if (!a.equals("zglos")) {
            this.messages().send(p, "admin-usage", Placeholder.unparsed("usage", "/ludnosc dom zglos | usun"));
            return;
        }
        if (!Hooks.worldEdit()) {
            this.messages().send(p, "house-no-worldedit");
            return;
        }
        final BlockBox box = WorldEditHook.selection(p);
        final World w = box == null ? null : Bukkit.getWorld(box.world());
        if (box == null || w == null) {
            this.messages().send(p, "house-no-selection");
            return;
        }
        if (box.volume() > this.plugin.settings().registerMaxVolume) {
            this.messages().send(p, "house-too-big", Placeholder.unparsed("max", Long.toString(this.plugin.settings().registerMaxVolume)));
            return;
        }
        final UUID owner = this.plugin.buildings().ownerOf(box);
        if (owner == null) {
            this.messages().send(p, "house-not-country");
            return;
        }
        if (this.plugin.buildings().overlapsAny(box)) {
            this.messages().send(p, "house-overlap");
            return;
        }
        final int beds = BuildingService.countBeds(w, box);
        if (beds <= 0) {
            this.messages().send(p, "house-no-beds");
            return;
        }
        final BlockPos spawn = BuildingService.findSpawn(w, box);
        this.plugin.buildings().add(owner, BuildingService.REGISTERED_TYPE, BuildingFunction.HOUSING, box, beds, spawn, 0, true);
        this.messages().send(p, "house-registered", Placeholder.unparsed("beds", Integer.toString(beds)),
            Placeholder.unparsed("country", this.plugin.countryName(owner)));
    }

    private void removeBuildingHere(final Player p) {
        final Location l = p.getLocation();
        final Building b = this.plugin.data().buildingAt(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (b == null) {
            this.messages().send(p, "house-none-here");
            return;
        }
        this.plugin.buildings().remove(b);
        this.messages().send(p, "house-removed", Placeholder.parsed("name", this.typeName(b.type())));
    }

    // ------------------------------------------------------------------ admin

    private void admin(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) {
            this.messages().send(sender, "no-permission");
            return;
        }
        final String a = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        final String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (a) {
            case "krolestwo", "krolewstwo" -> this.plugin.royalRebuild().command(sender,rest.length==1?rest[0]:"help");
            case "dodaj" -> this.adminAdd(sender, rest);
            case "usun" -> this.adminRemove(sender, rest);
            case "budynek" -> this.adminBuilding(sender, rest);
            case "jedzenie" -> this.adminFood(sender, rest);
            case "kolejka" -> this.adminQueue(sender, rest);
            case "przebudowa" -> this.adminRedesign(sender, rest);
            case "drogi" -> this.adminRoads(sender, rest);
            case "modernizuj" -> this.adminModernize(sender, rest);
            case "pora" -> this.adminSeason(sender, rest);
            case "nieurodzaj" -> this.adminBlight(sender, rest);
            case "praca" -> this.adminWork(sender);
            case "dzien" -> {
                this.plugin.economy().runDay();
                this.messages().send(sender, "admin-daily-done");
            }
            case "wzrost" -> this.messages().send(sender, "admin-growth-done",
                Placeholder.unparsed("n", Integer.toString(this.plugin.economy().growthTick())));
            case "reload" -> {
                final int n = this.plugin.reload();
                this.messages().send(sender, "reloaded", Placeholder.unparsed("templates", Integer.toString(n)));
            }
            default -> this.messages().send(sender, "admin-usage", Placeholder.unparsed("usage",
                "/ludnosc admin dodaj|usun <kraj> <n> | budynek anuluj [id] | budynek usun | jedzenie <kraj> <ilość> | kolejka <kraj> [wyczysc] | przebudowa <kraj> [podglad|teraz|anuluj|reset] [darmowo] | drogi <kraj> [darmowo] | modernizuj <kraj> [wszystko] [darmowo] | pora info|ustaw <pora> [dzień] | nieurodzaj <kraj> [dni|koniec] | praca | dzien | wzrost | reload"));
        }
    }

    /** Country name and the trailing keywords (podglad, teraz, darmowo, wszystko) of an admin command. */
    private record CountryArgs(KrajInfo country, java.util.Set<String> flags) {
    }

    private Optional<CountryArgs> countryWithFlags(final CommandSender sender, final String[] args, final String usage) {
        final java.util.Set<String> known = java.util.Set.of("podglad", "teraz", "darmowo", "wszystko", "anuluj", "reset");
        final java.util.Set<String> flags = new java.util.HashSet<>();
        int end = args.length;
        while (end > 0 && known.contains(args[end - 1].toLowerCase(Locale.ROOT))) {
            flags.add(args[--end].toLowerCase(Locale.ROOT));
        }
        if (end == 0) {
            this.messages().send(sender, "admin-usage", Placeholder.unparsed("usage", usage));
            return Optional.empty();
        }
        return this.countryByName(sender, Arrays.copyOfRange(args, 0, end)).map(k -> new CountryArgs(k, flags));
    }

    private void adminRedesign(final CommandSender sender, final String[] args) {
        final var ca = this.countryWithFlags(sender, args, "/ludnosc admin przebudowa <kraj> [podglad|teraz|anuluj|reset] [darmowo]");
        if (ca.isEmpty()) {
            return;
        }
        final KrajInfo k = ca.get().country();
        final boolean free = ca.get().flags().contains("darmowo");
        if (ca.get().flags().contains("anuluj") || ca.get().flags().contains("reset")) {
            final boolean active = this.plugin.redesign().progress(k.id()).active();
            final long[] c = this.plugin.redesign().cancel(k.id());
            if (ca.get().flags().contains("anuluj")) {
                if (!active && c[0] == 0) {
                    this.messages().send(sender, "admin-redesign-none-active", Placeholder.unparsed("country", k.name()));
                } else {
                    this.messages().send(sender, "admin-redesign-cancelled", Placeholder.unparsed("country", k.name()),
                        Placeholder.unparsed("n", Long.toString(c[0])), Placeholder.unparsed("refund", this.plugin.formatMoney(c[1])));
                }
                return;
            }
        }
        if (ca.get().flags().contains("teraz") || ca.get().flags().contains("reset")) {
            final var out = this.plugin.redesign().force(k.id(), free);
            switch (out.kind()) {
                case BUSY -> {
                    this.messages().send(sender, "admin-redesign-busy", Placeholder.unparsed("country", k.name()));
                    this.redesignStatus(sender, k);
                }
                case SPED_UP -> {
                    this.messages().send(sender, "admin-redesign-sped-up", Placeholder.unparsed("country", k.name()),
                        Placeholder.unparsed("n", Integer.toString(Math.max(0, out.orders()))));
                    this.redesignStatus(sender, k);
                }
                case FAILED -> this.messages().send(sender, "redesign-none");
                default -> {
                    this.messages().send(sender, "admin-redesign-done", Placeholder.unparsed("country", k.name()),
                        Placeholder.unparsed("n", Integer.toString(Math.max(0, out.orders()))));
                    this.redesignStatus(sender, k);
                }
            }
            return;
        }
        if (this.plugin.redesign().inProgress(k.id())) {
            this.redesignStatus(sender, k); // the running redesign first, then the plan as it would be now
        }
        final var plan = sender instanceof Player p ? this.plugin.redesign().preview(p, k.id()) : this.plugin.redesign().compute(k.id());
        if (plan == null) {
            this.messages().send(sender, "redesign-none");
            return;
        }
        final long[] est = this.plugin.redesign().estimate(plan);
        long levelBlocks = 0;
        long quarters = 0;
        for (final var q : plan.quarters()) {
            if (q.free() && !q.green()) {
                quarters++;
                levelBlocks += (long) (q.maxX() - q.minX() + 1) * (q.maxZ() - q.minZ() + 1) * 2;
            }
        }
        final boolean freePreview = free || !this.plugin.settings().redesignAdminPays;
        this.messages().send(sender, "admin-redesign-preview", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("demolish", Integer.toString(plan.demolish().size())),
            Placeholder.unparsed("roads", Integer.toString(plan.newRoads().size())),
            Placeholder.unparsed("quarters", Long.toString(quarters)),
            Placeholder.unparsed("level", Long.toString(levelBlocks)),
            Placeholder.unparsed("cost", freePreview ? "0 (darmowo)" : this.plugin.formatMoney(est[0])),
            Placeholder.unparsed("time", est[1] >= 120 ? est[1] / 60 + " h" : est[1] + " min"));
    }

    /** Progress of a running redesign with the cancel / restart buttons. */
    private void redesignStatus(final CommandSender sender, final KrajInfo k) {
        final var p = this.plugin.redesign().progress(k.id());
        sender.sendMessage(this.messages().get("admin-redesign-status",
            Placeholder.unparsed("levelled", Integer.toString(p.levelled())), Placeholder.unparsed("quarters", Integer.toString(p.quarters())),
            Placeholder.unparsed("filled", Integer.toString(p.filled())), Placeholder.unparsed("fill", Integer.toString(p.fill())),
            Placeholder.unparsed("demolish", Integer.toString(p.demolish())), Placeholder.unparsed("roads", Integer.toString(p.roads())),
            Placeholder.unparsed("queued", Integer.toString(p.queued())), Placeholder.unparsed("sites", Integer.toString(p.sites()))));
        if (!p.active()) {
            return;
        }
        final String base = "/ludnosc admin przebudowa " + k.name();
        sender.sendMessage(this.messages().get("admin-redesign-buttons",
            Placeholder.component("cancel", this.messages().get("admin-redesign-button-cancel")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(this.messages().get("admin-redesign-button-cancel-hover")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(base + " anuluj"))),
            Placeholder.component("reset", this.messages().get("admin-redesign-button-reset")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(this.messages().get("admin-redesign-button-reset-hover")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(base + " reset")))));
    }

    private void adminRoads(final CommandSender sender, final String[] args) {
        final var ca = this.countryWithFlags(sender, args, "/ludnosc admin drogi <kraj> [darmowo]");
        if (ca.isEmpty()) {
            return;
        }
        final KrajInfo k = ca.get().country();
        final boolean free = ca.get().flags().contains("darmowo") || !this.plugin.settings().redesignAdminPays;
        final var r = this.plugin.settlement().resurfaceRoads(k.id(), free ? Long.MAX_VALUE : this.plugin.settlement().treasury(k.id()),
            "admin", free);
        this.messages().send(sender, "admin-roads-done", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("result", switch (r) {
                case OK -> "zlecona";
                case INSUFFICIENT_FUNDS -> "brak pieniędzy w skarbcu";
                case TOO_MANY_ORDERS -> "kolejka pełna";
                default -> "brak dróg albo mają już styl epoki";
            }));
    }

    private void adminModernize(final CommandSender sender, final String[] args) {
        final var ca = this.countryWithFlags(sender, args, "/ludnosc admin modernizuj <kraj> [wszystko] [darmowo]");
        if (ca.isEmpty()) {
            return;
        }
        final KrajInfo k = ca.get().country();
        final boolean free = ca.get().flags().contains("darmowo") || !this.plugin.settings().redesignAdminPays;
        final int n = this.plugin.repairs().forceModernize(k.id(), free);
        this.messages().send(sender, "admin-modernize-done", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("n", Integer.toString(n)));
    }

    /** {@code <kraj...> <n>} → country + number (or empty after an error message). */
    private Optional<Map.Entry<KrajInfo, Long>> countryAndNumber(final CommandSender sender, final String[] args, final String usage) {
        if (args.length < 2) {
            this.messages().send(sender, "admin-usage", Placeholder.unparsed("usage", usage));
            return Optional.empty();
        }
        final Long n = parseLong(args[args.length - 1]);
        if (n == null || n < 0) {
            this.messages().send(sender, "invalid-number", Placeholder.unparsed("value", args[args.length - 1]));
            return Optional.empty();
        }
        return this.countryByName(sender, Arrays.copyOf(args, args.length - 1)).map(k -> Map.entry(k, n));
    }

    // ------------------------------------------------------------------ seasons and work

    /** {@code /ludnosc pora}: the season, the table of all seasons and the poor harvest of the own country. */
    private void season(final CommandSender sender) {
        final Messages m = this.messages();
        final var seasons = this.plugin.seasons();
        if (!seasons.enabled()) {
            m.send(sender, "season-disabled");
            return;
        }
        sender.sendMessage(m.get("season-header"));
        sender.sendMessage(seasons.line("season-now"));
        final var rules = this.plugin.settings().work.seasons;
        for (final pl.medivalrpg.ludnosc.logic.Season s : pl.medivalrpg.ludnosc.logic.Season.values()) {
            final String hint = "season-hint." + s.id(); // keys checked by ConfigTest#seasonHints
            sender.sendMessage(m.get("season-row", Placeholder.unparsed("season", s.displayName()),
                Placeholder.unparsed("food", pl.medivalrpg.ludnosc.service.SeasonService.times(rules.foodMultiplier(s))),
                Placeholder.unparsed("growth", Long.toString(Math.round(rules.growthChance(s) * 100))),
                Placeholder.unparsed("blight", String.format(Locale.ROOT, "%.1f", rules.blightChance().get(s) * 100).replace('.', ',')),
                Placeholder.component("hint", m.get(hint))));
        }
        if (sender instanceof Player p) {
            this.plugin.kraje().getCountryOf(p.getUniqueId()).ifPresent(k -> {
                final CountryState st = this.plugin.data().stateIfPresent(k.id());
                if (seasons.blight(k.id()) && st != null) {
                    sender.sendMessage(m.get("info-blight", Placeholder.unparsed("until", seasons.when(st.blightUntil)),
                        Placeholder.unparsed("factor", pl.medivalrpg.ludnosc.service.SeasonService.times(rules.blightFactor()))));
                }
            });
        }
        m.send(sender, "season-advice");
    }

    /** {@code /ludnosc praca [kraj]}: who works where, today's harvest and catch. */
    private void work(final CommandSender sender, final String[] args) {
        final Optional<KrajInfo> target = this.targetCountry(sender, args);
        if (target.isEmpty()) {
            return;
        }
        final KrajInfo k = target.get();
        final Messages m = this.messages();
        final var s = this.plugin.work().summary(k.id());
        sender.sendMessage(m.get("work-header", Placeholder.unparsed("country", k.name())));
        boolean any = false;
        for (final pl.medivalrpg.ludnosc.logic.JobBoard.Job j : pl.medivalrpg.ludnosc.logic.JobBoard.Job.values()) {
            final int places = s.places().get(j);
            if (places == 0) {
                continue;
            }
            any = true;
            sender.sendMessage(m.get("work-line", Placeholder.unparsed("job", j.title()),
                Placeholder.unparsed("workers", Integer.toString(s.workers().get(j))),
                Placeholder.unparsed("slots", Integer.toString(s.slots().get(j))),
                Placeholder.unparsed("places", Integer.toString(places)),
                Placeholder.unparsed("professions", String.join(", ", this.plugin.settings().work.professions.getOrDefault(j, List.of())))));
        }
        if (!any) {
            m.send(sender, "work-none");
        }
        sender.sendMessage(m.get("work-free", Placeholder.unparsed("adults", Integer.toString(s.adults())),
            Placeholder.unparsed("assigned", Integer.toString(s.assigned()))));
        sender.sendMessage(m.get("work-today", Placeholder.unparsed("food", Long.toString(s.foodToday())),
            Placeholder.unparsed("cap", Long.toString(s.foodCap())), Placeholder.unparsed("fish", Long.toString(s.fishToday())),
            Placeholder.unparsed("fishcap", Long.toString(s.fishCap()))));
        if (this.plugin.seasons().enabled()) {
            sender.sendMessage(m.get("work-season", Placeholder.unparsed("factor",
                pl.medivalrpg.ludnosc.service.SeasonService.times(this.plugin.seasons().foodFactor(k.id())))));
        }
    }

    /** {@code /ludnosc admin pora [info | ustaw <pora> [dzień]]}. */
    private void adminSeason(final CommandSender sender, final String[] args) {
        final Messages m = this.messages();
        final var seasons = this.plugin.seasons();
        final String a = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (a.equals("ustaw")) {
            final pl.medivalrpg.ludnosc.logic.Season season = args.length > 1 ? pl.medivalrpg.ludnosc.logic.Season.parse(args[1]) : null;
            if (season == null) {
                m.send(sender, "admin-usage", Placeholder.unparsed("usage", "/ludnosc admin pora ustaw <wiosna|lato|jesien|zima> [dzień]"));
                return;
            }
            int day = 1;
            if (args.length > 2) {
                try {
                    day = Integer.parseInt(args[2]);
                } catch (final NumberFormatException e) {
                    m.send(sender, "invalid-number", Placeholder.unparsed("value", args[2]));
                    return;
                }
            }
            if (!seasons.enabled()) {
                m.send(sender, "season-disabled");
                return;
            }
            seasons.set(season, day);
            sender.sendMessage(seasons.line("admin-season-set"));
            return;
        }
        if (!a.equals("info")) {
            m.send(sender, "admin-usage", Placeholder.unparsed("usage", "/ludnosc admin pora info | ustaw <pora> [dzień]"));
            return;
        }
        if (!seasons.enabled()) {
            m.send(sender, "season-disabled");
            return;
        }
        final var state = seasons.state();
        sender.sendMessage(seasons.line("season-now"));
        sender.sendMessage(m.get("admin-season-info", Placeholder.unparsed("start", seasons.when(state.startedAt())),
            Placeholder.unparsed("end", seasons.when(state.endsAt())),
            Placeholder.unparsed("length", String.format(Locale.ROOT, "%.2f", this.plugin.settings().work.seasons.lengthDays())
                .replaceAll("0+$", "").replaceAll("\\.$", "")),
            Placeholder.unparsed("serial", Long.toString(state.serial()))));
        final List<Component> blights = new ArrayList<>();
        for (final KrajInfo k : this.plugin.kraje().getCountries()) {
            if (seasons.blight(k.id())) {
                blights.add(Component.text(k.name() + " (do " + seasons.when(this.plugin.data().state(k.id()).blightUntil) + ")"));
            }
        }
        sender.sendMessage(m.get("admin-season-blights", Placeholder.component("list", this.list(blights))));
    }

    /** {@code /ludnosc admin nieurodzaj <kraj> [dni|koniec]}. */
    private void adminBlight(final CommandSender sender, final String[] args) {
        final Messages m = this.messages();
        if (args.length == 0) {
            m.send(sender, "admin-usage", Placeholder.unparsed("usage", "/ludnosc admin nieurodzaj <kraj> [dni|koniec]"));
            return;
        }
        final String lastArg = args[args.length - 1].toLowerCase(Locale.ROOT);
        Integer days = null;
        boolean end = false;
        String[] name = args;
        if (lastArg.equals("koniec")) {
            end = true;
            name = Arrays.copyOf(args, args.length - 1);
        } else if (args.length > 1) {
            try {
                days = Math.max(1, Math.min(60, Integer.parseInt(lastArg)));
                name = Arrays.copyOf(args, args.length - 1);
            } catch (final NumberFormatException ignored) {
                // the whole text is the country name
            }
        }
        final Optional<KrajInfo> k = this.countryByName(sender, name);
        if (k.isEmpty()) {
            return;
        }
        if (end) {
            m.send(sender, this.plugin.seasons().endBlight(k.get().id()) ? "admin-blight-ended" : "admin-blight-none",
                Placeholder.unparsed("country", k.get().name()));
            return;
        }
        final int d = days == null ? this.plugin.settings().work.seasons.blightMaxDays() : days;
        this.plugin.seasons().startBlight(k.get().id(), d);
        m.send(sender, "admin-blight-started", Placeholder.unparsed("country", k.get().name()),
            Placeholder.unparsed("days", Integer.toString(d)));
    }

    /** {@code /ludnosc admin praca}: the job of the villager you look at (or the nearest citizen), else the statistics. */
    private void adminWork(final CommandSender sender) {
        final Messages m = this.messages();
        org.bukkit.entity.Villager target = null;
        if (sender instanceof Player p) {
            if (p.getTargetEntity(8) instanceof org.bukkit.entity.Villager v) {
                target = v;
            } else {
                double best = Double.MAX_VALUE;
                for (final org.bukkit.entity.Entity e : p.getNearbyEntities(6, 4, 6)) {
                    if (e instanceof org.bukkit.entity.Villager v && this.plugin.data().citizen(v.getUniqueId()) != null
                        && v.getLocation().distanceSquared(p.getLocation()) < best) {
                        best = v.getLocation().distanceSquared(p.getLocation());
                        target = v;
                    }
                }
            }
        }
        if (target == null) {
            final var st = this.plugin.work().stats();
            sender.sendMessage(m.get("admin-work-stats", Placeholder.unparsed("boards", Integer.toString(st.boards())),
                Placeholder.unparsed("workers", Integer.toString(st.workers())),
                Placeholder.unparsed("harvests", Integer.toString(st.harvestsMinute())),
                Placeholder.unparsed("deliveries", Integer.toString(st.deliveriesMinute())),
                Placeholder.unparsed("paths", Integer.toString(st.pathsMinute())),
                Placeholder.unparsed("catches", Integer.toString(st.catchesMinute())),
                Placeholder.unparsed("ms", String.format(Locale.ROOT, "%.2f", st.lastMs())),
                Placeholder.unparsed("max", String.format(Locale.ROOT, "%.2f", st.maxMs())),
                Placeholder.unparsed("over", Integer.toString(st.overBudget()))));
            m.send(sender, "admin-work-hint");
            return;
        }
        final String name = target.customName() == null ? "wieśniak"
            : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(target.customName());
        sender.sendMessage(m.get("admin-work-header", Placeholder.unparsed("name", name)));
        for (final String line : this.plugin.work().describe(target)) {
            sender.sendMessage(m.get("admin-work-line", Placeholder.unparsed("line", line)));
        }
    }


    private void adminAdd(final CommandSender sender, final String[] args) {
        final var parsed = this.countryAndNumber(sender, args, "/ludnosc admin dodaj <kraj> <n>");
        if (parsed.isEmpty()) {
            return;
        }
        final KrajInfo k = parsed.get().getKey();
        final int n = (int) Math.min(100, parsed.get().getValue());
        Location loc = null;
        if (sender instanceof Player p && this.plugin.kraje().getCountryAt(p.getLocation()).map(KrajInfo::id)
            .map(k.id()::equals).orElse(false)) {
            loc = p.getLocation();
        } else {
            final World w = Bukkit.getWorld(k.capitalWorld());
            if (w != null && w.isChunkLoaded(k.capitalX(), k.capitalZ())) {
                final int x = k.capitalX() * 16 + 8;
                final int z = k.capitalZ() * 16 + 8;
                loc = new Location(w, x + 0.5, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
            }
        }
        if (loc == null) {
            this.messages().send(sender, "admin-not-on-land");
            return;
        }
        for (int i = 0; i < n; i++) {
            this.plugin.citizens().spawn(k.id(), loc);
        }
        this.messages().send(sender, "admin-added", Placeholder.unparsed("n", Integer.toString(n)),
            Placeholder.unparsed("country", k.name()));
    }

    private void adminRemove(final CommandSender sender, final String[] args) {
        final var parsed = this.countryAndNumber(sender, args, "/ludnosc admin usun <kraj> <n>");
        if (parsed.isEmpty()) {
            return;
        }
        final KrajInfo k = parsed.get().getKey();
        final int done = this.plugin.citizens().removeSome(k.id(), (int) Math.min(Integer.MAX_VALUE, parsed.get().getValue()));
        this.messages().send(sender, "admin-removed", Placeholder.unparsed("n", Integer.toString(done)),
            Placeholder.unparsed("country", k.name()));
    }

    private void adminFood(final CommandSender sender, final String[] args) {
        final var parsed = this.countryAndNumber(sender, args, "/ludnosc admin jedzenie <kraj> <ilość>");
        if (parsed.isEmpty()) {
            return;
        }
        final KrajInfo k = parsed.get().getKey();
        this.plugin.granary().setFood(k.id(), parsed.get().getValue());
        this.messages().send(sender, "admin-food", Placeholder.unparsed("country", k.name()),
            Placeholder.unparsed("food", Long.toString(parsed.get().getValue())));
    }

    private void adminBuilding(final CommandSender sender, final String[] args) {
        final String a = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (a.equals("usun")) {
            if (sender instanceof Player p) {
                this.removeBuildingHere(p);
            } else {
                this.messages().send(sender, "players-only");
            }
            return;
        }
        if (!a.equals("anuluj")) {
            this.messages().send(sender, "admin-usage", Placeholder.unparsed("usage", "/ludnosc admin budynek anuluj [id] | usun"));
            return;
        }
        ConstructionSite site = null;
        if (args.length > 1) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            for (final ConstructionSite s : this.plugin.data().allSites()) {
                if (s.id.toString().startsWith(prefix)) {
                    site = s;
                    break;
                }
            }
        } else if (sender instanceof Player p) {
            site = this.plugin.construction().siteAt(p.getLocation());
        }
        if (site == null) {
            this.messages().send(sender, "admin-no-site");
            return;
        }
        final long refund = this.plugin.construction().cancel(site, this.plugin.settings().refundOnCancel, "site-cancelled");
        this.messages().send(sender, "admin-site-cancelled", Placeholder.parsed("name", this.typeName(site.type)),
            Placeholder.unparsed("refund", this.plugin.formatMoney(refund)));
    }
}
