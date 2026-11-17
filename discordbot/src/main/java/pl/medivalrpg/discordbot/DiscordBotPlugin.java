// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.discordbot;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;

public final class DiscordBotPlugin extends JavaPlugin implements Listener {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),
        r->{Thread t=new Thread(r,"MEDIVALRPG-DiscordBot");t.setDaemon(true);return t;});
    private final RateGate gate=new RateGate(System::currentTimeMillis);
    private volatile Bot bot;
    private volatile boolean stopping;
    private volatile GameBridge.Snapshot snapshot=new GameBridge.Snapshot(List.of(),List.of(),Map.of(),20);
    private LinkStore store;
    private ChronicleService chronicle;
    private ChronicleHooks chronicleHooks;
    @Override public void onEnable() {
        getLogger().info("DiscordBot by Maciej Kaczmarczyk — lektoro.pl"); saveDefaultConfig();
        // Rules are owner-editable files; the bundled one is written only when the folder does not exist yet.
        if(!new java.io.File(getDataFolder(),"regulamin").exists())saveResource("regulamin/2-regulamin-rp.md",false);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event -> {
            var root=Commands.literal("discord").executes(c->command(c.getSource().getSender(),"pomoc"));
            for(String sub:List.of("pomoc","polacz","odlacz","status")) root.then(Commands.literal(sub).executes(c->command(c.getSource().getSender(),sub)));
            root.then(Commands.literal("admin").requires(s->s.getSender().hasPermission("discordbot.admin"))
                .then(Commands.literal("kronika").executes(c->command(c.getSource().getSender(),"chronicle")))
                .then(Commands.literal("przygotuj").then(Commands.literal("potwierdz").executes(c->command(c.getSource().getSender(),"setup"))))
                .then(Commands.literal("tresci").then(Commands.literal("odswiez").executes(c->command(c.getSource().getSender(),"content"))))
                .then(Commands.literal("synchronizuj").executes(c->command(c.getSource().getSender(),"sync")))
                .then(Commands.literal("uklad")
                    .then(Commands.literal("podglad").executes(c->command(c.getSource().getSender(),"layout-preview")))
                    .then(Commands.literal("zastosuj").then(Commands.literal("potwierdz").executes(c->command(c.getSource().getSender(),"layout-apply"))))
                    .then(Commands.literal("usun-stare").then(Commands.literal("potwierdz").then(Commands.argument("kod",StringArgumentType.word()).executes(c->command(c.getSource().getSender(),"layout-delete:"+StringArgumentType.getString(c,"kod"))))))));
            event.registrar().register(root.build(),"Discord MEDIVALRPG");
        });
        Bukkit.getPluginManager().registerEvents(this,this);
        // Collection is independent of gateway login and works in a token-free isolated Paper.
        enqueue(()->chronicle=new ChronicleService(this));
        chronicleHooks=new ChronicleHooks(this);chronicleHooks.register();
        Bukkit.getScheduler().runTaskTimer(this,()->{
            chronicleHooks.poll();enqueue(()->{if(chronicle!=null)chronicle.tick(bot);});
        },20,1200);
        if(!getConfig().getBoolean("enabled") || getConfig().getString("token","").isBlank()) {
            getLogger().warning("Bot bezczynny: brak tokenu lub enabled=false. Konfigurację uzupełnia właściciel."); return;
        }
        // Validate known channels before connecting. Existing webhook destinations belong to other modules.
        for(String key:List.of("status","events","moderation","guard","chat")) {
            if(Bot.reserved(getConfig().getString("channels."+key,""))) {
                getLogger().severe("Bot bezczynny: kanał zarezerwowany dla Nowości lub katalogu komend."); return;
            }
        }
        int seconds=Math.max(60,getConfig().getInt("sync-seconds",60));
        Bukkit.getScheduler().runTaskTimer(this,()->{snapshot=GameBridge.snapshot(); enqueue(()->{if(bot!=null) bot.tick(snapshot);});},20,seconds*20L);
        enqueue(()->{
            getDataFolder().mkdirs();
            store=new LinkStore("jdbc:sqlite:"+getDataFolder().toPath().resolve("links.db"),System::currentTimeMillis);
            bot=new Bot(this,store);
            bot.start();
        });
    }
    boolean enqueue(ThrowingRunnable task) {
        if(stopping) return false;
        // JDA rethrows async failures with a foreign stack, so remember where the task was queued from.
        String origin=StackWalker.getInstance().walk(s->s.skip(1).findFirst().map(f->f.getFileName()+":"+f.getLineNumber()).orElse("?"));
        try { worker.execute(()->{try { task.run(); } catch(Exception | LinkageError e) {
            // Never log exception messages/stack traces from a network client carrying credentials.
            // Class names and our own stack frames only: enough to locate the failure without any message text.
            Throwable cause=e.getCause();
            String where=java.util.Arrays.stream(e.getStackTrace()).filter(f->f.getClassName().startsWith("pl.medivalrpg.discordbot."))
                .findFirst().map(f->", "+f.getFileName()+":"+f.getLineNumber()).orElse("");
            getLogger().warning("Operacja DiscordBot nie powiodła się ("+e.getClass().getSimpleName()+(cause!=null?" ← "+cause.getClass().getSimpleName():"")
                +where+", zlecone w "+origin+"). Sprawdź uprawnienia i konfigurację.");
        }}); return true; } catch(RejectedExecutionException e) { return false; }
    }
    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }
    void main(Runnable action) { if(!stopping) Bukkit.getScheduler().runTask(this,action); }
    GameBridge.Snapshot snapshot() { return snapshot; }
    void chronicleAccept(Chronicle.Fact fact)throws Exception {if(chronicle!=null)chronicle.accept(fact);}
    private int command(org.bukkit.command.CommandSender sender,String sub) {
        if(sub.equals("chronicle")){
            if(!sender.hasPermission("discordbot.admin"))return 0;
            enqueue(()->{String result=chronicle==null?"Kronika uruchamia się.":chronicle.status();main(()->sender.sendMessage(Component.text(result)));});return 1;
        }
        if(sub.equals("pomoc")) {
            sender.sendMessage(Component.text("Discord: /discord polacz — kod na 5 minut; /discord odlacz; /discord status.")
                .clickEvent(ClickEvent.suggestCommand("/discord polacz")));
            if(sender.hasPermission("discordbot.admin")) {
                sender.sendMessage(Component.text("Kronika wojen — stan kolejki: /discord admin kronika").clickEvent(ClickEvent.runCommand("/discord admin kronika")));
                sender.sendMessage(Component.text("Układ — podgląd: /discord admin uklad podglad").clickEvent(ClickEvent.runCommand("/discord admin uklad podglad")));
                sender.sendMessage(Component.text("Układ — zastosuj: /discord admin uklad zastosuj potwierdz").clickEvent(ClickEvent.suggestCommand("/discord admin uklad zastosuj potwierdz")));
                sender.sendMessage(Component.text("Regulamin i server-info — publikuj po zmianie plików regulamin/*.md: /discord admin tresci odswiez").clickEvent(ClickEvent.suggestCommand("/discord admin tresci odswiez")));
                sender.sendMessage(Component.text("Setup: /discord admin przygotuj potwierdz").clickEvent(ClickEvent.suggestCommand("/discord admin przygotuj potwierdz")));
                sender.sendMessage(Component.text("Usunięcie starych kanałów — osobny kod z podglądu: /discord admin uklad usun-stare potwierdz <kod>").clickEvent(ClickEvent.suggestCommand("/discord admin uklad usun-stare potwierdz ")));
            }
            return 1;
        }
        if(sub.equals("status")) { sender.sendMessage("DiscordBot: "+(bot==null?"bezczynny":bot.status())+". Pomoc: /discord pomoc"); return 1; }
        if(bot==null) { sender.sendMessage("Bot nie jest połączony z Discordem. Brak aktywnej konfiguracji; niczego nie zmieniono."); return 0; }
        if(sub.equals("setup") || sub.equals("sync") || sub.equals("content") || sub.startsWith("layout-")) {
            if(!sender.hasPermission("discordbot.admin")) return 0;
            if(sender instanceof Player player && !GameBridge.authenticated(player)) {sender.sendMessage("Najpierw zaloguj się w grze.");return 0;}
            boolean accepted=enqueue(()->{
                try {
                    String actor=sender instanceof Player p?p.getUniqueId().toString():"console";
                    List<String> results=sub.startsWith("layout-delete:")?bot.cleanup(snapshot,sub.substring(14),actor):sub.startsWith("layout-")?bot.layout(snapshot,sub.equals("layout-apply"),actor):sub.equals("content")?bot.refreshContent():
                        List.of(sub.equals("setup")?bot.setup(snapshot):bot.sync(snapshot));
                    main(()->results.forEach(result->sender.sendMessage(Component.text(Policy.clean(result,1000)))));
                }
                catch(Exception e) {
                    // Own IllegalStateException texts and JDA's missing-permission name are safe to show; other messages are not.
                    String why=e instanceof net.dv8tion.jda.api.exceptions.InsufficientPermissionException p?" Brak uprawnienia bota: "+p.getPermission().getName()+".":
                        e instanceof IllegalStateException?" Powód: "+Policy.clean(String.valueOf(e.getMessage()),300):"";
                    main(()->sender.sendMessage("Operacja nie powiodła się."+why+" Część struktury mogła już powstać."));throw e;}
            });
            sender.sendMessage(accepted?"Zlecono operację Discorda. Wynik pojawi się na czacie.":"Kolejka pełna. Spróbuj później."); return 1;
        }
        if(!(sender instanceof Player p) || !GameBridge.authenticated(p)) {sender.sendMessage("Wymagane zalogowane konto w grze.");return 0;}
        if(!gate.allow("game:"+p.getUniqueId(),2,60_000)) {sender.sendMessage("Odczekaj minutę przed kolejną próbą.");return 0;}
        UUID id=p.getUniqueId();
        boolean accepted=enqueue(()->{
            String text;
            try {
                if(sub.equals("polacz")) text="Na Discordzie wpisz /polacz kod:"+store.issue(id)+" (ważny 5 min). Nie udostępniaj kodu.";
                else {String discord=store.links().get(id);if(discord!=null)store.put("revoke:"+discord,"true");store.unlink(id);bot.reconcile(discord,snapshot);text="Odłączono konto. Zachowano Whitelisted i dawne role. Odbierane są tylko pozostałe role nadane przez bota.";}
            } catch(IllegalStateException e) {text=e.getMessage();}
            catch(Exception e) {text="Operacja nie powiodła się. Sprawdź /discord status i skontaktuj się z administracją.";}
            String result=text;main(()->{if(p.isOnline() && GameBridge.authenticated(p)) p.sendMessage(Component.text(result));});
        });
        if(!accepted) sender.sendMessage("Kolejka pełna. Spróbuj później.");return 1;
    }
    @EventHandler public void quit(PlayerQuitEvent e) { UUID id=e.getPlayer().getUniqueId();enqueue(()->{if(store!=null)store.invalidate(id);}); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void kick(PlayerKickEvent e) {
        String name=e.getPlayer().getName(); enqueue(()->{if(bot!=null)bot.moderation("Minecraft: rozłączono gracza "+name+". Szczegóły w dzienniku serwera.");});
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void chat(AsyncChatEvent e) {
        if(bot==null || !getConfig().getBoolean("bridge.enabled"))return;
        String name=e.getPlayer().getName(); UUID id=e.getPlayer().getUniqueId();
        String message=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message());
        // Country chat is cancelled at HIGH by Kraje. Honor restricted viewers from other chat plugins too.
        Set<UUID> viewers=new HashSet<>();
        for(var a:e.viewers())if(a instanceof Player p)viewers.add(p.getUniqueId());
        main(()->{
            Player sender=Bukkit.getPlayer(id);
            if(sender==null || !GameBridge.authenticated(sender))return;
            for(Player p:Bukkit.getOnlinePlayers())if(GameBridge.authenticated(p) && !viewers.contains(p.getUniqueId()))return;
            enqueue(()->bot.gameChat(name,message));
        });
    }
    @Override public void onDisable() {
        stopping=true;
        if(bot!=null)bot.stop();
        worker.shutdownNow();
        try { if(worker.awaitTermination(3,TimeUnit.SECONDS)){if(store!=null)store.close();if(chronicle!=null)chronicle.close();} }
        catch(Exception ignored) { /* no credentials in logs */ }
    }
}
