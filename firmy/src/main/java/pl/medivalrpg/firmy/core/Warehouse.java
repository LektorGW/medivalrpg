// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import pl.medivalrpg.firmy.model.Firm;
import pl.medivalrpg.firmy.model.Role;

/** Protected warehouse chests of companies with a "who took what" log. Main thread. */
public final class Warehouse {

    private final Ctx ctx;
    /** chest key (world:x:y:z) → firm id. */
    private final Map<String, String> index = new HashMap<>();

    public Warehouse(final Ctx ctx) {
        this.ctx = ctx;
        this.reindex();
    }

    public void reindex() {
        this.index.clear();
        for (final Firm f : this.ctx.data.firms.values()) {
            for (final Firm.Chest c : f.warehouse) {
                this.index.put(c.key(), f.id);
            }
        }
    }

    public static String key(final String world, final int x, final int y, final int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public Optional<Firm> owner(final String key) {
        final String id = this.index.get(key);
        return id == null ? Optional.empty() : Optional.ofNullable(this.ctx.data.firms.get(id));
    }

    public boolean any() {
        return !this.index.isEmpty();
    }

    /** {@code /firma magazyn}: master or partner registers the chest they look at (on the seat country's land). */
    public Outcome add(final Firm f, final UUID actor, final String world, final int x, final int y, final int z,
                       final UUID chestCountry, final boolean otherProtection) {
        if (!f.active()) {
            return Outcome.fail("Firma jest zlikwidowana.");
        }
        if (!f.has(actor, Role.WSPOLNIK)) {
            return Outcome.fail("Magazynem zarządza mistrz albo wspólnik.");
        }
        final String key = key(world, x, y, z);
        if (this.index.containsKey(key)) {
            return Outcome.fail("Ta skrzynia już jest magazynem firmy.");
        }
        if (otherProtection) {
            return Outcome.fail("Ta skrzynia jest sklepem — sklep przypisuje się przez /firma sklep.");
        }
        if (chestCountry == null || !chestCountry.toString().equals(f.seatCountry)) {
            return Outcome.fail("Magazyn musi stać na ziemi kraju siedziby firmy.");
        }
        final int max = this.ctx.settings.maxChests.getOrDefault(f.type, 6);
        if (f.warehouse.size() >= max) {
            return Outcome.fail("Firma ma już najwięcej skrzyń magazynu (" + max + ").");
        }
        final String who = this.ctx.world.playerName(actor);
        f.warehouse.add(new Firm.Chest(world, x, y, z, this.ctx.now(), who));
        this.index.put(key, f.id);
        this.ctx.journal(f, "magazyn", "Nowa skrzynia magazynu " + x + " " + y + " " + z, 0, who);
        this.ctx.save();
        return Outcome.ok("Skrzynia " + x + " " + y + " " + z + " jest magazynem firmy „" + f.name + "” (" + f.warehouse.size() + "/" + max
            + "). Otwierają ją tylko ludzie firmy, a każde wyjęcie trafia do dziennika.");
    }

    public Outcome remove(final Firm f, final UUID actor, final boolean admin, final String key) {
        if (!admin && !f.has(actor, Role.WSPOLNIK)) {
            return Outcome.fail("Magazynem zarządza mistrz albo wspólnik.");
        }
        final boolean removed = f.warehouse.removeIf(c -> c.key().equals(key));
        if (!removed) {
            return Outcome.fail("Ta skrzynia nie jest magazynem tej firmy.");
        }
        this.index.remove(key);
        this.ctx.journal(f, "magazyn", "Usunięto skrzynię magazynu " + key, 0, admin ? "admin" : this.ctx.world.playerName(actor));
        this.ctx.save();
        return Outcome.ok("Skrzynia nie jest już magazynem firmy.");
    }

    /** Releases every chest of a company (after the former master's grace period). */
    public void release(final Firm f) {
        for (final Firm.Chest c : f.warehouse) {
            this.index.remove(c.key());
        }
        f.warehouse.clear();
    }

    /** Records item changes made by a player in a warehouse chest (delta per item description). */
    public void log(final Firm f, final String chestKey, final String player, final Map<String, Integer> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        final long now = this.ctx.now();
        for (final var e : deltas.entrySet()) {
            if (e.getValue() != 0) {
                f.chestLog.add(new Firm.ChestLog(now, player, e.getKey(), e.getValue(), chestKey));
            }
        }
        final int max = this.ctx.settings.chestLogSize;
        if (f.chestLog.size() > max) {
            f.chestLog.subList(0, f.chestLog.size() - max).clear();
        }
        this.ctx.save();
    }

    public List<Firm.ChestLog> recent(final Firm f, final int limit) {
        final int from = Math.max(0, f.chestLog.size() - limit);
        return List.copyOf(f.chestLog.subList(from, f.chestLog.size()));
    }
}
