// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import pl.medivalrpg.ludnosc.model.ProductionBinding;

/** Single main-thread transfer coordinator. Journal completion is a barrier, never permission to replay after restart. */
public final class SupplyLedger {
    public static final int MAX_RECORDS = 1024, MAX_WAITING = 16, MAX_AMOUNT = 4096;
    public enum Phase { REGISTERED, INTENT, DELIVERED, BLOCKED }
    public enum Proof { AFTER, BEFORE, MISMATCH, UNAVAILABLE }
    public record Payload(UUID player, UUID country, String contractId, String material, int amount) {
        public Payload {
            Objects.requireNonNull(player); Objects.requireNonNull(country);
            if (contractId == null || !contractId.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{0,127}")
                || material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}") || amount < 1 || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("Nieprawidłowy ładunek dostawy.");
        }
    }
    public record Plan(UUID warehouse, ProductionBinding binding, List<String> sourceBefore, List<String> sourceAfter,
                       List<String> targetBefore, List<String> targetAfter, String playerMarker, List<String> chestMarkers) {
        public Plan {
            Objects.requireNonNull(warehouse); Objects.requireNonNull(binding); Objects.requireNonNull(playerMarker);
            if (binding.token() == null || binding.parts() == null || binding.parts().isEmpty() || binding.parts().size() > 2)
                throw new IllegalArgumentException("Nieprawidłowa skrzynia.");
            binding = new ProductionBinding(binding.token(), List.copyOf(binding.parts()));
            sourceBefore = vector(sourceBefore); sourceAfter = vector(sourceAfter);
            targetBefore = vector(targetBefore); targetAfter = vector(targetAfter);
            chestMarkers = List.copyOf(chestMarkers);
            if (sourceBefore.size() != sourceAfter.size() || targetBefore.size() != targetAfter.size()
                || chestMarkers.size() != binding.parts().size()) throw new IllegalArgumentException("Niezgodne snapshoty.");
            long size = 0;
            for (var list : List.of(sourceBefore, sourceAfter, targetBefore, targetAfter))
                for (String s : list) if (s != null) size += s.length();
            if (size > 131_072) throw new IllegalArgumentException("Zbyt duży snapshot inventory.");
        }
        private static List<String> vector(List<String> input) {
            if (input == null || input.isEmpty() || input.size() > 54) throw new IllegalArgumentException("Nieprawidłowy snapshot.");
            return Collections.unmodifiableList(new ArrayList<>(input));
        }
    }
    public record Entry(int version, Payload payload, String receipt, Phase phase, Plan plan) {
        public Entry {
            if (version != 1) throw new IllegalArgumentException("Nieznana wersja dziennika dostaw.");
            Objects.requireNonNull(payload); Objects.requireNonNull(phase); UUID.fromString(receipt);
            if (phase != Phase.REGISTERED && plan == null) throw new IllegalArgumentException("Brak planu dostawy.");
            if (phase == Phase.REGISTERED && plan != null) throw new IllegalArgumentException("Niespójna rejestracja.");
        }
        public Entry with(Phase phase, Plan plan) { return new Entry(version, payload, receipt, phase, plan); }
    }
    public record Attempt(Plan plan, String reason) { }
    public interface Journal { CompletableFuture<Void> write(Entry entry); }
    public interface Port {
        Attempt prepare(Payload payload);
        void lock(Entry entry);
        void unlock(Entry entry);
        boolean before(Entry entry);
        void apply(Entry entry);
        Proof proof(Entry entry);
        Proof sourceProof(Entry entry);
        Proof targetProof(Entry entry);
        Proof durableProof(Entry entry);
    }
    private final Journal journal;
    private final Port port;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<Void>> registering = new LinkedHashMap<>();
    private final ArrayDeque<String> waiting = new ArrayDeque<>();
    private final Map<String, String> reasons = new HashMap<>();
    private final SupplyReceiptAudit audit;
    private Entry active;
    private CompletableFuture<Void> barrier;
    private boolean recovering, finalizing, applied;
    private String fault;

