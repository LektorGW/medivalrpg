// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Country lookups used by Turnieje. Implemented by {@link KrajeCountries} when Kraje is enabled; otherwise
 * {@link #NONE}. Keeps Kraje classes out of the rest of the plugin (softdepend).
 */
public interface Countries {

    /**
     * @param rankWeight rank of the asking player in this country (1 citizen, 2 advisor, 3 ruler; 0 = not a member)
     */
    record Country(UUID id, String name, String treasuryAccount, int rankWeight) {
    }

    boolean available();

    Optional<Country> countryOf(UUID player);

    Optional<Country> byId(UUID id);

    Optional<Country> byName(String name);

    List<String> names();

    Countries NONE = new Countries() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Optional<Country> countryOf(final UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<Country> byId(final UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Country> byName(final String name) {
            return Optional.empty();
        }

        @Override
        public List<String> names() {
            return List.of();
        }
    };
}
