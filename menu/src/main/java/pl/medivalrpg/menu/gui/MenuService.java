// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.menu.book.BookService;
import pl.medivalrpg.menu.config.MenuSettings;
import pl.medivalrpg.menu.config.Messages;
import pl.medivalrpg.menu.integration.InterakcjeHook;
import pl.medivalrpg.menu.integration.KrajeHook;
import pl.medivalrpg.menu.integration.PluginHooks;
import pl.medivalrpg.menu.model.ButtonDef;
import pl.medivalrpg.menu.model.MenuAction;
import pl.medivalrpg.menu.model.MenuConfig;
import pl.medivalrpg.menu.model.MenuDef;
import pl.medivalrpg.menu.model.MenuType;
import pl.medivalrpg.menu.text.Placeholders;

/** Renders menus from {@code menus.yml}, runs button actions and the nearby-player picker. Main thread only. */
public final class MenuService {

    public static final String PERM_ADMIN = "menu.admin";
    private static final int MAX_PICKER_HEADS = 45;

    private final Plugin plugin;
    private final Supplier<MenuConfig> menus;
    private final Supplier<MenuSettings> settings;
    private final Supplier<Messages> messages;
    private final Supplier<Placeholders> placeholders;
    private final KrajeHook kraje;
    private java.util.function.Predicate<Player> returnCheck = p -> false;
    private java.util.function.BiConsumer<Player, String> helpOpener = (p, id) -> { };
    private StepProgress progress = StepProgress.NONE;
    private final BookService books;
    private final ItemFactory items;

    public MenuService(final Plugin plugin, final Supplier<MenuConfig> menus, final Supplier<MenuSettings> settings,
                       final Supplier<Messages> messages, final Supplier<Placeholders> placeholders,
                       final KrajeHook kraje, final BookService books, final ItemFactory items) {
        this.plugin = plugin;
        this.menus = menus;
        this.settings = settings;
        this.messages = messages;
        this.placeholders = placeholders;
        this.kraje = kraje;
        this.books = books;
        this.items = items;
    }

    /** Condition {@code can-return}: is {@code /powrot} available for the player? */
    public void setReturnCheck(final java.util.function.Predicate<Player> check) {
        this.returnCheck = check;
    }

    /** Action {@code help:<kategoria>}: opens the generated help GUI (set by the plugin, see HelpService). */
    public void setHelpOpener(final java.util.function.BiConsumer<Player, String> opener) {
        this.helpOpener = opener;
    }

    /** Buttons with {@code progress:} (tutorial steps): who knows which of them the viewer has done. */
    public void setProgress(final StepProgress progress) {
        this.progress = progress;
    }

    // ------------------------------------------------------------------ opening

    /** Opens a menu; tells the player why when it is not possible. */
    public boolean open(final Player player, final String menuId) {
        return this.open(player, menuId, 0);
    }

    /** Opens page {@code page} (0-based, clamped) of a menu with generated content. */
    public boolean open(final Player player, final String menuId, final int page) {
        final MenuDef menu = this.menus.get().menu(menuId == null ? null : menuId.toLowerCase(Locale.ROOT));
        if (menu == null) {
            player.sendMessage(this.messages.get().get("unknown-menu", Placeholder.unparsed("menu", String.valueOf(menuId))));
            return false;
        }
        if (!this.canOpen(player, menu)) {
            player.sendMessage(this.messages.get().get("no-access"));
            this.sound(player, this.settings.get().soundError);
            return false;
        }
        player.openInventory(this.render(player, menu, page).getInventory());
        return true;
    }

    public boolean canOpen(final Player player, final MenuDef menu) {
        return pl.medivalrpg.menu.model.AccessRules.allows(menu.requiresPlugins(), menu.permission(),
            pl.medivalrpg.menu.integration.Requirements::met, permission -> pl.medivalrpg.menu.integration.PluginPermissions.has(player, permission));
    }

    /** Ids of menus the player may open (command suggestions). */
    public List<String> openableIds(final Player player) {
        return this.menus.get().menus().values().stream().filter(m -> this.canOpen(player, m)).map(MenuDef::id).toList();
    }

