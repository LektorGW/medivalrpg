// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import com.google.gson.JsonArray;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * Equal transfer from the World Treasury reserve to EVERY NPC country as one operation (pure Java + one file, unit tested).
 *
 * <p>One operation = one key ({@code [a-z0-9-]{8,64}}, chosen by the caller once per preview). Its record in
 * {@code bank/npc-bulk.json} is forced to disk as {@code PENDING} BEFORE any money moves, then all legs move in one
 * atomic ledger step ({@link Ledger#allocateWorldMoneyAll}); the caller marks it {@code DONE} after the bank journal is
 * durable. Every ledger reason carries {@code [npc-bulk:<key>]}, the second, independent proof of the operation.</p>
 * <ul>
 *   <li>The same key again never pays again: DONE (or PENDING applied in this server session) answers with the stored
 *       result and {@code replayed: true}.</li>
 *   <li>PENDING after a restart: the ledger logs decide — every leg tagged → DONE; none or some → UNCERTAIN, never
 *       repeated automatically; an explicit {@code retry} pays ONLY the legs without a tag (same key, same amount).</li>
 *   <li>Not enough in the reserve for the whole sum → refused, nothing moves. No NPC countries → refused.</li>
 * </ul>
 */
public final class NpcBulkTransfers {

    public static final String TAG = "[npc-bulk:";
    private static final int KEEP = 200;

    /** One NPC country of the operation. */
    public record Leg(String countryId, String name, String account) {
    }

    /** Stored operation. */
    public record Op(String key, String state, long perCountry, String reason, List<Leg> legs, long createdAt) {
        public long total() {
            return this.perCountry * this.legs.size();
        }
    }

    private final Path file;
    private final Path bankDir;
    private final Map<String, Op> ops = new LinkedHashMap<>();
    /** Keys whose money moved in this JVM: the in-memory ledger already has them, the logs may still lag behind. */
    private final Set<String> appliedThisSession = new HashSet<>();

    public NpcBulkTransfers(final Path bankDir) throws IOException {
        this.bankDir = bankDir;
        this.file = bankDir.resolve("npc-bulk.json");
        if (Files.exists(this.file)) {
            final JsonObject root = JsonParser.parseString(Files.readString(this.file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (final var e : root.getAsJsonObject("ops").entrySet()) {
                final JsonObject o = e.getValue().getAsJsonObject();
                final List<Leg> legs = new ArrayList<>();
                for (final var l : o.getAsJsonArray("legs")) {
                    final JsonObject j = l.getAsJsonObject();
                    legs.add(new Leg(j.get("country").getAsString(), j.get("name").getAsString(), j.get("account").getAsString()));
                }
                this.ops.put(e.getKey(), new Op(e.getKey(), o.get("state").getAsString(), o.get("perCountry").getAsLong(),
                    o.get("reason").getAsString(), List.copyOf(legs), o.get("createdAt").getAsLong()));
            }
        }
    }

    public static String tag(final String key) {
        return TAG + key + "]";
    }

    public static boolean validKey(final String key) {
        return key != null && key.matches("[a-z0-9-]{8,64}");
    }

    public synchronized Op op(final String key) {
        return this.ops.get(key);
    }

    /** Most recent operations first (owner statistics). */
    public synchronized List<Op> recent(final int limit) {
        final List<Op> all = new ArrayList<>(this.ops.values());
        java.util.Collections.reverse(all);
        return List.copyOf(all.subList(0, Math.min(Math.max(0, limit), all.size())));
    }

    /** Whether answering for this key needs the ledger logs (PENDING from an earlier server session). */
    public synchronized boolean needsEvidence(final String key) {
        final Op op = this.ops.get(key);
        return op != null && "PENDING".equals(op.state()) && !this.appliedThisSession.contains(key);
    }

    /** Accounts that received a tagged move of {@code key}, from journal + daily logs (any thread, read only). */
    public Set<String> evidence(final String key) throws IOException {
        final String tag = tag(key);
        final Set<String> out = new HashSet<>();
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
                if (!line.contains(tag)) {
                    continue;
                }
                final TxRecord r = TxRecord.decode(line);
                if (r != null && WorldMoney.RESERVE.equals(r.from()) && r.to() != null && r.reason().contains(tag)) {
                    out.add(r.to());
                }
            }
        }
        return out;
    }

    /**
     * Performs (or answers again for) the operation {@code key}. Main thread. {@code tagged} = accounts with a tagged move
     * of this key in the logs (only needed when {@link #needsEvidence}; else may be {@code null}).
     *
     * @param countries   current NPC countries (refused when empty)
     * @param perCountry  md for each country
     * @param confirmTotal the whole sum the owner confirmed (must equal perCountry × count)
     * @param confirmCount the number of countries the owner saw (must equal the current number)
     */
    public synchronized Map<String, Object> execute(final Ledger ledger, final UUID actor, final String key, final List<Leg> countries,
                                                    final long perCountry, final long confirmTotal, final int confirmCount, final String reason,
                                                    final boolean retry, final Set<String> tagged) throws IOException {
        if (actor == null || !WorldMoney.MANAGER.equals(actor)) {
            throw new IllegalArgumentException("Tylko WorldSteward zarządza Skarbem Świata.");
        }
        if (!ledger.closedSupply()) {
            throw new IllegalArgumentException("Zamknięty obieg nie został jeszcze aktywowany.");
        }
        if (!validKey(key)) {
            throw new IllegalArgumentException("Brak klucza operacji (odśwież podgląd).");
        }
        final Op existing = this.ops.get(key);
        if (existing != null) {
            return this.again(ledger, actor, existing, retry, tagged);
        }
        if (countries == null || countries.isEmpty()) {
            throw new IllegalArgumentException("Brak państw NPC — nie ma komu przelać.");
        }
        if (perCountry <= 0 || perCountry > WorldMoney.CAP) {
            throw new IllegalArgumentException("Podaj dodatnią kwotę.");
        }
        final long total;
        try {
            total = Math.multiplyExact(perCountry, (long) countries.size());
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException("Za duża kwota.");
        }
        if (confirmCount != countries.size()) {
            throw new IllegalArgumentException("Liczba państw NPC zmieniła się (teraz " + countries.size() + ") — odśwież podgląd.");
        }
        if (confirmTotal != total) {
            throw new IllegalArgumentException("Potwierdzona suma nie zgadza się z kwotą przelewu.");
        }
        final String why = clean(reason);
        if (why.length() < 3 || why.length() > 120) {
            throw new IllegalArgumentException("Podaj powód przelewu (3–120 znaków).");
        }
        final Set<String> accounts = new HashSet<>();
        for (final Leg l : countries) {
            if (!accounts.add(l.account())) {
                throw new IllegalArgumentException("Powtórzone państwo NPC na liście.");
            }
        }
        if (ledger.balance(WorldMoney.RESERVE) < total) {
            throw new IllegalArgumentException("Za mało środków w rezerwie Skarbu Świata na przelew do wszystkich państw NPC.");
        }
        final Op op = new Op(key, "PENDING", perCountry, why, List.copyOf(countries), System.currentTimeMillis());
        this.ops.put(key, op);
        this.save();
        final TxResult r = this.pay(ledger, actor, op, countries);
        if (!r.ok()) {
            this.ops.remove(key);
            this.save();
            throw new IllegalArgumentException(r == TxResult.INSUFFICIENT_FUNDS
                ? "Za mało środków w rezerwie Skarbu Świata na przelew do wszystkich państw NPC." : "Przelew nie został wykonany: " + r);
        }
        this.appliedThisSession.add(key);
        return this.result(ledger, op, false, countries.size());
    }

    private Map<String, Object> again(final Ledger ledger, final UUID actor, final Op op, final boolean retry, final Set<String> tagged)
        throws IOException {
        if ("DONE".equals(op.state()) || this.appliedThisSession.contains(op.key())) {
            return this.result(ledger, op, true, 0);
        }
        if (tagged == null) {
            throw new IllegalStateException("Brak dowodów z księgi dla operacji " + op.key());
        }
        final List<Leg> missing = op.legs().stream().filter(l -> !tagged.contains(l.account())).toList();
        if (missing.isEmpty()) {
            this.markDone(op.key());
            return this.result(ledger, this.ops.get(op.key()), true, 0);
        }
        if (!retry) {
            throw new IllegalArgumentException("Stan operacji " + op.key() + " jest niepewny (przerwany zapis): w księdze jest "
                + (op.legs().size() - missing.size()) + " z " + op.legs().size() + " przelewów. Sprawdź salda i ponów świadomie "
                + "(ponowienie przeleje tylko brakujące).");
        }
        final long need = op.perCountry() * missing.size();
        if (ledger.balance(WorldMoney.RESERVE) < need) {
            throw new IllegalArgumentException("Za mało środków w rezerwie Skarbu Świata na brakujące przelewy.");
        }
        final TxResult r = this.pay(ledger, actor, op, missing);
        if (!r.ok()) {
            throw new IllegalArgumentException("Przelew nie został wykonany: " + r);
        }
        this.appliedThisSession.add(op.key());
        return this.result(ledger, op, false, missing.size());
    }

    private TxResult pay(final Ledger ledger, final UUID actor, final Op op, final List<Leg> legs) {
        final LinkedHashMap<String, Long> targets = new LinkedHashMap<>();
        final Map<String, Leg> byAccount = new LinkedHashMap<>();
        for (final Leg l : legs) {
            targets.put(l.account(), op.perCountry());
            byAccount.put(l.account(), l);
        }
        final int n = op.legs().size();
        return ledger.allocateWorldMoneyAll(actor, targets, account -> {
            final Leg l = byAccount.get(account);
            return "WorldSteward: Skarb Świata → skarbiec kraju NPC " + l.name() + " (przelew do wszystkich " + n + " państw NPC): "
                + op.reason() + " " + tag(op.key());
        });
    }

    private Map<String, Object> result(final Ledger ledger, final Op op, final boolean replayed, final int paidNow) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("key", op.key());
        out.put("replayed", replayed);
        out.put("state", op.state());
        out.put("perCountry", op.perCountry());
        out.put("count", op.legs().size());
        out.put("total", op.total());
        out.put("paidNow", paidNow);
        out.put("reserve", ledger.balance(WorldMoney.RESERVE));
        out.put("totalMoney", ledger.totalMoney());
        final List<Map<String, Object>> legs = new ArrayList<>();
        for (final Leg l : op.legs()) {
            legs.add(Map.of("country", l.countryId(), "name", l.name(), "account", l.account(), "amount", op.perCountry(),
                "balance", ledger.balance(l.account())));
        }
        out.put("legs", legs);
        out.put("message", (replayed ? "Ta operacja była już wykonana — nic nie przelano drugi raz. " : "")
            + "Przelew do wszystkich państw NPC: " + op.legs().size() + " × " + pl.medivalrpg.skarbiec.money.Money.format(op.perCountry())
            + " = " + pl.medivalrpg.skarbiec.money.Money.format(op.total()) + ".");
        return out;
    }

    /** Summary for the statistics: one row per operation with its breakdown. */
    public static Map<String, Object> describe(final Op op) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", op.key());
        m.put("state", op.state());
        m.put("time", op.createdAt());
        m.put("perCountry", op.perCountry());
        m.put("count", op.legs().size());
        m.put("total", op.total());
        m.put("reason", op.reason());
        m.put("legs", op.legs().stream().map(l -> Map.of("country", l.countryId(), "name", l.name(), "account", l.account())).toList());
        return m;
    }

    public synchronized void markDone(final String key) throws IOException {
        final Op op = this.ops.get(key);
        if (op != null && !"DONE".equals(op.state())) {
            this.ops.put(key, new Op(op.key(), "DONE", op.perCountry(), op.reason(), op.legs(), op.createdAt()));
            this.save();
        }
    }

    private static String clean(final String reason) {
        return reason == null ? "" : reason.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('[', '(').replace(']', ')').strip();
    }

    private void save() throws IOException {
        while (this.ops.size() > KEEP) {
            final String oldest = this.ops.keySet().iterator().next();
            if (!"DONE".equals(this.ops.get(oldest).state())) {
                break; // never forget an unfinished operation
            }
            this.ops.remove(oldest);
        }
        final JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        final JsonObject all = new JsonObject();
        this.ops.forEach((k, op) -> {
            final JsonObject o = new JsonObject();
            o.addProperty("state", op.state());
            o.addProperty("perCountry", op.perCountry());
            o.addProperty("reason", op.reason());
            o.addProperty("createdAt", op.createdAt());
            final JsonArray legs = new JsonArray();
            for (final Leg l : op.legs()) {
                final JsonObject j = new JsonObject();
                j.addProperty("country", l.countryId());
                j.addProperty("name", l.name());
                j.addProperty("account", l.account());
                legs.add(j);
            }
            o.add("legs", legs);
            all.add(k, o);
        });
        root.add("ops", all);
        Files.createDirectories(this.file.getParent());
        final Path tmp = this.file.resolveSibling("npc-bulk.json.tmp");
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
