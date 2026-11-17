// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.display;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.postep.PostepPlugin;
import pl.medivalrpg.postep.config.PostepConfig;
import pl.medivalrpg.postep.model.Discovery;
import pl.medivalrpg.postep.model.EraDef;
import pl.medivalrpg.postep.service.ProgressService;
import pl.medivalrpg.postep.service.Snapshot;
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

/**
 * squaremap layer "Epoki": an era icon (coloured disc with 1–4 pips, drawn without fonts so it works on headless
 * servers) next to every capital, with a popup (era, progress, points, discoveries). Only instantiated when squaremap
 * is enabled; refreshed on the main thread.
 */
public final class SquaremapLayer {

    private static final Key LAYER = Key.of("postep_eras");

    private final PostepPlugin plugin;
    private final Squaremap api;
    private final Map<WorldIdentifier, SimpleLayerProvider> providers = new HashMap<>();
    private final Map<Integer, Key> icons = new HashMap<>();

    public SquaremapLayer(final PostepPlugin plugin) {
        this.plugin = plugin;
        final Squaremap service = Bukkit.getServicesManager().load(Squaremap.class);
        this.api = service != null ? service : SquaremapProvider.get();
        this.registerIcons();
    }

    private void registerIcons() {
        for (final EraDef era : this.plugin.service().cfg().eras) {
            final Key key = Key.of("postep_era_" + era.id());
            try {
                if (this.api.iconRegistry().hasEntry(key)) {
                    this.api.iconRegistry().unregister(key);
                }
                this.api.iconRegistry().register(key, icon(ProgressService.color(era.color()).value(), era.id()));
                this.icons.put(era.id(), key);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Nie udało się zarejestrować ikony epoki w squaremap", e);
            }
        }
    }

    /** 32×32 disc in the era colour with a white rim and {@code pips} white dots. */
    static BufferedImage icon(final int rgb, final int pips) {
        final BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 0, 0, 120));
        g.fillOval(2, 3, 28, 28);
        g.setColor(new Color(rgb));
        g.fillOval(1, 1, 28, 28);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(2, 2, 26, 26);
        final int n = Math.max(1, Math.min(4, pips));
        final int size = 6;
        final int gap = 2;
        final int total = n * size + (n - 1) * gap;
        int x = 15 - total / 2;
        for (int i = 0; i < n; i++) {
            g.fillOval(x, 12, size, size);
            x += size + gap;
        }
        g.dispose();
        return img;
    }

    public void shutdown() {
        for (final Map.Entry<WorldIdentifier, SimpleLayerProvider> e : this.providers.entrySet()) {
            try {
                this.api.getWorldIfEnabled(e.getKey()).ifPresent(w -> {
                    if (w.layerRegistry().hasEntry(LAYER)) {
                        w.layerRegistry().unregister(LAYER);
                    }
                });
            } catch (final RuntimeException ignored) {
                // squaremap shutting down
            }
        }
        this.providers.clear();
        for (final Key key : this.icons.values()) {
            try {
                if (this.api.iconRegistry().hasEntry(key)) {
                    this.api.iconRegistry().unregister(key);
                }
            } catch (final RuntimeException ignored) {
                // squaremap shutting down
            }
        }
    }

    /** Main thread. */
    public void refresh() {
        final PostepConfig cfg = this.plugin.service().cfg();
        if (!cfg.mapLayer) {
            return;
        }
        try {
            for (final World world : Bukkit.getWorlds()) {
                final WorldIdentifier id = BukkitAdapter.worldIdentifier(world);
                final java.util.Optional<MapWorld> mapWorld = this.api.getWorldIfEnabled(id);
                if (mapWorld.isEmpty()) {
                    continue;
                }
                final SimpleLayerProvider provider = this.provider(id, mapWorld.get(), cfg);
                provider.clearMarkers();
                for (final KrajInfo info : this.plugin.service().kraje().getCountries()) {
                    if (world.getName().equals(info.capitalWorld())) {
                        this.plugin.service().snapshot(info.id()).ifPresent(s -> this.addMarker(provider, info, s, cfg));
                    }
                }
            }
        } catch (final RuntimeException e) {
            this.plugin.getLogger().log(Level.WARNING, "Błąd odświeżania warstwy epok na mapie", e);
        }
    }

    private SimpleLayerProvider provider(final WorldIdentifier id, final MapWorld mapWorld, final PostepConfig cfg) {
        final SimpleLayerProvider existing = this.providers.get(id);
        final boolean registered = mapWorld.layerRegistry().hasEntry(LAYER);
        if (existing != null && registered && mapWorld.layerRegistry().get(LAYER) == existing) {
            return existing;
        }
        if (registered) {
            mapWorld.layerRegistry().unregister(LAYER);
        }
        final SimpleLayerProvider provider = SimpleLayerProvider.builder(cfg.mapLayerName)
            .showControls(true)
            .defaultHidden(cfg.mapLayerHidden)
            .layerPriority(cfg.mapLayerPriority)
            .zIndex(cfg.mapLayerZIndex)
            .build();
        mapWorld.layerRegistry().register(LAYER, provider);
        this.providers.put(id, provider);
        return provider;
    }

    private void addMarker(final SimpleLayerProvider provider, final KrajInfo info, final Snapshot s, final PostepConfig cfg) {
        final Key iconKey = this.icons.get(s.era());
        if (iconKey == null) {
            return;
        }
        final ProgressService service = this.plugin.service();
        final EraDef era = service.era(s.era());
        final Point point = Point.of(info.capitalX() * 16 + 8 + cfg.mapOffsetX, info.capitalZ() * 16 + 8 + cfg.mapOffsetZ);
        final Icon icon = Marker.icon(point, iconKey, cfg.mapIconSize);
        final String hover = "<b>" + html(info.name()) + "</b> — " + html(era.icon() + " " + era.name());
        final StringBuilder popup = new StringBuilder();
        popup.append("<b style=\"color:").append(html(info.hexColor())).append("\">").append(html(info.name())).append("</b>");
        if (info.npc()) {
            popup.append(" <i>(NPC)</i>");
        }
        popup.append("<br><span style=\"color:").append(html(era.color())).append("\">").append(html(era.icon() + " " + era.name()))
            .append("</span> <small>(").append(html(era.years())).append(")</small>");
        if (!s.check().atMaxEra()) {
            final EraDef next = service.era(s.era() + 1);
            popup.append("<br>Postęp do: ").append(html(next.name())).append(" — <b>")
                .append((int) Math.floor(s.check().progress() * 100)).append("%</b>");
        }
        popup.append("<br>Punkty badań: ").append(ProgressService.fmt(Math.floor(s.points())))
            .append(" (+").append(ProgressService.fmt(s.daily().total())).append("/dzień)");
        popup.append("<br>Odkrycia: ").append(s.discoveries().size()).append('/').append(service.tree().size());
        if (s.target() != null) {
            popup.append("<br>Badania: ").append(html(service.tree().get(s.target()).map(Discovery::name).orElse(s.target())));
        }
        icon.markerOptions(MarkerOptions.builder().hoverTooltip(hover).clickTooltip(popup.toString()));
        provider.addMarker(Key.of("postep_" + info.id()), icon);
    }

    static String html(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
