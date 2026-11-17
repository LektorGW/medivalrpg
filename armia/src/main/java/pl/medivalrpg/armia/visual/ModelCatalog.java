// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.visual;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Names shared by the generated {@code .bbmodel} files (tools/generate_models.py) and the code: model ids, bones,
 * animations and clip timings. {@code ModelAssetsTest} checks every constant against the bundled models.
 */
public final class ModelCatalog {
    public static final String KOGA = "armia_koga";
    public static final String KARAWELA = "armia_karawela";
    public static final String TREBUSZ = "armia_trebusz";
    public static final String KATAPULTA = "armia_katapulta";
    public static final String TARAN = "armia_taran";
    public static final String ARMATA = "armia_armata";
    public static final List<String> ALL = List.of(KOGA, KARAWELA, TREBUSZ, KATAPULTA, TARAN, ARMATA);

    // ship animations
    public static final String WAVES = "fala";
    public static final String SAILS_SET = "zagle_rozwin";
    public static final String SAILS_FURL = "zagle_zwin";
    public static final String SAILS_FURLED = "zagle_zwiniete";
    public static final String FLUTTER = "lopot";
    public static final String FLAGS = "flagi";
    public static final String ANCHOR_DROP = "kotwica_opusc";
    public static final String ANCHOR_RAISE = "kotwica_podnies";
    public static final String ANCHOR_DOWN = "kotwica_opuszczona";
    // ship bones
    public static final String RUDDER = "ster";
    public static final String RUDDER_TIP = "ster_koniec";
    public static final String ANCHOR = "kotwica";
    /** Bones whose white cloth is dyed with the country colour (prefix match on the bone name). */
    public static final List<String> TINTED = List.of("tarcze", "proporzec", "bandera", "herb");
    /** The stern ensign: target of a country flag image ({@link FlagArt}) or replaced by the Kraje banner. */
    public static final String ENSIGN = "bandera";

    /** Top of the stern ensign staff (blocks, model space: bow +Z, port +X), where a Kraje banner is hoisted. */
    public static double[] ensignHoist(final String shipModel) {
        if (KOGA.equals(shipModel)) return new double[] {0, (40 + 33 + 44) / 16.0, -(72 + 6) / 16.0};
        if (KARAWELA.equals(shipModel)) return new double[] {0, (48 + 33 + 44) / 16.0, -(88 + 6) / 16.0};
        return null;
    }
    /** Kraje banner scale matching the model ensign (cloth 2 x 1.25 blocks at scale 1). */
    public static final float ENSIGN_SCALE = 0.6f;

    // siege animations
    public static final String SHOT = "strzal";
    public static final String STRIKE = "uderzenie";
    public static final String RELOAD = "ladowanie";
    public static final String ROLL = "jazda";

    /** One engine model: clip lengths (s), release/impact moment inside the shot clip (s) and wheel radius (blocks). */
    public record Engine(String model, double shotLength, double release, double reloadLength, double wheelRadius,
                         double colliderSize, double colliderForward) {
        public String shotAnimation() { return TARAN.equals(this.model) ? STRIKE : SHOT; }
    }

    private static final Map<String, Engine> ENGINES = Map.of(
        TREBUSZ, new Engine(TREBUSZ, 1.0, 0.1, 4.0, 5 / 16.0, 1.5, 3.6),
        KATAPULTA, new Engine(KATAPULTA, 0.5, 0.08, 3.0, 7 / 16.0, 1.1, 2.5),
        TARAN, new Engine(TARAN, 1.0, 0.12, 2.0, 8 / 16.0, 1.5, 3.4),
        ARMATA, new Engine(ARMATA, 1.4, 0.0, 3.0, 13 / 16.0, 0.9, 3.0));

    private ModelCatalog() { }

    public static Engine engine(final String model) { return model == null ? null : ENGINES.get(model); }

    /** Fleet ship type (NavalRules id) to its model, or null. */
    public static String shipModel(final String shipType) {
        if ("transportowiec".equals(shipType)) return KOGA;
        if ("karawela".equals(shipType)) return KARAWELA;
        return null;
    }

    /** Built-in unit → engine models; modele.yml may override the list. */
    public static List<String> defaultEngineModels(final String unitType) {
        if (unitType == null) return List.of();
        return switch (unitType) {
            case "katapulta" -> List.of(KATAPULTA, TREBUSZ);
            case "armata", "artyleria" -> List.of(ARMATA);
            case "trebusz" -> List.of(TREBUSZ);
            case "taran" -> List.of(TARAN);
            default -> List.of();
        };
    }

    /** Stable per soldier: the same engine keeps its look across restarts and chunk loads. */
    public static String pick(final List<String> variants, final UUID id) {
        if (variants == null || variants.isEmpty()) return null;
        if (variants.size() == 1 || id == null) return variants.getFirst();
        final long mixed = id.getMostSignificantBits() * 31 + id.getLeastSignificantBits();
        return variants.get((int) Math.floorMod(mixed ^ (mixed >>> 29), (long) variants.size()));
    }

    public static boolean tinted(final String bone) {
        if (bone == null) return false;
        for (final String prefix : TINTED) if (bone.startsWith(prefix)) return true;
        return false;
    }
}
