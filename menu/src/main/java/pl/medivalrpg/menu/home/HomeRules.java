// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.home;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Pure rules of player homes: names, limits and where a home may be (Kraje). */
public final class HomeRules {

    public static final String DEFAULT_NAME = "dom";
    public static final int MAX_NAME_LENGTH = 16;
    /** Highest {@code menu.homes.<n>} checked. */
    public static final int MAX_PERMISSION_LIMIT = 100;
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final String PERM_UNLIMITED = "menu.homes.unlimited";
    public static final String PERM_LIMIT_PREFIX = "menu.homes.";

    /** Same characters Brigadier accepts in an unquoted word (no Polish letters, no spaces). */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1," + MAX_NAME_LENGTH + "}");

    /** Who owns the land of a home relative to its owner. */
    public enum Land {
        /** No country owns the chunk. */
        WILD,
        /** The player's own country. */
        OWN,
        /** Another country (the player has none or a different one). */
        FOREIGN
    }

    private HomeRules() {
    }

    /** Normalised name ({@code null}/blank → "dom"), empty when invalid. */
    public static Optional<String> normalizeName(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(DEFAULT_NAME);
        }
        final String name = raw.strip().toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /**
     * Number of homes the player may have: {@code homes.max} from config, raised by the highest
     * {@code menu.homes.<n>} permission; {@link #UNLIMITED} with {@code menu.homes.unlimited} (op).
     */
    public static int limit(final int configMax, final Predicate<String> hasPermission) {
        if (hasPermission.test(PERM_UNLIMITED)) {
            return UNLIMITED;
        }
        int limit = Math.max(0, configMax);
        for (int n = MAX_PERMISSION_LIMIT; n > limit; n--) {
            if (hasPermission.test(PERM_LIMIT_PREFIX + n)) {
                limit = n;
                break;
            }
        }
        return limit;
    }

    /** Can the player save a home named {@code name}? Overwriting an existing one never counts against the limit. */
    public static boolean canSet(final int currentCount, final boolean exists, final int limit) {
        return exists || currentCount < limit;
    }

    /** Classifies land for the "own country or wild" rule. {@code landOwner == null} = wild. */
    public static Land land(final UUID landOwner, final UUID playerCountry) {
        if (landOwner == null) {
            return Land.WILD;
        }
        return Objects.equals(landOwner, playerCountry) ? Land.OWN : Land.FOREIGN;
    }

    /** With {@code homes.only-own-or-wild} only foreign land is forbidden (to set and to teleport to). */
    public static boolean allowed(final Land land, final boolean onlyOwnOrWild) {
        return !onlyOwnOrWild || land != Land.FOREIGN;
    }
}
