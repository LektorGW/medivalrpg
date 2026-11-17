// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.turnieje.integration;

import java.util.Optional;
import java.util.UUID;

/**
 * Money operations used by Turnieje. Implemented by {@link SkarbiecEconomy} when Skarbiec is enabled; otherwise
 * {@link #NONE} (no fees, no prizes). Keeps every reference to Skarbiec classes out of the rest of the plugin, so that
 * Turnieje loads without Skarbiec (softdepend).
 */
public interface Economy {

    boolean available();

    long balance(String account);

    boolean transfer(String from, String to, long amount, String reason);

    /** Creates money (only used for the optional "mint" prize source). */
    boolean deposit(String account, long amount, String reason);

    /** Destroys money. */
    boolean withdraw(String account, long amount, String reason);

    String format(long amount);

    Optional<Long> parse(String input);

    default String playerAccount(final UUID player) {
        return "player:" + player;
    }

    Economy NONE = new Economy() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public long balance(final String account) {
            return 0;
        }

        @Override
        public boolean transfer(final String from, final String to, final long amount, final String reason) {
            return false;
        }

        @Override
        public boolean deposit(final String account, final long amount, final String reason) {
            return false;
        }

        @Override
        public boolean withdraw(final String account, final long amount, final String reason) {
            return false;
        }

        @Override
        public String format(final long amount) {
            return amount + " md";
        }

        @Override
        public Optional<Long> parse(final String input) {
            try {
                final long v = Long.parseLong(input.trim());
                return v >= 0 ? Optional.of(v) : Optional.empty();
            } catch (final RuntimeException e) {
                return Optional.empty();
            }
        }
    };
}