    public SupplyLedger(Journal journal, Port port, List<Entry> loaded) {
        this.journal = journal; this.port = port;
        for (Entry e : loaded) {
            if (entries.putIfAbsent(e.payload().contractId(), e) != null) throw new IllegalArgumentException("Powtórzony kontrakt.");
            if (e.phase() == Phase.REGISTERED) waiting.addLast(e.payload().contractId());
            else if (e.phase() != Phase.DELIVERED) {
                port.lock(e);
                if (active != null) fault = "Wiele nierozstrzygniętych dostaw — wymagany audyt.";
                active = e; recovering = true;
            }
        }
        if (entries.size() > MAX_RECORDS || waiting.size() > MAX_WAITING) fault = "Przekroczony limit dziennika — wymagany audyt.";
        audit=new SupplyReceiptAudit(loaded,port);
    }
    public void failClosed(String reason) { fault = reason; }
    public boolean faulted() { return fault != null; }
    public boolean contains(String id) { return entries.containsKey(id); }

    /** New IDs get a read-only readiness check. Known IDs are polls and must never depend on current inventory. */
    public Map<String,Object> receivePrepared(Payload payload) {
        if(!contains(payload.contractId())) {
            if(fault!=null) return result("UNAVAILABLE","",fault);
            Attempt attempt=port.prepare(payload);
            if(attempt.plan()==null) return reject(payload.contractId(),attempt.reason());
        }
        return receive(payload);
    }

    /** A caller error must not discard ownership of an already registered receipt. */
    public Map<String,Object> reject(String id,String reason) {
        Entry existing=entries.get(id);
        return result("BLOCKED",existing==null ? "" : existing.receipt(),reason);
    }

    /** Exception after possible registration is ambiguous, even if inventory has not yet changed. */
    public Map<String,Object> quarantine(String id,String reason) {
        if(contains(id)) { failClosed(reason); return reject(id,reason); }
        return result("BLOCKED","",reason);
    }

    public Map<String, Object> receive(Payload payload) {
        Entry existing = entries.get(payload.contractId());
        if (existing != null) {
            if (!existing.payload().equals(payload)) return result("BLOCKED", existing.receipt(), "ID kontraktu jest związane z innym ładunkiem.");
            return status(existing);
        }
        if (fault != null) return result("UNAVAILABLE", "", fault);
        if (entries.size() >= MAX_RECORDS || waiting.size() + registering.size() >= MAX_WAITING)
            return result("UNAVAILABLE", "", "Dziennik lub kolejka dostaw jest pełna.");
        Entry e = new Entry(1, payload, UUID.randomUUID().toString(), Phase.REGISTERED, null);
        entries.put(payload.contractId(), e);
        registering.put(payload.contractId(), journal.write(e));
        return result("PENDING", e.receipt(), "Trwa zapis rejestracji dostawy.");
    }
    private Map<String, Object> status(Entry e) {
        if (e.phase() == Phase.DELIVERED) {
            String status=fault!=null ? "BLOCKED" : audit.status(e);
            return result(status,e.receipt(),switch(status) {
                case "DELIVERED" -> "Dostawa i najnowsze pokwitowania zostały potwierdzone.";
                case "BLOCKED" -> "Niezgodny łańcuch pokwitowań lub snapshot po restarcie — stan zapasu nieznany, wymagany audyt.";
                default -> "Pokwitowanie z dziennika oczekuje na sprawdzenie najnowszego inventory gracza i magazynu po restarcie.";
            });
        }
        if (e.phase() == Phase.BLOCKED || fault != null) return result("BLOCKED", e.receipt(), fault != null ? fault : "Nierozstrzygnięty transfer — wymagany audyt dziennika i inventory.");
        String reason = reasons.get(e.payload().contractId());
        return result(e.phase() == Phase.REGISTERED && reason != null ? "UNAVAILABLE" : "PENDING", e.receipt(),
            reason == null ? "Dostawa oczekuje na trwałe potwierdzenie." : reason);
    }
    public static Map<String, Object> result(String status, String receipt, String reason) {
        return Map.of("status", status, "receipt", receipt, "reason", reason);
    }

