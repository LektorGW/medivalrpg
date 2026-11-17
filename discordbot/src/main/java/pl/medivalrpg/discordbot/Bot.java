// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.audit.ActionType;
import org.bukkit.configuration.file.FileConfiguration;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Gateway callbacks enqueue work; blocking REST and SQLite never execute on the Paper thread. */
final class Bot extends ListenerAdapter {
    private final DiscordBotPlugin plugin;
    private final LinkStore store;
    private final FileConfiguration config;
    private final RateGate limits=new RateGate(System::currentTimeMillis);
    private volatile JDA jda;
    private volatile boolean closed;
    private volatile boolean contentAllowed;
    private final IntentFallback fallback=new IntentFallback();
    private final DeletionPermit deletionPermit=new DeletionPermit(System::currentTimeMillis);
    private final Community community;
    Bot(DiscordBotPlugin plugin,LinkStore store) {this.plugin=plugin;this.store=store;this.config=plugin.getConfig();this.community=new Community(this,store,config);}
    static boolean reserved(String id) {return Set.of("1000000000000001221","1000000000000001222").contains(id);}
    void start() {start(config.getBoolean("bridge.enabled"));}
    private void start(boolean content) {
        if(closed)return;
        contentAllowed=content;
        EnumSet<GatewayIntent> intents=EnumSet.of(GatewayIntent.GUILD_MEMBERS);
        intents.add(GatewayIntent.GUILD_MODERATION);
        intents.add(GatewayIntent.GUILD_MESSAGES);
        intents.add(GatewayIntent.GUILD_VOICE_STATES);
        if(content) intents.add(GatewayIntent.MESSAGE_CONTENT);
        JDA client=JDABuilder.createLight(config.getString("token"),intents)
            .setMemberCachePolicy(MemberCachePolicy.ALL).setChunkingFilter(ChunkingFilter.ALL)
            .enableCache(CacheFlag.VOICE_STATE,CacheFlag.MEMBER_OVERRIDES,CacheFlag.ROLE_TAGS)
            .setAutoReconnect(true).setRequestTimeoutRetry(false).addEventListeners(this).build();
        jda=client;
        if(closed) client.shutdownNow();
    }
    @Override public void onShutdown(ShutdownEvent e) {
        if(closed||e.getJDA()!=jda||e.getCode()!=4014)return;
        boolean retry=fallback.retryWithoutContent(e.getCode(),contentAllowed);
        contentAllowed=false;
        if(retry) {
            plugin.getLogger().warning("Discord odrzucił intencje (4014). Wyłączam most czatu i ponawiam połączenie JEDEN raz bez Message Content Intent. Włącz tę intencję w portalu, aby używać mostu.");
            plugin.enqueue(()->start(false));
        } else plugin.getLogger().warning("Discord nadal odrzuca intencje (4014). Zatrzymano próby logowania. Sprawdź Server Members Intent w portalu; plugin pozostaje bezczynny.");
    }
    String status() {return (jda==null?"inicjalizacja":jda.getStatus().toString())+(config.getBoolean("bridge.enabled")&&!contentAllowed?"; most wyłączony — brak Message Content Intent":"");}
    void stop() {closed=true;if(jda!=null){if(jda.getStatus()==JDA.Status.CONNECTED)jda.getPresence().setActivity(Activity.customStatus("🛠️ Serwer się restartuje…"));jda.shutdown();}}
    @Override public void onButtonInteraction(ButtonInteractionEvent e) {
        if(!Community.ACCEPT.equals(e.getComponentId())||e.getGuild()==null||!ours(e.getGuild().getId())||e.getUser().isBot())return;
        if(!limits.allow("verify-all",30,60000)||!limits.allow("verify:"+e.getUser().getId(),2,60000)){e.reply("Odczekaj minutę przed kolejną próbą.").setEphemeral(true).queue(null,t->{});return;}
        e.deferReply(true).queue(hook->{if(!plugin.enqueue(()->{
            String response;try {response=community.accept(e.getGuild().retrieveMemberById(e.getUser().getId()).timeout(15,TimeUnit.SECONDS).complete());}
            catch(IllegalStateException x){response=x.getMessage();}catch(Exception x){response="Nie udało się zweryfikować. Administrator powinien sprawdzić role i uprawnienia.";}
            hook.editOriginal(Policy.clean(response,1800)).setAllowedMentions(List.of()).queue(null,t->{});
        }))hook.editOriginal("Kolejka jest pełna. Spróbuj później.").queue(null,t->{});},t->{});
    }
    Guild guild() {return jda==null || jda.getStatus()!=JDA.Status.CONNECTED?null:jda.getGuildById(config.getString("guild-id",""));}
    private boolean ours(String id) {return id.equals(config.getString("guild-id"));}
    private void failed() {plugin.getLogger().warning("Discord: operacja odrzucona. Sprawdź ID, uprawnienia i hierarchię ról.");}
    @Override public void onReady(ReadyEvent event) {plugin.enqueue(()->{
        // JDA fires ReadyEvent before the status becomes CONNECTED, so guild() may still return null here.
        Guild g=event.getJDA().getGuildById(config.getString("guild-id",""));if(g==null){failed();return;}
        // Upsert only our missing/changed commands; do not delete unrelated commands of this application.
        // Command writes are tightly rate-limited by Discord, so unchanged ones are skipped and the wait is generous.
        var existing=g.retrieveCommands().timeout(60,TimeUnit.SECONDS).complete();
        for(var command:List.of(
            Commands.slash("polacz","Powiąż konto Minecraft jednorazowym kodem").addOption(OptionType.STRING,"kod","Kod z /discord polacz w grze",true),
            Commands.slash("gracz","Publiczne informacje o graczu").addOption(OptionType.STRING,"nick","Nick gracza",false),
            Commands.slash("kraj","Publiczne informacje o kraju").addOption(OptionType.STRING,"nazwa","Nazwa kraju",true),
            Commands.slash("saldo","Prywatne saldo własnego powiązanego konta"),
            Commands.slash("online","Zalogowani gracze i TPS"),
            Commands.slash("ranking","Dziesięć największych krajów według powierzchni"))) {
            if(existing.stream().noneMatch(c->same(c,command)))g.upsertCommand(command).timeout(120,TimeUnit.SECONDS).complete();
        }
        if("true".equals(store.settings().get("layout-ready-v3")))community.ensureMessage();
        // Map/panel links are informational: a failure here must not hide the verification message above.
        try{community.ensureServerInfo();}catch(Exception e){plugin.getLogger().warning("Discord: nie udało się odświeżyć kanału server-info: "+e.getClass().getSimpleName());}
        try{plugin.getLogger().info("Discord: "+community.ensureRules(rulesDir()));}catch(Exception e){plugin.getLogger().warning("Discord: nie udało się opublikować regulaminu: "+e.getClass().getSimpleName());}
    });}
    static boolean same(net.dv8tion.jda.api.interactions.commands.Command c,net.dv8tion.jda.api.interactions.commands.build.SlashCommandData d) {
        if(c.getType()!=net.dv8tion.jda.api.interactions.commands.Command.Type.SLASH||!c.getName().equals(d.getName())||!c.getDescription().equals(d.getDescription())
            ||c.getOptions().size()!=d.getOptions().size())return false;
        for(int i=0;i<c.getOptions().size();i++) {
            var a=c.getOptions().get(i);var b=d.getOptions().get(i);
            if(!a.getName().equals(b.getName())||!a.getDescription().equals(b.getDescription())||a.getType()!=b.getType()||a.isRequired()!=b.isRequired())return false;
        }
        return true;
    }
    @Override public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        if(e.getGuild()==null || !ours(e.getGuild().getId())) return;
        if(!Set.of("polacz","gracz","kraj","saldo","online","ranking").contains(e.getName()))return;
        if(!limits.allow("all",100,60_000) || !limits.allow("slash:"+e.getUser().getId(),8,60_000)) {
            e.reply("Limit prób. Spróbuj za minutę.").setEphemeral(true).queue(null,t->{});return;
        }
        e.deferReply(true).queue(hook->{
            if(!plugin.enqueue(()->{
                try {slash(e);} catch(Exception ex) {hook.editOriginal("Operacja niedostępna. Spróbuj później.").queue(null,t->{});failed();}
            })) hook.editOriginal("Kolejka pełna. Spróbuj później.").queue(null,t->{});
        },t->{});
    }
    private void reply(SlashCommandInteractionEvent e,String text) {
        e.getHook().editOriginal(Policy.clean(text,1900)).setAllowedMentions(List.of()).queue(null,t->{});
    }
    private void slash(SlashCommandInteractionEvent e) throws Exception {
        var snap=plugin.snapshot();Map<UUID,String> links=store.links();
        UUID own=links.entrySet().stream().filter(x->x.getValue().equals(e.getUser().getId())).map(Map.Entry::getKey).findFirst().orElse(null);
        switch(e.getName()) {
            case "polacz" -> {
                UUID id=store.redeem(e.getOption("kod").getAsString(),e.getUser().getId());
                reply(e,id==null?"Kod nieprawidłowy, wygasły albo jedno z kont jest już powiązane.":"Powiązano konto Minecraft. Role zostaną zsynchronizowane.");
                if(id!=null)reconcile(e.getUser().getId(),snap);
            }
            case "saldo" -> {
                if(own==null){reply(e,"Najpierw powiąż konto przez /discord polacz w grze.");return;}
                // Only the server computes balances; no arbitrary account parameter is accepted.
                plugin.main(()->reply(e,"Twoje saldo: "+GameBridge.balance(own)));
            }
            case "online" -> reply(e,"Online ("+snap.online().size()+"): "+String.join(", ",snap.online())+String.format(Locale.ROOT," | TPS: %.2f",snap.tps()));
            case "ranking" -> reply(e,String.join("\n",snap.countries().stream().sorted(Comparator.comparingInt(GameBridge.Country::chunks).reversed().thenComparing(GameBridge.Country::id))
                .limit(10).map(k->k.name()+": "+k.chunks()+" chunków").toList()));
            case "kraj" -> {
                String name=e.getOption("nazwa").getAsString();
                var country=snap.countries().stream().filter(k->k.name().equalsIgnoreCase(name)).findFirst();
                reply(e,country.map(k->k.name()+" | Powierzchnia: "+k.chunks()+" chunków | Członkowie: "+k.members().size()).orElse("Nie znaleziono kraju."));
            }
            case "gracz" -> {
                String name=e.getOption("nick")==null?null:e.getOption("nick").getAsString();
                UUID id=name==null?own:snap.names().entrySet().stream().filter(x->x.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst().orElse(null);
                if(id==null){reply(e,"Nie znaleziono gracza. Podaj nick albo powiąż konto.");return;}
                reply(e,snap.names().getOrDefault(id,"Gracz")+" | Kraje: "+String.join(", ",snap.countries().stream().filter(k->k.members().containsKey(id)).map(GameBridge.Country::name).toList()));
            }
        }
    }
    Map<String,String> ids() throws Exception {
        Map<String,String> ids=new HashMap<>(store.settings());
        for(String key:List.of("join","player","ruler","advisor","citizen")) {
            String id=config.getString("roles."+key,"");if(!id.isBlank())ids.put(key,id);
        }
        // Country IDs may also be supplied explicitly, e.g. countries.roles.<UUID>: '<role ID>'.
        var section=config.getConfigurationSection("countries.roles");
        if(section!=null)for(String key:section.getKeys(false))ids.put("country:"+key,section.getString(key,""));
        for(String key:List.of("status","events","moderation","guard","chat","ranking","rules","general","trade","help")) {
            String id=config.getString("channels."+key,"");if(!id.isBlank())ids.put("channel:"+key,id);
        }
        Set<String> unique=new HashSet<>();
        for(var entry:ids.entrySet())if(Set.of("join","player","ruler","advisor","citizen").contains(entry.getKey()) || entry.getKey().startsWith("country:")) {
            if(!entry.getValue().matches("[0-9]{15,22}") || !unique.add(entry.getValue()))throw new IllegalStateException("Nieprawidłowe lub powtórzone ID ról.");
        }
        return ids;
    }
    private String channelId(String key) throws Exception {
        String configured=config.getString("channels."+key,"");
        String fresh=store.settings().get("v3:channel:"+key);
        return fresh!=null?fresh:configured.isBlank()?store.settings().getOrDefault("channel:"+key,""):configured;
    }
    private TextChannel channel(String key) throws Exception {
        Guild g=guild();String id=channelId(key);return g==null || id.isBlank() || reserved(id)?null:g.getTextChannelById(id);
    }
    private boolean safeRole(Guild g,Role role) {
        return role!=null && !role.isPublicRole() && !role.isManaged() && g.getSelfMember().canInteract(role)
            && Collections.disjoint(role.getPermissions(),EnumSet.of(Permission.ADMINISTRATOR,Permission.MANAGE_SERVER,Permission.MANAGE_ROLES,Permission.MANAGE_CHANNEL,
                Permission.MANAGE_WEBHOOKS,Permission.BAN_MEMBERS,Permission.KICK_MEMBERS,Permission.MESSAGE_MENTION_EVERYONE));
    }
    private void send(String key,String text) throws Exception {
        TextChannel c=channel(key);if(c==null)return;
        // Private reports/moderation must not leak into public channels, even after configuration mistakes.
        if((key.equals("guard") || key.equals("moderation")) && !isPrivate(c)) {failed();return;}
        c.sendMessage(Policy.clean(text,1900)).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();
    }
    private boolean isPrivate(TextChannel c) {
        var override=c.getPermissionOverride(c.getGuild().getPublicRole());
        if(override==null || !override.getDenied().contains(Permission.VIEW_CHANNEL))return false;
        for(var o:c.getPermissionOverrides())if(o.getAllowed().contains(Permission.VIEW_CHANNEL)) {
            if(o.isMemberOverride()) {
                if(!o.getId().equals(c.getGuild().getSelfMember().getId()) && !o.getId().equals(c.getGuild().getOwnerId()))return false;
            } else {
                Role r=o.getRole();
                if(r==null || !LayoutService.administrative(r) && !config.getStringList("private-role-ids").contains(r.getId()))return false;
            }
        }
        return true;
    }
    void moderation(String text) throws Exception {if(limits.allow("moderation",12,60_000))send("moderation",text);}
    void event(String text) throws Exception {if(limits.allow("events",12,60_000))send("events",text);}
    @Override public void onGuildBan(GuildBanEvent e) {if(ours(e.getGuild().getId()))plugin.enqueue(()->moderation("Discord: ban użytkownika ID "+e.getUser().getId()));}
    @Override public void onGuildAuditLogEntryCreate(GuildAuditLogEntryCreateEvent e) {
        if(ours(e.getGuild().getId()) && e.getEntry().getType()==ActionType.KICK)
            plugin.enqueue(()->moderation("Discord: kick użytkownika ID "+e.getEntry().getTargetId()+" przez ID "+e.getEntry().getUserId()));
    }
    @Override public void onGuildMemberJoin(GuildMemberJoinEvent e) {
        if(!ours(e.getGuild().getId()) || e.getMember().getUser().isBot())return;
        // New accounts receive no role until the persistent rules button is clicked.
        plugin.enqueue(()->{if(store.links().containsValue(e.getUser().getId()))reconcile(e.getUser().getId(),plugin.snapshot());});
    }
    void reconcile(String discord,GameBridge.Snapshot snap) throws Exception {
        Guild g=guild();if(g==null || discord==null)return;
        Map<String,String> ids=ids();Map<UUID,String> links=store.links();
        UUID player=links.entrySet().stream().filter(e->e.getValue().equals(discord)).map(Map.Entry::getKey).findFirst().orElse(null);
        Set<String> desired=new HashSet<>(Policy.wanted(player!=null,null,null,ids));
        if(player!=null)for(var country:snap.countries())if(country.members().containsKey(player))
            desired.addAll(Policy.wanted(true,country.id(),country.members().get(player),ids));
        Member m;
        try {m=g.retrieveMemberById(discord).timeout(15,TimeUnit.SECONDS).complete();}
        catch(net.dv8tion.jda.api.exceptions.ErrorResponseException ex) {
            if(ex.getErrorResponse()==net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MEMBER)return;
            throw ex;
        }
        Set<String> managed=new HashSet<>();
        ids.forEach((key,id)->{if(Set.of("join","player","ruler","advisor","citizen").contains(key)||key.startsWith("country:"))managed.add(id);});
        Set<String> current=new HashSet<>();m.getRoles().forEach(r->current.add(r.getId()));
        if(player==null&&store.settings().containsKey("accepted-rules:"+discord)&&ids.get("join")!=null)desired.add(ids.get("join"));
        Set<String> never=new HashSet<>();if(ids.get("player")!=null)never.add(ids.get("player"));g.getRolesByName("Whitelisted",true).forEach(r->never.add(r.getId()));
        var remove=RolePolicy.removable(current,managed,desired,store.grantedRoles(discord),never);
        for(String id:remove) {
            Role r=g.getRoleById(id);if(safeRole(g,r)){g.removeRoleFromMember(m,r).timeout(15,TimeUnit.SECONDS).complete();store.forgetGrant(discord,id);}
        }
        for(String id:desired)if(!current.contains(id))community.grant(m,g.getRoleById(id));
    }
    String sync(GameBridge.Snapshot snap) throws Exception {
        Guild g=guild();if(g==null)return "Discord nie jest jeszcze połączony.";
        for(String discord:store.links().values())try {reconcile(discord,snap);}catch(Exception e){failed();}
        for(String key:store.settings().keySet())if(key.startsWith("revoke:"))try{reconcile(key.substring(7),snap);}catch(Exception e){failed();}
        return "Zsynchronizowano powiązania i role Discorda.";
    }
    String setup(GameBridge.Snapshot snap) throws Exception {
        Guild g=guild();return g==null?"Discord nie jest jeszcze połączony.":new LayoutService(g,store,config,ids()).setupRoles(snap);
    }
    List<String> layout(GameBridge.Snapshot snap,boolean apply,String actor) throws Exception {
        Guild g=guild();if(g==null)return List.of("Bot nie jest połączony z Discordem. Układ nie został zmieniony.");
        if(snap.wars()==null)return List.of("Kraje są niedostępne. Nie porządkuję kanałów na podstawie niepełnych danych.");
        LayoutService service=new LayoutService(g,store,config,ids());
        if(apply) {
            var done=new ArrayList<>(service.apply(snap));community.ensureMessage();service.verifyReady(snap);store.put("layout-ready-v3","true");community.ensureServerInfo();done.add(community.ensureRules(rulesDir()));return done;
        }
        var out=new ArrayList<>(service.preview(snap));var cleanup=CleanupPlanner.plan(service.state(snap));
        cleanup.keep().forEach(t->out.add("ZACHOWAM: "+t));cleanup.delete().forEach(t->out.add("DO USUNIĘCIA (osobny krok): "+t.name()+" ["+t.id()+"] "+t.type()));
        String code=deletionPermit.issue(actor,cleanup.fingerprint());
        out.add("Usunięcie historii jest nieodwracalne. Najpierw zastosuj i sprawdź nowy układ. Kod jednorazowy na 10 min: "+code);
        out.add("Osobna komenda: /discord admin uklad usun-stare potwierdz "+code);return out;
    }
    List<String> cleanup(GameBridge.Snapshot snap,String code,String actor)throws Exception {
        Guild g=guild();if(g==null)return List.of("Bot nie jest połączony z Discordem. Niczego nie usunięto.");
        if(snap.wars()==null)return List.of("Brak danych Kraje. Nie wolno usuwać kanałów.");
        community.ensureMessage();
        return new CleanupService(g,store,plugin.getDataFolder().toPath(),config).remove(snap,deletionPermit,code,actor,contentAllowed);
    }
    java.nio.file.Path rulesDir(){return plugin.getDataFolder().toPath().resolve("regulamin");}
    /** Re-reads rule files and re-checks the server-info message; config values are those loaded at startup. */
    List<String> refreshContent()throws Exception {
        if(guild()==null)return List.of("Bot nie jest połączony z Discordem.");
        community.ensureServerInfo();return List.of("Kanał server-info sprawdzony.",community.ensureRules(rulesDir()));
    }
    boolean chronicleAvailable()throws Exception {
        Guild g=guild();TextChannel c=channel("chronicle");
        return g!=null&&c!=null&&g.getSelfMember().hasPermission(c,Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_EMBED_LINKS);
    }
    void chronicleSend(Chronicle.Embed fact)throws Exception {
        var c=channel("chronicle");if(c==null)throw new IllegalStateException("Brak kanału kroniki");
        var embed=new EmbedBuilder().setTitle(fact.title()).setDescription(fact.description()).setColor(fact.color())
            .setFooter("MEDIVALRPG • fakty po zdarzeniu • bez danych taktycznych").setTimestamp(Instant.now());
        if(fact.thumbnail()!=null)embed.setThumbnail(fact.thumbnail());
        c.sendMessageEmbeds(embed.build()).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();
    }
    void tick(GameBridge.Snapshot snap) throws Exception {
        if(guild()==null)return;
        community.tick(snap);
        if(config.getBoolean("countries.auto-create") && "true".equals(store.settings().get("layout-ready-v3")))new LayoutService(guild(),store,config,ids()).provision(snap);
        sync(snap);
        String status="MEDIVALRPG | Online: "+snap.online().size()+String.format(Locale.ROOT," | TPS: %.2f",snap.tps());

        TextChannel c=channel("status");
        if(c!=null) {
            String id=store.settings().get("status-message");
            if(id==null) {Message m=c.sendMessage(status).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();store.put("status-message",m.getId());}
            else try {c.editMessageById(id,status).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();}
            catch(net.dv8tion.jda.api.exceptions.ErrorResponseException ex) {
                if(ex.getErrorResponse()!=net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE)throw ex;
                Message m=c.sendMessage(status).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();store.put("status-message",m.getId());
            }
        }
        guardReport();
    }
    private void guardReport() throws Exception {
        TextChannel c=channel("guard");if(c==null || !isPrivate(c))return;
        String date=LocalDate.now(ZoneId.of("Europe/Warsaw")).toString();
        if(date.equals(store.settings().get("guard-date")))return;
        Path path=plugin.getDataFolder().toPath().resolveSibling("Straz").resolve("raporty").resolve(date+".md");
        if(!Files.isRegularFile(path))return;
        // Numeric summary only: do not forward error logs, IPs, account IDs or arbitrary report text.
        StringBuilder summary=new StringBuilder("Straż — raport "+date+" gotowy.\n");
        summary.append(GuardSummary.extract(Files.readString(path)));
        summary.append("Pełny raport dostępny właścicielowi w grze: /straz raport.");
        send("guard",summary.toString());store.put("guard-date",date);
    }
    @Override public void onMessageReceived(MessageReceivedEvent e) {
        if(!e.isFromGuild() || !ours(e.getGuild().getId()) || e.getAuthor().isBot() || e.isWebhookMessage() || e.getMember()==null)return;
        String user=e.getAuthor().getId(),channel=e.getChannel().getId();
        plugin.enqueue(()->{
            boolean linked=store.links().containsValue(user);
            boolean verified=config.getStringList("verification.channels").contains(channel);
            for(String key:List.of("chat","general","trade","help"))verified|=channel.equals(channelId(key));
            if(verified && !reserved(channel) && !linked) {
                // Verification is enforced by channel ACLs. Never delete historical or newly posted messages.
                return;
            }
            if(config.getBoolean("bridge.enabled") && contentAllowed && channel.equals(channelId("chat")) && !reserved(channel) && linked
                && limits.allow("chat-all",30,60_000) && limits.allow("chat:"+user,5,60_000)) {
                String text="[Discord] "+Policy.clean(e.getMember().getEffectiveName(),32)+": "+Policy.clean(e.getMessage().getContentRaw(),300);
                plugin.main(()->{for(var p:org.bukkit.Bukkit.getOnlinePlayers())if(GameBridge.authenticated(p))p.sendMessage(net.kyori.adventure.text.Component.text(text));});
            }
        });
    }
    void gameChat(String name,String message) throws Exception {
        if(config.getBoolean("bridge.enabled") && contentAllowed && limits.allow("chat-out",30,60_000))send("chat","[Minecraft] "+name+": "+message);
    }
}
