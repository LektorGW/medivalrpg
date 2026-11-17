// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.glos;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Logika głosu: lista podłączonych klientów, tryby, pętla {@code state}, wskaźniki, action bar,
 * linki i wyciszenia. Wszystkie metody publiczne (poza oznaczonymi) wołane na głównym wątku.
 */
public final class VoiceManager implements WorkerConnection.Handler {

    // ---- wiadomości protokołu (serializowane przez Gson na wątku io) ----
    record TokenMsg(String t, String token, String id, String n) {
    }

    record KickMsg(String t, String id, String reason) {
    }

    record StateMsg(String t, Map<String, Payload> to) {
    }

    record Payload(Me me, List<Peer> peers) {
    }

    record Me(double yaw, double pitch, String mode, int range, boolean muted, boolean uw) {
    }

    record Peer(String id, String n, double x, double y, double z, int r, boolean m, boolean mu) {
    }

    /** Migawka gracza zrobiona na głównym wątku na potrzeby jednego ticku stanu. */
    private record Snap(String id, String name, World world, double x, double y, double z,
                        double yaw, double pitch, VoiceMode mode, int range,
                        boolean uw, boolean muted, boolean spectator) {
    }

    private static final String TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final GlosPlugin plugin;
    private final Logger log;
    private final MuteStore mutes;
    private final PlayerPrefs prefs;
    private final SpeakingIndicator indicator;
    private final SecureRandom random = new SecureRandom();
    /** Ponowne /glos w ciągu 10 s daje ten sam link (ochrona Workera przed spamem tokenów). */
    private final RecentTokens recentTokens = new RecentTokens(10_000L);

    private GlosConfig config;
    private Messages messages;
    private WorkerConnection connection;

    private final Map<UUID, VoiceState> states = new ConcurrentHashMap<>();
    /** Lista podłączonych przeglądarek wg DO (wiadomość {@code clients}). */
    private final Set<UUID> clients = ConcurrentHashMap.newKeySet();
    /** Nicki graczy online – do podpowiedzi komend (bezpieczne wątkowo). */
    private final Map<UUID, String> onlineNames = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineSince = new HashMap<>();
    private final Set<UUID> offlineKicked = new HashSet<>();
    private boolean awaitingInitialClients;

