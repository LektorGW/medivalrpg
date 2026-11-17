// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Pure skin rules of NPCs (unit tested, no Bukkit).
 *
 * <ul>
 *   <li>{@code skin} holds a Mojang nick: the NPC wears that player's skin.</li>
 *   <li>No nick: the NPC gets an "appearance UUID", a random UUID stored in the record so it stays the same after
 *       restarts. It is sent as a profile with no textures, and the client then picks one of the 9 default skins
 *       from the UUID, so NPCs look different from each other.</li>
 * </ul>
 */
public final class NpcSkins {

    /** Command keyword for a new random default skin. */
    public static final String RANDOM = "losowy";

    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private NpcSkins() {
    }

    public static boolean isNick(final String skin) {
        return skin != null && NICK.matcher(skin).matches();
    }

    public static boolean isRandomKeyword(final String s) {
        return s != null && (s.equalsIgnoreCase(RANDOM) || s.equalsIgnoreCase("random") || s.equalsIgnoreCase("domyslny"));
    }

    /**
     * Appearance UUID the record should have: none while it wears a nick skin, otherwise the existing one, or a new
     * random one if it has none yet.
     */
    public static UUID appearance(final String skin, final UUID current, final Supplier<UUID> random) {
        if (isNick(skin)) {
            return current; // kept, so switching back to a default skin keeps the old look
        }
        return current != null ? current : random.get();
    }

    /**
     * Profile name for a default-skin NPC: at most 16 characters and starting with {@code #}, which a Mojang
     * account name cannot contain. A client can therefore never resolve it to a real player's skin.
     */
    public static String placeholderName(final UUID appearance) {
        return "#" + appearance.toString().replace("-", "").substring(0, 15).toLowerCase(Locale.ROOT);
    }
}
