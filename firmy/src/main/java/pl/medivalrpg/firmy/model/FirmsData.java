// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.firmy.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root of {@code firmy.json} (stage 1: registry, people, applications, country policies, intents). Later stages keep
 * their data in their own files ({@code kontrakty.json}, {@code kredyty.json}, {@code manufaktury.json}) so that a
 * rollback to an earlier jar never rewrites (and loses) them.
 */
public final class FirmsData {

    /** A founding application waiting for the seat country (escrow account {@link #account()} holds fee + capital). */
    public static final class Application {
        public static final String ACCOUNT_PREFIX = "firma-wniosek:";

        public String id;
        public String founder;
        public String founderName;
        public String name;
        public FirmType type;
        public String country;
        public String countryName;
        public long fee;
        public long capital;
        public long createdAt;
        public long expiresAt;
        /**
         * OPEN until decided; FUNDING while the escrow transfer is in flight; ZBIORKA while a co-founding gathering
         * still collects the fee + capital (nobody can approve it yet); REFUNDING while the escrow goes back.
         */
        public String state = "OPEN";
        /**
         * Co-founding (zrzutka): player uuid → copper paid into the escrow (the founder included). Empty for a classic
         * application paid by the founder alone. Shares of the new company and refunds follow these amounts.
         */
        public Map<String, Long> contributions = new LinkedHashMap<>();
        /** Names of the contributors (uuid → name), for messages and the member list. */
        public Map<String, String> contributorNames = new LinkedHashMap<>();
        /**
         * A contribution in flight, written (fsync) before the money moves: {@code +uuid|amount} paid in,
         * {@code -uuid|amount} paid back. After a crash the escrow balance tells whether it happened.
         */
        public String pending;
        /** Gathering: decided at the start whether the full application is approved without the ruler (NPC seat, ruler). */
        public boolean autoApprove;

        public boolean gathering() {
            return "ZBIORKA".equals(this.state);
        }

        public long collected() {
            long sum = 0;
            if (this.contributions != null) {
                for (final long v : this.contributions.values()) {
                    sum += v;
                }
            }
            return sum;
        }

        public String account() {
            return ACCOUNT_PREFIX + this.id;
        }
    }

    /**
     * Copy of the co-founding fields of one application, kept in {@code zrzutki.json}: an older jar rewrites
     * {@code firmy.json} without these fields, so after a rollback and a return they are restored from this file.
     */
    public static final class GatheringBackup {
        public Map<String, Long> contributions = new LinkedHashMap<>();
        public Map<String, String> contributorNames = new LinkedHashMap<>();
        public boolean autoApprove;
        public String pending;
        public String state;

        public static Map<String, GatheringBackup> of(final Map<String, Application> applications) {
            final Map<String, GatheringBackup> out = new LinkedHashMap<>();
            for (final Application a : applications.values()) {
                if (a.contributions != null && !a.contributions.isEmpty()) {
                    final GatheringBackup b = new GatheringBackup();
                    b.contributions = new LinkedHashMap<>(a.contributions);
                    b.contributorNames = a.contributorNames == null ? new LinkedHashMap<>() : new LinkedHashMap<>(a.contributorNames);
                    b.autoApprove = a.autoApprove;
                    b.pending = a.pending;
                    b.state = a.state;
                    out.put(a.id, b);
                }
            }
            return out;
        }

        /** Restores applications that lost their co-founding fields; returns how many. */
        public static int restore(final Map<String, Application> applications, final Map<String, GatheringBackup> backup) {
            int n = 0;
            if (backup == null) {
                return 0;
            }
            for (final Application a : applications.values()) {
                final GatheringBackup b = backup.get(a.id);
                if (b != null && (a.contributions == null || a.contributions.isEmpty()) && b.contributions != null && !b.contributions.isEmpty()) {
                    a.contributions = new LinkedHashMap<>(b.contributions);
                    a.contributorNames = b.contributorNames == null ? new LinkedHashMap<>() : new LinkedHashMap<>(b.contributorNames);
                    a.autoApprove = b.autoApprove;
                    if (a.pending == null) {
                        a.pending = b.pending;
                    }
                    n++;
                }
            }
            return n;
        }
    }

    /** Company tax and decisions of one country. */
    public static final class CountryPolicy {
        /** Tax on company income in basis points (500 = 5 %); -1 = default. */
        public int taxBp = -1;
        public String setBy;
        public long setAt;
    }

    /**
     * A money operation that was about to happen (written with fsync before the transfer, removed after the bank
     * confirmed durability). Left over after a crash means "uncertain": the company is frozen for an audit.
     */
    public static final class Intent {
        public String id;
        public String firm;
        public String kind;
        public String description;
        public long createdAt;
        public List<String> legs = new ArrayList<>();
    }

    public int version = 1;
    public Map<String, Firm> firms = new LinkedHashMap<>();
    public Map<String, Application> applications = new LinkedHashMap<>();
    public Map<String, CountryPolicy> countries = new LinkedHashMap<>();
    /** Normalized name → reserved until (epoch millis), after liquidation. */
    public Map<String, Long> reservedNames = new LinkedHashMap<>();
    /** Last day (yyyy-MM-dd, server zone) of the daily settlement. */
    public String lastDaily = "";
    /** Hashed last addresses of players (alt warnings for the owner only): uuid → list of "hash|epochMillis". */
    public Map<String, List<String>> addresses = new LinkedHashMap<>();
    /** Random salt of the address hashes (raw addresses are never stored). */
    public String addressSalt = "";
    /** Messages for offline players (MiniMessage), shown when they join; at most a few dozen per player. */
    public Map<String, List<String>> mail = new LinkedHashMap<>();
}
