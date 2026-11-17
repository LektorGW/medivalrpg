// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.armia.naval;

import java.util.UUID;

/** Durable hull position/health. The soldier registry stores the unique cargo manifest via transportShip. */
public final class ShipRecord {
    public UUID id,country;
    public String type,world;
    public double x,y,z,health,sailed;
    public float yaw;
    public long lastVolleyMillis;
    public boolean valid() {
        final var spec=NavalRules.type(this.type);
        if (this.id==null || this.country==null || spec==null || this.world==null || this.world.isBlank()
            || !Double.isFinite(this.x) || !Double.isFinite(this.y) || !Double.isFinite(this.z) || !Float.isFinite(this.yaw)
            || Math.abs(this.x)>29_999_000 || Math.abs(this.z)>29_999_000 || this.y< -2048 || this.y>4096) return false;
        if (!Double.isFinite(this.health) || this.health<=0) return false;
        this.health=Math.min(spec.health(),this.health);
        if (!Double.isFinite(this.sailed) || this.sailed<0) this.sailed=0;
        this.sailed=Math.min(100_000,this.sailed);
        if (this.lastVolleyMillis<0) this.lastVolleyMillis=0;
        return true;
    }
}
