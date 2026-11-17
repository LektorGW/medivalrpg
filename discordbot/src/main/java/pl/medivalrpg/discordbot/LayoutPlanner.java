// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.text.Normalizer;
import java.util.*;

/** Pure desired-state planner. No Bukkit, network, credentials or mutable guild entities. */
public final class LayoutPlanner {
    public static final String ANNOUNCEMENTS="1000000000000001221", COMMANDS="1000000000000001222";
    public record Channel(String id,String name,String type,String parent,int position,String profile,boolean occupied) {
        public Channel(String id,String name,String type,String parent,int position,String profile){this(id,name,type,parent,position,profile,false);}
    }
    public record Role(String id,String name,int color,boolean protectedRole) {}
    public record Country(String id,String name,int color) {}
    public record State(List<Channel> channels,List<Role> roles,Map<String,String> ids,List<String> roleOrder) {
        public State {channels=List.copyOf(channels);roles=List.copyOf(roles);ids=Map.copyOf(ids);roleOrder=List.copyOf(roleOrder);}
    }
    public enum Kind { BIND, CREATE_CATEGORY, CREATE_CHANNEL, CREATE_VOICE, CREATE_ROLE, RENAME, ROLE_UPDATE, PROFILE, MOVE, POSITION, ROLE_ORDER }
    public record Operation(Kind kind,String key,String target,String value,int number) {
        public String describe() {
            return switch(kind) {
                case BIND -> "Użyję istniejącej roli/kanału "+value+" ["+target+"] jako "+key+" — bez zmiany roli";
                case CREATE_CATEGORY -> "Utwórz kategorię: "+value;
                case CREATE_CHANNEL -> "Utwórz kanał: "+value+" → "+target;
                case CREATE_VOICE -> "Utwórz zablokowany kanał statystyk: "+value;
                case CREATE_ROLE -> "Utwórz rolę: "+value+String.format(Locale.ROOT," (#%06X)",number);
                case RENAME -> "Przemianuj "+key+" ["+target+"] → "+value;
                case ROLE_UPDATE -> "Nazwa/kolor roli "+target+" → "+value+String.format(Locale.ROOT," (#%06X)",number);
                case PROFILE -> "Ustaw uprawnienia "+key+" ["+target+"]: "+value;
                case MOVE -> "Przenieś "+key+" ["+target+"] → "+value+(value.equals("category:archive")?" (prywatne Archiwum, bez pisania)":" (bez synchronizacji uprawnień)");
                case POSITION -> "Kolejność "+key+" ["+target+"]: "+(number+1);
                case ROLE_ORDER -> "Kolejność własnych ról poniżej bota: "+value;
            };
        }
    }
    private record Wanted(String key,String name,String parent,String profile,List<String> aliases,String type) {
        Wanted(String key,String name,String parent,String profile,List<String> aliases){this(key,name,parent,profile,aliases,"text");}
    }
    public static final Map<String,String> CATEGORIES;
    static {
        Map<String,String> m=new LinkedHashMap<>();
        m.put("stats","📊 Statistics");m.put("info","📜 Information");m.put("server","🏰 Server");m.put("talk","💬 Chat");
        m.put("countries","⚔️ Countries");m.put("admin","🛡️ Administration");m.put("archive","🗄️ Archive");CATEGORIES=Collections.unmodifiableMap(m);
    }
    public static String slug(String name) {
        String text=Normalizer.normalize(name.replace('ł','l').replace('Ł','L'),Normalizer.Form.NFD)
            .replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");
        return text.isEmpty()?"kanal":text;
    }
    public static List<Operation> plan(State state,List<Country> countries) {
        Builder b=new Builder(state);
        int pos=0;
        for(var entry:CATEGORIES.entrySet())b.category("category:"+entry.getKey(),entry.getValue(),pos++);
        for(var entry:List.of(Map.entry("ruler","Władca"),Map.entry("advisor","Doradca"),Map.entry("citizen","Obywatel"),Map.entry("player","Gracz"),Map.entry("join","Przybysz")))b.role(entry.getKey(),entry.getValue(),0);
        List<Country> sorted=countries.stream().sorted(Comparator.comparing(Country::id)).toList();
        for(Country country:sorted)b.role("country:"+country.id(),"Kraj: "+country.name(),country.color());
        List<Wanted> wanted=new ArrayList<>(List.of(
            new Wanted("stats:online","👥 Online: "+state.ids.getOrDefault("value:online","—"),"stats","stats",List.of(),"voice"),
            new Wanted("stats:countries","🏰 Countries: "+countries.size(),"stats","stats",List.of(),"voice"),
            new Wanted("stats:members","👤 Members: "+state.ids.getOrDefault("value:members","—"),"stats","stats",List.of(),"voice"),
            new Wanted("channel:announcements","announcements","info","preserve",List.of()),
            new Wanted("channel:welcome","👋-welcome","info","readonly",List.of()),
            new Wanted("channel:verification","✅-verification","info","verification",List.of()),
            new Wanted("channel:rules","📄-rules","info","readonly",List.of("regulamin","rules")),
            new Wanted("channel:commands","Server-command","info","preserve",List.of()),
            new Wanted("channel:serverinfo","🌐-server-info","info","readonly",List.of()),
            new Wanted("channel:status","📡-status","server","readonly",List.of("status","medival-status")),
            new Wanted("channel:chronicle","📯-war-chronicle","server","chronicle",List.of()),
            new Wanted("channel:events","🎉-events","server","readonly",List.of("wydarzenia","medival-events")),
            new Wanted("channel:ranking","🏆-ranking","server","readonly",List.of("ranking")),
            new Wanted("channel:chat","🎮-game-chat","talk","verified",List.of("czat-z-gry","minecraft","medival-chat")),
            new Wanted("channel:general","💬-general","talk","verified",List.of("ogolny","general","ogólny")),
            new Wanted("channel:trade","🛒-trading","talk","verified",List.of("handel","trade")),
            new Wanted("channel:help","❓-help","talk","verified",List.of("pomoc","help")),
            new Wanted("channel:moderation","🛡️-moderation","admin","private",List.of("moderacja"))));
        for(Country country:sorted)wanted.add(new Wanted("country-channel:"+country.id(),"country-"+slug(country.name()),"countries","country:"+country.id(),List.of()));
        // A guard channel can only already exist; layout never creates it.
        if(state.ids().containsKey("channel:guard"))wanted.add(new Wanted("channel:guard","raporty-serwera","admin","preserve",List.of()));
        Map<String,Integer> positions=new HashMap<>();
        for(Wanted w:wanted)b.channel(w,positions.merge(w.parent,1,Integer::sum)-1);
        // Move protected channels out before editing any old category ACL: Discord syncs category ACLs to synced children.
        b.ops.addAll(b.deferredProfiles);
        return List.copyOf(b.ops);
    }
    private static final class Builder {
        final State state;final List<Operation> ops=new ArrayList<>(),deferredProfiles=new ArrayList<>();final Set<String> used=new HashSet<>();final Map<String,String> resolved=new HashMap<>();
        Builder(State state) {this.state=state;}
        Channel find(String key,String type,String name,List<String> aliases) {
            String id=state.ids.get(key);
            if(key.equals("channel:announcements"))id=ANNOUNCEMENTS;
            if(key.equals("channel:commands"))id=COMMANDS;
            if(id!=null) {
                String fixed=id;Channel c=state.channels.stream().filter(x->x.id.equals(fixed)).findFirst().orElse(null);
                if(c==null && (key.equals("channel:guard") || key.equals("channel:announcements") || key.equals("channel:commands")))throw new IllegalStateException("Brak chronionego kanału: "+key);
                if(c!=null && !c.type.equals(type))throw new IllegalStateException("Nieprawidłowy typ: "+key);
                if(c!=null)return c;
            }
            return null; // fresh layout: never adopt old categories/channels by name
        }
        void bind(String key,String id) {
            if(!used.add(id))throw new IllegalStateException("Ten sam kanał ma dwa przeznaczenia: "+key);
            resolved.put(key,id);if(!id.equals(state.ids.get(key)))ops.add(new Operation(Kind.BIND,key,id,"",0));
        }
        void category(String key,String name,int pos) {
            Channel c=find(key,"category",name,key.equals("category:countries")?List.of("Kraje MEDIVALRPG"):List.of());
            String profile=key.equals("category:archive")?"archive":key.equals("category:admin")||key.equals("category:countries")?"private":"public";
            if(c==null) {resolved.put(key,"new:"+key);ops.add(new Operation(Kind.CREATE_CATEGORY,key,"",name,0));if(!key.equals("category:info"))ops.add(new Operation(Kind.PROFILE,key,"",profile,0));ops.add(new Operation(Kind.POSITION,key,"","",pos));}
            else {bind(key,c.id);if(!c.name.equals(name))ops.add(new Operation(Kind.RENAME,key,c.id,name,0));
                // Information category keeps all existing ACLs: protected webhook rooms must remain unchanged.
                if(!key.equals("category:info")&&!profile.equals(c.profile))deferredProfiles.add(new Operation(Kind.PROFILE,key,c.id,profile,0));
                if(c.position!=pos)ops.add(new Operation(Kind.POSITION,key,c.id,"",pos));}
        }
        void role(String key,String name,int color) {
            String id=state.ids.get(key);String lookup=id;Role role=id==null?null:state.roles.stream().filter(r->r.id.equals(lookup)).findFirst().orElse(null);
            if(role==null) {
                String adopt=state.ids.getOrDefault("adopt-name:"+key,key.equals("player")?"Whitelisted":key.equals("join")?"non-whitelisted":name);
                var matches=state.roles.stream().filter(r->r.name.equalsIgnoreCase(adopt)).toList();
                if(matches.size()>1)throw new IllegalStateException("Niejednoznaczna rola: "+adopt);
                if(matches.size()==1){role=matches.getFirst();id=role.id;bRoleBind(key,role);}
            }
            if(role==null&&(key.equals("player")||key.equals("join")))throw new IllegalStateException("Brak roli do adopcji: "+key+". Wskaż istniejące ID/nazwę.");
            if(role==null) {resolved.put(key,"new:"+key);ops.add(new Operation(Kind.CREATE_ROLE,key,"",name,color));}
            else resolved.put(key,id); // all existing roles are immutable, including adopted roles above the bot
        }
        void bRoleBind(String key,Role role){ops.add(new Operation(Kind.BIND,key,role.id,role.name,0));}
        void profile(String key,Channel channel,String profile) {if(!profile.equals(channel.profile))ops.add(new Operation(Kind.PROFILE,key,channel.id,profile,0));}
        void channel(Wanted w,int pos) {
            Channel c=find(w.key,w.type,w.name,w.aliases);
            if(c==null) {resolved.put(w.key,"new:"+w.key);ops.add(new Operation(w.type.equals("voice")?Kind.CREATE_VOICE:Kind.CREATE_CHANNEL,w.key,"category:"+w.parent,w.name,0));
                ops.add(new Operation(Kind.PROFILE,w.key,"",w.profile,0));ops.add(new Operation(Kind.POSITION,w.key,"","category:"+w.parent,pos));return;}
            bind(w.key,c.id);
            if(!w.profile.equals("preserve")) {
                profile(w.key,c,w.profile);
                if(!w.key.startsWith("stats:")&&!w.name.equals(c.name))ops.add(new Operation(Kind.RENAME,w.key,c.id,w.name,0));
            }
            if(!Objects.equals(c.parent,resolved.get("category:"+w.parent)))ops.add(new Operation(Kind.MOVE,w.key,c.id,"category:"+w.parent,0));
            if(c.position!=pos)ops.add(new Operation(Kind.POSITION,w.key,c.id,"category:"+w.parent,pos));
        }
    }
}
