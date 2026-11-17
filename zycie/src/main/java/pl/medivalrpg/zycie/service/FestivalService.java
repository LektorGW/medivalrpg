// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.zycie.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.kraje.api.KrajeApi;
import pl.medivalrpg.zycie.ZyciePlugin;
import pl.medivalrpg.zycie.config.ZycieConfig;
import pl.medivalrpg.zycie.logic.DaySchedule;

/**
 * Festival effects at the square of the capital (citizens walk there and dance through {@link LifeService}): note-block
 * music and, in the evening, fireworks (tagged {@code zycie:firework}, their damage is cancelled). Only when the spot
 * is loaded and a player is within 64 blocks. Main thread, every tick (cheap when no festival runs).
 */
public final class FestivalService {

    private static final List<FireworkEffect.Type> TYPES = List.of(FireworkEffect.Type.BALL, FireworkEffect.Type.BALL_LARGE,
        FireworkEffect.Type.STAR, FireworkEffect.Type.BURST);
    private static final List<Color> COLORS = List.of(Color.RED, Color.YELLOW, Color.WHITE, Color.ORANGE, Color.AQUA,
        Color.LIME, Color.FUCHSIA);

    private final KrajeApi kraje;
    private final MoodService mood;
    private final LifeService life;
    private final NamespacedKey fireworkTag;
    private ZycieConfig cfg;
    private long tick;

    public FestivalService(final ZyciePlugin plugin, final KrajeApi kraje, final MoodService mood, final LifeService life) {
        this.kraje = kraje;
        this.mood = mood;
        this.life = life;
        this.fireworkTag = new NamespacedKey(plugin, "firework");
    }

    public void configure(final ZycieConfig config) {
        this.cfg = config;
    }

    public boolean isOurFirework(final Entity e) {
        return e instanceof Firework && e.getPersistentDataContainer().has(this.fireworkTag, PersistentDataType.BYTE);
    }

    public void tick() {
        this.tick++;
        final boolean music = this.tick % this.cfg.musicPeriod == 0;
        final boolean fireworks = this.tick % this.cfg.fireworksPeriod == 0;
        if (!music && !fireworks) {
            return;
        }
        for (final UUID country : this.mood.festivalCountries()) {
            final Location spot = this.mood.squareSpot(country);
            if (spot == null || !this.cfg.worldEnabled(spot.getWorld().getName()) || !this.life.playerWithin(spot, 64)) {
                continue;
            }
            if (music) {
                this.playNote(spot);
            }
            final int t = DaySchedule.timeOfDay(spot.getWorld().getFullTime());
            if (fireworks && t >= this.cfg.fireworksFrom && t <= this.cfg.fireworksTo
                && ThreadLocalRandom.current().nextDouble() < this.cfg.fireworksChance) {
                this.launch(spot, this.kraje.getCountry(country).orElse(null));
            }
        }
    }

    private void playNote(final Location spot) {
        final List<String> sounds = this.cfg.musicSounds;
        if (sounds.isEmpty()) {
            return;
        }
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final String sound = sounds.get(r.nextInt(sounds.size()));
        final float pitch = this.cfg.musicPitches.get(r.nextInt(this.cfg.musicPitches.size()));
        spot.getWorld().playSound(spot, sound, SoundCategory.RECORDS, this.cfg.musicVolume, pitch);
    }

    private void launch(final Location spot, final KrajInfo k) {
        final ThreadLocalRandom r = ThreadLocalRandom.current();
        final World w = spot.getWorld();
        final Location at = spot.clone().add(r.nextDouble(-6, 6), 1.0, r.nextDouble(-6, 6));
        final Color own = k == null ? Color.YELLOW : Color.fromRGB(k.color().value() & 0xFFFFFF);
        w.spawn(at, Firework.class, fw -> {
            fw.getPersistentDataContainer().set(this.fireworkTag, PersistentDataType.BYTE, (byte) 1);
            fw.setPersistent(false);
            final FireworkMeta meta = fw.getFireworkMeta();
            final FireworkEffect.Builder b = FireworkEffect.builder()
                .with(TYPES.get(r.nextInt(TYPES.size())))
                .withColor(own, COLORS.get(r.nextInt(COLORS.size())))
                .withFade(COLORS.get(r.nextInt(COLORS.size())));
            if (r.nextBoolean()) {
                b.withTrail();
            }
            if (r.nextDouble() < 0.3) {
                b.withFlicker();
            }
            meta.addEffect(b.build());
            meta.setPower(1 + r.nextInt(2));
            fw.setFireworkMeta(meta);
        });
    }
}
