// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.kolej;

import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import pl.medivalrpg.kolej.data.RailData;
import pl.medivalrpg.kolej.permit.NpcRailConsent;
import pl.medivalrpg.kolej.permit.NpcRailConsent.*;
import pl.medivalrpg.kolej.world.AtomicJsonFile;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.skarbiec.api.SkarbiecApi;

/**
 * Consent of an NPC host country for the private carrier. Asks Armia ({@code npcRailConsent}, softdepend, reflection,
 * null-safe; without Armia the previous unconditional consent applies). Fees are paid to {@code country:<host>} by the
 * payer of the project, at most once per host and payer, through a durable INTENT -> transfer -> bank barrier -> PAID
 * protocol. Main thread only.
 */
final class NpcConsentService implements AutoCloseable {
    private final KolejPlugin plugin;
    private final AtomicJsonFile<Ledger> file;
    private Ledger ledger;
    private String loadFailure;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private boolean busy;

    NpcConsentService(KolejPlugin plugin) {
        this.plugin = plugin;
        file = new AtomicJsonFile<>(plugin.getDataFolder().toPath().resolve("npc-consent-fees.json"), Ledger.class);
        try {ledger = file.read(new Ledger()); ledger.validate();}
        catch (Exception e) {ledger = new Ledger(); loadFailure = "Dziennik opłat za zgody jest uszkodzony; opłaty wstrzymane do kontroli.";plugin.getLogger().severe(loadFailure + " " + e);}
    }

    /** Fees already paid from an account (the network budget includes them in its approved limit). */
    long paidFrom(String account) {return ledger.paidFrom(account);}

