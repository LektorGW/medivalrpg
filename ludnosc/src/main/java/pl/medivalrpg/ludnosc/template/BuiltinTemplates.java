// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** The built-in medieval templates shipped in the jar under {@code budynki/} (pure loading, used by tests too). */
public final class BuiltinTemplates {

    /**
     * Look variants ({@code variant-of} in the YAML) added after the base templates: new ids, so buildings already
     * standing keep comparing against their unchanged base template.
     */
    public static final List<String> VARIANT_IDS = List.of("chata_szachulec", "chata_zrebowa", "dom_szachulec", "dom_kamienny",
        "kamienica_szczytowa", "kamienica_podcieniowa", "kamienica_renesansowa_loggia", "karczma_zajazd", "kuznia_kamienna",
        "piekarnia_szachulec", "tartak_kryty", "spichlerz_kamienny", "stajnia_kamienna", "kosciol_drewniany", "wieza_okragla");

    /** Ids of the base templates (unchanged since before the variants). */
    public static final List<String> BASE_IDS = List.of("studnia", "chata", "dom", "spichlerz", "kuznia", "koszary", "wieza",
        "karczma", "mlyn", "stajnia", "mur", "pole", "targ", "kamienica", "ratusz", "kosciol", "brama", "piekarnia", "browar",
        "tartak", "kopalnia", "biblioteka", "zielarnia", "przystan", "cmentarz", "pomnik", "magazyn", "laznia", "latarnia", "rynek", "ogrody", "koszary2", "akademia", "skarbiec", "katedra",
        "arena", "most", "kamieniolom", "kamienica_renesansowa", "kamienica_czynszowa", "fabryka", "dworzec", "blok", "wiezowiec_biurowy",
        "wiezowiec", "uniwersytet", "drukarnia", "port", "bank", "obserwatorium", "ratusz_renesansowy", "palac_renesansowy", "szkola",
        "szpital", "most_stalowy", "elektrownia", "park", "galeria", "szpital_nowoczesny", "szkola_nowoczesna", "stadion", "parlament",
        "dworzec_nowoczesny", "parking", "laboratorium", "lotnisko", "zamek", "palac");

    /**
     * Workplaces of the village production chains (glassworks, brickworks, sheepfold), each base type followed by its
     * look variant ({@code variant-of}). Every one has exactly one chest (the workshop store, no barrels); the
     * sheepfolds have an open-air grass paddock with a fence and one gate.
     */
    public static final List<String> WORKSHOP_IDS = List.of("huta_szkla", "huta_szkla_murowana", "cegielnia", "cegielnia_kryta",
        "owczarnia", "owczarnia_kamienna");

    /** Ids (= file names without {@code .yml}) of every built-in template: base templates, the variants, the workshops. */
    public static final List<String> IDS = java.util.stream.Stream.of(BASE_IDS, VARIANT_IDS, WORKSHOP_IDS)
        .flatMap(List::stream).toList();

    private BuiltinTemplates() {
    }

    public static String resourcePath(final String id) {
        return "budynki/" + id + ".yml";
    }

    /** Parses one built-in template from the class path. */
    public static BuildingTemplate load(final String id) throws IOException {
        final String path = resourcePath(id);
        try (InputStream in = BuiltinTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Brak zasobu " + path);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
                return TemplateParser.parse(yaml, id, "wbudowany:" + id + ".yml");
            }
        }
    }

    public static List<BuildingTemplate> loadAll() throws IOException {
        final List<BuildingTemplate> out = new ArrayList<>();
        for (final String id : IDS) {
            out.add(load(id));
        }
        return out;
    }
}
