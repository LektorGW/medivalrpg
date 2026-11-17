// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.template;

import java.util.Locale;

/** What a finished building does for its country. */
public enum BuildingFunction {
    /** Beds count as housing for NPC citizens. */
    HOUSING("mieszkanie", true),
    /** Raises the granary capacity; its chests/barrels become granary deposit points. */
    GRANARY("spichlerz", false),
    /** Soldiers' quarters (Armia); beds do NOT count as citizen housing. */
    BARRACKS("koszary", false),
    SMITHY("kuźnia", false),
    WELL("studnia", false),
    TOWER("wieża", false),
    TAVERN("karczma", false),
    MILL("młyn", false),
    STABLE("stajnia", false),
    MARKET("targ", false),
    TOWNHALL("ratusz", false),
    CHURCH("kościół", false),
    WALL("mur", false),
    GATE("brama", false),
    CASTLE("zamek", false),
    PALACE("pałac", false),
    /** Farm field: produces food every day ({@code food.farm-production}). */
    FARM("pole", false),
    BAKERY("piekarnia", false),
    BREWERY("browar", false),
    SAWMILL("tartak", false),
    /** Mine: galleries dug by miners, daily income per gallery block. */
    MINE("kopalnia", false),
    LIBRARY("biblioteka", false),
    HERBALIST("zielarnia", false),
    HARBOR("przystań", false),
    CEMETERY("cmentarz", false),
    MONUMENT("pomnik", false),
    WAREHOUSE("magazyn", false),
    BATH("łaźnia", false),
    LIGHTHOUSE("latarnia", false),
    PLAZA("rynek", false),
    GARDEN("ogrody", false),
    ACADEMY("akademia", false),
    TREASURY("skarbiec", false),
    CATHEDRAL("katedra", false),
    ARENA("arena", false),
    BRIDGE("most", false),
    QUARRY("kamieniołom", false),
    FACTORY("fabryka", false),
    STATION("dworzec", false),
    OFFICE("biurowiec", false),
    UNIVERSITY("uniwersytet", false),
    PRINTING("drukarnia", false),
    PORT("port", false),
    BANK("bank", false),
    OBSERVATORY("obserwatorium", false),
    SCHOOL("szkoła", false),
    HOSPITAL("szpital", false),
    POWERPLANT("elektrownia", false),
    PARK("park", false),
    MALL("galeria", false),
    STADIUM("stadion", false),
    PARLIAMENT("parlament", false),
    PARKING("parking", false),
    LAB("laboratorium", false),
    AIRPORT("lotnisko", false),
    /** Glassworks: digs sand on the country's shores and melts glass (fuel: coal / charcoal from the real store). */
    GLASSWORKS("huta szkła", false),
    /** Brickworks: digs clay at rivers and fires bricks and terracotta. */
    BRICKWORKS("cegielnia", false),
    /** Sheepfold: a flock of real sheep (wool) and a barn pressing hay bales from field wheat. */
    SHEEPFOLD("owczarnia", false),
    OTHER("inny", false);

    private final String displayName;
    private final boolean housing;

    BuildingFunction(final String displayName, final boolean housing) {
        this.displayName = displayName;
        this.housing = housing;
    }

    public String displayName() {
        return this.displayName;
    }

    /** True when beds inside count towards {@code LudnoscApi#housing}. */
    public boolean housing() {
        return this.housing;
    }

    public static BuildingFunction parse(final String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Nieznana funkcja budynku: " + value);
        }
    }
}