    void request(CommandSender sender, KrajInfo host, String payerMode) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        UUID id = host.id();
        if (plugin.data().permits.contains(id)) {plugin.message(sender, host.name() + " już zgodził się na przejazd i roboty przewoźnika."); return;}
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(id);
        if (until != null && until > now) throw new IllegalArgumentException("Władca " + host.name() + " niedawno odmówił. Ponów za " + Math.max(1, (until - now + 59_999) / 60_000) + " min.");
        if (ledger.blocked(id)) throw new IllegalArgumentException("Nierozliczona opłata za zgodę " + host.name() + " — wymagana kontrola administratora.");
        String applicant = "kraj-wlasciciela".equals(plugin.getConfig().getString("zgody.npc-wnioskodawca", "przewoznik")) && plugin.data().ownerCountry != null
            ? plugin.data().ownerCountry.toString() : "";
        long base = Math.max(0, plugin.getConfig().getLong("zgody.npc-oplata-bazowa", 500));
        Answer answer = NpcRailConsent.decide(ask(id, applicant, base), !applicant.isEmpty(),
            applicant.isEmpty() || plugin.countries().canStartJointProject(UUID.fromString(applicant), id));
        String ruler = host.rulerName() == null ? "Władca " + host.name() : host.rulerName();
        switch (answer.kind()) {
            case ACCEPT -> {grant(id); plugin.message(sender, ruler + " zgadza się na wspólny trakt i kolej.");}
            case REFUSE -> {
                long minutes = plugin.getConfig().getLong("zgody.npc-ponow-minuty", 60);
                cooldowns.put(id, NpcRailConsent.cooldownUntil(now, minutes));
                plugin.message(sender, ruler + " odmawia zgody: " + answer.reason() + "." + (minutes > 0 ? " Można ponowić wniosek za " + minutes + " min." : ""));
            }
            case ACCEPT_FEE -> payFee(sender, host, ruler, answer.fee(), payerMode);
        }
    }

    private Answer ask(UUID host, String applicant, long base) {
        var armia = Bukkit.getPluginManager().getPlugin("Armia");
        if (armia == null || !armia.isEnabled()) return NpcRailConsent.parse("ACCEPT"); // previous unconditional behaviour
        try {
            Object raw = armia.getClass().getMethod("npcRailConsent", String.class, String.class, long.class).invoke(armia, host.toString(), applicant, base);
            return NpcRailConsent.parse(raw instanceof String s ? s : null);
        } catch (NoSuchMethodException e) {
            return NpcRailConsent.parse("ACCEPT"); // older Armia without the bridge
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Armia npcRailConsent: " + e);
            return new Answer(Kind.REFUSE, 0, "władca nie może teraz odpowiedzieć");
        }
    }

    private void payFee(CommandSender sender, KrajInfo host, String ruler, long fee, String payerMode) {
        if (loadFailure != null) throw new IllegalArgumentException(loadFailure);
        if (busy) throw new IllegalArgumentException("Trwa rozliczanie innej opłaty za zgodę.");
        SkarbiecApi bank = plugin.economy();
        String payer;
        if ("budzet".equals(payerMode)) {
            var budget = plugin.networkBudget().orElseThrow(() -> new IllegalArgumentException("Brak aktywnego budżetu budowy sieci z rezerwy Skarbu Świata."));
            if (!NpcRailConsent.withinLimit(budget.maxTotalMd(), budget.usedByWork(), ledger.paidFrom(budget.account()), fee))
                throw new IllegalArgumentException("Opłata " + bank.format(fee) + " przekracza zatwierdzony limit budżetu budowy. Wymagana decyzja zarządcy.");
            payer = budget.account();
        } else payer = RailData.ACCOUNT;
        String key = NpcRailConsent.key(host.id(), payer);
        var old = ledger.get(key);
        if (old.isPresent() && old.get().state == State.PAID) {
            grant(host.id()); plugin.message(sender, ruler + " przyjął już wcześniej opłatę; zgoda przywrócona bez ponownej płatności."); return;
        }
        if (bank.balance(payer) < fee) throw new IllegalArgumentException(ruler + " żąda " + bank.format(fee) + "; brak środków na koncie " + payer + ".");
        Entry entry = ledger.intent(host.id(), payer, fee, System.currentTimeMillis());
        busy = true;
        plugin.message(sender, ruler + " zgodzi się za opłatą " + bank.format(fee) + " (z konta " + payer + "). Zapisuję i rozliczam...");
        durable(file.write(snapshot())).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {ledger.entries.remove(entry); busy = false; plugin.message(sender, "Nie zapisano zamiaru opłaty; nic nie pobrano."); return;}
            var tx = bank.transfer(payer, SkarbiecApi.countryAccount(host.id()), fee, "Kolej: opłata za zgodę na przejazd przez " + host.name() + " tx:" + key);
            if (!tx.ok()) {
                ledger.settle(key, State.FAILED);
                durable(file.write(snapshot())).whenComplete((o, e) -> Bukkit.getScheduler().runTask(plugin, () -> busy = false));
                plugin.message(sender, "Przelew opłaty nie powiódł się (" + tx + "). Zgoda nie została udzielona.");
                return;
            }
            bankDurable().whenComplete((ack, bankError) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (bankError != null) {busy = false; audit(sender, key, bankError); return;} // INTENT stays: blocks this host until audited
                ledger.settle(key, State.PAID);
                durable(file.write(snapshot())).whenComplete((o, e) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy = false;
                    if (e != null) {audit(sender, key, e); return;}
                    grant(host.id()); plugin.message(sender, ruler + " otrzymał " + bank.format(fee) + " i zgadza się na wspólny trakt i kolej.");
                }));
            }));
        }));
    }

    private void grant(UUID host) {plugin.data().permits.add(host); plugin.save();}

    private void audit(CommandSender sender, String key, Throwable error) {
        plugin.getLogger().severe("Opłata za zgodę " + key + " wymaga kontroli: " + error);
        plugin.message(sender, "Opłata mogła zostać pobrana, ale potwierdzenie nie zostało zapisane — wymagana kontrola administratora (tx:" + key + ").");
    }

    /** Writer-thread serialization must never observe later main-thread changes. */
    private Ledger snapshot() {
        Ledger copy = new Ledger();
        for (Entry e : ledger.entries) copy.entries.add(new Entry(e.key, e.host, e.payer, e.fee, e.state, e.created));
        return copy;
    }

    private static CompletableFuture<Object> durable(Future<?> write) {
        return CompletableFuture.supplyAsync(() -> {try {return write.get(10, TimeUnit.SECONDS);} catch (Exception e) {throw new CompletionException(e);}});
    }

    private CompletableFuture<?> bankDurable() {
        try {
            var bank = Bukkit.getPluginManager().getPlugin("Skarbiec");
            Object ack = bank == null ? null : bank.getClass().getMethod("bankDurable").invoke(bank);
            if (ack instanceof CompletableFuture<?> future) return future.orTimeout(10, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | RuntimeException e) {return CompletableFuture.failedFuture(e);}
        return CompletableFuture.failedFuture(new IllegalStateException("Brak potwierdzenia trwałości Skarbca"));
    }

    @Override public void close() {file.close();}
}
