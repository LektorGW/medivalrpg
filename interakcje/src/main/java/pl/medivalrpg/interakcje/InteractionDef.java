// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One interaction loaded from {@code interactions.<id>} in config.yml. Pure data: particle, sound and
 * material names stay as strings and are resolved against the server registries at runtime.
 *
 * @param animation id of the character animation in the animation catalogue ({@code animations.json}),
 *                  or {@code null}: only the classic effects are played
 * @param animationNoTarget animation used when an optional-target interaction is done without a target
 *                  (self-coronation), or {@code null}: the same as {@code animation}
 * @param requires  who may be the subject: {@link Requirement#RULER} = the crowned / honoured player (the
 *                  target, or the sender without a target) must rule a country (Kraje)
 * @param grantTitle a knighting: the target receives a knight title when the sender is a ruler
 * @param audienceBow players standing nearby bow to the subject at the impact moment
 */
public record InteractionDef(
        String id,
        boolean enabled,
        String name,
        String description,
        String command,
        List<String> aliases,
        String icon,
        int slot,
        boolean consent,
        boolean targetOptional,
        double range,
        long cooldownMs,
        Set<String> exemptGamemodes,
        String requestText,
        String broadcast,
        String broadcastNoTarget,
        Effects effects,
        HoldSpec hold,
        String animation,
        String animationNoTarget,
        Requirement requires,
        boolean grantTitle,
        boolean audienceBow) {

    /** The interaction without the ceremony extras (tests, older configs). */
    public InteractionDef(String id, boolean enabled, String name, String description, String command,
                          List<String> aliases, String icon, int slot, boolean consent, boolean targetOptional,
                          double range, long cooldownMs, Set<String> exemptGamemodes, String requestText,
                          String broadcast, String broadcastNoTarget, Effects effects, HoldSpec hold, String animation) {
        this(id, enabled, name, description, command, aliases, icon, slot, consent, targetOptional, range, cooldownMs,
                exemptGamemodes, requestText, broadcast, broadcastNoTarget, effects, hold, animation, null,
                Requirement.NONE, false, false);
    }

    /** Who must the subject of the interaction be. */
    public enum Requirement {
        NONE, RULER
    }

    /** Animation for this call: without a target the {@code animation-no-target} one, if set. */
    public String animationFor(boolean hasTarget) {
        return !hasTarget && animationNoTarget != null ? animationNoTarget : animation;
    }

    public String permission() {
        return "interakcje." + id;
    }

    public boolean isExempt(String gameModeName) {
        return exemptGamemodes.contains(gameModeName.toUpperCase(Locale.ROOT));
    }

    /** Who swings their arm. */
    public enum Swing {
        NONE, SENDER, TARGET, BOTH;

        public boolean sender() {
            return this == SENDER || this == BOTH;
        }

        public boolean target() {
            return this == TARGET || this == BOTH;
        }
    }

    /** Where a particle effect is spawned. */
    public enum Anchor {
        SENDER, TARGET, BOTH, BETWEEN
    }

    public record ParticleSpec(String type, Anchor at, double height, int count, double spread) {
    }

    public record SoundSpec(String sound, float volume, float pitch) {
    }

    public record Effects(
            Swing swing,
            boolean faceEachOther,
            boolean nod,
            double knockback,
            double knockbackUp,
            List<ParticleSpec> particles,
            List<SoundSpec> sounds) {

        public static final Effects NONE = new Effects(Swing.NONE, false, false, 0, 0, List.of(), List.of());
    }

    /** Lasting interaction (holding hands): ends after {@code durationSec}, on distance or on release. */
    public record HoldSpec(int durationSec, double maxDistance, int intervalTicks, List<ParticleSpec> particles) {
    }
}
