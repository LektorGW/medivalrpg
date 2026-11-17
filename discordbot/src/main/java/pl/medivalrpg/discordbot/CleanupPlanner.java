// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
public final class CleanupPlanner {
    public record Target(String id,String name,String type) {}
    public record Plan(List<Target> delete,List<String> keep) {
        public String fingerprint() {
            try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(delete.stream().map(t->t.id()+":"+t.name()+":"+t.type()).sorted().reduce("",(a,b)->a+"\n"+b).getBytes(StandardCharsets.UTF_8)));}
            catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        }
    }
    public static Set<String> ticketProtection(List<LayoutPlanner.Channel> channels) {
        Set<String> categories=new HashSet<>(),protectedIds=new HashSet<>();
        for(var c:channels) {
            String name=c.name().toLowerCase(Locale.ROOT);
            if(name.matches("^(ticket|closed)-.*")||name.contains("ticket")) {
                protectedIds.add(c.id());if(c.type().equals("category"))categories.add(c.id());else if(c.parent()!=null)categories.add(c.parent());
            }
        }
        protectedIds.addAll(categories);
        for(var c:channels)if(categories.contains(c.parent()))protectedIds.add(c.id());
        return Set.copyOf(protectedIds);
    }
    public static Plan plan(LayoutPlanner.State state) {
        Set<String> tickets=ticketProtection(state.channels());Set<String> fresh=new HashSet<>();
        state.ids().forEach((k,v)->{if(k.startsWith("category:")||k.startsWith("channel:")||k.startsWith("country-channel:")||k.startsWith("stats:"))fresh.add(v);});
        Set<String> parentsKept=new HashSet<>();List<Target> deletion=new ArrayList<>();List<String> keep=new ArrayList<>();
        for(var c:state.channels())if(!c.type().equals("category")) {
            String why=tickets.contains(c.id())?"Ticket Tool":Set.of(LayoutPlanner.ANNOUNCEMENTS,LayoutPlanner.COMMANDS).contains(c.id())?"webhook — zachowuję ID":fresh.contains(c.id())?"nowy układ":c.occupied()?"kanał głosowy jest zajęty":null;
            if(why!=null) {keep.add(c.name()+" ["+c.id()+"] — "+why);if(c.parent()!=null&&!Set.of(LayoutPlanner.ANNOUNCEMENTS,LayoutPlanner.COMMANDS).contains(c.id()))parentsKept.add(c.parent());}
            else deletion.add(new Target(c.id(),c.name(),c.type()));
        }
        for(var c:state.channels())if(c.type().equals("category")) {
            if(tickets.contains(c.id())||fresh.contains(c.id())||parentsKept.contains(c.id()))keep.add(c.name()+" ["+c.id()+"] — kategoria chronionych kanałów lub nowego układu");
            else deletion.add(new Target(c.id(),c.name(),c.type()));
        }
        deletion.sort(Comparator.comparing((Target t)->t.type().equals("category")).thenComparing(Target::id));Collections.sort(keep);
        return new Plan(List.copyOf(deletion),List.copyOf(keep));
    }
}