    public VoiceManager(GlosPlugin plugin, GlosConfig config, Messages messages, MuteStore mutes,
                        PlayerPrefs prefs, SpeakingIndicator indicator) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.config = config;
        this.messages = messages;
        this.mutes = mutes;
        this.prefs = prefs;
        this.indicator = indicator;
    }

    public void setConnection(WorkerConnection connection) {
        this.connection = connection;
    }

    public void reload(GlosConfig config, Messages messages) {
        this.config = config;
        this.messages = messages;
    }

    public boolean isWorkerConnected() {
        return connection != null && connection.isOpen();
    }

    // ------------------------------------------------------------------ gracze

    public void playerJoined(Player p) {
        UUID id = p.getUniqueId();
        states.computeIfAbsent(id, k -> new VoiceState());
        onlineNames.put(id, p.getName());
        mutes.rememberName(id, p.getName());
        offlineSince.remove(id);
        offlineKicked.remove(id);
        if (!prefs.get(id, PlayerPrefs.INDICATORS)) {
            indicator.applyViewer(p, false);
        }
    }

    public void playerQuit(Player p) {
        UUID id = p.getUniqueId();
        indicator.hide(id);
        states.remove(id);
        onlineNames.remove(id);
        sendKick(id, plugin.kickReason("quit", "Wyszedłeś z serwera"));
    }

    public VoiceState state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new VoiceState());
    }

    public boolean isClient(UUID id) {
        return clients.contains(id);
    }

    public Collection<String> onlineNames() {
        return List.copyOf(onlineNames.values());
    }

    /** Efektywny tryb: krzyk > kucanie > tryb bazowy. */
    public VoiceMode effectiveMode(Player p, VoiceState st) {
        VoiceMode base = st.baseMode();
        if (base == VoiceMode.NORMAL && config.sneakWhisper() && p.isSneaking()) {
            return VoiceMode.WHISPER;
        }
        return base;
    }

    // ------------------------------------------------------------------ komendy graczy

    public void sendLink(Player p) {
        if (!isWorkerConnected() || config.workerUrl().isEmpty()) {
            messages.send(p, "unavailable");
            return;
        }
        long now = System.currentTimeMillis();
        String token = recentTokens.reusable(p.getUniqueId(), now);
        if (token == null) {
            token = randomToken(32);
            if (!connection.send(new TokenMsg("token", token, p.getUniqueId().toString(), p.getName()))) {
                messages.send(p, "unavailable");
                return;
            }
            recentTokens.issued(p.getUniqueId(), token, now);
        }
        String url = config.workerUrl() + "/?t=" + token;
        Component hover = messages.get("link-hover");
        messages.send(p, "link",
                Placeholder.styling("link", ClickEvent.openUrl(url), HoverEvent.showText(hover)),
                Messages.text("url", url));
    }

    public void setMode(Player p, VoiceMode mode, boolean fromBrowser) {
        VoiceState st = state(p);
        st.setBaseMode(mode);
        messages.send(p, fromBrowser ? "mode-changed-browser" : "mode-changed",
                Messages.text("mode", messages.modeName(mode)),
                Messages.text("range", String.valueOf(config.range(mode))));
        sendActionBar(p);
    }

    /** {@code /glos pasek [wlacz|wylacz]}; {@code enable == null} = przełącz. */
    public void toggleActionbar(Player p, Boolean enable) {
        if (!config.actionbar()) {
            messages.send(p, "actionbar-server-disabled");
            return;
        }
        UUID id = p.getUniqueId();
        boolean on = enable != null ? enable : !prefs.get(id, PlayerPrefs.ACTIONBAR);
        prefs.set(id, PlayerPrefs.ACTIONBAR, on);
        if (on) {
            messages.send(p, "actionbar-on");
            sendActionBar(p);
        } else {
            p.sendActionBar(Component.empty()); // od razu czyści pasek
            messages.send(p, "actionbar-off");
        }
    }

    /** {@code /glos wskaznik [wlacz|wylacz]}: czy widz widzi ikonki mówienia nad innymi. */
    public void toggleIndicators(Player p, Boolean enable) {
        UUID id = p.getUniqueId();
        boolean on = enable != null ? enable : !prefs.get(id, PlayerPrefs.INDICATORS);
        prefs.set(id, PlayerPrefs.INDICATORS, on);
        indicator.applyViewer(p, on);
        messages.send(p, on ? "indicators-on" : "indicators-off");
    }

    public void disconnect(Player p) {
        UUID id = p.getUniqueId();
        if (!isWorkerConnected()) {
            messages.send(p, "unavailable");
            return;
        }
        sendKick(id, plugin.kickReason("command", "Rozłączono komendą /glos rozlacz"));
        indicator.hide(id);
        messages.send(p, clients.contains(id) ? "disconnected-command" : "not-connected");
    }

    // ------------------------------------------------------------------ wyciszenia

    /** @param durationMs czas albo {@code -1} = na stałe */
    public void mute(CommandSender by, UUID target, String name, long durationMs) {
        long expires = durationMs < 0 ? MuteStore.PERMANENT : System.currentTimeMillis() + durationMs;
        mutes.mute(target, name, expires);
        indicator.hide(target);
        String time = durationMs < 0 ? messages.raw("time-permanent") : Durations.format(durationMs);
        messages.send(by, "mute-done", Messages.text("player", name), Messages.text("time", time));
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            messages.send(p, durationMs < 0 ? "muted-permanent" : "muted-temporary", Messages.text("time", time));
            sendActionBar(p);
        }
    }

    public void unmute(CommandSender by, UUID target, String name) {
        if (!mutes.unmute(target)) {
            messages.send(by, "unmute-not-muted", Messages.text("player", name));
            return;
        }
        messages.send(by, "unmute-done", Messages.text("player", name));
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            messages.send(p, "unmuted");
            sendActionBar(p);
        }
    }

    public void sendList(CommandSender to) {
        messages.send(to, isWorkerConnected() ? "list-worker-online" : "list-worker-offline");
        if (clients.isEmpty()) {
            messages.send(to, "list-empty");
            return;
        }
        messages.send(to, "list-header", Messages.text("count", String.valueOf(clients.size())));
        List<UUID> sorted = new ArrayList<>(clients);
        sorted.sort((a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b)));
        for (UUID id : sorted) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                messages.send(to, "list-entry-offline", Messages.text("player", nameOf(id)));
                continue;
            }
            VoiceState st = state(p);
            VoiceMode mode = effectiveMode(p, st);
            messages.send(to, "list-entry",
                    Messages.text("player", p.getName()),
                    Messages.text("mode", messages.modeName(mode)),
                    Messages.text("range", String.valueOf(config.range(mode))),
                    Placeholder.parsed("speaking", st.speaking() ? messages.raw("list-speaking") : ""),
                    Placeholder.parsed("muted", mutes.isMuted(id) ? messages.raw("list-muted") : ""));
        }
    }

    private String nameOf(UUID id) {
        String n = onlineNames.get(id);
        if (n != null) {
            return n;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString();
    }

    // ------------------------------------------------------------------ WorkerConnection.Handler (główny wątek)

    @Override
    public void onConnected() {
        awaitingInitialClients = true;
    }

    @Override
    public void onDisconnected() {
        recentTokens.clear();
        clients.clear();
        offlineSince.clear();
        offlineKicked.clear();
        states.values().forEach(s -> s.setSpeaking(false));
        indicator.hideAll();
    }

    @Override
    public void onMessage(String type, JsonObject msg) {
        try {
            switch (type) {
                case "clients" -> onClients(msg);
                case "speaking" -> onSpeaking(msg);
                case "mode" -> onMode(msg);
                default -> {
                    if (config.debug()) {
                        log.info("Nieobsługiwana wiadomość od Workera: " + type);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warning("Błąd obsługi wiadomości '" + type + "': " + e);
        }
    }

    private void onClients(JsonObject msg) {
        JsonElement idsEl = msg.get("ids");
        if (idsEl == null || !idsEl.isJsonArray()) {
            return;
        }
        Set<UUID> fresh = new HashSet<>();
        JsonArray arr = idsEl.getAsJsonArray();
        for (JsonElement e : arr) {
            UUID id = parseUuid(e);
            if (id != null) {
                fresh.add(id);
            }
        }
        boolean initial = awaitingInitialClients;
        awaitingInitialClients = false;

        for (UUID old : Set.copyOf(clients)) {
            if (!fresh.contains(old)) {
                clients.remove(old);
                offlineSince.remove(old);
                offlineKicked.remove(old);
                VoiceState st = states.get(old);
                if (st != null) {
                    st.setSpeaking(false);
                }
                indicator.hide(old);
                Player p = Bukkit.getPlayer(old);
                if (p != null && !initial) {
                    messages.send(p, "client-disconnected");
                }
            }
        }
        for (UUID id : fresh) {
            if (clients.add(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !initial) {
                    messages.send(p, "client-connected");
                    if (mutes.isMuted(id)) {
                        messages.send(p, "muted-reminder");
                    }
                }
            }
        }
    }

    private void onSpeaking(JsonObject msg) {
        UUID id = parseUuid(msg.get("id"));
        if (id == null) {
            return;
        }
        VoiceState st = states.get(id);
        if (st == null) {
            return;
        }
        JsonElement onEl = msg.get("on");
        boolean on = onEl != null && onEl.isJsonPrimitive() && onEl.getAsBoolean();
        if (st.speaking() == on) {
            return;
        }
        st.setSpeaking(on);
        if (!on) {
            indicator.hide(id);
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            sendActionBar(p);
        }
    }

    private void onMode(JsonObject msg) {
        UUID id = parseUuid(msg.get("id"));
        JsonElement modeEl = msg.get("mode");
        if (id == null || modeEl == null || !modeEl.isJsonPrimitive()) {
            return;
        }
        VoiceMode mode = VoiceMode.fromId(modeEl.getAsString());
        Player p = Bukkit.getPlayer(id);
        if (mode == null || p == null) {
            if (config.debug()) {
                log.info("Odrzucono zmianę trybu: " + msg);
            }
            return;
        }
        if (state(p).baseMode() != mode) {
            setMode(p, mode, true);
        }
    }

    private static UUID parseUuid(JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return UUID.fromString(e.getAsString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ zadania cykliczne

    /** Co 4 ticki: buduje payload {@code state} (tu, na głównym wątku); serializacja i wysyłka na wątku io. */
    public void tickState() {
        if (!isWorkerConnected() || clients.isEmpty()) {
            return;
        }
        List<Snap> snaps = new ArrayList<>(clients.size());
        for (UUID id : clients) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                continue;
            }
            VoiceState st = state(p);
            VoiceMode mode = effectiveMode(p, st);
            Location eye = p.getEyeLocation();
            snaps.add(new Snap(
                    id.toString().toLowerCase(Locale.ROOT), p.getName(), p.getWorld(),
                    eye.getX(), eye.getY(), eye.getZ(),
                    round1(Location.normalizeYaw(eye.getYaw())), round1(eye.getPitch()),
                    mode, config.range(mode),
                    p.isUnderWater(), mutes.isMuted(id), p.getGameMode() == GameMode.SPECTATOR));
        }
        if (snaps.isEmpty()) {
            return;
        }
        double maxDist = config.maxRange() + 8.0;
        double maxSq = maxDist * maxDist;
        Map<String, Payload> to = new LinkedHashMap<>();
        for (Snap l : snaps) {
            List<Peer> peers = new ArrayList<>();
            for (Snap s : snaps) {
                if (s == l || s.spectator || !s.world.equals(l.world)) {
                    continue; // gracze w trybie widza nie są słyszani
                }
                double dx = s.x - l.x;
                double dy = s.y - l.y;
                double dz = s.z - l.z;
                if (dx * dx + dy * dy + dz * dz > maxSq) {
                    continue;
                }
                peers.add(new Peer(s.id, s.name, round2(dx), round2(dy), round2(dz), s.range,
                        l.uw || s.uw, s.muted));
            }
            to.put(l.id, new Payload(
                    new Me(l.yaw, l.pitch, l.mode.id(), l.range, l.muted, l.uw),
                    List.copyOf(peers)));
        }
        connection.sendState(new StateMsg("state", to));
    }

    /** Co tick: uzgadnia wskaźniki „kto mówi”. */
    public void tickIndicators() {
        for (Map.Entry<UUID, VoiceState> e : states.entrySet()) {
            UUID id = e.getKey();
            VoiceState st = e.getValue();
            if (!st.speaking() && !indicator.has(id)) {
                continue;
            }
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                indicator.hide(id);
                continue;
            }
            indicator.sync(p, st.speaking() && indicatorAllowed(p) ? effectiveMode(p, st) : null);
        }
        // Wskaźniki graczy, których stanu już nie ma (np. wyszli).
        for (UUID id : indicator.activeIds()) {
            if (!states.containsKey(id)) {
                indicator.hide(id);
            }
        }
    }

    private boolean indicatorAllowed(Player p) {
        if (!clients.contains(p.getUniqueId()) || mutes.isMuted(p.getUniqueId())) {
            return false;
        }
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        return !config.indicatorHideWhenInvisible()
                || !(p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY));
    }

    /** Co sekundę: wygasanie wyciszeń, action bar, rozłączanie klientów graczy offline. */
    public void tickSecond() {
        for (UUID id : mutes.purgeExpired()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                messages.send(p, "unmuted-expired");
            }
        }
        if (config.actionbar()) {
            for (UUID id : clients) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    sendActionBar(p);
                }
            }
        }
        int grace = config.offlineKickSeconds();
        if (grace > 0 && isWorkerConnected()) {
            long now = System.currentTimeMillis();
            for (UUID id : clients) {
                if (Bukkit.getPlayer(id) != null || offlineKicked.contains(id)) {
                    continue;
                }
                long since = offlineSince.computeIfAbsent(id, k -> now);
                if (now - since >= grace * 1000L) {
                    offlineKicked.add(id);
                    sendKick(id, plugin.kickReason("offline", "Nie ma cię na serwerze. Wejdź do gry i wpisz /glos."));
                }
            }
        }
    }

    public void sendActionBar(Player p) {
        if (!config.actionbar() || !clients.contains(p.getUniqueId())
                || !prefs.get(p.getUniqueId(), PlayerPrefs.ACTIONBAR)) {
            return;
        }
        if (mutes.isMuted(p.getUniqueId())) {
            p.sendActionBar(messages.get("actionbar-muted"));
            return;
        }
        VoiceState st = state(p);
        VoiceMode mode = effectiveMode(p, st);
        p.sendActionBar(messages.get(st.speaking() ? "actionbar-speaking" : "actionbar",
                Messages.text("mode", messages.modeName(mode)),
                Messages.text("range", String.valueOf(config.range(mode))),
                Placeholder.parsed("underwater", p.isUnderWater() ? messages.raw("actionbar-underwater") : "")));
    }

    // ------------------------------------------------------------------ pomocnicze

    private void sendKick(UUID id, String reason) {
        recentTokens.forget(id); // kick unieważnia tokeny w Workerze
        if (connection != null) {
            connection.send(new KickMsg("kick", id.toString().toLowerCase(Locale.ROOT), reason));
        }
    }

    private String randomToken(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
