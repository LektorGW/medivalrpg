// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.List;
import java.util.Locale;
import java.util.function.ToIntBiFunction;
import pl.medivalrpg.kolej.network.TrackCell;

/**
 * Visual/earthwork style of a NEW track project. The style is chosen once, when the survey produces the block plan;
 * the plan (and later the persisted job entries) never changes afterwards. Existing jobs, the completed base and the
 * hash-pinned mainline commission are therefore unaffected by this choice.
 */
public enum TrackStyle {
    /** Historical generator: {@link Factory#segment} with the given settings, byte-identical to earlier releases. */
    CLASSIC("klasyczny"),
    /** 19th-century steam railway: sleepers, ballast, toe, ditches, cutting steps, embankment slopes, masonry viaducts. */
    REALISTIC("realistyczny");

    private final String polish;

    TrackStyle(final String polish) {
        this.polish = polish;
    }

    public String polish() {
        return this.polish;
    }

    public static TrackStyle parse(final String value) {
        final String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (final TrackStyle style : values()) {
            if (style.polish.equals(key)) {
                return style;
            }
        }
        throw new IllegalArgumentException("Styl toru: realistyczny albo klasyczny.");
    }

    public List<BlockOp> segment(final List<TrackCell> cells, final ToIntBiFunction<Integer, Integer> groundTop,
                                 final ToIntBiFunction<Integer, Integer> seabed, final ToIntBiFunction<Integer, Integer> water,
                                 final CellWorks.Settings settings) {
        return this == CLASSIC
            ? Factory.segment(cells, groundTop, seabed, water, settings)
            : RealisticTrack.segment(cells, groundTop, seabed, water, settings);
    }
}
