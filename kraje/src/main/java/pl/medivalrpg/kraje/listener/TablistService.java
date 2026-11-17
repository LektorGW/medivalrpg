// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.config.Messages;
import pl.medivalrpg.kraje.economy.Economy;
import pl.medivalrpg.kraje.integration.Integrations;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.tablist.ActivityTracker;
import pl.medivalrpg.kraje.tablist.ChangeTracker;
import pl.medivalrpg.kraje.tablist.TabFormat;
import pl.medivalrpg.kraje.tablist.TabLines;
import pl.medivalrpg.kraje.tablist.TablistNews;
import pl.medivalrpg.kraje.tablist.TablistOrder;
import pl.medivalrpg.kraje.tablist.TablistSettings;
import pl.medivalrpg.kraje.tablist.TtlCache;

/**
 * The single owner of the tab list: name in the list ({@code Player#playerListName}: country tag, rank, era, AFK),
 * grouping by country and rank ({@code Player#setPlayerListOrder}, higher values are listed first) and header /
 * footer. Does not use scoreboard teams and does not touch name tags.
 *
 * <p>{@code tablist.style: extended} (default, also for old server configs without the key) builds the header /
 * footer from the line lists in {@code tablist.extended} (server, players, ping, TPS, date and season, links, country,
 * rank, era, balance, latest news, rotating tip); {@code classic} keeps the old {@code tablist.header/footer/format}.
 * Data of other plugins (Postep era, Ludnosc season, Skarbiec balance, Nowosci) is optional and cached for
 * {@code cache-seconds}; a line whose data is missing disappears ({@link TabLines}).</p>
 *
 * <p>Packets are sent only when the content for the player changed ({@link ChangeTracker}); all state of a player
 * is dropped when they quit. Postep's era-icon decorator steps aside when the name format of the active style shows
 * the era itself ({@code <era>} / {@code <era_icon>}).</p>
 */
public final class TablistService implements Listener {

    private static final long NEWS_TTL_MILLIS = 30_000L;
    private static final String GLOBAL = "global";

    private record HeaderFooter(Component header, Component footer) {
    }

    private final KrajePlugin plugin;
    private final ActivityTracker activity = new ActivityTracker();
    private final ChangeTracker<Component> names = new ChangeTracker<>();
    private final ChangeTracker<HeaderFooter> headers = new ChangeTracker<>();
    private final Set<UUID> afkShown = new HashSet<>();
    private final TtlCache<UUID, Optional<Integrations.Era>> eras = new TtlCache<>(5_000L);
    private final TtlCache<UUID, String> countryNames = new TtlCache<>(5_000L);
    private final TtlCache<UUID, Long> balances = new TtlCache<>(5_000L);
    private final TtlCache<String, Optional<Integrations.Season>> seasons = new TtlCache<>(5_000L);
    private final TtlCache<String, Optional<String>> news = new TtlCache<>(NEWS_TTL_MILLIS);
    private final TablistNews newsSource;
    private BukkitTask periodic;
    private BukkitTask pendingFull;
    private boolean applied;

    public TablistService(final KrajePlugin plugin) {
        this.plugin = plugin;
        this.newsSource = new TablistNews(plugin.getLogger());
    }

