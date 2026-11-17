// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Work animations of NPCs through the Interakcje plugin ({@code pl.medivalrpg.interakcje.api.WorkAnimations} in the
 * ServicesManager), by reflection: no compile dependency and no softdepend (the API is looked up lazily, at most every
 * 30 s while missing, so the load order does not matter). Without Interakcje, or with an older one without the API,
 * every call silently does nothing. See {@code interakcje/INTEGRATION-NPC.md}. Main thread only.
 */
public final class WorkAnim {

    private static final String API = "pl.medivalrpg.interakcje.api.WorkAnimations";
    private static Object provider;
    private static Method play;
    private static Method stop;
    private static long nextLookup;

    private WorkAnim() {
    }

    private static boolean ready() {
        if (provider != null && Bukkit.getPluginManager().isPluginEnabled("Interakcje")) {
            return true;
        }
        provider = null;
        final long now = System.currentTimeMillis();
        if (now < nextLookup) {
            return false;
        }
        nextLookup = now + 30_000L;
        try {
            for (final Class<?> k : Bukkit.getServicesManager().getKnownServices()) {
                if (k.getName().equals(API)) {
                    final Object p = Bukkit.getServicesManager().load(k);
                    if (p != null) {
                        play = k.getMethod("play", Entity.class, String.class, boolean.class);
                        stop = k.getMethod("stop", Entity.class);
                        provider = p;
                        return true;
                    }
                }
            }
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            // older Interakcje without the API
        }
        return false;
    }

    /**
     * The NPC does this ({@code loop} = work state stored on the entity; false = one-off gesture).
     *
     * @return true when Interakcje accepted it (false: Interakcje missing, or it refused — retry later)
     */
    public static boolean play(final Entity npc, final String work, final boolean loop) {
        if (npc == null || work == null || !ready()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(play.invoke(provider, npc, work, loop));
        } catch (final ReflectiveOperationException | RuntimeException e) {
            provider = null;
            return false;
        }
    }

    /** The NPC stops working (also forgets the stored state). */
    public static void stop(final Entity npc) {
        if (npc != null && ready()) {
            try {
                stop.invoke(provider, npc);
            } catch (final ReflectiveOperationException | RuntimeException e) {
                provider = null;
            }
        }
    }
}
