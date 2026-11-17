// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import pl.medivalrpg.armia.api.ArmiaApi;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.api.LudnoscApi;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.util.Json;
import pl.medivalrpg.zycie.api.ZycieApi;

/**
 * Mood of citizens (plugin Zycie, optional) in {@code country.get} and the NPC-country actions added on 2026-10-26:
 * {@code admin.npc.festival} (runs {@code zycie admin swieto <kraj>} from the console) and the forced builds of
 * Ludnosc ({@code admin.npc.forceRedesign}, {@code forceRoads}, {@code forceModernize}). Main thread.
 */
final class NpcExtraMethods {

    static final List<String> WRITES = List.of("admin.npc.festival", "admin.npc.forceRedesign", "admin.npc.forceRoads", "admin.npc.forceModernize");

    private final PanelPlugin plugin;

    NpcExtraMethods(final PanelPlugin plugin) {
        this.plugin = plugin;
    }

    Object invoke(final Call c) {
        return switch (c.method) {
            case "admin.npc.festival" -> this.festival(c);
            case "admin.npc.forceRedesign" -> this.forceRedesign(c);
            case "admin.npc.forceRoads" -> this.forceRoads(c);
            case "admin.npc.forceModernize" -> this.forceModernize(c);
            default -> throw new RpcException("Nieznana metoda: " + c.method);
        };
    }

    private static <T> T compat(final Supplier<T> call) {
        try {
            return call.get();
        } catch (final LinkageError e) {
            throw new RpcException("Zainstalowana wersja pluginu nie obsługuje tej funkcji — zaktualizuj ją.");
        }
    }

    private static <T> T quiet(final Supplier<T> call, final T fallback) {
        try {
            final T v = call.get();
            return v == null ? fallback : v;
        } catch (final LinkageError | RuntimeException e) {
            return fallback;
        }
    }

    private LudnoscApi lud() {
        final LudnoscApi l = this.plugin.ludnosc();
        if (l == null) {
            throw new RpcException("Plugin Ludnosc nie jest dostępny.");
        }
        return l;
    }

