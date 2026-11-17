// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.logic;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Persisted incident lifecycle. Recovery is accepted once, after a complete quiet scan. Pure Java. */
public final class FireIncident {
    public record Cell(int x, int y, int z) { }
    public UUID id = UUID.randomUUID();
    public UUID country;
    public String world;
    public int x, y, z, minX, minY, minZ, maxX, maxY, maxZ;
    public long lastFireAt, lastAlarmAt, lastSweepAt, nextRecoveryAt, recoveredAt;
    public Set<Cell> fires = new LinkedHashSet<>();

    public FireIncident() { }
    public FireIncident(final UUID country, final String world, final Cell at, final long now) {
        this.country = country; this.world = world;
        this.x = this.minX = this.maxX = at.x(); this.y = this.minY = this.maxY = at.y();
        this.z = this.minZ = this.maxZ = at.z();
        this.observe(at, now, 1024);
    }

    public boolean accepts(final UUID owner, final String dimension, final Cell cell, final int radius) {
        return this.recoveredAt == 0 && owner.equals(this.country) && dimension.equals(this.world)
            && Math.abs(cell.x() - this.x) <= radius && Math.abs(cell.z() - this.z) <= radius
            && Math.abs(cell.y() - this.y) <= radius;
    }

    public void observe(final Cell cell, final long now, final int maximumCells) {
        this.lastFireAt = now;
        this.minX = Math.min(this.minX, cell.x()); this.maxX = Math.max(this.maxX, cell.x());
        this.minY = Math.min(this.minY, cell.y()); this.maxY = Math.max(this.maxY, cell.y());
        this.minZ = Math.min(this.minZ, cell.z()); this.maxZ = Math.max(this.maxZ, cell.z());
        if (this.fires.size() < maximumCells) this.fires.add(cell);
    }

    public boolean alarmDue(final long now, final long cooldown) {
        return this.recoveredAt == 0 && !this.fires.isEmpty()
            && (this.lastAlarmAt == 0 || now - this.lastAlarmAt >= cooldown);
    }

    public boolean recoveryDue(final long now, final long quietMillis) {
        return this.recoveredAt == 0 && this.fires.isEmpty() && this.lastSweepAt > this.lastFireAt
            && now - this.lastFireAt >= quietMillis && now >= this.nextRecoveryAt;
    }

    public void recoveryResult(final int accepted, final long now, final long retryMillis) {
        if (accepted >= 0) this.recoveredAt = now;
        else this.nextRecoveryAt = now + Math.max(1000, retryMillis);
    }

    public FireIncident normalize() {
        if (this.id == null) this.id = UUID.randomUUID();
        if (this.fires == null) this.fires = new LinkedHashSet<>();
        return this;
    }
}
