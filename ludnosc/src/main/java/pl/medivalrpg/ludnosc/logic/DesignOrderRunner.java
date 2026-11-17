// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.*;
import java.util.function.Function;
import pl.medivalrpg.ludnosc.model.CountryState;

/** One bounded pass of explicit plan orders. Retry state survives restart; no placement or permission is bypassed. */
public final class DesignOrderRunner {
    public static final int MAX_ATTEMPTS=8;
    public static final long FIRST_RETRY=30_000,MAX_RETRY=600_000;
    public enum Disposition { ORDERED, RETRY, WAIT_GLOBAL, REJECTED }
    public record Attempt(Disposition disposition,String code,String reason) {
        public static Attempt ordered() {return new Attempt(Disposition.ORDERED,"OK",null);}
        public static Attempt retry(final String code,final String reason) {return new Attempt(Disposition.RETRY,code,reason);}
        public static Attempt waiting(final String code,final String reason) {return new Attempt(Disposition.WAIT_GLOBAL,code,reason);}
        public static Attempt rejected(final String code,final String reason) {return new Attempt(Disposition.REJECTED,code,reason);}
    }
    public static final class Failure {
        public String code,reason;
        public int attempts;
        public long retryAt;
        public boolean terminal,blockPlan;
    }
    public record Result(boolean ordered,boolean changed,boolean blocked) { }
    private DesignOrderRunner() { }

    /** Requirements are checked before reserving priority for funds; null means normal placement admission may run. */
    public static Attempt gate(final int requiredEra,final int era,final boolean discovery,final long cost,final long budget,
                               final String eraReason,final String discoveryReason,final String fundsReason) {
        if(requiredEra>era) return Attempt.retry("UNKNOWN_BUILDING",eraReason);
        if(!discovery) return Attempt.retry("UNKNOWN_BUILDING",discoveryReason);
        if(cost>budget) return Attempt.waiting("INSUFFICIENT_FUNDS",fundsReason);
        return null;
    }

    public static long delay(final int attempts) {
        return Math.min(MAX_RETRY,FIRST_RETRY*(1L<<Math.max(0,Math.min(5,attempts-1))));
    }
    public static boolean due(final Failure failure,final long now) {
        return failure==null || !failure.terminal && (now>=failure.retryAt || failure.retryAt-now>MAX_RETRY);
    }
    private static Failure failure(final Failure prior,final Attempt attempt,final long now) {
        final Failure next=new Failure();next.code=attempt.code();next.reason=attempt.reason();
        next.attempts=prior!=null && Objects.equals(prior.code,next.code)?Math.min(32,Math.max(0,prior.attempts)+1):1;
        next.terminal=attempt.disposition()==Disposition.REJECTED;next.blockPlan=attempt.disposition()==Disposition.WAIT_GLOBAL;
        next.retryAt=next.terminal?0:now+delay(next.attempts);return next;
    }

    public static <T> Result run(final CountryState state,final List<T> orders,final Function<T,String> key,
                                 final Function<T,Attempt> admission,final long now) {
        boolean changed=false;
        if(state.designDone==null) {state.designDone=new ArrayList<>();changed=true;}
        if(state.designFailed==null) {state.designFailed=new ArrayList<>();changed=true;}
        if(state.designFailures==null) {state.designFailures=new LinkedHashMap<>();changed=true;}
        // Old versions permanently remembered every obstruction, including vanished temporary sites/decorations.
        for(final String rejected:new ArrayList<>(state.designFailed)) if(!state.designFailures.containsKey(rejected)) {
            state.designFailures.put(rejected,failure(null,Attempt.retry("LEGACY","Poprzednio odrzucono tę pozycję. Zaplanowano ponowną kontrolę terenu."),now));
            state.designFailed.remove(rejected);changed=true;
        }
        int attempts=0;
        for(final T order:orders) {
            final String id=key.apply(order);
            if(state.designDone.contains(id)) continue;
            final Failure previous=state.designFailures.get(id);
            if(previous!=null && previous.terminal) continue;
            if(!due(previous,now)) {
                if(previous.blockPlan) return new Result(false,changed,true);
                continue;
            }
            if(attempts++>=MAX_ATTEMPTS) break;
            final Attempt attempt=Objects.requireNonNull(admission.apply(order));changed=true;
            if(attempt.disposition()==Disposition.ORDERED) {
                state.designDone.add(id);state.designFailed.remove(id);state.designFailures.remove(id);
                return new Result(true,true,false);
            }
            final Failure next=failure(previous,attempt,now);state.designFailures.put(id,next);
            if(next.terminal) {if(!state.designFailed.contains(id)) state.designFailed.add(id);} else state.designFailed.remove(id);
            if(next.blockPlan) return new Result(false,true,true);
        }
        return new Result(false,changed,false);
    }
}
