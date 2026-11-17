// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.gui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import pl.medivalrpg.firmy.bridge.BukkitNotifier;
import pl.medivalrpg.firmy.bridge.ShopBridge;
import pl.medivalrpg.firmy.core.FirmService;
import pl.medivalrpg.firmy.core.Names;
import pl.medivalrpg.firmy.core.People;
import pl.medivalrpg.firmy.core.Registry;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.FirmType;
import pl.medivalrpg.firmy.model.FirmsData;
import pl.medivalrpg.firmy.model.Role;

/**
 * {@code /firma}: chest GUI of one's company with tabs Konto, Ludzie, Sklepy, Magazyn, Kontrakty, Księga and (stage 2)
 * Zlecenia — the board of orders to take. Players without a company get the start screen (checklist, founding alone or
 * together, open gatherings, the board). Buttons either suggest a command in the chat or run one, so every rule stays in
 * the command path.
 */
public final class FirmGui implements Listener {

    public enum Tab { KONTO, LUDZIE, SKLEPY, MAGAZYN, KONTRAKTY, KSIEGA, ZLECENIA }

    /** One button: the item and what a click does (run a command, else suggest one in the chat, else nothing). */
    public record Button(ItemStack item, String run, String suggest) {
        public static Button info(final ItemStack item) {
            return new Button(item, null, null);
        }
    }

    /** Extra lines of the Kontrakty tab (stage 2 fills it). */
    public interface ContractsView {
        List<ItemStack> items(Firm f, UUID viewer);

        /** Buttons of the Kontrakty tab (default: the plain items without actions). */
        default List<Button> buttons(final Firm f, final Player viewer) {
            return this.items(f, viewer.getUniqueId()).stream().map(Button::info).toList();
        }
    }

    /** The board of orders anybody's company may take (stage 2): NPC wholesale and open contracts. */
    public interface BoardView {
        /** {@code f} is the viewer's company or null (players without one only look). */
        List<Button> board(Firm f, Player viewer);
    }

    private enum Kind { FIRM, START, BOARD }

    private static final class Holder implements InventoryHolder {
        final Kind kind;
        final String firm;
        final Tab tab;
        final Map<Integer, String> suggest = new java.util.HashMap<>();
        final Map<Integer, String> run = new java.util.HashMap<>();
        Inventory inv;

        Holder(final Kind kind, final String firm, final Tab tab) {
            this.kind = kind;
            this.firm = firm;
            this.tab = tab;
        }

        @Override
        public Inventory getInventory() {
            return this.inv;
        }
    }

    private final FirmService service;
    private final ShopBridge shops;
    private ContractsView contracts;
    private BoardView board;
    private final ThreadLocal<SimpleDateFormat> date = ThreadLocal.withInitial(() -> new SimpleDateFormat("dd.MM HH:mm"));

    public FirmGui(final FirmService service, final ShopBridge shops) {
        this.service = service;
        this.shops = shops;
    }

    public void setContracts(final ContractsView contracts) {
        this.contracts = contracts;
    }

    public void setBoard(final BoardView board) {
        this.board = board;
    }

    public boolean hasBoard() {
        return this.board != null;
    }

