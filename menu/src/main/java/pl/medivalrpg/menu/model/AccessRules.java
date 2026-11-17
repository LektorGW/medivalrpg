// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.model;

import java.util.Collection;
import java.util.function.Predicate;

/** Shared access checks for opening menus, showing buttons/books and revalidating clicks. */
public final class AccessRules {
    private AccessRules() { }

    public static boolean allows(final Collection<String> plugins, final String permission,
                                 final Predicate<String> enabled, final Predicate<String> permitted) {
        return plugins.stream().allMatch(enabled) && (permission == null || permitted.test(permission));
    }

    public static boolean visible(final ButtonDef button, final Predicate<String> enabled,
                                  final Predicate<String> permitted, final boolean hasCountry, final boolean canReturn) {
        return allows(button.requiresPlugins(), button.permission(), enabled, permitted) && switch (button.condition()) {
            case NONE -> true;
            case HAS_COUNTRY -> hasCountry;
            case NO_COUNTRY -> !hasCountry;
            case CAN_RETURN -> canReturn;
        };
    }
}