    MenuHolder render(final Player player, final MenuDef menu, final int requestedPage) {
        final Messages msg = this.messages.get();
        // Generated content decides the page count before the buttons (page:prev/next, <page>/<pages>) are placed.
        final Optional<List<CountryList.Entry>> countries = menu.type() == MenuType.COUNTRIES
            ? this.kraje.countries().map(CountryList::sorted) : Optional.empty();
        final Pages.Page<CountryList.Entry> page = Pages.of(countries.orElse(List.of()), requestedPage,
            menu.contentSlots().size());
        final TagResolver resolver = TagResolver.resolver(this.placeholders.get().resolver(player),
            Placeholder.unparsed("page", Integer.toString(page.index() + 1)),
            Placeholder.unparsed("pages", Integer.toString(page.pages())),
            Placeholder.unparsed("count", Integer.toString(countries.map(List::size).orElse(0))));
        final MenuHolder holder = new MenuHolder(menu.id(), menu.size(), msg.parse(menu.title(), resolver));

        if (menu.border() != null) {
            for (final int slot : borderSlots(menu.rows())) {
                final boolean corner = isCorner(slot, menu.rows());
                holder.set(slot, this.items.decoration(corner && menu.borderCorners() != null
                    ? menu.borderCorners() : menu.border()), null);
            }
        }

        final boolean hasCountry = this.hasCountry(player);
        final java.util.Map<String, Boolean> steps = menu.buttons().stream().anyMatch(b -> b.progress() != null)
            ? this.progress.states(player) : java.util.Map.of();
        final List<ButtonDef> auto = new ArrayList<>();
        for (final ButtonDef b : menu.buttons()) {
            if (!this.visible(player, b, hasCountry)) {
                continue;
            }
            if (b.action() instanceof MenuAction.Page(final int delta) && !page.canMove(delta)) {
                continue; // no previous/next page (or not a paged menu)
            }
            if (b.autoSlot()) {
                auto.add(b);
            } else {
                this.place(holder, b.slot(), player, menu, b, resolver, page.index(), steps);
            }
        }
        final List<Integer> autoSlots = AutoLayout.assign(menu.autoSlots(), auto.size());
        for (int i = 0; i < autoSlots.size(); i++) {
            this.place(holder, autoSlots.get(i), player, menu, auto.get(i), resolver, page.index(), steps);
        }

        if (menu.type() == MenuType.INTERACTIONS) {
            this.fillInteractions(holder, player, menu);
        } else if (menu.type() == MenuType.MOTIONS) {
            this.fillMotions(holder, player, menu);
        } else if (menu.type() == MenuType.COUNTRIES) {
            this.fillCountries(holder, menu, countries.isPresent(), page);
        }

        if (menu.filler() != null) {
            for (int slot = 0; slot < menu.size(); slot++) {
                if (holder.getInventory().getItem(slot) == null) {
                    holder.set(slot, this.items.decoration(menu.filler()), null);
                }
            }
        }
        return holder;
    }

    private boolean visible(final Player player, final ButtonDef b, final boolean hasCountry) {
        return pl.medivalrpg.menu.model.AccessRules.visible(b, pl.medivalrpg.menu.integration.Requirements::met,
            permission -> pl.medivalrpg.menu.integration.PluginPermissions.has(player, permission),
            hasCountry, b.condition() == pl.medivalrpg.menu.model.Condition.CAN_RETURN && this.returnCheck.test(player));
    }

    private boolean hasCountry(final Player player) {
        return PluginHooks.isEnabled("Kraje") && this.kraje.hasCountry(player.getUniqueId());
    }

    private void place(final MenuHolder holder, final int slot, final Player player, final MenuDef menu,
                       final ButtonDef b, final TagResolver base, final int page,
                       final java.util.Map<String, Boolean> steps) {
        // Tutorial steps: "✔" and a status line for a step, "done/total" for the summary button.
        String name = b.name();
        List<String> lore = b.lore();
        boolean glow = b.glow();
        TagResolver resolver = base;
        if (b.progress() != null && !steps.isEmpty()) {
            final Messages msg = this.messages.get();
            final List<String> extended = new ArrayList<>(lore);
            if (ButtonDef.PROGRESS_SUMMARY.equals(b.progress())) {
                resolver = TagResolver.resolver(base,
                    Placeholder.unparsed("done", Long.toString(steps.values().stream().filter(d -> d).count())),
                    Placeholder.unparsed("total", Integer.toString(steps.size())));
                extended.add(msg.raw("tutorial-summary-lore"));
                lore = extended;
            } else if (steps.containsKey(b.progress())) {
                final boolean done = steps.get(b.progress());
                if (done) {
                    name = msg.raw("tutorial-done-prefix") + name;
                    glow = true;
                }
                extended.add("");
                extended.add(msg.raw(done ? "tutorial-done-lore" : "tutorial-todo-lore"));
                lore = extended;
            }
        }
        holder.set(slot,
            this.items.icon(b.material(), Messages.item(name, resolver), Messages.itemLines(lore, resolver),
                glow, b.viewerHead() ? player : null),
            b.action() instanceof MenuAction.None ? null : (p, click) -> {
                if (!this.canOpen(p, menu) || !this.visible(p, b, this.hasCountry(p))) {
                    p.sendMessage(this.messages.get().get("no-access"));
                    p.closeInventory();
                    return;
                }
                if (b.progress() != null) {
                    this.progress.clicked(p, b.progress());
                }
                if (b.action() instanceof MenuAction.Page(final int delta)) {
                    this.sound(p, this.settings.get().soundClick);
                    this.open(p, menu.id(), page + delta);
                } else {
                    this.run(p, menu, b.action(), b.keepOpen());
                }
            });
    }

