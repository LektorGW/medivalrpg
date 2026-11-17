// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.ai;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Narrow reflective bridge to Kraje for background sieges (no change of the frozen shared API): the public methods
 * {@code KrajePlugin#abstractSiegeBegin/abstractSiegeConquer/abstractRevolt}. Results are {@code OK…} or
 * {@code DENIED:<reason>}; an older Kraje without these methods answers {@code DENIED:unsupported}. Main thread only.
 */
public final class KrajeBridge {

    private final Logger logger;
    private Plugin cachedFor;
    private Method begin;
    private Method conquer;
    private Method revolt;
    private boolean warned;

    public KrajeBridge(final Logger logger) {
        this.logger = logger;
    }

    private boolean resolve() {
        final Plugin kraje = Bukkit.getPluginManager().getPlugin("Kraje");
        if (kraje == null || !kraje.isEnabled()) {
            return false;
        }
        if (kraje != this.cachedFor) {
            this.cachedFor = kraje;
            try {
                final Class<?> c = kraje.getClass();
                this.begin = c.getMethod("abstractSiegeBegin", String.class, String.class, int.class, int.class, boolean.class, boolean.class);
                this.conquer = c.getMethod("abstractSiegeConquer", String.class, String.class, int.class, int.class, boolean.class);
                this.revolt = c.getMethod("abstractRevolt", String.class, String.class, int.class, int.class, String.class);
            } catch (final NoSuchMethodException e) {
                this.begin = null;
                this.conquer = null;
                this.revolt = null;
                if (!this.warned) {
                    this.warned = true;
                    this.logger.warning("Kraje bez oblężeń w tle (abstractSiege*) — podbój NPC↔NPC poza zasięgiem graczy jest wyłączony. Zaktualizuj Kraje.");
                }
            }
        }
        return this.begin != null;
    }

    public boolean available() {
        return this.resolve();
    }

    private String call(final Method m, final Object... args) {
        if (!this.resolve() || m == null) {
            return "DENIED:unsupported";
        }
        try {
            final Object r = m.invoke(this.cachedFor, args);
            return r == null ? "DENIED:null" : r.toString();
        } catch (final InvocationTargetException e) {
            this.logger.log(Level.WARNING, "Kraje: błąd oblężenia w tle", e.getCause());
            return "DENIED:error";
        } catch (final IllegalAccessException | RuntimeException e) {
            this.logger.log(Level.WARNING, "Kraje: niedostępne oblężenie w tle", e);
            return "DENIED:error";
        }
    }

    public String begin(final UUID attacker, final String world, final int cx, final int cz, final boolean expedition, final boolean charge) {
        return this.call(this.begin, attacker.toString(), world, cx, cz, expedition, charge);
    }

    public String conquer(final UUID attacker, final String world, final int cx, final int cz, final boolean expedition) {
        return this.call(this.conquer, attacker.toString(), world, cx, cz, expedition);
    }

    public String revolt(final UUID holder, final String world, final int cx, final int cz, final UUID former) {
        return this.call(this.revolt, holder.toString(), world, cx, cz, former == null ? "" : former.toString());
    }

    /** Readable reason of a {@code DENIED:} answer. */
    public static String reason(final String result) {
        if (result == null) {
            return "?";
        }
        final String code = result.startsWith("DENIED:") ? result.substring(7) : result;
        return switch (code) {
            case "disabled" -> "oblężenia w tle wyłączone w Kraje";
            case "not-owned" -> "chunk nie należy do wroga";
            case "player-country" -> "ziemia kraju gracza (tylko walka fizyczna)";
            case "not-at-war" -> "brak wojny";
            case "siege-running" -> "trwa oblężenie fizyczne";
            case "chunk-busy" -> "chunk już oblegany";
            case "players-near" -> "gracze w pobliżu (walka fizyczna)";
            case "protected" -> "obszar chroniony";
            case "not-adjacent" -> "brak wspólnej granicy";
            case "not-border" -> "cel nie leży na skraju ziem wroga";
            case "bridgehead-limit" -> "limit przyczółków";
            case "splits-defender", "splits_defender" -> "rozcięłoby ziemie wroga";
            case "capital-protected", "capital_protected" -> "stolica (dopiero jako ostatni chunk)";
            case "funds" -> "brak pieniędzy w skarbcu";
            case "unsupported" -> "Kraje bez oblężeń w tle";
            default -> code;
        };
    }
}
