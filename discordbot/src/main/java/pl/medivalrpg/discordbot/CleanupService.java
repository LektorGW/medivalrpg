// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.attribute.*;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.channel.middleman.*;
import org.bukkit.configuration.file.FileConfiguration;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only this class can delete channels. Every text history export must be durable and verified first. */
final class CleanupService {
    private final Guild guild;private final LinkStore store;private final Path root;private final FileConfiguration config;
    CleanupService(Guild guild,LinkStore store,Path root,FileConfiguration config){this.guild=guild;this.store=store;this.root=root;this.config=config;}
    private List<HistoryExporter.Entry> history(GuildMessageChannel channel)throws Exception {
        if(!guild.getSelfMember().hasPermission(channel,Permission.VIEW_CHANNEL,Permission.MESSAGE_HISTORY))throw new IllegalStateException("Brak odczytu historii — nie usuwam kanału.");
        List<HistoryExporter.Entry> entries=new ArrayList<>();MessageHistory history=channel.getHistory();
        while(entries.size()<1000) {
            List<Message> page=history.retrievePast(Math.min(100,1000-entries.size())).timeout(20,TimeUnit.SECONDS).complete();
            for(Message m:page)entries.add(entry(m));if(page.size()<100)break;
        }return entries;
    }
    private HistoryExporter.Entry entry(Message m) {
        return new HistoryExporter.Entry(m.getId(),m.getAuthor().getId(),m.getAuthor().getName(),m.getTimeCreated().toInstant().toString(),m.getContentRaw(),
            m.getAttachments().stream().map(Message.Attachment::getUrl).toList(),m.getEmbeds().stream().map(e->Objects.toString(e.getTitle(),"")+"\n"+Objects.toString(e.getDescription(),"")+"\n"+e.getFields().stream().map(f->f.getName()+": "+f.getValue()).reduce("",(a,b)->a+"\n"+b)).toList());
    }
    private HistoryExporter.Result export(GuildChannel channel,List<HistoryExporter.Entry> entries)throws Exception {
        Path directory=root.resolve("archiwum").resolve(LocalDate.now(ZoneId.of("Europe/Warsaw")).toString());
        var result=HistoryExporter.write(directory,channel.getName(),channel.getId(),entries);
        if(!result.verify())throw new IllegalStateException("Eksport nie przeszedł weryfikacji.");
        store.put("export:"+channel.getId(),result.jsonl().getFileName()+"|"+result.jsonSha()+"|"+result.textSha()+"|"+result.count());return result;
    }
    private List<HistoryExporter.Result> exportThreads(GuildChannel parent)throws Exception {
        List<HistoryExporter.Result> receipts=new ArrayList<>();
        if(!(parent instanceof IThreadContainer threads))return receipts;
        Map<String,ThreadChannel> all=new HashMap<>();threads.getThreadChannels().forEach(t->all.put(t.getId(),t));
        var publicThreads=threads.retrieveArchivedPublicThreadChannels().takeAsync(10000).get(60,TimeUnit.SECONDS);
        if(publicThreads.size()>=10000)throw new IllegalStateException("Za dużo wątków do jednego eksportu.");publicThreads.forEach(t->all.put(t.getId(),t));
        if(parent instanceof TextChannel) {
            // Missing Manage Threads must stop deletion, never silently discard inaccessible private threads.
            var privateThreads=threads.retrieveArchivedPrivateThreadChannels().takeAsync(10000).get(60,TimeUnit.SECONDS);
            if(privateThreads.size()>=10000)throw new IllegalStateException("Za dużo prywatnych wątków.");privateThreads.forEach(t->all.put(t.getId(),t));
        }
        for(ThreadChannel thread:all.values())receipts.add(export(thread,history(thread)));return receipts;
    }
    private void migratePins(GuildMessageChannel source)throws Exception {
        String slug=LayoutPlanner.slug(source.getName());
        if(!Set.of("welcome","server-rule","server-rules").contains(slug))return;
        var pins=source.retrievePinnedMessages().timeout(20,TimeUnit.SECONDS).complete();
        if(pins.size()>=50)throw new IllegalStateException("Lista 50 przypięć może być niepełna. Nie usuwam kanału powitania/regulaminu.");
        if(!pins.isEmpty())export(source,pins.stream().map(this::entry).toList());
        String destId=store.settings().get("v3:channel:"+(slug.equals("welcome")?"welcome":"rules"));
        TextChannel destination=destId==null?null:guild.getTextChannelById(destId);if(destination==null)throw new IllegalStateException("Brak nowego kanału docelowego przypięć.");
        for(Message pin:pins)if(!store.settings().containsKey("migrated-pin:"+pin.getId())) {
            var data=entry(pin);String text="Przypięta wiadomość z #"+source.getName()+" · "+data.author()+" · "+data.date()+"\n"+data.content()+"\n"+String.join("\n",data.embeds())+"\n"+String.join("\n",data.attachments());
            for(int offset=0;offset<text.length();offset+=1800)destination.sendMessage(text.substring(offset,Math.min(text.length(),offset+1800))).setAllowedMentions(List.of()).timeout(20,TimeUnit.SECONDS).complete();
            store.put("migrated-pin:"+pin.getId(),destination.getId());
        }
    }
    List<String> remove(GameBridge.Snapshot snapshot,DeletionPermit permit,String code,String actor,boolean contentAllowed)throws Exception {
        if(!contentAllowed)throw new IllegalStateException("Bez Message Content Intent nie wolno usuwać kanałów: eksport treści byłby niepełny.");
        if(!"true".equals(store.settings().get("layout-ready-v3")))throw new IllegalStateException("Najpierw utwórz i sprawdź nowy układ.");
        LayoutService service=new LayoutService(guild,store,config,store.settings());service.verifyReady(snapshot);
        var plan=CleanupPlanner.plan(service.state(snapshot));
        if(!permit.consume(code,actor,plan.fingerprint()))return List.of("Kod nieprawidłowy, wygasły, zużyty albo plan się zmienił. Wykonaj ponownie podgląd.");
        List<String> results=new ArrayList<>();
        for(var target:plan.delete()) {
            if(guild.getJDA().getStatus()!=net.dv8tion.jda.api.JDA.Status.CONNECTED)throw new IllegalStateException("Gateway rozłączony. Nie usuwam kanałów na podstawie starej listy głosowej.");
            GuildChannel channel=guild.getGuildChannelById(target.id());if(channel==null)continue;
            var current=CleanupPlanner.plan(new LayoutService(guild,store,config,store.settings()).state(snapshot));
            if(current.delete().stream().noneMatch(t->t.id().equals(target.id()))) {results.add("Pominięto chroniony lub zajęty kanał: "+channel.getName());continue;}
            if(channel instanceof Category category&&!category.getChannels().isEmpty()){results.add("Pominięto niepustą kategorię: "+category.getName());continue;}
            List<HistoryExporter.Result> receipts=new ArrayList<>();
            boolean requiresExport=channel instanceof GuildMessageChannel||channel instanceof IThreadContainer;
            if(channel instanceof GuildMessageChannel text) {
                var entries=history(text);var archive=export(channel,entries);receipts.add(archive);receipts.addAll(exportThreads(channel));migratePins(text);
                var latest=text.getHistory().retrievePast(1).timeout(20,TimeUnit.SECONDS).complete();
                if(!latest.isEmpty()&&(entries.isEmpty()||!latest.getFirst().getId().equals(entries.getFirst().id())))throw new IllegalStateException("Nowa wiadomość w starym kanale. Wymagany świeży eksport i podgląd.");
                if(!archive.verify())throw new IllegalStateException("Eksport został zmieniony — nie usuwam.");
            } else if(channel instanceof IThreadContainer) {receipts.addAll(exportThreads(channel));receipts.add(export(channel,List.of()));}
            // Voice occupancy and Ticket Tool are checked again immediately before the irreversible request.
            boolean ticket=CleanupPlanner.ticketProtection(new LayoutService(guild,store,config,store.settings()).state(snapshot).channels()).contains(channel.getId());
            if(ticket||Bot.reserved(channel.getId())||channel instanceof AudioChannel audio&&!audio.getMembers().isEmpty()||channel instanceof Category category&&!category.getChannels().isEmpty()) {results.add("Pominięto zajęty/chroniony: "+channel.getName());continue;}
            DeletionGate.afterExport(requiresExport,receipts,()->channel.delete().reason("MEDIVALRPG: osobny kod administratora; nowe kanały sprawdzone; eksport zapisany").timeout(20,TimeUnit.SECONDS).complete());
            store.put("cleanup-deleted:"+channel.getId(),Instant.now().toString());results.add("Usunięto stary kanał/kategorię: "+channel.getName()+" ["+channel.getId()+"]");
            Thread.sleep(500);
        }
        return results;
    }
}
