// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Embassy;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.PersonalUnion;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.model.StoredInvite;
import pl.medivalrpg.kraje.model.TreasuryEntry;
import pl.medivalrpg.kraje.model.Vassalage;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Converts countries to and from JSON (Gson tree model, no reflection).
 *
 * <p>Format history: version 1 (no economy / NPC fields) is still read. Every field added in version 2
 * ({@code npc}, {@code npcRulerName}, {@code taxPerCitizen}, {@code unpaidUpkeepDays}, {@code warSince},
 * {@code warEndedAt}, {@code treasuryLog}, member {@code taxDebt}/{@code debtDays}, root {@code economy})
 * is optional and gets a default when missing. Older plugin versions ignore the new fields.</p>
 *
 * <p>Version 3 adds, again optional: country {@code vassalage} ({@code overlord}, {@code since}, {@code until},
 * {@code tributeShare}, {@code tributeMin}, {@code debt}, {@code debtDays}, {@code tributePaid}) and root
 * {@code invitations} (persistent invitations: {@code player}, {@code playerName}, {@code country}, {@code createdAt},
 * {@code expiresAt}, {@code reason}). Files of version 1 and 2 load with no vassals and no invitations.</p>
 *
 * <p>Still version 3, two more optional country fields (older Kraje ignores them): {@code union} on the PARTNER of a
 * personal union ({@code home}, {@code monarch}, {@code monarchName}, {@code since}, {@code regent}, {@code regentName};
 * the monarch is then also listed in the partner's {@code members} with rank RULER) and {@code embassies} on the guest
 * ({@code host}, {@code world}, {@code x}, {@code z}, {@code spot}, {@code openedAt}, {@code closesAt}).</p>
 */
public final class CountryCodec {

    public static final int FORMAT_VERSION = 3;

    private CountryCodec() {
    }

    public static JsonObject encodeAll(final Collection<Country> countries) {
        return encodeAll(countries, 0L);
    }

    /** @param lastDailyTick epoch millis of the last processed daily economy tick (0 = never) */
    public static JsonObject encodeAll(final Collection<Country> countries, final long lastDailyTick) {
        return encodeAll(countries, lastDailyTick, List.of());
    }

    /** Whole file: countries, last daily tick and persistent invitations. */
    public static JsonObject encodeAll(final Collection<Country> countries, final long lastDailyTick,
                                       final Collection<StoredInvite> invites) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        final JsonObject economy = new JsonObject();
        economy.addProperty("lastDailyTick", lastDailyTick);
        root.add("economy", economy);
        final JsonArray array = new JsonArray();
        for (final Country c : countries) {
            array.add(encode(c));
        }
        root.add("countries", array);
        final JsonArray inv = new JsonArray();
        for (final StoredInvite i : invites) {
            final JsonObject io = new JsonObject();
            io.addProperty("player", i.player().toString());
            io.addProperty("playerName", i.playerName() == null ? "" : i.playerName());
            io.addProperty("country", i.country().toString());
            io.addProperty("createdAt", i.createdAt());
            io.addProperty("expiresAt", i.expiresAt());
            io.addProperty("reason", i.reason() == null ? "" : i.reason());
            inv.add(io);
        }
        root.add("invitations", inv);
        return root;
    }

    /** Persistent invitations (empty for files older than version 3). Broken entries are skipped. */
    public static List<StoredInvite> decodeInvites(final JsonObject root) {
        final List<StoredInvite> out = new ArrayList<>();
        final JsonArray array = root.getAsJsonArray("invitations");
        if (array == null) {
            return out;
        }
        for (final JsonElement el : array) {
            try {
                final JsonObject io = el.getAsJsonObject();
                out.add(new StoredInvite(UUID.fromString(io.get("player").getAsString()), string(io, "playerName"),
                    UUID.fromString(io.get("country").getAsString()), number(io, "createdAt"), number(io, "expiresAt"),
                    string(io, "reason")));
            } catch (final RuntimeException ignored) {
                // a damaged invitation is not worth failing the whole file
            }
        }
        return out;
    }

    /** Epoch millis of the last processed daily tick, 0 if none (e.g. version 1 files). */
    public static long decodeLastDailyTick(final JsonObject root) {
        final JsonObject economy = root.getAsJsonObject("economy");
        return economy == null ? 0L : number(economy, "lastDailyTick");
    }

    public static List<Country> decodeAll(final JsonObject root) {
        final List<Country> result = new ArrayList<>();
        final JsonArray array = root.getAsJsonArray("countries");
        if (array == null) {
            return result;
        }
        for (final JsonElement element : array) {
            result.add(decode(element.getAsJsonObject()));
        }
        return result;
    }

    public static JsonObject encode(final Country c) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.id().toString());
        o.addProperty("name", c.name());
        o.addProperty("color", c.hexColor());
        o.addProperty("description", c.description());
        o.addProperty("ruler", c.ruler().toString());
        o.addProperty("foundedAt", c.foundedAt());

        final JsonArray members = new JsonArray();
        for (final Member m : c.members()) {
            final JsonObject mo = new JsonObject();
            mo.addProperty("uuid", m.uuid().toString());
            mo.addProperty("name", m.name());
            mo.addProperty("rank", m.rank().name());
            mo.addProperty("joinedAt", m.joinedAt());
            if (m.taxDebt() > 0 || m.debtDays() > 0) {
                mo.addProperty("taxDebt", m.taxDebt());
                mo.addProperty("debtDays", m.debtDays());
            }
            members.add(mo);
        }
        o.add("members", members);

        final JsonObject claims = new JsonObject();
        for (final Map.Entry<String, Set<Long>> entry : c.claimsMap().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            final JsonArray list = new JsonArray();
            for (final long key : entry.getValue()) {
                final JsonArray pair = new JsonArray();
                pair.add(ChunkKeys.x(key));
                pair.add(ChunkKeys.z(key));
                list.add(pair);
            }
            claims.add(entry.getKey(), list);
        }
        o.add("claims", claims);
        final JsonArray bridgeheads=new JsonArray();
        for (final ChunkPos root:c.bridgeheads()) {
            final JsonObject item=new JsonObject();
            item.addProperty("world",root.world()); item.addProperty("x",root.x()); item.addProperty("z",root.z());
            bridgeheads.add(item);
        }
        o.add("bridgeheads",bridgeheads);

        final JsonObject capital = new JsonObject();
        capital.addProperty("world", c.capital().world());
        capital.addProperty("x", c.capital().x());
        capital.addProperty("z", c.capital().z());
        o.add("capital", capital);

        final SpawnPoint s = c.spawn();
        final JsonObject spawn = new JsonObject();
        spawn.addProperty("world", s.world());
        spawn.addProperty("x", s.x());
        spawn.addProperty("y", s.y());
        spawn.addProperty("z", s.z());
        spawn.addProperty("yaw", s.yaw());
        spawn.addProperty("pitch", s.pitch());
        o.add("spawn", spawn);

        final JsonObject relations = new JsonObject();
        for (final Map.Entry<UUID, Relation> entry : c.relationsMap().entrySet()) {
            if (entry.getValue() != Relation.NEUTRAL) {
                relations.addProperty(entry.getKey().toString(), entry.getValue().name());
            }
        }
        o.add("relations", relations);

        // --- version 2 ---
        o.addProperty("npc", c.npc());
        if (c.npc()) {
            o.addProperty("npcRulerName", c.npcRulerName());
        }
        o.addProperty("taxPerCitizen", c.taxPerCitizen());
        o.addProperty("unpaidUpkeepDays", c.unpaidUpkeepDays());
        o.add("warSince", encodeTimes(c.warSinceMap()));
        o.add("warEndedAt", encodeTimes(c.warEndedAtMap()));
        final JsonArray log = new JsonArray();
        for (final TreasuryEntry e : c.treasuryLog()) {
            final JsonObject eo = new JsonObject();
            eo.addProperty("time", e.time());
            eo.addProperty("type", e.type());
            eo.addProperty("amount", e.amount());
            eo.addProperty("actor", e.actor());
            log.add(eo);
        }
        o.add("treasuryLog", log);

        // --- version 3 ---
        final Vassalage v = c.vassalage();
        if (v != null) {
            final JsonObject vo = new JsonObject();
            vo.addProperty("overlord", v.overlord().toString());
            vo.addProperty("since", v.since());
            vo.addProperty("until", v.until());
            vo.addProperty("tributeShare", v.tributeShare());
            vo.addProperty("tributeMin", v.tributeMin());
            vo.addProperty("debt", v.debt());
            vo.addProperty("debtDays", v.debtDays());
            vo.addProperty("tributePaid", v.tributePaid());
            o.add("vassalage", vo);
        }

        // --- union & embassies (optional) ---
        final PersonalUnion u = c.union();
        if (u != null) {
            final JsonObject uo = new JsonObject();
            uo.addProperty("home", u.home().toString());
            uo.addProperty("monarch", u.monarch().toString());
            uo.addProperty("monarchName", u.monarchName());
            uo.addProperty("since", u.since());
            if (u.regent() != null) {
                uo.addProperty("regent", u.regent().toString());
            }
            uo.addProperty("regentName", u.regentName());
            o.add("union", uo);
        }
        if (!c.embassiesMap().isEmpty()) {
            final JsonArray list = new JsonArray();
            for (final Embassy e : c.embassiesMap().values()) {
                final JsonObject eo = new JsonObject();
                eo.addProperty("host", e.host().toString());
                eo.addProperty("world", e.chunk().world());
                eo.addProperty("x", e.chunk().x());
                eo.addProperty("z", e.chunk().z());
                eo.add("spot", encodeSpawn(e.spot()));
                eo.addProperty("openedAt", e.openedAt());
                eo.addProperty("closesAt", e.closesAt());
                list.add(eo);
            }
            o.add("embassies", list);
        }
        return o;
    }

    private static JsonObject encodeSpawn(final SpawnPoint s) {
        final JsonObject spawn = new JsonObject();
        spawn.addProperty("world", s.world());
        spawn.addProperty("x", s.x());
        spawn.addProperty("y", s.y());
        spawn.addProperty("z", s.z());
        spawn.addProperty("yaw", s.yaw());
        spawn.addProperty("pitch", s.pitch());
        return spawn;
    }

    public static Country decode(final JsonObject o) {
        final JsonObject cap = o.getAsJsonObject("capital");
        final ChunkPos capital = new ChunkPos(cap.get("world").getAsString(), cap.get("x").getAsInt(), cap.get("z").getAsInt());
        final JsonObject sp = o.getAsJsonObject("spawn");
        final SpawnPoint spawn = sp == null
            ? new SpawnPoint(capital.world(), capital.centerBlockX() + 0.5, 64, capital.centerBlockZ() + 0.5, 0f, 0f)
            : new SpawnPoint(sp.get("world").getAsString(), sp.get("x").getAsDouble(), sp.get("y").getAsDouble(),
                sp.get("z").getAsDouble(), sp.get("yaw").getAsFloat(), sp.get("pitch").getAsFloat());

        final Country c = new Country(
            UUID.fromString(o.get("id").getAsString()),
            o.get("name").getAsString(),
            Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16),
            string(o, "description"),
            UUID.fromString(o.get("ruler").getAsString()),
            o.get("foundedAt").getAsLong(),
            capital,
            spawn
        );

        final JsonArray members = o.getAsJsonArray("members");
        if (members != null) {
            for (final JsonElement el : members) {
                final JsonObject mo = el.getAsJsonObject();
                final UUID uuid = UUID.fromString(mo.get("uuid").getAsString());
                final Member member = new Member(uuid, mo.get("name").getAsString(),
                    Rank.valueOf(mo.get("rank").getAsString()), mo.get("joinedAt").getAsLong());
                member.taxDebt(number(mo, "taxDebt"));
                member.debtDays((int) number(mo, "debtDays"));
                c.membersMap().put(uuid, member);
            }
        }

        final JsonObject claims = o.getAsJsonObject("claims");
        if (claims != null) {
            for (final Map.Entry<String, JsonElement> entry : claims.entrySet()) {
                final Set<Long> set = c.mutableClaimsIn(entry.getKey());
                for (final JsonElement el : entry.getValue().getAsJsonArray()) {
                    final JsonArray pair = el.getAsJsonArray();
                    set.add(ChunkKeys.key(pair.get(0).getAsInt(), pair.get(1).getAsInt()));
                }
            }
        }

        final JsonArray bridgeheads=o.getAsJsonArray("bridgeheads");
        if (bridgeheads!=null) for (final JsonElement el:bridgeheads) {
            try {
                final JsonObject item=el.getAsJsonObject();
                final ChunkPos root=new ChunkPos(item.get("world").getAsString(),item.get("x").getAsInt(),item.get("z").getAsInt());
                if (c.claimsIn(root.world()).contains(root.key())) c.mutableBridgeheads().add(root);
            } catch (final RuntimeException ignored) { /* Skip a damaged optional seed. */ }
        }
        pl.medivalrpg.kraje.geometry.Bridgeheads.normalize(c);
        final JsonObject relations = o.getAsJsonObject("relations");
        if (relations != null) {
            for (final Map.Entry<String, JsonElement> entry : relations.entrySet()) {
                c.relationsMap().put(UUID.fromString(entry.getKey()), Relation.valueOf(entry.getValue().getAsString()));
            }
        }

        // --- version 2 (all optional) ---
        c.npc(bool(o, "npc"));
        c.npcRulerName(string(o, "npcRulerName"));
        c.taxPerCitizen(number(o, "taxPerCitizen"));
        c.unpaidUpkeepDays((int) number(o, "unpaidUpkeepDays"));
        decodeTimes(o.getAsJsonObject("warSince"), c.warSinceMap());
        decodeTimes(o.getAsJsonObject("warEndedAt"), c.warEndedAtMap());
        final JsonArray log = o.getAsJsonArray("treasuryLog");
        if (log != null) {
            for (final JsonElement el : log) {
                final JsonObject eo = el.getAsJsonObject();
                c.treasuryLog().addLast(new TreasuryEntry(number(eo, "time"), string(eo, "type"),
                    number(eo, "amount"), string(eo, "actor")));
            }
        }

        // --- version 3 (optional) ---
        final JsonObject vo = o.getAsJsonObject("vassalage");
        if (vo != null && has(vo, "overlord")) {
            final Vassalage v = new Vassalage(UUID.fromString(vo.get("overlord").getAsString()), number(vo, "since"),
                number(vo, "until"), has(vo, "tributeShare") ? vo.get("tributeShare").getAsDouble() : 0.0, number(vo, "tributeMin"));
            v.debt(number(vo, "debt"));
            v.debtDays((int) number(vo, "debtDays"));
            v.tributePaid(number(vo, "tributePaid"));
            c.vassalage(v);
        }

        // --- union & embassies (optional; damaged entries are skipped, the manager validates the rest) ---
        final JsonObject uo = o.getAsJsonObject("union");
        if (uo != null && has(uo, "home") && has(uo, "monarch")) {
            try {
                c.union(new PersonalUnion(UUID.fromString(uo.get("home").getAsString()),
                    UUID.fromString(uo.get("monarch").getAsString()), string(uo, "monarchName"), number(uo, "since"),
                    has(uo, "regent") ? UUID.fromString(uo.get("regent").getAsString()) : null, string(uo, "regentName")));
            } catch (final RuntimeException ignored) {
                // a damaged union is dropped; the manager hands the throne back on load
            }
        }
        final JsonArray embassies = o.getAsJsonArray("embassies");
        if (embassies != null) {
            for (final JsonElement el : embassies) {
                try {
                    final JsonObject eo = el.getAsJsonObject();
                    final ChunkPos pos = new ChunkPos(eo.get("world").getAsString(), eo.get("x").getAsInt(), eo.get("z").getAsInt());
                    final JsonObject so = eo.getAsJsonObject("spot");
                    final SpawnPoint spot = so == null
                        ? new SpawnPoint(pos.world(), pos.centerBlockX() + 0.5, 64, pos.centerBlockZ() + 0.5, 0f, 0f)
                        : new SpawnPoint(so.get("world").getAsString(), so.get("x").getAsDouble(), so.get("y").getAsDouble(),
                            so.get("z").getAsDouble(), so.get("yaw").getAsFloat(), so.get("pitch").getAsFloat());
                    final UUID host = UUID.fromString(eo.get("host").getAsString());
                    c.embassiesMap().put(host, new Embassy(host, pos, spot, number(eo, "openedAt"), number(eo, "closesAt")));
                } catch (final RuntimeException ignored) {
                    // a damaged embassy is not worth failing the whole file
                }
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject encodeTimes(final Map<UUID, Long> map) {
        final JsonObject o = new JsonObject();
        for (final Map.Entry<UUID, Long> entry : map.entrySet()) {
            o.addProperty(entry.getKey().toString(), entry.getValue());
        }
        return o;
    }

    private static void decodeTimes(final JsonObject o, final Map<UUID, Long> into) {
        if (o == null) {
            return;
        }
        for (final Map.Entry<String, JsonElement> entry : o.entrySet()) {
            into.put(UUID.fromString(entry.getKey()), entry.getValue().getAsLong());
        }
    }

    private static boolean has(final JsonObject o, final String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    private static boolean bool(final JsonObject o, final String key) {
        return has(o, key) && o.get(key).getAsBoolean();
    }

    private static long number(final JsonObject o, final String key) {
        return has(o, key) ? o.get(key).getAsLong() : 0L;
    }

    private static String string(final JsonObject o, final String key) {
        return has(o, key) ? o.get(key).getAsString() : "";
    }
}
