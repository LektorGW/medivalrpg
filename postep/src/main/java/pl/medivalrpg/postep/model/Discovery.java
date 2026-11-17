// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.postep.model;

import java.util.List;
import java.util.Map;

/**
 * One discovery (tech) from {@code drzewko.yml}.
 *
 * @param id             lower-case id, e.g. {@code "druk"}
 * @param name           Polish name
 * @param era            era it belongs to
 * @param cost           research points
 * @param requires       ids of required discoveries (same or earlier era)
 * @param requiredForEra needed to leave {@code era} (enter the next one)
 * @param category       gospodarka, nauka, wojsko, budownictwo, dyplomacja
 * @param icon           material name for the GUI
 * @param description    Polish description
 * @param effectText     Polish description of the effect
 * @param effects        numeric effects in percent (podatki, jedzenie, sila-armii, tempo-budowy, badania, ...)
 */
public record Discovery(String id, String name, int era, long cost, List<String> requires, boolean requiredForEra,
                        String category, String icon, String description, String effectText, Map<String, Double> effects) {

    public Discovery {
        requires = List.copyOf(requires);
        effects = Map.copyOf(effects);
    }
}
