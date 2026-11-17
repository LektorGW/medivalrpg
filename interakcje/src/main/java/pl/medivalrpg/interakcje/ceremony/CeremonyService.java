// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.interakcje.ceremony;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import pl.medivalrpg.interakcje.InteractionDef;
import pl.medivalrpg.interakcje.InterakcjePlugin;
import pl.medivalrpg.interakcje.Messages;
import pl.medivalrpg.interakcje.PlayerDataStore;
import pl.medivalrpg.interakcje.anim.AnimationCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ceremonies: the crown of the coronation (an item display moved along the prop track exported by
 * the generator, then hovering over the crowned head), the bows of the people standing around, and
 * the knight title granted at a knighting (shown before the nick in chat through the display name).
 * Main thread only.
 */
public final class CeremonyService {
    /** Model pixels -> blocks (BetterModel draws the 32 px player model 1.875 blocks tall... 0.9375 / 16). */
    static final double PX = 0.9375 / 16.0;

    private final InterakcjePlugin plugin;
    private final NamespacedKey propKey;
    private final List<Crown> crowns = new ArrayList<>();
    private Config config = Config.from(null);

    /** {@code coronation:} and {@code knighting:} in config.yml. */
    public record Config(String crownItem, float crownScale, int hoverSeconds, String audienceMode,
                         double audienceRadius, boolean grantTitle, boolean titleNeedsRuler, String defaultTitle,
                         List<String> titles, boolean titleInDisplayName, boolean requireKraje) {

        static Config from(ConfigurationSection root) {
            ConfigurationSection c = root == null ? null : root.getConfigurationSection("coronation");
            ConfigurationSection k = root == null ? null : root.getConfigurationSection("knighting");
            if (c == null) {
                c = new MemoryConfiguration();
            }
            if (k == null) {
                k = new MemoryConfiguration();
            }
            List<String> titles = k.isList("titles") ? k.getStringList("titles") : List.of("Sir", "Pani");
            return new Config(
                    c.getString("crown-item", "GOLDEN_HELMET"),
                    (float) Math.max(0.2, Math.min(2.0, c.getDouble("crown-scale", 0.6))),
                    Math.max(0, Math.min(120, c.getInt("hover-seconds", 10))),
                    c.getString("audience-bow", "auto").trim().toLowerCase(Locale.ROOT),
                    Math.max(2.0, Math.min(48.0, c.getDouble("audience-radius", 16.0))),
                    k.getBoolean("grant-title", true),
                    k.getBoolean("require-ruler", true),
                    k.getString("default-title", "Sir"),
                    List.copyOf(titles),
                    k.getBoolean("title-in-display-name", true),
                    c.getBoolean("require-kraje", true));
        }
    }

    private final class Crown {
        final UUID owner;
        final UUID crowned;
        final AnimationCatalog.Prop prop;
        final int lengthTicks;
        final Location anchor;
        final float yaw;
        final int startTick;
        ItemDisplay display;
        BukkitTask task;
        boolean set;

        Crown(UUID owner, UUID crowned, AnimationCatalog.Prop prop, int lengthTicks, Location anchor, float yaw) {
            this.owner = owner;
            this.crowned = crowned;
            this.prop = prop;
            this.lengthTicks = lengthTicks;
            this.anchor = anchor;
            this.yaw = yaw;
            this.startTick = Bukkit.getCurrentTick();
        }
    }

    public CeremonyService(InterakcjePlugin plugin) {
        this.plugin = plugin;
        this.propKey = new NamespacedKey(plugin, "prop");
    }

    public void reload() {
        config = Config.from(plugin.getConfig());
    }

    public Config config() {
        return config;
    }

    // ------------------------------------------------------------------ rulers

    /**
     * Is the subject allowed by {@code requires}?
     *
     * @return message key of the refusal, or {@code null}
     */
    public String checkRequirement(InteractionDef def, Player subject) {
        if (def.requires() != InteractionDef.Requirement.RULER) {
            return null;
        }
        return switch (KrajeBridge.isRuler(subject.getUniqueId())) {
            case YES -> null;
            case NO -> "requires-ruler";
            case UNKNOWN -> config.requireKraje() ? "requires-kraje" : null;
        };
    }

    // ------------------------------------------------------------------ crown

