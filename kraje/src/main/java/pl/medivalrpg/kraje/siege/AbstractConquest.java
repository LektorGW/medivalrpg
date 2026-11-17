// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.siege;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.geometry.Bridgeheads;
import pl.medivalrpg.kraje.geometry.ChunkGraph;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Relation;
import pl.medivalrpg.kraje.model.SpawnPoint;
import pl.medivalrpg.kraje.util.ChunkKeys;

/**
 * Sieges of NPC countries fought "in the background" (Armia {@code ai.conquest}): far from every player the battle
 * itself is computed by Armia; Kraje only checks the same rules as a real siege and applies the result exactly like
 * {@link SiegeManager}'s conquest — {@link ClaimCause#CONQUEST}, territory consistency, the capital only as the last
 * chunk, loot, fall of the country, Ludnosc citizens following the land (through the usual
 * {@code ChunkOwnerChangeEvent}). Nothing here loads a chunk or touches blocks. Announcements are left to Armia
 * (aggregated), so a background war does not flood the chat. Main thread only.
 *
 * <p>Results are short strings for the reflective bridge of {@code KrajePlugin}: {@code OK…} or
 * {@code DENIED:<reason>}.</p>
 */
public final class AbstractConquest {

    /** Why an abstract siege is not possible (pure rule check, see {@link #problem}). */
    public enum Problem {
        NONE, DISABLED, NO_COUNTRY, NOT_OWNED, OWN, NOT_AT_WAR, PLAYER_COUNTRY, SIEGE_RUNNING, CHUNK_BUSY, PLAYERS_NEAR,
        PROTECTED, NOT_ADJACENT, NOT_BORDER, BRIDGEHEAD_LIMIT, SPLITS_DEFENDER, CAPITAL_PROTECTED;

