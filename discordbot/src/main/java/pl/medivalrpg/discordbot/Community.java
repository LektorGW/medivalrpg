// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.bukkit.configuration.file.FileConfiguration;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class Community {
    static final String ACCEPT="medivalrpg:accept-rules:v1";
    private final Bot bot;private final LinkStore store;private final FileConfiguration config;
    private long lastPresence;private int rotation;
    Community(Bot bot,LinkStore store,FileConfiguration config){this.bot=bot;this.store=store;this.config=config;}
    boolean verified(Member member)throws Exception {
        var ids=bot.ids();return member.getRoles().stream().anyMatch(r->r.getId().equals(ids.get("player"))||r.getId().equals(ids.get("join"))||r.getName().equalsIgnoreCase("Whitelisted")||r.getName().equalsIgnoreCase("non-whitelisted")||LayoutService.administrative(r));
    }
    boolean grant(Member member,Role role)throws Exception {
        if(role==null||member.getUser().isBot()||member.getRoles().contains(role))return false;
        if(role.isManaged()||LayoutService.administrative(role)||!member.getGuild().getSelfMember().canInteract(role))throw new IllegalStateException("Rola jest ponad botem lub chroniona. Właściciel powinien przesunąć MedivalRPG pod ----Bots----.");
        member.getGuild().addRoleToMember(member,role).timeout(15,TimeUnit.SECONDS).complete();
        if(!store.settings().containsKey("preexisting-grant:"+member.getId()+":"+role.getId()))store.recordGrant(member.getId(),role.getId());return true;
    }
    String accept(Member member)throws Exception {
        if(member.getUser().isBot())return "Konta botów są pomijane.";
        if(verified(member))return "Twoje konto jest już zweryfikowane. Zachowano obecne role.";
        int days=Math.max(0,config.getInt("verification.minimum-account-age-days",0));
        if(!VerificationPolicy.oldEnough(member.getUser().getTimeCreated().toInstant(),Instant.now(),days))return "Konto Discord jest za młode. Minimalny wiek: "+days+" dni.";
        String role=bot.ids().get("join");
        if(role==null)return "Rola weryfikacji nie została jeszcze ustawiona przez administratora.";
        grant(member,member.getGuild().getRoleById(role));store.put("accepted-rules:"+member.getId(),Instant.now().toString());
        return "Dziękujemy! Przyznano rolę non-whitelisted/Przybysz. Powiąż konto z grą przez /discord polacz, a potem /polacz.";
    }
    void ensureMessage()throws Exception {
        Guild g=bot.guild();if(g==null)return;String id=store.settings().get("v3:channel:verification");if(id==null)return;
        TextChannel channel=g.getTextChannelById(id);if(channel==null)throw new IllegalStateException("Brak nowej weryfikacji.");
        if(!g.getSelfMember().hasPermission(channel,net.dv8tion.jda.api.Permission.VIEW_CHANNEL,net.dv8tion.jda.api.Permission.MESSAGE_HISTORY,net.dv8tion.jda.api.Permission.MESSAGE_SEND,net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS))throw new IllegalStateException("Weryfikacja wymaga odczytu historii, wysyłania i Embed Links.");
        String key="verification-message:"+id,saved=store.settings().get(key);
        if(saved!=null)try {channel.retrieveMessageById(saved).timeout(15,TimeUnit.SECONDS).complete();return;}
        catch(net.dv8tion.jda.api.exceptions.ErrorResponseException e){if(e.getErrorResponse()!=net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE)throw e;}
        for(Message m:channel.getHistory().retrievePast(50).timeout(15,TimeUnit.SECONDS).complete())if(m.getAuthor().getId().equals(g.getSelfMember().getId())&&m.getButtons().stream().anyMatch(b->ACCEPT.equals(b.getId()))) {store.put(key,m.getId());return;}
        List<String> rules=config.getStringList("verification.rules");
        String text=String.join("\n",rules.stream().map(rule->"• "+Policy.clean(rule,500)).toList())+"\n\nKliknij przycisk, aby zaakceptować regulamin. Po powiązaniu konta z grą otrzymasz rolę Gracz.";
        Message m=channel.sendMessageEmbeds(new EmbedBuilder().setTitle("Witaj w MEDIVALRPG").setDescription(text.length()>3500?text.substring(0,3500):text).setColor(0xB08D36).build())
            .setActionRow(Button.success(ACCEPT,"Akceptuję regulamin")).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();store.put(key,m.getId());
    }
    /** One bot message with map/panel links; edited only when the configured content changes. */
    void ensureServerInfo()throws Exception {
        Guild g=bot.guild();if(g==null||!config.getBoolean("server-info.enabled",true))return;String id=store.settings().get("v3:channel:serverinfo");if(id==null)return;
        TextChannel channel=g.getTextChannelById(id);if(channel==null)throw new IllegalStateException("Brak kanału serwer-info.");
        if(!g.getSelfMember().hasPermission(channel,net.dv8tion.jda.api.Permission.VIEW_CHANNEL,net.dv8tion.jda.api.Permission.MESSAGE_HISTORY,net.dv8tion.jda.api.Permission.MESSAGE_SEND,net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS))throw new IllegalStateException("Serwer-info wymaga odczytu historii, wysyłania i Embed Links.");
        var info=ServerInfo.of(config.getString("server-info.address",""),config.getString("server-info.map-url",ServerInfo.MAP_DEFAULT),config.getString("server-info.panel-url",ServerInfo.PANEL_DEFAULT));
        var embed=new EmbedBuilder().setTitle(info.title()).setDescription(info.description()).setColor(0xB08D36).build();
        List<Button> buttons=info.links().stream().map(l->Button.link(l.url(),l.label())).toList();
        String key="serverinfo-message:"+id,saved=store.settings().get(key),hash=info.fingerprint();
        if(saved!=null)try {
            channel.retrieveMessageById(saved).timeout(15,TimeUnit.SECONDS).complete();
            if(hash.equals(store.settings().get("serverinfo-hash:"+id)))return;
            var edit=channel.editMessageEmbedsById(saved,embed);(buttons.isEmpty()?edit.setComponents():edit.setActionRow(buttons)).timeout(15,TimeUnit.SECONDS).complete();
            store.put("serverinfo-hash:"+id,hash);return;
        }catch(net.dv8tion.jda.api.exceptions.ErrorResponseException e){if(e.getErrorResponse()!=net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE)throw e;}
        var send=channel.sendMessageEmbeds(embed).setAllowedMentions(List.of());if(!buttons.isEmpty())send.setActionRow(buttons);
        Message m=send.timeout(15,TimeUnit.SECONDS).complete();store.put(key,m.getId());store.put("serverinfo-hash:"+id,hash);
    }
    /** Rule documents (*.md from the plugin folder) as bot embeds in the rules channel; reposted only after a change. */
    String ensureRules(java.nio.file.Path dir)throws Exception {
        Guild g=bot.guild();if(g==null)return "Discord nie jest połączony.";String id=store.settings().get("v3:channel:rules");if(id==null)return "Brak kanału regulaminu. Najpierw zastosuj układ.";
        if(!java.nio.file.Files.isDirectory(dir))return "Brak folderu "+dir.getFileName()+" z plikami .md regulaminu.";
        SortedMap<String,String> docs=new TreeMap<>();
        try(var files=java.nio.file.Files.list(dir)){for(var f:files.filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList())docs.put(f.getFileName().toString(),java.nio.file.Files.readString(f));}
        var book=RulesBook.of(docs);if(book.pages().isEmpty())return "Folder regulaminu jest pusty. Niczego nie zmieniono.";
        TextChannel channel=g.getTextChannelById(id);if(channel==null)throw new IllegalStateException("Brak kanału regulaminu.");
        if(!g.getSelfMember().hasPermission(channel,net.dv8tion.jda.api.Permission.VIEW_CHANNEL,net.dv8tion.jda.api.Permission.MESSAGE_HISTORY,net.dv8tion.jda.api.Permission.MESSAGE_SEND,net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS))throw new IllegalStateException("Regulamin wymaga odczytu historii, wysyłania i Embed Links.");
        String key="rules-messages:"+id,hashKey="rules-hash:"+id,hash=book.fingerprint();
        List<String> saved=Arrays.stream(store.settings().getOrDefault(key,"").split(",")).filter(s->!s.isBlank()).toList();
        boolean present=!saved.isEmpty();
        if(hash.equals(store.settings().get(hashKey)))for(String m:saved)try{channel.retrieveMessageById(m).timeout(15,TimeUnit.SECONDS).complete();}
            catch(net.dv8tion.jda.api.exceptions.ErrorResponseException e){if(e.getErrorResponse()!=net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE)throw e;present=false;}
        if(present&&hash.equals(store.settings().get(hashKey)))return "Regulamin bez zmian ("+book.pages().size()+" wiadomości).";
        // Only our own previously recorded messages are removed; other history in the channel stays untouched.
        for(String m:saved)try{channel.deleteMessageById(m).timeout(15,TimeUnit.SECONDS).complete();}
            catch(net.dv8tion.jda.api.exceptions.ErrorResponseException e){if(e.getErrorResponse()!=net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE)throw e;}
        store.put(hashKey,"");List<String> posted=new ArrayList<>();
        for(var page:book.pages()) {
            Message m=channel.sendMessageEmbeds(new EmbedBuilder().setTitle(page.title()).setDescription(page.text()).setColor(0xB08D36).build()).setAllowedMentions(List.of()).timeout(15,TimeUnit.SECONDS).complete();
            posted.add(m.getId());store.put(key,String.join(",",posted)); // recorded per message, so a failed run never leaves untracked copies
        }
        store.put(hashKey,hash);return "Opublikowano regulamin: "+book.pages().size()+" wiadomości z "+docs.size()+" plików.";
    }
    void tick(GameBridge.Snapshot snap)throws Exception {
        Guild g=bot.guild();if(g==null)return;long now=System.currentTimeMillis();
        if(now-lastPresence>=Math.max(60,config.getInt("presence.interval-seconds",60))*1000L) {
            Map<String,String> values=new HashMap<>();values.put("online",Integer.toString(snap.online().size()));if(snap.maximum()>0)values.put("max",Integer.toString(snap.maximum()));
            if(snap.wars()!=null){values.put("kraje",Integer.toString(snap.countries().size()));values.put("wojny",snap.wars().toString());}
            String era=snap.era()==null?config.getString("presence.epoka",""):snap.era();if(!era.isBlank())values.put("epoka",era);
            List<PresenceFormatter.Line> templates=new ArrayList<>();for(var line:config.getMapList("presence.lines"))templates.add(new PresenceFormatter.Line(Objects.toString(line.get("type"),""),Objects.toString(line.get("text"),"")));
            var formatted=PresenceFormatter.format(templates,values);if(!formatted.isEmpty()){var line=formatted.get(Math.floorMod(rotation++,formatted.size()));g.getJDA().getPresence().setActivity(line.type().equals("PLAYING")?Activity.playing(line.text()):Activity.customStatus(line.text()));lastPresence=now;}
        }
        Map<String,String> names=new LinkedHashMap<>();names.put("online","👥 Online: "+snap.online().size()+"/"+snap.maximum());if(snap.wars()!=null)names.put("countries","🏰 Countries: "+snap.countries().size());names.put("members","👤 Members: "+g.getMemberCount());
        var settings=store.settings();for(var name:names.entrySet()) {
            String id=settings.get("v3:stats:"+name.getKey());VoiceChannel channel=id==null?null:g.getVoiceChannelById(id);if(channel==null)continue;
            long last=Long.parseLong(settings.getOrDefault("stats-rename:"+id,"0"));
            if(StatsRenamePolicy.due(channel.getName(),name.getValue(),last,now)) {
                // Durable reservation BEFORE REST. Failed/uncertain requests also consume the 10 minute slot.
                store.put("stats-rename:"+id,Long.toString(now));channel.getManager().setName(name.getValue()).timeout(20,TimeUnit.SECONDS).complete();
            }
        }
    }
}