    /**
     * A clip with a prop track started on {@code owner} (main thread, the tick the clip starts).
     *
     * @param crowned whose head the crown ends on (the partner, or the owner when crowning oneself)
     */
    public void startProps(Player owner, AnimationCatalog.Clip clip, float yaw, UUID crowned) {
        AnimationCatalog.Prop prop = clip.prop("crown");
        if (prop == null) {
            return;
        }
        Crown c = new Crown(owner.getUniqueId(), crowned == null ? owner.getUniqueId() : crowned, prop,
                clip.lengthTicks(), owner.getLocation(), yaw);
        crowns.add(c);
        c.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(c), 0L, 1L);
    }

    private void tick(Crown c) {
        int t = Bukkit.getCurrentTick() - c.startTick;
        Player crowned = Bukkit.getPlayer(c.crowned);
        Player owner = Bukkit.getPlayer(c.owner);
        if (crowned == null || !crowned.getWorld().equals(c.anchor.getWorld())) {
            end(c, false);
            return;
        }
        boolean ceremonyRunning = t < c.lengthTicks && owner != null && plugin.animations().isAnimating(c.owner);
        if (t < c.lengthTicks && !ceremonyRunning) {
            // interrupted (someone walked away): before the crowning the crown just vanishes
            if (!c.set) {
                end(c, false);
                return;
            }
        }
        Location at;
        if (ceremonyRunning && t >= c.prop.firstTick()) {
            AnimationCatalog.PropPoint p = c.prop.at(t);
            at = toWorld(c.anchor, c.yaw, p.x(), p.y(), p.z());
            if (!c.set && t >= c.prop.setTick()) {
                c.set = true;
                crowned(crowned);
            }
        } else if (c.set || t >= c.lengthTicks) {
            int hover = t - c.lengthTicks;
            if (hover > config.hoverSeconds() * 20) {
                end(c, true);
                return;
            }
            at = crowned.getLocation().add(0, 2.08 + 0.05 * Math.sin(t / 9.0), 0);
            at.setYaw((t * 3) % 360);
        } else {
            return; // the officiant has not taken the crown yet
        }
        if (c.display == null || !c.display.isValid()) {
            c.display = spawnCrown(at);
        } else if (t % 2 == 0) {
            c.display.teleport(at);
        }
    }

    private ItemDisplay spawnCrown(Location at) {
        Material m = Material.matchMaterial(config.crownItem());
        ItemStack item = new ItemStack(m == null || !m.isItem() ? Material.GOLDEN_HELMET : m);
        float s = config.crownScale();
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s),
                    new AxisAngle4f()));
            d.setTeleportDuration(2);
            d.setBrightness(new Display.Brightness(15, 15));
            d.getPersistentDataContainer().set(propKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Model space of a player standing at {@code anchor} facing {@code yaw} -> world. */
    static Location toWorld(Location anchor, float yaw, double x, double y, double z) {
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        double rx = -Math.cos(rad);
        double rz = -Math.sin(rad);
        double right = x * PX;
        double forward = -z * PX;
        Location l = anchor.clone();
        l.add(rx * right + fx * forward, y * PX, rz * right + fz * forward);
        l.setYaw(yaw);
        l.setPitch(0f);
        return l;
    }

    private void crowned(Player crowned) {
        Location head = crowned.getLocation().add(0, 2.0, 0);
        World w = crowned.getWorld();
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, head, 40, 0.4, 0.3, 0.4, 0.25);
        w.spawnParticle(Particle.END_ROD, head, 12, 0.3, 0.2, 0.3, 0.02);
        w.playSound(head, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.0f);
        w.playSound(head, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    private void end(Crown c, boolean sparkle) {
        if (c.task != null) {
            c.task.cancel();
        }
        if (c.display != null && c.display.isValid()) {
            if (sparkle) {
                c.display.getWorld().spawnParticle(Particle.WAX_ON, c.display.getLocation(), 10, 0.2, 0.1, 0.2, 0.0);
            }
            c.display.remove();
        }
        crowns.remove(c);
    }

    public void shutdown() {
        for (Crown c : new ArrayList<>(crowns)) {
            end(c, false);
        }
        crowns.clear();
    }

    /** Number of crowns in flight (tests / status). */
    public int activeCrowns() {
        return crowns.size();
    }

    /** Leftover prop displays (non-persistent, so only after a crash with a chunk saved in between). */
    public int removeStrayProps(Iterable<Entity> entities) {
        int n = 0;
        for (Entity e : entities) {
            if (e instanceof ItemDisplay && e.getPersistentDataContainer().has(propKey, PersistentDataType.BYTE)) {
                e.remove();
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ the people around

    /** Players standing near the crowned bow to them ({@code coronation.audience-bow: auto|prompt|off}). */
    public void audienceBow(Player subject, Player officiant) {
        if ("off".equals(config.audienceMode())) {
            return;
        }
        double r2 = config.audienceRadius() * config.audienceRadius();
        PlayerDataStore data = plugin.data();
        Component prompt = null;
        for (Player p : subject.getWorld().getPlayers()) {
            if (p.equals(subject) || p.equals(officiant) || p.getLocation().distanceSquared(subject.getLocation()) > r2
                    || p.getGameMode() == GameMode.SPECTATOR || plugin.service().isHidden(p)
                    || data.isDisabled(p.getUniqueId()) || data.isBlocked(p.getUniqueId(), subject.getUniqueId())) {
                continue;
            }
            boolean busy = plugin.animations().isAnimating(p.getUniqueId()) || plugin.poses().isPosing(p.getUniqueId());
            if ("auto".equals(config.audienceMode()) && !busy && plugin.animations().stillFor(p, 20)
                    && plugin.animations().playSolo("poklon", p, subject) != null) {
                plugin.messages().send(p, "coronation-bowing", plugin.names(p, subject));
                continue;
            }
            if (prompt == null) {
                prompt = plugin.messages().get("coronation-bow-button")
                        .hoverEvent(HoverEvent.showText(plugin.messages().get("coronation-bow-hover")))
                        .clickEvent(ClickEvent.runCommand("/emotka poklon " + subject.getName()));
            }
            plugin.messages().send(p, "coronation-bow-prompt", plugin.names(p, subject),
                    Messages.component("button", prompt));
        }
    }

    // ------------------------------------------------------------------ knight titles

    /** At the end of a knighting: the knighted gets a title if the knight-maker rules a country. */
    public void knighted(Player by, Player knighted) {
        if (!config.grantTitle()) {
            return;
        }
        if (config.titleNeedsRuler() && KrajeBridge.isRuler(by.getUniqueId()) != KrajeBridge.Ruler.YES) {
            plugin.messages().send(by, "knight-no-title", plugin.names(by, knighted));
            return;
        }
        PlayerDataStore.Title old = plugin.data().title(knighted.getUniqueId());
        String title = old != null ? old.title() : config.defaultTitle();
        plugin.data().setTitle(knighted.getUniqueId(),
                new PlayerDataStore.Title(title, by.getName(), System.currentTimeMillis(), false));
        applyDisplayName(knighted);
        plugin.messages().send(knighted, "knight-title-granted", plugin.names(by, knighted),
                Messages.text("title", title), Messages.text("titles", String.join("|", config.titles())));
    }

    /** {@code /interakcje tytul <sir|pani|ukryj|pokaz>}. */
    public void chooseTitle(Player p, String choice) {
        PlayerDataStore.Title t = plugin.data().title(p.getUniqueId());
        if (t == null) {
            plugin.messages().send(p, "title-none");
            return;
        }
        String c = choice.trim().toLowerCase(Locale.ROOT);
        PlayerDataStore.Title next;
        if (c.equals("ukryj") || c.equals("hide")) {
            next = new PlayerDataStore.Title(t.title(), t.byName(), t.since(), true);
        } else if (c.equals("pokaz") || c.equals("pokaż") || c.equals("show")) {
            next = new PlayerDataStore.Title(t.title(), t.byName(), t.since(), false);
        } else {
            String match = null;
            for (String allowed : config.titles()) {
                if (allowed.equalsIgnoreCase(c)) {
                    match = allowed;
                }
            }
            if (match == null) {
                plugin.messages().send(p, "title-unknown", Messages.text("titles", String.join(", ", config.titles())));
                return;
            }
            next = new PlayerDataStore.Title(match, t.byName(), t.since(), false);
        }
        plugin.data().setTitle(p.getUniqueId(), next);
        applyDisplayName(p);
        plugin.messages().send(p, next.hidden() ? "title-hidden" : "title-set", Messages.text("title", next.title()));
    }

    /** Join / change: {@code Sir Nick} as the display name (used by the chat of Kraje and others). */
    public void applyDisplayName(Player p) {
        if (!config.titleInDisplayName()) {
            return;
        }
        String title = plugin.data().shownTitle(p.getUniqueId());
        Component wanted = title == null ? null : Component.text(title + " " + p.getName());
        String plain = PlainTextComponentSerializer.plainText().serialize(p.displayName());
        boolean ours = config.titles().stream().anyMatch(t -> plain.equals(t + " " + p.getName()));
        if (wanted != null) {
            p.displayName(wanted);
        } else if (ours) {
            p.displayName(null); // back to the plain nick
        }
    }
}
