// Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
package pl.medivalrpg.ludnosc.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import pl.medivalrpg.kraje.api.KrajInfo;
import pl.medivalrpg.ludnosc.LudnoscPlugin;
import pl.medivalrpg.ludnosc.config.LudnoscConfig;
import pl.medivalrpg.ludnosc.logic.NamePool;
import pl.medivalrpg.ludnosc.model.ChunkRef;
import pl.medivalrpg.ludnosc.model.CitizenRecord;
import pl.medivalrpg.ludnosc.util.Keys;
import pl.medivalrpg.ludnosc.api.LudnoscApi;

/**
 * NPC citizens: spawning settlers, adopting free villagers, releasing/removing, conscription and reconciliation of
 * the registry with entities when chunks load/unload (no world scans). Main thread unless noted.
 */
public final class CitizenService {

    private static final long TOMBSTONE_MILLIS = TimeUnit.DAYS.toMillis(60);

    private final LudnoscPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Integer> pending = new HashMap<>();
    private NamePool names;

    public CitizenService(final LudnoscPlugin plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        this.names = new NamePool(this.plugin.settings().names, this.random);
    }

    private LudnoscData data() {
        return this.plugin.data();
    }

    private LudnoscConfig cfg() {
        return this.plugin.settings();
    }

    private Keys keys() {
        return this.plugin.keys();
    }

    public String nextName() {
        return this.names.next();
    }

    // ------------------------------------------------------------------ identity

    public UUID taggedCountry(final Entity entity) {
        return Keys.getUuid(entity, this.keys().country);
    }

    public boolean isCitizen(final Entity entity) {
        return entity instanceof Villager && this.data().citizen(entity.getUniqueId()) != null;
    }

    public Component displayName(final String name, final UUID country) {
        final KrajInfo info = this.plugin.kraje().getCountry(country).orElse(null);
        final TextColor color = info == null ? NamedTextColor.WHITE : info.color();
        return this.plugin.messages().parse(this.cfg().nameFormat,
            Placeholder.unparsed("imie", name),
            Placeholder.unparsed("kraj", info == null ? "" : info.name()),
            Placeholder.styling("kolor", color));
    }

    /** Writes PDC and the custom name of a citizen entity from its record. */
    public void applyIdentity(final Villager v, final CitizenRecord rec) {
        Keys.setUuid(v, this.keys().country, rec.countryId());
        v.getPersistentDataContainer().set(this.keys().citizenName, PersistentDataType.STRING, rec.name());
        v.customName(this.displayName(rec.name(), rec.countryId()));
        v.setCustomNameVisible(this.cfg().nameAlwaysVisible);
        v.setRemoveWhenFarAway(false);
        v.setPersistent(true);
    }

    /** Removes Ludnosc tags; the custom name too when it is the one Ludnosc gave. */
    public void strip(final Villager v) {
        final String given = v.getPersistentDataContainer().get(this.keys().citizenName, PersistentDataType.STRING);
        Keys.setUuid(v, this.keys().country, null);
        v.getPersistentDataContainer().remove(this.keys().citizenName);
        final Component current = v.customName();
        if (given != null && current != null && PlainTextComponentSerializer.plainText().serialize(current).contains(given)) {
            v.customName(null);
            v.setCustomNameVisible(false);
        }
    }

    public static String professionKey(final Villager v) {
        final NamespacedKey key = Registry.VILLAGER_PROFESSION.getKey(v.getProfession());
        return key == null ? "minecraft:none" : key.toString();
    }

