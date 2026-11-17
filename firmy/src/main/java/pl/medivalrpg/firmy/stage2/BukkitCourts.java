// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.stage2;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;

/**
 * NPC courts through Kraje, the shared quest budget and reputation through Wladcy (optional methods
 * {@code firmyOrderBudget}, {@code firmyOrderBooked}, {@code firmyOrderReputation} — see wladcy/FIRMY-PATCH.md), and
 * World Treasury loans through Skarbiec ({@code worldTreasury(owner, "world.allocate-firm-loan" | "world.return-firm-loan")}).
 */
public final class BukkitCourts implements Courts {

    private final KrajeApi kraje;

    public BukkitCourts(final KrajeApi kraje) {
        this.kraje = kraje;
    }

    @Override
    public List<Court> npcCountries() {
        return this.kraje.getCountries().stream().filter(KrajInfo::npc)
            .map(k -> new Court(k.id(), k.name(), k.treasuryAccount() == null || k.treasuryAccount().isBlank() ? "country:" + k.id()
                : k.treasuryAccount(), k.capitalWorld(), k.capitalX(), k.capitalZ())).toList();
    }

    private static Plugin wladcy() {
        final Plugin p = Bukkit.getPluginManager().getPlugin("Wladcy");
        return p != null && p.isEnabled() ? p : null;
    }

    private static Object call(final Plugin p, final String name, final Class<?>[] types, final Object... args) throws ReflectiveOperationException {
        final Method m = p.getClass().getMethod(name, types);
        return m.invoke(p, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Budget> wladcyBudget(final UUID npc) {
        final Plugin w = wladcy();
        if (w == null) {
            return Optional.empty();
        }
        try {
            final Object r = call(w, "firmyOrderBudget", new Class<?>[] {UUID.class}, npc);
            if (r instanceof Map<?, ?> m && m.get("perOrder") instanceof Number po && m.get("perDayLeft") instanceof Number pd) {
                return Optional.of(new Budget(po.longValue(), pd.longValue()));
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // older Wladcy: own limits of Firmy
        }
        return Optional.empty();
    }

    @Override
    public void booked(final UUID npc, final long amount) {
        final Plugin w = wladcy();
        if (w != null) {
            try {
                call(w, "firmyOrderBooked", new Class<?>[] {UUID.class, long.class}, npc, amount);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // own limit already counted
            }
        }
    }

    @Override
    public void reputation(final UUID npc, final UUID player, final String name, final int delta) {
        final Plugin w = wladcy();
        if (w != null) {
            try {
                call(w, "firmyOrderReputation", new Class<?>[] {UUID.class, UUID.class, String.class, int.class}, npc, player, name, delta);
            } catch (final ReflectiveOperationException | RuntimeException ignored) {
                // no reputation without the bridge
            }
        }
    }

    private static Optional<String> world(final UUID owner, final String method, final Map<String, String> args) {
        final Plugin s = Bukkit.getPluginManager().getPlugin("Skarbiec");
        if (s == null || !s.isEnabled()) {
            return Optional.of("Skarbiec jest niedostępny.");
        }
        try {
            s.getClass().getMethod("worldTreasury", UUID.class, String.class, Map.class).invoke(s, owner, method, args);
            return Optional.empty();
        } catch (final InvocationTargetException e) {
            return Optional.of(e.getCause() == null ? "odmowa" : String.valueOf(e.getCause().getMessage()));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return Optional.of("Skarbiec nie obsługuje kredytów firm (zaktualizuj Skarbiec).");
        }
    }

    @Override
    public Optional<String> allocateLoan(final UUID owner, final String escrow, final long amount, final String firmName) {
        return world(owner, "world.allocate-firm-loan", Map.of("account", escrow, "amount", Long.toString(amount), "firm", firmName));
    }

    @Override
    public Optional<String> returnLoan(final UUID owner, final String escrow, final long amount) {
        return world(owner, "world.return-firm-loan", Map.of("account", escrow, "amount", Long.toString(amount)));
    }
}
