// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.medivalrpg.ludnosc.logic.BlueprintRules;
import pl.medivalrpg.ludnosc.model.Blueprint;
import pl.medivalrpg.ludnosc.template.BlockStates;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;
import pl.medivalrpg.ludnosc.template.Connections;
import pl.medivalrpg.ludnosc.template.FoundationMode;
import pl.medivalrpg.ludnosc.template.TemplateParser;

/**
 * Blueprint file format = a normal YAML template ({@link TemplateParser}: {@code palette} + {@code layers}, front on the
 * south, {@code .} air) plus a {@code blueprint:} section with the owner and the player-facing name. Pure (Bukkit's
 * YAML classes only), unit tested.
 */
public final class BlueprintCodec {

    /** Most distinct block states one blueprint may use (palette characters). */
    public static final int MAX_PALETTE = 4000;

    private BlueprintCodec() {
    }

    /**
     * Palette character number {@code n}: ASCII letters and digits, then Latin-1 / Latin Extended-A letters, then CJK
     * ideographs — never {@code .}, {@code _}, whitespace, line separators or YAML indicator characters.
     */
    static char paletteChar(final int n) {
        final String ascii = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        if (n < ascii.length()) {
            return ascii.charAt(n);
        }
        int k = n - ascii.length();
        for (char c = 'À'; c <= 'ſ'; c++) {
            if (c == '×' || c == '÷') {
                continue;
            }
            if (k-- == 0) {
                return c;
            }
        }
        if (k >= 0 && k < 20_000) {
            return (char) ('一' + k);
        }
        throw new IllegalArgumentException("Za dużo różnych bloków w kopii");
    }

    /** Display name of a blueprint template (names are validated, so they hold no MiniMessage tags). */
    public static String displayName(final String name) {
        return "<gold>" + name + "</gold>";
    }

    /** Metadata of a blueprint template; the function follows the beds (housing / neutral). */
    public static BuildingTemplate.Meta meta(final String id, final String name, final String author, final int beds,
                                             final long cost, final FoundationMode foundation, final String foundationBlock,
                                             final int groundLayer, final String source) {
        return new BuildingTemplate.Meta(id, displayName(name), "Kopia budowli gracza" + (author.isEmpty() ? "" : " " + author),
            BlueprintRules.function(beds), beds, cost, foundation, foundationBlock, groundLayer, source, 0,
            BlueprintRules.CATEGORY, "inner", 0, 0, 0L, 1, "");
    }

    /** Template of a fresh copy. */
    public static BuildingTemplate template(final UUID country, final String key, final String name, final String author,
                                            final BlueprintRules.Grid grid, final long cost) {
        final String id = BlueprintRules.templateId(country, key);
        // same connection pass as TemplateParser, so the saved copy equals the one read back after a restart
        final String[] cells = Connections.apply(grid.cells(), grid.width(), grid.height(), grid.depth());
        final BuildingTemplate counting = new BuildingTemplate(meta(id, name, author, 0, cost, FoundationMode.FILL,
            "cobblestone", 0, source(country, key)), grid.width(), grid.height(), grid.depth(), cells);
        return counting.withMeta(meta(id, name, author, counting.bedCount(), cost, FoundationMode.FILL, "cobblestone", 0,
            source(country, key)));
    }

    public static String source(final UUID country, final String key) {
        return "kopia:" + country + "/" + key;
    }

