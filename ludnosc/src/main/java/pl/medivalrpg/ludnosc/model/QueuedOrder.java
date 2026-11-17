// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.List;
import java.util.UUID;

/**
 * A paid building order waiting in the country's construction queue (persisted in data.json). Its area is reserved:
 * no other site, building or queued order may overlap it. When a site slot frees up the first valid order becomes a
 * {@link ConstructionSite}; skipped or cancelled orders are refunded in full ({@link #cost}).
 */
public final class QueuedOrder {

    public UUID id;
    public UUID countryId;
    public String type;
    public String world;
    public int originX;
    public int originY;
    public int originZ;
    /** Clockwise quarter turns. */
    public int rotation;
    /** Copper already paid (reserved) from the treasury. */
    public long cost;
    public String orderedBy;
    public long createdAt;
    /** Dye for {@code {kolor}} blocks. */
    public String dye;
    /** Planned road cells (from the settlement planner). */
    public List<BlockPos> road;
    /** {@code build} (default), {@code repair} or {@code road}. */
    public String kind;
    /** Building to repair (kind {@code repair}) or modernise (kind {@code upgrade}). */
    public UUID repairOf;
    /** Demolition: the type built in its place afterwards. */
    public String then;
    /** Settlement level of the materials. */
    public int level;

    public String kind() {
        return this.kind == null ? ConstructionSite.BUILD : this.kind;
    }

    public QueuedOrder() {
    }

    public static QueuedOrder of(final UUID country, final String type, final String world, final int x, final int y,
                                 final int z, final int rotation, final long cost, final String orderedBy) {
        final QueuedOrder o = new QueuedOrder();
        o.id = UUID.randomUUID();
        o.countryId = country;
        o.type = type;
        o.world = world;
        o.originX = x;
        o.originY = y;
        o.originZ = z;
        o.rotation = rotation;
        o.cost = cost;
        o.orderedBy = orderedBy == null ? "" : orderedBy;
        o.createdAt = System.currentTimeMillis();
        return o;
    }
}
