// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.anim;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Plays model animations on players. The only implementation that does something is
 * {@link BetterModelBackend}; without BetterModel the {@link #NONE} backend makes every animation
 * fall back to the classic particle / sound effects.
 */
public interface AnimationBackend {

    /** How a clip is played. */
    record PlayOptions(boolean loop, Float fixedYaw, int blendInTicks, Predicate<UUID> viewer) {
    }

    /** A running animation on one player. */
    interface Handle {
        /** Ends the animation and shows the real player again. Idempotent. */
        void stop();
    }

    String name();

    /** Models are loaded and animations can be played right now. */
    boolean ready();

    boolean hasClip(String model, String clip);

    /**
     * Starts {@code clip} of limb model {@code model} on the player (any other animation of this
     * plugin on the player is stopped first).
     *
     * @param onEnd called once when a non-looping clip finishes by itself (any thread)
     * @return the handle, or {@code null} when the clip could not be started
     */
    Handle play(Player player, String model, String clip, PlayOptions options, Runnable onEnd);

    /**
     * Starts {@code clip} of limb model {@code model} on any living entity (an NPC Mannequin) with an
     * explicit skin, independent of player animations. Only players allowed by {@code viewer} see it.
     *
     * @param rawTextures base64 value of the Mojang {@code textures} property, or {@code null} for
     *                    BetterModel's fallback skin
     * @param onEnd       called once when a non-looping clip finishes by itself (any thread)
     * @return the handle, or {@code null} when the clip could not be started
     */
    default Handle playEntity(LivingEntity entity, String model, String clip, String rawTextures, String profileName,
                              boolean loop, Predicate<UUID> viewer, Runnable onEnd) {
        return null;
    }

    /** Plugin disable: stop everything. */
    void shutdown();

    AnimationBackend NONE = new AnimationBackend() {
        @Override
        public String name() {
            return "brak (efekty cząsteczkowe)";
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public boolean hasClip(String model, String clip) {
            return false;
        }

        @Override
        public Handle play(Player player, String model, String clip, PlayOptions options, Runnable onEnd) {
            return null;
        }

        @Override
        public void shutdown() {
            // nothing to stop
        }
    };
}