    private Villager.Profession randomProfession() {
        final Map<String, Integer> weights = this.cfg().professionWeights;
        int total = 0;
        for (final int w : weights.values()) {
            total += w;
        }
        int roll = this.random.nextInt(Math.max(1, total));
        for (final Map.Entry<String, Integer> e : weights.entrySet()) {
            roll -= e.getValue();
            if (roll < 0) {
                final Villager.Profession p = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(e.getKey()));
                return p == null ? Villager.Profession.NONE : p;
            }
        }
        return Villager.Profession.NONE;
    }

    private CitizenRecord snapshot(final Villager v, final UUID country, final String name, final long since) {
        final Location l = v.getLocation();
        return new CitizenRecord(v.getUniqueId(), country, name, professionKey(v), v.isAdult(), l.getWorld().getName(),
            l.getBlockX(), l.getBlockY(), l.getBlockZ(), since, 0);
    }

    // ------------------------------------------------------------------ spawning / adoption

    /** Spawns a new settler at a location in a loaded chunk. */
    public Villager spawn(final UUID country, final Location loc) {
        return this.spawn(country, loc, null);
    }

    /** Spawns a settler with a given profession ({@code null} = random by config weights). */
    public Villager spawn(final UUID country, final Location loc, final Villager.Profession fixed) {
        return this.spawnNamed(country, loc, fixed == null ? this.randomProfession() : fixed, this.nextName(), System.currentTimeMillis());
    }

    private Villager spawnNamed(final UUID country, final Location loc, final Villager.Profession profession, final String name,
                                final long since) {
        return this.spawnNamed(country, loc, profession, name, since, false);
    }

    /** {@code baby}: a child of a citizen couple — stays a child until the family rules let it grow up (age lock). */
    private Villager spawnNamed(final UUID country, final Location loc, final Villager.Profession profession, final String name,
                                final long since, final boolean baby) {
        final Villager v = loc.getWorld().spawn(loc, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, true, villager -> {
            if (baby) {
                villager.setBaby();
                villager.setAgeLock(true);
                return;
            }
            if (profession != Villager.Profession.NONE) {
                villager.setProfession(profession);
                if (this.cfg().lockProfession && profession != Villager.Profession.NITWIT) {
                    villager.setVillagerExperience(Math.max(1, villager.getVillagerExperience()));
                }
            }
        });
        final CitizenRecord rec = this.snapshot(v, country, name, since);
        this.data().putCitizen(rec);
        this.applyIdentity(v, rec);
        if (this.cfg().effects) {
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0);
        }
        return v;
    }

    /**
     * A new citizen at {@code loc}: spawned now when the chunk is loaded, otherwise registered right away as a
     * <i>pending</i> record (counted in population, taxes and food) whose villager appears when the chunk loads.
     */
    public CitizenRecord spawnOrQueue(final UUID country, final Location loc, final Villager.Profession fixed) {
        final Villager.Profession profession = fixed == null ? this.randomProfession() : fixed;
        final World w = loc.getWorld();
        if (w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
            && w.getChunkAt(loc.getBlockX() >> 4, loc.getBlockZ() >> 4).isEntitiesLoaded()) {
            final Location spot = safe(loc.getBlock()) ? loc : this.safeSpot(country, loc);
            final Villager v = this.spawnNamed(country, spot == null ? loc : spot, profession, this.nextName(),
                System.currentTimeMillis());
            return this.data().citizen(v.getUniqueId());
        }
        final NamespacedKey key = Registry.VILLAGER_PROFESSION.getKey(profession);
        final CitizenRecord rec = new CitizenRecord(UUID.randomUUID(), country, this.nextName(),
            key == null ? "minecraft:none" : key.toString(), true, w == null ? "world" : w.getName(), loc.getBlockX(),
            loc.getBlockY(), loc.getBlockZ(), System.currentTimeMillis(), 0, true);
        this.data().putCitizen(rec);
        return rec;
    }

    /** Pending citizens of a chunk whose entities just loaded become real villagers. */
    private void materialise(final Chunk chunk) {
        final ChunkRef ref = new ChunkRef(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        for (final CitizenRecord rec : this.data().citizensIn(ref)) {
            if (!rec.pending()) {
                continue;
            }
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(rec.countryId())) {
                continue;
            }
            this.data().removeCitizen(rec.entityId());
            if (this.plugin.kraje().getCountry(rec.countryId()).isEmpty()) {
                continue;
            }
            Location loc = new Location(chunk.getWorld(), rec.x() + 0.5, rec.y(), rec.z() + 0.5);
            if (!safe(loc.getBlock())) {
                // the remembered spot is no longer safe (built over, a hole, water): nearest safe block on own land
                final Location spot = this.safeSpot(rec.countryId(), loc);
                if (spot != null) {
                    loc = spot;
                } else {
                    loc.setY(chunk.getWorld().getHighestBlockYAt(rec.x(), rec.z(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
                }
            }
            final Villager v = this.spawnNamed(rec.countryId(), loc, professionFromKey(rec.profession()), rec.name(), rec.since(),
                !rec.adult());
            if (this.plugin.life() != null) {
                this.plugin.life().rekey(rec.entityId(), v.getUniqueId());
            }
        }
    }

    /**
     * A child born to a citizen couple at {@code loc} (a pending child record when the chunk is not loaded). Returns
     * the record, null when it could not be placed.
     */
    public CitizenRecord spawnChild(final UUID country, final Location loc) {
        final World w = loc.getWorld();
        if (w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
            && w.getChunkAt(loc.getBlockX() >> 4, loc.getBlockZ() >> 4).isEntitiesLoaded()) {
            final Location spot = safe(loc.getBlock()) ? loc : this.safeSpot(country, loc);
            final Villager v = this.spawnNamed(country, spot == null ? loc : spot, Villager.Profession.NONE, this.nextName(),
                System.currentTimeMillis(), true);
            return this.data().citizen(v.getUniqueId());
        }
        final CitizenRecord rec = new CitizenRecord(UUID.randomUUID(), country, this.nextName(), "minecraft:none", false,
            w == null ? "world" : w.getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), System.currentTimeMillis(), 0, true);
        this.data().putCitizen(rec);
        return rec;
    }

    /** A grown-up child: adult villager with the profession it took over (kept like a settler's profession). */
    public void growUp(final Villager v, final String profession) {
        v.setAgeLock(false);
        v.setAdult();
        final Villager.Profession p = professionFromKey(profession);
        if (p != Villager.Profession.NONE && v.getProfession() != p) {
            v.setProfession(p);
            if (this.cfg().lockProfession && p != Villager.Profession.NITWIT) {
                v.setVillagerExperience(Math.max(1, v.getVillagerExperience()));
            }
        }
        final CitizenRecord rec = this.data().citizen(v.getUniqueId());
        if (rec != null) {
            final Location l = v.getLocation();
            this.data().putCitizen(rec.withState(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                professionKey(v), true));
        }
    }

    /** Loads the chunk asynchronously, then spawns. {@code after} gets the villager (or null on failure). */
    public void spawnAsync(final UUID country, final Location loc, final Consumer<Villager> after) {
        this.pending.merge(country, 1, Integer::sum);
        loc.getWorld().getChunkAtAsync(loc).whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.pending.computeIfPresent(country, (k, n) -> n <= 1 ? null : n - 1);
            Villager v = null;
            if (error == null && this.plugin.kraje().getCountry(country).isPresent()) {
                try {
                    v = this.spawn(country, loc);
                } catch (final RuntimeException e) {
                    this.plugin.getLogger().warning("Nie udało się stworzyć osadnika: " + e.getMessage());
                }
            }
            if (after != null) {
                after.accept(v);
            }
        }));
    }

    /** Profession from a key ({@code minecraft:farmer} / {@code farmer}); NONE when unknown or blank. */
    public static Villager.Profession professionFromKey(final String key) {
        if (key == null || key.isBlank()) {
            return Villager.Profession.NONE;
        }
        final NamespacedKey k = NamespacedKey.fromString(key.trim().toLowerCase(java.util.Locale.ROOT));
        final Villager.Profession p = k == null ? null : Registry.VILLAGER_PROFESSION.get(k);
        return p == null ? Villager.Profession.NONE : p;
    }

    private static boolean safe(final Block feet) {
        final Block head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid()
            && feet.getRelative(0, -1, 0).isSolid();
    }

    /**
     * A safe standing spot on the country's land: {@code loc} itself, else the surface within 8 blocks (loaded chunks
     * only), else the capital centre. Null when none is found. May load the chunk of {@code loc} (main thread).
     */
    public Location safeSpot(final UUID country, final Location loc) {
        final World w = loc.getWorld();
        if (w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            final Block start = loc.getBlock();
            if (this.ownedBy(country, w, start.getX(), start.getZ()) && safe(start)) {
                return start.getLocation().add(0.5, 0, 0.5);
            }
            for (int r = 0; r <= 8; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        final int x = start.getX() + dx;
                        final int z = start.getZ() + dz;
                        if (!w.isChunkLoaded(x >> 4, z >> 4) || !this.ownedBy(country, w, x, z)) {
                            continue;
                        }
                        final Block feet = w.getBlockAt(x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
                        if (safe(feet)) {
                            return feet.getLocation().add(0.5, 0, 0.5);
                        }
                    }
                }
            }
        }
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        final World cw = k == null ? null : Bukkit.getWorld(k.capitalWorld());
        if (cw == null || !cw.isChunkLoaded(k.capitalX(), k.capitalZ())) {
            return null;
        }
        final int x = k.capitalX() * 16 + 8;
        final int z = k.capitalZ() * 16 + 8;
        return new Location(cw, x + 0.5, cw.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z + 0.5);
    }

    /** Centre column of the capital chunk (y: surface when loaded, else sea level — a pending record finds its spot). */
    private Location capitalCentre(final UUID country) {
        final KrajInfo k = this.plugin.kraje().getCountry(country).orElse(null);
        final World cw = k == null ? null : Bukkit.getWorld(k.capitalWorld());
        if (cw == null) {
            return null;
        }
        final int x = k.capitalX() * 16 + 8;
        final int z = k.capitalZ() * 16 + 8;
        final int y = cw.isChunkLoaded(k.capitalX(), k.capitalZ())
            ? cw.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1 : cw.getSeaLevel() + 1;
        return new Location(cw, x + 0.5, y, z + 0.5);
    }

    private boolean ownedBy(final UUID country, final World w, final int x, final int z) {
        return this.plugin.kraje().getCountryAt(w.getName(), x >> 4, z >> 4).map(KrajInfo::id).map(country::equals).orElse(false);
    }

    /**
     * {@code LudnoscApi#returnCitizen}: a soldier comes back as an adult citizen with the given profession. Counts
     * towards the population even above the housing capacity (homelessness rules apply later).
     */
    public boolean returnCitizen(final UUID country, final Location location, final String profession) {
        if (country == null || location == null || location.getWorld() == null
            || this.plugin.kraje().getCountry(country).isEmpty()) {
            return false;
        }
        final World lw = location.getWorld();
        if (!lw.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return this.spawnOrQueue(country, location, professionFromKey(profession)) != null;
        }
        final Location spot = this.safeSpot(country, location);
        if (spot == null) {
            // no safe spot on the country's land around (land lost, built over) and the capital is not loaded: the
            // citizen comes back as a pending record at the capital instead of being lost (soldiers, company workers)
            final Location capital = this.capitalCentre(country);
            return capital != null && this.spawnOrQueue(country, capital, professionFromKey(profession)) != null;
        }
        try {
            return this.spawn(country, spot, professionFromKey(profession)) != null;
        } catch (final RuntimeException e) {
            this.plugin.getLogger().warning("returnCitizen: nie udało się stworzyć wieśniaka: " + e.getMessage());
            return false;
        }
    }

    /** NPCs of other plugins (Armia "brains"/horses carry PDC in the {@code armia} namespace) are never citizens. */
    public static boolean foreignNpc(final Entity e) {
        for (final NamespacedKey key : e.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals("armia")) {
                return true;
            }
        }
        return false;
    }

    /** Settlers being spawned right now (async chunk load in progress). */
    public int pending(final UUID country) {
        return this.pending.getOrDefault(country, 0);
    }

    /** Makes a free villager a citizen (keeps a name a player gave it). */
    public void adopt(final Villager v, final UUID country) {
        final Component custom = v.customName();
        final String name = custom == null ? this.nextName() : PlainTextComponentSerializer.plainText().serialize(custom);
        final CitizenRecord rec = this.snapshot(v, country, name.isBlank() ? this.nextName() : name, System.currentTimeMillis());
        this.data().putCitizen(rec);
        this.applyIdentity(v, rec);
    }

    private boolean mayAdopt(final UUID country) {
        if (!this.cfg().adoptVillagers) {
            return false;
        }
        return !this.cfg().adoptRequiresHousing || this.data().population(country) < this.data().housing(country);
    }

    /** Adopts the untagged villagers standing in a loaded chunk owned by {@code country}. */
    public int adoptLoadedIn(final Chunk chunk, final UUID country) {
        int n = 0;
        for (final Entity e : chunk.getEntities()) {
            if (e instanceof Villager v && v.isValid() && this.data().citizen(v.getUniqueId()) == null
                && this.taggedCountry(v) == null && !this.plugin.construction().isHelper(v) && !foreignNpc(v)
                && this.mayAdopt(country)) {
                this.adopt(v, country);
                n++;
            }
        }
        return n;
    }

    /** A villager was born (breeding) — becomes a citizen of the land's country. */
    public void onBorn(final Villager v) {
        if (this.plugin.construction().isHelper(v) || foreignNpc(v)) {
            return;
        }
        final Location l = v.getLocation();
        this.plugin.kraje().getCountryAt(l).ifPresent(c -> {
            if (this.mayAdopt(c.id())) {
                this.adopt(v, c.id());
            }
        });
    }

    // ------------------------------------------------------------------ removal

    /** Citizen stops being one but stays in the world as a normal villager. */
    public void release(final CitizenRecord rec) {
        this.data().removeCitizen(rec.entityId());
        final Entity e = Bukkit.getEntity(rec.entityId());
        if (e instanceof Villager v && v.isValid()) {
            this.strip(v);
        }
        // unloaded: the entity is stripped as an orphan when its chunk loads
    }

    /** Citizen leaves the country for good (entity disappears now or when its chunk loads). */
    public void depart(final CitizenRecord rec) {
        this.data().removeCitizen(rec.entityId());
        final Entity e = Bukkit.getEntity(rec.entityId());
        if (e != null && e.isValid()) {
            if (this.cfg().effects) {
                e.getWorld().spawnParticle(Particle.POOF, e.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            }
            e.remove();
        } else {
            this.data().tombstone(rec.entityId(), System.currentTimeMillis() + TOMBSTONE_MILLIS);
        }
    }

    /** Moves a citizen to another country (record + loaded entity). */
    public void reassign(final CitizenRecord rec, final UUID country) {
        final CitizenRecord moved = rec.withCountry(country);
        this.data().putCitizen(moved);
        final Entity e = Bukkit.getEntity(rec.entityId());
        if (e instanceof Villager v && v.isValid()) {
            this.applyIdentity(v, moved);
        }
    }

    /** Country disbanded. */
    public void removeCountry(final UUID country) {
        final boolean remove = this.cfg().onDisband == LudnoscConfig.DisbandMode.REMOVE;
        for (final CitizenRecord rec : this.data().citizensOf(country)) {
            if (remove) {
                this.depart(rec);
            } else {
                this.release(rec);
            }
        }
        this.pending.remove(country);
    }

    /** Removes {@code n} citizens of a country, loaded ones first. Returns how many were removed. */
    public int removeSome(final UUID country, final int n) {
        final List<CitizenRecord> recs = new ArrayList<>(this.data().citizensOf(country));
        recs.sort(Comparator.comparing(r -> Bukkit.getEntity(r.entityId()) == null));
        int done = 0;
        for (final CitizenRecord r : recs) {
            if (done >= n) {
                break;
            }
            this.depart(r);
            done++;
        }
        return done;
    }

    // ------------------------------------------------------------------ conscription

    public Optional<LudnoscApi.Conscript> conscript(final UUID country, final Location near) {
        Villager best = null;
        double bestScore = Double.MAX_VALUE;
        for (final CitizenRecord rec : this.data().citizensOf(country)) {
            final Entity e = Bukkit.getEntity(rec.entityId());
            if (!(e instanceof Villager v) || !v.isValid() || !v.isAdult()) {
                continue;
            }
            double score = 0;
            if (near != null && near.getWorld() != null) {
                score = near.getWorld().equals(v.getWorld()) ? v.getLocation().distanceSquared(near) : 1e12;
            }
            // villagers that players traded with are taken last (protects trading halls)
            if (v.getVillagerExperience() > 1 || v.getVillagerLevel() > 1) {
                score += 1e15;
            }
            if (score < bestScore) {
                bestScore = score;
                best = v;
            }
        }
        if (best != null) {
            final Location loc = best.getLocation();
            final String profession = professionKey(best);
            this.data().removeCitizen(best.getUniqueId());
            best.remove();
            return Optional.of(new LudnoscApi.Conscript(country, profession, loc));
        }
        if (this.cfg().conscriptUnloaded) {
            final List<CitizenRecord> recs = new ArrayList<>(this.data().citizensOf(country));
            recs.sort(Comparator.comparing(r -> !r.pending()));
            for (final CitizenRecord rec : recs) {
                final World w = Bukkit.getWorld(rec.world());
                if (!rec.adult() || w == null || !rec.pending() && Bukkit.getEntity(rec.entityId()) != null) {
                    continue;
                }
                this.data().removeCitizen(rec.entityId());
                if (!rec.pending()) {
                    this.data().tombstone(rec.entityId(), System.currentTimeMillis() + TOMBSTONE_MILLIS);
                }
                return Optional.of(new LudnoscApi.Conscript(country, rec.profession(),
                    new Location(w, rec.x() + 0.5, rec.y(), rec.z() + 0.5)));
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ hired by a company (Firmy)

    /**
     * {@code LudnoscPlugin#hireWorker}: the nearest loaded <b>unemployed</b> adult citizen of {@code country} leaves the
     * population to work in a player company's manufactory (decision 2026-10-29: never a citizen who has work). Who is
     * unemployed comes from a fresh homes/work assignment of the country (citizens' life) and the job board; the villager
     * is removed like a conscript, its bed is freed at once and its savings stay with the citizens
     * ({@link LifeService#leftForWork}). Returns the profession key (for {@code returnCitizen} later), empty when there is
     * nobody to hire. Main thread.
     */
    public Optional<String> hireWorker(final UUID country, final Location near) {
        // production jobs first (computed now when the board is not cached yet, e.g. right after a start)
        final Set<UUID> onBoard = this.plugin.work() == null ? Set.of()
            : Set.copyOf(this.plugin.work().ensureBoard(country).workplaceOf().keySet());
        final Set<UUID> idle = new HashSet<>();
        final LifeService life = this.plugin.life();
        final boolean lifeOn = life != null && life.enabled();
        if (lifeOn) {
            idle.addAll(life.unemployedAdults(country));
        }
        final List<pl.medivalrpg.ludnosc.logic.WorkerHire.Candidate> candidates = new ArrayList<>();
        final Map<UUID, Villager> loaded = new HashMap<>();
        for (final CitizenRecord rec : this.data().citizensOf(country)) {
            if (rec.pending() || !rec.adult()) {
                continue;
            }
            final Entity e = Bukkit.getEntity(rec.entityId());
            if (!(e instanceof Villager v) || !v.isValid() || !v.isAdult()) {
                continue;
            }
            loaded.put(v.getUniqueId(), v);
            final boolean sameWorld = near != null && near.getWorld() != null && near.getWorld().equals(v.getWorld());
            final boolean employed = onBoard.contains(rec.entityId()) || lifeOn && !idle.contains(rec.entityId());
            final boolean busy = v.isTrading() || v.isLeashed() || v.isInsideVehicle();
            candidates.add(new pl.medivalrpg.ludnosc.logic.WorkerHire.Candidate(rec.entityId(), true, sameWorld,
                sameWorld ? v.getLocation().distanceSquared(near) : 0, v.getVillagerExperience() > 1 || v.getVillagerLevel() > 1,
                employed, busy));
        }
        final Optional<UUID> chosen = pl.medivalrpg.ludnosc.logic.WorkerHire.pick(candidates);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        final Villager v = loaded.get(chosen.get());
        final CitizenRecord rec = this.data().citizen(v.getUniqueId());
        final String profession = professionKey(v);
        this.data().removeCitizen(v.getUniqueId());
        if (this.cfg().effects) {
            v.getWorld().spawnParticle(Particle.POOF, v.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
        }
        v.remove();
        if (life != null) {
            life.leftForWork(v.getUniqueId(), country);
        }
        this.plugin.getLogger().info("Firmy: obywatel " + (rec == null ? v.getUniqueId() : rec.name()) + " (" + profession
            + ", bezrobotny) z kraju " + this.plugin.countryName(country) + " poszedł do pracy w manufakturze.");
        return Optional.of(profession);
    }

    // ------------------------------------------------------------------ chunk events

    /** Reconciles the registry with the villagers of a chunk whose entities just loaded. */
    public void onEntitiesLoad(final Chunk chunk, final List<Entity> entities) {
        this.materialise(chunk);
        final String world = chunk.getWorld().getName();
        final ChunkRef ref = new ChunkRef(world, chunk.getX(), chunk.getZ());
        final Set<UUID> present = new HashSet<>();
        UUID owner = null;
        boolean ownerKnown = false;
        for (final Entity e : entities) {
            if (!(e instanceof Villager v) || this.plugin.construction().isHelper(v) || foreignNpc(v)) {
                continue;
            }
            final UUID id = v.getUniqueId();
            if (this.data().takeTombstone(id)) {
                v.remove();
                continue;
            }
            final CitizenRecord rec = this.data().citizen(id);
            final UUID tagged = this.taggedCountry(v);
            if (rec != null) {
                present.add(id);
                if (this.plugin.kraje().getCountry(rec.countryId()).isEmpty()) {
                    this.release(rec);
                    continue;
                }
                if (rec.adult() && !v.isAdult()) {
                    this.growUp(v, rec.profession()); // grew up while its chunk was unloaded
                }
                final CitizenRecord updated = this.snapshot(v, rec.countryId(), rec.name(), rec.since());
                if (!updated.equals(rec)) {
                    this.data().putCitizen(updated);
                }
                if (!rec.countryId().equals(tagged)) {
                    this.applyIdentity(v, updated);
                }
                continue;
            }
            if (tagged != null) {
                if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(tagged)){
                    this.adopt(v,tagged);present.add(id);continue;
                }
                this.strip(v); // orphan (country gone / released / forgotten)
            }
            if (!ownerKnown) {
                owner = this.plugin.kraje().getCountryAt(world, chunk.getX(), chunk.getZ()).map(KrajInfo::id).orElse(null);
                ownerKnown = true;
            }
            if (owner != null && this.mayAdopt(owner)) {
                this.adopt(v, owner);
                present.add(id);
            }
        }
        // records that should be here but are not (and are not loaded elsewhere)
        for (final CitizenRecord rec : this.data().citizensIn(ref)) {
            if(this.plugin.royalRebuild()!=null&&this.plugin.royalRebuild().blocks(rec.countryId()))continue;
            if (rec.pending() || present.contains(rec.entityId()) || Bukkit.getEntity(rec.entityId()) != null) {
                continue;
            }
            final int missed = rec.missed() + 1;
            if (missed >= this.cfg().forgetAfterMissedLoads) {
                this.data().removeCitizen(rec.entityId());
            } else {
                this.data().putCitizen(rec.withMissed(missed));
            }
        }
    }

    /** Stores the last position/profession of citizens whose chunk unloads. */
    public void onEntitiesUnload(final List<Entity> entities) {
        for (final Entity e : entities) {
            if (e instanceof Villager v) {
                final CitizenRecord rec = this.data().citizen(v.getUniqueId());
                if (rec != null) {
                    final CitizenRecord updated = this.snapshot(v, rec.countryId(), rec.name(), rec.since());
                    if (!updated.equals(rec)) {
                        this.data().putCitizen(updated);
                    }
                }
            }
        }
    }

    /** Periodic: refreshes records of loaded citizens (map lookups only, no scanning). */
    public void refreshLoaded() {
        for (final CitizenRecord rec : List.copyOf(this.data().allCitizens())) {
            final Entity e = Bukkit.getEntity(rec.entityId());
            if (e instanceof Villager v && v.isValid()) {
                final CitizenRecord updated = this.snapshot(v, rec.countryId(), rec.name(), rec.since());
                if (!updated.equals(rec)) {
                    this.data().putCitizen(updated);
                }
            }
        }
    }

    /** Profession changed (vanilla job site, curing...). */
    public void onCareerChange(final Villager v, final Villager.Profession profession) {
        final CitizenRecord rec = this.data().citizen(v.getUniqueId());
        if (rec != null) {
            final NamespacedKey key = Registry.VILLAGER_PROFESSION.getKey(profession);
            final Location l = v.getLocation();
            this.data().putCitizen(rec.withState(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                key == null ? "minecraft:none" : key.toString(), v.isAdult()));
        }
    }

    /** Entity gone for good (death, transformation into a zombie villager/witch, plugin removal...). */
    public void onGone(final UUID entity) {
        this.data().removeCitizen(entity);
    }

    /**
     * Chunk changed owner. Citizens of the old owner in that chunk: to another country → {@code on-conquest}
     * (switch / release / leave); to wilderness → {@code on-unclaim} (release / keep / leave). Free villagers in a
     * newly claimed loaded chunk are adopted.
     */
    public void chunkChanged(final String world, final int cx, final int cz, final UUID oldOwner, final UUID newOwner) {
        final World w = Bukkit.getWorld(world);
        final boolean loaded = w != null && w.isChunkLoaded(cx, cz);
        if (oldOwner != null) {
            final Map<UUID, CitizenRecord> affected = new HashMap<>();
            for (final CitizenRecord r : this.data().citizensIn(new ChunkRef(world, cx, cz))) {
                if (r.countryId().equals(oldOwner)) {
                    affected.put(r.entityId(), r);
                }
            }
            if (loaded) {
                for (final Entity e : w.getChunkAt(cx, cz).getEntities()) {
                    final CitizenRecord r = this.data().citizen(e.getUniqueId());
                    if (r != null && r.countryId().equals(oldOwner)) {
                        affected.put(r.entityId(), r);
                    }
                }
            }
            for (final CitizenRecord r : affected.values()) {
                if (newOwner != null) {
                    switch (this.cfg().onConquest) {
                        case SWITCH -> this.reassign(r, newOwner);
                        case RELEASE -> this.release(r);
                        case LEAVE -> this.depart(r);
                    }
                } else {
                    switch (this.cfg().onUnclaim) {
                        case RELEASE -> this.release(r);
                        case LEAVE -> this.depart(r);
                        case KEEP -> {
                        }
                    }
                }
            }
        }
        if (newOwner != null && loaded) {
            this.adoptLoadedIn(w.getChunkAt(cx, cz), newOwner);
        }
    }

    /** On enable: drops records of countries that no longer exist (their entities are stripped when loaded). */
    public void dropDeadCountries() {
        for (final CitizenRecord rec : List.copyOf(this.data().allCitizens())) {
            if (this.plugin.kraje().getCountry(rec.countryId()).isEmpty()) {
                this.data().removeCitizen(rec.entityId());
            }
        }
        this.data().purgeTombstones(System.currentTimeMillis());
    }
}
