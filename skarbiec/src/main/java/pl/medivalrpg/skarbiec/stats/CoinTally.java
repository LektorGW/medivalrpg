// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import pl.medivalrpg.skarbiec.money.Denomination;

/** Physical coins found by the census, grouped by place (pure Java). */
public final class CoinTally {

    public static final Map<String, String> KIND_LABELS;

    static {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("PLAYER_ONLINE", "Ekwipunki graczy online");
        m.put("ENDER_ONLINE", "Ender chesty graczy online");
        m.put("PLAYER_OFFLINE", "Ekwipunki graczy offline");
        m.put("ENDER_OFFLINE", "Ender chesty graczy offline");
        m.put("CONTAINER", "Skrzynie i kontenery");
        m.put("GROUND", "Przedmioty na ziemi");
        m.put("ENTITY", "Byty: ramki, wózki, zwierzęta");
        m.put("OTHER", "Inne");
        KIND_LABELS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * One coin stack found somewhere.
     *
     * @param kind    see {@link #KIND_LABELS}
     * @param label   human description of the holder ("Skrzynia", "Ramka"…)
     * @param ownerId player uuid when the place belongs to a player, else null
     * @param world   world name or null (unknown, offline player without position)
     * @param source  LIVE or DISK
     * @param denom   0 copper, 1 silver, 2 gold
     * @param count   coins in the stack
     */
    public record Found(String kind, String label, String ownerId, String world, Integer x, Integer y, Integer z, String source,
                        int denom, int count) {
        public long value() {
            return (long) this.count * Denomination.values()[this.denom].value();
        }
    }

    private static final class Place {
        final Found first;
        final long[] coins = new long[3];
        int stacks;

        Place(final Found f) {
            this.first = f;
        }

        long amount() {
            long v = 0;
            for (final Denomination d : Denomination.values()) {
                v += this.coins[d.ordinal()] * d.value();
            }
            return v;
        }
    }

    private final Map<String, Place> places = new HashMap<>();
    private final long[] denominations = new long[3];
    private long total;
    private long stacks;

    public void add(final Found f) {
        if (f.count() <= 0 || f.denom() < 0 || f.denom() > 2) {
            return;
        }
        final String key = f.kind() + '|' + f.label() + '|' + f.ownerId() + '|' + f.world() + '|' + f.x() + '|' + f.y() + '|' + f.z() + '|' + f.source();
        final Place p = this.places.computeIfAbsent(key, k -> new Place(f));
        p.coins[f.denom()] += f.count();
        p.stacks++;
        this.denominations[f.denom()] += f.count();
        this.total += f.value();
        this.stacks++;
    }

    public void addAll(final Iterable<Found> found) {
        for (final Found f : found) {
            this.add(f);
        }
    }

    public long total() {
        return this.total;
    }

    public long stacks() {
        return this.stacks;
    }

    public int places() {
        return this.places.size();
    }

    /** JSON-ready result; {@code owner} resolves player uuids to names. */
    public Map<String, Object> toMap(final int maxLocations, final Function<String, String> owner) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", this.total);
        out.put("stacks", this.stacks);
        out.put("byDenomination", FlowQuery.map("copper", this.denominations[0], "silver", this.denominations[1], "gold", this.denominations[2]));
        final Map<String, long[]> kinds = new LinkedHashMap<>();
        for (final String k : KIND_LABELS.keySet()) {
            kinds.put(k, new long[3]);
        }
        for (final Place p : this.places.values()) {
            final long[] k = kinds.computeIfAbsent(p.first.kind(), x -> new long[3]);
            k[0] += p.amount();
            k[1] += p.stacks;
            k[2]++;
        }
        final List<Map<String, Object>> byKind = new ArrayList<>();
        kinds.forEach((k, v) -> byKind.add(FlowQuery.map("kind", k, "label", KIND_LABELS.getOrDefault(k, k), "amount", v[0], "stacks", v[1], "places", v[2])));
        out.put("byKind", byKind);
        final List<Place> sorted = new ArrayList<>(this.places.values());
        sorted.sort(Comparator.comparingLong(Place::amount).reversed().thenComparing(p -> p.first.kind()));
        final List<Map<String, Object>> locations = new ArrayList<>();
        for (final Place p : sorted) {
            if (locations.size() >= maxLocations) {
                break;
            }
            final Found f = p.first;
            locations.add(FlowQuery.map("kind", f.kind(), "label", f.label(), "owner", f.ownerId() == null ? null : owner.apply(f.ownerId()),
                "ownerId", f.ownerId(), "world", f.world(), "x", f.x(), "y", f.y(), "z", f.z(), "amount", p.amount(), "stacks", p.stacks,
                "copper", p.coins[0], "silver", p.coins[1], "gold", p.coins[2], "source", f.source()));
        }
        out.put("locations", locations);
        out.put("truncated", sorted.size() > maxLocations);
        return out;
    }

    /** Polish name of a container block or entity id ({@code minecraft:chest} → „Skrzynia”). */
    public static String holderLabel(final String id) {
        final String key = id == null ? "" : id.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        final String name;
        if (key.endsWith("shulker_box")) {
            name = "Skrzynia shulkerów";
        } else if (key.endsWith("chest_boat") || key.endsWith("chest_raft")) {
            name = "Łódź ze skrzynią";
        } else if (key.endsWith("_furnace") || key.equals("furnace")) {
            name = "Piec";
        } else {
            name = switch (key) {
                case "chest" -> "Skrzynia";
                case "trapped_chest" -> "Skrzynia-pułapka";
                case "barrel" -> "Beczka";
                case "hopper" -> "Lejek";
                case "dispenser" -> "Dozownik";
                case "dropper" -> "Podajnik";
                case "crafter" -> "Crafter";
                case "brewing_stand" -> "Stół alchemiczny";
                case "decorated_pot" -> "Dzban";
                case "chiseled_bookshelf" -> "Rzeźbiona biblioteczka";
                case "lectern" -> "Pulpit";
                case "jukebox" -> "Szafa grająca";
                case "campfire", "soul_campfire" -> "Ognisko";
                case "shelf" -> "Półka";
                case "item" -> "Przedmiot na ziemi";
                case "item_frame" -> "Ramka";
                case "glow_item_frame" -> "Świecąca ramka";
                case "armor_stand" -> "Stojak na zbroję";
                case "chest_minecart" -> "Wózek ze skrzynią";
                case "hopper_minecart" -> "Wózek z lejkiem";
                case "donkey" -> "Osioł";
                case "mule" -> "Muł";
                case "llama", "trader_llama" -> "Lama";
                case "allay" -> "Wszędobylsko";
                case "villager" -> "Wieśniak";
                case "mannequin" -> "Manekin (NPC)";
                case "item_display" -> "Ekspozycja przedmiotu";
                case "ender_chest" -> "Ender chest";
                case "player" -> "Ekwipunek gracza";
                default -> key.isEmpty() ? "Nieznane" : key;
            };
        }
        return key.isEmpty() || name.equals(key) ? name : name + " (" + key + ")";
    }
}
