// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kraje.economy;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.medivalrpg.kraje.KrajePlugin;
import pl.medivalrpg.kraje.api.ClaimCause;
import pl.medivalrpg.kraje.api.KrajeResult;
import pl.medivalrpg.kraje.api.event.TreasuryTickEvent;
import pl.medivalrpg.kraje.config.KrajeConfig;
import pl.medivalrpg.kraje.manager.KrajeException;
import pl.medivalrpg.kraje.manager.Notify;
import pl.medivalrpg.kraje.model.ChunkPos;
import pl.medivalrpg.kraje.model.Country;
import pl.medivalrpg.kraje.model.Member;
import pl.medivalrpg.kraje.model.Rank;
import pl.medivalrpg.kraje.model.TreasuryEntry;

/**
 * Country treasuries (Skarbiec account {@code country:<id>}), costs, player taxes, daily upkeep and bankruptcy.
 * Everything here is a no-op while the economy is disabled (no Skarbiec): costs are 0, nothing is collected.
 * Main thread only.
 */
public final class TreasuryService {

    /** Where a deposit comes from. */
    public enum Source { AUTO, ACCOUNT, COINS }

    private final KrajePlugin plugin;

    public TreasuryService(final KrajePlugin plugin) {
        this.plugin = plugin;
    }

    private Economy eco() {
        return this.plugin.economy();
    }

    private KrajeConfig cfg() {
        return this.plugin.cfg();
    }

    public boolean active() {
        return this.eco().enabled();
    }

    public long balance(final Country c) {
        return this.active() ? this.eco().balance(c.treasuryAccount()) : 0;
    }

    public String format(final long amount) {
        return this.eco().format(amount);
    }

    /** Price of the next chunk for this country (0 without economy). */
    public long claimCost(final Country c) {
        return this.active() ? Costs.claimCost(this.cfg().claimCostBase, this.cfg().claimCostPerChunk, c.claimCount()) : 0;
    }

    public long upkeep(final Country c) {
        return this.active() ? Costs.upkeep(this.cfg().upkeepPerChunk, this.cfg().upkeepFreeChunks, c.claimCount()) : 0;
    }

