// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import pl.medivalrpg.kraje.model.Relation;

/** The relation between two countries changed. Fired on the main thread after the change is applied. */
public final class RelationChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID countryA;
    private final UUID countryB;
    private final Relation oldRelation;
    private final Relation newRelation;

    public RelationChangeEvent(final UUID countryA, final UUID countryB, final Relation oldRelation, final Relation newRelation) {
        this.countryA = countryA;
        this.countryB = countryB;
        this.oldRelation = oldRelation;
        this.newRelation = newRelation;
    }

    public UUID countryA() {
        return this.countryA;
    }

    public UUID countryB() {
        return this.countryB;
    }

    public Relation oldRelation() {
        return this.oldRelation;
    }

    public Relation newRelation() {
        return this.newRelation;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