    private void aiLog(final Call c, final String category, final String text) {
        final ArmiaApi a = this.plugin.armia();
        if (a != null) {
            quiet(() -> {
                a.logNpcDecision(c.target.id(), category, "Panel (" + c.actor.name() + "): " + text);
                return Boolean.TRUE;
            }, Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------ mood (country.get, public)

    /** Polish label of a mood value 0..100. */
    static String moodLabel(final int v) {
        if (v >= 80) {
            return "Zachwyt";
        }
        if (v >= 65) {
            return "Zadowolenie";
        }
        if (v >= 50) {
            return "Spokój";
        }
        if (v >= 35) {
            return "Niepokój";
        }
        if (v >= 20) {
            return "Niezadowolenie";
        }
        return "Bunt";
    }

    /** {@code country.get.mood}: value, label, factors (largest effect first), concerns, multiplier, festival, unrest. */
    static Map<String, Object> moodJson(final int value, final Map<String, Integer> factors, final List<String> concerns,
                                        final double multiplier, final boolean festival, final boolean unrest) {
        final int v = Math.max(0, Math.min(100, value));
        final List<Map<String, Object>> f = new ArrayList<>();
        if (factors != null) {
            factors.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null && e.getValue() != 0)
                .sorted((a, b) -> Integer.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .forEach(e -> {
                    final String k = e.getKey();
                    final String label = k.isEmpty() ? k : (k.substring(0, 1).toUpperCase(Locale.ROOT) + k.substring(1)).replace('_', ' ').replace('-', ' ');
                    f.add(Json.obj("key", k, "label", label, "value", e.getValue()));
                });
        }
        final List<String> cs = new ArrayList<>();
        if (concerns != null) {
            for (final String s : concerns) {
                if (s != null && !s.isBlank()) {
                    cs.add(s.strip());
                }
            }
        }
        return Json.obj("value", v, "label", moodLabel(v), "factors", f, "concerns", cs,
            "multiplier", Math.round(multiplier * 100) / 100.0, "festival", festival, "unrest", unrest);
    }

    /** Mood part of {@code country.get}, or null without Zycie. */
    Map<String, Object> moodPart(final KrajInfo k) {
        final ZycieApi z = this.plugin.zycie();
        if (z == null) {
            return null;
        }
        final UUID id = k.id();
        try {
            return moodJson(z.mood(id), quiet(() -> z.moodFactors(id), Map.<String, Integer>of()), quiet(() -> z.concerns(id), List.<String>of()),
                quiet(() -> z.moodMultiplier(id), 1.0), quiet(() -> z.festival(id), false), quiet(() -> z.unrest(id), false));
        } catch (final LinkageError | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ festival

    /** Console command of Zycie; a country name with spaces is quoted. */
    static String festivalCommand(final String countryName) {
        final String n = countryName == null ? "" : countryName.replace("\"", "");
        return "zycie admin swieto " + (n.contains(" ") ? "\"" + n + "\"" : n);
    }

    private Object festival(final Call c) {
        if (Bukkit.getPluginManager().getPlugin("Zycie") == null) {
            throw new RpcException("Plugin Zycie nie jest dostępny.");
        }
        final ZycieApi z = this.plugin.zycie();
        if (z != null && quiet(() -> z.festival(c.target.id()), false)) {
            throw new RpcException("W " + c.target.name() + " właśnie trwa festyn.");
        }
        final boolean ran = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), festivalCommand(c.target.name()));
        if (!ran) {
            throw new RpcException("Komenda „/zycie admin swieto” nie zadziałała (sprawdź konsolę serwera).");
        }
        this.aiLog(c, "OTHER", "ogłoszono festyn");
        final boolean now = z != null && quiet(() -> z.festival(c.target.id()), false);
        return Json.obj("ok", true, "festival", now, "message", "Ogłoszono festyn w " + c.target.name() + ".");
    }

    // ------------------------------------------------------------------ forced builds

    private String failure(final LudnoscApi lud, final UUID id) {
        final Optional<String> why = quiet(() -> lud.lastPlanFailure(id), Optional.<String>empty());
        return why.filter(s -> !s.isBlank()).map(s -> " Powód: " + s + ".").orElse("");
    }

    private Object forceRedesign(final Call c) {
        final LudnoscApi lud = this.lud();
        final UUID id = c.target.id();
        final boolean free = c.params.has("free") && c.params.bool("free");
        final int orders = compat(() -> lud.forceRedesign(id, free));
        if (orders < 0) {
            throw new RpcException("Przebudowa miasta się nie udała (Ludnosc odmówiła albo nie obsługuje tej funkcji)." + this.failure(lud, id));
        }
        this.aiLog(c, "BUILD", "wymuszono przebudowę miasta (" + orders + " zleceń" + (free ? ", darmowo" : "") + ")");
        return Json.obj("ok", true, "orders", orders, "free", free,
            "message", orders == 0 ? "Przebudowa: nic do zmiany." + this.failure(lud, id) : "Zlecono przebudowę miasta: " + orders + " zleceń" + (free ? " (darmowo)" : "") + ".");
    }

    private Object forceRoads(final Call c) {
        final LudnoscApi lud = this.lud();
        final UUID id = c.target.id();
        if (!compat(() -> lud.forceRoads(id))) {
            throw new RpcException("Przebudowa dróg się nie udała (Ludnosc odmówiła albo nie obsługuje tej funkcji)." + this.failure(lud, id));
        }
        this.aiLog(c, "BUILD", "wymuszono przebudowę dróg pod epokę");
        return Json.obj("ok", true, "message", "Zlecono przebudowę dróg w stylu epoki.");
    }

    private Object forceModernize(final Call c) {
        final LudnoscApi lud = this.lud();
        final UUID id = c.target.id();
        final int orders = compat(() -> lud.forceModernize(id));
        if (orders < 0) {
            throw new RpcException("Modernizacja się nie udała." + this.failure(lud, id));
        }
        this.aiLog(c, "BUILD", "wymuszono modernizację budynków (" + orders + " zleceń)");
        return Json.obj("ok", true, "orders", orders, "message", orders == 0 ? "Wszystkie budynki są już w stylu epoki." : "Zlecono modernizację: " + orders + " budynków.");
    }
}
