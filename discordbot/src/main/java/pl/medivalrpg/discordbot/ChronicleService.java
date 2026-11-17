// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import java.time.*;
import java.util.*;

/** Runs on the Discord worker even with the gateway disabled; no credentials are needed to collect facts. */
final class ChronicleService implements AutoCloseable {
    private final DiscordBotPlugin plugin;
    private final ChronicleJournal journal;
    private long lastWarning;
    ChronicleService(DiscordBotPlugin plugin)throws Exception {
        this.plugin=plugin;plugin.getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        journal=new ChronicleJournal("jdbc:sqlite:"+plugin.getDataFolder().toPath().resolve("links.db"));
    }
    void accept(Chronicle.Fact fact)throws Exception {
        if(!plugin.getConfig().getBoolean("chronicle.enabled",true))return;
        if(fact.npc()&&!plugin.getConfig().getBoolean("chronicle.npc",true))return;
        if(fact.kind().importance<Math.max(1,Math.min(3,plugin.getConfig().getInt("chronicle.min-importance",1))))return;
        if(journal.add(fact))plugin.getLogger().info("Kronika: zapisano fakt "+fact.kind()+" do kolejki (bez danych taktycznych).");
    }
    String status()throws Exception {return "Kronika wojen: "+(plugin.getConfig().getBoolean("chronicle.enabled",true)?"włączona":"wyłączona")+", kolejka: "+journal.pendingCount()+". Grupowanie 5 min, maks. 20 wiadomości/h, podsumowanie o 21:00 Europe/Warsaw.";}
    void tick(Bot bot)throws Exception {
        if(!plugin.getConfig().getBoolean("chronicle.enabled",true))return;
        long now=System.currentTimeMillis();
        var cutoff=Chronicle.cutoff(Instant.ofEpochMilli(now));
        // Calendar interval (DST safe), at most one catch-up report for the last ended day.
        journal.report(cutoff.toInstant().toEpochMilli(),Chronicle.daily(journal.between(cutoff.minusDays(1).toInstant().toEpochMilli(),cutoff.toInstant().toEpochMilli()),cutoff.toLocalDate().toString()));
        journal.prune(now);
        if(bot==null||!bot.chronicleAvailable())return;
        var report=journal.pendingReport();
        var batch=report==null?journal.batch(now):List.<Chronicle.Fact>of();
        if(report==null&&batch.isEmpty())return;
        if(!journal.reserve(batch,report==null?null:report.getKey(),now))return;
        try {
            bot.chronicleSend(report==null?Chronicle.format(batch):report.getValue());
            journal.delivered(batch,report==null?null:report.getKey());
        }catch(Exception e){
            // REST may have reached Discord: keep reserved, do not duplicate automatically after restart.
            if(now-lastWarning>60000){lastWarning=now;plugin.getLogger().warning("Kronika: wysyłka niepotwierdzona. Zapis pozostaje w links.db; bez automatycznego ponawiania, aby nie dublować wpisów.");}
        }
    }
    public void close()throws Exception{journal.close();}
}
