// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.TextColor;

/**
 * A country. Mutated only by {@code CountryManager} on the main thread, which also keeps the lookup
 * indexes in sync; other code should treat instances as read-only.
 *
 * <p>An NPC country ({@link #npc()}) is run by the Armia AI: its ruler is a synthetic id
 * ({@link #npcRulerId(UUID)}) that is not a player and its ruler name is {@link #npcRulerName()}.
 * It normally has no player members.</p>
 */
public final class Country {

    private final UUID id;
    private String name;
    private int color;
    private String description;
    private UUID ruler;
    private final long foundedAt;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Map<String, Set<Long>> claims = new HashMap<>();
    /** Persistent seeds of overseas territory; only CountryManager mutates these. */
    private final Set<ChunkPos> bridgeheads = new HashSet<>();
    private ChunkPos capital;
    private SpawnPoint spawn;
    private final Map<UUID, Relation> relations = new HashMap<>();
    /** Other country → epoch millis when the current war with it started. */
    private final Map<UUID, Long> warSince = new HashMap<>();
    /** Other country → epoch millis when the last war with it ended (re-declaration cooldown). */
    private final Map<UUID, Long> warEndedAt = new HashMap<>();
    /** Newest first. */
    private final Deque<TreasuryEntry> treasuryLog = new ArrayDeque<>();
    private boolean npc;
    private String npcRulerName = "";
    private long taxPerCitizen;
    private int unpaidUpkeepDays;
    /** Non-null while this country is the vassal of another one. */
    private Vassalage vassalage;
    /** Non-null while this country is the PARTNER of a personal union (its throne is held by another country's ruler). */
    private PersonalUnion union;
    /** Embassies of this country abroad: host id → embassy. */
    private final Map<UUID, Embassy> embassies = new LinkedHashMap<>();

    public Country(final UUID id, final String name, final int color, final String description, final UUID ruler,
                   final long foundedAt, final ChunkPos capital, final SpawnPoint spawn) {
        this.id = id;
        this.name = name;
        this.color = color & 0xFFFFFF;
        this.description = description;
        this.ruler = ruler;
        this.foundedAt = foundedAt;
        this.capital = capital;
        this.spawn = spawn;
    }

    public UUID id() {
        return this.id;
    }

    public String name() {
        return this.name;
    }

    public void name(final String name) {
        this.name = name;
    }

    public int color() {
        return this.color;
    }

    public TextColor textColor() {
        return TextColor.color(this.color);
    }

    public String hexColor() {
        return String.format("#%06X", this.color);
    }

    public void color(final int color) {
        this.color = color & 0xFFFFFF;
    }

    public String description() {
        return this.description;
    }

    public void description(final String description) {
        this.description = description;
    }

    public UUID ruler() {
        return this.ruler;
    }

    public void ruler(final UUID ruler) {
        this.ruler = ruler;
    }

    public long foundedAt() {
        return this.foundedAt;
    }

    public ChunkPos capital() {
        return this.capital;
    }

    public void capital(final ChunkPos capital) {
        this.capital = capital;
    }

    public SpawnPoint spawn() {
        return this.spawn;
    }

    public void spawn(final SpawnPoint spawn) {
        this.spawn = spawn;
    }

    // --- members ---

    public Map<UUID, Member> membersMap() {
        return this.members;
    }

    public Collection<Member> members() {
        return Collections.unmodifiableCollection(this.members.values());
    }

    public Member member(final UUID uuid) {
        return this.members.get(uuid);
    }

    public boolean isMember(final UUID uuid) {
        return this.members.containsKey(uuid);
    }

    public int memberCount() {
        return this.members.size();
    }

    public Member rulerMember() {
        return this.members.get(this.ruler);
    }

    public String rulerName() {
        if (this.npc) {
            return this.npcRulerName == null || this.npcRulerName.isEmpty() ? "?" : this.npcRulerName;
        }
        final Member m = this.rulerMember();
        return m == null ? "?" : m.name();
    }

    // --- NPC ---

    /** Synthetic ruler id of an NPC country (never a player UUID). */
    public static UUID npcRulerId(final UUID countryId) {
        return UUID.nameUUIDFromBytes(("kraje:npc-ruler:" + countryId).getBytes(StandardCharsets.UTF_8));
    }

