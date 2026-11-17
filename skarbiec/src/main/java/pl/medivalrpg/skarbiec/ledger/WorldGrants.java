// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * One-time deployment grants from the World Treasury reserve (pure Java + files, unit tested).
 *
 * <p>Idempotency has two independent keys: the grant's own state file ({@code bank/grants.json}, written atomically
 * and forced to disk BEFORE the money moves) and the tag {@code [grant:<key>]} in the ledger reason. States:</p>
 * <ul>
 *   <li>{@code NEW} — never attempted;</li>
 *   <li>{@code PENDING} — marked before the move; on the next look the ledger logs decide: tag found → {@code DONE};
 *       tag not found → {@code UNCERTAIN} (e.g. power loss between the move and its log line) — it is NEVER retried
 *       automatically, only by the owner's explicit retry after checking the balances;</li>
 *   <li>{@code DONE} — the move is in the ledger; never paid twice.</li>
 * </ul>
 */
public final class WorldGrants {

    /**
     * Start of every grant reason. Only reserve moves written by {@link #apply} begin with it, so a {@code [grant:…]} tag
     * inside another reason (a company name in a loan, the reason of an NPC treasury transfer) never counts as evidence.
     */
    static final String REASON_PREFIX = "WorldSteward: jednorazowe zasilenie wdrożeniowe — ";

    /** A grant: {@code key} is the one-time key, also written into the ledger reason. */
    public record Grant(String key, String account, long amount, String label) {
        public String reason() {
            return REASON_PREFIX + this.label + " [grant:" + this.key + "]";
        }

        public String tag() {
            return "[grant:" + this.key + "]";
        }
    }

    /** User decisions 2026-10-29: households 312 600 md, merchants 50 000 md. */
    public static final List<Grant> DEPLOYMENT = List.of(
        new Grant("households-312600-20261029", WorldMoney.HOUSEHOLDS, 312_600L, "pula obywateli NPC"),
        new Grant("merchant-50000-20261029", WorldMoney.MERCHANT, 50_000L, "budżet kupców"));

    private final Path file;
    private final Path bankDir;
    private final Map<String, String> state = new LinkedHashMap<>();

    public WorldGrants(final Path bankDir) throws IOException {
        this.bankDir = bankDir;
        this.file = bankDir.resolve("grants.json");
        if (Files.exists(this.file)) {
            final JsonObject o = JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (final var e : o.getAsJsonObject("grants").entrySet()) {
                this.state.put(e.getKey(), e.getValue().getAsString());
            }
        }
    }

    public static Grant byKey(final String key) {
        return DEPLOYMENT.stream().filter(g -> g.key().equals(key)).findFirst().orElse(null);
    }

    /** Stored state: NEW, PENDING or DONE (UNCERTAIN is derived from PENDING + missing ledger tag). */
    public synchronized String stored(final String key) {
        return this.state.getOrDefault(key, "NEW");
    }

    /** Ledger tags of grants and allocations from the reserve per account, read from journal + daily logs (any thread). */
    public record Evidence(Set<String> tags, Map<String, List<TxRecord>> allocations) {
    }

    public Evidence evidence() throws IOException {
        final java.util.HashSet<String> tags = new java.util.HashSet<>();
        final Map<String, List<TxRecord>> alloc = new LinkedHashMap<>();
        final List<Path> files = new ArrayList<>();
        files.add(this.bankDir.resolve("journal.log"));
        final Path logs = this.bankDir.resolve("logs");
        if (Files.isDirectory(logs)) {
            try (Stream<Path> s = Files.list(logs)) {
                s.filter(p -> p.getFileName().toString().startsWith("transakcje-")).sorted().forEach(files::add);
            }
        }
        for (final Path f : files) {
            if (!Files.exists(f)) {
                continue;
            }
            for (final String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                final TxRecord r = TxRecord.decode(line);
                if (r == null || !WorldMoney.RESERVE.equals(r.from()) || r.to() == null) {
                    continue;
                }
                final int at = r.reason().indexOf("[grant:");
                if (at >= 0 && r.reason().indexOf(']', at) > at && r.reason().startsWith(REASON_PREFIX)) {
                    tags.add(r.reason().substring(at, r.reason().indexOf(']', at) + 1));
                }
                alloc.computeIfAbsent(r.to(), k -> new ArrayList<>()).add(r);
            }
        }
        return new Evidence(tags, alloc);
    }

    /** Effective state: NEW / DONE / UNCERTAIN (PENDING whose ledger tag is missing). Promotes PENDING+tag to DONE. */
    public synchronized String effective(final Grant g, final Evidence ev) throws IOException {
        final String s = this.stored(g.key());
        if ("DONE".equals(s)) {
            return "DONE";
        }
        if (ev.tags().contains(g.tag())) {
            this.put(g.key(), "DONE");
            return "DONE";
        }
        return "PENDING".equals(s) ? "UNCERTAIN" : "NEW";
    }

    /**
     * Pays one grant from the reserve. Owner only, closed pool only. Refuses DONE grants; refuses UNCERTAIN unless
     * {@code retryUncertain} (explicit owner decision). The state is forced to disk as PENDING before the move.
     * The caller marks it {@link #markDone} after the journal is durable.
     */
    public synchronized TxResult apply(final Ledger ledger, final UUID actor, final Grant g, final Evidence ev, final boolean retryUncertain)
        throws IOException {
        if (actor == null || !WorldMoney.MANAGER.equals(actor)) {
            throw new IllegalArgumentException("Tylko WorldSteward zarządza Skarbem Świata.");
        }
        if (!ledger.closedSupply()) {
            throw new IllegalArgumentException("Zamknięty obieg nie został jeszcze aktywowany.");
        }
        final String eff = this.effective(g, ev);
        if ("DONE".equals(eff)) {
            throw new IllegalArgumentException("To zasilenie zostało już wykonane (" + g.key() + ").");
        }
        if ("UNCERTAIN".equals(eff) && !retryUncertain) {
            throw new IllegalArgumentException("Stan zasilenia " + g.key() + " jest niepewny (przerwany zapis). Sprawdź saldo "
                + g.account() + " i ponów świadomie: /skarbswiata wdrozenie ponow " + g.key());
        }
        if (ledger.balance(WorldMoney.RESERVE) < g.amount()) {
            throw new IllegalArgumentException("Za mało środków w rezerwie Skarbu Świata.");
        }
        final String before = this.state.get(g.key());
        this.put(g.key(), "PENDING");
        final TxResult r = ledger.allocateWorldMoney(actor, g.account(), g.amount(), g.reason());
        if (!r.ok()) {
            // Nothing moved now: back to the stored state from before (an UNCERTAIN grant stays PENDING, never NEW).
            if (before == null) {
                this.state.remove(g.key());
            } else {
                this.state.put(g.key(), before);
            }
            this.save();
            throw new IllegalArgumentException(r == TxResult.INSUFFICIENT_FUNDS ? "Za mało środków w rezerwie Skarbu Świata."
                : "Zasilenie nie zostało wykonane: " + r);
        }
        return r;
    }

    public synchronized void markDone(final String key) throws IOException {
        this.put(key, "DONE");
    }

    private void put(final String key, final String value) throws IOException {
        this.state.put(key, value);
        this.save();
    }

    private void save() throws IOException {
        final JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        final JsonObject g = new JsonObject();
        this.state.forEach(g::addProperty);
        root.add("grants", g);
        Files.createDirectories(this.file.getParent());
        final Path tmp = this.file.resolveSibling("grants.json.tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(java.nio.ByteBuffer.wrap(root.toString().getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        try {
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
