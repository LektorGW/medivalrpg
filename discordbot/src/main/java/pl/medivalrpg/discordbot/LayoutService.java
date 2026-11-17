// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import com.google.gson.Gson;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.attribute.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.bukkit.configuration.file.FileConfiguration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Fresh channels are namespaced v3. Existing roles and Ticket Tool objects are immutable. */
final class LayoutService {
    record Grant(long allow,long deny) {}
    private final Guild guild;private final LinkStore store;private final FileConfiguration config;
    private final Map<String,String> ids=new HashMap<>();private final Map<String,GuildChannel> channels=new HashMap<>();
    private final Set<String> legacy=new HashSet<>();private long nextRequest;
    private final List<LayoutPlanner.Operation> recoveries=new ArrayList<>();
    private record PendingObject(String name,String type,List<String> before) {}
    private static final long VIEW=Permission.VIEW_CHANNEL.getRawValue(),HISTORY=Permission.MESSAGE_HISTORY.getRawValue(),SEND=Permission.MESSAGE_SEND.getRawValue();
    private static final long WRITE=Permission.getRaw(Permission.MESSAGE_SEND,Permission.MESSAGE_SEND_IN_THREADS,Permission.CREATE_PUBLIC_THREADS,Permission.CREATE_PRIVATE_THREADS,Permission.VOICE_CONNECT,Permission.VOICE_SPEAK,Permission.MESSAGE_ADD_REACTION);
    LayoutService(Guild guild,LinkStore store,FileConfiguration config,Map<String,String> effective) throws Exception {
        this.guild=guild;this.store=store;this.config=config;var settings=store.settings();
        effective.forEach((k,v)->{if(roleKey(k))ids.put(k,v);});
        for(String key:List.of("player","join","ruler","advisor","citizen")){String id=config.getString("roles."+key,"");if(!id.isBlank())ids.put(key,id);}
        var configuredCountries=config.getConfigurationSection("countries.roles");if(configuredCountries!=null)for(String key:configuredCountries.getKeys(false))ids.put("country:"+key,configuredCountries.getString(key,""));
        settings.forEach((k,v)->{if(k.startsWith("v3:"))ids.put(k.substring(3),v);if(k.startsWith("legacy-member:"))legacy.add(k.substring(14));});
        for(String key:List.of("player","join","ruler","advisor","citizen"))ids.put("adopt-name:"+key,config.getString("roles.adopt-by-name."+key,key.equals("player")?"Whitelisted":key.equals("join")?"non-whitelisted":switch(key){case "ruler"->"Władca";case "advisor"->"Doradca";default->"Obywatel";}));
        guild.getChannels().forEach(c->channels.put(c.getId(),c));
        for(var entry:settings.entrySet())if(entry.getKey().startsWith("v3-pending:")) {
            String key=entry.getKey().substring(11);if(ids.containsKey(key)&&channels.containsKey(ids.get(key)))continue;
            PendingObject pending=new Gson().fromJson(entry.getValue(),PendingObject.class);
            var matches=guild.getChannels().stream().filter(c->!pending.before().contains(c.getId())&&c.getName().equals(pending.name()))
                .filter(c->pending.type().equals("category")?c instanceof Category:pending.type().equals("voice")?c instanceof VoiceChannel:c instanceof TextChannel).toList();
            if(matches.size()>1)throw new IllegalStateException("Niejednoznaczne przerwane tworzenie: "+key);
            if(matches.size()==1){ids.put(key,matches.getFirst().getId());recoveries.add(new LayoutPlanner.Operation(LayoutPlanner.Kind.BIND,key,matches.getFirst().getId(),pending.name(),0));}
        }
        if(!"true".equals(settings.get("legacy-captured-v3"))) {
            List<Member> members=guild.getMemberCount()>guild.getMembers().size()?guild.loadMembers().setTimeout(30,TimeUnit.SECONDS).get():guild.getMembers();
            if(members.size()<guild.getMemberCount())throw new IllegalStateException("Brak pełnej listy członków — nie ograniczam dostępu.");
            for(Member m:members)if(!m.getUser().isBot()&&m.getRoles().stream().anyMatch(r->r.getId().equals(roleId("player"))||r.getId().equals(roleId("join"))||r.getName().equalsIgnoreCase("Whitelisted")||r.getName().equalsIgnoreCase("non-whitelisted")||administrative(r)))legacy.add(m.getId());
        }
    }
    static boolean roleKey(String k){return Set.of("join","player","ruler","advisor","citizen").contains(k)||k.startsWith("country:");}
    static boolean administrative(Role r) {
        return Set.of("👑owner","owner","developer","head admin","moderator","support").contains(r.getName().toLowerCase(Locale.ROOT))||
            !Collections.disjoint(r.getPermissions(),EnumSet.of(Permission.ADMINISTRATOR,Permission.MANAGE_SERVER,Permission.MANAGE_ROLES,Permission.MANAGE_CHANNEL,Permission.MANAGE_WEBHOOKS,Permission.KICK_MEMBERS,Permission.BAN_MEMBERS,Permission.MODERATE_MEMBERS));
    }
    private String roleId(String key) {
        String existing=ids.get(key);if(existing!=null&&guild.getRoleById(existing)!=null)return existing;
        String name=ids.get("adopt-name:"+key);if(name==null)return null;
        var matches=guild.getRolesByName(name,true);if(matches.size()>1)throw new IllegalStateException("Niejednoznaczna rola: "+name);return matches.isEmpty()?null:matches.getFirst().getId();
    }
    // JDA getPosition() is the index among ALL guild channels; category ordering needs the index among categories.
    private int categoryIndex(Category cat) {return guild.getCategories().indexOf(cat);}
    private <T>T rest(RestAction<T> action) {
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Przerwano układ.");
        long delay=nextRequest-System.nanoTime();if(delay>0)LockSupport.parkNanos(delay);
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Przerwano układ.");
        nextRequest=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400);return action.timeout(20,TimeUnit.SECONDS).complete();
    }
    private void await(BooleanSupplier ready) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.getAsBoolean()&&System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted())LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        if(!ready.getAsBoolean())throw new IllegalStateException("Oczekiwanie na aktualizację Discorda. Ponów podgląd.");
    }
    private Map<String,Grant> permissions(String profile) {
        Map<String,Grant> out=new TreeMap<>();boolean info=Set.of("readonly","verification","chronicle").contains(profile); // Info rooms below override readability individually
        out.put("r:"+guild.getId(),new Grant(info?VIEW|HISTORY|Permission.USE_APPLICATION_COMMANDS.getRawValue():0,info?WRITE:VIEW|WRITE));
        boolean publicArea=Set.of("public","verified","stats","readonly-members").contains(profile);
        if(publicArea)for(String k:List.of("join","player")) {
            String id=roleId(k);if(id!=null)out.put("r:"+id,new Grant(VIEW|HISTORY|(profile.equals("verified")&&k.equals("player")?SEND:0),0));
        }
        if(publicArea)for(String id:legacy)out.put("m:"+id,new Grant(VIEW|HISTORY|(profile.equals("verified")?SEND:0),0));
        if(profile.startsWith("country:")) {String r=roleId(profile);if(r!=null)out.put("r:"+r,new Grant(VIEW|HISTORY|SEND,0));}
        if(!info)for(Role r:guild.getRoles())if(administrative(r)&&!r.isPublicRole()&&!r.isManaged())out.put("r:"+r.getId(),new Grant(VIEW|HISTORY|(profile.equals("verified")?SEND:0),0));
        if(!info)for(String id:config.getStringList("private-role-ids"))if(guild.getRoleById(id)!=null)out.put("r:"+id,new Grant(VIEW|HISTORY,0));
        long bot=Permission.getRaw(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MANAGE_CHANNEL,Permission.MANAGE_PERMISSIONS);
        out.put("m:"+guild.getSelfMember().getId(),new Grant(bot,profile.equals("stats")?Permission.getRaw(Permission.VOICE_CONNECT,Permission.VOICE_SPEAK):0));
        return out;
    }
    private String intendedProfile(String key,String supplied) {
        if(supplied.equals("readonly")&&!Set.of("channel:rules","channel:welcome","channel:serverinfo").contains(key))return "readonly-members";
        return supplied;
    }
    private Map<String,Grant> actual(IPermissionContainer c) {
        Map<String,Grant> map=new TreeMap<>();for(var p:c.getPermissionOverrides())map.put((p.isMemberOverride()?"m:":"r:")+p.getId(),new Grant(p.getAllowedRaw(),p.getDeniedRaw()));return map;
    }
    private String detect(IPermissionContainer c,List<LayoutPlanner.Country> countries,String key) {
        var current=actual(c);
        String expected=key.startsWith("stats:")?"stats":key.startsWith("country-channel:")?"country:"+key.substring(16):
            key.equals("channel:chronicle")?"chronicle":key.equals("channel:verification")?"verification":Set.of("channel:chat","channel:general","channel:trade","channel:help").contains(key)?"verified":
            key.startsWith("channel:")?"readonly":key.equals("category:archive")?"archive":Set.of("category:countries","category:admin").contains(key)?"private":"public";
        if(current.equals(permissions(intendedProfile(key,expected))))return expected;
        for(String p:List.of("public","readonly","chronicle","verification","verified","private","stats","archive"))if(current.equals(permissions(intendedProfile(key,p))))return p;
        for(var country:countries)if(current.equals(permissions("country:"+country.id())))return "country:"+country.id();return "niestandardowe";
    }
    private List<LayoutPlanner.Country> countries(GameBridge.Snapshot snap){return snap.countries().stream().map(c->new LayoutPlanner.Country(c.id(),c.name(),c.color())).toList();}
    LayoutPlanner.State state(GameBridge.Snapshot snap) {
        channels.clear();guild.getChannels().forEach(c->channels.put(c.getId(),c));
        var country=countries(snap);List<LayoutPlanner.Channel> list=new ArrayList<>();
        for(var c:channels.values()) {
            String key=ids.entrySet().stream().filter(e->e.getValue().equals(c.getId())).map(Map.Entry::getKey).findFirst().orElse("");
            if(c instanceof Category cat)list.add(new LayoutPlanner.Channel(c.getId(),c.getName(),"category",null,categoryIndex(cat),detect(cat,country,key)));
            else if(c instanceof ICategorizableChannel cc)list.add(new LayoutPlanner.Channel(c.getId(),c.getName(),c instanceof TextChannel||c instanceof NewsChannel?"text":c instanceof VoiceChannel?"voice":c instanceof StageChannel?"stage":"forum",cc.getParentCategoryId(),cc.getPositionInCategory(),detect(cc,country,key),c instanceof net.dv8tion.jda.api.entities.channel.middleman.AudioChannel audio&&!audio.getMembers().isEmpty()));
        }
        Map<String,String> values=new HashMap<>(ids);values.put("value:online",snap.online().size()+"/"+snap.maximum());values.put("value:members",Integer.toString(guild.getMemberCount()));
        return new LayoutPlanner.State(list,guild.getRoles().stream().map(r->new LayoutPlanner.Role(r.getId(),r.getName(),r.getColorRaw(),r.isManaged()||administrative(r)||!guild.getSelfMember().canInteract(r))).toList(),values,List.of());
    }
    List<LayoutPlanner.Operation> plan(GameBridge.Snapshot s){var result=new ArrayList<>(recoveries);result.addAll(LayoutPlanner.plan(state(s),countries(s)));return result;}
    List<String> preview(GameBridge.Snapshot s) {
        List<String> out=new ArrayList<>();out.add("Nowy układ od zera. To jest podgląd; nie zmieniono Discorda.");
        guild.getRoles().forEach(r->out.add("ZACHOWAM ROLĘ: "+r.getName()+" ["+r.getId()+"] — bez zmiany nazwy, koloru, uprawnień, pozycji i dawnych przydziałów."));
        plan(s).forEach(op->out.add(op.describe()));out.add("Obecnych członków zachowujących dostęp: "+legacy.size()+". Ticket Tool jest pomijany.");
        return out;
    }
    private void persistLegacy()throws Exception {
        if(!"true".equals(store.settings().get("legacy-captured-v3")))for(Member m:guild.getMembers())for(Role r:m.getRoles())store.put("preexisting-grant:"+m.getId()+":"+r.getId(),"true");
        for(String id:legacy)store.put("legacy-member:"+id,"true");store.put("legacy-captured-v3","true");
    }
    private void bind(String key,String id)throws Exception {store.put(roleKey(key)?key:"v3:"+key,id);ids.put(key,id);recoveries.removeIf(op->op.key().equals(key));}
    private GuildChannel channel(String key,String explicit){String id=explicit.isBlank()?ids.get(key):explicit;return Objects.requireNonNull(channels.get(id),key);}
    private void profile(GuildChannel c,String key,String supplied) {
        if(Bot.reserved(c.getId()))throw new IllegalStateException("Chronione ACL webhooka.");
        var pc=(IPermissionContainer)c;var desired=permissions(intendedProfile(key,supplied));var manager=pc.getManager();
        for(var p:pc.getPermissionOverrides())if(!desired.containsKey((p.isMemberOverride()?"m:":"r:")+p.getId()))manager.removePermissionOverride(p.getIdLong());
        desired.forEach((k,v)->{long id=Long.parseUnsignedLong(k.substring(2));if(k.startsWith("r:"))manager.putRolePermissionOverride(id,v.allow,v.deny);else manager.putMemberPermissionOverride(id,v.allow,v.deny);});rest(manager);
        await(()->actual((IPermissionContainer)guild.getGuildChannelById(c.getId())).equals(desired));
    }
    private void execute(LayoutPlanner.Operation op)throws Exception {
        switch(op.kind()) {
            case BIND->bind(op.key(),op.target());
            case CREATE_ROLE->{
                // Never update an existing role. On uncertain success next run adopts the unique matching name.
                Role r=rest(guild.createRole().setName(op.value()).setColor(op.number()).setPermissions(0L).setMentionable(false));bind(op.key(),r.getId());store.put("owned-role:"+r.getId(),"true");await(()->guild.getRoleById(r.getId())!=null);
            }
            case CREATE_CATEGORY->{
                store.put("v3-pending:"+op.key(),new Gson().toJson(new PendingObject(op.value(),"category",guild.getChannels().stream().map(GuildChannel::getId).toList())));
                boolean info=op.key().equals("category:info");
                Category c=rest(guild.createCategory(op.value()).addPermissionOverride(guild.getPublicRole(),info?VIEW|HISTORY:0,info?WRITE:VIEW|WRITE).addPermissionOverride(guild.getSelfMember(),Permission.getRaw(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MANAGE_CHANNEL,Permission.MANAGE_PERMISSIONS),0));bind(op.key(),c.getId());channels.put(c.getId(),c);await(()->guild.getCategoryById(c.getId())!=null);
            }
            case CREATE_CHANNEL,CREATE_VOICE->{
                store.put("v3-pending:"+op.key(),new Gson().toJson(new PendingObject(op.value(),op.kind()==LayoutPlanner.Kind.CREATE_VOICE?"voice":"text",guild.getChannels().stream().map(GuildChannel::getId).toList())));
                Category parent=(Category)channel(op.target(),"");
                GuildChannel c=op.kind()==LayoutPlanner.Kind.CREATE_VOICE?rest(guild.createVoiceChannel(op.value(),parent).addPermissionOverride(guild.getPublicRole(),0,VIEW|WRITE).addPermissionOverride(guild.getSelfMember(),VIEW|Permission.MANAGE_CHANNEL.getRawValue()|Permission.MANAGE_PERMISSIONS.getRawValue(),0)):
                    rest(guild.createTextChannel(op.value(),parent).addPermissionOverride(guild.getPublicRole(),0,VIEW|WRITE).addPermissionOverride(guild.getSelfMember(),VIEW|Permission.MANAGE_CHANNEL.getRawValue()|Permission.MANAGE_PERMISSIONS.getRawValue(),0));
                bind(op.key(),c.getId());channels.put(c.getId(),c);if(op.kind()==LayoutPlanner.Kind.CREATE_VOICE)store.put("stats-rename:"+c.getId(),Long.toString(System.currentTimeMillis()));await(()->guild.getGuildChannelById(c.getId())!=null);
            }
            case PROFILE->profile(channel(op.key(),op.target()),op.key(),op.value());
            case RENAME->{var c=channel(op.key(),op.target());if(Bot.reserved(c.getId())||op.key().startsWith("stats:"))throw new IllegalStateException("Nazwa chroniona.");rest(c.getManager().setName(op.value()));}
            case MOVE->{var c=(ICategorizableChannel)channel(op.key(),op.target());Category parent=(Category)channel(op.value(),"");rest(c.getManager().setParent(parent));await(()->guild.getGuildChannelById(c.getId()) instanceof ICategorizableChannel actual&&Objects.equals(actual.getParentCategoryId(),parent.getId()));}
            case POSITION->{
                var c=channel(op.key(),op.target());if(c instanceof Category cat){rest(guild.modifyCategoryPositions().selectPosition(cat).moveTo(op.number()));await(()->categoryIndex(guild.getCategoryById(c.getId()))==op.number());}
                else {Category parent=(Category)channel(op.value(),"");var action=c instanceof VoiceChannel?guild.modifyVoiceChannelPositions(parent):guild.modifyTextChannelPositions(parent);rest(action.selectPosition((ICategorizableChannel)c).moveTo(op.number()));await(()->guild.getGuildChannelById(c.getId()) instanceof ICategorizableChannel actual&&actual.getPositionInCategory()==op.number());}
            }
            case ROLE_UPDATE,ROLE_ORDER->throw new IllegalStateException("Zmiana istniejących ról jest zabroniona.");
        }
        store.put("layout-last-completed",op.describe());
    }
    String setupRoles(GameBridge.Snapshot snap)throws Exception {
        var operations=plan(snap);persistLegacy();for(var op:operations)if((op.kind()==LayoutPlanner.Kind.BIND&&roleKey(op.key()))||op.kind()==LayoutPlanner.Kind.CREATE_ROLE)execute(op);
        store.put("setup-approved","true");return "Przyjęto istniejące role; utworzono tylko brakujące rangi/role krajów poniżej bota. Kanały: najpierw /discord admin uklad podglad.";
    }
    List<String> apply(GameBridge.Snapshot snap)throws Exception {
        var operations=plan(snap);persistLegacy();
        Map<String,Object> before=new LinkedHashMap<>();before.put("state",state(snap));Map<String,Map<String,Grant>> acl=new TreeMap<>();for(var c:channels.values())if(c instanceof IPermissionContainer pc)acl.put(c.getId(),actual(pc));before.put("overrides",acl);store.put("layout-backup:"+Instant.now(),new Gson().toJson(before));
        List<String> done=new ArrayList<>();for(var op:operations){execute(op);done.add(op.describe());}
        store.put("setup-approved","true");store.put("layout-approved-v3","true");
        verifyReady(snap);done.add("Nowe kanały utworzone i sprawdzone. Stare NIE zostały usunięte. Usuwanie wymaga osobnej komendy i kodu z podglądu.");return done;
    }
    void verifyReady(GameBridge.Snapshot snap) {
        if(!plan(snap).isEmpty())throw new IllegalStateException("Nowy układ jeszcze nie jest kompletny. Ponów zastosowanie.");
    }
    void provision(GameBridge.Snapshot snap)throws Exception {
        for(var op:plan(snap))if(op.key().startsWith("country:")||op.key().startsWith("country-channel:"))execute(op);
    }
}
