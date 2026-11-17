// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.gui;

import java.util.Map;
import org.bukkit.entity.Player;

/** Per-player state of the buttons with {@code progress:} (the tutorial steps). Main thread only. */
public interface StepProgress {

    /** Nothing is tracked: buttons with {@code progress:} look like ordinary buttons. */
    StepProgress NONE = new StepProgress() {
        @Override
        public Map<String, Boolean> states(final Player player) {
            return Map.of();
        }

        @Override
        public void clicked(final Player player, final String step) {
        }
    };

    /** Step id → done, for every step the player can see, in menu order. */
    Map<String, Boolean> states(Player player);

    /** The player clicked the button of a step (informational steps become done). */
    void clicked(Player player, String step);
}
