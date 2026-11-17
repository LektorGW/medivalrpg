// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.listener;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;

/**
 * Addresses of a player known to the login plugin (Straznik), reached by reflection on its ServicesManager service
 * {@code pl.medivalrpg.straznik.api.StraznikApi}. Any method taking a UUID whose name speaks of an address
 * ({@code lastIp, registrationIp, addresses, knownAddresses, ...}) is used; it may return a String, an Optional or a
 * Collection. Without the plugin (or such a method) the join address from Paper is enough.
 */
public final class StraznikAddresses {

    private static final List<String> NAMES = List.of("lastIp", "lastAddress", "registrationIp", "registerIp", "addresses",
        "knownAddresses", "ipAddresses", "ips", "ipOf", "address");

    private StraznikAddresses() {
    }

    private static Object service() {
        for (final Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
            if (c.getName().equals("pl.medivalrpg.straznik.api.StraznikApi")) {
                final var reg = Bukkit.getServicesManager().getRegistration(c);
                return reg == null ? null : reg.getProvider();
            }
        }
        return null;
    }

    public static List<String> addresses(final UUID player) {
        final List<String> out = new ArrayList<>();
        try {
            final Object api = service();
            if (api == null) {
                return out;
            }
            for (final Method m : api.getClass().getMethods()) {
                if (!NAMES.contains(m.getName()) || m.getParameterCount() != 1 || m.getParameterTypes()[0] != UUID.class) {
                    continue;
                }
                add(out, m.invoke(api, player));
            }
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            // optional source only
        }
        return out;
    }

    private static void add(final List<String> out, final Object v) {
        if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        } else if (v instanceof Optional<?> o) {
            o.ifPresent(x -> add(out, x));
        } else if (v instanceof Collection<?> c) {
            c.forEach(x -> add(out, x));
        } else if (v instanceof java.net.InetAddress a) {
            out.add(a.getHostAddress());
        }
    }
}
