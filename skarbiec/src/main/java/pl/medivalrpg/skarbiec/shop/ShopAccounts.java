// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.shop;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Policies of shops that trade for another account than their builder's (Firmy registers {@code firma}). A policy is a
 * plain {@code Function<Map, Map>} registered by reflection, so Skarbiec has no compile-time dependency on Firmy and
 * Firmy none on Skarbiec internals. Without a policy (Firmy missing or disabled) such shops stand still and their
 * chests stay closed to everybody but the builder and admins: no money is ever routed by guesswork.
 *
 * <p>Requests ({@code op}): {@code access} {account, player, action: open|break} → {allow};
 * {@code trade} {account, player, type: SELL|BUY, world, x, y, z, price, amount, item} → {allow, reason, taxAccount, taxBp};
 * {@code traded} {account, player, playerName, type, gross, tax, amount, item} (notification);
 * {@code removed} {account, shop} (notification).</p>
 */
public final class ShopAccounts {

    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> policies = new ConcurrentHashMap<>();
    private final Logger log;

    public ShopAccounts(final Logger log) {
        this.log = log;
    }

    public static String namespace(final String account) {
        final int colon = account == null ? -1 : account.indexOf(':');
        return colon <= 0 ? "" : account.substring(0, colon);
    }

    public void register(final String namespace, final Function<Map<String, Object>, Map<String, Object>> policy) {
        this.policies.put(namespace, policy);
    }

    public void unregister(final String namespace) {
        this.policies.remove(namespace);
    }

    public boolean has(final String account) {
        return this.policies.containsKey(namespace(account));
    }

    /** Asks the policy; a missing policy or a failing one answers "no" with a reason. Never throws. */
    public Map<String, Object> ask(final String account, final String op, final Map<String, Object> args) {
        final Function<Map<String, Object>, Map<String, Object>> p = this.policies.get(namespace(account));
        if (p == null) {
            return Map.of("allow", false, "reason", "obsługa firm jest niedostępna");
        }
        final Map<String, Object> req = new HashMap<>(args);
        req.put("op", op);
        req.put("account", account);
        try {
            final Map<String, Object> r = p.apply(req);
            return r == null ? Map.of("allow", false, "reason", "brak odpowiedzi") : r;
        } catch (final RuntimeException | LinkageError e) {
            this.log.warning("Polityka sklepów " + namespace(account) + " zgłosiła błąd przy " + op + ": " + e);
            return Map.of("allow", false, "reason", "błąd obsługi firm");
        }
    }

    public static boolean allowed(final Map<String, Object> r) {
        return Boolean.TRUE.equals(r.get("allow"));
    }

    public static String reason(final Map<String, Object> r) {
        final Object o = r.get("reason");
        return o == null ? "" : o.toString();
    }

    public static long longOf(final Map<String, Object> r, final String key) {
        final Object o = r.get(key);
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