    // ------------------------------------------------------------------ countries (type: countries)

    /**
     * One banner per country of the current page: colour = nearest dye of the country colour; NPC countries carry
     * a border + circle mark and "(NPC)". Click: {@code /kraj admin tp <kraj>} as the player (Kraje checks
     * {@code kraje.admin} again).
     */
    private void fillCountries(final MenuHolder holder, final MenuDef menu, final boolean available,
                               final Pages.Page<CountryList.Entry> page) {
        final Messages msg = this.messages.get();
        final List<Integer> slots = menu.contentSlots();
        if (slots.isEmpty()) {
            return;
        }
        if (!available || page.items().isEmpty()) {
            holder.set(slots.get(slots.size() / 2), this.items.icon("BARRIER",
                msg.get(available ? "countries-empty" : "countries-unavailable"), List.of(), false, null), null);
            return;
        }
        for (int i = 0; i < page.items().size() && i < slots.size(); i++) {
            final CountryList.Entry c = page.items().get(i);
            final DyeColor dye = CountryList.nearestDye(c.rgb());
            final TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("name", c.name()),
                Placeholder.styling("color", TextColor.color(c.rgb())),
                Placeholder.unparsed("hex", String.format(Locale.ROOT, "#%06X", c.rgb())),
                Placeholder.unparsed("ruler", c.ruler()),
                Placeholder.unparsed("chunks", CountryList.chunks(c.chunks())),
                Placeholder.unparsed("members", c.members() < 0 ? "?" : Integer.toString(c.members())),
                Placeholder.parsed("npc", c.npc() ? msg.raw("country-npc-suffix") : ""),
                Placeholder.unparsed("command", CountryList.TELEPORT_COMMAND + c.name()));
            final List<String> lore = msg.rawList(c.npc() ? "country-lore-npc" : "country-lore-player");
            final String name = c.name();
            holder.set(slots.get(i),
                this.items.banner(CountryList.bannerMaterial(dye), c.npc() ? CountryList.markColor(dye) : null,
                    Messages.item(msg.raw("country-name"), r), Messages.itemLines(lore, r)),
                (p, click) -> {
                    this.sound(p, this.settings.get().soundClick);
                    p.closeInventory();
                    p.performCommand(CountryList.TELEPORT_COMMAND + name);
                });
        }
    }

    // ------------------------------------------------------------------ interactions

    private void fillInteractions(final MenuHolder holder, final Player player, final MenuDef menu) {
        final Messages msg = this.messages.get();
        final List<InterakcjeHook.InteractionEntry> entries = new ArrayList<>();
        if (player.hasPermission(InterakcjeHook.PERM_USE)) {
            for (final InterakcjeHook.InteractionEntry e : InterakcjeHook.current()) {
                if (player.hasPermission(e.permission())) {
                    entries.add(e);
                }
            }
        }
        final List<Integer> slots = menu.contentSlots();
        if (entries.isEmpty() && !slots.isEmpty()) {
            holder.set(slots.get(slots.size() / 2), this.items.icon("BARRIER", msg.get("interactions-empty"),
                List.of(), false, null), null);
            return;
        }
        for (int i = 0; i < entries.size() && i < slots.size(); i++) {
            final InterakcjeHook.InteractionEntry e = entries.get(i);
            final TagResolver r = TagResolver.resolver(
                Placeholder.parsed("name", e.name()),
                Placeholder.parsed("description", e.description()),
                Placeholder.unparsed("command", e.label()),
                Placeholder.unparsed("range", format(e.range())),
                Placeholder.parsed("consent", msg.raw(e.consent() ? "consent-yes" : "consent-no")));
            final List<String> lore = msg.rawList(e.optionalTarget() ? "interaction-lore-optional" : "interaction-lore-required");
            holder.set(slots.get(i),
                this.items.icon(e.icon(), Messages.item(msg.raw("interaction-name"), r), Messages.itemLines(lore, r), false, null),
                (p, click) -> this.clickInteraction(p, menu, e, click));
        }
    }

    /** Dances and emotes from Interakcje (type: motions). */
    private void fillMotions(final MenuHolder holder, final Player player, final MenuDef menu) {
        final Messages msg = this.messages.get();
        final List<InterakcjeHook.MotionEntry> entries = new ArrayList<>();
        for (final InterakcjeHook.MotionEntry e : InterakcjeHook.motions()) {
            if (player.hasPermission(e.permission())) {
                entries.add(e);
            }
        }
        final List<Integer> slots = menu.contentSlots();
        if (entries.isEmpty() && !slots.isEmpty()) {
            holder.set(slots.get(slots.size() / 2), this.items.icon("BARRIER", msg.get("motions-empty"),
                List.of(), false, null), null);
            return;
        }
        for (int i = 0; i < entries.size() && i < slots.size(); i++) {
            final InterakcjeHook.MotionEntry e = entries.get(i);
            final TagResolver r = TagResolver.resolver(
                Placeholder.parsed("name", e.name()),
                Placeholder.parsed("description", e.description()),
                Placeholder.unparsed("command", e.command()),
                Placeholder.parsed("group", msg.raw(e.dance() ? "motion-group-dance" : "motion-group-emote")));
            final List<String> lore = msg.rawList(e.optionalTarget() ? "motion-lore-target" : "motion-lore");
            holder.set(slots.get(i),
                this.items.icon(e.icon(), Messages.item(msg.raw("motion-name"), r), Messages.itemLines(lore, r), false, null),
                (p, click) -> {
                    this.sound(p, this.settings.get().soundClick);
                    if (e.optionalTarget() && click.isRightClick()) {
                        this.openPicker(p, msg.get("picker-title-interaction", Placeholder.parsed("name", e.name())),
                            10.0, e.command(), menu.id());
                    } else {
                        p.closeInventory();
                        p.performCommand(e.command());
                    }
                });
        }
    }

    private void clickInteraction(final Player player, final MenuDef menu, final InterakcjeHook.InteractionEntry e,
                                  final ClickType click) {
        if (e.optionalTarget() && !click.isRightClick()) {
            player.closeInventory();
            player.performCommand(e.label());
            return;
        }
        final Component title = this.messages.get().get("picker-title-interaction", Placeholder.parsed("name", e.name()));
        this.openPicker(player, title, e.range(), e.label(), menu.id());
    }

    // ------------------------------------------------------------------ actions

    void run(final Player player, final MenuDef menu, final MenuAction action, final boolean keepOpen) {
        final Placeholders ph = this.placeholders.get();
        final Messages msg = this.messages.get();
        this.sound(player, this.settings.get().soundClick);
        switch (action) {
            case MenuAction.Command(final String command) -> {
                if (!keepOpen) {
                    player.closeInventory();
                }
                player.performCommand(ph.apply(command, player));
                if (keepOpen && player.getOpenInventory().getTopInventory().getHolder(false) instanceof MenuHolder) {
                    this.open(player, menu.id()); // refresh (e.g. a toggle)
                }
            }
            case MenuAction.OpenMenu(final String id) -> this.open(player, id);
            case MenuAction.OpenBook(final String id) -> {
                player.closeInventory();
                switch (this.books.openBook(player, id)) {
                    case OPENED -> { }
                    case NO_ACCESS -> player.sendMessage(msg.get("no-access"));
                    case UNKNOWN -> player.sendMessage(msg.get("unknown-book", Placeholder.unparsed("book", id)));
                }
            }
            case MenuAction.Url(final String rawUrl) -> {
                player.closeInventory();
                final String url = ph.apply(rawUrl, player);
                if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    player.sendMessage(msg.get("url-missing"));
                    return;
                }
                final Component link = msg.get("url-link", Placeholder.unparsed("url", url))
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(msg.get("url-hover")));
                player.sendMessage(msg.get("url-line", Placeholder.component("link", link)));
            }
            case MenuAction.Suggest(final String rawText) -> {
                player.closeInventory();
                final String text = ph.apply(rawText, player);
                final Component button = msg.get("suggest-button", Placeholder.unparsed("command", text.strip()))
                    .clickEvent(ClickEvent.suggestCommand(text))
                    .hoverEvent(HoverEvent.showText(msg.get("suggest-hover")));
                player.sendMessage(msg.get("suggest-line", Placeholder.component("button", button)));
            }
            case MenuAction.PickPlayer(final double range, final String command) -> this.openPicker(player,
                msg.get("picker-title-command", Placeholder.unparsed("command", command)), range,
                ph.apply(command, player), menu.id());
            // Paging needs the current page, so place() handles it; without one this opens the first page.
            case MenuAction.Page(final int delta) -> this.open(player, menu.id());
            case MenuAction.Help(final String category) -> this.helpOpener.accept(player, category);
            case MenuAction.Close() -> player.closeInventory();
            case MenuAction.None() -> {
            }
        }
    }

    // ------------------------------------------------------------------ player picker

    /**
     * Heads of the players within {@code range} (same world, visible, not spectators), nearest first.
     * Click runs {@code /<command> <nick>}. With nobody nearby the player gets a message and the current menu stays.
     */
    public void openPicker(final Player viewer, final Component title, final double range, final String command,
                           final String backMenu) {
        final Messages msg = this.messages.get();
        final List<Player> nearby = nearby(viewer, range);
        if (nearby.isEmpty()) {
            viewer.sendMessage(msg.get("nobody-nearby", Placeholder.unparsed("range", format(range))));
            this.sound(viewer, this.settings.get().soundError);
            return;
        }
        final int heads = Math.min(nearby.size(), MAX_PICKER_HEADS);
        final int rows = Math.min(6, (heads + 8) / 9 + 1);
        final MenuHolder holder = new MenuHolder("picker", rows * 9, title);
        for (int i = 0; i < heads; i++) {
            final Player target = nearby.get(i);
            final TagResolver r = TagResolver.resolver(
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("distance", format(viewer.getLocation().distance(target.getLocation()))),
                Placeholder.unparsed("command", command));
            final String name = target.getName();
            holder.set(i, this.items.icon("PLAYER_HEAD", Messages.item(msg.raw("picker-head-name"), r),
                    Messages.itemLines(msg.rawList("picker-head-lore"), r), false, target),
                (p, click) -> {
                    p.closeInventory();
                    p.performCommand(command + " " + name);
                });
        }
        final int nav = (rows - 1) * 9;
        for (int slot = nav; slot < nav + 9; slot++) {
            holder.set(slot, this.items.decoration("BLACK_STAINED_GLASS_PANE"), null);
        }
        final TagResolver none = TagResolver.empty();
        if (backMenu != null && this.menus.get().menu(backMenu) != null) {
            holder.set(nav + 3, this.items.icon("ARROW", Messages.item(msg.raw("back-name"), none), List.of(), false, null),
                (p, click) -> this.open(p, backMenu));
        }
        holder.set(nav + 5, this.items.icon("BARRIER", Messages.item(msg.raw("close-name"), none), List.of(), false, null),
            (p, click) -> p.closeInventory());
        viewer.openInventory(holder.getInventory());
    }

    static List<Player> nearby(final Player viewer, final double range) {
        final double max = range * range;
        return viewer.getWorld().getPlayers().stream()
            .filter(p -> !p.equals(viewer))
            .filter(viewer::canSee)
            .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
            .filter(p -> !vanished(p))
            .filter(p -> p.getLocation().distanceSquared(viewer.getLocation()) <= max)
            .sorted(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(viewer.getLocation())))
            .toList();
    }

    /** Vanish plugins (EssentialsX, SuperVanish, PremiumVanish) still use the legacy "vanished" metadata. */
    @SuppressWarnings("deprecation")
    private static boolean vanished(final Player p) {
        for (final MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    public void sound(final Player player, final String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(key.strip().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, 0.6f, 1.0f));
        } catch (final RuntimeException ignored) {
            // invalid key in config
        }
    }

    static String format(final double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    static List<Integer> borderSlots(final int rows) {
        final List<Integer> out = new ArrayList<>();
        for (int slot = 0; slot < rows * 9; slot++) {
            final int row = slot / 9;
            final int col = slot % 9;
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                out.add(slot);
            }
        }
        return out;
    }

    private static boolean isCorner(final int slot, final int rows) {
        final int row = slot / 9;
        final int col = slot % 9;
        return (row == 0 || row == rows - 1) && (col == 0 || col == 8);
    }

    /** Runs on the next tick (opening inventories inside click events is unsafe). */
    void later(final Runnable task) {
        Bukkit.getScheduler().runTask(this.plugin, task);
    }
}