    public boolean npc() {
        return this.npc;
    }

    public void npc(final boolean npc) {
        this.npc = npc;
    }

    public String npcRulerName() {
        return this.npcRulerName;
    }

    public void npcRulerName(final String npcRulerName) {
        this.npcRulerName = npcRulerName == null ? "" : npcRulerName;
    }

    // --- economy ---

    /** Daily tax per player citizen in copper (0 = none). */
    public long taxPerCitizen() {
        return this.taxPerCitizen;
    }

    public void taxPerCitizen(final long tax) {
        this.taxPerCitizen = Math.max(0, tax);
    }

    /** Consecutive daily ticks in which the upkeep could not be paid. */
    public int unpaidUpkeepDays() {
        return this.unpaidUpkeepDays;
    }

    public void unpaidUpkeepDays(final int days) {
        this.unpaidUpkeepDays = Math.max(0, days);
    }

    public Deque<TreasuryEntry> treasuryLog() {
        return this.treasuryLog;
    }

    /** Skarbiec account id of the treasury (same as {@code SkarbiecApi.countryAccount}). */
    public String treasuryAccount() {
        return treasuryAccount(this.id);
    }

    public static String treasuryAccount(final UUID countryId) {
        return "country:" + countryId;
    }

    public List<Member> membersWithRank(final Rank rank) {
        return this.members.values().stream().filter(m -> m.rank() == rank).toList();
    }

    // --- claims ---

    /** Mutable claim sets per world (manager use only). */
    public Map<String, Set<Long>> claimsMap() {
        return this.claims;
    }

    public Set<ChunkPos> bridgeheads() {
        return Collections.unmodifiableSet(this.bridgeheads);
    }

    public Set<ChunkPos> mutableBridgeheads() {
        return this.bridgeheads;
    }

    public Set<Long> claimsIn(final String world) {
        final Set<Long> set = this.claims.get(world);
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    public Set<Long> mutableClaimsIn(final String world) {
        return this.claims.computeIfAbsent(world, w -> new HashSet<>());
    }

    public Set<String> claimWorlds() {
        return Collections.unmodifiableSet(this.claims.keySet());
    }

    public int claimCount() {
        int total = 0;
        for (final Set<Long> set : this.claims.values()) {
            total += set.size();
        }
        return total;
    }

    // --- vassalage ---

    /** Vassalage of this country (null = independent). */
    public Vassalage vassalage() {
        return this.vassalage;
    }

    public void vassalage(final Vassalage vassalage) {
        this.vassalage = vassalage;
    }

    /** Overlord id, null when independent. */
    public UUID overlord() {
        return this.vassalage == null ? null : this.vassalage.overlord();
    }

    // --- personal union & embassies ---

    /** Union in which this country is the partner (null otherwise; the home side is found through the manager). */
    public PersonalUnion union() {
        return this.union;
    }

    public void union(final PersonalUnion union) {
        this.union = union;
    }

    /** Whether {@code player} holds this country's throne only through a personal union (not a regular member). */
    public boolean isTitular(final UUID player) {
        return this.union != null && player != null && player.equals(this.union.monarch());
    }

    /** Members without the titular union monarch (claim limit, "last member" checks). */
    public int realMemberCount() {
        return this.members.size() - (this.union != null && this.members.containsKey(this.union.monarch()) ? 1 : 0);
    }

    /** Regular citizens for taxes and treasury division; never the titular union monarch. */
    public java.util.List<Member> realMembers() {
        return this.members.values().stream().filter(m -> !this.isTitular(m.uuid())).toList();
    }

    /** Mutable embassies abroad (manager use only). */
    public Map<UUID, Embassy> embassiesMap() {
        return this.embassies;
    }

    public Collection<Embassy> embassies() {
        return Collections.unmodifiableCollection(this.embassies.values());
    }

    public Embassy embassyIn(final UUID host) {
        return this.embassies.get(host);
    }

    // --- relations ---

    public Map<UUID, Relation> relationsMap() {
        return this.relations;
    }

    public Relation relationTo(final UUID other) {
        return this.relations.getOrDefault(other, Relation.NEUTRAL);
    }

    public Map<UUID, Long> warSinceMap() {
        return this.warSince;
    }

    public Map<UUID, Long> warEndedAtMap() {
        return this.warEndedAt;
    }
}
