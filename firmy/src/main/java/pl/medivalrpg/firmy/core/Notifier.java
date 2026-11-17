// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.core;

import java.util.UUID;

/** Messages to players (MiniMessage). Offline players get them queued until they join. */
public interface Notifier {

    void player(UUID player, String miniMessage);

    /** Ruler and advisors of a country. */
    void countryLeaders(UUID country, String miniMessage);

    /** Log line for administrators (console). {@code severe} for audit cases. */
    void admin(String text, boolean severe);

    Notifier NONE = new Notifier() {
        @Override
        public void player(final UUID player, final String miniMessage) {
        }

        @Override
        public void countryLeaders(final UUID country, final String miniMessage) {
        }

        @Override
        public void admin(final String text, final boolean severe) {
        }
    };
}
