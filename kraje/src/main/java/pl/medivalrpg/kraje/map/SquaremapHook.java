// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.map;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.geometry.ChunkPolygons;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.ProtectedArea;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.siege.SiegeManager;
import pl.medivalrpg.kraje.util.Format;
import xyz.jpenilla.squaremap.api.BukkitAdapter;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;
import xyz.jpenilla.squaremap.api.marker.Icon;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;
import xyz.jpenilla.squaremap.api.marker.MultiPolygon;
import xyz.jpenilla.squaremap.api.marker.Rectangle;

/**
 * squaremap layer "Kraje": one MultiPolygon marker per country and world (merged chunk outlines with
 * holes) plus a capital icon; and layer "Oblężenia" with a ⚔ icon and a red frame on every sieged chunk.
 *
 * <p>Threading: on Paper (non-Folia) squaremap reads layer providers from a main-thread repeating task
 * ({@code MarkerDataPublisher}), and its per-world layer registry map is a plain static {@code HashMap}
 * populated lazily. All calls here therefore run on the main thread; marker rebuilding is debounced
 * so a burst of claims causes a single redraw.</p>
 */
public final class SquaremapHook implements MapHook {

    private static final Key LAYER_KEY = Key.of("kraje_countries");
    private static final Key SIEGE_LAYER_KEY = Key.of("kraje_sieges");
    private static final Key SETTLEMENT_LAYER_KEY = Key.of("kraje_settlements");
    private static final Key ICON_KEY = Key.of("kraje_capital");
    private static final Key SIEGE_ICON_KEY = Key.of("kraje_siege");
    private static final long MAX_DEBOUNCE_MILLIS = 10_000L;

    private final KrajePlugin plugin;
    private final Squaremap api;
    private final Map<WorldIdentifier, SimpleLayerProvider> providers = new HashMap<>();
    private final Map<WorldIdentifier, SimpleLayerProvider> siegeProviders = new HashMap<>();
    private final Map<WorldIdentifier, SimpleLayerProvider> settlementProviders = new HashMap<>();
    private BukkitTask pending;
    private BukkitTask settlementRefresh;
    private long firstRequestAt;
    private boolean iconRegistered;
    private boolean siegeIconRegistered;
    private final MapExport export;

    public SquaremapHook(final KrajePlugin plugin) {
        this.plugin = plugin;
        this.export = MapExport.create(plugin);
        final Squaremap service = Bukkit.getServicesManager().load(Squaremap.class);
        this.api = service != null ? service : SquaremapProvider.get();
        this.settlementRefresh = Bukkit.getScheduler().runTaskTimer(this.plugin, this::refreshSettlements, 40L, 600L);
        try {
            if (!this.api.iconRegistry().hasEntry(ICON_KEY)) {
                this.api.iconRegistry().register(ICON_KEY, capitalIcon());
                this.iconRegistered = true;
            }
            if (!this.api.iconRegistry().hasEntry(SIEGE_ICON_KEY)) {
                this.api.iconRegistry().register(SIEGE_ICON_KEY, siegeIcon());
                this.siegeIconRegistered = true;
            }
        } catch (final RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Nie udało się zarejestrować ikon w squaremap", e);
        }
    }

    @Override
    public void scheduleRefresh() {
        final long now = System.currentTimeMillis();
        if (this.pending != null) {
            if (now - this.firstRequestAt > MAX_DEBOUNCE_MILLIS) {
                return; // keep the pending task so constant changes cannot postpone the redraw forever
            }
            this.pending.cancel();
        } else {
            this.firstRequestAt = now;
        }
        this.pending = Bukkit.getScheduler().runTaskLater(this.plugin, this::refreshNow, this.plugin.cfg().mapRefreshDebounceTicks);
    }