    /** YAML text of a blueprint (template keys + {@code blueprint:} section). */
    public static String encode(final Blueprint b) {
        final BuildingTemplate t = b.template();
        final YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl",
            "Kopia budowli gracza (/ludnosc kopia). Format jak szablony w budynki/: palette + layers, front na południu."));
        y.set("id", t.id());
        y.set("name", t.meta().displayName());
        y.set("description", t.meta().description());
        y.set("function", t.meta().function().name().toLowerCase(java.util.Locale.ROOT));
        y.set("beds", t.bedCount());
        y.set("cost", t.meta().cost());
        y.set("category", BlueprintRules.CATEGORY);
        y.set("foundation", t.meta().foundation().name().toLowerCase(java.util.Locale.ROOT));
        y.set("foundation-block", t.meta().foundationBlock());
        y.set("ground-layer", t.meta().groundLayer());
        y.set("blueprint.country", b.country().toString());
        y.set("blueprint.key", b.key());
        y.set("blueprint.name", b.name());
        y.set("blueprint.author", b.author());
        y.set("blueprint.author-id", b.authorId() == null ? null : b.authorId().toString());
        y.set("blueprint.created", b.createdAt());
        y.set("blueprint.skipped-forbidden", b.forbidden());
        y.set("blueprint.skipped-liquids", b.liquids());
        y.set("blueprint.removed", b.removed());
        final Map<String, Character> palette = new LinkedHashMap<>();
        for (final String s : t.distinctStates()) {
            if (!BlockStates.isAir(s)) {
                if (palette.size() >= MAX_PALETTE) {
                    throw new IllegalArgumentException("Za dużo różnych bloków w kopii (maks. " + MAX_PALETTE + ")");
                }
                palette.put(s, paletteChar(palette.size()));
            }
        }
        for (final Map.Entry<String, Character> e : palette.entrySet()) {
            y.set("palette." + e.getValue(), e.getKey());
        }
        final List<String> layers = new ArrayList<>();
        for (int ly = 0; ly < t.height(); ly++) {
            final StringBuilder sb = new StringBuilder();
            for (int z = 0; z < t.depth(); z++) {
                if (z > 0) {
                    sb.append('\n');
                }
                for (int x = 0; x < t.width(); x++) {
                    final String s = t.state(x, ly, z);
                    sb.append(s == null ? TemplateParser.KEEP_CHAR : BlockStates.isAir(s) ? TemplateParser.AIR_CHAR : palette.get(s));
                }
            }
            layers.add(sb.toString());
        }
        y.set("layers", layers);
        return y.saveToString();
    }

    /**
     * Parses a blueprint file. {@code expectedCountry}/{@code expectedKey} come from the folder / file name and must
     * match the content (a moved or hand-edited file is refused). Metadata is rebuilt from the rules (housing by beds,
     * category {@code kopia}, no epoch requirements); {@code cost} is the given current price.
     */
    public static Blueprint decode(final String text, final UUID expectedCountry, final String expectedKey, final long cost)
        throws InvalidConfigurationException {
        final YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        final ConfigurationSection bp = y.getConfigurationSection("blueprint");
        if (bp == null) {
            throw new InvalidConfigurationException("brak sekcji blueprint");
        }
        final UUID country;
        try {
            country = UUID.fromString(bp.getString("country", ""));
        } catch (final IllegalArgumentException e) {
            throw new InvalidConfigurationException("zły kraj kopii");
        }
        final String key = bp.getString("key", "");
        if (!BlueprintRules.validKey(key) || (expectedKey != null && !expectedKey.equals(key))
            || (expectedCountry != null && !expectedCountry.equals(country))) {
            throw new InvalidConfigurationException("plik kopii nie pasuje do folderu kraju / nazwy pliku");
        }
        final String name = bp.getString("name", "");
        final String problem = BlueprintRules.nameProblem(name);
        if (problem != null) {
            throw new InvalidConfigurationException("zła nazwa kopii: " + problem);
        }
        final String id = BlueprintRules.templateId(country, key);
        if (!id.equals(y.getString("id"))) {
            throw new InvalidConfigurationException("id szablonu kopii nie pasuje (" + y.getString("id") + " ≠ " + id + ")");
        }
        final BuildingTemplate parsed = TemplateParser.parse(y, id, source(country, key));
        final String author = bp.getString("author", "");
        final BuildingTemplate t = parsed.withMeta(meta(id, name, author, parsed.bedCount(), cost, parsed.meta().foundation(),
            parsed.meta().foundationBlock(), parsed.meta().groundLayer(), source(country, key)));
        UUID authorId = null;
        try {
            final String a = bp.getString("author-id");
            authorId = a == null || a.isBlank() ? null : UUID.fromString(a);
        } catch (final IllegalArgumentException ignored) {
            // optional
        }
        return new Blueprint(key, country, name, author, authorId, bp.getLong("created", 0L), bp.getInt("skipped-forbidden", 0),
            bp.getInt("skipped-liquids", 0), bp.getBoolean("removed", false), t);
    }
}
