// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.menu.teleport;

/**
 * Countdown of one pending teleport: where the player stood when it started and when it ends. Pure (no server),
 * so the "moved too far" and "seconds left" rules are unit-tested.
 */
public final class Warmup {

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final double maxMoveSquared;
    private final long endsAt;

    /**
     * @param maxMove allowed distance (blocks) from the starting point; more cancels the teleport
     * @param startMs start time (epoch ms)
     * @param seconds countdown length
     */
    public Warmup(final String world, final double x, final double y, final double z, final double maxMove,
                  final long startMs, final int seconds) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        final double move = Math.max(0.0, maxMove);
        this.maxMoveSquared = move * move;
        this.endsAt = startMs + Math.max(0, seconds) * 1000L;
    }

    /** {@code true} when the player is in another world or further than {@code maxMove} from the start. */
    public boolean movedTooFar(final String world, final double x, final double y, final double z) {
        if (this.world == null ? world != null : !this.world.equals(world)) {
            return true;
        }
        final double dx = x - this.x;
        final double dy = y - this.y;
        final double dz = z - this.z;
        return dx * dx + dy * dy + dz * dz > this.maxMoveSquared;
    }

    /** Whole seconds left, rounded up (shown as "Teleport za 3…"); 0 when finished. */
    public int secondsLeft(final long nowMs) {
        final long left = this.endsAt - nowMs;
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    public boolean finished(final long nowMs) {
        return nowMs >= this.endsAt;
    }
}
