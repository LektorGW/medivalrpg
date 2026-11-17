// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.skarbiec.ledger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import pl.medivalrpg.skarbiec.api.TxResult;

/**
 * In-memory bank ledger: the single source of truth for account balances (pure Java, no Bukkit).
 *
 * <p>Every operation runs under one monitor, so it is atomic and linearizable from any thread (a transfer can
 * never be observed half done, balances never go negative, money is never created by a race). Each successful
 * operation gets the next sequence number and is handed to the {@link Sink} <i>while the lock is held</i>, so
 * the journal sees operations in exactly the order they were applied. The sink must only enqueue (no IO).</p>
 */
public final class Ledger {

    /** Receives committed operations and snapshot requests, in commit order, under the ledger lock. */
    public interface Sink {
        void record(TxRecord record);

        void snapshot(Map<String, Long> balances, long seq);

        Sink NONE = new Sink() {
            @Override
            public void record(final TxRecord record) {
            }

            @Override
            public void snapshot(final Map<String, Long> balances, final long seq) {
            }
        };
    }

    /** {@code <namespace>:<id>}, e.g. {@code player:<uuid>}, {@code country:<uuid>}, {@code ludnosc:budowa}. */
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_\\-]{1,24}:[A-Za-z0-9_\\-.:]{1,64}");

    private final Map<String, Long> balances = new HashMap<>();
    private final LongSupplier clock;
    private Sink sink;
    private long seq;
    private long snapshotSeq = -1;
    private boolean closedSupply;
    private java.util.UUID supplyOwner;
    private final java.util.List<java.util.function.Consumer<TxRecord>> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private java.util.List<String> refundPrefixes = java.util.List.of();

    /**
     * Observers see every committed operation in commit order, under the ledger lock (like the sink): they must only
     * update memory, never call back into the ledger or do IO.
     */
    public void addObserver(final java.util.function.Consumer<TxRecord> observer) {
        this.observers.add(java.util.Objects.requireNonNull(observer));
    }

    /**
     * Refunds paid from PAYMENTS whose reason starts with one of these prefixes may be topped up from HOUSEHOLDS when
     * PAYMENTS alone is short (labour already passed on to the citizens) — so the labour share can never block a refund.
     */
    public synchronized void setRefundPrefixes(final java.util.List<String> prefixes) {
        this.refundPrefixes = prefixes == null ? java.util.List.of() : java.util.List.copyOf(prefixes);
    }

    private boolean refund(final String reason) {
        if (reason == null) {
            return false;
        }
        for (final String p : this.refundPrefixes) {
            if (!p.isEmpty() && reason.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Enable only after a separately audited migration; never changes a balance. */
    public synchronized void enableClosedSupply(long cap,java.util.UUID manager) {
        if(closedSupply || !WorldMoney.MANAGER.equals(manager))throw new IllegalArgumentException("Nieprawidłowy zarządca Skarbu Świata");
        WorldMoney.validate(balances,cap);closedSupply=true;supplyOwner=manager;
    }
    public synchronized boolean closedSupply(){return closedSupply;}
    private boolean protectedWorldAccount(String account){return WorldMoney.RESERVE.equals(account) || WorldMoney.CASH.equals(account);}

    /** Explicit owner-only reserve allocation. OP, console and country rank are not substitutes for UUID. */
    public synchronized TxResult allocateWorldMoney(java.util.UUID actor,String target,long amount,String reason) {
        if(!closedSupply || actor==null || !actor.equals(supplyOwner) || protectedWorldAccount(target))return TxResult.INVALID_ACCOUNT;
        return move(WorldMoney.RESERVE,target,amount,reason);
    }
    /**
     * Owner-only allocation of the reserve to several accounts in ONE atomic step: everything is validated first
     * (owner, accounts, amounts, overflow, the reserve covers the whole sum) and only then all moves are applied under
     * this lock, so either every target gets its amount or nothing moves. Each move is journaled with its own reason.
     *
     * @param targets account → amount, in the order of the moves (accounts distinct, none protected)
     */
    public synchronized TxResult allocateWorldMoneyAll(java.util.UUID actor,java.util.LinkedHashMap<String,Long> targets,
                                                       java.util.function.Function<String,String> reason) {
        if(!closedSupply || actor==null || !actor.equals(supplyOwner) || targets==null || targets.isEmpty())return TxResult.INVALID_ACCOUNT;
        long total=0;
        for(var e:targets.entrySet()){
            String account=e.getKey();
            if(!validAccount(account) || protectedWorldAccount(account) || WorldMoney.RESERVE.equals(account))return TxResult.INVALID_ACCOUNT;
            long amount=e.getValue()==null?0:e.getValue();
            if(amount<=0)return TxResult.INVALID_AMOUNT;
            try{total=Math.addExact(total,amount);Math.addExact(this.balances.getOrDefault(account,0L),amount);}
            catch(ArithmeticException x){return TxResult.INVALID_AMOUNT;}
        }
        if(this.balances.getOrDefault(WorldMoney.RESERVE,0L)<total)return TxResult.INSUFFICIENT_FUNDS;
        for(var e:targets.entrySet()){
            TxResult r=move(WorldMoney.RESERVE,e.getKey(),e.getValue(),reason.apply(e.getKey()));
            if(!r.ok())throw new IllegalStateException("Przelew zbiorczy przerwany mimo sprawdzenia: "+r); // cannot happen: validated above
        }
        return TxResult.OK;
    }
    /** An existing zero balance is a durable marker: automatic construction budgets are never refilled. */
    public synchronized TxResult allocateRailBudgetOnce(java.util.UUID actor,String target,long amount,String reason) {
        if(!closedSupply || actor==null || !actor.equals(supplyOwner) || target==null || !target.matches("kolej:budzet_[a-f0-9]{16}"))return TxResult.INVALID_ACCOUNT;
        if(amount<=0 || amount>WorldMoney.CAP)return TxResult.INVALID_AMOUNT;
        if(balances.containsKey(target))return TxResult.OK;
        return allocateWorldMoney(actor,target,amount,reason);
    }
    public synchronized TxResult returnWorldMoney(java.util.UUID actor,String source,long amount,String reason) {
        if(!closedSupply || actor==null || !actor.equals(supplyOwner) || protectedWorldAccount(source))return TxResult.INVALID_ACCOUNT;
        return move(source,WorldMoney.RESERVE,amount,reason);
    }
    /** Cash conversion uses the same atomic journaled transfers as bank money. */
    public synchronized TxResult issueCash(String source,long amount,String reason) {
        if(!closedSupply || protectedWorldAccount(source))return TxResult.INVALID_ACCOUNT;
        return move(source,WorldMoney.CASH,amount,reason);
    }
    public synchronized TxResult redeemCash(String target,long amount,String reason) {
        if(!closedSupply || protectedWorldAccount(target))return TxResult.INVALID_ACCOUNT;
        return move(WorldMoney.CASH,target,amount,reason);
    }

    public Ledger(final Sink sink, final LongSupplier clock) {
        this.sink = Objects.requireNonNull(sink);
        this.clock = Objects.requireNonNull(clock);
    }

    public Ledger(final Sink sink) {
        this(sink, System::currentTimeMillis);
    }

    public static boolean validAccount(final String account) {
        return account != null && ACCOUNT.matcher(account).matches();
    }

    public synchronized void setSink(final Sink sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    /** Replaces the whole state (used once while loading from disk). */
    public synchronized void restore(final Map<String, Long> state, final long lastSeq) {
        if(closedSupply)throw new IllegalStateException("Nie można zastąpić aktywnej zamkniętej księgi");
        this.balances.clear();
        this.balances.putAll(state);
        this.seq = lastSeq;
        this.snapshotSeq = -1;
    }

    public synchronized long seq() {
        return this.seq;
    }

    public synchronized long balance(final String account) {
        if (!validAccount(account)) {
            return 0;
        }
        return this.balances.getOrDefault(account, 0L);
    }

    public synchronized boolean exists(final String account) {
        return this.balances.containsKey(account);
    }

    /** Copy of all balances. */
    public synchronized Map<String, Long> balances() {
        return new HashMap<>(this.balances);
    }

    /** Sum of all balances (money in the bank), saturating at Long.MAX_VALUE. */
    public synchronized long totalMoney() {
        long sum = 0;
        for (final long v : this.balances.values()) {
            sum = sum > Long.MAX_VALUE - v ? Long.MAX_VALUE : sum + v;
        }
        return sum;
    }

    public synchronized TxResult deposit(final String account, final long amount, final String reason) {
        if(closedSupply){
            if(protectedWorldAccount(account))return TxResult.INVALID_ACCOUNT;
            String source=reason!=null && reason.startsWith("Ludnosc: podatek od obywateli NPC")?WorldMoney.HOUSEHOLDS:WorldMoney.PAYMENTS;
            if(WorldMoney.PAYMENTS.equals(source) && amount>0 && refund(reason) && validAccount(account)){
                final long have=this.balances.getOrDefault(WorldMoney.PAYMENTS,0L);
                if(have<amount){
                    final long shortfall=amount-have;
                    if(this.balances.getOrDefault(WorldMoney.HOUSEHOLDS,0L)<shortfall)return TxResult.INSUFFICIENT_FUNDS;
                    final TxResult top=move(WorldMoney.HOUSEHOLDS,WorldMoney.PAYMENTS,shortfall,LaborShare.REASON_PREFIX+"pokrycie zwrotu z puli obywateli ("+TxRecord.sanitize(reason)+")");
                    if(!top.ok())return top;
                }
            }
            return move(source,account,amount,reason);
        }
        if (!validAccount(account)) {
            return TxResult.INVALID_ACCOUNT;
        }
        if (amount <= 0) {
            return TxResult.INVALID_AMOUNT;
        }
        final long after;
        try {
            after = Math.addExact(this.balances.getOrDefault(account, 0L), amount);
        } catch (final ArithmeticException e) {
            return TxResult.INVALID_AMOUNT;
        }
        this.balances.put(account, after);
        this.commit(TxRecord.Type.DEPOSIT, null, account, amount, null, after, reason);
        return TxResult.OK;
    }

    public synchronized TxResult withdraw(final String account, final long amount, final String reason) {
        if(closedSupply){if(protectedWorldAccount(account))return TxResult.INVALID_ACCOUNT;return move(account,WorldMoney.PAYMENTS,amount,reason);}
        if (!validAccount(account)) {
            return TxResult.INVALID_ACCOUNT;
        }
        if (amount <= 0) {
            return TxResult.INVALID_AMOUNT;
        }
        final long balance = this.balances.getOrDefault(account, 0L);
        if (balance < amount) {
            return TxResult.INSUFFICIENT_FUNDS;
        }
        final long after = balance - amount;
        this.balances.put(account, after);
        this.commit(TxRecord.Type.WITHDRAW, account, null, amount, after, null, reason);
        return TxResult.OK;
    }

    public synchronized TxResult transfer(final String from, final String to, final long amount, final String reason) {
        if(closedSupply && (protectedWorldAccount(from) || protectedWorldAccount(to)))return TxResult.INVALID_ACCOUNT;
        return move(from,to,amount,reason);
    }

    /** One move of {@link #transferAll}. */
    public record Leg(String from, String to, long amount, String reason) {
    }

    /**
     * Several transfers as ONE atomic step (Firmy: dividends, liquidation, founding): everything is validated first
     * (accounts, amounts, protected world accounts, overflow, and that every payer covers the SUM of its legs) and only
     * then all moves are applied under this lock, so either every leg happens or none. Each leg is journaled on its own.
     */
    public synchronized TxResult transferAll(final java.util.List<Leg> legs) {
        if (legs == null || legs.isEmpty() || legs.size() > 256) {
            return TxResult.INVALID_AMOUNT;
        }
        final Map<String, Long> out = new HashMap<>();
        final Map<String, Long> in = new HashMap<>();
        for (final Leg l : legs) {
            if (l == null || !validAccount(l.from()) || !validAccount(l.to())) {
                return TxResult.INVALID_ACCOUNT;
            }
            if (closedSupply && (protectedWorldAccount(l.from()) || protectedWorldAccount(l.to()))) {
                return TxResult.INVALID_ACCOUNT;
            }
            if (l.amount() <= 0 || l.from().equals(l.to())) {
                return TxResult.INVALID_AMOUNT;
            }
            try {
                out.merge(l.from(), l.amount(), Math::addExact);
                in.merge(l.to(), l.amount(), Math::addExact);
            } catch (final ArithmeticException e) {
                return TxResult.INVALID_AMOUNT;
            }
        }
        for (final var e : out.entrySet()) {
            if (this.balances.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                return TxResult.INSUFFICIENT_FUNDS;
            }
        }
        for (final var e : in.entrySet()) {
            try {
                Math.addExact(this.balances.getOrDefault(e.getKey(), 0L), e.getValue());
            } catch (final ArithmeticException x) {
                return TxResult.INVALID_AMOUNT;
            }
        }
        for (final Leg l : legs) {
            final TxResult r = move(l.from(), l.to(), l.amount(), l.reason());
            if (!r.ok()) {
                // cannot happen: validated above (a payer receiving inside the batch only makes later legs easier)
                throw new IllegalStateException("Przelew zbiorczy przerwany mimo sprawdzenia: " + r);
            }
        }
        return TxResult.OK;
    }

    private TxResult move(final String from,final String to,final long amount,final String reason) {
        if (!validAccount(from) || !validAccount(to)) {
            return TxResult.INVALID_ACCOUNT;
        }
        if (amount <= 0 || from.equals(to)) {
            return TxResult.INVALID_AMOUNT;
        }
        final long fromBalance = this.balances.getOrDefault(from, 0L);
        if (fromBalance < amount) {
            return TxResult.INSUFFICIENT_FUNDS;
        }
        final long toAfter;
        try {
            toAfter = Math.addExact(this.balances.getOrDefault(to, 0L), amount);
        } catch (final ArithmeticException e) {
            return TxResult.INVALID_AMOUNT;
        }
        final long fromAfter = fromBalance - amount;
        this.balances.put(from, fromAfter);
        this.balances.put(to, toAfter);
        this.commit(TxRecord.Type.TRANSFER, from, to, amount, fromAfter, toAfter, reason);
        return TxResult.OK;
    }

    /** Admin: sets the balance (≥ 0). */
    public synchronized TxResult set(final String account, final long amount, final String reason) {
        if (!validAccount(account)) {
            return TxResult.INVALID_ACCOUNT;
        }
        if (amount < 0) {
            return TxResult.INVALID_AMOUNT;
        }
        if(closedSupply) {
            if(protectedWorldAccount(account))return TxResult.INVALID_ACCOUNT;
            long current=balance(account);
            return amount==current?TxResult.OK:amount>current?deposit(account,amount-current,reason):withdraw(account,current-amount,reason);
        }
        this.balances.put(account, amount);
        this.commit(TxRecord.Type.SET, null, account, amount, null, amount, reason);
        return TxResult.OK;
    }

    /** Hands a copy of the state to the sink if anything changed since the last snapshot (or {@code force}). */
    public synchronized boolean requestSnapshot(final boolean force) {
        if (!force && this.snapshotSeq == this.seq) {
            return false;
        }
        this.snapshotSeq = this.seq;
        this.sink.snapshot(new HashMap<>(this.balances), this.seq);
        return true;
    }

    private void commit(final TxRecord.Type type, final String from, final String to, final long amount,
                        final Long fromAfter, final Long toAfter, final String reason) {
        this.seq++;
        final TxRecord record = new TxRecord(this.seq, this.clock.getAsLong(), type, from, to, amount, fromAfter, toAfter,
            TxRecord.sanitize(reason));
        this.sink.record(record);
        for (final java.util.function.Consumer<TxRecord> o : this.observers) {
            try {
                o.accept(record);
            } catch (final RuntimeException e) {
                // an observer never undoes or blocks a committed operation
            }
        }
    }
}
