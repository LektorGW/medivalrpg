// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.permit;

import java.util.*;

/**
 * Pure rules of an NPC host's answer to a railway consent request (Armia {@code ArmiaPlugin#npcRailConsent}) and the
 * durable, idempotent fee ledger. No Bukkit access.
 *
 * <p>Fee protocol: INTENT is durable before any money moves; the transfer reason carries the key; PAID is durable before
 * the permit is granted. An INTENT without PAID after a crash blocks that host until an administrator audits the
 * transaction log. A PAID key grants the permit again without a second payment.</p>
 */
public final class NpcRailConsent {
    private NpcRailConsent() { }

    public enum Kind { ACCEPT, ACCEPT_FEE, REFUSE }

    public record Answer(Kind kind, long fee, String reason) {
        public Answer {
            if (kind == null || fee < 0 || kind != Kind.ACCEPT_FEE && fee != 0 || kind == Kind.ACCEPT_FEE && fee <= 0) {
                throw new IllegalArgumentException("Nieprawidłowa odpowiedź władcy NPC");
            }
            reason = reason == null ? "" : reason;
        }
    }

    /** Armia contract: {@code ACCEPT}, {@code ACCEPT_FEE:<md>} or {@code REFUSE:<powód>}. Anything else is a refusal. */
    public static Answer parse(final String raw) {
        if (raw == null) return new Answer(Kind.REFUSE, 0, "brak odpowiedzi");
        if (raw.equals("ACCEPT")) return new Answer(Kind.ACCEPT, 0, "");
        if (raw.startsWith("ACCEPT_FEE:")) {
            try {
                final long fee = Long.parseLong(raw.substring("ACCEPT_FEE:".length()).trim());
                if (fee > 0 && fee <= 100_000_000L) return new Answer(Kind.ACCEPT_FEE, fee, "");
            } catch (final NumberFormatException ignored) { }
            return new Answer(Kind.REFUSE, 0, "nieczytelna opłata");
        }
        if (raw.startsWith("REFUSE")) {
            final String why = raw.length() > 7 ? raw.substring(7).trim() : "";
            return new Answer(Kind.REFUSE, 0, why.isEmpty() ? "odmowa władcy" : why);
        }
        return new Answer(Kind.REFUSE, 0, "nieznana odpowiedź");
    }

    /** Combines the joint-project rule of Kraje (only for an applicant country) with the host's answer. */
    public static Answer decide(final Answer host, final boolean applicantCountry, final boolean jointProjectAllowed) {
        if (applicantCountry && !jointProjectAllowed) {
            return new Answer(Kind.REFUSE, 0, "stosunki z krajem wnioskodawcy nie pozwalają na wspólny projekt (unia albo pokój z ambasadą)");
        }
        return host;
    }

    /** Next allowed request after a refusal; {@code minutes <= 0} disables the cooldown. */
    public static long cooldownUntil(final long nowMillis, final long minutes) {
        return minutes <= 0 ? nowMillis : Math.addExact(nowMillis, Math.multiplyExact(minutes, 60_000L));
    }

    public static String key(final UUID host, final String payer) {
        return "zgoda:" + host + ":" + payer;
    }

    /** A budget payer may only spend what the approved limit still allows after quoted work and earlier fees. */
    public static boolean withinLimit(final long maxTotalMd, final long usedByWork, final long earlierFees, final long fee) {
        if (maxTotalMd <= 0 || usedByWork < 0 || earlierFees < 0 || fee <= 0) return false;
        return Math.addExact(Math.addExact(usedByWork, earlierFees), fee) <= maxTotalMd;
    }

    public enum State { INTENT, PAID, FAILED }

    public static final class Entry {
        public String key;
        public UUID host;
        public String payer;
        public long fee;
        public State state;
        public long created;

        public Entry() { }

        public Entry(final String key, final UUID host, final String payer, final long fee, final State state, final long created) {
            this.key = key; this.host = host; this.payer = payer; this.fee = fee; this.state = state; this.created = created;
        }
    }

    /** Serialized ledger. All mutation goes through these methods; persistence is the caller's. */
    public static final class Ledger {
        public int version = 1;
        public List<Entry> entries = new ArrayList<>();

        public Optional<Entry> get(final String key) {
            return entries.stream().filter(e -> e.key.equals(key)).findFirst();
        }

        /** Unresolved INTENT for this host blocks every further request until an administrator audits it. */
        public boolean blocked(final UUID host) {
            return entries.stream().anyMatch(e -> host.equals(e.host) && e.state == State.INTENT);
        }

        public long paidFrom(final String payer) {
            return entries.stream().filter(e -> e.payer.equals(payer) && e.state == State.PAID).mapToLong(e -> e.fee).sum();
        }

        public Entry intent(final UUID host, final String payer, final long fee, final long now) {
            final String key = key(host, payer);
            final Optional<Entry> old = get(key);
            if (old.isPresent() && old.get().state != State.FAILED) throw new IllegalStateException("Opłata za tę zgodę jest już zapisana: " + old.get().state);
            old.ifPresent(entries::remove);
            final Entry entry = new Entry(key, host, payer, fee, State.INTENT, now);
            entries.add(entry);
            return entry;
        }

        public void settle(final String key, final State state) {
            final Entry e = get(key).orElseThrow(() -> new IllegalStateException("Brak zamiaru opłaty"));
            if (e.state != State.INTENT || state == State.INTENT) throw new IllegalStateException("Nieprawidłowe przejście opłaty");
            e.state = state;
        }

        public void validate() {
            final Set<String> keys = new HashSet<>();
            if (version != 1 || entries == null) throw new IllegalArgumentException("Nieprawidłowy dziennik opłat za zgody");
            for (final Entry e : entries) {
                if (e == null || e.key == null || e.host == null || e.payer == null || e.state == null || e.fee <= 0
                    || !e.key.equals(key(e.host, e.payer)) || !keys.add(e.key)) throw new IllegalArgumentException("Nieprawidłowy wpis opłaty za zgodę");
            }
        }
    }
}