    /** Throws {@code treasury-insufficient} unless the treasury holds {@code amount}. */
    public void requireFunds(final Country c, final long amount) {
        if (!this.active() || amount <= 0) {
            return;
        }
        final long balance = this.balance(c);
        if (balance < amount) {
            throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "treasury-insufficient",
                Notify.unparsed("cost", this.format(amount)), Notify.unparsed("balance", this.format(balance)));
        }
    }

    /** Takes {@code amount} from the treasury (logged as {@code type}); throws when it cannot. No-op without economy. */
    public void charge(final Country c, final long amount, final String type, final String actor) {
        if (!this.active() || amount <= 0) {
            return;
        }
        this.requireFunds(c, amount);
        if (!this.eco().withdraw(c.treasuryAccount(), amount, "Kraje: " + type + " (" + c.name() + ")")) {
            throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "treasury-insufficient",
                Notify.unparsed("cost", this.format(amount)), Notify.unparsed("balance", this.format(this.balance(c))));
        }
        this.log(c, type, -amount, actor);
    }

    /** Takes a fee from a player: bank account first, then coins in the inventory. No-op without economy. */
    public void chargePlayer(final Player player, final long amount, final String reason) {
        if (!this.active() || amount <= 0) {
            return;
        }
        final String account = Economy.playerAccount(player.getUniqueId());
        if (this.eco().withdraw(account, amount, "Kraje: " + reason)) {
            return;
        }
        if (this.eco().takeCoins(player, amount)) {
            return;
        }
        throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "player-insufficient",
            Notify.unparsed("cost", this.format(amount)));
    }

    public void log(final Country c, final String type, final long amount, final String actor) {
        this.plugin.manager().addTreasuryEntry(c,
            new TreasuryEntry(System.currentTimeMillis(), type, amount, actor == null ? "" : actor), this.cfg().treasuryLogSize);
    }

    /** Player pays into the treasury. Returns the source actually used. */
    public Source deposit(final Player player, final Country c, final long amount, final Source source) {
        this.requireActive();
        final String account = Economy.playerAccount(player.getUniqueId());
        final String reason = "Kraje: wpłata do skarbca " + c.name();
        Source used = null;
        if (source != Source.COINS && this.eco().transfer(account, c.treasuryAccount(), amount, reason)) {
            used = Source.ACCOUNT;
        } else if (source != Source.ACCOUNT && this.eco().takeCoins(player, amount)) {
            if (this.eco().deposit(c.treasuryAccount(), amount, reason + " (monety)")) {
                used = Source.COINS;
            } else {
                // Should never happen; give the value back to the player's account rather than losing it.
                if (!this.eco().deposit(account, amount, "Kraje: zwrot nieudanej wpłaty")) {
                    this.plugin.getLogger().severe("Wpłata monet do skarbca " + c.name() + ": monety (" + amount + " md) zabrane graczowi "
                        + player.getName() + ", a zwrot na jego konto NIE powiódł się — wyrównać ręcznie z world:payments");
                }
                this.plugin.getLogger().warning("Nie udało się zaksięgować wpłaty monet do skarbca " + c.name());
            }
        }
        if (used == null) {
            throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "deposit-insufficient",
                Notify.unparsed("amount", this.format(amount)));
        }
        this.log(c, "deposit", amount, player.getName());
        return used;
    }

    /** Ruler takes money from the treasury to their bank account. */
    public void withdraw(final Player player, final Country c, final long amount) {
        this.withdraw(player.getUniqueId(), player.getName(), c, amount);
    }

    /** Same as {@link #withdraw(Player, Country, long)} for a player who may be offline (API / web panel). */
    public void withdraw(final java.util.UUID player, final String playerName, final Country c, final long amount) {
        this.requireActive();
        this.requireFunds(c, amount);
        if (!this.eco().transfer(c.treasuryAccount(), Economy.playerAccount(player), amount,
            "Kraje: wypłata ze skarbca " + c.name())) {
            throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "treasury-insufficient",
                Notify.unparsed("cost", this.format(amount)), Notify.unparsed("balance", this.format(this.balance(c))));
        }
        this.log(c, "withdraw", -amount, playerName);
    }

    /** A player (possibly offline) pays into the treasury from their bank account only (API / web panel). */
    public void depositFromAccount(final java.util.UUID player, final String playerName, final Country c, final long amount) {
        this.requireActive();
        if (!this.eco().transfer(Economy.playerAccount(player), c.treasuryAccount(), amount, "Kraje: wpłata do skarbca " + c.name())) {
            throw new KrajeException(KrajeResult.Status.INSUFFICIENT_FUNDS, "deposit-insufficient",
                Notify.unparsed("amount", this.format(amount)));
        }
        this.log(c, "deposit", amount, playerName);
    }

    public void requireActive() {
        if (!this.active()) {
            throw new KrajeException(KrajeResult.Status.INVALID, "economy-disabled");
        }
    }

    // ------------------------------------------------------------------ transfers between countries

    /**
     * Moves up to {@code amount} from {@code from}'s treasury to {@code to}'s (never more than {@code from} holds) and logs
     * it in both histories as {@code type} (the other country as actor). Returns the amount moved (0 without economy).
     */
    public long transferBetween(final Country from, final Country to, final long amount, final String type) {
        if (!this.active() || amount <= 0) {
            return 0;
        }
        final long moved = Math.min(amount, this.balance(from));
        if (moved <= 0 || !this.eco().transfer(from.treasuryAccount(), to.treasuryAccount(), moved,
            "Kraje: " + type + " " + from.name() + " → " + to.name())) {
            return 0;
        }
        this.log(from, type, -moved, to.name());
        this.log(to, type, moved, from.name());
        return moved;
    }

    // ------------------------------------------------------------------ spoils of war

    /** A chunk was conquered: the winner takes {@code conquest.loot-per-chunk} of the loser's current treasury. */
    public long lootChunk(final Country winner, final Country loser) {
        final long loot = this.loot(winner, loser, this.cfg().lootPerChunk);
        if (loot > 0) {
            final TagResolver r = TagResolver.resolver(Notify.pair(winner, loser), Notify.unparsed("amount", this.format(loot)));
            Notify.members(this.plugin.messages(), winner, "loot-chunk-winner", r);
            Notify.members(this.plugin.messages(), loser, "loot-chunk-loser", r);
        }
        return loot;
    }

    /**
     * The loser fell (last chunk conquered): the winner takes {@code conquest.loot-on-fall} of what is left, the rest is
     * destroyed or paid out to the former player members ({@code conquest.fall-remainder}). Call before disbanding.
     */
    public void lootFall(final Country winner, final Country loser) {
        if (!this.active()) {
            return;
        }
        final long loot = this.loot(winner, loser, this.cfg().lootOnFall);
        if (loot > 0) {
            final TagResolver r = TagResolver.resolver(Notify.pair(winner, loser), Notify.unparsed("amount", this.format(loot)));
            Notify.members(this.plugin.messages(), winner, "loot-fall-winner", r);
            Notify.members(this.plugin.messages(), loser, "loot-fall-loser", r);
        }
        if (this.cfg().fallRemainder == KrajeConfig.FallRemainder.MEMBERS) {
            this.payOutToMembers(loser, "fall");
        } else {
            this.destroyRest(loser, "fall");
        }
    }

    /**
     * A country is disbanded without a war ({@code /kraj rozwiaz}, admin, API): its treasury must not stay orphaned.
     * {@code disband.treasury}: {@code ruler} pays it to the ruler's bank account, {@code members} splits it among the
     * player members, {@code destroy} burns it; NPC countries (no player to pay) always burn it. Call before disbanding.
     */
    public void settleDisband(final Country c) {
        if (!this.active() || this.balance(c) <= 0) {
            return;
        }
        final KrajeConfig.DisbandTreasury mode = this.cfg().disbandTreasury;
        if (c.npc() || mode == KrajeConfig.DisbandTreasury.DESTROY) {
            this.destroyRest(c, "disband");
            return;
        }
        if (mode == KrajeConfig.DisbandTreasury.MEMBERS) {
            this.payOutToMembers(c, "disband");
            return;
        }
        final Member ruler = c.rulerMember();
        if (ruler == null) {
            this.destroyRest(c, "disband");
            return;
        }
        final long amount = this.balance(c);
        if (this.eco().transfer(c.treasuryAccount(), Economy.playerAccount(ruler.uuid()), amount,
            "Kraje: skarbiec rozwiązanego kraju " + c.name())) {
            Notify.members(this.plugin.messages(), c, "disband-treasury-ruler", Notify.country(c),
                Notify.unparsed("amount", this.format(amount)), Notify.unparsed("player", ruler.name()));
        } else {
            this.destroyRest(c, "disband");
        }
    }

    /** {@link Spoils#take} from the loser to the winner, logged in both treasury histories. */
    private long loot(final Country winner, final Country loser, final double fraction) {
        if (!this.active()) {
            return 0;
        }
        final long moved = Spoils.take(this.eco(), loser.treasuryAccount(), winner.treasuryAccount(), fraction,
            "Kraje: łupy " + loser.name() + " → " + winner.name());
        if (moved > 0) {
            this.log(loser, "loot", -moved, winner.name());
            this.log(winner, "loot", moved, loser.name());
        }
        return moved;
    }

    /** Splits the whole treasury equally among the player members' bank accounts (none → destroyed). */
    private void payOutToMembers(final Country c, final String why) {
        final long total = this.balance(c);
        if (total <= 0) {
            return;
        }
        final List<Member> members = c.realMembers();
        if (members.isEmpty()) {
            this.destroyRest(c, why);
            return;
        }
        final long[] paid = Spoils.payOut(this.eco(), c.treasuryAccount(),
            members.stream().map(m -> Economy.playerAccount(m.uuid())).toList(), "Kraje: podział skarbca " + c.name());
        for (int i = 0; i < members.size(); i++) {
            final Player online = paid[i] > 0 ? Bukkit.getPlayer(members.get(i).uuid()) : null;
            if (online != null) {
                this.plugin.messages().send(online, "treasury-share-received", Notify.country(c),
                    Notify.unparsed("amount", this.format(paid[i])), Notify.unparsed("total", this.format(total)),
                    Notify.unparsed("count", members.size()));
            }
        }
        this.destroyRest(c, why); // whatever could not be paid out
    }

    /** Burns what is left in the treasury (Skarbiec withdrawal to nobody). */
    private void destroyRest(final Country c, final String why) {
        final long rest = Spoils.burn(this.eco(), c.treasuryAccount(), "Kraje: skarbiec " + c.name() + " przepada (" + why + ")");
        if (rest > 0) {
            Notify.members(this.plugin.messages(), c, "treasury-destroyed", Notify.country(c), Notify.unparsed("amount", this.format(rest)));
            this.plugin.getLogger().info("Skarbiec " + c.name() + " przepada: " + rest + " md (" + why + ")");
        }
    }

    // ------------------------------------------------------------------ income estimate (tribute)

    /** Expected daily tax income: player taxes (as charged at the next settlement) + NPC taxes from Ludnosc. */
    public long estimatedDailyIncome(final Country c) {
        final KrajeConfig cfg = this.cfg();
        final long tax = Math.min(c.taxPerCitizen(), cfg.taxMax);
        long players = 0;
        if (tax > 0) {
            for (final Member m : c.realMembers()) {
                if (!(cfg.taxExemptRuler && m.rank() == Rank.RULER)) {
                    players += tax;
                }
            }
        }
        return players + this.plugin.integrations().npcDailyTax(c.id()).orElse(0L);
    }

    // ------------------------------------------------------------------ daily tick

    /** Called every minute and at startup: runs every daily tick that is due (catching up after restarts). */
    public void checkSchedule() {
        final long now = System.currentTimeMillis();
        final KrajeConfig cfg = this.cfg();
        final long last = this.plugin.manager().lastDailyTick();
        if (last <= 0) {
            // First start with the economy feature: begin counting from the last scheduled time, charge nothing.
            this.plugin.manager().setLastDailyTick(DailySchedule.latestScheduled(now, cfg.dailyTickTime, ZoneId.systemDefault()));
            return;
        }
        final List<Long> due = DailySchedule.dueTicks(last, now, cfg.dailyTickTime, ZoneId.systemDefault(), cfg.maxCatchUpDays);
        final long latest = DailySchedule.latestScheduled(now, cfg.dailyTickTime, ZoneId.systemDefault());
        if (due.isEmpty()) {
            if (latest > last) {
                this.plugin.manager().setLastDailyTick(latest); // catch-up disabled (max-catch-up-days: 0)
            }
            return;
        }
        if (due.size() > 1) {
            this.plugin.getLogger().info("Nadrabiam dzienne rozliczenia krajów po przerwie: " + due.size());
        }
        for (final long tick : due) {
            // at most once: the day is marked and checkpointed before any money moves (the bank ledger is durable on
            // its own; a crash in between must lose the rest of a day rather than charge taxes/upkeep twice)
            this.plugin.manager().setLastDailyTick(tick);
            this.plugin.saveRoyalCheckpoint().whenComplete((ok, error) -> {
                if (error != null) {
                    this.plugin.getLogger().log(Level.WARNING, "Zapis znacznika dziennego rozliczenia nieudany", error);
                }
            });
            this.runTick();
        }
    }

    /** One daily settlement of all countries (also {@code /kraj admin tick}). Does nothing without economy. */
    public void runTick() {
        if (!this.active()) {
            return;
        }
        for (final Country c : this.plugin.manager().countriesSnapshot()) {
            if (this.plugin.manager().get(c.id()) == null) {
                continue;
            }
            try {
                this.tickCountry(c);
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Błąd dziennego rozliczenia kraju " + c.name(), e);
            }
        }
        this.plugin.manager().markChanged();
    }

    private void tickCountry(final Country c) {
        final KrajeConfig cfg = this.cfg();
        final Economy eco = this.eco();
        final TagResolver country = Notify.country(c);

        // 1. player taxes (NPC citizens are taxed by Ludnosc)
        long taxes = 0;
        final long tax = Math.min(c.taxPerCitizen(), cfg.taxMax);
        for (final Member m : c.realMembers()) {
            if (cfg.taxExemptRuler && m.rank() == Rank.RULER) {
                continue;
            }
            if (tax <= 0 && m.taxDebt() <= 0) {
                continue;
            }
            final String account = Economy.playerAccount(m.uuid());
            final long due = tax + m.taxDebt();
            final long paid = Costs.taxPayment(tax, m.taxDebt(), eco.balance(account));
            if (paid > 0 && !eco.transfer(account, c.treasuryAccount(), paid, "Kraje: podatek " + c.name())) {
                continue; // balance changed meanwhile — try again next day
            }
            taxes += paid;
            m.taxDebt(due - paid);
            m.debtDays(m.taxDebt() > 0 ? m.debtDays() + 1 : 0);
            final Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) {
                if (m.taxDebt() > 0) {
                    this.plugin.messages().send(online, "tax-unpaid", country, Notify.unparsed("paid", this.format(paid)),
                        Notify.unparsed("debt", this.format(m.taxDebt())), Notify.unparsed("days", m.debtDays()));
                } else if (paid > 0) {
                    this.plugin.messages().send(online, "tax-paid", country, Notify.unparsed("amount", this.format(paid)));
                }
            }
        }
        if (taxes > 0) {
            c.treasuryLog().addFirst(new TreasuryEntry(System.currentTimeMillis(), "tax", taxes, ""));
        }

        // 2. upkeep
        final long upkeep = this.upkeep(c);
        boolean bankrupt = false;
        long upkeepPaid = 0;
        if (upkeep > 0) {
            if (eco.withdraw(c.treasuryAccount(), upkeep, "Kraje: utrzymanie " + c.name())) {
                upkeepPaid = upkeep;
                c.unpaidUpkeepDays(0);
                c.treasuryLog().addFirst(new TreasuryEntry(System.currentTimeMillis(), "upkeep", -upkeep, ""));
            } else {
                bankrupt = true;
                c.unpaidUpkeepDays(c.unpaidUpkeepDays() + 1);
            }
        } else {
            c.unpaidUpkeepDays(0);
        }

        // 3. tribute of a vassal: share of today's tax income (players + NPC citizens), at least the minimum
        if (c.vassalage() != null) {
            this.plugin.vassals().settleTribute(c, taxes + this.plugin.integrations().npcDailyTax(c.id()).orElse(0L));
        }
        while (c.treasuryLog().size() > cfg.treasuryLogSize) {
            c.treasuryLog().removeLast();
        }

        if (bankrupt) {
            final int days = c.unpaidUpkeepDays();
            if (days > cfg.graceDays) {
                final List<ChunkPos> lost = BankruptcyPlanner.chunksToLose(c.claimsMap(), c.capital(), cfg.bankruptcyChunksPerDay);
                if (!lost.isEmpty()) {
                    this.plugin.manager().unclaimAll(c, lost, ClaimCause.BANKRUPTCY);
                    Notify.members(this.plugin.messages(), c, "bankruptcy-lost", country,
                        Notify.unparsed("count", lost.size()), Notify.unparsed("upkeep", this.format(upkeep)));
                    this.plugin.getLogger().info("Bankructwo: " + c.name() + " traci " + lost.size() + " chunk(ów).");
                }
            } else {
                Notify.members(this.plugin.messages(), c, "bankruptcy-warning", country,
                    Notify.unparsed("upkeep", this.format(upkeep)), Notify.unparsed("days", days),
                    Notify.unparsed("grace", cfg.graceDays), Notify.unparsed("left", Math.max(0, cfg.graceDays - days + 1)));
            }
        }
        Bukkit.getPluginManager().callEvent(new TreasuryTickEvent(c.id(), taxes, upkeepPaid, bankrupt));
    }

    /** Reminder for a joining player with unpaid taxes. */
    public void remindDebt(final Player player) {
        if (!this.active()) {
            return;
        }
        final Country c = this.plugin.manager().countryOf(player.getUniqueId());
        final Member m = c == null ? null : c.member(player.getUniqueId());
        if (m != null && m.taxDebt() > 0) {
            this.plugin.messages().send(player, "tax-debt-reminder", Notify.country(c),
                Notify.unparsed("debt", this.format(m.taxDebt())), Notify.unparsed("days", m.debtDays()));
        }
    }

    /** Parses a user amount ({@code 150}, {@code 3zl}, {@code 1zł 5sr}). */
    public long parseAmount(final String input) {
        final long value = this.eco().parse(input.toLowerCase(Locale.ROOT)).orElse(0L);
        if (value <= 0) {
            throw new KrajeException(KrajeResult.Status.INVALID, "invalid-amount", Notify.unparsed("input", input));
        }
        return value;
    }

    /** Debtors of a country (for {@code /kraj podatek}). */
    public List<Member> debtors(final Country c) {
        return c.members().stream().filter(m -> m.taxDebt() > 0)
            .sorted((a, b) -> Integer.compare(b.debtDays(), a.debtDays())).toList();
    }

    public boolean canKickForTaxes(final Member m) {
        return m.taxDebt() > 0 && m.debtDays() >= this.cfg().taxKickAfterDays;
    }
}