    /** (Re)starts according to the current config. */
    public void start() {
        this.stopTasks();
        this.clearState();
        final KrajeConfig cfg = this.plugin.cfg();
        if (!cfg.tablistEnabled) {
            if (this.applied) {
                this.resetAll();
            }
            return;
        }
        final TablistSettings ts = cfg.tablist;
        for (final TtlCache<?, ?> cache : List.of(this.eras, this.countryNames, this.balances, this.seasons)) {
            cache.ttl(ts.cacheMillis);
        }
        final long now = System.currentTimeMillis();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (this.activity.idleMillis(player.getUniqueId(), now) < 0) {
                this.activity.touch(player.getUniqueId(), now);
            }
        }
        final long period = ts.extended ? ts.refreshTicks : cfg.tablistRefreshTicks;
        this.periodic = Bukkit.getScheduler().runTaskTimer(this.plugin, this::refreshAll, period, period);
        this.refreshAll();
    }

    public void shutdown() {
        this.stopTasks();
        if (this.applied) {
            this.resetAll();
        }
        this.clearState();
        this.activity.clear();
    }

    private void stopTasks() {
        if (this.periodic != null) {
            this.periodic.cancel();
            this.periodic = null;
        }
        if (this.pendingFull != null) {
            this.pendingFull.cancel();
            this.pendingFull = null;
        }
    }

    private void clearState() {
        this.names.clear();
        this.headers.clear();
        this.afkShown.clear();
        this.eras.clear();
        this.countryNames.clear();
        this.balances.clear();
        this.seasons.clear();
        this.news.clear();
    }

    /** Coalesces multiple changes in the same tick into one refresh on the next tick (country data changed). */
    public void scheduleRefresh() {
        if (!this.plugin.cfg().tablistEnabled || this.pendingFull != null || !this.plugin.isEnabled()) {
            return;
        }
        this.pendingFull = Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.pendingFull = null;
            // country, rank, colour or era may have changed: read them again right away
            this.eras.clear();
            this.countryNames.clear();
            this.refreshAll();
        });
    }

    /** Recomputes names, order, header and footer of everybody; sends only what changed. Main thread. */
    public void refreshAll() {
        final KrajeConfig cfg = this.plugin.cfg();
        if (!cfg.tablistEnabled) {
            return;
        }
        this.applied = true;
        final long now = System.currentTimeMillis();
        final Map<UUID, Integer> bases = TablistOrder.groupBases(this.plugin.manager().countries(), Country::id, Country::name);
        final Global global = this.global(cfg, now);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.applyName(player, bases, now);
            this.applyHeaderFooter(player, global, now);
        }
    }

    // ------------------------------------------------------------------ name in the list

    private void applyName(final Player player, final Map<UUID, Integer> bases, final long now) {
        final KrajeConfig cfg = this.plugin.cfg();
        final TablistSettings ts = cfg.tablist;
        final Messages messages = this.plugin.messages();
        final UUID id = player.getUniqueId();
        final boolean afk = this.activity.isAfk(id, now, ts.afkMillis);
        if (afk) {
            this.afkShown.add(id);
        } else {
            this.afkShown.remove(id);
        }
        final TagResolver afkTag = Placeholder.parsed("afk", afk ? ts.afkTag : "");
        final Country country = this.plugin.manager().countryOf(id);
        final Component name;
        final int order;
        if (country == null) {
            final String format = ts.extended ? ts.noCountryNameFormat : cfg.tablistNoCountryFormat;
            name = format.isEmpty() ? Component.empty() : messages.parse(format,
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("country", ""),
                Placeholder.styling("color", NamedTextColor.GRAY),
                Placeholder.unparsed("rank", ""),
                Placeholder.parsed("rank_icon", ""),
                Placeholder.parsed("era", ""),
                Placeholder.parsed("era_icon", ""),
                afkTag);
            order = 0;
        } else {
            final Rank rank = rankOf(country, id);
            final String format = ts.extended ? ts.nameFormat : cfg.tablistFormat;
            final Optional<Integrations.Era> era = needsEra(format) ? this.era(country, now) : Optional.empty();
            name = messages.parse(format,
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("country", this.countryName(country, now)),
                Placeholder.styling("color", country.textColor()),
                Placeholder.unparsed("rank", rank.displayName()),
                Placeholder.parsed("rank_icon", cfg.rankIcon(rank)),
                Placeholder.parsed("era", era.map(Integrations.Era::badge).orElse("")),
                Placeholder.parsed("era_icon", era.map(e -> e.iconMini().isEmpty() ? "" : e.iconMini() + " ").orElse("")),
                afkTag);
            order = TablistOrder.order(bases, country.id(), rank.weight());
        }
        if (this.names.update(id, name)) {
            // empty = vanilla name (no tag for players without a country when the format is empty)
            player.playerListName(name.equals(Component.empty()) ? null : name);
        }
        if (cfg.tablistSort && player.getPlayerListOrder() != order) {
            player.setPlayerListOrder(order);
        }
    }

    /** Does a tab-list format use an era placeholder ({@code <era>}, {@code <era_icon>})? Postep reads Kraje's config the same way. */
    public static boolean needsEra(final String format) {
        return format != null && (format.contains("<era>") || format.contains("<era_icon>"));
    }

    private static Rank rankOf(final Country country, final UUID player) {
        final Member member = country.member(player);
        return member == null ? Rank.CITIZEN : member.rank();
    }

    private Optional<Integrations.Era> era(final Country country, final long now) {
        final Integrations integrations = this.plugin.integrations();
        if (integrations == null) {
            return Optional.empty();
        }
        return this.eras.get(country.id(), now, () -> integrations.era(country.id()));
    }

    private String countryName(final Country country, final long now) {
        return this.countryNames.get(country.id(), now, () -> this.plugin.displayName(country));
    }

    // ------------------------------------------------------------------ header and footer

    /** Values shared by every player in one refresh. */
    private record Global(int online, int max, Component tps, String date, Optional<Integrations.Season> season,
                          Optional<String> news, String tip, int afkCount, Component mapLink, Component panelLink,
                          Component classicMapLink) {
    }

    private Global global(final KrajeConfig cfg, final long now) {
        final TablistSettings ts = cfg.tablist;
        final int online = Bukkit.getOnlinePlayers().size();
        final int max = Bukkit.getMaxPlayers();
        final Component classicMap = cfg.mapUrl.isEmpty() ? Component.empty()
            : Component.text(cfg.mapUrl).clickEvent(ClickEvent.openUrl(cfg.mapUrl));
        if (!ts.extended) {
            return new Global(online, max, Component.empty(), "", Optional.empty(), Optional.empty(), null, 0,
                Component.empty(), Component.empty(), classicMap);
        }
        final double tps = Bukkit.getTPS()[0];
        final Component tpsText = this.plugin.messages().parse(
            "<" + TabFormat.levelColor(TabFormat.tpsLevel(tps)) + ">" + TabFormat.tps(tps));
        final String date = ZonedDateTime.now(ts.zone).format(ts.dateFormat);
        final Integrations integrations = this.plugin.integrations();
        final Optional<Integrations.Season> season = integrations == null ? Optional.empty()
            : this.seasons.get(GLOBAL, now, integrations::season);
        final Optional<String> latest = ts.news
            ? this.news.get(GLOBAL, now, this.newsSource::latestTitle).map(t -> TabFormat.shorten(t, ts.newsMaxLength))
            : Optional.empty();
        final String tip = TabFormat.tip(ts.tips, now, ts.tipSeconds);
        int afk = 0;
        for (final Player p : Bukkit.getOnlinePlayers()) {
            if (this.activity.isAfk(p.getUniqueId(), now, ts.afkMillis)) {
                afk++;
            }
        }
        final Component map = link(cfg.mapUrl);
        final Component panel = link(ts.panelUrl);
        return new Global(online, max, tpsText, date, season, latest, tip, afk, map, panel, classicMap);
    }

    private static Component link(final String url) {
        return url.isEmpty() ? Component.empty()
            : Component.text(TabFormat.displayUrl(url)).clickEvent(ClickEvent.openUrl(url));
    }

    private void applyHeaderFooter(final Player player, final Global g, final long now) {
        final KrajeConfig cfg = this.plugin.cfg();
        final HeaderFooter hf = cfg.tablist.extended ? this.extended(player, g, now) : this.classic(player, g);
        if (this.headers.update(player.getUniqueId(), hf)) {
            player.sendPlayerListHeaderAndFooter(hf.header(), hf.footer());
        }
    }

    private HeaderFooter classic(final Player player, final Global g) {
        final KrajeConfig cfg = this.plugin.cfg();
        final Messages messages = this.plugin.messages();
        final Country country = this.plugin.manager().countryOf(player.getUniqueId());
        final Component countryName = country == null
            ? messages.get("wilderness-name")
            : Component.text(this.plugin.displayName(country), country.textColor());
        final TagResolver r = TagResolver.resolver(
            Placeholder.unparsed("online", Integer.toString(g.online())),
            Placeholder.unparsed("max", Integer.toString(g.max())),
            Placeholder.unparsed("player", player.getName()),
            Placeholder.component("country", countryName),
            Placeholder.component("map_link", g.classicMapLink()),
            Placeholder.unparsed("map_url", cfg.mapUrl));
        final Component header = messages.parse(cfg.tablistHeader, r);
        Component footer = messages.parse(cfg.tablistFooter, r);
        if (!cfg.mapUrl.isEmpty() && !cfg.tablistFooterMapLine.isEmpty()) {
            footer = footer.append(messages.parse(cfg.tablistFooterMapLine, r));
        }
        return new HeaderFooter(header, footer);
    }

    private HeaderFooter extended(final Player player, final Global g, final long now) {
        final KrajeConfig cfg = this.plugin.cfg();
        final TablistSettings ts = cfg.tablist;
        final Messages messages = this.plugin.messages();
        final UUID id = player.getUniqueId();
        final Set<String> missing = new HashSet<>();

        final Country country = this.plugin.manager().countryOf(id);
        final Component countryName;
        String rankName = "";
        String rankIcon = "";
        Optional<Integrations.Era> era = Optional.empty();
        if (country == null) {
            countryName = messages.get("wilderness-name");
            missing.add("rank");
            missing.add("rank_icon");
        } else {
            countryName = Component.text(this.countryName(country, now), country.textColor());
            final Rank rank = rankOf(country, id);
            rankName = rank.displayName();
            rankIcon = cfg.rankIcon(rank);
            era = this.era(country, now);
        }
        if (era.isEmpty()) {
            missing.add("era");
            missing.add("era_icon");
        }

        final Economy economy = this.plugin.economy();
        String balance = "";
        if (economy != null && economy.enabled()) {
            final long amount = this.balances.get(id, now, () -> economy.balance(Economy.playerAccount(id)));
            balance = economy.format(amount);
        } else {
            missing.add("balance");
        }

        final Integrations.Season season = g.season().orElse(null);
        if (season == null) {
            missing.addAll(List.of("season", "season_icon", "season_day", "season_days"));
        }
        if (g.news().isEmpty()) {
            missing.add("news");
        }
        if (g.tip() == null) {
            missing.add("tip");
        }
        if (g.afkCount() <= 0) {
            missing.add("afk_count");
        }
        if (cfg.mapUrl.isEmpty()) {
            missing.add("map_link");
            missing.add("map_url");
        }
        if (ts.panelUrl.isEmpty()) {
            missing.add("panel_link");
            missing.add("panel_url");
        }

        final int ping = player.getPing();
        final World world = player.getWorld();
        final Optional<Integrations.Era> eraView = era;
        final TagResolver r = TagResolver.resolver(
            Placeholder.unparsed("player", player.getName()),
            Placeholder.unparsed("online", Integer.toString(g.online())),
            Placeholder.unparsed("max", Integer.toString(g.max())),
            Placeholder.unparsed("afk_count", Integer.toString(g.afkCount())),
            Placeholder.component("ping", messages.parse(
                "<" + TabFormat.levelColor(TabFormat.pingLevel(ping)) + ">" + TabFormat.ping(ping))),
            Placeholder.component("tps", g.tps()),
            Placeholder.unparsed("date", g.date()),
            Placeholder.unparsed("game_time", TabFormat.gameTime(world.getTime())),
            Placeholder.unparsed("game_day", Long.toString(TabFormat.gameDay(world.getFullTime()))),
            Placeholder.unparsed("season", season == null ? "" : season.name()),
            Placeholder.parsed("season_icon", season == null ? "" : ts.seasonIcon(season.id())),
            Placeholder.unparsed("season_day", season == null ? "" : Integer.toString(season.day())),
            Placeholder.unparsed("season_days", season == null ? "" : Integer.toString(season.days())),
            Placeholder.component("country", countryName),
            Placeholder.unparsed("rank", rankName),
            Placeholder.parsed("rank_icon", rankIcon),
            Placeholder.parsed("era", eraView.map(Integrations.Era::badge).orElse("")),
            Placeholder.parsed("era_icon", eraView.map(Integrations.Era::iconMini).orElse("")),
            Placeholder.unparsed("balance", balance),
            Placeholder.unparsed("news", g.news().orElse("")),
            Placeholder.parsed("tip", g.tip() == null ? "" : g.tip()),
            Placeholder.component("map_link", g.mapLink()),
            Placeholder.unparsed("map_url", cfg.mapUrl),
            Placeholder.component("panel_link", g.panelLink()),
            Placeholder.unparsed("panel_url", ts.panelUrl));
        return new HeaderFooter(this.lines(ts.header, missing, r), this.lines(ts.footer, missing, r));
    }

    private Component lines(final List<String> raw, final Set<String> missing, final TagResolver r) {
        final List<String> kept = TabLines.filter(raw, missing);
        final List<Component> parsed = new ArrayList<>(kept.size());
        final Messages messages = this.plugin.messages();
        for (final String line : kept) {
            parsed.add(messages.parse(line, r));
        }
        return Component.join(JoinConfiguration.newlines(), parsed);
    }

    // ------------------------------------------------------------------ reset

    private void resetAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.playerListName(null);
            player.setPlayerListOrder(0);
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        this.applied = false;
        this.clearState();
    }

    // ------------------------------------------------------------------ activity (AFK) and lifecycle

    private void forget(final UUID id) {
        this.names.forget(id);
        this.headers.forget(id);
        this.afkShown.remove(id);
        this.balances.invalidate(id);
        this.activity.remove(id);
    }

    /** Activity of a player who was shown as AFK: drop the mark right away instead of on the next refresh. */
    private void active(final Player player) {
        final UUID id = player.getUniqueId();
        this.activity.touch(id, System.currentTimeMillis());
        if (this.afkShown.contains(id) && this.plugin.cfg().tablistEnabled) {
            this.scheduleRefresh();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        this.forget(id);
        this.activity.touch(id, System.currentTimeMillis());
        this.scheduleRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.forget(event.getPlayer().getUniqueId());
        this.scheduleRefresh();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (event.hasChangedBlock() || event.hasChangedOrientation()) {
            this.active(event.getPlayer());
        }
    }

    /** Asynchronous: only the thread-safe activity map is touched; the mark disappears with the next refresh. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(final AsyncChatEvent event) {
        this.activity.touch(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        this.active(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(final PlayerInteractEvent event) {
        this.active(event.getPlayer());
    }
}