    public static ItemStack item(final Material m, final String name, final List<String> lore) {
        final ItemStack it = new ItemStack(m);
        final ItemMeta meta = it.getItemMeta();
        meta.displayName(BukkitNotifier.parse(name).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        final List<Component> l = new ArrayList<>();
        for (final String s : lore) {
            l.add(BukkitNotifier.parse(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private String money(final long v) {
        return this.service.ctx.fmt(v);
    }

    private static final Material[] TAB_ICONS = {Material.GOLD_INGOT, Material.PLAYER_HEAD, Material.OAK_SIGN, Material.CHEST,
        Material.PAPER, Material.WRITABLE_BOOK, Material.HAY_BLOCK};
    private static final String[] TAB_NAMES = {"Konto", "Ludzie", "Sklepy", "Magazyn", "Kontrakty", "Księga", "Zlecenia do wzięcia"};
    private static final String[] TAB_HINTS = {"Saldo, przychód, podatek, wpłata.", "Role, udziały, pensje, zaproszenia.",
        "Sklepy firmowe i ich zapasy.", "Skrzynie magazynu i dziennik.", "Kontrakty firmy w realizacji.", "Każdy ruch pieniędzy.",
        "Zlecenia hurtowe dworów NPC i kontrakty graczy."};

    public void open(final Player p, final Firm f, final Tab tab) {
        final Tab t = tab == Tab.ZLECENIA && this.board == null ? Tab.KONTO : tab;
        final Holder h = new Holder(Kind.FIRM, f.id, t);
        final Inventory inv = Bukkit.createInventory(h, 54, BukkitNotifier.parse("<dark_gray>Firma: " + Names.plain(f.name)));
        h.inv = inv;
        final Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == Tab.ZLECENIA && this.board == null) {
                continue;
            }
            inv.setItem(i, item(TAB_ICONS[i], (tabs[i] == t ? "<gold><bold>" : "<yellow>") + TAB_NAMES[i],
                List.of("<gray>" + TAB_HINTS[i], "<dark_gray>Kliknij, aby otworzyć.")));
        }
        inv.setItem(8, this.banner(f));
        this.pane(inv);
        final List<Button> content = switch (t) {
            case KONTO -> this.account(f, p.getUniqueId());
            case LUDZIE -> this.people(f);
            case SKLEPY -> wrap(this.shopItems(f));
            case MAGAZYN -> wrap(this.warehouse(f));
            case KONTRAKTY -> this.contracts == null ? List.of(Button.info(item(Material.PAPER, "<gray>Kontrakty",
                List.of("<gray>Kontrakty z depozytem pojawią się", "<gray>w kolejnym etapie.")))) : this.contractButtons(f, p);
            case KSIEGA -> wrap(this.journal(f));
            case ZLECENIA -> this.boardButtons(f, p);
        };
        this.fill(h, content);
        p.openInventory(inv);
    }

    private List<Button> contractButtons(final Firm f, final Player p) {
        final List<Button> b = this.contracts.buttons(f, p);
        if (b.isEmpty()) {
            return List.of(new Button(item(Material.PAPER, "<gray>Brak kontraktów w realizacji", List.of(
                "<gray>Weź zlecenie z zakładki <yellow>Zlecenia do wzięcia</yellow>", "<gray>albo zamów towar u innej firmy:",
                "<dark_gray>/firma kontrakt zamow <firma|wszyscy> ...")), this.board == null ? null : "firma zlecenia", null));
        }
        return b;
    }

    private List<Button> boardButtons(final Firm f, final Player p) {
        final List<Button> b = this.board == null ? List.of() : this.board.board(f, p);
        if (b.isEmpty()) {
            return List.of(Button.info(item(Material.HAY_BLOCK, "<gray>Tablica jest teraz pusta", List.of(
                "<gray>Dwory NPC wystawiają nowe zlecenia hurtowe", "<gray>codziennie i uzupełniają je w ciągu dnia."))));
        }
        return b;
    }

    private static List<Button> wrap(final List<ItemStack> items) {
        return items.stream().map(Button::info).toList();
    }

    private void pane(final Inventory inv) {
        final ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 9; i < 18; i++) {
            inv.setItem(i, pane);
        }
    }

    private void fill(final Holder h, final List<Button> content) {
        for (int i = 0; i < content.size() && i < 36; i++) {
            final Button b = content.get(i);
            h.inv.setItem(18 + i, b.item());
            if (b.run() != null) {
                h.run.put(18 + i, b.run());
            } else if (b.suggest() != null) {
                h.suggest.put(18 + i, b.suggest());
            }
        }
    }

    // ------------------------------------------------------------------ start screen (no company yet)

    /** {@code /firma} without a company: what firms give, the founding checklist here, founding alone or together. */
    public void openStart(final Player p, final UUID here) {
        final Holder h = new Holder(Kind.START, null, null);
        final Inventory inv = Bukkit.createInventory(h, 54, BukkitNotifier.parse("<dark_gray>Firmy — jak zacząć"));
        h.inv = inv;
        final Registry reg = this.service.registry;
        final var s = this.service.ctx.settings;
        inv.setItem(4, item(Material.GOLDEN_HELMET, "<gold><bold>Firmy graczy", List.of(
            "<gray>Wspólne konto w banku, sklepy i magazyn firmy,",
            "<gray>zlecenia hurtowe dworów NPC i kontrakty graczy,",
            "<gray>pensje, udziały i dywidendy dla wspólników.",
            "",
            "<yellow>Jak zarabia firma?",
            "<gray>• bierze zlecenia z <gold>Tablicy zleceń</gold> i dowozi towar,",
            "<gray>• sprzedaje w sklepach firmowych ([Sklep] + /firma sklep),",
            "<gray>• wykonuje kontrakty innych graczy, firm i krajów.")));
        this.pane(inv);
        final List<String> lines = new ArrayList<>();
        boolean all = true;
        for (final Registry.Check c : reg.readiness(p.getUniqueId(), here)) {
            lines.add((c.ok() ? "<green>✔ " : "<red>✘ ") + "<gray>" + Names.plain(c.text()));
            all &= c.ok();
        }
        lines.add("");
        lines.add(all ? "<green>Możesz założyć firmę tutaj." : "<yellow>Popraw to, co czerwone (albo zrób zrzutkę).");
        final List<Button> content = new ArrayList<>();
        content.add(Button.info(item(all ? Material.LIME_DYE : Material.ORANGE_DYE, "<yellow>Czy mogę założyć firmę tutaj?", lines)));
        final long target = reg.gatheringTarget();
        for (final FirmType t : List.of(FirmType.CECH, FirmType.KOMPANIA, FirmType.MANUFAKTURA)) {
            final Material m = t == FirmType.CECH ? Material.ANVIL : t == FirmType.KOMPANIA ? Material.CHEST_MINECART : Material.CRAFTING_TABLE;
            content.add(new Button(item(m, "<green>Załóż: " + t.displayName(), List.of(
                "<gray>" + t.description() + ".",
                "<gray>Koszt: " + this.money(s.fee) + " opłaty dla kraju + " + this.money(s.minCapital) + " kapitału.",
                t == FirmType.MANUFAKTURA ? "<gray>Wymaga Renesansu w kraju siedziby; warsztaty z robotnikami NPC."
                    : t == FirmType.CECH ? "<gray>Bez wymogu epoki; 1 warsztat z robotnikami NPC (przepisy średniowieczne)."
                    : "<gray>Bez wymogu epoki.",
                "<dark_gray>/firma zaloz " + t.key() + " <nazwa>",
                "",
                "<gold>Kliknij</gold> <gray>– wpisz nazwę na czacie")), null, "/firma zaloz " + t.key() + " "));
        }
        content.add(new Button(item(Material.GOLD_NUGGET, "<gold>Zrzutka — załóżcie firmę razem", List.of(
            "<gray>Nie masz " + this.money(target) + "? Zacznij zrzutkę od " + this.money(s.gatheringMinContribution) + ".",
            "<gray>Inni gracze dokładają; po zebraniu kwoty wniosek",
            "<gray>idzie do kraju siedziby. Udziały według wkładu,",
            "<gray>ty zostajesz mistrzem, reszta wspólnikami.",
            "<gray>Bez kompletu w " + s.applicationHours + " h każdy dostaje wkład z powrotem.",
            "<dark_gray>/firma zrzutka zaloz <typ> <wkład> <nazwa>",
            "",
            "<gold>Kliknij</gold> <gray>– wpisz na czacie")), null, "/firma zrzutka zaloz cech 5zl "));
        for (final FirmsData.Application a : reg.gatherings()) {
            if (content.size() >= 24) {
                break;
            }
            final List<String> lore = new ArrayList<>(List.of(
                "<gray>" + a.type.displayName() + " · siedziba: <white>" + Names.plain(a.countryName),
                "<gray>Zebrano: <gold>" + this.money(a.collected()) + "</gold> z " + this.money(target),
                "<gray>Wspólnicy: <white>" + Names.plain(String.join(", ", a.contributorNames.values())),
                "<gray>Do: " + this.date.get().format(new Date(a.expiresAt)),
                "",
                "<gold>Kliknij</gold> <gray>– dołóż swój wkład (np. 5zl)"));
            content.add(new Button(item(Material.EMERALD, "<yellow>Zrzutka: " + Names.plain(a.name), lore), null,
                "/firma zrzutka dolacz " + a.id + " "));
        }
        this.service.people.invitation(p.getUniqueId()).ifPresent(inv2 -> content.add(new Button(item(Material.LIME_DYE,
            "<green>Zaproszenie: " + Names.plain(inv2), List.of("<gold>Kliknij</gold> <gray>– dołącz do firmy")), "firma przyjmij", null)));
        for (final FirmsData.Application a : this.service.ctx.data.applications.values()) {
            if (a.founder.equals(p.getUniqueId().toString()) && !a.gathering()) {
                content.add(new Button(item(Material.CLOCK, "<yellow>Twój wniosek: " + Names.plain(a.name), List.of(
                    "<gray>Czeka na Władcę/Doradcę kraju " + Names.plain(a.countryName),
                    "<gray>do " + this.date.get().format(new Date(a.expiresAt)) + ".", "<gold>Kliknij</gold> <gray>– szczegóły")), "firma wniosek", null));
            }
        }
        if (this.board != null) {
            content.add(new Button(item(Material.HAY_BLOCK, "<gold>Tablica zleceń", List.of(
                "<gray>Zlecenia hurtowe dworów NPC i kontrakty",
                "<gray>graczy, które może wziąć twoja przyszła firma.",
                "", "<gold>Kliknij</gold> <gray>– zobacz")), "firma zlecenia", null));
        }
        content.add(new Button(item(Material.GOLD_BLOCK, "<yellow>Ranking firm", List.of("<gray>Obrót z 30 dni.")), "firma ranking", null));
        content.add(new Button(item(Material.KNOWLEDGE_BOOK, "<aqua>Pomoc: komendy firm", List.of("<gray>Pełna lista na czacie.")), "firma pomoc", null));
        this.fill(h, content);
        p.openInventory(inv);
    }

    /** Board of orders for a player without a company (look only; taking needs a company). */
    public void openBoard(final Player p) {
        final Holder h = new Holder(Kind.BOARD, null, null);
        final Inventory inv = Bukkit.createInventory(h, 54, BukkitNotifier.parse("<dark_gray>Tablica zleceń firm"));
        h.inv = inv;
        inv.setItem(0, item(Material.ARROW, "<yellow>« Firmy — jak zacząć", List.of("<gray>Załóż firmę, aby brać zlecenia.")));
        h.run.put(0, "firma");
        inv.setItem(4, item(Material.HAY_BLOCK, "<gold>Tablica zleceń", List.of(
            "<gray>Zlecenia przyjmuje mistrz albo wspólnik firmy.", "<gray>Zapłata trafia na konto firmy (minus podatek).")));
        this.pane(inv);
        this.fill(h, this.boardButtons(null, p));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ tabs

    private ItemStack banner(final Firm f) {
        ItemStack b = null;
        if (f.banner != null) {
            try {
                b = ItemStack.deserializeBytes(Base64.getDecoder().decode(f.banner));
            } catch (final RuntimeException ignored) {
                b = null;
            }
        }
        if (b == null) {
            b = new ItemStack(Material.WHITE_BANNER);
        }
        final ItemMeta meta = b.getItemMeta();
        meta.displayName(BukkitNotifier.parse("<color:" + f.color + "><bold>" + Names.plain(f.name)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(List.of(
            BukkitNotifier.parse("<gray>" + f.type.displayName() + " · siedziba: " + Names.plain(f.seatCountryName == null ? "—" : f.seatCountryName))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE),
            BukkitNotifier.parse("<dark_gray>id " + f.id).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        b.setItemMeta(meta);
        return b;
    }

    private List<Button> account(final Firm f, final UUID viewer) {
        final List<Button> out = new ArrayList<>();
        final long bal = this.service.treasury.balance(f);
        final String week = this.service.ctx.windowStart(7);
        final List<String> lore = new ArrayList<>(List.of(
            "<gray>Saldo: <gold>" + this.money(bal),
            "<gray>Przychód 7 dni: <green>" + this.money(f.income(week)),
            "<gray>Podatek 7 dni: <red>" + this.money(f.tax(week)),
            "<gray>Przychód 30 dni: <green>" + this.money(f.income(this.service.ctx.windowStart(30))),
            "<gray>Podatek od przychodu: <yellow>" + this.service.treasury.taxTarget(f).map(t -> t.getValue() / 100.0 + " %").orElse("— (brak siedziby)"),
            "<gray>Wykonane kontrakty: <white>" + f.contractsDone));
        if (f.totalArrears() > 0) {
            lore.add("<red>Zaległe pensje: " + this.money(f.totalArrears()) + " (dni: " + f.unpaidDays + ")");
        }
        if (f.frozen) {
            lore.add("<red>Zamrożona: " + Names.plain(f.frozenReason));
        }
        if (f.dormantSince != 0) {
            lore.add("<yellow>Uśpiona — sklepy stoją.");
        }
        if (f.warSuspended) {
            lore.add("<red>Sklepy zawieszone na czas wojny.");
        }
        if (!f.payouts.isEmpty()) {
            lore.add("<yellow>Oczekujące dywidendy: " + f.payouts.size());
        }
        out.add(Button.info(item(Material.GOLD_BLOCK, "<gold>Konto firmy", lore)));
        out.add(new Button(item(Material.EMERALD, "<green>Wpłać do firmy", List.of("<gray>Z twojego konta bankowego.",
            "<gray>Kwota np. <white>10zl</white>, <white>5sr</white> (sama liczba = miedziaki).", "<dark_gray>/firma wplac <kwota>")), null, "/firma wplac "));
        if (f.has(viewer, Role.MISTRZ)) {
            out.add(new Button(item(Material.GOLD_NUGGET, "<yellow>Dywidenda", List.of("<gray>Dla wszystkich udziałowców według udziałów.",
                "<gray>Duża wypłata czeka 24 h.", "<dark_gray>/firma dywidenda <kwota>")), null, "/firma dywidenda "));
        }
        if (this.board != null) {
            out.add(new Button(item(Material.HAY_BLOCK, "<gold>Zarabiaj: zlecenia do wzięcia", List.of(
                "<gray>Zlecenia hurtowe dworów NPC i kontrakty graczy.", "<gray>Zapłata z depozytu na konto firmy.",
                "", "<gold>Kliknij</gold> <gray>– otwórz tablicę")), "firma zlecenia", null));
        }
        out.add(new Button(item(Material.OAK_SIGN, "<yellow>Sklep firmowy", List.of("<gray>Postaw zwykły [Sklep] na ziemi kraju siedziby,",
            "<gray>spójrz na tabliczkę i kliknij tutaj.", "<gray>Utarg trafia na konto firmy.")), "firma sklep", null));
        return out;
    }

    private List<Button> people(final Firm f) {
        final List<Button> out = new ArrayList<>();
        for (final Firm.Member m : f.members.values()) {
            final Material mat = m.role == Role.MISTRZ ? Material.GOLDEN_HELMET : m.role == Role.WSPOLNIK ? Material.IRON_HELMET : Material.LEATHER_HELMET;
            final List<String> lore = new ArrayList<>(List.of("<gray>Rola: <white>" + m.role.displayName(),
                "<gray>Udziały: <white>" + People.pct(m.shareBp), "<gray>Pensja: <white>" + this.money(m.salary) + " / dzień"));
            final long owed = f.arrears.getOrDefault(m.uuid, 0L);
            if (owed > 0) {
                lore.add("<red>Zaległa pensja: " + this.money(owed));
            }
            lore.add("<dark_gray>Kliknij: ustaw pensję");
            out.add(new Button(item(mat, "<yellow>" + Names.plain(m.name), lore), null, "/firma pensja " + m.name + " "));
        }
        out.add(new Button(item(Material.WRITABLE_BOOK, "<green>Zaproś", List.of("<dark_gray>/firma zapros <gracz> <wspolnik|czeladnik>")),
            null, "/firma zapros "));
        return out;
    }

    private List<ItemStack> shopItems(final Firm f) {
        final List<ItemStack> out = new ArrayList<>();
        for (final Map<String, Object> s : this.shops.shopsOf(f)) {
            Material m = Material.matchMaterial(String.valueOf(s.get("item")));
            if (m == null || !m.isItem() || m.isAir()) {
                m = Material.CHEST;
            }
            final boolean buy = "BUY".equals(s.get("type"));
            final Object stock = s.get("stock");
            final boolean empty = !buy && stock instanceof Number n && n.intValue() < ((Number) s.get("amount")).intValue();
            out.add(item(m, (buy ? "<dark_red>Kupię " : "<green>Sprzedam ") + s.get("amount") + "× " + s.get("item"),
                List.of("<gray>Cena: <gold>" + this.money(((Number) s.get("price")).longValue()),
                    "<gray>Stan: " + (stock == null ? "<dark_gray>(niezaładowany)" : empty ? "<red>" + stock + " — PUSTY" : "<white>" + stock),
                    "<gray>Miejsce: " + s.get("x") + " " + s.get("y") + " " + s.get("z"))));
        }
        if (out.isEmpty()) {
            out.add(item(Material.OAK_SIGN, "<gray>Brak sklepów", List.of("<gray>Postaw zwykły [Sklep] na ziemi kraju siedziby,",
                "<gray>spójrz na niego i wpisz <white>/firma sklep</white>.")));
        }
        return out;
    }

    private List<ItemStack> warehouse(final Firm f) {
        final List<ItemStack> out = new ArrayList<>();
        out.add(item(Material.CHEST, "<yellow>Skrzynie magazynu: " + f.warehouse.size() + "/" + this.service.ctx.settings.maxChests.getOrDefault(f.type, 6),
            f.warehouse.isEmpty() ? List.of("<gray>Spójrz na skrzynię na ziemi siedziby", "<gray>i wpisz <white>/firma magazyn</white>.")
                : f.warehouse.stream().map(c -> "<gray>" + c.x + " " + c.y + " " + c.z + " <dark_gray>(" + Names.plain(c.addedBy) + ")").limit(10).toList()));
        final List<Firm.ChestLog> logs = new ArrayList<>(this.service.warehouse.recent(f, 34));
        java.util.Collections.reverse(logs);
        for (final Firm.ChestLog l : logs) {
            out.add(item(l.delta < 0 ? Material.RED_DYE : Material.LIME_DYE,
                // item names come from anvils: never let them carry MiniMessage tags
                (l.delta < 0 ? "<red>" + Names.plain(l.player) + " wyjął " + (-l.delta) : "<green>" + Names.plain(l.player) + " włożył " + l.delta)
                    + "× " + Names.plain(l.item),
                List.of("<gray>" + this.date.get().format(new Date(l.time)) + " · skrzynia " + l.chest)));
        }
        return out;
    }

    private List<ItemStack> journal(final Firm f) {
        final List<ItemStack> out = new ArrayList<>();
        final List<Firm.Entry> entries = new ArrayList<>(f.journal);
        java.util.Collections.reverse(entries);
        for (final Firm.Entry e : entries) {
            if (out.size() >= 36) {
                break;
            }
            final Material m = e.amount > 0 ? Material.LIME_CONCRETE : e.amount < 0 ? Material.RED_CONCRETE : Material.PAPER;
            out.add(item(m, (e.amount > 0 ? "<green>+" + this.money(e.amount) : e.amount < 0 ? "<red>−" + this.money(-e.amount) : "<gray>" + Names.plain(e.kind))
                + " <dark_gray>" + this.date.get().format(new Date(e.time)),
                List.of("<white>" + Names.plain(e.text), "<dark_gray>" + Names.plain(e.kind) + (e.actor == null ? "" : " · " + Names.plain(e.actor)))));
        }
        return out;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        final int slot = event.getRawSlot();
        if (h.kind == Kind.FIRM) {
            final Firm f = this.service.ctx.data.firms.get(h.firm);
            if (f == null || !f.active() || !f.members.containsKey(p.getUniqueId().toString())) {
                p.closeInventory();
                return;
            }
            if (slot >= 0 && slot < Tab.values().length) {
                final Tab t = Tab.values()[slot];
                if (t == Tab.ZLECENIA && this.board == null) {
                    return;
                }
                Bukkit.getScheduler().runTask(this.serviceOwner, () -> this.open(p, f, t));
                return;
            }
        }
        final String run = h.run.get(slot);
        if (run != null) {
            p.closeInventory();
            Bukkit.getScheduler().runTask(this.serviceOwner, () -> p.performCommand(run));
            return;
        }
        final String cmd = h.suggest.get(slot);
        if (cmd != null) {
            p.closeInventory();
            p.sendMessage(BukkitNotifier.parse("<gold>[Firmy]</gold> <gray>Kliknij, aby wpisać: </gray>")
                .append(Component.text(cmd).clickEvent(ClickEvent.suggestCommand(cmd))));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private org.bukkit.plugin.Plugin serviceOwner;

    public FirmGui plugin(final org.bukkit.plugin.Plugin plugin) {
        this.serviceOwner = plugin;
        return this;
    }

    /** For callers outside the GUI (stage modules): a company visible to the viewer. */
    public Optional<Firm> firm(final String id) {
        return Optional.ofNullable(id == null ? null : this.service.ctx.data.firms.get(id));
    }
}