        public String code() {
            return this.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Everything the pure rule needs. */
    public record Situation(boolean enabled, boolean expeditionsEnabled, boolean allowPlayerCountries,
                            boolean attackerNpc, boolean defenderNpc, boolean atWar, boolean attackerSieging,
                            boolean chunkSieged, boolean playersNear, boolean protectedArea, boolean expedition,
                            Map<String, Set<Long>> attackerClaims, Map<String, Set<Long>> defenderClaims,
                            ChunkPos defenderCapital, ChunkPos target, boolean capitalAnytime, boolean bridgeheadFree) {
    }

    private final KrajePlugin plugin;

    public AbstractConquest(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    /** True when the target lies on the edge of the defender's land (a march reaches it from outside). */
    static boolean onEdge(final Set<Long> defender, final long key) {
        for (final long n : ChunkGraph.neighbours(key)) {
            if (!defender.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Background revolt rule (pure): like a background siege it never touches a player country (as holder or as the
     * country the chunk returns to) unless {@code siege.abstract.allow-player-countries}; disabled sieges stop it too.
     */
    public static Problem revoltProblem(final boolean siegeEnabled, final boolean allowPlayerCountries, final boolean holderNpc,
                                        final Boolean formerNpc) {
        if (!siegeEnabled) {
            return Problem.DISABLED;
        }
        if (!allowPlayerCountries && (!holderNpc || formerNpc != null && !formerNpc)) {
            return Problem.PLAYER_COUNTRY;
        }
        return Problem.NONE;
    }

    /** The rule (pure): same as a real siege plus the background conditions. */
    public static Problem problem(final Situation s) {
        if (!s.enabled()) {
            return Problem.DISABLED;
        }
        if (s.target() == null) {
            return Problem.NOT_OWNED;
        }
        final Set<Long> defLand = s.defenderClaims().getOrDefault(s.target().world(), Set.of());
        if (!defLand.contains(s.target().key())) {
            return Problem.NOT_OWNED;
        }
        if ((!s.attackerNpc() || !s.defenderNpc()) && !s.allowPlayerCountries()) {
            return Problem.PLAYER_COUNTRY;
        }
        if (!s.atWar()) {
            return Problem.NOT_AT_WAR;
        }
        if (s.attackerSieging()) {
            return Problem.SIEGE_RUNNING;
        }
        if (s.chunkSieged()) {
            return Problem.CHUNK_BUSY;
        }
        if (s.playersNear()) {
            return Problem.PLAYERS_NEAR;
        }
        if (s.protectedArea()) {
            return Problem.PROTECTED;
        }
        final boolean adjacent = ChunkGraph.isAdjacent(s.attackerClaims().getOrDefault(s.target().world(), Set.of()), s.target().key());
        final boolean landing = !adjacent;
        if (landing) {
            if (!s.expedition() || !s.expeditionsEnabled()) {
                return Problem.NOT_ADJACENT;
            }
            if (!onEdge(defLand, s.target().key())) {
                return Problem.NOT_BORDER;
            }
            if (!s.bridgeheadFree()) {
                return Problem.BRIDGEHEAD_LIMIT;
            }
        }
        final ConquestPlan.Outcome plan = ConquestPlan.plan(s.attackerClaims(), s.defenderClaims(), s.defenderCapital(), s.target(),
            true, s.capitalAnytime(), landing);
        return switch (plan.problem()) {
            case NONE -> Problem.NONE;
            case SPLITS_DEFENDER -> Problem.SPLITS_DEFENDER;
            case CAPITAL_PROTECTED -> Problem.CAPITAL_PROTECTED;
            case NOT_ADJACENT -> Problem.NOT_ADJACENT;
            case NOT_OWNED -> Problem.NOT_OWNED;
        };
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    private boolean playersNear(final ChunkPos pos) {
        final int radius = this.cfg().abstractPlayerBlockChunks;
        final World w = Bukkit.getWorld(pos.world());
        if (w == null) {
            return false;
        }
        for (final Player p : w.getPlayers()) {
            final int dx = Math.abs((p.getLocation().getBlockX() >> 4) - pos.x());
            final int dz = Math.abs((p.getLocation().getBlockZ() >> 4) - pos.z());
            if (Math.max(dx, dz) <= radius) {
                return true;
            }
        }
        return false;
    }

    private Situation situation(final Country attacker, final Country defender, final ChunkPos pos, final boolean expedition) {
        final KrajeConfig cfg = this.cfg();
        final SiegeManager sieges = this.plugin.sieges();
        return new Situation(cfg.siegeEnabled && cfg.abstractEnabled, cfg.abstractExpeditions, cfg.abstractAllowPlayerCountries,
            attacker.npc(), defender.npc(), attacker.relationTo(defender.id()) == Relation.WAR,
            sieges.siegeOf(attacker.id()) != null, sieges.siegeAt(pos) != null, this.playersNear(pos),
            this.plugin.protectedAreas().at(pos.world(), pos.x(), pos.z()).isPresent(), expedition,
            attacker.claimsMap(), defender.claimsMap(), defender.capital(), pos, cfg.siegeCapitalAnytime,
            Bridgeheads.canEstablish(attacker, pos, cfg.landingMaxBridgeheads));
    }

    private static String denied(final Problem p) {
        return "DENIED:" + p.code();
    }

    /**
     * Checks the abstract siege of chunk ({@code cx}, {@code cz}); with {@code charge} the attacker also pays the siege
     * cost (+ the expedition surcharge for a non-adjacent target) like a real siege, logged in its treasury history.
     */
    public String begin(final UUID attackerId, final String world, final int cx, final int cz, final boolean expedition, final boolean charge) {
        final Country attacker = this.plugin.manager().get(attackerId);
        if (attacker == null || world == null || Bukkit.getWorld(world) == null) {
            return denied(Problem.NO_COUNTRY);
        }
        final ChunkPos pos = new ChunkPos(world, cx, cz);
        final Country defender = this.plugin.manager().countryAt(pos);
        if (defender == null) {
            return denied(Problem.NOT_OWNED);
        }
        if (defender == attacker) {
            return denied(Problem.OWN);
        }
        final Problem p = problem(this.situation(attacker, defender, pos, expedition));
        if (p != Problem.NONE) {
            return denied(p);
        }
        if (charge) {
            final boolean landing = !ChunkGraph.isAdjacent(attacker.claimsIn(world), pos.key());
            final long cost = this.cfg().siegeCost + (landing ? this.cfg().abstractExpeditionExtraCost : 0);
            try {
                this.plugin.treasury().charge(attacker, cost, "siege", landing ? "SI: wyprawa" : "SI: oblężenie w tle");
            } catch (final KrajeException e) {
                return "DENIED:funds";
            }
            return "OK:" + cost;
        }
        return "OK:0";
    }

    /**
     * Applies a won abstract siege. Returns {@code OK:CONQUERED:<loot>}, {@code OK:BRIDGEHEAD:<loot>},
     * {@code OK:RAZED:<loot>} or {@code OK:FELL:<loot>} (the defender lost its last chunk and is disbanded).
     */
    public String conquer(final UUID attackerId, final String world, final int cx, final int cz, final boolean expedition) {
        final String check = this.begin(attackerId, world, cx, cz, expedition, false);
        if (!check.startsWith("OK")) {
            return check;
        }
        final Country attacker = this.plugin.manager().get(attackerId);
        final ChunkPos pos = new ChunkPos(world, cx, cz);
        final Country defender = this.plugin.manager().countryAt(pos);
        final boolean landing = !ChunkGraph.isAdjacent(attacker.claimsIn(world), pos.key());
        final ConquestPlan.Outcome plan = ConquestPlan.plan(attacker.claimsMap(), defender.claimsMap(), defender.capital(), pos,
            true, this.cfg().siegeCapitalAnytime, landing);
        if (!plan.possible()) {
            return "DENIED:" + plan.problem().name().toLowerCase(Locale.ROOT);
        }
        this.plugin.manager().transferChunk(defender, plan.toAttacker() ? attacker : null, world, pos.key(), ClaimCause.CONQUEST, landing);
        final long loot = this.plugin.treasury().lootChunk(attacker, defender);
        String kind = plan.toAttacker() ? (landing ? "BRIDGEHEAD" : "CONQUERED") : "RAZED";
        if (plan.defenderFalls()) {
            kind = "FELL";
            final List<Member> former = List.copyOf(defender.members());
            this.plugin.treasury().lootFall(attacker, defender);
            this.plugin.actions().disband(defender, ClaimCause.CONQUEST);
            if (this.plugin.manager().get(attacker.id()) != null) {
                this.plugin.actions().inviteFormerCitizens(attacker, defender, former);
            }
        } else if (plan.newCapital() != null) {
            this.plugin.manager().setCapital(defender, plan.newCapital(), spawnWithoutLoading(plan.newCapital(), defender.spawn()));
        }
        this.plugin.getLogger().info("Oblężenie w tle: " + attacker.name() + " zdobywa " + cx + "," + cz + " od " + defender.name()
            + " (" + kind + ", łup " + loot + " md)");
        return "OK:" + kind + ":" + loot;
    }

    /**
     * Revolt on recently conquered land: the chunk returns to {@code formerId} (when it still exists and borders the chunk)
     * or, with {@code formerId == null}, becomes wilderness. The holder's land must stay connected; never its capital.
     */
    public String revolt(final UUID holderId, final String world, final int cx, final int cz, final UUID formerId) {
        final Country holder = this.plugin.manager().get(holderId);
        if (holder == null || world == null || Bukkit.getWorld(world) == null || !this.cfg().abstractEnabled) {
            return denied(Problem.NO_COUNTRY);
        }
        final ChunkPos pos = new ChunkPos(world, cx, cz);
        if (this.plugin.manager().countryAt(pos) != holder) {
            return denied(Problem.NOT_OWNED);
        }
        if (pos.equals(holder.capital()) || holder.claimCount() <= 1) {
            return denied(Problem.CAPITAL_PROTECTED);
        }
        if (this.plugin.sieges().siegeAt(pos) != null) {
            return denied(Problem.CHUNK_BUSY);
        }
        if (this.playersNear(pos)) {
            return denied(Problem.PLAYERS_NEAR);
        }
        final Set<Long> land = holder.claimsIn(world);
        final Long capitalKey = holder.capital().world().equals(world) ? holder.capital().key() : null;
        if (!ChunkGraph.staysConnected(land, pos.key(), capitalKey)) {
            return denied(Problem.SPLITS_DEFENDER);
        }
        Country former = formerId == null ? null : this.plugin.manager().get(formerId);
        if (former != null && (former == holder || !ChunkGraph.isAdjacent(former.claimsIn(world), pos.key()))) {
            former = null;
        }
        if (former == null && formerId != null) {
            return denied(Problem.NOT_ADJACENT);
        }
        final Problem rule = revoltProblem(this.cfg().siegeEnabled, this.cfg().abstractAllowPlayerCountries, holder.npc(),
            former == null ? null : former.npc());
        if (rule != Problem.NONE) {
            return denied(rule);
        }
        this.plugin.manager().transferChunk(holder, former, world, pos.key(), ClaimCause.CONQUEST, false);
        this.plugin.getLogger().info("Bunt w tle: " + holder.name() + " traci " + cx + "," + cz
            + (former == null ? " (dzicz)" : " na rzecz " + former.name()));
        return former == null ? "OK:WILD" : "OK:RETURNED";
    }

    /** New capital spawn without loading the chunk: the highest block only when loaded, else the old spawn height. */
    private static SpawnPoint spawnWithoutLoading(final ChunkPos pos, final SpawnPoint old) {
        final World w = Bukkit.getWorld(pos.world());
        final int x = pos.centerBlockX();
        final int z = pos.centerBlockZ();
        final double y = w != null && w.isChunkLoaded(pos.x(), pos.z()) ? w.getHighestBlockYAt(x, z) + 1
            : old != null ? old.y() : 80;
        return new SpawnPoint(pos.world(), x + 0.5, y, z + 0.5, 0f, 0f);
    }

    /** Diagnostic for logs/tests. */
    static String key(final long k) {
        return ChunkKeys.x(k) + "," + ChunkKeys.z(k);
    }
}
