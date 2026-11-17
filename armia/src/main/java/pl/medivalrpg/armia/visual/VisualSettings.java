// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/** {@code plugins/Armia/modele.yml}: a separate file so the main config of Armia is never touched. */
public record VisualSettings(boolean enabled, boolean install, boolean reloadBetterModel,
                             double shipShow, double shipHide, int shipLimit, boolean hideBoat,
                             ShipAnimator.Settings ship, double engineShow, double engineHide, int engineLimit,
                             boolean engineColliders, int colliderAfterTicks, boolean enginesNearPlayersOnly,
                             Map<String, List<String>> engineModels, String flagItemModel) {
    public static final VisualSettings DEFAULT = new VisualSettings(true, true, true, 96, 112, 24, true,
        ShipAnimator.Settings.DEFAULT, 64, 80, 96, true, 40, true, Map.of(), "");

    public static VisualSettings from(final ConfigurationSection c) {
        if (c == null) return DEFAULT;
        final Map<String, List<String>> engines = new HashMap<>();
        final ConfigurationSection models = c.getConfigurationSection("machiny.modele");
        if (models != null) for (final String unit : models.getKeys(false)) {
            final List<String> list = new ArrayList<>();
            for (final String model : models.getStringList(unit)) if (ModelCatalog.ALL.contains(model)) list.add(model);
            if (models.isString(unit) && ModelCatalog.ALL.contains(models.getString(unit))) list.add(models.getString(unit));
            engines.put(unit, List.copyOf(list));
        }
        final double shipShow = clamp(c.getDouble("statki.zasieg-widocznosci", 96), 16, 256);
        final double engineShow = clamp(c.getDouble("machiny.zasieg-widocznosci", 64), 16, 256);
        return new VisualSettings(c.getBoolean("wlaczone", true), c.getBoolean("instaluj-modele", true),
            c.getBoolean("przeladuj-bettermodel", true),
            shipShow, Math.max(shipShow, clamp(c.getDouble("statki.zasieg-ukrycia", 112), 16, 288)),
            (int) clamp(c.getInt("statki.limit-modeli", 24), 0, 64), c.getBoolean("statki.ukryj-lodz", true),
            new ShipAnimator.Settings((int) clamp(c.getInt("statki.zagle-rozwin-po-tickach", 5), 0, 200),
                (int) clamp(c.getInt("statki.zagle-zwin-po-tickach", 100), 1, 12000),
                (int) clamp(c.getInt("statki.kotwica-po-tickach", 200), 1, 12000), c.getBoolean("statki.kolysanie", true)),
            engineShow, Math.max(engineShow, clamp(c.getDouble("machiny.zasieg-ukrycia", 80), 16, 288)),
            (int) clamp(c.getInt("machiny.limit-modeli", 96), 0, 256), c.getBoolean("machiny.kolizja", true),
            (int) clamp(c.getInt("machiny.kolizja-po-tickach", 40), 1, 1200), c.getBoolean("machiny.tylko-przy-graczach", true),
            Map.copyOf(engines),
            c.getString("flagi.model-przedmiotu", ""));
    }

    /** Model variants for a unit type: modele.yml first, then the built-in mapping. */
    public List<String> engineModels(final String unitType) {
        final List<String> configured = this.engineModels.get(unitType);
        return configured != null ? configured : ModelCatalog.defaultEngineModels(unitType);
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }
}
