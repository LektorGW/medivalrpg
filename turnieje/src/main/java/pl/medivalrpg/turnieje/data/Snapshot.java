// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.data;

import java.util.ArrayList;
import java.util.List;
import pl.medivalrpg.turnieje.arena.Pos;

/**
 * State of a player taken when they enter a running tournament, restored when they leave it (also after a crash: the
 * file is read at start and the snapshot applied when the player joins). Plain fields for Gson.
 */
public final class Snapshot {

    public Pos location;
    public double health;
    public int food;
    public float saturation;
    public String gameMode;
    /** Kit mode: the inventory was stored and cleared; {@link #items} holds it (Base64 of Paper's item bytes, null = empty slot). */
    public boolean inventoryStored;
    public List<String> items = new ArrayList<>();
    /** Session that took the snapshot (diagnostics). */
    public String session;
}
