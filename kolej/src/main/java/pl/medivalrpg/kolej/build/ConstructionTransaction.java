// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej.build;

import java.util.concurrent.Future;
import pl.medivalrpg.skarbiec.api.TxResult;

/** Main-thread state machine: each irreversible step follows an acknowledged durable predecessor. */
public final class ConstructionTransaction {
    public interface Ports {
        Future<?> checkpoint(ConstructionJournal.Intent intent);
        default Future<?> unpaidPause(ConstructionJournal.Intent intent){return checkpoint(intent);}
        Future<?> stage(ConstructionJournal.Intent intent);
        TxResult debit(ConstructionJournal.Intent intent);
        Future<?> paymentDurable(ConstructionJournal.Intent intent);
        Future<?> graph(ConstructionJournal.Intent intent);
        Future<?> activate(ConstructionJournal.Intent intent);
        void execute(ConstructionJournal.Intent intent);
        void stopped(ConstructionJournal.Intent intent);
    }
    private enum Step { INTENT, STAGE, DEBIT_INTENT, PAYMENT, PAID, GRAPH, ACTIVATE, ACTIVE, UNPAID_PAUSE, STOP }
    private final Ports ports;
    private ConstructionJournal.Intent intent;
    private Step step=Step.INTENT;
    private Future<?> waiting;
    public ConstructionTransaction(ConstructionJournal.Intent intent,Ports ports) {
        this.intent=intent.phase("INTENT","");this.ports=ports;waiting=ports.checkpoint(this.intent);
    }
    private ConstructionTransaction(ConstructionJournal.Intent intent,Ports ports,boolean paid) {
        if(!safelyPaid(intent))throw new IllegalArgumentException("Brak trwałego potwierdzenia płatności.");
        this.intent=intent;this.ports=ports;step=Step.PAID;waiting=java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    public static ConstructionTransaction resumePaid(ConstructionJournal.Intent intent,Ports ports){return new ConstructionTransaction(intent,ports,true);}
    public static boolean canRecoverUnpaid(ConstructionJournal.Intent intent,java.util.Optional<RailwayWorks.Job> job) {
        return intent.phase().equals("INTENT") && (job.isEmpty() || job.get().status().equals("HELD") && RailwayWorks.matchesIntent(job.get(),intent.plan()) && job.get().routeCells().equals(intent.graph().cells()));
    }
    public static ConstructionTransaction resumeUnpaid(ConstructionJournal.Intent intent,Ports ports,boolean staged) {
        if(!intent.phase().equals("INTENT"))throw new IllegalArgumentException("Brak dowodu niepobranej opłaty.");
        var tx=new ConstructionTransaction(intent,ports);
        tx.step=staged?Step.STAGE:Step.INTENT;
        return tx;
    }
    public static boolean safelyPaid(ConstructionJournal.Intent intent){return java.util.Set.of("PAID","ACTIVE").contains(intent.phase());}
    public static boolean canRecoverPaid(ConstructionJournal.Intent intent,java.util.Optional<RailwayWorks.Job> job) {
        return safelyPaid(intent) && job.isPresent() && job.get().id().equals(intent.id()) && !job.get().status().equals("CANCELLED") && job.get().routeCells().equals(intent.graph().cells()) && RailwayWorks.matchesIntent(job.get(),intent.plan());
    }
    public boolean finished(){return step==Step.STOP;}
    public boolean pauseSettled(){return finished() || waiting.isDone() && step!=Step.DEBIT_INTENT && step!=Step.PAYMENT;}
    public ConstructionJournal.Intent intent(){return intent;}
    public void tick() {tick(false);}
    public void tick(boolean paused) {
        if(finished() || !waiting.isDone())return;
        try {
            waiting.get();
            if(step==Step.UNPAID_PAUSE){if(paused)return;step=Step.STAGE;}
            if(paused) {
                // A debit already issued finishes ONLY its durability bookkeeping. No new payment or execution.
                if(step==Step.PAYMENT){step=Step.PAID;intent=intent.phase("PAID","");waiting=ports.checkpoint(intent);}
                // No debit happened: supersede our DEBITTING intent before a clean restart in pause.
                else if(step==Step.STAGE || step==Step.DEBIT_INTENT){step=Step.UNPAID_PAUSE;intent=intent.phase("INTENT","");waiting=ports.unpaidPause(intent);}
                return;
            }
            switch(step) {
                case INTENT -> {step=Step.STAGE;waiting=ports.stage(intent);}
                case STAGE -> {step=Step.DEBIT_INTENT;intent=intent.phase("DEBITTING","");waiting=ports.checkpoint(intent);}
                case DEBIT_INTENT -> {
                    TxResult result=ports.debit(intent); // Exactly one call, only after DEBITTING is durable.
                    if(result!=TxResult.OK){stop("DECLINED","Płatność odrzucona: "+result);return;}
                    step=Step.PAYMENT;waiting=ports.paymentDurable(intent);
                }
                case PAYMENT -> {step=Step.PAID;intent=intent.phase("PAID","");waiting=ports.checkpoint(intent);}
                case PAID -> {step=Step.GRAPH;waiting=ports.graph(intent);}
                case GRAPH -> {step=Step.ACTIVATE;waiting=ports.activate(intent);}
                case ACTIVATE -> {step=Step.ACTIVE;intent=intent.phase("ACTIVE","");waiting=ports.checkpoint(intent);}
                case ACTIVE -> {ports.execute(intent);step=Step.STOP;}
                default -> { }
            }
        }catch(Exception ex){stop("RECONCILE","Transakcja wymaga kontroli; bez ponownej opłaty: "+ex.getClass().getSimpleName());}
    }
    private void stop(String phase,String reason) {
        step=Step.STOP;intent=intent.phase(phase,reason);
        // Preserve the last durable intent even if this diagnostic checkpoint itself cannot be written.
        try{ports.checkpoint(intent);}catch(RuntimeException ignored){}ports.stopped(intent);
    }
    /** Only PAID/ACTIVE were written after the bank durability barrier. A DEBITTING crash is ambiguous. */
    public static ConstructionJournal.Intent recover(ConstructionJournal.Intent intent) {
        if(intent.phase().equals("DECLINED") || intent.phase().equals("INTENT") || safelyPaid(intent))return intent;
        return intent.phase("RECONCILE","Restart: sprawdź dziennik Skarbca, cenę, graf i zlecenie. Nie pobrano ponownie opłaty.");
    }
}
