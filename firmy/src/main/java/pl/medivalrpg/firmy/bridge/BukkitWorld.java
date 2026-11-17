// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import pl.medivalrpg.firmy.core.GameWorld;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.postep.api.PostepApi;

/** The game seen by the company rules: Kraje (required), Postep and Wladcy (optional, by service / reflection). */
public final class BukkitWorld implements GameWorld {

    private final KrajeApi kraje;

    public BukkitWorld(final KrajeApi kraje) {
        this.kraje = kraje;
    }

    public KrajeApi kraje() {
        return this.kraje;
    }

    private static Country of(final KrajInfo k) {
        return new Country(k.id(), k.name(), k.npc(), k.treasuryAccount() == null || k.treasuryAccount().isBlank()
            ? "country:" + k.id() : k.treasuryAccount());
    }

    @Override
    public Optional<Country> country(final UUID id) {
        return id == null ? Optional.empty() : this.kraje.getCountry(id).map(BukkitWorld::of);
    }

    @Override
    public Optional<Country> countryOf(final UUID player) {
        return this.kraje.getCountryOf(player).map(BukkitWorld::of);
    }

    @Override
    public int rank(final UUID country, final UUID player) {
        if (country == null || player == null) {
            return 0;
        }
        final Optional<KrajInfo> k = this.kraje.getCountryOf(player);
        if (k.isEmpty() || !k.get().id().equals(country)) {
            return 0;
        }
        final Rank r = k.get().rankOf(player);
        return r == null ? 0 : r.weight();
    }

    @Override
    public boolean atWar(final UUID a, final UUID b) {
        return a != null && b != null && !a.equals(b) && this.kraje.isAtWar(a, b);
    }

    @Override
    public long playMinutes(final UUID player) {
        try {
            final Player online = Bukkit.getPlayer(player);
            final OfflinePlayer p = online != null ? online : Bukkit.getOfflinePlayer(player);
            if (!p.hasPlayedBefore() && online == null) {
                return 0;
            }
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        } catch (final RuntimeException e) {
            return -1;
        }
    }

    @Override
    public long firstPlayed(final UUID player) {
        final Player online = Bukkit.getPlayer(player);
        final OfflinePlayer p = online != null ? online : Bukkit.getOfflinePlayer(player);
        return p.getFirstPlayed();
    }

    @Override
    public Optional<Integer> reputation(final UUID npcCountry, final UUID player) {
        final Plugin w = Bukkit.getPluginManager().getPlugin("Wladcy");
        if (w == null || !w.isEnabled()) {
            return Optional.empty();
        }
        try {
            final Method m = w.getClass().getMethod("firmyReputation", UUID.class, UUID.class);
            final Object v = m.invoke(w, npcCountry, player);
            return v instanceof Integer i ? Optional.of(i) : Optional.empty();
        } catch (final NoSuchMethodException e) {
            // older Wladcy: read the quest service directly (public method value(npc, player))
            try {
                final Field f = w.getClass().getDeclaredField("quests");
                f.setAccessible(true);
                final Object quests = f.get(w);
                if (quests == null) {
                    return Optional.empty();
                }
                final Object enabled = quests.getClass().getMethod("enabled").invoke(quests);
                if (!Boolean.TRUE.equals(enabled)) {
                    return Optional.empty();
                }
                final Object v = quests.getClass().getMethod("value", UUID.class, UUID.class).invoke(quests, npcCountry, player);
                return v instanceof Integer i ? Optional.of(i) : Optional.empty();
            } catch (final ReflectiveOperationException | RuntimeException x) {
                return Optional.empty();
            }
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public int era(final UUID country) {
        // Postep is only a softdepend: without the plugin its API classes are not on the class path at all, so the class
        // must not even be touched (the start screen asks for the era of every country). Without Postep the server is in
        // the Renaissance at most; manufactories are not blocked by a missing plugin.
        final Plugin postep = Bukkit.getPluginManager().getPlugin("Postep");
        if (postep == null || !postep.isEnabled()) {
            return 2;
        }
        try {
            final PostepApi p = Bukkit.getServicesManager().load(PostepApi.class);
            return p == null ? 2 : p.era(country);
        } catch (final LinkageError e) {
            return 2;
        } catch (final RuntimeException e) {
            return 1;
        }
    }

    @Override
    public String playerName(final UUID player) {
        if (player == null) {
            return "admin";
        }
        final Player online = Bukkit.getPlayer(player);
        if (online != null) {
            return online.getName();
        }
        final String n = Bukkit.getOfflinePlayer(player).getName();
        return n != null ? n : player.toString().substring(0, 8);
    }
}
