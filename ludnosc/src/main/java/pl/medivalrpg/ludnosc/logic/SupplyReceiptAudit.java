// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.logic;

import java.util.*;
import pl.medivalrpg.ludnosc.logic.SupplyLedger.*;
import pl.medivalrpg.ludnosc.model.BlockPos;

/** Startup verification of DELIVERED frontiers. Historical snapshots are never replayed or compared to newer receipts. */
final class SupplyReceiptAudit {
    private record Check(UUID player, BlockPos chest) { }
    private final SupplyLedger.Port port;
    private final Map<String,Entry> pending=new LinkedHashMap<>();
    private final Map<UUID,Entry> sources=new HashMap<>();
    private final Map<BlockPos,Entry> targets=new HashMap<>();
    private final Set<UUID> goodSources=new HashSet<>(), badSources=new HashSet<>();
    private final Set<BlockPos> goodTargets=new HashSet<>(), badTargets=new HashSet<>();
    private final ArrayDeque<Check> checks=new ArrayDeque<>();

    SupplyReceiptAudit(List<Entry> loaded, SupplyLedger.Port port) {
        this.port=port;
        Map<String,Entry> receipts=new HashMap<>();
        for(Entry e:loaded) if(receipts.putIfAbsent(e.receipt(),e)!=null) throw new IllegalArgumentException("Powtórzone pokwitowanie.");
        Map<UUID,Set<String>> previousSources=new HashMap<>(); Map<BlockPos,Set<String>> previousTargets=new HashMap<>();
        for(Entry e:loaded) if(e.phase()==Phase.DELIVERED) {
            String previous=e.plan().playerMarker();
            if(!previous.isEmpty()) {
                Entry older=receipts.get(previous);
                if(older==null || older.phase()!=Phase.DELIVERED || !older.payload().player().equals(e.payload().player()))
                    throw new IllegalArgumentException("Przerwany łańcuch pokwitowań gracza.");
                previousSources.computeIfAbsent(e.payload().player(),k -> new HashSet<>()).add(previous);
            }
            for(int i=0;i<e.plan().binding().parts().size();i++) {
                BlockPos pos=e.plan().binding().parts().get(i); previous=e.plan().chestMarkers().get(i);
                if(previous.isEmpty()) continue;
                Entry older=receipts.get(previous);
                if(older==null || older.phase()!=Phase.DELIVERED || !older.plan().binding().parts().contains(pos))
                    throw new IllegalArgumentException("Przerwany łańcuch pokwitowań magazynu.");
                previousTargets.computeIfAbsent(pos,k -> new HashSet<>()).add(previous);
            }
            pending.put(e.receipt(),e);
        }
        for(Entry e:pending.values()) {
            UUID player=e.payload().player();
            if(!previousSources.getOrDefault(player,Set.of()).contains(e.receipt()) && sources.putIfAbsent(player,e)!=null)
                throw new IllegalArgumentException("Rozgałęziony łańcuch pokwitowań gracza.");
            for(BlockPos pos:e.plan().binding().parts())
                if(!previousTargets.getOrDefault(pos,Set.of()).contains(e.receipt()) && targets.putIfAbsent(pos,e)!=null)
                    throw new IllegalArgumentException("Rozgałęziony łańcuch pokwitowań magazynu.");
        }
        for(Entry e:pending.values()) {
            if(!sources.containsKey(e.payload().player()) || e.plan().binding().parts().stream().anyMatch(p -> !targets.containsKey(p)))
                throw new IllegalArgumentException("Cykl w łańcuchu pokwitowań.");
            port.lock(e);
        }
        // A detached cycle must not hide behind another apparently valid head of the same inventory.
        for(var head:sources.entrySet()) {
            Set<String> visited=new HashSet<>(); Entry cursor=head.getValue();
            while(cursor!=null) {
                if(!visited.add(cursor.receipt())) throw new IllegalArgumentException("Cykl pokwitowań gracza.");
                String previous=cursor.plan().playerMarker(); cursor=previous.isEmpty() ? null : receipts.get(previous);
            }
            long expected=pending.values().stream().filter(e -> e.payload().player().equals(head.getKey())).count();
            if(visited.size()!=expected) throw new IllegalArgumentException("Odłączona historia pokwitowań gracza.");
        }
        for(var head:targets.entrySet()) {
            Set<String> visited=new HashSet<>(); Entry cursor=head.getValue();
            while(cursor!=null) {
                if(!visited.add(cursor.receipt())) throw new IllegalArgumentException("Cykl pokwitowań magazynu.");
                int index=cursor.plan().binding().parts().indexOf(head.getKey());
                String previous=cursor.plan().chestMarkers().get(index); cursor=previous.isEmpty() ? null : receipts.get(previous);
            }
            long expected=pending.values().stream().filter(e -> e.plan().binding().parts().contains(head.getKey())).count();
            if(visited.size()!=expected) throw new IllegalArgumentException("Odłączona historia pokwitowań magazynu.");
        }
        sources.keySet().forEach(p -> checks.addLast(new Check(p,null)));
        targets.keySet().forEach(p -> checks.addLast(new Check(null,p)));
    }
    /** At most four independent inventory checks and 32 lease releases per search tick. */
    void tick(Entry active) {
        int count=Math.min(4,checks.size());
        for(int n=0;n<count;n++) {
            Check check=checks.removeFirst();
            if(active!=null && (check.player()!=null ? check.player().equals(active.payload().player())
                : active.plan().binding().parts().contains(check.chest()))) { checks.addLast(check); continue; }
            if(check.player()!=null) {
                if(goodSources.contains(check.player()) || badSources.contains(check.player())) continue;
                Proof proof=port.sourceProof(sources.get(check.player()));
                if(proof==Proof.AFTER) goodSources.add(check.player());
                else if(proof==Proof.UNAVAILABLE) checks.addLast(check);
                else badSources.add(check.player());
            } else {
                if(goodTargets.contains(check.chest()) || badTargets.contains(check.chest())) continue;
                Proof proof=port.targetProof(targets.get(check.chest()));
                if(proof==Proof.AFTER) goodTargets.add(check.chest());
                else if(proof==Proof.UNAVAILABLE) checks.addLast(check);
                else badTargets.add(check.chest());
            }
        }
        int released=0;
        for(var it=pending.values().iterator();it.hasNext() && released<32;) {
            Entry e=it.next();
            if(ready(e)) { port.unlock(e); it.remove(); released++; }
        }
    }
    private boolean ready(Entry e) {
        return goodSources.contains(e.payload().player()) && e.plan().binding().parts().stream().allMatch(goodTargets::contains);
    }
    String status(Entry e) {
        if(!pending.containsKey(e.receipt())) return "DELIVERED";
        if(badSources.contains(e.payload().player()) || e.plan().binding().parts().stream().anyMatch(badTargets::contains)) return "BLOCKED";
        return "PENDING";
    }
    void completed(Entry e) {
        sources.put(e.payload().player(),e); goodSources.add(e.payload().player()); badSources.remove(e.payload().player());
        for(BlockPos pos:e.plan().binding().parts()) { targets.put(pos,e); goodTargets.add(pos); badTargets.remove(pos); }
    }
}
