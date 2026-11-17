// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.model;

import java.util.Objects;
import java.util.UUID;
import pl.medivalrpg.ludnosc.template.BuildingTemplate;

/**
 * A player blueprint ({@code /ludnosc kopia}): a building copied from the country's land, stored per country in
 * {@code plugins/Ludnosc/kopie/<country>/<key>.yml} and registered as template {@link #templateId()}.
 *
 * @param key       stable file / id key ({@code [a-z0-9]{4,12}}); the name can change, the key never does
 * @param name      player-facing name (unique per country, case-insensitive)
 * @param removed   deleted by the players while buildings / sites still use it: hidden, not orderable, kept only so
 *                  repairs of existing buildings still know the template
 * @param forbidden blocks left out when copying (not allowed)
 * @param liquids   water / lava cells left out when copying
 */
public record Blueprint(String key, UUID country, String name, String author, UUID authorId, long createdAt,
                        int forbidden, int liquids, boolean removed, BuildingTemplate template) {

    public Blueprint {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(country, "country");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(template, "template");
        author = author == null ? "" : author;
    }

    public String templateId() {
        return this.template.id();
    }

    public Blueprint withName(final String newName, final BuildingTemplate newTemplate) {
        return new Blueprint(this.key, this.country, newName, this.author, this.authorId, this.createdAt, this.forbidden,
            this.liquids, this.removed, newTemplate);
    }

    public Blueprint withRemoved(final boolean value) {
        return new Blueprint(this.key, this.country, this.name, this.author, this.authorId, this.createdAt, this.forbidden,
            this.liquids, value, this.template);
    }

    public Blueprint withTemplate(final BuildingTemplate newTemplate) {
        return new Blueprint(this.key, this.country, this.name, this.author, this.authorId, this.createdAt, this.forbidden,
            this.liquids, this.removed, newTemplate);
    }
}