    private void refreshNow() {
        this.pending = null;
        if (!this.plugin.isEnabled()) {
            return;
        }
        try {
            for (final World world : Bukkit.getWorlds()) {
                final WorldIdentifier id = BukkitAdapter.worldIdentifier(world);
                final Optional<MapWorld> mapWorld = this.api.getWorldIfEnabled(id);
                if (mapWorld.isEmpty()) {
                    continue;
                }
                final SimpleLayerProvider provider = this.countryProvider(id, mapWorld.get());
                provider.clearMarkers();
                final SimpleLayerProvider settlements = this.provider(id, mapWorld.get(), SETTLEMENT_LAYER_KEY,
                    this.settlementProviders, "Królestwa — osady i trakty", true, this.plugin.cfg().mapLayerPriority + 1,
                    this.plugin.cfg().mapLayerZIndex + 1);
                settlements.clearMarkers();
                for (final Country country : this.plugin.manager().countries()) {
                    this.addMarkers(provider, world.getName(), country);
                    this.addSettlement(settlements, world.getName(), country);
                }
                for (final ProtectedArea area : this.plugin.protectedAreas().all()) {
                    if (area.world().equals(world.getName())) {
                        this.addProtectedArea(provider, area);
                    }
                }
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błąd podczas odświeżania mapy krajów", e);
        }
        this.refreshSieges();
        if (this.export != null) {
            try {
                this.export.export();
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Błąd eksportu kraje.json", e);
            }
        }
    }

    @Override
    public void refreshSieges() {
        if (!this.plugin.isEnabled() && this.plugin.sieges() == null) {
            return;
        }
        try {
            final KrajeConfig cfg = this.plugin.cfg();
            for (final World world : Bukkit.getWorlds()) {
                final WorldIdentifier id = BukkitAdapter.worldIdentifier(world);
                final Optional<MapWorld> mapWorld = this.api.getWorldIfEnabled(id);
                if (mapWorld.isEmpty()) {
                    continue;
                }
                final SimpleLayerProvider provider = this.provider(id, mapWorld.get(), SIEGE_LAYER_KEY, this.siegeProviders,
                    cfg.mapSiegeLayerName, false, cfg.mapLayerPriority + 1, cfg.mapLayerZIndex + 1);
                provider.clearMarkers();
                if (this.plugin.sieges() == null) {
                    continue;
                }
                for (final SiegeManager.Siege siege : this.plugin.sieges().sieges()) {
                    if (siege.pos().world().equals(world.getName())) {
                        this.addSiege(provider, siege);
                    }
                }
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błąd podczas odświeżania oblężeń na mapie", e);
        }
    }

    private void addSiege(final SimpleLayerProvider provider, final SiegeManager.Siege siege) {
        final Country attacker = this.plugin.manager().get(siege.attacker());
        final Country defender = this.plugin.manager().get(siege.defender());
        if (attacker == null || defender == null) {
            return;
        }
        final ChunkPos pos = siege.pos();
        final String tooltip = this.plugin.cfg().mapSiegeTooltip
            .replace("{attacker}", "<span style=\"color:" + attacker.hexColor() + "\">" + Format.html(attacker.name()) + "</span>")
            .replace("{defender}", "<span style=\"color:" + defender.hexColor() + "\">" + Format.html(defender.name()) + "</span>")
            .replace("{progress}", Integer.toString((int) Math.floor(siege.progress() * 100)))
            .replace("{time}", Format.clock(siege.endsAt() - System.currentTimeMillis()))
            .replace("{x}", Integer.toString(pos.centerBlockX()))
            .replace("{z}", Integer.toString(pos.centerBlockZ()));
        final Rectangle frame = Marker.rectangle(Point.of(pos.x() * 16.0, pos.z() * 16.0), Point.of(pos.x() * 16.0 + 16, pos.z() * 16.0 + 16));
        frame.markerOptions(MarkerOptions.builder()
            .stroke(true).strokeColor(new Color(0xC0392B)).strokeWeight(3).strokeOpacity(1.0)
            .fill(true).fillColor(new Color(0xC0392B)).fillOpacity(0.25)
            .hoverTooltip(tooltip).clickTooltip(tooltip));
        provider.addMarker(siegeKey("frame", pos), frame);
        if (this.api.iconRegistry().hasEntry(SIEGE_ICON_KEY)) {
            final Icon icon = Marker.icon(Point.of(pos.centerBlockX(), pos.centerBlockZ()), SIEGE_ICON_KEY, 20);
            icon.markerOptions(MarkerOptions.builder().hoverTooltip(tooltip).clickTooltip(tooltip));
            provider.addMarker(siegeKey("icon", pos), icon);
        }
    }

    /** Grey rectangle over every protected chunk (area + buffer), labelled "Spawn" for the id {@code spawn}. */
    private void addProtectedArea(final SimpleLayerProvider provider, final ProtectedArea area) {
        final String tooltip = "<b>" + Format.html(area.label()) + "</b><br>Ziemia neutralna — nie można jej zająć";
        final Rectangle rect = Marker.rectangle(
            Point.of(area.minChunkX() * 16.0, area.minChunkZ() * 16.0),
            Point.of((area.maxChunkX() + 1) * 16.0, (area.maxChunkZ() + 1) * 16.0));
        final Color grey = new Color(0x808080);
        rect.markerOptions(MarkerOptions.builder()
            .stroke(true).strokeColor(grey).strokeWeight(2).strokeOpacity(0.9)
            .fill(true).fillColor(grey).fillOpacity(0.3)
            .hoverTooltip(tooltip).clickTooltip(tooltip));
        provider.addMarker(Key.of("kraje_protected_" + sanitize(area.id())), rect);
    }

    private SimpleLayerProvider countryProvider(final WorldIdentifier id, final MapWorld mapWorld) {
        final KrajeConfig cfg = this.plugin.cfg();
        return this.provider(id, mapWorld, LAYER_KEY, this.providers, cfg.mapLayerName, cfg.mapLayerDefaultHidden,
            cfg.mapLayerPriority, cfg.mapLayerZIndex);
    }

    private void addSettlement(final SimpleLayerProvider provider, final String world, final Country country) {
        SettlementMapData.read(country.id()).ifPresent(summary -> {
            int index = 0;
            final Color countryColor = Color.decode(country.hexColor());
            for (final SettlementMapData.Road road : summary.roads()) {
                if (!world.equals(road.world())) continue;
                final var marker = Marker.polyline(road.points());
                final String tooltip = (road.planned() ? "Planowany trakt" : "Droga / trakt") + " — " + Format.html(country.name());
                marker.markerOptions(MarkerOptions.builder().stroke(true)
                    .strokeColor(road.planned() ? new Color(0xD9A441) : countryColor)
                    .strokeWeight(road.planned() ? 2 : 3).strokeOpacity(road.planned() ? 0.5 : 0.8)
                    .hoverTooltip(tooltip).clickTooltip(tooltip));
                provider.addMarker(Key.of("kingdom_road_" + country.id() + "_" + index++), marker);
            }
            int centreIndex = 0;
            for (final SettlementMapData.Centre centre : summary.centres()) {
                if (!world.equals(centre.world())) continue;
                final String tooltip = "<b>" + (centre.capital() ? "Stolica" : "Osada " + centreIndex) + " — "
                    + Format.html(country.name()) + "</b><br>" + Format.html(summary.name()) + ", poziom " + summary.level()
                    + "<br>" + centre.x() + ", " + centre.z();
                final var marker = Marker.icon(Point.of(centre.x() + 0.5, centre.z() + 0.5), ICON_KEY,
                    centre.capital() ? 24 : 16);
                marker.markerOptions(MarkerOptions.builder().hoverTooltip(tooltip).clickTooltip(tooltip));
                provider.addMarker(Key.of("kingdom_centre_" + country.id() + "_" + centreIndex++), marker);
            }
            // A provider predating kingdomMap still gets an explicit capital marker.
            if (summary.centres().isEmpty() && world.equals(country.capital().world())) {
                final var capital = Marker.icon(Point.of(country.capital().centerBlockX(), country.capital().centerBlockZ()), ICON_KEY, 24);
                capital.markerOptions(MarkerOptions.builder().hoverTooltip("Stolica — " + Format.html(country.name())));
                provider.addMarker(Key.of("kingdom_capital_" + country.id()), capital);
            }
            for (final SettlementMapData.Place place : summary.places()) {
                if (!world.equals(place.world())) continue;
                if (index >= 800) break;
                final String tooltip = "<b>" + Format.html(place.name()) + "</b><br>" + Format.html(country.name())
                    + " — " + Format.html(summary.name()) + " (poziom " + summary.level() + ")<br>"
                    + Format.html(place.function()) + (place.percent() < 100 ? " " + place.percent() + "%" : "")
                    + "<br>Ludność: " + summary.population() + ", mieszkania: " + summary.housing();
                final Rectangle marker = Marker.rectangle(Point.of(place.x() - 2, place.z() - 2),
                    Point.of(place.x() + 2, place.z() + 2));
                final Color color = place.percent() < 100 ? new Color(0xE0A63B) : Color.decode(country.hexColor());
                marker.markerOptions(MarkerOptions.builder().stroke(true).strokeColor(color).strokeWeight(2)
                    .fill(true).fillColor(color).fillOpacity(0.65).hoverTooltip(tooltip).clickTooltip(tooltip));
                provider.addMarker(Key.of("settlement_" + country.id() + "_" + index++), marker);
            }
        });
    }

    /** Buildings change independently of land claims, so their layer has its own slow refresh. */
    private void refreshSettlements() {
        if (!this.plugin.isEnabled()) return;
        try {
            for (final World world : Bukkit.getWorlds()) {
                final WorldIdentifier id = BukkitAdapter.worldIdentifier(world);
                final Optional<MapWorld> mapWorld = this.api.getWorldIfEnabled(id);
                if (mapWorld.isEmpty()) continue;
                final SimpleLayerProvider provider = this.provider(id, mapWorld.get(), SETTLEMENT_LAYER_KEY,
                    this.settlementProviders, "Królestwa — osady i trakty", true, this.plugin.cfg().mapLayerPriority + 1,
                    this.plugin.cfg().mapLayerZIndex + 1);
                provider.clearMarkers();
                for (final Country country : this.plugin.manager().countries()) this.addSettlement(provider, world.getName(), country);
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błąd odświeżania osad na mapie", e);
        }
    }

    private SimpleLayerProvider provider(final WorldIdentifier id, final MapWorld mapWorld, final Key key,
                                         final Map<WorldIdentifier, SimpleLayerProvider> cache, final String name,
                                         final boolean hidden, final int priority, final int zIndex) {
        final SimpleLayerProvider existing = cache.get(id);
        final boolean registered = mapWorld.layerRegistry().hasEntry(key);
        if (existing != null && registered && mapWorld.layerRegistry().get(key) == existing) {
            return existing;
        }
        if (registered) {
            // Stale layer (e.g. from a previous plugin instance) — replace it.
            mapWorld.layerRegistry().unregister(key);
        }
        final SimpleLayerProvider provider = SimpleLayerProvider.builder(name)
            .showControls(true)
            .defaultHidden(hidden)
            .layerPriority(priority)
            .zIndex(zIndex)
            .build();
        mapWorld.layerRegistry().register(key, provider);
        cache.put(id, provider);
        return provider;
    }

    private void addMarkers(final SimpleLayerProvider provider, final String world, final Country country) {
        final Set<Long> claims = country.claimsIn(world);
        if (claims.isEmpty()) {
            return;
        }
        final KrajeConfig cfg = this.plugin.cfg();
        final List<MultiPolygon.MultiPolygonPart> parts = new ArrayList<>();
        for (final ChunkPolygons.Part part : ChunkPolygons.fromKeys(claims)) {
            final List<List<Point>> holes = new ArrayList<>(part.holes().size());
            for (final List<ChunkPolygons.Vertex> hole : part.holes()) {
                holes.add(toPoints(hole));
            }
            parts.add(MultiPolygon.part(toPoints(part.outer()), holes));
        }
        final Color color = new Color(country.color());
        final String hover = this.fill(cfg.mapHoverTemplate, country, false);
        final String popup = this.fill(cfg.mapPopupTemplate, country, true);
        final MultiPolygon polygon = Marker.multiPolygon(parts);
        polygon.markerOptions(MarkerOptions.builder()
            .stroke(true)
            .strokeColor(color)
            .strokeWeight(cfg.mapStrokeWeight)
            .strokeOpacity(cfg.mapStrokeOpacity)
            .fill(true)
            .fillColor(color)
            .fillOpacity(cfg.mapFillOpacity)
            .fillRule(MarkerOptions.FillRule.EVENODD)
            .hoverTooltip(hover)
            .clickTooltip(popup));
        provider.addMarker(Key.of("kraje_country_" + country.id() + "_" + sanitize(world)), polygon);

        final ChunkPos capital = country.capital();
        if (cfg.mapShowCapitals && this.iconAvailable() && capital.world().equals(world)) {
            final SpawnPoint spawn = country.spawn();
            final boolean spawnInCapital = spawn.world().equals(world)
                && ((int) Math.floor(spawn.x())) >> 4 == capital.x() && ((int) Math.floor(spawn.z())) >> 4 == capital.z();
            final Point point = spawnInCapital
                ? Point.of(spawn.x(), spawn.z())
                : Point.of(capital.centerBlockX(), capital.centerBlockZ());
            final Icon icon = Marker.icon(point, ICON_KEY, 16);
            icon.markerOptions(MarkerOptions.builder()
                .hoverTooltip("Stolica: <b>" + Format.html(country.name()) + "</b>" + (country.npc() ? Format.html(cfg.mapNpcSuffix) : ""))
                .clickTooltip(popup));
            provider.addMarker(Key.of("kraje_capital_" + country.id() + "_" + sanitize(world)), icon);
        }
    }

    private boolean iconAvailable() {
        return this.api.iconRegistry().hasEntry(ICON_KEY);
    }

    private String fill(final String rawTemplate, final Country country, final boolean popup) {
        final KrajeConfig cfg = this.plugin.cfg();
        String template = rawTemplate;
        // Old server templates have no {npc}: mark NPC countries right after the name.
        if (!template.contains("{npc}")) {
            template = template.replace("{name}", "{name}{npc}");
        }
        if (popup) {
            // Country flag icon (flag/FlagMapIcons); old templates without {flag} get it at the very top.
            final pl.medivalrpg.kraje.flag.FlagService flags = this.plugin.flags();
            final String flag = flags == null ? "" : flags.mapIconHtml(country.id());
            if (!template.contains("{flag}") && !flag.isEmpty()) {
                template = "{flag}" + template;
            }
            template = template.replace("{flag}", flag);
            final String extra = this.popupExtra(country);
            if (template.contains("{extra}")) {
                template = template.replace("{extra}", extra);
            } else if (!extra.isEmpty()) {
                final int end = template.lastIndexOf("</div>");
                template = end >= 0 ? template.substring(0, end) + "<br>" + extra + template.substring(end) : template + "<br>" + extra;
            }
        } else {
            template = template.replace("{extra}", "").replace("{flag}", "");
        }
        final List<String> allies = new ArrayList<>();
        final List<String> wars = new ArrayList<>();
        for (final Map.Entry<UUID, Relation> entry : country.relationsMap().entrySet()) {
            final Country other = this.plugin.manager().get(entry.getKey());
            if (other == null) {
                continue;
            }
            final String label = "<span style=\"color:" + other.hexColor() + "\">" + Format.html(other.name()) + "</span>";
            if (entry.getValue() == Relation.ALLIANCE) {
                allies.add(label);
            } else if (entry.getValue() == Relation.WAR) {
                wars.add(label);
            }
        }
        final int chunks = country.claimCount();
        final List<String> advisors = country.membersWithRank(Rank.ADVISOR).stream().map(m -> Format.html(m.name())).toList();
        final OptionalInt population = this.plugin.integrations().population(country.id());
        final OptionalInt housing = this.plugin.integrations().housing(country.id());
        final OptionalInt soldiers = this.plugin.integrations().soldiers(country.id());
        final Country overlord = this.plugin.manager().get(country.overlord());
        final List<String> vassals = this.plugin.manager().vassalsOf(country.id()).stream()
            .map(v -> "<span style=\"color:" + v.hexColor() + "\">" + Format.html(v.name()) + "</span>").toList();
        final pl.medivalrpg.kraje.api.UnionInfo union = this.plugin.manager().unionInfo(country.id());
        final Country unionPartner = union == null ? null : this.plugin.manager().get(union.other(country.id()));
        final List<String> embassies = new ArrayList<>();
        for (final pl.medivalrpg.kraje.model.Embassy e : country.embassiesMap().values()) {
            final Country host = this.plugin.manager().get(e.host());
            if (host != null) {
                embassies.add("→ <span style=\"color:" + host.hexColor() + "\">" + Format.html(host.name()) + "</span>");
            }
        }
        for (final Country guest : this.plugin.manager().embassyGuestsIn(country.id())) {
            embassies.add("← <span style=\"color:" + guest.hexColor() + "\">" + Format.html(guest.name()) + "</span>");
        }
        final java.util.Optional<pl.medivalrpg.kraje.integration.Integrations.Era> era = template.contains("{era")
            || template.contains("{progress}") ? this.plugin.integrations().era(country.id()) : java.util.Optional.empty();
        return template
            .replace("{era_icon}", era.map(SquaremapHook::eraIconHtml).orElse(""))
            .replace("{era}", era.map(e -> eraIconHtml(e) + (e.icon().isEmpty() ? "" : " ") + span(e.color(), e.name())).orElse("—"))
            .replace("{progress}", era.map(e -> e.max() ? "maks." : Format.percent(e.progress())).orElse("—"))
            .replace("{overlord}", overlord == null ? "—"
                : "<span style=\"color:" + overlord.hexColor() + "\">" + Format.html(overlord.name()) + "</span>")
            .replace("{until}", country.vassalage() == null ? "—" : Format.dateTime(country.vassalage().until()))
            .replace("{vassals}", vassals.isEmpty() ? "—" : String.join(", ", vassals))
            .replace("{union}", unionPartner == null ? "—"
                : "<span style=\"color:" + unionPartner.hexColor() + "\">" + Format.html(unionPartner.name()) + "</span>")
            .replace("{monarch}", union == null ? "—" : Format.html(union.monarchName()))
            .replace("{embassies}", embassies.isEmpty() ? "—" : String.join(", ", embassies))
            .replace("{name}", Format.html(this.plugin.displayName(country)))
            .replace("{npc}", country.npc() ? Format.html(cfg.mapNpcSuffix) : "")
            .replace("{color}", country.hexColor())
            .replace("{description}", Format.html(country.description()))
            .replace("{ruler}", Format.html(country.rulerName()))
            .replace("{advisors}", advisors.isEmpty() ? "—" : String.join(", ", advisors))
            .replace("{members}", Integer.toString(country.memberCount()))
            .replace("{chunks}", Format.chunks(chunks))
            .replace("{blocks}", Format.number(chunks * 256L) + " bl²")
            .replace("{area}", Format.area(chunks))
            .replace("{capital}", country.capital().centerBlockX() + ", " + country.capital().centerBlockZ())
            .replace("{founded}", Format.date(country.foundedAt()))
            .replace("{allies}", allies.isEmpty() ? "—" : String.join(", ", allies))
            .replace("{wars}", wars.isEmpty() ? "—" : String.join(", ", wars))
            .replace("{population}", population.isPresent() ? Integer.toString(population.getAsInt()) : "—")
            .replace("{housing}", housing.isPresent() ? Integer.toString(housing.getAsInt()) : "—")
            .replace("{soldiers}", soldiers.isPresent() ? Integer.toString(soldiers.getAsInt()) : "—");
    }

    /** Extra popup lines (NPC, population, army, sieges) — only those with data. */
    private String popupExtra(final Country country) {
        final KrajeConfig cfg = this.plugin.cfg();
        final StringBuilder sb = new StringBuilder();
        if (country.npc()) {
            sb.append(cfg.mapPopupNpc);
        }
        if (this.plugin.integrations().postepPresent()) {
            sb.append(cfg.mapPopupEra);
        }
        if (this.plugin.integrations().population(country.id()).isPresent()) {
            sb.append(cfg.mapPopupPopulation);
        }
        if (this.plugin.integrations().soldiers(country.id()).isPresent()) {
            sb.append(cfg.mapPopupArmy);
        }
        if (country.overlord() != null && this.plugin.manager().get(country.overlord()) != null) {
            sb.append(cfg.mapPopupVassal);
        }
        if (!this.plugin.manager().vassalsOf(country.id()).isEmpty()) {
            sb.append(cfg.mapPopupVassals);
        }
        if (this.plugin.manager().unionPartnerId(country.id()) != null) {
            sb.append(cfg.mapPopupUnion);
        }
        if (!country.embassiesMap().isEmpty() || !this.plugin.manager().embassyGuestsIn(country.id()).isEmpty()) {
            sb.append(cfg.mapPopupEmbassies);
        }
        if (this.plugin.sieges() != null) {
            for (final SiegeManager.Siege s : this.plugin.sieges().sieges()) {
                if (s.attacker().equals(country.id()) || s.defender().equals(country.id())) {
                    sb.append(cfg.mapPopupSiege.replace("{x}", Integer.toString(s.pos().centerBlockX()))
                        .replace("{z}", Integer.toString(s.pos().centerBlockZ())));
                }
            }
        }
        return sb.toString();
    }

    private static String eraIconHtml(final pl.medivalrpg.kraje.integration.Integrations.Era era) {
        return era.icon().isEmpty() ? "" : span(era.color(), era.icon());
    }

    private static String span(final String color, final String text) {
        return color.isEmpty() ? Format.html(text) : "<span style=\"color:" + color + "\">" + Format.html(text) + "</span>";
    }

    private static List<Point> toPoints(final List<ChunkPolygons.Vertex> ring) {
        final List<Point> points = new ArrayList<>(ring.size());
        for (final ChunkPolygons.Vertex v : ring) {
            points.add(Point.of(v.x() * 16.0, v.z() * 16.0));
        }
        return points;
    }

    private static Key siegeKey(final String kind, final ChunkPos pos) {
        return Key.of("kraje_siege_" + kind + "_" + sanitize(pos.world()) + "_" + pos.x() + "_" + pos.z());
    }

    private static String sanitize(final String world) {
        return world.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    public void shutdown() {
        if (this.pending != null) {
            this.pending.cancel();
            this.pending = null;
        }
        this.unregisterAll(LAYER_KEY, this.providers);
        this.unregisterAll(SIEGE_LAYER_KEY, this.siegeProviders);
        this.unregisterAll(SETTLEMENT_LAYER_KEY, this.settlementProviders);
        if (this.settlementRefresh != null) this.settlementRefresh.cancel();
        if (this.export != null) {
            this.export.shutdown();
        }
        if (this.iconRegistered) {
            this.unregisterIcon(ICON_KEY);
            this.iconRegistered = false;
        }
        if (this.siegeIconRegistered) {
            this.unregisterIcon(SIEGE_ICON_KEY);
            this.siegeIconRegistered = false;
        }
    }

    private void unregisterAll(final Key key, final Map<WorldIdentifier, SimpleLayerProvider> cache) {
        for (final Map.Entry<WorldIdentifier, SimpleLayerProvider> entry : cache.entrySet()) {
            try {
                this.api.getWorldIfEnabled(entry.getKey()).ifPresent(mapWorld -> {
                    if (mapWorld.layerRegistry().hasEntry(key)) {
                        mapWorld.layerRegistry().unregister(key);
                    }
                });
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.FINE, "Nie udało się wyrejestrować warstwy", e);
            }
            entry.getValue().clearMarkers();
        }
        cache.clear();
    }

    private void unregisterIcon(final Key key) {
        try {
            if (this.api.iconRegistry().hasEntry(key)) {
                this.api.iconRegistry().unregister(key);
            }
        } catch (final RuntimeException ignored) {
            // squaremap may already be shutting down
        }
    }

    /** Draws a small golden crown (16x16) used as the capital marker. */
    private static BufferedImage capitalIcon() {
        final BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final Polygon crown = new Polygon(
                new int[] {1, 1, 4, 8, 12, 15, 15},
                new int[] {14, 4, 8, 2, 8, 4, 14}, 7);
            g.setColor(new Color(0xF2C230));
            g.fillPolygon(crown);
            g.setColor(new Color(0x5A3E00));
            g.setStroke(new BasicStroke(1.2f));
            g.drawPolygon(crown);
            g.setColor(new Color(0xC0392B));
            g.fillOval(6, 9, 4, 4);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Two crossed swords ⚔ (20x20) on a dark red disc, the siege marker. */
    private static BufferedImage siegeIcon() {
        final BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x8E1B2B));
            g.fillOval(0, 0, 20, 20);
            g.setColor(new Color(0xF5F5F5));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(5, 5, 15, 15);
            g.drawLine(15, 5, 5, 15);
            g.setColor(new Color(0xF2C230));
            g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(11, 15, 15, 11);
            g.drawLine(5, 11, 9, 15);
        } finally {
            g.dispose();
        }
        return image;
    }
}
