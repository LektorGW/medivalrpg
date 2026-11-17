// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.npc;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Registry entry of one NPC (survives restarts; the entity itself is persistent and re-attached by id). */
public final class NpcRecord {

    final UUID id;
    final NpcType type;
    String name;
    String skin;
    final String world;
    final double x;
    final double y;
    final double z;
    final float yaw;
    final float pitch;
    UUID entity;
    /** Random UUID giving a default skin when there is no nick skin (persisted, so the look is stable). */
    UUID appearance;
    /** Interaction hitbox entity (not persistent: re-created whenever the chunk loads). */
    UUID hitbox;
    /** Merchant type id ({@code merchant.types}); {@code null} = the configured default type (all older merchants). */
    String merchantType;
    /** Market stall (BetterModel) of a merchant; {@code null} = none. Same world as the NPC. */
    Stall stall;

    /** Placement of a merchant's stall model: block coordinates of its origin and its yaw. */
    public record Stall(double x, double y, double z, float yaw) {
    }

    NpcRecord(final UUID id, final NpcType type, final String name, final String skin, final Location at, final UUID entity) {
        this(id, type, name, skin, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), entity);
    }

    private NpcRecord(final UUID id, final NpcType type, final String name, final String skin, final String world,
                      final double x, final double y, final double z, final float yaw, final float pitch, final UUID entity) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.skin = skin;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.entity = entity;
    }

    public UUID id() {
        return this.id;
    }

    public NpcType type() {
        return this.type;
    }

    public String name() {
        return this.name;
    }

    public String skin() {
        return this.skin;
    }

    public String world() {
        return this.world;
    }

    public int chunkX() {
        return (int) Math.floor(this.x) >> 4;
    }

    public int chunkZ() {
        return (int) Math.floor(this.z) >> 4;
    }

    /** Spawn location, {@code null} when the world is not loaded. */
    public UUID appearance() {
        return this.appearance;
    }

    public Stall stall() {
        return this.stall;
    }

    /** Stored merchant type id, {@code null} for the default type. */
    public String merchantType() {
        return this.merchantType;
    }

    public Location location() {
        final World w = Bukkit.getWorld(this.world);
        return w == null ? null : new Location(w, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", this.id.toString());
        o.addProperty("type", this.type.id());
        o.addProperty("name", this.name);
        o.addProperty("skin", this.skin);
        o.addProperty("world", this.world);
        o.addProperty("x", this.x);
        o.addProperty("y", this.y);
        o.addProperty("z", this.z);
        o.addProperty("yaw", this.yaw);
        o.addProperty("pitch", this.pitch);
        if (this.entity != null) {
            o.addProperty("entity", this.entity.toString());
        }
        if (this.appearance != null) {
            o.addProperty("appearance", this.appearance.toString());
        }
        if (this.merchantType != null) {
            o.addProperty("merchantType", this.merchantType);
        }
        if (this.stall != null) {
            final JsonObject s = new JsonObject();
            s.addProperty("x", this.stall.x());
            s.addProperty("y", this.stall.y());
            s.addProperty("z", this.stall.z());
            s.addProperty("yaw", this.stall.yaw());
            o.add("stall", s);
        }
        return o;
    }

    static NpcRecord fromJson(final JsonObject o) {
        final NpcRecord r = fromJsonBase(o);
        if (o.has("appearance")) {
            r.appearance = UUID.fromString(o.get("appearance").getAsString());
        }
        if (o.has("merchantType") && !o.get("merchantType").isJsonNull()) {
            r.merchantType = o.get("merchantType").getAsString();
        }
        if (o.has("stall") && o.get("stall").isJsonObject()) {
            final JsonObject s = o.getAsJsonObject("stall");
            r.stall = new Stall(s.get("x").getAsDouble(), s.get("y").getAsDouble(), s.get("z").getAsDouble(), s.get("yaw").getAsFloat());
        }
        return r;
    }

    private static NpcRecord fromJsonBase(final JsonObject o) {
        final NpcType type = NpcType.byId(o.get("type").getAsString());
        if (type == null) {
            throw new IllegalArgumentException("Nieznany typ NPC: " + o.get("type"));
        }
        return new NpcRecord(UUID.fromString(o.get("id").getAsString()), type, o.get("name").getAsString(),
            o.has("skin") ? o.get("skin").getAsString() : "", o.get("world").getAsString(),
            o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
            o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat(),
            o.has("entity") ? UUID.fromString(o.get("entity").getAsString()) : null);
    }
}