    /** Search at most one waiting request when requested; active barriers are polled every tick. */
    public void tick(boolean search) {
        if (fault != null) return;
        if(search) audit.tick(active);
        for (var it = registering.entrySet().iterator(); it.hasNext();) {
            var item = it.next(); if (!item.getValue().isDone()) continue;
            if (item.getValue().isCompletedExceptionally()) { fault = "Błąd trwałego zapisu rejestracji — wymagany audyt."; return; }
            waiting.addLast(item.getKey()); it.remove();
        }
        if (active != null) {
            if (active.phase() == Phase.BLOCKED) return;
            if (recovering) {
                if (!search) return;
                Proof proof = port.proof(active);
                if (proof == Proof.UNAVAILABLE) { reasons.put(active.payload().contractId(), "Oczekiwanie na gracza i skrzynię do odczytu pokwitowań po awarii."); return; }
                recovering = false;
                if (proof == Proof.AFTER) { applied=true; awaitDisk(); }
                else block(); // Even a clean BEFORE is not permission to replay an interrupted transfer.
                return;
            }
            if(applied && !finalizing) { awaitDisk(); return; }
            if (!barrier.isDone()) return;
            if (barrier.isCompletedExceptionally()) { fault = "Błąd trwałego zapisu dostawy — inventory pozostają zablokowane do audytu."; return; }
            if (finalizing) {
                Proof proof=port.proof(active);
                if(proof==Proof.UNAVAILABLE) { reasons.put(active.payload().contractId(),"Oczekiwanie na końcowy odczyt obu inventory i pokwitowań."); return; }
                if(proof!=Proof.AFTER) { block(); return; }
                Entry done = active.with(Phase.DELIVERED, active.plan());
                entries.put(done.payload().contractId(), done); audit.completed(done); port.unlock(done);
                reasons.remove(done.payload().contractId()); active = null; finalizing = false; applied=false;
                return;
            }
            try {
                if (!port.before(active)) { block(); return; }
                port.apply(active);
                if (port.proof(active) != Proof.AFTER) { block(); return; }
                applied=true; awaitDisk();
            } catch (RuntimeException ex) { block(); }
            return;
        }
        if (!search || waiting.isEmpty()) return;
        String id = waiting.removeFirst(); Entry e = entries.get(id);
        final Attempt attempt;
        try { attempt = port.prepare(e.payload()); }
        catch (RuntimeException ex) { waiting.addLast(id); reasons.put(id, "Nie można bezpiecznie odczytać źródła lub magazynu."); return; }
        if (attempt.plan() == null) { waiting.addLast(id); reasons.put(id, attempt.reason()); return; }
        active = e.with(Phase.INTENT, attempt.plan()); entries.put(id, active); reasons.remove(id);
        port.lock(active);
        barrier = journal.write(active); // Source and destination have not been changed.
    }
    private void complete() {
        finalizing = true;
        barrier = journal.write(active.with(Phase.DELIVERED, active.plan()));
    }
    private void awaitDisk() {
        Proof live=port.proof(active);
        if(live==Proof.UNAVAILABLE) { reasons.put(active.payload().contractId(),"Oczekiwanie na oba inventory do sprawdzenia zapisu."); return; }
        if(live!=Proof.AFTER) { block(); return; }
        Proof persisted=port.durableProof(active);
        if(persisted==Proof.AFTER) { reasons.remove(active.payload().contractId()); complete(); }
        else if(persisted==Proof.MISMATCH) block();
        else reasons.put(active.payload().contractId(),"Oczekiwanie na zapis pokwitowania w pliku gracza i regionie magazynu.");
    }
    private void block() {
        active = active.with(Phase.BLOCKED, active.plan());
        entries.put(active.payload().contractId(), active); barrier = journal.write(active);
        // Keep both locks. Even a failed BLOCKED write leaves the older durable INTENT for restart reconciliation.
    }
}
