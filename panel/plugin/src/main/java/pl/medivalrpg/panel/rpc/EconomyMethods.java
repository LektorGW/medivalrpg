// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.panel.rpc;

import com.google.gson.JsonElement;
import java.util.UUID;
import java.util.function.Predicate;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.panel.PanelPlugin;
import pl.medivalrpg.panel.access.Access;
import pl.medivalrpg.panel.access.Actor;
import pl.medivalrpg.panel.economy.EconomyService;

/**
 * {@code country.economy {id, fresh?}} — read-only economy of one country (PANEL.md „Gospodarka kraju”).
 * Access is decided here, on the server, on EVERY call, before anything is read: the ruler and the advisors of that country,
 * a panel admin (any country), the personal manager WorldSteward (any country — Skarbiec confirms the UUID), and the manager of
 * that NPC country. A citizen, an outsider or the ruler of another country gets {@link #DENIED} and no data.
 */
final class EconomyMethods {

    static final String DENIED = "Brak dostępu do gospodarki tego kraju — widzą ją tylko władca i doradcy kraju.";
    static final String DISABLED = "Strona gospodarki jest wyłączona w konfiguracji Panelu (economy.enabled).";

    private final EconomyService service;

    EconomyMethods(final PanelPlugin plugin) {
        this.service = new EconomyService(plugin);
        try {
            this.service.start();
        } catch (final RuntimeException | LinkageError e) {
            // no running server (unit tests): the RPC still works, the sampler does not
        }
    }

    /**
     * Role of the viewer for this country, or null when refused. The owner check is a supplier because it asks Skarbiec by
     * reflection — it is only asked when nothing cheaper already decided.
     */
    static String access(final Actor a, final UUID target, final boolean targetNpc, final Predicate<UUID> owner) {
        if (a == null || target == null) {
            return null;
        }
        final Rank rank = a.rankIn(target);
        if (rank == Rank.RULER) {
            return "RULER";
        }
        if (rank == Rank.ADVISOR) {
            return "ADVISOR";
        }
        if (a.admin()) {
            return "ADMIN";
        }
        if (owner != null && owner.test(a.uuid())) {
            return "OWNER";
        }
        if (Access.canManageNpc(a, target, targetNpc)) {
            return "NPC_MANAGER";
        }
        return null;
    }

    Object invoke(final Call c) {
        if (c.target == null) {
            throw new RpcException("Nie ma takiego kraju.");
        }
        final String role = access(c.actor, c.target.id(), c.target.npc(), WorldMethods::manager);
        if (role == null) {
            throw new RpcException(DENIED);
        }
        final JsonElement f = c.params.element("fresh");
        final boolean fresh = f != null && f.isJsonPrimitive() && ("true".equals(f.getAsString()) || "1".equals(f.getAsString()));
        try {
            return this.service.report(c.target, role, fresh);
        } catch (final IllegalStateException e) {
            if ("disabled".equals(e.getMessage())) {
                throw new RpcException(DISABLED);
            }
            throw e;
        }
    }
}
